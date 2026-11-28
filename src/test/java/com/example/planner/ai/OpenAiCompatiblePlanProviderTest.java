package com.example.planner.ai;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenAiCompatiblePlanProviderTest {
    private final HttpClient http = mock(HttpClient.class);
    private final OpenAiCompatiblePlanProvider provider = new OpenAiCompatiblePlanProvider(
            new JsonMapper(), "https://example.test/v1/", "example-model", "test-fixture-key", http);

    @Test
    void readsMessageFromCompatibleEndpoint() {
        reply(200, """
                {"choices":[{"message":{"content":"{}"}}]}
                """);
        assertEquals("{}", provider.generate("instructions", "input"));
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(), any());
        assertEquals("https://example.test/v1/chat/completions", request.getValue().uri().toString());
        assertEquals("POST", request.getValue().method());
    }

    @Test
    void returnsSafeErrorForHttpFailureWithoutRetry() {
        reply(429, "private provider error body");
        var failure = assertThrows(IllegalStateException.class, () -> provider.generate("instructions", "input"));
        assertFalse(failure.getMessage().contains("private provider error body"));
        verify(http, times(1)).sendAsync(any(), any());
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
