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

import com.fasterxml.jackson.databind.JsonNode;
import io.airlift.slice.Slices;
import io.trino.plugin.web3.adapter.ChainDataClient;
import io.trino.plugin.web3.adapter.ChainRow;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.DiscreteValueChainSplit;
import io.trino.plugin.web3.adapter.ExecutableChainRegistry;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.core.Web3ColumnHandle;
import io.trino.plugin.web3.core.Web3DiscreteValueSplit;
import io.trino.plugin.web3.core.Web3RangeSplit;
import io.trino.plugin.web3.core.Web3Split;
import io.trino.plugin.web3.core.Web3TableHandle;
import io.trino.plugin.web3.core.Web3TransactionHashSplit;
import io.trino.plugin.web3.evm.EthereumBlockClient;
import io.trino.plugin.web3.evm.EthereumChainAdapter;
import io.trino.plugin.web3.evm.EthereumChainDataClient;
import io.trino.plugin.web3.evm.EthereumTransactionClient;
import io.trino.plugin.web3.runtime.RemoteExecution;
import io.trino.plugin.web3.runtime.RemoteExecutionMetrics;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.spi.PageBuilder;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.ConnectorPageSourceProvider;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorSplit;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.DynamicFilter;
import io.trino.spi.connector.SourcePage;
import io.trino.spi.metrics.Metrics;
import io.trino.spi.type.Type;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.util.Objects.requireNonNull;

public final class Web3PageSourceProvider
        implements ConnectorPageSourceProvider
{
    private final ChainMetadataRegistry tables;
    private final Map<String, ChainDataClient> clientsBySchema;

    public Web3PageSourceProvider(EthereumBlockClient blockClient, EthereumTransactionClient transactionClient)
    {
        this(
                ExecutableChainRegistry.of(new EthereumChainAdapter()),
                Map.of("ethereum", new EthereumChainDataClient(blockClient, transactionClient)),
                Web3Metadata::resolveBuiltInType);
    }

    static Web3PageSourceProvider forRuntimes(
            ExecutableChainRegistry adapters,
            Map<String, RemoteExecutionRuntime> runtimes,
            Function<String, Type> typeResolver)
    {
        return new Web3PageSourceProvider(adapters, createClients(adapters, runtimes), typeResolver);
    }

    private Web3PageSourceProvider(
            ExecutableChainRegistry adapters,
            Map<String, ChainDataClient> clientsBySchema,
            Function<String, Type> typeResolver)
    {
        requireNonNull(adapters, "adapters is null");
        tables = new ChainMetadataRegistry(adapters.descriptors(), requireNonNull(typeResolver, "typeResolver is null"));
        this.clientsBySchema = Map.copyOf(requireNonNull(clientsBySchema, "clientsBySchema is null"));
        if (!new java.util.HashSet<>(tables.schemas()).containsAll(this.clientsBySchema.keySet())) {
            throw new IllegalArgumentException("data clients contain an unknown executable chain schema");
        }
    }

    @Override
    public ConnectorPageSource createPageSource(
            ConnectorTransactionHandle transaction,
            ConnectorSession session,
            ConnectorSplit split,
            ConnectorTableHandle table,
            List<ColumnHandle> columns,
            DynamicFilter dynamicFilter)
    {
        if (!(table instanceof Web3TableHandle web3Table)) {
            throw new IllegalArgumentException("table is not a Web3 table handle");
        }
        ChainSplit chainSplit = toChainSplit(split);
        ChainMetadataRegistry.ResolvedTable resolvedTable = tables.table(web3Table)
                .orElseThrow(() -> new IllegalArgumentException("unknown executable chain table " + web3Table.schemaName() + "." + web3Table.tableName()));
        List<ProjectedColumn> projectedColumns = columns.stream()
                .map(column -> projectedColumn(resolvedTable, column))
                .toList();
        ChainDataClient client = clientsBySchema.get(web3Table.schemaName());
        if (client == null) {
            throw new IllegalStateException("no remote endpoint is configured for schema " + web3Table.schemaName());
        }
        RemoteExecution<List<ChainRow>> execution = client.execute(web3Table.tableName(), chainSplit);
        return new ChainPageSource(execution, projectedColumns);
    }

    private static ChainSplit toChainSplit(ConnectorSplit split)
    {
        requireNonNull(split, "split is null");
        if (split instanceof Web3RangeSplit rangeSplit) {
            return new RangeChainSplit(
                    rangeSplit.column(),
                    rangeSplit.startInclusive(),
                    rangeSplit.endInclusive());
        }
        if (split instanceof Web3DiscreteValueSplit valueSplit) {
            return new DiscreteValueChainSplit(valueSplit.column(), valueSplit.value());
        }
        // Accept M1-M3 split values so in-process callers and rolling upgrades
        // retain the established Ethereum serialization contract.
        if (split instanceof Web3Split rangeSplit) {
            return new RangeChainSplit(
                    "block_number",
                    rangeSplit.blockRange().startInclusive(),
                    rangeSplit.blockRange().endInclusive());
        }
        if (split instanceof Web3TransactionHashSplit valueSplit) {
            return new DiscreteValueChainSplit("hash", valueSplit.transactionHash());
        }
        throw new IllegalArgumentException("split is not a supported Web3 chain split");
    }

    private static Map<String, ChainDataClient> createClients(ExecutableChainRegistry adapters, Map<String, RemoteExecutionRuntime> runtimes)
    {
        requireNonNull(adapters, "adapters is null");
        requireNonNull(runtimes, "runtimes is null");
        Map<String, ChainDataClient> clients = new LinkedHashMap<>();
        adapters.adapters().forEach(adapter -> {
            String schemaName = adapter.descriptor().schemaName();
            RemoteExecutionRuntime runtime = runtimes.get(schemaName);
            if (runtime != null) {
                clients.put(schemaName, requireNonNull(adapter.createDataClient(runtime), "adapter data client is null"));
            }
        });
        return Map.copyOf(clients);
    }

    private static ProjectedColumn projectedColumn(ChainMetadataRegistry.ResolvedTable table, ColumnHandle column)
    {
        if (!(column instanceof Web3ColumnHandle web3Column)) {
            throw new IllegalArgumentException("column is not a Web3 column handle");
        }
        ColumnMetadata metadata = table.columnMetadata(web3Column);
        boolean nullable = table.descriptor().columns().get(web3Column.ordinal()).nullable();
        return new ProjectedColumn(web3Column.name(), metadata.getType(), nullable);
    }

    private static final class ChainPageSource
            implements ConnectorPageSource
    {
        private final RemoteExecution<List<ChainRow>> rows;
        private final List<ProjectedColumn> columns;
        private boolean finished;
        private long completedPositions;

        private ChainPageSource(RemoteExecution<List<ChainRow>> rows, List<ProjectedColumn> columns)
        {
            this.rows = requireNonNull(rows, "rows is null");
            this.columns = List.copyOf(requireNonNull(columns, "columns is null"));
        }

        @Override
        public long getCompletedBytes()
        {
            return 0;
        }

        @Override
        public OptionalLong getCompletedPositions()
        {
            return OptionalLong.of(completedPositions);
        }

        @Override
        public long getReadTimeNanos()
        {
            return 0;
        }

        @Override
        public boolean isFinished()
        {
            return finished;
        }

        @Override
        public CompletableFuture<?> isBlocked()
        {
            return rows.future();
        }

        @Override
        public SourcePage getNextSourcePage()
        {
            if (finished || !rows.future().isDone()) {
                return null;
            }
            List<ChainRow> resolvedRows = rows.future().join();
            PageBuilder pageBuilder = new PageBuilder(columns.stream().map(ProjectedColumn::type).toList());
            for (ChainRow row : resolvedRows) {
                pageBuilder.declarePosition();
                for (int channel = 0; channel < columns.size(); channel++) {
                    writeValue(pageBuilder, channel, columns.get(channel), row.value(columns.get(channel).name()));
                }
            }
            finished = true;
            completedPositions = resolvedRows.size();
            return SourcePage.create(pageBuilder.build());
        }

        @Override
        public long getMemoryUsage()
        {
            if (finished || !rows.future().isDone() || rows.future().isCompletedExceptionally() || rows.future().isCancelled()) {
                return rows.memoryUsage();
            }
            return rows.memoryUsage() + rows.future().getNow(List.of()).stream()
                    .mapToLong(ChainRow::retainedSizeInBytes)
                    .sum();
        }

        @Override
        public Metrics getMetrics()
        {
            return toMetrics(rows.metrics());
        }

        @Override
        public void close()
        {
            finished = true;
            rows.future().cancel(true);
        }
    }

    private static void writeValue(PageBuilder pageBuilder, int channel, ProjectedColumn column, JsonNode value)
    {
        if (value.isNull()) {
            if (!column.nullable()) {
                throw new IllegalStateException("chain row has null for non-nullable column " + column.name());
            }
            pageBuilder.getBlockBuilder(channel).appendNull();
            return;
        }
        if (column.type().equals(BIGINT)) {
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                throw new IllegalStateException("chain row has invalid bigint column " + column.name());
            }
            BIGINT.writeLong(pageBuilder.getBlockBuilder(channel), value.longValue());
            return;
        }
        if (column.type().equals(BOOLEAN)) {
            if (!value.isBoolean()) {
                throw new IllegalStateException("chain row has invalid boolean column " + column.name());
            }
            BOOLEAN.writeBoolean(pageBuilder.getBlockBuilder(channel), value.booleanValue());
            return;
        }
        if (column.type().equals(VARCHAR)) {
            if (!value.isTextual()) {
                throw new IllegalStateException("chain row has invalid varchar column " + column.name());
            }
            VARCHAR.writeSlice(pageBuilder.getBlockBuilder(channel), Slices.utf8Slice(value.textValue()));
            return;
        }
        throw new IllegalStateException("no chain row writer for Trino type " + column.type());
    }

    private static Metrics toMetrics(RemoteExecutionMetrics metrics)
    {
        return new Metrics(Map.ofEntries(
                Map.entry("web3.rpc.requests", new Web3Count(metrics.requestCount())),
                Map.entry("web3.rpc.failures", new Web3Count(metrics.failureCount())),
                Map.entry("web3.rpc.retries", new Web3Count(metrics.retryCount())),
                Map.entry("web3.rpc.throttled", new Web3Count(metrics.throttledCount())),
                Map.entry("web3.rpc.in-flight", new Web3Count(metrics.inFlightRequests())),
                Map.entry("web3.rpc.failovers", new Web3Count(metrics.failoverCount())),
                Map.entry("web3.rpc.latency-nanos", new Web3Count(metrics.requestLatencyNanos())),
                Map.entry("web3.rpc.batches", new Web3Count(metrics.batchCount())),
                Map.entry("web3.rpc.batch-items", new Web3Count(metrics.batchItemCount())),
                Map.entry("web3.cache.hits", new Web3Count(metrics.cacheHitCount())),
                Map.entry("web3.cache.misses", new Web3Count(metrics.cacheMissCount())),
                Map.entry("web3.cache.revalidations", new Web3Count(metrics.cacheRevalidationCount())),
                Map.entry("web3.cache.bytes-read", new Web3Count(metrics.cacheBytesRead())),
                Map.entry("web3.cache.bytes-written", new Web3Count(metrics.cacheBytesWritten()))));
    }

    private record ProjectedColumn(String name, Type type, boolean nullable)
    {
        private ProjectedColumn
        {
            requireNonNull(name, "name is null");
            requireNonNull(type, "type is null");
        }
    }
}
