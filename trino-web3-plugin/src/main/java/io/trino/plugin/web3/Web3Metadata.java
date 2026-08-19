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

import io.airlift.slice.Slice;
import io.trino.plugin.web3.core.Web3ColumnHandle;
import io.trino.plugin.web3.core.BlockRange;
import io.trino.plugin.web3.core.Web3TableHandle;
import io.trino.plugin.web3.evm.EthereumBlocksTable;
import io.trino.plugin.web3.evm.EthereumTransactionsTable;
import io.trino.spi.StandardErrorCode;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTableVersion;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.ConstraintApplicationResult;
import io.trino.spi.connector.SchemaTableName;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static java.lang.Math.addExact;
import static java.lang.Math.subtractExact;

public final class Web3Metadata
        implements ConnectorMetadata
{
    private static final Pattern TRANSACTION_HASH_PATTERN = Pattern.compile("0x[0-9a-f]{64}");

    private final int maximumTransactionHashesPerQuery;

    public Web3Metadata(int maximumTransactionHashesPerQuery)
    {
        if (maximumTransactionHashesPerQuery < 1) {
            throw new IllegalArgumentException("maximumTransactionHashesPerQuery must be positive");
        }
        this.maximumTransactionHashesPerQuery = maximumTransactionHashesPerQuery;
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return List.of(EthereumBlocksTable.SCHEMA_NAME);
    }

    @Override
    public ConnectorTableHandle getTableHandle(
            ConnectorSession session,
            SchemaTableName tableName,
            Optional<ConnectorTableVersion> startVersion,
            Optional<ConnectorTableVersion> endVersion)
    {
        if (tableName.equals(EthereumBlocksTable.TABLE_METADATA.getTable()) ||
                tableName.equals(EthereumTransactionsTable.TABLE_METADATA.getTable())) {
            return new Web3TableHandle(tableName.getSchemaName(), tableName.getTableName(), Optional.empty());
        }
        return null;
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle table)
    {
        if (isBlocksTable(table)) {
            return EthereumBlocksTable.TABLE_METADATA;
        }
        verifyTransactionsTable(table);
        return EthereumTransactionsTable.TABLE_METADATA;
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> schemaName)
    {
        if (schemaName.isEmpty() || schemaName.get().equals(EthereumBlocksTable.SCHEMA_NAME)) {
            return List.of(EthereumBlocksTable.TABLE_METADATA.getTable(), EthereumTransactionsTable.TABLE_METADATA.getTable());
        }
        return List.of();
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle table)
    {
        if (isBlocksTable(table)) {
            return Map.copyOf(EthereumBlocksTable.columnHandles());
        }
        verifyTransactionsTable(table);
        return Map.copyOf(EthereumTransactionsTable.columnHandles());
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle table, ColumnHandle column)
    {
        if (!(column instanceof Web3ColumnHandle web3Column)) {
            throw new IllegalArgumentException("column is not a Web3 column handle");
        }
        if (isBlocksTable(table)) {
            return EthereumBlocksTable.columnMetadata(web3Column);
        }
        verifyTransactionsTable(table);
        return EthereumTransactionsTable.columnMetadata(web3Column);
    }

    @Override
    public Optional<ConstraintApplicationResult<ConnectorTableHandle>> applyFilter(
            ConnectorSession session,
            ConnectorTableHandle table,
            Constraint constraint)
    {
        verifyEthereumTable(table);
        Web3TableHandle web3Table = (Web3TableHandle) table;
        if (isTransactionsTable(table) && web3Table.transactionHashes().isEmpty() && web3Table.blockRange().isEmpty()) {
            Optional<List<String>> transactionHashes = constraint.getSummary().getDomains()
                    .map(domains -> domains.get(EthereumTransactionsTable.HASH_COLUMN))
                    .flatMap(this::toTransactionHashes);
            if (transactionHashes.isPresent()) {
                Web3TableHandle newTable = web3Table.withTransactionHashes(transactionHashes.orElseThrow());
                return Optional.of(new ConstraintApplicationResult<>(
                        newTable,
                        constraint.getSummary(),
                        constraint.getExpression(),
                        false));
            }
        }
        if (!web3Table.transactionHashes().isEmpty()) {
            return Optional.empty();
        }
        Web3ColumnHandle blockNumberColumn = isBlocksTable(table) ?
                EthereumBlocksTable.BLOCK_NUMBER_COLUMN : EthereumTransactionsTable.BLOCK_NUMBER_COLUMN;
        Optional<BlockRange> pushedRange = constraint.getSummary().getDomains()
                .map(domains -> domains.get(blockNumberColumn))
                .flatMap(Web3Metadata::toBoundedBlockRange)
                .flatMap(range -> web3Table.blockRange()
                        .map(existingRange -> intersect(existingRange, range))
                        .orElse(Optional.of(range)));

        if (pushedRange.isEmpty() || pushedRange.equals(web3Table.blockRange())) {
            return Optional.empty();
        }

        Web3TableHandle newTable = web3Table.withBlockRange(pushedRange.orElseThrow());
        return Optional.of(new ConstraintApplicationResult<>(
                newTable,
                constraint.getSummary().filter((column, domain) -> !column.equals(blockNumberColumn)),
                constraint.getExpression(),
                false));
    }

    private Optional<List<String>> toTransactionHashes(io.trino.spi.predicate.Domain domain)
    {
        if (domain == null || domain.isNullAllowed() || !domain.getValues().isDiscreteSet()) {
            return Optional.empty();
        }
        TreeSet<String> hashes = new TreeSet<>();
        for (Object value : domain.getValues().getDiscreteSet()) {
            String hash = ((Slice) value).toStringUtf8().toLowerCase(Locale.ENGLISH);
            if (!TRANSACTION_HASH_PATTERN.matcher(hash).matches()) {
                return Optional.empty();
            }
            hashes.add(hash);
            if (hashes.size() > maximumTransactionHashesPerQuery) {
                throw new TrinoException(
                        StandardErrorCode.NOT_SUPPORTED,
                        "ethereum.transactions hash predicate exceeds the configured query limit of " + maximumTransactionHashesPerQuery);
            }
        }
        if (hashes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(hashes));
    }

    private static Optional<BlockRange> toBoundedBlockRange(io.trino.spi.predicate.Domain domain)
    {
        if (domain == null || domain.isNullAllowed() || domain.getValues().isNone() || domain.getValues().isAll()) {
            return Optional.empty();
        }
        List<io.trino.spi.predicate.Range> ranges = domain.getValues().getRanges().getOrderedRanges();
        if (ranges.size() != 1) {
            return Optional.empty();
        }
        io.trino.spi.predicate.Range range = ranges.getFirst();
        if (range.isLowUnbounded() || range.isHighUnbounded()) {
            return Optional.empty();
        }
        long start = (long) range.getLowBoundedValue();
        long end = (long) range.getHighBoundedValue();
        if (!range.isLowInclusive()) {
            start = addExact(start, 1);
        }
        if (!range.isHighInclusive()) {
            end = subtractExact(end, 1);
        }
        if (start < 0 || end < start) {
            return Optional.empty();
        }
        return Optional.of(new BlockRange(start, end));
    }

    private static Optional<BlockRange> intersect(BlockRange left, BlockRange right)
    {
        long start = Math.max(left.startInclusive(), right.startInclusive());
        long end = Math.min(left.endInclusive(), right.endInclusive());
        if (end < start) {
            return Optional.empty();
        }
        return Optional.of(new BlockRange(start, end));
    }

    private static boolean isBlocksTable(ConnectorTableHandle table)
    {
        return table instanceof Web3TableHandle web3Table &&
                web3Table.schemaName().equals(EthereumBlocksTable.SCHEMA_NAME) &&
                web3Table.tableName().equals(EthereumBlocksTable.TABLE_NAME);
    }

    private static void verifyTransactionsTable(ConnectorTableHandle table)
    {
        if (!(table instanceof Web3TableHandle web3Table) ||
                !web3Table.schemaName().equals(EthereumTransactionsTable.SCHEMA_NAME) ||
                !web3Table.tableName().equals(EthereumTransactionsTable.TABLE_NAME)) {
            throw new IllegalArgumentException("table is not ethereum.transactions");
        }
    }

    private static boolean isTransactionsTable(ConnectorTableHandle table)
    {
        return table instanceof Web3TableHandle web3Table &&
                web3Table.schemaName().equals(EthereumTransactionsTable.SCHEMA_NAME) &&
                web3Table.tableName().equals(EthereumTransactionsTable.TABLE_NAME);
    }

    private static void verifyEthereumTable(ConnectorTableHandle table)
    {
        if (!isBlocksTable(table)) {
            verifyTransactionsTable(table);
        }
    }
}
