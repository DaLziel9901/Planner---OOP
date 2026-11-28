package com.example.planner.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OpenAiCompatiblePlanProvider implements PlanGenerationProvider {
    private static final int MAX_BYTES = 512 * 1024;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final HttpClient client;

    @Autowired
    public OpenAiCompatiblePlanProvider(ObjectMapper mapper,
                                        @Value("${planner.ai.base-url:${AI_BASE_URL:}}") String baseUrl,
                                        @Value("${planner.ai.model:${AI_MODEL:}}") String model,
                                        @Value("${planner.ai.api-key:${AI_API_KEY:}}") String apiKey) {
        this(mapper, baseUrl, model, apiKey,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    OpenAiCompatiblePlanProvider(ObjectMapper mapper, String baseUrl, String model, String apiKey, HttpClient client) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
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
                throw new IllegalStateException("Dịch vụ tạo kế hoạch trả về lỗi HTTP.");
            }
            return readContent(body.toByteArray());
        } catch (InterruptedException failure) {
            flight.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Yêu cầu tạo kế hoạch đã bị gián đoạn.");
        } catch (ExecutionException | TimeoutException failure) {
            flight.cancel(true);
            throw new IllegalStateException("Không kết nối được dịch vụ tạo kế hoạch hoặc yêu cầu đã quá hạn.");
        }
    }

    private String readContent(byte[] response) {
        try {
            var content = mapper.readTree(response).path("choices").path(0).path("message").path("content");
            if (content.isTextual()) {
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
            var body = Map.of("model", model, "messages", List.of(
                    Map.of("role", "system", "content", instructions),
                    Map.of("role", "user", "content", input)),
                    "response_format", Map.of("type", "json_object"));
            return HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(35))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Cấu hình dịch vụ tạo kế hoạch không hợp lệ.");
        }
    }
}
