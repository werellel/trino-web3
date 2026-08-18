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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestRemoteExecutionRuntime
{
    private HttpServer primary;
    private HttpServer fallback;
    private final AtomicInteger primaryRequests = new AtomicInteger();
    private final AtomicInteger fallbackRequests = new AtomicInteger();

    @BeforeEach
    public void setUp()
            throws IOException
    {
        primary = server(this::handlePrimary);
        fallback = server(this::handleFallback);
    }

    @AfterEach
    public void tearDown()
    {
        primary.stop(0);
        fallback.stop(0);
    }

    @Test
    public void testFailsOverAfterTemporaryPrimaryFailure()
    {
        try (RemoteExecutionRuntime runtime = runtime()) {
            RemoteResult result = runtime.execute(new RemoteOperation("eth_chainId", List.of())).join();

            assertThat(result.value().asText()).isEqualTo("0x1");
            assertThat(result.providerName()).isEqualTo("fallback");
            assertThat(primaryRequests).hasValue(1);
            assertThat(fallbackRequests).hasValue(1);
            assertThat(runtime.metrics().retryCount()).isEqualTo(1);
            assertThat(runtime.metrics().failoverCount()).isEqualTo(1);
        }
    }

    @Test
    public void testSingleFlightSharesIdenticalInFlightOperation()
            throws Exception
    {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        primary.removeContext("/");
        primary.createContext("/", exchange -> {
            primaryRequests.incrementAndGet();
            requestStarted.countDown();
            try {
                if (!proceed.await(1, TimeUnit.SECONDS)) {
                    throw new IOException("test request was not released");
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            writeJson(exchange, 200, "[{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":\"0x1\"}]");
        });
        try (RemoteExecutionRuntime runtime = runtime()) {
            RemoteOperation operation = new RemoteOperation("eth_chainId", List.of());
            var first = runtime.execute(operation);
            assertThat(requestStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var second = runtime.execute(operation);
            proceed.countDown();

            assertThat(first.join().value().asText()).isEqualTo("0x1");
            assertThat(second.join().value().asText()).isEqualTo("0x1");
            assertThat(primaryRequests).hasValue(1);
        }
    }

    @Test
    public void testCancellingOneSubscriberDoesNotCancelSharedOperation()
            throws Exception
    {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        primary.removeContext("/");
        primary.createContext("/", exchange -> {
            primaryRequests.incrementAndGet();
            requestStarted.countDown();
            try {
                if (!proceed.await(1, TimeUnit.SECONDS)) {
                    throw new IOException("test request was not released");
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            writeJson(exchange, 200, "[{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":\"0x1\"}]");
        });
        try (RemoteExecutionRuntime runtime = runtime()) {
            RemoteOperation operation = new RemoteOperation("eth_chainId", List.of());
            var first = runtime.execute(operation);
            var second = runtime.execute(operation);
            assertThat(requestStarted.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(first.cancel(true)).isTrue();
            proceed.countDown();

            assertThat(second.join().value().asText()).isEqualTo("0x1");
            assertThat(primaryRequests).hasValue(1);
        }
    }

    @Test
    public void testNewSubscriberDoesNotJoinCancelledInFlightOperation()
            throws Exception
    {
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRequest = new CountDownLatch(1);
        primary.removeContext("/");
        primary.createContext("/", exchange -> {
            int requestNumber = primaryRequests.incrementAndGet();
            if (requestNumber == 1) {
                firstRequestStarted.countDown();
                try {
                    if (!releaseFirstRequest.await(1, TimeUnit.SECONDS)) {
                        throw new IOException("test request was not released");
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            writeJson(exchange, 200, "[{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":\"0x1\"}]");
        });
        try (RemoteExecutionRuntime runtime = runtime()) {
            RemoteOperation operation = new RemoteOperation("eth_chainId", List.of());
            var cancelled = runtime.execute(operation);
            assertThat(firstRequestStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelled.cancel(true)).isTrue();

            var replacement = runtime.execute(operation);
            releaseFirstRequest.countDown();

            assertThat(replacement.join().value().asText()).isEqualTo("0x1");
            assertThat(primaryRequests).hasValue(2);
        }
        finally {
            releaseFirstRequest.countDown();
        }
    }

    @Test
    public void testLogicalBatchCombinesOperationsIntoOneWireRequest()
    {
        primary.removeContext("/");
        primary.createContext("/", exchange -> {
            primaryRequests.incrementAndGet();
            writeJson(exchange, 200, "[" +
                    "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":\"0x1\"}," +
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x2\"}]");
        });
        try (RemoteExecutionRuntime runtime = runtime()) {
            List<RemoteResult> results = runtime.executeBatch(List.of(
                    new RemoteOperation("eth_getBlockByNumber", List.of("0x1", false)),
                    new RemoteOperation("eth_getBlockByNumber", List.of("0x2", false)))).join();

            assertThat(results).extracting(result -> result.value().asText()).containsExactly("0x1", "0x2");
            assertThat(primaryRequests).hasValue(1);
        }
    }

    @Test
    public void testCancelledQueuedOperationReleasesQueueCapacity()
            throws Exception
    {
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        primary.removeContext("/");
        primary.createContext("/", exchange -> {
            int requestNumber = primaryRequests.incrementAndGet();
            if (requestNumber == 1) {
                firstRequestStarted.countDown();
                try {
                    if (!proceed.await(1, TimeUnit.SECONDS)) {
                        throw new IOException("test request was not released");
                    }
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            writeJson(exchange, 200, "[{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":\"0x1\"}]");
        });
        try (RemoteExecutionRuntime runtime = new RemoteExecutionRuntime(
                HttpClient.newHttpClient(),
                List.of(new ProviderProfile("primary", endpoint(primary))),
                Duration.ofSeconds(1),
                1_024,
                1_024,
                new ExecutionPolicy(1, 1, 1, 1, 100, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(1)))) {
            var active = runtime.execute(new RemoteOperation("first", List.of()));
            assertThat(firstRequestStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var cancelled = runtime.execute(new RemoteOperation("cancelled", List.of()));
            assertThat(cancelled.cancel(true)).isTrue();
            var replacement = runtime.execute(new RemoteOperation("replacement", List.of()));
            proceed.countDown();

            assertThat(active.join().value().asText()).isEqualTo("0x1");
            assertThat(replacement.join().value().asText()).isEqualTo("0x1");
            assertThat(primaryRequests).hasValue(2);
        }
    }

    @Test
    public void testRetriesThrottleUsingRetryAfter()
    {
        primary.removeContext("/");
        primary.createContext("/", exchange -> {
            if (primaryRequests.incrementAndGet() == 1) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
                return;
            }
            writeJson(exchange, 200, "[{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":\"0x1\"}]");
        });
        try (RemoteExecutionRuntime runtime = new RemoteExecutionRuntime(
                HttpClient.newHttpClient(),
                List.of(new ProviderProfile("primary", endpoint(primary))),
                Duration.ofSeconds(1),
                1_024,
                1_024,
                new ExecutionPolicy(1, 4, 4, 2, 100, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(1)))) {
            assertThat(runtime.execute(new RemoteOperation("eth_chainId", List.of())).join().value().asText()).isEqualTo("0x1");
            assertThat(runtime.metrics().throttledCount()).isEqualTo(1);
            assertThat(runtime.metrics().retryCount()).isEqualTo(1);
        }
    }

    @Test
    public void testWaitsForProviderCooldownWhenAllProvidersAreUnhealthy()
    {
        primary.removeContext("/");
        primary.createContext("/", exchange -> {
            if (primaryRequests.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            writeJson(exchange, 200, "[{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":\"0x1\"}]");
        });
        try (RemoteExecutionRuntime runtime = new RemoteExecutionRuntime(
                HttpClient.newHttpClient(),
                List.of(new ProviderProfile("primary", endpoint(primary))),
                Duration.ofSeconds(1),
                1_024,
                1_024,
                new ExecutionPolicy(1, 4, 4, 2, 100, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(50)))) {
            long start = System.nanoTime();
            assertThat(runtime.execute(new RemoteOperation("eth_chainId", List.of())).join().value().asText()).isEqualTo("0x1");

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(40));
            assertThat(primaryRequests).hasValue(2);
        }
    }

    @Test
    public void testDoesNotRetryTerminalHttpFailure()
    {
        primary.removeContext("/");
        primary.createContext("/", exchange -> {
            primaryRequests.incrementAndGet();
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        try (RemoteExecutionRuntime runtime = runtime()) {
            assertThatThrownBy(() -> runtime.execute(new RemoteOperation("eth_chainId", List.of())).join())
                    .hasRootCauseInstanceOf(JsonRpcClient.JsonRpcHttpException.class)
                    .hasRootCauseMessage("JSON-RPC endpoint returned HTTP 400");
            assertThat(primaryRequests).hasValue(1);
            assertThat(fallbackRequests).hasValue(0);
        }
    }

    @Test
    public void testFailsExplicitlyAfterRetryLimit()
    {
        try (RemoteExecutionRuntime runtime = new RemoteExecutionRuntime(
                HttpClient.newHttpClient(),
                List.of(new ProviderProfile("primary", endpoint(primary))),
                Duration.ofSeconds(1),
                1_024,
                1_024,
                new ExecutionPolicy(1, 4, 4, 2, 100, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(1)))) {
            assertThatThrownBy(() -> runtime.execute(new RemoteOperation("eth_chainId", List.of())).join())
                    .hasRootCauseInstanceOf(JsonRpcClient.JsonRpcHttpException.class);
            assertThat(primaryRequests).hasValue(2);
            assertThat(runtime.metrics().retryCount()).isEqualTo(1);
        }
    }

    @Test
    public void testPartialBatchFailureIsExplicitAndNotRetried()
    {
        primary.removeContext("/");
        primary.createContext("/", exchange -> {
            primaryRequests.incrementAndGet();
            writeJson(exchange, 200, "[" +
                    "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":\"0x1\"}," +
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602,\"message\":\"invalid params\"}}]");
        });
        try (RemoteExecutionRuntime runtime = runtime()) {
            assertThatThrownBy(() -> runtime.executeBatch(List.of(
                    new RemoteOperation("valid", List.of()),
                    new RemoteOperation("invalid", List.of()))).join())
                    .hasRootCauseMessage("JSON-RPC endpoint returned error code -32602 for id 1");
            assertThat(primaryRequests).hasValue(1);
            assertThat(fallbackRequests).hasValue(0);
        }
    }

    @Test
    public void testTimeoutFallsBack()
            throws Exception
    {
        CountDownLatch releasePrimary = new CountDownLatch(1);
        primary.removeContext("/");
        primary.createContext("/", exchange -> {
            primaryRequests.incrementAndGet();
            try {
                if (!releasePrimary.await(1, TimeUnit.SECONDS)) {
                    throw new IOException("test request was not released");
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            writeJson(exchange, 200, "[{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":\"0x1\"}]");
        });
        try (RemoteExecutionRuntime runtime = new RemoteExecutionRuntime(
                HttpClient.newHttpClient(),
                List.of(
                        new ProviderProfile("primary", endpoint(primary)),
                        new ProviderProfile("fallback", endpoint(fallback))),
                Duration.ofMillis(20),
                1_024,
                1_024,
                new ExecutionPolicy(1, 4, 4, 2, 100, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(1)))) {
            RemoteResult result = runtime.execute(new RemoteOperation("eth_chainId", List.of())).join();

            assertThat(result.providerName()).isEqualTo("fallback");
            assertThat(primaryRequests).hasValue(1);
            assertThat(fallbackRequests).hasValue(1);
            assertThat(runtime.metrics().retryCount()).isEqualTo(1);
            assertThat(runtime.metrics().failoverCount()).isEqualTo(1);
        }
        finally {
            releasePrimary.countDown();
        }
    }

    @Test
    public void testConnectionFailureFallsBack()
    {
        URI unavailableEndpoint = endpoint(primary);
        primary.stop(0);
        try (RemoteExecutionRuntime runtime = new RemoteExecutionRuntime(
                HttpClient.newHttpClient(),
                List.of(
                        new ProviderProfile("primary", unavailableEndpoint),
                        new ProviderProfile("fallback", endpoint(fallback))),
                Duration.ofMillis(100),
                1_024,
                1_024,
                new ExecutionPolicy(1, 4, 4, 2, 100, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(1)))) {
            RemoteResult result = runtime.execute(new RemoteOperation("eth_chainId", List.of())).join();

            assertThat(result.providerName()).isEqualTo("fallback");
            assertThat(fallbackRequests).hasValue(1);
        }
    }

    private RemoteExecutionRuntime runtime()
    {
        return new RemoteExecutionRuntime(
                HttpClient.newHttpClient(),
                List.of(
                        new ProviderProfile("primary", endpoint(primary)),
                        new ProviderProfile("fallback", endpoint(fallback))),
                Duration.ofSeconds(1),
                1_024,
                1_024,
                new ExecutionPolicy(1, 4, 4, 2, 100, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(1)));
    }

    private void handlePrimary(HttpExchange exchange)
            throws IOException
    {
        primaryRequests.incrementAndGet();
        exchange.sendResponseHeaders(503, -1);
        exchange.close();
    }

    private void handleFallback(HttpExchange exchange)
            throws IOException
    {
        fallbackRequests.incrementAndGet();
        writeJson(exchange, 200, "[{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":\"0x1\"}]");
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler)
            throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static URI endpoint(HttpServer server)
    {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void writeJson(HttpExchange exchange, int status, String body)
            throws IOException
    {
        byte[] bytes = body.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
