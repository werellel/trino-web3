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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.Weigher;
import io.trino.cache.EvictableCacheBuilder;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

final class RemoteResultCache
{
    private static final int ENTRY_OVERHEAD_BYTES = 32;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final boolean enabled;
    private final int maximumEntryBytes;
    private final Cache<RemoteCacheKey, byte[]> cache;

    public RemoteResultCache(RemoteCacheConfig config)
    {
        this(config, Ticker.systemTicker());
    }

    RemoteResultCache(RemoteCacheConfig config, Ticker ticker)
    {
        requireNonNull(config, "config is null");
        requireNonNull(ticker, "ticker is null");
        enabled = config.enabled();
        maximumEntryBytes = config.maximumEntryBytes();
        EvictableCacheBuilder<Object, Object> baseBuilder = EvictableCacheBuilder.newBuilder()
                .recordStats()
                .ticker(ticker);
        if (!enabled) {
            baseBuilder.maximumSize(0)
                    .shareNothingWhenDisabled();
            config.ttl().ifPresent(baseBuilder::expireAfterWrite);
            cache = baseBuilder.build();
            return;
        }

        EvictableCacheBuilder<RemoteCacheKey, byte[]> builder = baseBuilder
                .maximumWeight(config.maximumWeightBytes())
                .weigher((Weigher<RemoteCacheKey, byte[]>) RemoteResultCache::weight);
        config.ttl().ifPresent(builder::expireAfterWrite);
        cache = builder.build();
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public Optional<CachedValue> get(RemoteCacheKey key)
    {
        return get(key, Long.MAX_VALUE);
    }

    public Optional<CachedValue> get(RemoteCacheKey key, long maximumSerializedBytes)
    {
        requireNonNull(key, "key is null");
        if (maximumSerializedBytes < 0) {
            throw new IllegalArgumentException("maximumSerializedBytes is negative");
        }
        if (!enabled) {
            return Optional.empty();
        }
        byte[] bytes = cache.getIfPresent(key);
        if (bytes == null || bytes.length > maximumSerializedBytes) {
            return Optional.empty();
        }
        try {
            return Optional.of(new CachedValue(OBJECT_MAPPER.readTree(bytes), bytes.length));
        }
        catch (IOException e) {
            cache.invalidate(key);
            return Optional.empty();
        }
    }

    public int put(RemoteCacheKey key, JsonNode value)
    {
        requireNonNull(key, "key is null");
        requireNonNull(value, "value is null");
        return prepare(value)
                .map(prepared -> put(key, prepared))
                .orElse(0);
    }

    public Optional<PreparedValue> prepare(JsonNode value)
    {
        requireNonNull(value, "value is null");
        if (!enabled) {
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = OBJECT_MAPPER.writeValueAsBytes(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cache value cannot be serialized", e);
        }
        if (bytes.length > maximumEntryBytes) {
            return Optional.empty();
        }
        return Optional.of(new PreparedValue(bytes));
    }

    public int put(RemoteCacheKey key, PreparedValue value)
    {
        requireNonNull(key, "key is null");
        requireNonNull(value, "value is null");
        byte[] bytes = value.bytes();
        try {
            byte[] stored = cache.get(key, () -> bytes);
            return stored == bytes ? bytes.length : 0;
        }
        catch (ExecutionException e) {
            throw new IllegalStateException("unexpected checked cache load failure", e);
        }
    }

    public void invalidate(RemoteCacheKey key)
    {
        cache.invalidate(requireNonNull(key, "key is null"));
    }

    public void invalidateAll()
    {
        cache.invalidateAll();
    }

    public RemoteCacheMetrics metrics()
    {
        if (!enabled) {
            return new RemoteCacheMetrics(0, 0, 0);
        }
        long valueBytes = cache.asMap().values().stream()
                .mapToLong(value -> value.length)
                .sum();
        long keyBytes = cache.asMap().keySet().stream()
                .mapToLong(key -> key.estimatedSize() + ENTRY_OVERHEAD_BYTES)
                .sum();
        long retainedBytes = valueBytes + keyBytes;
        return new RemoteCacheMetrics(cache.size(), retainedBytes, cache.stats().evictionCount());
    }

    private static int weight(RemoteCacheKey key, byte[] value)
    {
        return toIntExact(Math.min(Integer.MAX_VALUE, (long) key.estimatedSize() + value.length + ENTRY_OVERHEAD_BYTES));
    }

    public record CachedValue(JsonNode value, int serializedBytes)
    {
        public CachedValue
        {
            requireNonNull(value, "value is null");
            if (serializedBytes < 0) {
                throw new IllegalArgumentException("serializedBytes is negative");
            }
        }
    }

    public record PreparedValue(byte[] bytes)
    {
        public PreparedValue
        {
            requireNonNull(bytes, "bytes is null");
        }
    }
}
