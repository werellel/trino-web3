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

import io.trino.plugin.web3.core.BlockRangeSplitter;
import io.trino.plugin.web3.core.Web3TableHandle;
import io.trino.plugin.web3.core.Web3TransactionHashSplit;
import io.trino.spi.StandardErrorCode;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorSplitManager;
import io.trino.spi.connector.ConnectorSplitSource;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.DynamicFilter;
import io.trino.spi.connector.FixedSplitSource;

import static java.util.Objects.requireNonNull;

public final class Web3SplitManager
        implements ConnectorSplitManager
{
    private final long maximumBlocksPerSplit;
    private final long maximumBlocksPerQuery;
    private final int maximumTransactionHashesPerQuery;

    public Web3SplitManager(long maximumBlocksPerSplit, long maximumBlocksPerQuery)
    {
        this(maximumBlocksPerSplit, maximumBlocksPerQuery, 1_000);
    }

    public Web3SplitManager(long maximumBlocksPerSplit, long maximumBlocksPerQuery, int maximumTransactionHashesPerQuery)
    {
        if (maximumBlocksPerSplit < 1) {
            throw new IllegalArgumentException("maximumBlocksPerSplit must be positive");
        }
        if (maximumBlocksPerQuery < maximumBlocksPerSplit) {
            throw new IllegalArgumentException("maximumBlocksPerQuery is smaller than maximumBlocksPerSplit");
        }
        this.maximumBlocksPerSplit = maximumBlocksPerSplit;
        this.maximumBlocksPerQuery = maximumBlocksPerQuery;
        if (maximumTransactionHashesPerQuery < 1) {
            throw new IllegalArgumentException("maximumTransactionHashesPerQuery must be positive");
        }
        this.maximumTransactionHashesPerQuery = maximumTransactionHashesPerQuery;
    }

    @Override
    public ConnectorSplitSource getSplits(
            ConnectorTransactionHandle transaction,
            ConnectorSession session,
            ConnectorTableHandle table,
            DynamicFilter dynamicFilter,
            Constraint constraint)
    {
        requireNonNull(transaction, "transaction is null");
        requireNonNull(session, "session is null");
        requireNonNull(dynamicFilter, "dynamicFilter is null");
        requireNonNull(constraint, "constraint is null");

        if (!(table instanceof Web3TableHandle web3Table)) {
            throw new IllegalArgumentException("table is not a Web3 table handle");
        }
        if (!web3Table.transactionHashes().isEmpty()) {
            if (web3Table.transactionHashes().size() > maximumTransactionHashesPerQuery) {
                throw new TrinoException(
                        StandardErrorCode.NOT_SUPPORTED,
                        "ethereum.transactions hash predicate exceeds the configured query limit of " + maximumTransactionHashesPerQuery);
            }
            return new FixedSplitSource(web3Table.transactionHashes().stream()
                    .map(Web3TransactionHashSplit::new)
                    .map(io.trino.spi.connector.ConnectorSplit.class::cast)
                    .toList());
        }
        return web3Table.blockRange()
                .<ConnectorSplitSource>map(range -> new FixedSplitSource(BlockRangeSplitter.split(range, maximumBlocksPerSplit, maximumBlocksPerQuery)))
                .orElseThrow(() -> new TrinoException(
                        StandardErrorCode.NOT_SUPPORTED,
                        web3Table.schemaName() + "." + web3Table.tableName() + " requires a bounded block_number predicate" +
                                (web3Table.tableName().equals("transactions") ? " or transaction hash equality/IN predicate" : "")));
    }
}
