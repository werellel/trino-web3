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

import com.fasterxml.jackson.databind.JsonNode;
import io.trino.plugin.web3.core.BlockRange;
import io.trino.plugin.web3.runtime.JsonRpcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.lang.Long.parseUnsignedLong;
import static java.util.Objects.requireNonNull;

public final class EthereumTransactionClient
{
    private final JsonRpcClient jsonRpcClient;

    public EthereumTransactionClient(JsonRpcClient jsonRpcClient)
    {
        this.jsonRpcClient = requireNonNull(jsonRpcClient, "jsonRpcClient is null");
    }

    public CompletableFuture<List<EthereumTransaction>> getTransactions(BlockRange range)
    {
        List<JsonRpcClient.JsonRpcRequest> requests = new ArrayList<>();
        for (long blockNumber = range.startInclusive(); blockNumber <= range.endInclusive(); blockNumber++) {
            requests.add(new JsonRpcClient.JsonRpcRequest(
                    blockNumber,
                    "eth_getBlockByNumber",
                    List.of(toHex(blockNumber), true)));
            if (blockNumber == Long.MAX_VALUE) {
                break;
            }
        }
        CompletableFuture<List<JsonNode>> responses = jsonRpcClient.executeBatch(requests);
        CompletableFuture<List<EthereumTransaction>> transactions = responses.thenApply(results -> decode(range, results));
        transactions.whenComplete((value, failure) -> {
            if (transactions.isCancelled()) {
                responses.cancel(true);
            }
        });
        return transactions;
    }

    private static List<EthereumTransaction> decode(BlockRange range, List<JsonNode> results)
    {
        List<EthereumTransaction> transactions = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            JsonNode block = results.get(index);
            long expectedBlockNumber = range.startInclusive() + index;
            if (block == null || block.isNull() || parseQuantity(block, "number") != expectedBlockNumber) {
                throw new IllegalStateException("Ethereum transaction response does not match its block request");
            }
            JsonNode blockTransactions = block.get("transactions");
            if (blockTransactions == null || !blockTransactions.isArray()) {
                throw new IllegalStateException("Ethereum block response is missing transactions");
            }
            for (JsonNode transaction : blockTransactions) {
                long transactionBlockNumber = parseQuantity(transaction, "blockNumber");
                if (transactionBlockNumber != expectedBlockNumber) {
                    throw new IllegalStateException("Ethereum transaction block number does not match its block response");
                }
                transactions.add(new EthereumTransaction(
                        requiredText(transaction, "hash"),
                        transactionBlockNumber,
                        requiredText(transaction, "from"),
                        optionalText(transaction, "to")));
            }
        }
        return List.copyOf(transactions);
    }

    private static long parseQuantity(JsonNode node, String fieldName)
    {
        String value = requiredText(node, fieldName);
        if (!value.startsWith("0x")) {
            throw new IllegalStateException("Ethereum " + fieldName + " is not hexadecimal");
        }
        return parseUnsignedLong(value.substring(2), 16);
    }

    private static String requiredText(JsonNode node, String fieldName)
    {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("Ethereum response is missing " + fieldName);
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode node, String fieldName)
    {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalStateException("Ethereum response has invalid " + fieldName);
        }
        return value.textValue();
    }

    private static String toHex(long value)
    {
        return "0x" + Long.toHexString(value);
    }

    public record EthereumTransaction(String hash, long blockNumber, String fromAddress, String toAddress)
    {
        public EthereumTransaction
        {
            requireNonNull(hash, "hash is null");
            if (blockNumber < 0) {
                throw new IllegalArgumentException("blockNumber is negative");
            }
            requireNonNull(fromAddress, "fromAddress is null");
        }
    }
}
