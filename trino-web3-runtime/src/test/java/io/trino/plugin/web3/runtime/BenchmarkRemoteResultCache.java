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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class BenchmarkRemoteResultCache
{
    private static final RemoteCacheKey HIT_KEY = new RemoteCacheKey("ethereum", "eth_getBlockByHash", "0x" + "a".repeat(64), "fullTransactions=false", 1);
    private static final RemoteCacheKey MISS_KEY = new RemoteCacheKey("ethereum", "eth_getBlockByHash", "0x" + "b".repeat(64), "fullTransactions=false", 1);

    @Param({"1024", "65536"})
    private int payloadBytes;

    private RemoteResultCache cache;
    private ObjectNode value;

    @Setup
    public void setup()
    {
        cache = new RemoteResultCache(new RemoteCacheConfig(true, 128L * 1_048_576, 1_048_576, Optional.empty()));
        value = JsonNodeFactory.instance.objectNode().put("payload", "x".repeat(payloadBytes));
        cache.put(HIT_KEY, value);
    }

    @Benchmark
    public void cacheHit(Blackhole blackhole)
    {
        blackhole.consume(cache.get(HIT_KEY));
    }

    @Benchmark
    public void cacheMiss(Blackhole blackhole)
    {
        blackhole.consume(cache.get(MISS_KEY));
    }

    @Benchmark
    public void serializeForAdmission(Blackhole blackhole)
    {
        blackhole.consume(cache.put(HIT_KEY, value));
    }
}
