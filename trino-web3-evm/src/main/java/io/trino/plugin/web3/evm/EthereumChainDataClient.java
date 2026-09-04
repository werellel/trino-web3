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
    private final String chainName;
    private final EthereumBlockClient blockClient;
    private final EthereumTransactionClient transactionClient;
    private final EthereumReceiptClient receiptClient;
    private final EthereumLogClient logClient;

    public EthereumChainDataClient(RemoteExecutionRuntime runtime)
    {
        this(runtime, "ethereum");
    }

    public EthereumChainDataClient(RemoteExecutionRuntime runtime, String chainName)
    {
        requireNonNull(runtime, "runtime is null");
        this.chainName = requireNonNull(chainName, "chainName is null");
        blockClient = new EthereumBlockClient(runtime, chainName);
        transactionClient = new EthereumTransactionClient(runtime, chainName);
        receiptClient = new EthereumReceiptClient(runtime);
        logClient = new EthereumLogClient(runtime);
    }

    public EthereumChainDataClient(EthereumBlockClient blockClient, EthereumTransactionClient transactionClient)
    {
        this(blockClient, transactionClient, "ethereum");
    }

    EthereumChainDataClient(EthereumBlockClient blockClient, EthereumTransactionClient transactionClient, String chainName)
    {
        this.chainName = requireNonNull(chainName, "chainName is null");
        this.blockClient = requireNonNull(blockClient, "blockClient is null");
        this.transactionClient = requireNonNull(transactionClient, "transactionClient is null");
        this.receiptClient = new EthereumReceiptClient(blockClient.runtime());
        this.logClient = new EthereumLogClient(blockClient.runtime());
    }

    @Override
    public RemoteExecution<List<ChainRow>> execute(String tableName, ChainSplit split)
    {
        requireNonNull(tableName, "tableName is null");
        requireNonNull(split, "split is null");
        if (tableName.equals("blocks")) {
            if (!(split instanceof RangeChainSplit rangeSplit) || !rangeSplit.column().equals("block_number")) {
                throw new IllegalArgumentException(chainName + ".blocks requires a block range split");
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
            throw new IllegalArgumentException(chainName + ".transactions requires a block range or transaction hash split");
        }
        if (tableName.equals("receipts")) {
            if (!(split instanceof DiscreteValueChainSplit valueSplit) || !valueSplit.column().equals("transaction_hash")) {
                throw new IllegalArgumentException(chainName + ".receipts requires a transaction hash split");
            }
            return receiptClient.getReceipt(valueSplit.value())
                    .map(receipts -> receipts.stream().map(EthereumChainDataClient::receiptRow).toList());
        }
        if (tableName.equals("logs")) {
            if (!(split instanceof RangeChainSplit rangeSplit) || !rangeSplit.column().equals("block_number")) {
                throw new IllegalArgumentException(chainName + ".logs requires a block range split");
            }
            return logClient.getLogs(blockRange(rangeSplit))
                    .map(logs -> logs.stream().map(EthereumChainDataClient::logRow).toList());
        }
        throw new IllegalArgumentException("unknown executable " + chainName + " table " + tableName);
    }

    private static BlockRange blockRange(RangeChainSplit split)
    {
        return new BlockRange(split.startInclusive(), split.endInclusive());
    }

    private static ChainRow blockRow(EthereumBlockClient.EthereumBlock block)
    {
        return new ChainRow(Map.of(
                "block_number", LongNode.valueOf(block.number()),
                "block_hash", TextNode.valueOf(block.hash()),
                "raw_json", TextNode.valueOf(block.rawJson())));
    }

    private static ChainRow transactionRow(EthereumTransactionClient.EthereumTransaction transaction)
    {
        return new ChainRow(Map.of(
                "hash", TextNode.valueOf(transaction.hash()),
                "block_number", transaction.blockNumber() == null ? NullNode.instance : LongNode.valueOf(transaction.blockNumber()),
                "from_address", TextNode.valueOf(transaction.fromAddress()),
                "to_address", transaction.toAddress() == null ? NullNode.instance : TextNode.valueOf(transaction.toAddress()),
                "raw_json", TextNode.valueOf(transaction.rawJson())));
    }

    private static ChainRow receiptRow(EthereumReceiptClient.EthereumReceipt receipt)
    {
        return new ChainRow(Map.ofEntries(
                Map.entry("transaction_hash", TextNode.valueOf(receipt.transactionHash())),
                Map.entry("transaction_index", receipt.transactionIndex() == null ? NullNode.instance : LongNode.valueOf(receipt.transactionIndex())),
                Map.entry("block_number", receipt.blockNumber() == null ? NullNode.instance : LongNode.valueOf(receipt.blockNumber())),
                Map.entry("block_hash", receipt.blockHash() == null ? NullNode.instance : TextNode.valueOf(receipt.blockHash())),
                Map.entry("from_address", TextNode.valueOf(receipt.fromAddress())),
                Map.entry("to_address", receipt.toAddress() == null ? NullNode.instance : TextNode.valueOf(receipt.toAddress())),
                Map.entry("contract_address", receipt.contractAddress() == null ? NullNode.instance : TextNode.valueOf(receipt.contractAddress())),
                Map.entry("cumulative_gas_used", receipt.cumulativeGasUsed() == null ? NullNode.instance : LongNode.valueOf(receipt.cumulativeGasUsed())),
                Map.entry("gas_used", receipt.gasUsed() == null ? NullNode.instance : LongNode.valueOf(receipt.gasUsed())),
                Map.entry("status", receipt.status() == null ? NullNode.instance : LongNode.valueOf(receipt.status())),
                Map.entry("logs_bloom", receipt.logsBloom() == null ? NullNode.instance : TextNode.valueOf(receipt.logsBloom())),
                Map.entry("raw_json", TextNode.valueOf(receipt.rawJson()))));
    }

    private static ChainRow logRow(EthereumLogClient.EthereumLog log)
    {
        return new ChainRow(Map.ofEntries(
                Map.entry("block_number", LongNode.valueOf(log.blockNumber())),
                Map.entry("block_hash", log.blockHash() == null ? NullNode.instance : TextNode.valueOf(log.blockHash())),
                Map.entry("transaction_hash", TextNode.valueOf(log.transactionHash())),
                Map.entry("transaction_index", log.transactionIndex() == null ? NullNode.instance : LongNode.valueOf(log.transactionIndex())),
                Map.entry("log_index", LongNode.valueOf(log.logIndex())),
                Map.entry("address", TextNode.valueOf(log.address())),
                Map.entry("topic0", log.topic0() == null ? NullNode.instance : TextNode.valueOf(log.topic0())),
                Map.entry("topic1", log.topic1() == null ? NullNode.instance : TextNode.valueOf(log.topic1())),
                Map.entry("topic2", log.topic2() == null ? NullNode.instance : TextNode.valueOf(log.topic2())),
                Map.entry("topic3", log.topic3() == null ? NullNode.instance : TextNode.valueOf(log.topic3())),
                Map.entry("data", TextNode.valueOf(log.data())),
                Map.entry("removed", log.removed() == null ? NullNode.instance : com.fasterxml.jackson.databind.node.BooleanNode.valueOf(log.removed())),
                Map.entry("raw_json", TextNode.valueOf(log.rawJson()))));
    }
}
