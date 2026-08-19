/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.web3.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestRestClient
{
    @Test
    public void testGetEncodesQueryAndParsesJson()
            throws Exception
    {
        AtomicReference<String> requestTarget = new AtomicReference<>();
        try (TestingServer server = createServer(exchange -> {
            requestTarget.set(exchange.getRequestURI().toString());
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            respond(exchange, 200, "{\"value\":42}");
        })) {
            RestClient client = client(server, 1_024, 1_024);
            RestRemoteRequest request = new RestRemoteRequest(
                    "GET",
                    "/v1/transactions",
                    Map.of("account", List.of("0x1 / value"), "start", List.of("10")),
                    Optional.empty());

            JsonNode response = client.execute(request).join();

            assertThat(response.path("value").longValue()).isEqualTo(42);
            assertThat(requestTarget.get())
                    .contains("/v1/transactions?")
                    .contains("account=0x1%20%2F%20value")
                    .contains("start=10");
        }
    }

    @Test
    public void testPostSendsBoundedJsonBody()
            throws Exception
    {
        AtomicReference<String> body = new AtomicReference<>();
        try (TestingServer server = createServer(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/json");
            respond(exchange, 200, "true");
        })) {
            RestClient client = client(server, 1_024, 1_024);
            RestRemoteRequest request = new RestRemoteRequest(
                    "POST",
                    "/v1/view",
                    Map.of(),
                    Optional.of(JsonNodeFactory.instance.objectNode().put("function", "0x1::test")));

            assertThat(client.execute(request).join().booleanValue()).isTrue();
            assertThat(body.get()).isEqualTo("{\"function\":\"0x1::test\"}");
        }
    }

    @Test
    public void testHttpFailurePreservesBoundedRetryAfter()
            throws Exception
    {
        try (TestingServer server = createServer(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "3");
            respond(exchange, 429, "throttled response is not parsed");
        })) {
            RestClient client = client(server, 1_024, 1_024);
            CompletableFuture<JsonNode> result = client.execute(new RestRemoteRequest("GET", "/v1", Map.of(), Optional.empty()));

            assertThatThrownBy(result::join)
                    .hasRootCauseInstanceOf(RemoteHttpException.class)
                    .rootCause()
                    .extracting(value -> ((RemoteHttpException) value).statusCode(), value -> ((RemoteHttpException) value).retryAfter())
                    .containsExactly(429, Optional.of("3"));
        }
    }

    @Test
    public void testRejectsMalformedAndOversizedResponses()
            throws Exception
    {
        try (TestingServer malformed = createServer(exchange -> respond(exchange, 200, "not-json"));
                TestingServer oversized = createServer(exchange -> respond(exchange, 200, "{\"value\":42}"))) {
            assertThatThrownBy(() -> client(malformed, 1_024, 1_024)
                    .execute(new RestRemoteRequest("GET", "/v1", Map.of(), Optional.empty()))
                    .join())
                    .hasStackTraceContaining("REST endpoint returned malformed JSON");
            assertThatThrownBy(() -> client(oversized, 1_024, 4)
                    .execute(new RestRemoteRequest("GET", "/v1", Map.of(), Optional.empty()))
                    .join())
                    .hasRootCauseMessage("REST response exceeds maximumResponseBytes");
        }
    }

    @Test
    public void testCancellationCompletesPromptly()
            throws Exception
    {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TestingServer server = createServer(exchange -> {
            received.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        })) {
            CompletableFuture<JsonNode> result = client(server, 1_024, 1_024)
                    .execute(new RestRemoteRequest("GET", "/v1", Map.of(), Optional.empty()));
            assertThat(received.await(10, TimeUnit.SECONDS)).isTrue();

            boolean cancelled = result.cancel(true);
            release.countDown();
            assertThat(cancelled).isTrue();
            assertThat(result).isCompletedExceptionally();
        }
    }

    @Test
    public void testRejectsEndpointWithCredentialsWithoutEchoingIt()
    {
        String secret = "secret-token";
        assertThatThrownBy(() -> new RestClient(
                HttpClient.newHttpClient(),
                URI.create("https://" + secret + "@example.com"),
                Duration.ofSeconds(1),
                1_024,
                1_024))
                .hasMessage("REST endpoint must be an HTTP(S) origin without credentials, path, query, or fragment")
                .hasMessageNotContaining(secret);
    }

    private static RestClient client(TestingServer server, int maximumRequestBytes, int maximumResponseBytes)
    {
        return new RestClient(
                HttpClient.newHttpClient(),
                server.endpoint(),
                Duration.ofSeconds(2),
                maximumRequestBytes,
                maximumResponseBytes);
    }

    private static TestingServer createServer(ExchangeHandler handler)
            throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            }
            finally {
                exchange.close();
            }
        });
        server.start();
        return new TestingServer(server);
    }

    private static void respond(HttpExchange exchange, int statusCode, String value)
            throws IOException
    {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
    }

    @FunctionalInterface
    private interface ExchangeHandler
    {
        void handle(HttpExchange exchange)
                throws IOException;
    }

    private record TestingServer(HttpServer server)
            implements AutoCloseable
    {
        private URI endpoint()
        {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close()
        {
            server.stop(0);
        }
    }
}
