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
package io.trino.plugin.web3;

import io.trino.plugin.web3.runtime.RemoteExecutionMetrics;
import io.trino.spi.metrics.Metrics;

import java.util.Map;

/** Stable Trino page-source metric names for one Web3 execution. */
final class Web3Metrics
{
    static final String RPC_REQUESTS = "web3.rpc.requests";
    static final String RPC_FAILURES = "web3.rpc.failures";
    static final String RPC_RETRIES = "web3.rpc.retries";
    static final String RPC_THROTTLED = "web3.rpc.throttled";
    static final String RPC_IN_FLIGHT = "web3.rpc.in-flight";
    static final String RPC_FAILOVERS = "web3.rpc.failovers";
    static final String RPC_LATENCY_NANOS = "web3.rpc.latency-nanos";
    static final String RPC_BATCHES = "web3.rpc.batches";
    static final String RPC_BATCH_ITEMS = "web3.rpc.batch-items";
    static final String CACHE_HITS = "web3.cache.hits";
    static final String CACHE_MISSES = "web3.cache.misses";
    static final String CACHE_REVALIDATIONS = "web3.cache.revalidations";
    static final String CACHE_BYTES_READ = "web3.cache.bytes-read";
    static final String CACHE_BYTES_WRITTEN = "web3.cache.bytes-written";

    private Web3Metrics() {}

    public static Metrics forExecution(RemoteExecutionMetrics metrics)
    {
        return new Metrics(Map.ofEntries(
                Map.entry(RPC_REQUESTS, new Web3Count(metrics.requestCount())),
                Map.entry(RPC_FAILURES, new Web3Count(metrics.failureCount())),
                Map.entry(RPC_RETRIES, new Web3Count(metrics.retryCount())),
                Map.entry(RPC_THROTTLED, new Web3Count(metrics.throttledCount())),
                Map.entry(RPC_IN_FLIGHT, new Web3Count(metrics.inFlightRequests())),
                Map.entry(RPC_FAILOVERS, new Web3Count(metrics.failoverCount())),
                Map.entry(RPC_LATENCY_NANOS, new Web3Count(metrics.requestLatencyNanos())),
                Map.entry(RPC_BATCHES, new Web3Count(metrics.batchCount())),
                Map.entry(RPC_BATCH_ITEMS, new Web3Count(metrics.batchItemCount())),
                Map.entry(CACHE_HITS, new Web3Count(metrics.cacheHitCount())),
                Map.entry(CACHE_MISSES, new Web3Count(metrics.cacheMissCount())),
                Map.entry(CACHE_REVALIDATIONS, new Web3Count(metrics.cacheRevalidationCount())),
                Map.entry(CACHE_BYTES_READ, new Web3Count(metrics.cacheBytesRead())),
                Map.entry(CACHE_BYTES_WRITTEN, new Web3Count(metrics.cacheBytesWritten()))));
    }
}
