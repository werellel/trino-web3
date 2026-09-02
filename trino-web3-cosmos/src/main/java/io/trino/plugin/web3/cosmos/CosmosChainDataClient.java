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
package io.trino.plugin.web3.cosmos;

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

import static java.util.Objects.requireNonNull;

public final class CosmosChainDataClient
        implements ChainDataClient
{
    private final RemoteExecutionRuntime runtime;
    private final String schemaName;

    public CosmosChainDataClient(RemoteExecutionRuntime runtime, String schemaName)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.schemaName = requireNonNull(schemaName, "schemaName is null");
    }

    @Override
    public RemoteExecution<List<ChainRow>> execute(String tableName, ChainSplit split)
    {
        if (!(split instanceof RangeChainSplit range) || !range.column().equals("height")) {
            throw new IllegalArgumentException(schemaName + " tables require a height range split");
        }
        List<RemoteExecution<JsonNode>> executions = new ArrayList<>();
        RemoteExecutionRuntime.ExecutionContext context = runtime.newExecutionContext();
        for (long height = range.startInclusive(); height <= range.endInclusive(); height++) {
            executions.add(context.execute(new RestRemoteRequest(
                    "GET",
                    "/cosmos/base/tendermint/v1beta1/blocks/" + height,
                    Map.of(),
                    Optional.empty())).map(result -> result.value()));
            if (height == Long.MAX_VALUE) {
                break;
            }
        }
        java.util.concurrent.CompletableFuture<List<ChainRow>> result = java.util.concurrent.CompletableFuture.allOf(executions.stream().map(RemoteExecution::future).toArray(java.util.concurrent.CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<ChainRow> rows = new ArrayList<>();
                    for (int index = 0; index < executions.size(); index++) {
                        JsonNode response = executions.get(index).future().join();
                        long height = Math.addExact(range.startInclusive(), index);
                        if (tableName.equals("blocks")) {
                            rows.add(decodeBlock(response, height));
                        }
                        else if (tableName.equals("transactions")) {
                            rows.addAll(decodeTransactions(response, height));
                        }
                        else {
                            throw new IllegalArgumentException("unknown executable " + schemaName + " table " + tableName);
                        }
                    }
                    return List.copyOf(rows);
                });
        return context.execution(result);
    }

    static ChainRow decodeBlock(JsonNode response, long requestedHeight)
    {
        JsonNode block = validateBlock(response, requestedHeight);
        JsonNode header = block.path("header");
        JsonNode data = block.path("data");
        return new ChainRow(Map.of(
                "height", LongNode.valueOf(requestedHeight),
                "hash", TextNode.valueOf(requiredText(response.path("block_id"), "hash")),
                "chain_id", TextNode.valueOf(requiredText(header, "chain_id")),
                "time", TextNode.valueOf(requiredText(header, "time")),
                "transaction_count", LongNode.valueOf(data.path("txs").isArray() ? data.path("txs").size() : 0),
                "raw_json", TextNode.valueOf(response.toString())));
    }

    static List<ChainRow> decodeTransactions(JsonNode response, long requestedHeight)
    {
        JsonNode block = validateBlock(response, requestedHeight);
        JsonNode txs = block.path("data").path("txs");
        if (txs.isNull() || !txs.isArray()) {
            return List.of();
        }
        List<ChainRow> rows = new ArrayList<>();
        for (int index = 0; index < txs.size(); index++) {
            JsonNode tx = txs.get(index);
            if (!tx.isTextual() || tx.textValue().isBlank()) {
                throw new IllegalStateException("Cosmos block transaction is not a non-empty base64 string");
            }
            rows.add(new ChainRow(Map.of(
                    "height", LongNode.valueOf(requestedHeight),
                    "index", LongNode.valueOf(index),
                    "tx_base64", TextNode.valueOf(tx.textValue()),
                    "raw_json", TextNode.valueOf(tx.toString()))));
        }
        return List.copyOf(rows);
    }

    private static JsonNode validateBlock(JsonNode response, long requestedHeight)
    {
        JsonNode block = response.path("block");
        long height = parseLong(block.path("header").path("height"), "height");
        if (!block.isObject() || height != requestedHeight) {
            throw new IllegalStateException("Cosmos block response does not match its requested height");
        }
        return block;
    }

    private static String requiredText(JsonNode node, String field)
    {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("Cosmos response has an invalid " + field);
        }
        return value.textValue();
    }

    private static long parseLong(JsonNode value, String field)
    {
        try {
            if (value.isIntegralNumber()) {
                if (!value.canConvertToLong()) {
                    throw new NumberFormatException("out of range");
                }
                return value.longValue();
            }
            return Long.parseLong(value.textValue());
        }
        catch (RuntimeException e) {
            throw new IllegalStateException("Cosmos response has an invalid " + field, e);
        }
    }
}
