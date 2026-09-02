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
import com.fasterxml.jackson.databind.node.LongNode;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class BenchmarkRemoteExecution
{
    @Param({"1", "100"})
    private int operationCount;

    private List<RemoteOperation> operations;
    private RemoteExecutionRuntime singleRequestRuntime;
    private RemoteExecutionRuntime batchRuntime;
    private RemoteExecutionRuntime rateLimitedBatchRuntime;

    @Setup
    public void setup()
    {
        operations = java.util.stream.IntStream.range(0, operationCount)
                .mapToObj(index -> new RemoteOperation("benchmark_method", List.of(index)))
                .toList();
        ProviderProfile nonBatchProvider = provider(false);
        ProviderProfile batchProvider = provider(true);
        ExecutionPolicy policy = policy(10_000);
        singleRequestRuntime = runtime(nonBatchProvider, policy);
        batchRuntime = runtime(batchProvider, policy);
        rateLimitedBatchRuntime = runtime(batchProvider, policy(10_000));
    }

    @Benchmark
    public void requestPerItem(Blackhole blackhole)
    {
        for (RemoteOperation operation : operations) {
            blackhole.consume(singleRequestRuntime.execute(operation).join());
        }
    }

    @Benchmark
    public void jsonRpcBatch(Blackhole blackhole)
    {
        blackhole.consume(batchRuntime.executeBatch(operations).join());
    }

    @Benchmark
    public void jsonRpcBatchWithRateAdmission(Blackhole blackhole)
    {
        blackhole.consume(rateLimitedBatchRuntime.executeBatch(operations).join());
    }

    @TearDown
    public void tearDown()
    {
        singleRequestRuntime.close();
        batchRuntime.close();
        rateLimitedBatchRuntime.close();
    }

    private static RemoteExecutionRuntime runtime(ProviderProfile provider, ExecutionPolicy policy)
    {
        return new RemoteExecutionRuntime(
                List.of(provider),
                java.util.Map.of(provider.name(), new BenchmarkTransport()),
                policy,
                new ExecutorRemoteExecutionScheduler());
    }

    private static ProviderProfile provider(boolean supportsBatch)
    {
        return new ProviderProfile(
                "primary",
                URI.create("http://127.0.0.1:1"),
                new ProviderCapabilities(supportsBatch));
    }

    private static ExecutionPolicy policy(int requestsPerSecond)
    {
        return new ExecutionPolicy(
                16,
                1_024,
                100,
                1,
                requestsPerSecond,
                Duration.ofMillis(1),
                Duration.ofMillis(1),
                Duration.ofMillis(1));
    }

    private static final class BenchmarkTransport
            implements JsonRpcTransport
    {
        @Override
        public CompletableFuture<JsonNode> execute(JsonRpcClient.JsonRpcRequest request)
        {
            return CompletableFuture.completedFuture(LongNode.valueOf(request.id()));
        }

        @Override
        public CompletableFuture<List<JsonNode>> executeBatch(List<JsonRpcClient.JsonRpcRequest> requests)
        {
            return CompletableFuture.completedFuture(requests.stream()
                    .map(request -> LongNode.valueOf(request.id()))
                    .map(JsonNode.class::cast)
                    .toList());
        }
    }
}
