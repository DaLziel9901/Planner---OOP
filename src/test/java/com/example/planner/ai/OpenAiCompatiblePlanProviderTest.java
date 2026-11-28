package com.example.planner.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiCompatiblePlanProviderTest {
    private final HttpClient http = mock(HttpClient.class);
    private final JsonMapper mapper = new JsonMapper();
    private final OpenAiCompatiblePlanProvider provider = new OpenAiCompatiblePlanProvider(
            mapper, "https://example.test/v1/", "example-model", "test-fixture-key", false, false, http);

    @Test
    void readsMessageFromCompatibleEndpoint() throws Exception {
        reply(200, """
                {"choices":[{"message":{"content":"{}"}}]}
                """);
        assertEquals("{}", provider.generate("instructions", "input"));
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(), any());
        assertEquals("https://example.test/v1/chat/completions", request.getValue().uri().toString());
        assertEquals("POST", request.getValue().method());
        var format = requestBody(request.getValue()).path("response_format");
        assertEquals("json_object", format.path("type").asString());
        assertEquals(1, format.size());
    }

    @Test
    void sendsStrictSchemaAndReturnsDraftWithNestedSubPlans(CapturedOutput captured) throws Exception {
        var strict = new OpenAiCompatiblePlanProvider(
                mapper, "https://api.groq.com/openai/v1", "openai/gpt-oss-20b", "test-fixture-key", true, false, http);
        String json = """
                {"title":"Study","goal":null,"startDate":null,"endDate":null,"tasks":[],"subPlans":[
                  {"title":"Phase","description":null,"startDate":null,"endDate":null,"tasks":[],"subPlans":[
                    {"title":"Nested","description":null,"startDate":null,"endDate":null,"subPlans":[],"tasks":[
                      {"title":"Practice","description":null,"startDate":null,"endDate":null,"subTasks":[
                        {"title":"Solve one exercise","description":null,"scheduledDate":null}
                      ]}
                    ]}
                  ]}
                ]}
                """;
        reply(200, """
                {"choices":[{"finish_reason":"stop","message":{"content":%s}}]}
                """.formatted(mapper.writeValueAsString(json)));
        var draft = new PlanDraftService(strict, mapper)
                .generate("Ôn tập Java cơ bản", "Beginner", 7, "Use a few practice sessions.");
        assertEquals("Solve one exercise", draft.subPlans().get(0).subPlans().get(0)
                .tasks().get(0).subTasks().get(0).title());

        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(), any());
        byte[] wire = requestBytes(request.getValue());
        var format = mapper.readTree(wire).path("response_format");
        assertEquals("json_schema", format.path("type").asString());
        var output = format.path("json_schema");
        assertEquals("planner_plan_draft", output.path("name").asString());
        assertTrue(output.path("strict").asBoolean());
        var root = output.path("schema");
        var phase = root.path("$defs").path("subPlan");
        var task = root.path("$defs").path("task");
        var leaf = root.path("$defs").path("subTask");
        assertClosedObject(root, "title", "goal", "startDate", "endDate", "subPlans", "tasks");
        assertClosedObject(phase, "title", "description", "startDate", "endDate", "subPlans", "tasks");
        assertClosedObject(task, "title", "description", "startDate", "endDate", "subTasks");
        assertClosedObject(leaf, "title", "description", "scheduledDate");
        assertArrayReference(root, "subPlans", "subPlan");
        assertArrayReference(root, "tasks", "task");
        assertArrayReference(phase, "subPlans", "subPlan");
        assertArrayReference(phase, "tasks", "task");
        assertArrayReference(task, "subTasks", "subTask");
        assertTrue(captured.getAll().contains("model=openai/gpt-oss-20b"
                + " endpoint=https://api.groq.com/openai/v1/chat/completions response_format.type=json_schema strict=true"
                + " schema_bytes=" + mapper.writeValueAsBytes(root).length + " request_bytes=" + wire.length));
        assertFalse(captured.getAll().contains("Ôn tập Java cơ bản"));
        assertFalse(captured.getAll().contains("test-fixture-key"));
        if (Boolean.getBoolean("planner.ai.diagnostic.export")) {
            Files.write(Path.of("target/planner-ai-diagnostic-request.json"), wire);
        }
    }

    @Test
    void springBindsStrictSchemaFromEnvironmentAndHonorsExplicitOverride() {
        assertFalse(boundStrictSchema(Map.of(), Map.of()));
        assertTrue(boundStrictSchema(Map.of("AI_STRICT_SCHEMA", "true"), Map.of()));
        assertFalse(boundStrictSchema(Map.of("AI_STRICT_SCHEMA", "true"),
                Map.of("planner.ai.strict-schema", "false")));
    }

    @Test
    void flatProviderSendsStrictFlatSchemaAndReturnsExistingEditableTree() throws Exception {
        var flat = new OpenAiCompatiblePlanProvider(
                mapper, "https://example.test/v1/", "example-model", "test-fixture-key", false, true, http);
        String output = FlatPlanTreeBuilderTest.draft(List.of(
                FlatPlanTreeBuilderTest.node(1, "null", "Plan", "Flat plan"),
                FlatPlanTreeBuilderTest.node(2, "1", "Task", "Practice"),
                FlatPlanTreeBuilderTest.node(3, "2", "SubTask", "Exercise")));
        reply(200, """
                {"choices":[{"finish_reason":"stop","message":{"content":%s}}]}
                """.formatted(mapper.writeValueAsString(output)));
        var drafts = new PlanDraftService(flat, mapper);
        var editable = drafts.generateEditable("Study", "Beginner", 7, "");
        assertEquals("Flat plan", editable.tree().title());
        assertEquals("Exercise", drafts.parse(editable.json()).tasks().get(0).subTasks().get(0).title());
        assertFalse(mapper.readTree(editable.json()).has("nodes"));

        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(), any());
        var body = requestBody(request.getValue());
        var format = body.path("response_format");
        assertEquals("json_schema", format.path("type").asString());
        var strict = format.path("json_schema");
        assertEquals("planner_flat_draft", strict.path("name").asString());
        assertTrue(strict.path("strict").asBoolean());
        assertTrue(strict.path("schema").path("properties").has("nodes"));
        String instructions = body.path("messages").path(0).path("content").asString();
        assertTrue(instructions.contains("Return {\"nodes\":[...]}"));
        assertFalse(instructions.contains("SubPlan fields:"));
    }

    private boolean boundStrictSchema(Map<String, Object> environment, Map<String, Object> overrides) {
        try (var context = new AnnotationConfigApplicationContext()) {
            var sources = context.getEnvironment().getPropertySources();
            var systemEnvironment = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
            sources.replace(systemEnvironment, new SystemEnvironmentPropertySource(systemEnvironment, environment));
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            sources.addFirst(new MapPropertySource("diagnosticOverrides", overrides));
            context.registerBean(JsonMapper.class, () -> mapper);
            context.register(OpenAiCompatiblePlanProvider.class);
            context.refresh();
            var format = (JsonNode) ReflectionTestUtils.getField(
                    context.getBean(OpenAiCompatiblePlanProvider.class), "responseFormat");
            return format.path("json_schema").path("strict").asBoolean(false);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\" \"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"refusal\":\"private\",\"content\":\"{}\"}}]}",
            "{\"choices\":[]}",
            "{\"choices\":["
    })
    void rejectsIncompleteResponse(String response) {
        reply(200, response);
        var failure = assertThrows(IllegalStateException.class, () -> provider.generate("instructions", "input"));
        assertEquals("Phản hồi từ dịch vụ tạo kế hoạch không hợp lệ.", failure.getMessage());
        verify(http, times(1)).sendAsync(any(), any());
    }

    @Test
    void returnsSafeErrorForHttpFailureWithoutRetry(CapturedOutput output) {
        reply(429, "private provider error body");
        var failure = assertThrows(IllegalStateException.class, () -> provider.generate("instructions", "input"));
        assertFalse(failure.getMessage().contains("private provider error body"));
        assertTrue(output.getAll().contains("status=429 type=absent code=absent"));
        assertFalse(output.getAll().contains("private provider error body"));
        verify(http, times(1)).sendAsync(any(), any());
    }

    @Test
    void logsKnownIdentifiersAndSuppressesProviderContent(CapturedOutput output) {
        reply(400, """
                {"error":{"type":"invalid_request_error","code":"json_validate_failed",
                "message":"private message","failed_generation":"private completion"}}
                """);
        var failure = assertThrows(IllegalStateException.class,
                () -> provider.generate("private instructions", "private goal"));
        assertEquals("Dịch vụ tạo kế hoạch trả về lỗi HTTP.", failure.getMessage());
        assertTrue(output.getAll().contains("status=400 type=invalid_request_error code=json_validate_failed"));
        for (String secret : List.of("private message", "private completion", "private instructions",
                "private goal", "test-fixture-key", "Authorization")) {
            assertFalse(output.getAll().contains(secret));
        }
    }

    @Test
    void redactsUnknownErrorIdentifiers(CapturedOutput output) {
        reply(400, """
                {"error":{"type":"personal_goal","code":"gsk_fixture_token"}}
                """);
        assertThrows(IllegalStateException.class, () -> provider.generate("instructions", "input"));
        assertTrue(output.getAll().contains("status=400 type=unrecognized code=unrecognized"));
        assertFalse(output.getAll().contains("personal_goal"));
        assertFalse(output.getAll().contains("gsk_fixture_token"));
    }

    @Test
    void rejectsMalformedEnvelope() {
        reply(200, "{not JSON");
        assertThrows(IllegalStateException.class, () -> provider.generate("instructions", "input"));
        verify(http, times(1)).sendAsync(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelsTimedOutRequestWithoutRetry() throws Exception {
        CompletableFuture<HttpResponse<Void>> future = mock(CompletableFuture.class);
        when(future.get(35, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        when(http.<Void>sendAsync(any(), any())).thenReturn(future);
        assertThrows(IllegalStateException.class, () -> provider.generate("instructions", "input"));
        verify(future).cancel(true);
        verify(http, times(1)).sendAsync(any(), any());
    }

    private static void assertClosedObject(JsonNode schema, String... fields) {
        assertEquals("object", schema.path("type").asString());
        assertEquals(false, schema.path("additionalProperties").asBoolean(true));
        var properties = schema.path("properties");
        assertEquals(Set.of(fields), Set.copyOf(properties.propertyNames()));
        var required = new HashSet<String>();
        schema.path("required").forEach(field -> required.add(field.asString()));
        assertEquals(Set.of(fields), required);
        assertEquals("string", properties.path("title").path("type").asString());
        for (String field : fields) {
            if (Set.of("goal", "description", "startDate", "endDate", "scheduledDate").contains(field)) {
                var nullable = properties.path(field).path("type");
                assertEquals(2, nullable.size());
                assertEquals("string", nullable.path(0).asString());
                assertEquals("null", nullable.path(1).asString());
            }
        }
    }

    private static void assertArrayReference(JsonNode parent, String field, String definition) {
        var array = parent.path("properties").path(field);
        assertEquals("array", array.path("type").asString());
        assertEquals("#/$defs/" + definition, array.path("items").path("$ref").asString());
    }

    private JsonNode requestBody(HttpRequest request) throws Exception {
        return mapper.readTree(requestBytes(request));
    }

    private static byte[] requestBytes(HttpRequest request) throws Exception {
        var body = new ByteArrayOutputStream();
        var complete = new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                body.writeBytes(bytes);
            }

            public void onError(Throwable error) {
                complete.completeExceptionally(error);
            }

            public void onComplete() {
                complete.complete(body.toByteArray());
            }
        });
        return complete.get(5, TimeUnit.SECONDS);
    }

    private void reply(int status, String body) {
        when(http.<Void>sendAsync(any(), any())).thenAnswer(call -> {
            HttpResponse.BodyHandler<Void> handler = call.getArgument(1);
            var subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
            subscriber.onSubscribe(mock(Flow.Subscription.class));
            subscriber.onNext(List.of(ByteBuffer.wrap(body.getBytes(StandardCharsets.UTF_8))));
            subscriber.onComplete();
            @SuppressWarnings("unchecked")
            HttpResponse<Void> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(status);
            return subscriber.getBody().thenApply(ignored -> response).toCompletableFuture();
        });
    }
}
