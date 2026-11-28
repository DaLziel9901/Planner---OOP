package com.example.planner.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OpenAiCompatiblePlanProvider implements PlanGenerationProvider {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatiblePlanProvider.class);
    private static final int MAX_BYTES = 512 * 1024;
    private static final String FLAT_INSTRUCTIONS = """
            Return {"nodes":[...]} in draft order. Each node has id, parentId, type, title, details, date and endDate.
            Use short positive integer IDs. Only the single Plan has a null parentId.
            SubPlan belongs to Plan or SubPlan; Task belongs to Plan or SubPlan; SubTask belongs to Task.
            Use details for the Plan goal or other nodes' description. Use date for the start date, or the scheduled date for a SubTask.
            Use null for unknown details or dates. SubTask endDate is always null.
            """;
    private static final Set<String> ERROR_TYPES = Set.of(
            "invalid_request_error", "authentication_error", "permission_error",
            "rate_limit_error", "server_error", "api_error", "insufficient_quota");
    private static final Set<String> ERROR_CODES = Set.of(
            "invalid_api_key", "invalid_request", "invalid_value", "invalid_json",
            "json_validate_failed", "context_length_exceeded", "rate_limit_exceeded",
            "insufficient_quota", "model_not_found", "model_decommissioned",
            "model_permission_blocked", "content_policy_violation", "internal_server_error");
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final boolean flatSchema;
    private final JsonNode responseFormat;
    private final HttpClient client;

    @Autowired
    public OpenAiCompatiblePlanProvider(ObjectMapper mapper,
                                        @Value("${planner.ai.base-url:${AI_BASE_URL:}}") String baseUrl,
                                        @Value("${planner.ai.model:${AI_MODEL:}}") String model,
                                        @Value("${planner.ai.api-key:${AI_API_KEY:}}") String apiKey,
                                        @Value("${planner.ai.strict-schema:${AI_STRICT_SCHEMA:false}}") boolean strictSchema,
                                        @Value("${planner.ai.flat-schema:${AI_FLAT_SCHEMA:false}}") boolean flatSchema) {
        this(mapper, baseUrl, model, apiKey, strictSchema, flatSchema,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    OpenAiCompatiblePlanProvider(ObjectMapper mapper, String baseUrl, String model, String apiKey,
                                 boolean strictSchema, boolean flatSchema, HttpClient client) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.flatSchema = flatSchema;
        this.responseFormat = mapper.valueToTree(flatSchema ? flatResponseFormat(mapper)
                : strictSchema ? PlanDraftSchema.RESPONSE_FORMAT : Map.of("type", "json_object"));
        this.client = client;
    }

    @Override
    public String generate(String instructions, String input) {
        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
            throw new IllegalStateException("Chưa cấu hình dịch vụ tạo kế hoạch.");
        }
        HttpRequest request = buildRequest(instructions, input);
        // Never retry automatically. A failed or timed-out request may already have incurred a cost.
        var body = new ByteArrayOutputStream();
        var flight = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArrayConsumer(chunk ->
                chunk.ifPresent(bytes -> {
                    if (bytes.length > MAX_BYTES - body.size()) {
                        throw new IllegalStateException("Response too large");
                    }
                    body.writeBytes(bytes);
                })));
        try {
            var response = flight.get(35, TimeUnit.SECONDS);
            if (response.statusCode() != 200) {
                logHttpFailure(response.statusCode(), body.toByteArray());
                throw new IllegalStateException("Dịch vụ tạo kế hoạch trả về lỗi HTTP.");
            }
            String content = readContent(body.toByteArray());
            return flatSchema ? FlatPlanTreeBuilder.toNestedJson(mapper, content) : content;
        } catch (InterruptedException failure) {
            flight.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Yêu cầu tạo kế hoạch đã bị gián đoạn.");
        } catch (ExecutionException | TimeoutException failure) {
            flight.cancel(true);
            throw new IllegalStateException("Không kết nối được dịch vụ tạo kế hoạch hoặc yêu cầu đã quá hạn.");
        }
    }

    private void logHttpFailure(int status, byte[] response) {
        String type = "absent";
        String code = "absent";
        try {
            var error = mapper.readTree(response).path("error");
            type = safeErrorIdentifier(error.path("type").asText(""), ERROR_TYPES);
            code = safeErrorIdentifier(error.path("code").asText(""), ERROR_CODES);
        } catch (RuntimeException ignored) {
            // Error responses from proxies can be empty or use a different format.
        }
        LOG.warn("AI provider HTTP error: status={} type={} code={}", status, type, code);
    }

    private static String safeErrorIdentifier(String value, Set<String> allowed) {
        if (value.isEmpty()) {
            return "absent";
        }
        return allowed.contains(value) ? value : "unrecognized";
    }

    private String readContent(byte[] response) {
        try {
            var choice = mapper.readTree(response).path("choices").path(0);
            var finishReason = choice.path("finish_reason");
            var message = choice.path("message");
            var content = message.path("content");
            if ((finishReason.isMissingNode() || "stop".equals(finishReason.asText()))
                    && !message.hasNonNull("refusal") && content.isTextual() && !content.asString().isBlank()) {
                return content.asString();
            }
        } catch (RuntimeException failure) {
            // Keep provider response bodies out of logs and error pages.
        }
        throw new IllegalStateException("Phản hồi từ dịch vụ tạo kế hoạch không hợp lệ.");
    }

    private HttpRequest buildRequest(String instructions, String input) {
        try {
            var endpoint = URI.create(baseUrl.replaceFirst("/+$", "") + "/chat/completions");
            if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            String system = flatSchema
                    ? instructions.replaceAll("(?m)^(?:Plan|SubPlan|Task|SubTask) fields: [^\\r\\n]+\\R", "")
                            + "\n" + FLAT_INSTRUCTIONS
                    : instructions;
            var body = Map.of("model", model, "messages", List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", input)),
                    "response_format", responseFormat);
            byte[] payload = mapper.writeValueAsBytes(body);
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(35))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            logRequestMetadata(request.uri(), responseFormat, payload.length);
            return request;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Cấu hình dịch vụ tạo kế hoạch không hợp lệ.");
        }
    }

    private static Map<String, Object> flatResponseFormat(ObjectMapper mapper) {
        try (var input = new ClassPathResource("ai/planner-flat.schema.json").getInputStream()) {
            return Map.of("type", "json_schema", "json_schema",
                    Map.of("name", "planner_flat_draft", "strict", true, "schema", mapper.readTree(input)));
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not load the flat Planner schema.", failure);
        }
    }

    private void logRequestMetadata(URI endpoint, JsonNode format, int requestBytes) {
        if (!LOG.isInfoEnabled()) {
            return;
        }
        var structured = format.path("json_schema");
        var schema = structured.path("schema");
        String safeModel = model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}") && !model.contains(apiKey)
                ? model : "<redacted>";
        // Custom endpoint paths can contain credentials. Show the full path for standard API routes only.
        String path = endpoint.getRawPath();
        String safeEndpoint = endpoint.toASCIIString().contains(apiKey) ? "<redacted>"
                : endpoint.getScheme() + "://" + endpoint.getRawAuthority()
                + (path.matches("(?:/(?:openai|v[0-9]+|chat|completions))+") ? path : "/<redacted>");
        LOG.info("AI provider request: model={} endpoint={} response_format.type={} strict={} schema_bytes={} request_bytes={}",
                safeModel, safeEndpoint, format.path("type").asText(),
                structured.path("strict").asBoolean(false),
                schema.isMissingNode() ? 0 : mapper.writeValueAsBytes(schema).length, requestBytes);
    }
}
