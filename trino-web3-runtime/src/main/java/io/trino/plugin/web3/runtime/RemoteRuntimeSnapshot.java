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

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Safe, local-only runtime state for connector observability. */
public record RemoteRuntimeSnapshot(
        RemoteRequest.Protocol protocol,
        ExecutionPolicy executionPolicy,
        boolean cacheEnabled,
        RemoteExecutionMetrics executionMetrics,
        RemoteCacheMetrics cacheMetrics,
        List<ProviderSnapshot> providers)
{
    public RemoteRuntimeSnapshot
    {
        requireNonNull(protocol, "protocol is null");
        requireNonNull(executionPolicy, "executionPolicy is null");
        requireNonNull(executionMetrics, "executionMetrics is null");
        requireNonNull(cacheMetrics, "cacheMetrics is null");
        providers = List.copyOf(requireNonNull(providers, "providers is null"));
    }

    /** Provider availability derived only from this runtime's retry cooldown. */
    public record ProviderSnapshot(String name, boolean jsonRpcBatchEnabled, State state, long cooldownRemainingMillis, RemoteExecutionMetrics metrics)
    {
        public ProviderSnapshot
        {
            requireNonNull(name, "name is null");
            requireNonNull(state, "state is null");
            requireNonNull(metrics, "metrics is null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name is blank");
            }
            if (cooldownRemainingMillis < 0) {
                throw new IllegalArgumentException("cooldownRemainingMillis is negative");
            }
        }

        public enum State
        {
            AVAILABLE,
            COOLDOWN,
        }
    }
}
