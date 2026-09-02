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
package io.trino.plugin.web3.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.trino.plugin.web3.adapter.ChainDataClient;
import io.trino.plugin.web3.adapter.ChainRow;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.runtime.RemoteExecution;
import io.trino.plugin.web3.runtime.RemoteOperation;
import io.trino.plugin.web3.runtime.RemoteResult;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public final class SolanaChainDataClient
        implements ChainDataClient
{
    private static final String SLOT_COLUMN = "slot";
    private static final Map<String, Object> BLOCK_CONFIG = Map.of(
            "commitment", "finalized",
            "encoding", "json",
            "transactionDetails", "none",
            "rewards", false,
            "maxSupportedTransactionVersion", 0);
    private static final Map<String, Object> TRANSACTION_CONFIG = Map.of(
            "commitment", "finalized",
            "encoding", "json",
            "transactionDetails", "full",
            "rewards", false,
            "maxSupportedTransactionVersion", 0);

    private final RemoteExecutionRuntime runtime;

    public SolanaChainDataClient(RemoteExecutionRuntime runtime)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    @Override
    public RemoteExecution<List<ChainRow>> execute(String tableName, ChainSplit split)
    {
        if (!(split instanceof RangeChainSplit range) || !range.column().equals(SLOT_COLUMN)) {
            throw new IllegalArgumentException("solana." + tableName + " requires a slot range split");
        }
        return switch (tableName) {
            case "blocks" -> execute(range, BLOCK_CONFIG, SolanaChainDataClient::decodeBlocks);
            case "transactions" -> execute(range, TRANSACTION_CONFIG, SolanaChainDataClient::decodeTransactions);
            case "instructions" -> execute(range, TRANSACTION_CONFIG, SolanaChainDataClient::decodeInstructions);
            default -> throw new IllegalArgumentException("unknown executable Solana table " + tableName);
        };
    }

    private RemoteExecution<List<ChainRow>> execute(RangeChainSplit range, Map<String, Object> configuration, RangeDecoder decoder)
    {
        List<RemoteOperation> operations = new ArrayList<>();
        for (long slot = range.startInclusive(); slot <= range.endInclusive(); slot++) {
            operations.add(new RemoteOperation("getBlock", List.of(slot, configuration)));
            if (slot == Long.MAX_VALUE) {
                break;
            }
        }
        return runtime.executeBatchWithMetrics(operations)
                .map(results -> decoder.decode(range, results));
    }

    static List<ChainRow> decodeBlocks(RangeChainSplit range, List<RemoteResult> results)
    {
        verifyResultCount(range, results);
        List<ChainRow> rows = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            JsonNode block = results.get(index).value();
            if (block.isNull()) {
                continue;
            }
            long slot = expectedSlot(range, index);
            rows.add(new ChainRow(Map.of(
                    "slot", LongNode.valueOf(slot),
                    "blockhash", TextNode.valueOf(requiredText(block, "blockhash")),
                    "parent_slot", LongNode.valueOf(requiredLong(block, "parentSlot")),
                    "block_time", optionalLong(block, "blockTime")
                            .<JsonNode>map(LongNode::valueOf)
                            .orElse(NullNode.instance),
                    "raw_json", TextNode.valueOf(block.toString()))));
        }
        return List.copyOf(rows);
    }

    static List<ChainRow> decodeTransactions(RangeChainSplit range, List<RemoteResult> results)
    {
        verifyResultCount(range, results);
        List<ChainRow> rows = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            JsonNode block = results.get(index).value();
            if (block.isNull()) {
                continue;
            }
            long slot = expectedSlot(range, index);
            for (JsonNode transaction : requiredArray(block, "transactions")) {
                JsonNode meta = requiredObject(transaction, "meta");
                rows.add(new ChainRow(Map.of(
                        "slot", LongNode.valueOf(slot),
                        "signature", TextNode.valueOf(transactionSignature(transaction)),
                        "success", BooleanNode.valueOf(meta.get("err") != null && meta.get("err").isNull()),
                        "fee", LongNode.valueOf(requiredLong(meta, "fee")),
                        "raw_json", TextNode.valueOf(transaction.toString()))));
            }
        }
        return List.copyOf(rows);
    }

    static List<ChainRow> decodeInstructions(RangeChainSplit range, List<RemoteResult> results)
    {
        verifyResultCount(range, results);
        List<ChainRow> rows = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            JsonNode block = results.get(index).value();
            if (block.isNull()) {
                continue;
            }
            long slot = expectedSlot(range, index);
            for (JsonNode transaction : requiredArray(block, "transactions")) {
                JsonNode message = requiredObject(requiredObject(transaction, "transaction"), "message");
                List<String> accountKeys = accountKeys(message);
                String signature = transactionSignature(transaction);
                JsonNode instructions = requiredArray(message, "instructions");
                for (int instructionIndex = 0; instructionIndex < instructions.size(); instructionIndex++) {
                    JsonNode instruction = instructions.get(instructionIndex);
                    int programIdIndex = Math.toIntExact(requiredLong(instruction, "programIdIndex"));
                    if (programIdIndex < 0 || programIdIndex >= accountKeys.size()) {
                        throw new IllegalStateException("Solana instruction programIdIndex is outside message accountKeys");
                    }
                    JsonNode accounts = requiredArray(instruction, "accounts");
                    rows.add(new ChainRow(Map.of(
                            "slot", LongNode.valueOf(slot),
                            "transaction_signature", TextNode.valueOf(signature),
                            "instruction_index", LongNode.valueOf(instructionIndex),
                            "program_id", TextNode.valueOf(accountKeys.get(programIdIndex)),
                            "account_indices", TextNode.valueOf(accounts.toString()),
                            "data", TextNode.valueOf(requiredText(instruction, "data")),
                            "raw_json", TextNode.valueOf(instruction.toString()))));
                }
            }
        }
        return List.copyOf(rows);
    }

    private static void verifyResultCount(RangeChainSplit range, List<RemoteResult> results)
    {
        long expectedCount = Math.addExact(Math.subtractExact(range.endInclusive(), range.startInclusive()), 1);
        if (results.size() != expectedCount) {
            throw new IllegalStateException("Solana block response count does not match its slot range");
        }
    }

    private static long expectedSlot(RangeChainSplit range, int index)
    {
        return Math.addExact(range.startInclusive(), index);
    }

    private static JsonNode requiredObject(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalStateException("Solana response has an invalid " + field);
        }
        return value;
    }

    private static JsonNode requiredArray(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalStateException("Solana response has an invalid " + field);
        }
        return value;
    }

    private static String transactionSignature(JsonNode transaction)
    {
        JsonNode signatures = requiredArray(requiredObject(transaction, "transaction"), "signatures");
        JsonNode signature = signatures.get(0);
        if (signature == null || !signature.isTextual() || signature.textValue().isEmpty()) {
            throw new IllegalStateException("Solana transaction has an invalid signature");
        }
        return signature.textValue();
    }

    private static List<String> accountKeys(JsonNode message)
    {
        List<String> accountKeys = new ArrayList<>();
        for (JsonNode accountKey : requiredArray(message, "accountKeys")) {
            if (!accountKey.isTextual() || accountKey.textValue().isEmpty()) {
                throw new IllegalStateException("Solana message has an invalid account key");
            }
            accountKeys.add(accountKey.textValue());
        }
        return List.copyOf(accountKeys);
    }

    private static String requiredText(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw new IllegalStateException("Solana response has an invalid " + field);
        }
        return value.textValue();
    }

    private static long requiredLong(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalStateException("Solana response has an invalid " + field);
        }
        return value.longValue();
    }

    private static java.util.Optional<Long> optionalLong(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return java.util.Optional.empty();
        }
        if (!value.canConvertToLong()) {
            throw new IllegalStateException("Solana response has an invalid " + field);
        }
        return java.util.Optional.of(value.longValue());
    }

    @FunctionalInterface
    private interface RangeDecoder
    {
        List<ChainRow> decode(RangeChainSplit range, List<RemoteResult> results);
    }
}
