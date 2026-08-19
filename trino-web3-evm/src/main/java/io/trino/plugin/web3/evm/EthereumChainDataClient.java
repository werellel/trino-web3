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
package io.trino.plugin.web3.evm;

import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.trino.plugin.web3.adapter.ChainDataClient;
import io.trino.plugin.web3.adapter.ChainRow;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.DiscreteValueChainSplit;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.core.BlockRange;
import io.trino.plugin.web3.runtime.RemoteExecution;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public final class EthereumChainDataClient
        implements ChainDataClient
{
    private final EthereumBlockClient blockClient;
    private final EthereumTransactionClient transactionClient;

    public EthereumChainDataClient(RemoteExecutionRuntime runtime)
    {
        requireNonNull(runtime, "runtime is null");
        blockClient = new EthereumBlockClient(runtime);
        transactionClient = new EthereumTransactionClient(runtime);
    }

    public EthereumChainDataClient(EthereumBlockClient blockClient, EthereumTransactionClient transactionClient)
    {
        this.blockClient = requireNonNull(blockClient, "blockClient is null");
        this.transactionClient = requireNonNull(transactionClient, "transactionClient is null");
    }

    @Override
    public RemoteExecution<List<ChainRow>> execute(String tableName, ChainSplit split)
    {
        requireNonNull(tableName, "tableName is null");
        requireNonNull(split, "split is null");
        if (tableName.equals("blocks")) {
            if (!(split instanceof RangeChainSplit rangeSplit) || !rangeSplit.column().equals("block_number")) {
                throw new IllegalArgumentException("ethereum.blocks requires a block range split");
            }
            return blockClient.getBlocks(blockRange(rangeSplit))
                    .map(blocks -> blocks.stream().map(EthereumChainDataClient::blockRow).toList());
        }
        if (tableName.equals("transactions")) {
            if (split instanceof RangeChainSplit rangeSplit && rangeSplit.column().equals("block_number")) {
                return transactionClient.getTransactions(blockRange(rangeSplit))
                        .map(transactions -> transactions.stream().map(EthereumChainDataClient::transactionRow).toList());
            }
            if (split instanceof DiscreteValueChainSplit valueSplit && valueSplit.column().equals("hash")) {
                return transactionClient.getTransaction(valueSplit.value())
                        .map(transactions -> transactions.stream().map(EthereumChainDataClient::transactionRow).toList());
            }
            throw new IllegalArgumentException("ethereum.transactions requires a block range or transaction hash split");
        }
        throw new IllegalArgumentException("unknown executable Ethereum table " + tableName);
    }

    private static BlockRange blockRange(RangeChainSplit split)
    {
        return new BlockRange(split.startInclusive(), split.endInclusive());
    }

    private static ChainRow blockRow(EthereumBlockClient.EthereumBlock block)
    {
        return new ChainRow(Map.of(
                "block_number", LongNode.valueOf(block.number()),
                "block_hash", TextNode.valueOf(block.hash())));
    }

    private static ChainRow transactionRow(EthereumTransactionClient.EthereumTransaction transaction)
    {
        return new ChainRow(Map.of(
                "hash", TextNode.valueOf(transaction.hash()),
                "block_number", transaction.blockNumber() == null ? NullNode.instance : LongNode.valueOf(transaction.blockNumber()),
                "from_address", TextNode.valueOf(transaction.fromAddress()),
                "to_address", transaction.toAddress() == null ? NullNode.instance : TextNode.valueOf(transaction.toAddress())));
    }
}
