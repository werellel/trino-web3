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

import io.airlift.slice.Slices;
import io.trino.plugin.web3.core.Web3ColumnHandle;
import io.trino.plugin.web3.core.Web3Split;
import io.trino.plugin.web3.core.Web3TableHandle;
import io.trino.plugin.web3.evm.EthereumBlockClient;
import io.trino.plugin.web3.evm.EthereumTransactionClient;
import io.trino.plugin.web3.runtime.RemoteExecution;
import io.trino.plugin.web3.runtime.RemoteExecutionMetrics;
import io.trino.spi.PageBuilder;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.ConnectorPageSourceProvider;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorSplit;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.DynamicFilter;
import io.trino.spi.connector.SourcePage;
import io.trino.spi.metrics.Metrics;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static java.util.Objects.requireNonNull;

public final class Web3PageSourceProvider
        implements ConnectorPageSourceProvider
{
    private final EthereumBlockClient blockClient;
    private final EthereumTransactionClient transactionClient;

    public Web3PageSourceProvider(EthereumBlockClient blockClient, EthereumTransactionClient transactionClient)
    {
        this.blockClient = requireNonNull(blockClient, "blockClient is null");
        this.transactionClient = requireNonNull(transactionClient, "transactionClient is null");
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
        if (!(split instanceof Web3Split web3Split)) {
            throw new IllegalArgumentException("split is not a Web3 split");
        }
        if (!(table instanceof Web3TableHandle web3Table)) {
            throw new IllegalArgumentException("table is not a Web3 table handle");
        }
        List<Web3ColumnHandle> web3Columns = columns.stream()
                .map(column -> {
                    if (!(column instanceof Web3ColumnHandle web3Column)) {
                        throw new IllegalArgumentException("column is not a Web3 column handle");
                    }
                    return web3Column;
                })
                .toList();
        if (web3Table.tableName().equals("blocks")) {
            return new EthereumBlocksPageSource(blockClient.getBlocks(web3Split.blockRange()), web3Columns);
        }
        if (web3Table.tableName().equals("transactions")) {
            return new EthereumTransactionsPageSource(transactionClient.getTransactions(web3Split.blockRange()), web3Columns);
        }
        throw new IllegalArgumentException("table is not a supported Ethereum table");
    }

    private static final class EthereumBlocksPageSource
            implements ConnectorPageSource
    {
        private final RemoteExecution<List<EthereumBlockClient.EthereumBlock>> blocks;
        private final List<Web3ColumnHandle> columns;
        private boolean finished;
        private long completedPositions;

        private EthereumBlocksPageSource(
                RemoteExecution<List<EthereumBlockClient.EthereumBlock>> blocks,
                List<Web3ColumnHandle> columns)
        {
            this.blocks = requireNonNull(blocks, "blocks is null");
            this.columns = List.copyOf(columns);
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
            return blocks.future();
        }

        @Override
        public SourcePage getNextSourcePage()
        {
            if (finished || !blocks.future().isDone()) {
                return null;
            }
            List<EthereumBlockClient.EthereumBlock> resolvedBlocks = blocks.future().join();
            PageBuilder pageBuilder = new PageBuilder(columns.stream()
                    .map(column -> column.ordinal() == 0 ? BIGINT : VARCHAR)
                    .toList());
            for (EthereumBlockClient.EthereumBlock block : resolvedBlocks) {
                pageBuilder.declarePosition();
                for (int channel = 0; channel < columns.size(); channel++) {
                    if (columns.get(channel).ordinal() == 0) {
                        BIGINT.writeLong(pageBuilder.getBlockBuilder(channel), block.number());
                    }
                    else {
                        VARCHAR.writeSlice(pageBuilder.getBlockBuilder(channel), Slices.utf8Slice(block.hash()));
                    }
                }
            }
            finished = true;
            completedPositions = resolvedBlocks.size();
            return SourcePage.create(pageBuilder.build());
        }

        @Override
        public long getMemoryUsage()
        {
            return 0;
        }

        @Override
        public Metrics getMetrics()
        {
            return toMetrics(blocks.metrics());
        }

        @Override
        public void close()
                throws IOException
        {
            finished = true;
            blocks.future().cancel(true);
        }
    }

    private static final class EthereumTransactionsPageSource
            implements ConnectorPageSource
    {
        private final RemoteExecution<List<EthereumTransactionClient.EthereumTransaction>> transactions;
        private final List<Web3ColumnHandle> columns;
        private boolean finished;
        private long completedPositions;

        private EthereumTransactionsPageSource(
                RemoteExecution<List<EthereumTransactionClient.EthereumTransaction>> transactions,
                List<Web3ColumnHandle> columns)
        {
            this.transactions = requireNonNull(transactions, "transactions is null");
            this.columns = List.copyOf(columns);
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
            return transactions.future();
        }

        @Override
        public SourcePage getNextSourcePage()
        {
            if (finished || !transactions.future().isDone()) {
                return null;
            }
            List<EthereumTransactionClient.EthereumTransaction> resolvedTransactions = transactions.future().join();
            PageBuilder pageBuilder = new PageBuilder(columns.stream()
                    .map(EthereumTransactionsPageSource::typeFor)
                    .toList());
            for (EthereumTransactionClient.EthereumTransaction transaction : resolvedTransactions) {
                pageBuilder.declarePosition();
                for (int channel = 0; channel < columns.size(); channel++) {
                    writeTransactionValue(pageBuilder, channel, columns.get(channel), transaction);
                }
            }
            finished = true;
            completedPositions = resolvedTransactions.size();
            return SourcePage.create(pageBuilder.build());
        }

        private static io.trino.spi.type.Type typeFor(Web3ColumnHandle column)
        {
            return column.ordinal() == 1 ? BIGINT : VARCHAR;
        }

        private static void writeTransactionValue(
                PageBuilder pageBuilder,
                int channel,
                Web3ColumnHandle column,
                EthereumTransactionClient.EthereumTransaction transaction)
        {
            switch (column.ordinal()) {
                case 0 -> VARCHAR.writeSlice(pageBuilder.getBlockBuilder(channel), Slices.utf8Slice(transaction.hash()));
                case 1 -> BIGINT.writeLong(pageBuilder.getBlockBuilder(channel), transaction.blockNumber());
                case 2 -> VARCHAR.writeSlice(pageBuilder.getBlockBuilder(channel), Slices.utf8Slice(transaction.fromAddress()));
                case 3 -> {
                    if (transaction.toAddress() == null) {
                        pageBuilder.getBlockBuilder(channel).appendNull();
                    }
                    else {
                        VARCHAR.writeSlice(pageBuilder.getBlockBuilder(channel), Slices.utf8Slice(transaction.toAddress()));
                    }
                }
                default -> throw new IllegalArgumentException("unknown Ethereum transactions column");
            }
        }

        @Override
        public long getMemoryUsage()
        {
            return 0;
        }

        @Override
        public Metrics getMetrics()
        {
            return toMetrics(transactions.metrics());
        }

        @Override
        public void close()
                throws IOException
        {
            finished = true;
            transactions.future().cancel(true);
        }
    }

    private static Metrics toMetrics(RemoteExecutionMetrics metrics)
    {
        return new Metrics(Map.of(
                "web3.rpc.requests", new Web3Count(metrics.requestCount()),
                "web3.rpc.failures", new Web3Count(metrics.failureCount()),
                "web3.rpc.retries", new Web3Count(metrics.retryCount()),
                "web3.rpc.throttled", new Web3Count(metrics.throttledCount()),
                "web3.rpc.in-flight", new Web3Count(metrics.inFlightRequests()),
                "web3.rpc.failovers", new Web3Count(metrics.failoverCount()),
                "web3.rpc.latency-nanos", new Web3Count(metrics.requestLatencyNanos()),
                "web3.rpc.batches", new Web3Count(metrics.batchCount()),
                "web3.rpc.batch-items", new Web3Count(metrics.batchItemCount())));
    }
}
