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
package io.trino.plugin.web3.tron;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.trino.plugin.web3.adapter.ChainDataClient;
import io.trino.plugin.web3.adapter.ChainRow;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.runtime.RemoteExecution;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RestRemoteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;

public final class TronChainDataClient
        implements ChainDataClient
{
    private final RemoteExecutionRuntime runtime;

    public TronChainDataClient(RemoteExecutionRuntime runtime)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    @Override
    public RemoteExecution<List<ChainRow>> execute(String tableName, ChainSplit split)
    {
        if (!tableName.equals("blocks") && !tableName.equals("transactions")) {
            throw new IllegalArgumentException("unknown executable Tron table " + tableName);
        }
        if (!(split instanceof RangeChainSplit range) || !range.column().equals("block_number")) {
            throw new IllegalArgumentException("tron tables require a block_number range split");
        }
        RemoteExecutionRuntime.ExecutionContext context = runtime.newExecutionContext();
        List<CompletableFuture<JsonNode>> responses = new ArrayList<>();
        for (long number = range.startInclusive(); number <= range.endInclusive(); number++) {
            com.fasterxml.jackson.databind.node.ObjectNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            body.set("num", LongNode.valueOf(number));
            responses.add(context.execute(new RestRemoteRequest("POST", "/wallet/getblockbynum", Map.of(), Optional.of(body))).future()
                    .thenApply(result -> result.value()));
            if (number == Long.MAX_VALUE) {
                break;
            }
        }
        CompletableFuture<List<ChainRow>> result = CompletableFuture.allOf(responses.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<ChainRow> rows = new ArrayList<>();
                    for (int index = 0; index < responses.size(); index++) {
                        JsonNode block = responses.get(index).join();
                        long number = range.startInclusive() + index;
                        if (tableName.equals("blocks")) {
                            rows.add(blockRow(block, number));
                        }
                        else if (tableName.equals("transactions")) {
                            rows.addAll(transactionRows(block, number));
                        }
                    }
                    return List.copyOf(rows);
                });
        return context.execution(result);
    }

    private static ChainRow blockRow(JsonNode block, long expectedNumber)
    {
        validateBlock(block, expectedNumber);
        JsonNode rawData = block.path("block_header").path("raw_data");
        JsonNode transactions = block.path("transactions");
        return new ChainRow(Map.of(
                "block_number", LongNode.valueOf(expectedNumber),
                "block_hash", TextNode.valueOf(block.path("blockID").textValue()),
                "timestamp", rawData.path("timestamp").isIntegralNumber() ? rawData.path("timestamp") : com.fasterxml.jackson.databind.node.NullNode.instance,
                "transaction_count", LongNode.valueOf(transactions.isArray() ? transactions.size() : 0),
                "raw_json", TextNode.valueOf(block.toString())));
    }

    private static List<ChainRow> transactionRows(JsonNode block, long expectedNumber)
    {
        validateBlock(block, expectedNumber);
        JsonNode transactions = block.path("transactions");
        if (!transactions.isArray()) {
            return List.of();
        }
        List<ChainRow> rows = new ArrayList<>();
        for (JsonNode transaction : transactions) {
            if (!transaction.path("txID").isTextual()) {
                throw new IllegalStateException("Tron transaction response is missing txID");
            }
            JsonNode contracts = transaction.path("raw_data").path("contract");
            rows.add(new ChainRow(Map.of(
                    "txid", TextNode.valueOf(transaction.path("txID").textValue()),
                    "block_number", LongNode.valueOf(expectedNumber),
                    "contract_count", LongNode.valueOf(contracts.isArray() ? contracts.size() : 0),
                    "raw_json", TextNode.valueOf(transaction.toString()))));
        }
        return List.copyOf(rows);
    }

    private static void validateBlock(JsonNode block, long expectedNumber)
    {
        if (!block.isObject() || !block.path("blockID").isTextual() || block.path("block_header").path("raw_data").path("number").asLong(Long.MIN_VALUE) != expectedNumber) {
            throw new IllegalStateException("Tron block response does not match its request");
        }
    }
}
