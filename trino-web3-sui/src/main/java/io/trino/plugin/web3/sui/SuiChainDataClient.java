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
package io.trino.plugin.web3.sui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.trino.plugin.web3.adapter.ChainDataClient;
import io.trino.plugin.web3.adapter.ChainRow;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.runtime.RemoteExecution;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteOperation;
import io.trino.plugin.web3.runtime.RemoteResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public final class SuiChainDataClient
        implements ChainDataClient
{
    private static final String CHECKPOINT_COLUMN = "checkpoint_sequence_number";
    private static final Map<String, Object> TRANSACTION_OPTIONS = Map.of(
            "showInput", true,
            "showEffects", true,
            "showEvents", true,
            "showObjectChanges", true,
            "showBalanceChanges", false);

    private final RemoteExecutionRuntime runtime;

    public SuiChainDataClient(RemoteExecutionRuntime runtime)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    @Override
    public RemoteExecution<List<ChainRow>> execute(String tableName, ChainSplit split)
    {
        if (!(split instanceof RangeChainSplit range) || !range.column().equals(CHECKPOINT_COLUMN)) {
            throw new IllegalArgumentException("sui tables require a checkpoint_sequence_number range split");
        }
        List<RemoteOperation> checkpoints = operations("sui_getCheckpoint", range, checkpoint -> List.of(Long.toString(checkpoint)));
        RemoteExecutionRuntime.ExecutionContext context = runtime.newExecutionContext();
        RemoteExecution<List<RemoteResult>> checkpointExecution = context.executeBatch(checkpoints);
        if (tableName.equals("checkpoints")) {
            return checkpointExecution.map(results -> decodeCheckpoints(range, results));
        }
        if (!tableName.equals("transactions")) {
            throw new IllegalArgumentException("unknown executable Sui table " + tableName);
        }
        return checkpointExecution.flatMap(results -> {
            List<RemoteOperation> transactions = new ArrayList<>();
            for (RemoteResult result : results) {
                JsonNode checkpoint = result.value();
                if (checkpoint.isNull()) {
                    continue;
                }
                for (JsonNode digest : requiredArray(checkpoint, "transactions")) {
                    if (!digest.isTextual() || digest.textValue().isBlank()) {
                        throw new IllegalStateException("Sui checkpoint transaction digest is invalid");
                    }
                    transactions.add(new RemoteOperation("sui_getTransactionBlock", List.of(digest.textValue(), TRANSACTION_OPTIONS)));
                }
            }
            if (transactions.isEmpty()) {
                return context.execution(java.util.concurrent.CompletableFuture.completedFuture(List.of()));
            }
            return context.executeBatch(transactions).map(fetched -> decodeTransactions(range, results, fetched));
        });
    }

    static List<ChainRow> decodeCheckpoints(RangeChainSplit range, List<RemoteResult> results)
    {
        verifyResultCount(range, results);
        List<ChainRow> rows = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            JsonNode checkpoint = results.get(index).value();
            long sequence = expectedSequence(range, index);
            if (checkpoint.isNull()) {
                continue;
            }
            if (!checkpoint.isObject() || requiredLong(checkpoint, "sequenceNumber") != sequence) {
                throw new IllegalStateException("Sui checkpoint response does not match its request");
            }
            rows.add(new ChainRow(Map.of(
                    "checkpoint_sequence_number", LongNode.valueOf(sequence),
                    "digest", TextNode.valueOf(requiredText(checkpoint, "digest")),
                    "epoch", LongNode.valueOf(requiredLong(checkpoint, "epoch")),
                    "timestamp_ms", optionalLong(checkpoint, "timestampMs").<JsonNode>map(LongNode::valueOf).orElse(NullNode.instance),
                    "transaction_count", LongNode.valueOf(requiredArray(checkpoint, "transactions").size()),
                    "raw_json", TextNode.valueOf(checkpoint.toString()))));
        }
        return List.copyOf(rows);
    }

    static List<ChainRow> decodeTransactions(RangeChainSplit range, List<RemoteResult> checkpoints, List<RemoteResult> results)
    {
        verifyResultCount(range, checkpoints);
        int expected = 0;
        for (RemoteResult checkpoint : checkpoints) {
            if (checkpoint.value().isNull()) {
                continue;
            }
            expected = Math.addExact(expected, requiredArray(checkpoint.value(), "transactions").size());
        }
        if (results.size() != expected) {
            throw new IllegalStateException("Sui transaction response count does not match checkpoint contents");
        }
        List<ChainRow> rows = new ArrayList<>(results.size());
        int index = 0;
        for (RemoteResult checkpointResult : checkpoints) {
            JsonNode checkpoint = checkpointResult.value();
            if (checkpoint.isNull()) {
                continue;
            }
            long sequence = requiredLong(checkpoint, "sequenceNumber");
            for (JsonNode digest : requiredArray(checkpoint, "transactions")) {
                JsonNode transaction = results.get(index++).value();
                if (!transaction.isObject() || !requiredText(transaction, "digest").equals(digest.textValue())) {
                    throw new IllegalStateException("Sui transaction response does not match its requested digest");
                }
                JsonNode effects = transaction.path("effects");
                String status = effects.path("status").path("status").isTextual() ? effects.path("status").path("status").textValue() : null;
                rows.add(new ChainRow(Map.of(
                        "checkpoint_sequence_number", LongNode.valueOf(sequence),
                        "digest", TextNode.valueOf(digest.textValue()),
                        "sender", optionalText(transaction.path("transaction").path("data"), "sender").<JsonNode>map(TextNode::valueOf).orElse(NullNode.instance),
                        "status", status == null ? NullNode.instance : TextNode.valueOf(status),
                        "raw_json", TextNode.valueOf(transaction.toString()))));
            }
        }
        return List.copyOf(rows);
    }

    private static List<RemoteOperation> operations(String method, RangeChainSplit range, java.util.function.LongFunction<List<Object>> parameters)
    {
        List<RemoteOperation> operations = new ArrayList<>();
        for (long value = range.startInclusive(); value <= range.endInclusive(); value++) {
            operations.add(new RemoteOperation(method, parameters.apply(value)));
            if (value == Long.MAX_VALUE) {
                break;
            }
        }
        return List.copyOf(operations);
    }

    private static void verifyResultCount(RangeChainSplit range, List<RemoteResult> results)
    {
        long expected = Math.addExact(Math.subtractExact(range.endInclusive(), range.startInclusive()), 1);
        if (results.size() != expected) {
            throw new IllegalStateException("Sui checkpoint response count does not match its range");
        }
    }

    private static long expectedSequence(RangeChainSplit range, int index)
    {
        return Math.addExact(range.startInclusive(), index);
    }

    private static JsonNode requiredArray(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalStateException("Sui response has an invalid " + field);
        }
        return value;
    }

    private static long requiredLong(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || !(value.isIntegralNumber() || value.isTextual())) {
            throw new IllegalStateException("Sui response has an invalid " + field);
        }
        if (value.isIntegralNumber()) {
            if (!value.canConvertToLong()) {
                throw new IllegalStateException("Sui response has an out-of-range " + field);
            }
            return value.longValue();
        }
        try {
            return Long.parseLong(value.textValue());
        }
        catch (NumberFormatException e) {
            throw new IllegalStateException("Sui response has an invalid " + field, e);
        }
    }

    private static String requiredText(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("Sui response has an invalid " + field);
        }
        return value.textValue();
    }

    private static java.util.Optional<Long> optionalLong(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || !(value.isIntegralNumber() || value.isTextual())) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(value.isIntegralNumber() ? value.longValue() : Long.parseLong(value.textValue()));
        }
        catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<String> optionalText(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.textValue().isBlank() ? java.util.Optional.of(value.textValue()) : java.util.Optional.empty();
    }
}
