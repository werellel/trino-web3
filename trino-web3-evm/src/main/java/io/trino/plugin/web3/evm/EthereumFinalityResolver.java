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
package io.trino.plugin.web3.evm;

import com.fasterxml.jackson.databind.JsonNode;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime.ExecutionContext;
import io.trino.plugin.web3.runtime.RemoteOperation;
import io.trino.plugin.web3.runtime.RemoteResult;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;

import static io.trino.plugin.web3.evm.EthereumJson.requiredQuantity;
import static java.util.Objects.requireNonNull;

final class EthereumFinalityResolver
{
    private static final Duration DEFAULT_SNAPSHOT_TTL = Duration.ofSeconds(1);

    private final long snapshotTtlNanos;
    private final LongSupplier nanoTime;
    private volatile Snapshot snapshot;

    public EthereumFinalityResolver()
    {
        this(DEFAULT_SNAPSHOT_TTL, System::nanoTime);
    }

    EthereumFinalityResolver(Duration snapshotTtl, LongSupplier nanoTime)
    {
        requireNonNull(snapshotTtl, "snapshotTtl is null");
        if (snapshotTtl.isZero() || snapshotTtl.isNegative()) {
            throw new IllegalArgumentException("snapshotTtl must be positive");
        }
        snapshotTtlNanos = snapshotTtl.toNanos();
        this.nanoTime = requireNonNull(nanoTime, "nanoTime is null");
    }

    public CompletableFuture<EthereumFinalityBoundaries> resolve(ExecutionContext context)
    {
        requireNonNull(context, "context is null");
        long now = nanoTime.getAsLong();
        Snapshot current = snapshot;
        if (current != null && now - current.expiresAtNanos() < 0) {
            return CompletableFuture.completedFuture(current.boundaries());
        }

        CompletableFuture<List<RemoteResult>> safeRequest = context.executeBatch(List.of(
                        new RemoteOperation("eth_getBlockByNumber", List.of("safe", false))))
                .future();
        CompletableFuture<List<RemoteResult>> finalizedRequest = context.executeBatch(List.of(
                        new RemoteOperation("eth_getBlockByNumber", List.of("finalized", false))))
                .future();
        CompletableFuture<List<RemoteResult>> request = CompletableFuture.allOf(safeRequest, finalizedRequest)
                .thenApply(ignored -> List.of(safeRequest.join().getFirst(), finalizedRequest.join().getFirst()));
        request.whenComplete((value, failure) -> {
            if (request.isCancelled()) {
                safeRequest.cancel(true);
                finalizedRequest.cancel(true);
            }
        });
        CompletableFuture<EthereumFinalityBoundaries> resolved = request.handle((results, failure) -> {
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                if (cause instanceof CancellationException) {
                    throw new CompletionException(cause);
                }
                return EthereumFinalityBoundaries.unavailable();
            }
            try {
                JsonNode safe = value(results.get(0));
                JsonNode finalized = value(results.get(1));
                return new EthereumFinalityBoundaries(
                        java.util.OptionalLong.of(requiredQuantity(safe, "number")),
                        java.util.OptionalLong.of(requiredQuantity(finalized, "number")));
            }
            catch (IllegalArgumentException | IllegalStateException ignored) {
                return EthereumFinalityBoundaries.unavailable();
            }
        }).thenApply(boundaries -> {
            snapshot = new Snapshot(boundaries, nanoTime.getAsLong() + snapshotTtlNanos);
            return boundaries;
        });
        resolved.whenComplete((value, failure) -> {
            if (resolved.isCancelled()) {
                request.cancel(true);
            }
        });
        return resolved;
    }

    private static JsonNode value(RemoteResult result)
    {
        JsonNode value = result.value();
        if (value.isNull()) {
            throw new IllegalStateException("Ethereum finality block is missing");
        }
        return value;
    }

    private record Snapshot(EthereumFinalityBoundaries boundaries, long expiresAtNanos)
    {
        private Snapshot
        {
            requireNonNull(boundaries, "boundaries is null");
        }
    }
}
