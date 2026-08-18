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

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** A cancellable result paired with metrics for only the remote work it observes. */
public final class RemoteExecution<T>
{
    private final CompletableFuture<T> future;
    private final Supplier<RemoteExecutionMetrics> metrics;

    RemoteExecution(CompletableFuture<T> future, Supplier<RemoteExecutionMetrics> metrics)
    {
        this.future = requireNonNull(future, "future is null");
        this.metrics = requireNonNull(metrics, "metrics is null");
    }

    public CompletableFuture<T> future()
    {
        return future;
    }

    public RemoteExecutionMetrics metrics()
    {
        return metrics.get();
    }

    public <R> RemoteExecution<R> map(Function<T, R> mapper)
    {
        requireNonNull(mapper, "mapper is null");
        CompletableFuture<R> mapped = future.thenApply(mapper);
        mapped.whenComplete((value, failure) -> {
            if (mapped.isCancelled()) {
                future.cancel(true);
            }
        });
        return new RemoteExecution<>(mapped, metrics);
    }
}
