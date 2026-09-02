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
            assertThat(runtime.snapshot().providers())
                    .extracting(provider -> provider.name(), provider -> provider.metrics().requestCount(), provider -> provider.metrics().failureCount(), provider -> provider.metrics().retryCount(), provider -> provider.metrics().failoverCount())
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("primary", 1L, 1L, 1L, 1L),
                            org.assertj.core.groups.Tuple.tuple("fallback", 1L, 0L, 0L, 0L));
        }
    }

    @Test
    public void testTargetedExecutionDoesNotFailOver()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport primary = new RecordingTransport();
        primary.batchHandler = requests -> CompletableFuture.failedFuture(httpFailure(503));
        RecordingTransport fallback = new RecordingTransport();
        fallback.batchHandler = requests -> CompletableFuture.completedFuture(List.of(text("fallback")));
        ProviderProfile primaryProvider = provider("primary", true);
        ProviderProfile fallbackProvider = provider("fallback", true);
        try (RemoteExecutionRuntime runtime = runtime(
                List.of(primaryProvider, fallbackProvider),
                Map.of(primaryProvider.name(), primary, fallbackProvider.name(), fallback),
                policy(1, 10, 10, 1, 100),
                scheduler)) {
            CompletableFuture<RemoteResult> result = runtime.executeOnProvider("primary", operation("eth_chainId"));
            scheduler.runUntil(result::isDone);

            assertThatThrownBy(result::join).hasRootCauseInstanceOf(JsonRpcClient.JsonRpcHttpException.class);
            assertThat(primary.batchRequests).hasValue(1);
            assertThat(fallback.batchRequests).hasValue(0);
            assertThat(runtime.metrics().failoverCount()).isZero();
        }
    }

    @Test
    public void testTargetedExecutionRetriesWithoutCooldownOrFailover()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport primary = new RecordingTransport();
        primary.batchHandler = requests -> primary.batchRequests.get() == 1 ?
                CompletableFuture.failedFuture(httpFailure(503)) :
                CompletableFuture.completedFuture(List.of(text("primary")));
        RecordingTransport fallback = new RecordingTransport();
        fallback.batchHandler = requests -> CompletableFuture.completedFuture(List.of(text("fallback")));
        ProviderProfile primaryProvider = provider("primary", true);
        ProviderProfile fallbackProvider = provider("fallback", true);
        ExecutionPolicy policy = new ExecutionPolicy(1, 10, 10, 2, 100, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(50));
        try (RemoteExecutionRuntime runtime = runtime(
                List.of(primaryProvider, fallbackProvider),
                Map.of(primaryProvider.name(), primary, fallbackProvider.name(), fallback),
                policy,
                scheduler)) {
            CompletableFuture<RemoteResult> result = runtime.executeOnProvider("primary", operation("eth_chainId"));
            scheduler.runUntil(result::isDone);

            assertThat(result.join().providerName()).isEqualTo("primary");
            assertThat(primary.batchRequests).hasValue(2);
            assertThat(fallback.batchRequests).hasValue(0);
            assertThat(runtime.metrics().failoverCount()).isZero();
            assertThat(scheduler.nanoTime()).isLessThan(Duration.ofMillis(50).toNanos());
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
    public void testSnapshotReportsOnlyLocalProviderCooldownState()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        transport.batchHandler = requests -> CompletableFuture.failedFuture(httpFailure(503));
        ProviderProfile provider = provider("primary", true);
        ExecutionPolicy policy = new ExecutionPolicy(1, 10, 10, 2, 100, Duration.ofMillis(1), Duration.ofMillis(10), Duration.ofMillis(50));
        try (RemoteExecutionRuntime runtime = runtime(List.of(provider), Map.of(provider.name(), transport), policy, scheduler)) {
            runtime.execute(operation("method"));
            scheduler.runUntil(() -> transport.batchRequests.get() == 1);

            RemoteRuntimeSnapshot snapshot = runtime.snapshot();
            assertThat(snapshot.protocol()).isEqualTo(RemoteRequest.Protocol.JSON_RPC);
            assertThat(snapshot.providers()).singleElement().satisfies(providerSnapshot -> {
                assertThat(providerSnapshot.name()).isEqualTo("primary");
                assertThat(providerSnapshot.jsonRpcBatchEnabled()).isTrue();
                assertThat(providerSnapshot.state()).isEqualTo(RemoteRuntimeSnapshot.ProviderSnapshot.State.COOLDOWN);
                assertThat(providerSnapshot.cooldownRemainingMillis()).isEqualTo(50);
                assertThat(providerSnapshot.metrics().requestCount()).isEqualTo(1);
                assertThat(providerSnapshot.metrics().failureCount()).isEqualTo(1);
                assertThat(providerSnapshot.metrics().retryCount()).isEqualTo(1);
            });
            assertThat(snapshot.executionPolicy()).isEqualTo(policy);
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
    public void testCacheMetricsAreIsolatedPerExecutionContext()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        ProviderProfile provider = provider("primary", true);
        RemoteCacheKey key = new RemoteCacheKey("ethereum", "block", "0x1", "full=false", 1);
        try (RemoteExecutionRuntime runtime = new RemoteExecutionRuntime(
                List.of(provider),
                Map.of(provider.name(), transport),
                policy(1, 10, 10, 1, 100),
                scheduler,
                new RemoteCacheConfig(true, 4_096, 2_048, Optional.empty()))) {
            RemoteExecutionRuntime.ExecutionContext cold = runtime.newExecutionContext();
            RemoteExecutionRuntime.ExecutionContext warm = runtime.newExecutionContext();

            assertThat(cold.getCached(key)).isEmpty();
            cold.admit(key, text("value"));
            assertThat(warm.getCached(key)).contains(text("value"));

            assertThat(cold.metrics().cacheMissCount()).isOne();
            assertThat(cold.metrics().cacheHitCount()).isZero();
            assertThat(cold.metrics().cacheBytesWritten()).isPositive();
            assertThat(warm.metrics().cacheMissCount()).isZero();
            assertThat(warm.metrics().cacheHitCount()).isOne();
            assertThat(warm.metrics().cacheBytesRead()).isPositive();
        }
    }

    @Test
    public void testRuntimeClosePreventsLateCacheAdmission()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        ProviderProfile provider = provider("primary", true);
        RemoteExecutionRuntime runtime = new RemoteExecutionRuntime(
                List.of(provider),
                Map.of(provider.name(), transport),
                policy(1, 10, 10, 1, 100),
                scheduler,
                new RemoteCacheConfig(true, 4_096, 2_048, Optional.empty()));
        RemoteExecutionRuntime.ExecutionContext context = runtime.newExecutionContext();
        RemoteCacheKey key = new RemoteCacheKey("ethereum", "block", "0x1", "full=false", 1);
        context.admit(key, text("value"));
        assertThat(runtime.cacheMetrics().entryCount()).isOne();

        runtime.close();
        context.admit(key, text("late"));

        assertThat(runtime.cacheMetrics().entryCount()).isZero();
        assertThat(scheduler.isClosed()).isTrue();
    }

    @Test
    public void testRuntimeCloseIsIdempotent()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        ProviderProfile provider = provider("primary", true);
        RemoteExecutionRuntime runtime = runtime(List.of(provider), Map.of(provider.name(), transport), policy(1, 10, 10, 1, 100), scheduler);

        runtime.close();
        runtime.close();

        assertThat(scheduler.closeCalls()).isOne();
    }

    @Test
    public void testCancelledExecutionContextPreventsCacheAdmission()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        ProviderProfile provider = provider("primary", true);
        try (RemoteExecutionRuntime runtime = new RemoteExecutionRuntime(
                List.of(provider),
                Map.of(provider.name(), transport),
                policy(1, 10, 10, 1, 100),
                scheduler,
                new RemoteCacheConfig(true, 4_096, 2_048, Optional.empty()))) {
            RemoteExecutionRuntime.ExecutionContext context = runtime.newExecutionContext();
            RemoteCacheKey key = new RemoteCacheKey("ethereum", "block", "0x1", "full=false", 1);
            CompletableFuture<Void> result = new CompletableFuture<>();
            RemoteExecution<Void> execution = context.execution(result);

            assertThat(execution.future().cancel(true)).isTrue();
            context.admit(key, text("late"));

            assertThat(runtime.cacheMetrics().entryCount()).isZero();
            assertThat(context.metrics().cacheBytesWritten()).isZero();
        }
    }

    @Test
    public void testExecutionCacheReadsAreBoundedAndReportedAsMemory()
    {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingTransport transport = new RecordingTransport();
        ProviderProfile provider = provider("primary", true);
        RemoteCacheKey firstKey = new RemoteCacheKey("ethereum", "block", "0x1", "full=false", 1);
        RemoteCacheKey secondKey = new RemoteCacheKey("ethereum", "block", "0x2", "full=false", 1);
        try (RemoteExecutionRuntime runtime = new RemoteExecutionRuntime(
                List.of(provider),
                Map.of(provider.name(), transport),
                policy(1, 10, 10, 1, 100),
                scheduler,
                new RemoteCacheConfig(true, 4_096, 2_048, Optional.empty()),
                8)) {
            RemoteExecutionRuntime.ExecutionContext writer = runtime.newExecutionContext();
            writer.admit(firstKey, text("first"));
            writer.admit(secondKey, text("second"));
            RemoteExecutionRuntime.ExecutionContext reader = runtime.newExecutionContext();

            assertThat(reader.getCached(firstKey)).contains(text("first"));
            assertThat(reader.getCached(secondKey)).isEmpty();
            CompletableFuture<Void> result = new CompletableFuture<>();
            RemoteExecution<Void> execution = reader.execution(result);
            assertThat(execution.memoryUsage()).isEqualTo(7);

            result.complete(null);

            assertThat(execution.memoryUsage()).isZero();
            assertThat(reader.metrics().cacheHitCount()).isOne();
            assertThat(reader.metrics().cacheMissCount()).isOne();
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
            RemoteExecutionMetrics providerMetrics = runtime.snapshot().providers().getFirst().metrics();
            assertThat(providerMetrics.requestCount()).isEqualTo(2);
            assertThat(providerMetrics.throttledCount()).isEqualTo(1);
            assertThat(providerMetrics.retryCount()).isEqualTo(1);
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

    @Test
    public void testRestRequestsUseSingleFlightWithoutWireBatching()
    {
        ManualScheduler scheduler = new ManualScheduler();
        ProviderProfile provider = provider("primary", true);
        RecordingRestTransport transport = new RecordingRestTransport();
        transport.handler = request -> CompletableFuture.completedFuture(text(request.path()));
        RestRemoteRequest request = new RestRemoteRequest("GET", "/v1/transactions", Map.of("start", List.of("10")), Optional.empty());

        try (RemoteExecutionRuntime runtime = RemoteExecutionRuntime.forRest(
                List.of(provider),
                Map.of(provider.name(), transport),
                policy(2, 10, 100, 1, 100),
                scheduler)) {
            CompletableFuture<RemoteResult> first = runtime.execute(request);
            CompletableFuture<RemoteResult> second = runtime.execute(request);
            scheduler.runUntil(() -> first.isDone() && second.isDone());

            assertThat(first.join().value().textValue()).isEqualTo("/v1/transactions");
            assertThat(second.join().value().textValue()).isEqualTo("/v1/transactions");
            assertThat(transport.requests).hasValue(1);
            assertThat(runtime.metrics().batchItemCount()).isEqualTo(1);
        }
    }

    @Test
    public void testRestFailureRetriesOnFallbackProvider()
    {
        ManualScheduler scheduler = new ManualScheduler();
        ProviderProfile primary = provider("primary", true);
        ProviderProfile fallback = provider("fallback", true);
        RecordingRestTransport unavailable = new RecordingRestTransport();
        unavailable.handler = request -> CompletableFuture.failedFuture(new RemoteHttpException(503, Optional.empty()));
        RecordingRestTransport available = new RecordingRestTransport();
        available.handler = request -> CompletableFuture.completedFuture(text("ok"));

        try (RemoteExecutionRuntime runtime = RemoteExecutionRuntime.forRest(
                List.of(primary, fallback),
                Map.of(primary.name(), unavailable, fallback.name(), available),
                policy(1, 10, 100, 2, 100),
                scheduler)) {
            CompletableFuture<RemoteResult> result = runtime.execute(new RestRemoteRequest("GET", "/v1", Map.of(), Optional.empty()));
            scheduler.runUntil(result::isDone);

            assertThat(result.join().value().textValue()).isEqualTo("ok");
            assertThat(unavailable.requests).hasValue(1);
            assertThat(available.requests).hasValue(1);
            assertThat(runtime.metrics().retryCount()).isEqualTo(1);
            assertThat(runtime.metrics().failoverCount()).isEqualTo(1);
        }
    }

    @Test
    public void testRestCancellationCancelsTransportFuture()
    {
        ManualScheduler scheduler = new ManualScheduler();
        ProviderProfile provider = provider("primary", true);
        CompletableFuture<JsonNode> transportFuture = new CompletableFuture<>();
        RecordingRestTransport transport = new RecordingRestTransport();
        transport.handler = request -> transportFuture;

        try (RemoteExecutionRuntime runtime = RemoteExecutionRuntime.forRest(
                List.of(provider),
                Map.of(provider.name(), transport),
                policy(1, 10, 100, 1, 100),
                scheduler)) {
            CompletableFuture<RemoteResult> result = runtime.execute(new RestRemoteRequest("GET", "/v1", Map.of(), Optional.empty()));
            scheduler.runUntil(() -> transport.requests.get() == 1);

            assertThat(result.cancel(true)).isTrue();
            assertThat(transportFuture).isCancelled();
        }
    }

    @Test
    public void testRuntimeRejectsMismatchedProtocol()
    {
        ManualScheduler scheduler = new ManualScheduler();
        ProviderProfile provider = provider("primary", true);
        RecordingRestTransport transport = new RecordingRestTransport();
        transport.handler = request -> CompletableFuture.completedFuture(text("unused"));

        try (RemoteExecutionRuntime runtime = RemoteExecutionRuntime.forRest(
                List.of(provider),
                Map.of(provider.name(), transport),
                policy(1, 10, 100, 1, 100),
                scheduler)) {
            assertThatThrownBy(() -> runtime.execute(operation("eth_blockNumber")).join())
                    .hasRootCauseMessage("remote request protocol does not match runtime protocol");
            assertThat(transport.requests).hasValue(0);
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

    private static final class RecordingRestTransport
            implements RestTransport
    {
        private final AtomicInteger requests = new AtomicInteger();
        private java.util.function.Function<RestRemoteRequest, CompletableFuture<JsonNode>> handler;

        @Override
        public CompletableFuture<JsonNode> execute(RestRemoteRequest request)
        {
            requests.incrementAndGet();
            return handler.apply(request);
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
        private int closeCalls;

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

        public int closeCalls()
        {
            return closeCalls;
        }

        @Override
        public void close()
        {
            closeCalls++;
            closed = true;
            tasks.clear();
        }

        private record ScheduledTask(long dueNanos, long sequence, Runnable task) {}
    }
}
