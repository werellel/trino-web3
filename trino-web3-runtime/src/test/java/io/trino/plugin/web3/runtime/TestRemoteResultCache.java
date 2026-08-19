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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestRemoteResultCache
{
    private static final RemoteCacheKey KEY = new RemoteCacheKey("ethereum", "block", "0xabc", "full=false", 1);

    @Test
    public void testDisabledCacheSharesNothing()
    {
        RemoteResultCache cache = new RemoteResultCache(RemoteCacheConfig.disabled());

        assertThat(cache.put(KEY, JsonNodeFactory.instance.objectNode().put("hash", "0xabc"))).isZero();
        assertThat(cache.get(KEY)).isEmpty();
        assertThat(cache.metrics()).isEqualTo(new RemoteCacheMetrics(0, 0, 0));
    }

    @Test
    public void testDisabledCacheIgnoresInactiveEntryWeightRelationship()
    {
        RemoteResultCache cache = new RemoteResultCache(new RemoteCacheConfig(false, 1, 2, Optional.empty()));

        assertThat(cache.put(KEY, JsonNodeFactory.instance.objectNode().put("hash", "0xabc"))).isZero();
        assertThat(cache.get(KEY)).isEmpty();
    }

    @Test
    public void testCachedValueIsIsolatedFromCallerMutation()
    {
        RemoteResultCache cache = new RemoteResultCache(new RemoteCacheConfig(true, 4_096, 2_048, Optional.empty()));
        ObjectNode value = JsonNodeFactory.instance.objectNode().put("hash", "0xabc");

        assertThat(cache.put(KEY, value)).isPositive();
        ObjectNode first = (ObjectNode) cache.get(KEY).orElseThrow().value();
        first.put("hash", "0xmutated");

        assertThat(cache.get(KEY).orElseThrow().value().path("hash").asText()).isEqualTo("0xabc");
        assertThat(cache.metrics().entryCount()).isOne();
        assertThat(cache.metrics().retainedBytes()).isPositive();
    }

    @Test
    public void testOversizedEntryBypassesCache()
    {
        RemoteResultCache cache = new RemoteResultCache(new RemoteCacheConfig(true, 1_024, 32, Optional.empty()));
        ObjectNode value = JsonNodeFactory.instance.objectNode().put("payload", "x".repeat(64));

        assertThat(cache.put(KEY, value)).isZero();
        assertThat(cache.get(KEY)).isEmpty();
    }

    @Test
    public void testWeightEvictionIsReported()
    {
        RemoteResultCache cache = new RemoteResultCache(new RemoteCacheConfig(true, 350, 128, Optional.empty()));
        for (int index = 0; index < 10; index++) {
            cache.put(new RemoteCacheKey("ethereum", "block", "0x" + index, "full=false", 1),
                    JsonNodeFactory.instance.objectNode().put("payload", "x".repeat(80)));
        }

        assertThat(cache.metrics().entryCount()).isLessThan(10);
        assertThat(cache.metrics().evictionCount()).isPositive();
        assertThat(cache.metrics().retainedBytes()).isLessThanOrEqualTo(350);
    }

    @Test
    public void testEntryExpiresAfterConfiguredTtl()
    {
        AtomicLong nanos = new AtomicLong();
        Ticker ticker = new Ticker()
        {
            @Override
            public long read()
            {
                return nanos.get();
            }
        };
        RemoteResultCache cache = new RemoteResultCache(
                new RemoteCacheConfig(true, 4_096, 2_048, Optional.of(Duration.ofSeconds(1))),
                ticker);
        cache.put(KEY, JsonNodeFactory.instance.objectNode().put("hash", "0xabc"));

        assertThat(cache.get(KEY)).isPresent();
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));

        assertThat(cache.get(KEY)).isEmpty();
        assertThat(cache.metrics().entryCount()).isZero();
    }

    @Test
    public void testInvalidateAllReleasesRetainedEntries()
    {
        RemoteResultCache cache = new RemoteResultCache(new RemoteCacheConfig(true, 4_096, 2_048, Optional.empty()));
        cache.put(KEY, JsonNodeFactory.instance.objectNode().put("hash", "0xabc"));

        cache.invalidateAll();

        assertThat(cache.metrics().entryCount()).isZero();
        assertThat(cache.metrics().retainedBytes()).isZero();
    }

    @Test
    public void testConfigurationValidation()
    {
        assertThatThrownBy(() -> new RemoteCacheConfig(true, 0, 1, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteCacheConfig(true, 10, 11, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteCacheConfig(true, 10, 1, Optional.of(Duration.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
