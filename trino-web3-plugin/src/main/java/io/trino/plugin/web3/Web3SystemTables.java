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

import io.trino.plugin.web3.adapter.ExecutableChainRegistry;
import io.trino.plugin.web3.runtime.RemoteExecutionMetrics;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteRuntimeSnapshot;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.InMemoryRecordSet;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SystemTable;
import io.trino.spi.predicate.TupleDomain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.trino.spi.connector.SystemTable.Distribution.SINGLE_COORDINATOR;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.util.Objects.requireNonNull;

/** Read-only local snapshots. These tables never invoke remote providers. */
final class Web3SystemTables
{
    private static final String SYSTEM_SCHEMA = "system";

    private Web3SystemTables() {}

    public static Set<SystemTable> create(ExecutableChainRegistry adapters, Map<String, RemoteExecutionRuntime> runtimes)
    {
        requireNonNull(adapters, "adapters is null");
        requireNonNull(runtimes, "runtimes is null");
        Set<SystemTable> tables = new LinkedHashSet<>();
        tables.add(new SnapshotSystemTable("chains", List.of(
                column("schema_name", VARCHAR),
                column("chain_name", VARCHAR),
                column("descriptor_api_version", VARCHAR),
                column("adapter_version", BIGINT),
                column("runtime_configured", BOOLEAN),
                column("protocol", VARCHAR),
                column("configured_provider_count", BIGINT),
                column("cache_enabled", BOOLEAN)), () -> chainRows(adapters, runtimes)));
        tables.add(new SnapshotSystemTable("providers", List.of(
                column("schema_name", VARCHAR),
                column("provider_name", VARCHAR),
                column("protocol", VARCHAR),
                column("json_rpc_batch_enabled", BOOLEAN),
                column("state", VARCHAR),
                column("cooldown_remaining_millis", BIGINT)), () -> providerRows(runtimes)));
        tables.add(new SnapshotSystemTable("rpc_metrics", List.of(
                column("schema_name", VARCHAR),
                column("request_count", BIGINT),
                column("failure_count", BIGINT),
                column("retry_count", BIGINT),
                column("throttled_count", BIGINT),
                column("in_flight_request_count", BIGINT),
                column("failover_count", BIGINT),
                column("request_latency_nanos", BIGINT),
                column("batch_count", BIGINT),
                column("batch_item_count", BIGINT),
                column("cache_hit_count", BIGINT),
                column("cache_miss_count", BIGINT),
                column("cache_revalidation_count", BIGINT),
                column("cache_bytes_read", BIGINT),
                column("cache_bytes_written", BIGINT)), () -> metricRows(runtimes)));
        tables.add(new SnapshotSystemTable("rate_limits", List.of(
                column("schema_name", VARCHAR),
                column("maximum_concurrency", BIGINT),
                column("maximum_queue_size", BIGINT),
                column("maximum_batch_size", BIGINT),
                column("maximum_attempts", BIGINT),
                column("requests_per_second", BIGINT),
                column("initial_backoff_millis", BIGINT),
                column("maximum_backoff_millis", BIGINT),
                column("provider_cooldown_millis", BIGINT)), () -> rateLimitRows(runtimes)));
        tables.add(new SnapshotSystemTable("cache_stats", List.of(
                column("schema_name", VARCHAR),
                column("cache_enabled", BOOLEAN),
                column("entry_count", BIGINT),
                column("retained_bytes", BIGINT),
                column("eviction_count", BIGINT)), () -> cacheRows(runtimes)));
        return Set.copyOf(tables);
    }

    private static ColumnMetadata column(String name, io.trino.spi.type.Type type)
    {
        return new ColumnMetadata(name, type);
    }

    private static List<List<Object>> chainRows(ExecutableChainRegistry adapters, Map<String, RemoteExecutionRuntime> runtimes)
    {
        return adapters.adapters().stream()
                .map(adapter -> {
                    var descriptor = adapter.descriptor();
                    RemoteExecutionRuntime runtime = runtimes.get(descriptor.schemaName());
                    if (runtime == null) {
                        return java.util.Arrays.<Object>asList(descriptor.schemaName(), descriptor.name(), descriptor.apiVersion(), (long) descriptor.adapterVersion(), false, null, 0L, false);
                    }
                    RemoteRuntimeSnapshot snapshot = runtime.snapshot();
                    return List.<Object>of(descriptor.schemaName(), descriptor.name(), descriptor.apiVersion(), (long) descriptor.adapterVersion(), true, snapshot.protocol().name(), (long) snapshot.providers().size(), snapshot.cacheEnabled());
                })
                .toList();
    }

    private static List<List<Object>> providerRows(Map<String, RemoteExecutionRuntime> runtimes)
    {
        return runtimes.entrySet().stream()
                .flatMap(entry -> {
                    RemoteRuntimeSnapshot snapshot = entry.getValue().snapshot();
                    return snapshot.providers().stream()
                        .map(provider -> List.<Object>of(
                                entry.getKey(),
                                provider.name(),
                                snapshot.protocol().name(),
                                provider.jsonRpcBatchEnabled(),
                                provider.state().name(),
                                provider.cooldownRemainingMillis()));
                })
                .toList();
    }

    private static List<List<Object>> metricRows(Map<String, RemoteExecutionRuntime> runtimes)
    {
        return runtimes.entrySet().stream()
                .map(entry -> metricRow(entry.getKey(), entry.getValue().snapshot().executionMetrics()))
                .toList();
    }

    private static List<Object> metricRow(String schemaName, RemoteExecutionMetrics metrics)
    {
        return List.of(
                schemaName,
                metrics.requestCount(),
                metrics.failureCount(),
                metrics.retryCount(),
                metrics.throttledCount(),
                metrics.inFlightRequests(),
                metrics.failoverCount(),
                metrics.requestLatencyNanos(),
                metrics.batchCount(),
                metrics.batchItemCount(),
                metrics.cacheHitCount(),
                metrics.cacheMissCount(),
                metrics.cacheRevalidationCount(),
                metrics.cacheBytesRead(),
                metrics.cacheBytesWritten());
    }

    private static List<List<Object>> rateLimitRows(Map<String, RemoteExecutionRuntime> runtimes)
    {
        return runtimes.entrySet().stream()
                .map(entry -> {
                    var policy = entry.getValue().snapshot().executionPolicy();
                    return List.<Object>of(
                            entry.getKey(),
                            (long) policy.maximumConcurrency(),
                            (long) policy.maximumQueueSize(),
                            (long) policy.maximumBatchSize(),
                            (long) policy.maximumAttempts(),
                            (long) policy.requestsPerSecond(),
                            policy.initialBackoff().toMillis(),
                            policy.maximumBackoff().toMillis(),
                            policy.providerCooldown().toMillis());
                })
                .toList();
    }

    private static List<List<Object>> cacheRows(Map<String, RemoteExecutionRuntime> runtimes)
    {
        return runtimes.entrySet().stream()
                .map(entry -> {
                    RemoteRuntimeSnapshot snapshot = entry.getValue().snapshot();
                    return List.<Object>of(
                            entry.getKey(),
                            snapshot.cacheEnabled(),
                            snapshot.cacheMetrics().entryCount(),
                            snapshot.cacheMetrics().retainedBytes(),
                            snapshot.cacheMetrics().evictionCount());
                })
                .toList();
    }

    private static final class SnapshotSystemTable
            implements SystemTable
    {
        private final ConnectorTableMetadata metadata;
        private final java.util.function.Supplier<List<List<Object>>> rows;

        private SnapshotSystemTable(String tableName, List<ColumnMetadata> columns, java.util.function.Supplier<List<List<Object>>> rows)
        {
            metadata = new ConnectorTableMetadata(new SchemaTableName(SYSTEM_SCHEMA, tableName), columns);
            this.rows = requireNonNull(rows, "rows is null");
        }

        @Override
        public Distribution getDistribution()
        {
            return SINGLE_COORDINATOR;
        }

        @Override
        public ConnectorTableMetadata getTableMetadata()
        {
            return metadata;
        }

        @Override
        public RecordCursor cursor(ConnectorTransactionHandle transaction, ConnectorSession session, TupleDomain<Integer> constraint)
        {
            requireNonNull(transaction, "transaction is null");
            requireNonNull(session, "session is null");
            requireNonNull(constraint, "constraint is null");
            InMemoryRecordSet.Builder builder = InMemoryRecordSet.builder(metadata);
            rows.get().forEach(row -> builder.addRow(row.toArray()));
            return builder.build().cursor();
        }
    }
}
