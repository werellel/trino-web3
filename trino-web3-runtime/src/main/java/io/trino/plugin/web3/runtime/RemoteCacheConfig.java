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

import java.time.Duration;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Bounds the catalog-owned worker-local remote result cache. */
public record RemoteCacheConfig(boolean enabled, long maximumWeightBytes, int maximumEntryBytes, Optional<Duration> ttl)
{
    public RemoteCacheConfig
    {
        requireNonNull(ttl, "ttl is null");
        if (maximumWeightBytes < 1) {
            throw new IllegalArgumentException("maximumWeightBytes must be positive");
        }
        if (maximumEntryBytes < 1) {
            throw new IllegalArgumentException("maximumEntryBytes must be positive");
        }
        if (enabled && maximumEntryBytes > maximumWeightBytes) {
            throw new IllegalArgumentException("maximumEntryBytes must be no greater than maximumWeightBytes when caching is enabled");
        }
        if (ttl.isPresent() && (ttl.orElseThrow().isZero() || ttl.orElseThrow().isNegative())) {
            throw new IllegalArgumentException("ttl must be positive");
        }
    }

    public static RemoteCacheConfig disabled()
    {
        return new RemoteCacheConfig(false, 128L * 1_048_576, 8 * 1_048_576, Optional.empty());
    }
}
