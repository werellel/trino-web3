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
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestDeterministicRemoteExecutionRuntime
{
    @Test
    public void testExecutesWithoutBatchEnvelopeWhenProviderDoesNotSupportBatching()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        transport.singleHandler = request -> CompletableFuture.completedFuture(text(request.method()));
        ProviderProfile provider = provider("primary", false);
        try (RemoteExecutionRuntime runtime = runtime(List.of(provider), Map.of(provider.name(), transport), policy(2, 10, 10, 2, 100), scheduler)) {
            CompletableFuture<List<RemoteResult>> result = runtime.executeBatch(List.of(operation("first"), operation("second")));
            scheduler.runUntil(result::isDone);

            assertThat(result.join()).extracting(value -> value.value().asText()).containsExactly("first", "second");
            assertThat(transport.singleRequests).hasValue(2);
            assertThat(transport.batchRequests).hasValue(0);
            assertThat(runtime.metrics().batchItemCount()).isEqualTo(2);
        }
    }

    @Test
    public void testFallsBackFromBatchProviderToNonBatchProvider()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport primary = new RecordingTransport();
        primary.batchHandler = requests -> CompletableFuture.failedFuture(httpFailure(503));
        RecordingTransport fallback = new RecordingTransport();
        fallback.singleHandler = request -> CompletableFuture.completedFuture(text("fallback"));
        ProviderProfile primaryProvider = provider("primary", true);
        ProviderProfile fallbackProvider = provider("fallback", false);
        try (RemoteExecutionRuntime runtime = runtime(
                List.of(primaryProvider, fallbackProvider),
                Map.of(primaryProvider.name(), primary, fallbackProvider.name(), fallback),
                policy(1, 10, 10, 2, 100),
                scheduler)) {
            CompletableFuture<RemoteResult> result = runtime.execute(operation("method"));
            scheduler.runUntil(result::isDone);

            assertThat(result.join().providerName()).isEqualTo("fallback");
            assertThat(primary.batchRequests).hasValue(1);
            assertThat(fallback.singleRequests).hasValue(1);
        }
    }

    @Test
    public void testCooldownUsesControllableScheduler()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        transport.batchHandler = requests -> transport.batchRequests.get() == 1 ?
                CompletableFuture.failedFuture(httpFailure(503)) :
                CompletableFuture.completedFuture(List.of(text("ok")));
        ProviderProfile provider = provider("primary", true);
        ExecutionPolicy policy = new ExecutionPolicy(1, 10, 10, 2, 100, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(50));
        try (RemoteExecutionRuntime runtime = runtime(List.of(provider), Map.of(provider.name(), transport), policy, scheduler)) {
            CompletableFuture<RemoteResult> result = runtime.execute(operation("method"));
            scheduler.runUntil(result::isDone);

            assertThat(result.join().value().asText()).isEqualTo("ok");
            assertThat(scheduler.nanoTime()).isGreaterThanOrEqualTo(Duration.ofMillis(51).toNanos());
            assertThat(transport.batchRequests).hasValue(2);
        }
    }

    @Test
    public void testRateAdmissionUsesAsynchronousScheduling()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        transport.singleHandler = request -> CompletableFuture.completedFuture(text(request.method()));
        ProviderProfile provider = provider("primary", false);
        try (RemoteExecutionRuntime runtime = runtime(List.of(provider), Map.of(provider.name(), transport), policy(2, 10, 10, 1, 1), scheduler)) {
            CompletableFuture<List<RemoteResult>> result = runtime.executeBatch(List.of(operation("first"), operation("second")));
            scheduler.runUntil(result::isDone);

            assertThat(result.join()).hasSize(2);
            assertThat(scheduler.nanoTime()).isGreaterThanOrEqualTo(Duration.ofSeconds(1).toNanos());
            assertThat(transport.singleRequests).hasValue(2);
        }
    }

    @Test
    public void testMetricsAreIsolatedPerExecutionWhenWireBatchIsShared()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        transport.batchHandler = requests -> CompletableFuture.completedFuture(requests.stream()
                .map(request -> text(request.method()))
                .toList());
        ProviderProfile provider = provider("primary", true);
        try (RemoteExecutionRuntime runtime = runtime(List.of(provider), Map.of(provider.name(), transport), policy(1, 10, 10, 1, 100), scheduler)) {
            RemoteExecution<List<RemoteResult>> first = runtime.executeBatchWithMetrics(List.of(operation("first")));
            RemoteExecution<List<RemoteResult>> second = runtime.executeBatchWithMetrics(List.of(operation("second")));
            scheduler.runUntil(() -> first.future().isDone() && second.future().isDone());

            assertThat(runtime.metrics().requestCount()).isEqualTo(1);
            assertThat(runtime.metrics().batchItemCount()).isEqualTo(2);
            assertIsolatedMetrics(first.metrics());
            assertIsolatedMetrics(second.metrics());
        }
    }

    @Test
    public void testMaximumConcurrencyIsEnforced()
    {
        ManualScheduler scheduler = new ManualScheduler();
        List<CompletableFuture<JsonNode>> responses = new ArrayList<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        RecordingTransport transport = new RecordingTransport();
        transport.singleHandler = request -> {
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            CompletableFuture<JsonNode> response = new CompletableFuture<>();
            response.whenComplete((value, failure) -> active.decrementAndGet());
            responses.add(response);
            return response;
        };
        ProviderProfile provider = provider("primary", false);
        try (RemoteExecutionRuntime runtime = runtime(List.of(provider), Map.of(provider.name(), transport), policy(2, 10, 10, 1, 10_000), scheduler)) {
            CompletableFuture<List<RemoteResult>> result = runtime.executeBatch(List.of(operation("first"), operation("second"), operation("third")));
            scheduler.runUntil(() -> responses.size() == 2);

            assertThat(maximumActive).hasValue(2);
            assertThat(responses).hasSize(2);
            responses.getFirst().complete(text("first"));
            scheduler.runUntil(() -> responses.size() == 3);
            responses.get(1).complete(text("second"));
            responses.get(2).complete(text("third"));
            scheduler.runUntil(result::isDone);

            assertThat(result.join()).hasSize(3);
            assertThat(maximumActive).hasValue(2);
        }
    }

    @Test
    public void testQueueOverflowFailsExplicitly()
    {
        ManualScheduler scheduler = new ManualScheduler();
        CompletableFuture<JsonNode> activeResponse = new CompletableFuture<>();
        RecordingTransport transport = new RecordingTransport();
        transport.singleHandler = request -> activeResponse;
        ProviderProfile provider = provider("primary", false);
        RemoteExecutionRuntime runtime = runtime(List.of(provider), Map.of(provider.name(), transport), policy(1, 1, 1, 1, 100), scheduler);
        try {
            CompletableFuture<RemoteResult> active = runtime.execute(operation("active"));
            scheduler.runUntil(() -> transport.singleRequests.get() == 1);
            CompletableFuture<RemoteResult> queued = runtime.execute(operation("queued"));

            assertThatThrownBy(() -> runtime.execute(operation("overflow")).join())
                    .hasRootCauseMessage("RPC execution queue is full");
            queued.cancel(true);
            activeResponse.complete(text("active"));
            scheduler.runUntil(active::isDone);
        }
        finally {
            runtime.close();
        }
    }

    @Test
    public void testCloseStopsOperationWaitingForRetry()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        transport.batchHandler = requests -> CompletableFuture.failedFuture(httpFailure(503));
        ProviderProfile provider = provider("primary", true);
        RemoteExecutionRuntime runtime = runtime(List.of(provider), Map.of(provider.name(), transport), policy(1, 10, 10, 3, 100), scheduler);
        CompletableFuture<RemoteResult> result = runtime.execute(operation("method"));
        scheduler.runUntil(() -> transport.batchRequests.get() == 1);

        runtime.close();

        assertThatThrownBy(result::join).isInstanceOf(CancellationException.class);
        assertThat(transport.batchRequests).hasValue(1);
        assertThat(scheduler.isClosed()).isTrue();
    }

    @Test
    public void testLastSubscriberCancellationCancelsTransportFuture()
    {
        ManualScheduler scheduler = new ManualScheduler();
        CompletableFuture<List<JsonNode>> transportFuture = new CompletableFuture<>();
        RecordingTransport transport = new RecordingTransport();
        transport.batchHandler = requests -> transportFuture;
        ProviderProfile provider = provider("primary", true);
        try (RemoteExecutionRuntime runtime = runtime(List.of(provider), Map.of(provider.name(), transport), policy(1, 10, 10, 1, 100), scheduler)) {
            CompletableFuture<RemoteResult> result = runtime.execute(operation("method"));
            scheduler.runUntil(() -> transport.batchRequests.get() == 1);

            assertThat(result.cancel(true)).isTrue();

            assertThat(transportFuture).isCancelled();
        }
    }

    @Test
    public void testRetries429WithoutRetryAfter()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        transport.batchHandler = requests -> transport.batchRequests.get() == 1 ?
                CompletableFuture.failedFuture(httpFailure(429)) :
                CompletableFuture.completedFuture(List.of(text("ok")));
        ProviderProfile provider = provider("primary", true);
        try (RemoteExecutionRuntime runtime = runtime(List.of(provider), Map.of(provider.name(), transport), policy(1, 10, 10, 2, 100), scheduler)) {
            CompletableFuture<RemoteResult> result = runtime.execute(operation("method"));
            scheduler.runUntil(result::isDone);

            assertThat(result.join().value().asText()).isEqualTo("ok");
            assertThat(runtime.metrics().throttledCount()).isEqualTo(1);
            assertThat(runtime.metrics().retryCount()).isEqualTo(1);
        }
    }

    @Test
    public void test429RetryExhaustionFailsExplicitly()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        transport.batchHandler = requests -> CompletableFuture.failedFuture(httpFailure(429));
        ProviderProfile provider = provider("primary", true);
        try (RemoteExecutionRuntime runtime = runtime(List.of(provider), Map.of(provider.name(), transport), policy(1, 10, 10, 2, 100), scheduler)) {
            CompletableFuture<RemoteResult> result = runtime.execute(operation("method"));
            scheduler.runUntil(result::isDone);

            assertThatThrownBy(result::join).hasRootCauseInstanceOf(JsonRpcClient.JsonRpcHttpException.class);
            assertThat(transport.batchRequests).hasValue(2);
            assertThat(runtime.metrics().throttledCount()).isEqualTo(2);
            assertThat(runtime.metrics().retryCount()).isEqualTo(1);
        }
    }

    @Test
    public void testUnsupportedMethodIsTerminal()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        transport.batchHandler = requests -> CompletableFuture.failedFuture(new JsonRpcClient.JsonRpcResponseException(-32601, 0));
        ProviderProfile provider = provider("primary", true);
        try (RemoteExecutionRuntime runtime = runtime(List.of(provider), Map.of(provider.name(), transport), policy(1, 10, 10, 3, 100), scheduler)) {
            CompletableFuture<RemoteResult> result = runtime.execute(operation("unsupported"));
            scheduler.runUntil(result::isDone);

            assertThatThrownBy(result::join).hasRootCauseMessage("JSON-RPC endpoint returned error code -32601 for id 0");
            assertThat(transport.batchRequests).hasValue(1);
            assertThat(runtime.metrics().retryCount()).isZero();
        }
    }

    @Test
    public void testSynchronousTransportFailureCompletesSubscriber()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        transport.batchHandler = requests -> {
            throw new IllegalStateException("transport rejected request");
        };
        ProviderProfile provider = provider("primary", true);
        try (RemoteExecutionRuntime runtime = runtime(List.of(provider), Map.of(provider.name(), transport), policy(1, 10, 10, 1, 100), scheduler)) {
            CompletableFuture<RemoteResult> result = runtime.execute(operation("method"));
            scheduler.runUntil(result::isDone);

            assertThatThrownBy(result::join).hasRootCauseMessage("transport rejected request");
            assertThat(transport.batchRequests).hasValue(1);
        }
    }

    private static void assertIsolatedMetrics(RemoteExecutionMetrics metrics)
    {
        assertThat(metrics.requestCount()).isEqualTo(1);
        assertThat(metrics.batchCount()).isEqualTo(1);
        assertThat(metrics.batchItemCount()).isEqualTo(1);
        assertThat(metrics.inFlightRequests()).isZero();
    }

    private static ExecutionPolicy policy(int concurrency, int queueSize, int batchSize, int attempts, int requestsPerSecond)
    {
        return new ExecutionPolicy(
                concurrency,
                queueSize,
                batchSize,
                attempts,
                requestsPerSecond,
                Duration.ofMillis(1),
                Duration.ofMillis(10),
                Duration.ofMillis(10));
    }

    private static RemoteExecutionRuntime runtime(
            List<ProviderProfile> providers,
            Map<String, JsonRpcTransport> transports,
            ExecutionPolicy policy,
            ManualScheduler scheduler)
    {
        return new RemoteExecutionRuntime(providers, transports, policy, scheduler);
    }

    private static ProviderProfile provider(String name, boolean supportsBatch)
    {
        return new ProviderProfile(name, URI.create("http://" + name + ".invalid"), new ProviderCapabilities(supportsBatch));
    }

    private static RemoteOperation operation(String method)
    {
        return new RemoteOperation(method, List.of());
    }

    private static JsonNode text(String value)
    {
        return JsonNodeFactory.instance.textNode(value);
    }

    private static JsonRpcClient.JsonRpcHttpException httpFailure(int statusCode)
    {
        return new JsonRpcClient.JsonRpcHttpException(statusCode, Optional.empty());
    }

    private static final class RecordingTransport
            implements JsonRpcTransport
    {
        private final AtomicInteger singleRequests = new AtomicInteger();
        private final AtomicInteger batchRequests = new AtomicInteger();
        private java.util.function.Function<JsonRpcClient.JsonRpcRequest, CompletableFuture<JsonNode>> singleHandler;
        private java.util.function.Function<List<JsonRpcClient.JsonRpcRequest>, CompletableFuture<List<JsonNode>>> batchHandler;

        @Override
        public CompletableFuture<JsonNode> execute(JsonRpcClient.JsonRpcRequest request)
        {
            singleRequests.incrementAndGet();
            return singleHandler.apply(request);
        }

        @Override
        public CompletableFuture<List<JsonNode>> executeBatch(List<JsonRpcClient.JsonRpcRequest> requests)
        {
            batchRequests.incrementAndGet();
            return batchHandler.apply(requests);
        }
    }

    private static final class ManualScheduler
            implements RemoteExecutionScheduler
    {
        private static final ZonedDateTime START_TIME = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        private final PriorityQueue<ScheduledTask> tasks = new PriorityQueue<>(Comparator
                .comparingLong(ScheduledTask::dueNanos)
                .thenComparingLong(ScheduledTask::sequence));
        private long nanoTime;
        private long sequence;
        private boolean closed;

        @Override
        public long nanoTime()
        {
            return nanoTime;
        }

        @Override
        public ZonedDateTime currentTime()
        {
            return START_TIME.plusNanos(nanoTime);
        }

        @Override
        public void schedule(Runnable task, long delay, TimeUnit unit)
        {
            if (!closed) {
                tasks.add(new ScheduledTask(Math.addExact(nanoTime, unit.toNanos(delay)), sequence++, task));
            }
        }

        public void runUntil(BooleanSupplier condition)
        {
            int executed = 0;
            while (!condition.getAsBoolean() && !tasks.isEmpty() && executed < 10_000) {
                ScheduledTask task = tasks.remove();
                nanoTime = Math.max(nanoTime, task.dueNanos());
                task.task().run();
                executed++;
            }
            assertThat(condition.getAsBoolean()).isTrue();
        }

        public boolean isClosed()
        {
            return closed;
        }

        @Override
        public void close()
        {
            closed = true;
            tasks.clear();
        }

        private record ScheduledTask(long dueNanos, long sequence, Runnable task) {}
    }
}
