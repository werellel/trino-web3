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
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.DiscreteValueChainSplit;
import io.trino.plugin.web3.runtime.RemoteExecution;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteOperation;

import java.util.List;

import static io.trino.plugin.web3.evm.EthereumJson.normalizeHash;
import static io.trino.plugin.web3.evm.EthereumJson.requiredQuantity;
import static io.trino.plugin.web3.evm.EthereumJson.requiredText;
import static java.util.Objects.requireNonNull;

/** Reads transaction receipts using the provider-independent RPC runtime. */
public final class EthereumReceiptClient
{
    private final RemoteExecutionRuntime runtime;

    public EthereumReceiptClient(RemoteExecutionRuntime runtime)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    public RemoteExecution<List<EthereumReceipt>> getReceipt(String transactionHash)
    {
        String hash = normalizeHash(transactionHash, "transaction hash");
        return runtime.executeBatchWithMetrics(List.of(new RemoteOperation("eth_getTransactionReceipt", List.of(hash))))
                .map(results -> results.getFirst().value())
                .map(value -> value.isNull() ? List.of() : List.of(decode(value, hash)));
    }

    public RemoteExecution<List<EthereumReceipt>> getReceipt(ChainSplit split)
    {
        if (!(split instanceof DiscreteValueChainSplit valueSplit) || !valueSplit.column().equals("transaction_hash")) {
            throw new IllegalArgumentException("receipt requires a transaction hash split");
        }
        return getReceipt(valueSplit.value());
    }

    private static EthereumReceipt decode(JsonNode receipt, String expectedHash)
    {
        String hash = normalizeHash(requiredText(receipt, "transactionHash"), "transaction hash");
        if (!hash.equals(expectedHash)) {
            throw new IllegalStateException("Ethereum receipt transaction hash does not match its request");
        }
        return new EthereumReceipt(
                hash,
                optionalQuantity(receipt, "transactionIndex"),
                optionalQuantity(receipt, "blockNumber"),
                optionalHash(receipt, "blockHash"),
                requiredText(receipt, "from"),
                optionalText(receipt, "to"),
                optionalText(receipt, "contractAddress"),
                optionalQuantity(receipt, "cumulativeGasUsed"),
                optionalQuantity(receipt, "gasUsed"),
                optionalQuantity(receipt, "status"),
                optionalText(receipt, "logsBloom"),
                receipt.toString());
    }

    private static String optionalHash(JsonNode node, String fieldName)
    {
        String value = optionalText(node, fieldName);
        return value == null ? null : normalizeHash(value, fieldName);
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

    private static Long optionalQuantity(JsonNode node, String fieldName)
    {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        return requiredQuantity(node, fieldName);
    }

    public record EthereumReceipt(
            String transactionHash,
            Long transactionIndex,
            Long blockNumber,
            String blockHash,
            String fromAddress,
            String toAddress,
            String contractAddress,
            Long cumulativeGasUsed,
            Long gasUsed,
            Long status,
            String logsBloom,
            String rawJson)
    {
        public EthereumReceipt
        {
            requireNonNull(transactionHash, "transactionHash is null");
            requireNonNull(fromAddress, "fromAddress is null");
            requireNonNull(rawJson, "rawJson is null");
        }
    }
}
