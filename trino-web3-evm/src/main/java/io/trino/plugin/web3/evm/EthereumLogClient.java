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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.trino.plugin.web3.core.BlockRange;
import io.trino.plugin.web3.runtime.RemoteExecution;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteOperation;

import java.util.ArrayList;
import java.util.List;

import static io.trino.plugin.web3.evm.EthereumJson.normalizeHash;
import static io.trino.plugin.web3.evm.EthereumJson.requiredQuantity;
import static io.trino.plugin.web3.evm.EthereumJson.requiredText;
import static java.util.Objects.requireNonNull;

/** Reads event logs over a bounded block range with the standard eth_getLogs filter. */
public final class EthereumLogClient
{
    private final RemoteExecutionRuntime runtime;

    public EthereumLogClient(RemoteExecutionRuntime runtime)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    public RemoteExecution<List<EthereumLog>> getLogs(BlockRange range)
    {
        requireNonNull(range, "range is null");
        ObjectNode filter = JsonNodeFactory.instance.objectNode()
                .put("fromBlock", EthereumJson.toQuantity(range.startInclusive()))
                .put("toBlock", EthereumJson.toQuantity(range.endInclusive()));
        return runtime.executeBatchWithMetrics(List.of(new RemoteOperation("eth_getLogs", List.of(filter))))
                .map(results -> results.getFirst().value())
                .map(value -> decode(value, range));
    }

    private static List<EthereumLog> decode(com.fasterxml.jackson.databind.JsonNode value, BlockRange range)
    {
        if (!value.isArray()) {
            throw new IllegalStateException("Ethereum logs response is not an array");
        }
        List<EthereumLog> logs = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode log : value) {
            long blockNumber = requiredQuantity(log, "blockNumber");
            if (blockNumber < range.startInclusive() || blockNumber > range.endInclusive()) {
                throw new IllegalStateException("Ethereum log block number does not match its request");
            }
            com.fasterxml.jackson.databind.JsonNode topics = log.get("topics");
            if (topics == null || !topics.isArray() || topics.size() > 4) {
                throw new IllegalStateException("Ethereum log topics are invalid");
            }
            logs.add(new EthereumLog(
                    blockNumber,
                    optionalHash(log, "blockHash"),
                    normalizeHash(requiredText(log, "transactionHash"), "transaction hash"),
                    optionalQuantity(log, "transactionIndex"),
                    requiredQuantity(log, "logIndex"),
                    requiredText(log, "address"),
                    topic(topics, 0), topic(topics, 1), topic(topics, 2), topic(topics, 3),
                    requiredText(log, "data"),
                    optionalBoolean(log, "removed"),
                    log.toString()));
        }
        return List.copyOf(logs);
    }

    private static String topic(com.fasterxml.jackson.databind.JsonNode topics, int index)
    {
        if (index >= topics.size() || topics.get(index).isNull()) {
            return null;
        }
        if (!topics.get(index).isTextual()) {
            throw new IllegalStateException("Ethereum log topic is not textual");
        }
        return normalizeHash(topics.get(index).textValue(), "log topic");
    }

    private static String optionalHash(com.fasterxml.jackson.databind.JsonNode node, String fieldName)
    {
        String value = optionalText(node, fieldName);
        return value == null ? null : normalizeHash(value, fieldName);
    }

    private static String optionalText(com.fasterxml.jackson.databind.JsonNode node, String fieldName)
    {
        com.fasterxml.jackson.databind.JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalStateException("Ethereum response has invalid " + fieldName);
        }
        return value.textValue();
    }

    private static Long optionalQuantity(com.fasterxml.jackson.databind.JsonNode node, String fieldName)
    {
        com.fasterxml.jackson.databind.JsonNode value = node.get(fieldName);
        return value == null || value.isNull() ? null : requiredQuantity(node, fieldName);
    }

    private static Boolean optionalBoolean(com.fasterxml.jackson.databind.JsonNode node, String fieldName)
    {
        com.fasterxml.jackson.databind.JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw new IllegalStateException("Ethereum response has invalid " + fieldName);
        }
        return value.booleanValue();
    }

    public record EthereumLog(
            long blockNumber,
            String blockHash,
            String transactionHash,
            Long transactionIndex,
            long logIndex,
            String address,
            String topic0,
            String topic1,
            String topic2,
            String topic3,
            String data,
            Boolean removed,
            String rawJson)
    {
        public EthereumLog
        {
            requireNonNull(transactionHash, "transactionHash is null");
            requireNonNull(address, "address is null");
            requireNonNull(data, "data is null");
            requireNonNull(rawJson, "rawJson is null");
        }
    }
}
