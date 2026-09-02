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
package io.trino.plugin.web3.bitcoin;

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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

public final class BitcoinChainDataClient
        implements ChainDataClient
{
    private static final String HEIGHT_COLUMN = "height";
    private static final String BLOCK_HEIGHT_COLUMN = "block_height";
    private static final int SATOSHIS_PER_BTC = 100_000_000;

    private final RemoteExecutionRuntime runtime;

    public BitcoinChainDataClient(RemoteExecutionRuntime runtime)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    @Override
    public RemoteExecution<List<ChainRow>> execute(String tableName, ChainSplit split)
    {
        requireNonNull(tableName, "tableName is null");
        if (!(split instanceof RangeChainSplit range)) {
            throw new IllegalArgumentException("bitcoin." + tableName + " requires a height range split");
        }
        String expectedColumn = tableName.equals("blocks") ? HEIGHT_COLUMN : BLOCK_HEIGHT_COLUMN;
        if (!range.column().equals(expectedColumn)) {
            throw new IllegalArgumentException("bitcoin." + tableName + " requires a " + expectedColumn + " range split");
        }
        if (!List.of("blocks", "transactions", "inputs", "outputs").contains(tableName)) {
            throw new IllegalArgumentException("unknown executable Bitcoin table " + tableName);
        }

        List<RemoteOperation> hashRequests = new ArrayList<>();
        for (long height = range.startInclusive(); height <= range.endInclusive(); height++) {
            hashRequests.add(new RemoteOperation("getblockhash", List.of(height)));
            if (height == Long.MAX_VALUE) {
                break;
            }
        }
        return runtime.executeBatchWithMetrics(hashRequests)
                .flatMap(hashResults -> fetchBlocks(range, tableName, hashResults));
    }

    private RemoteExecution<List<ChainRow>> fetchBlocks(
            RangeChainSplit range,
            String tableName,
            List<RemoteResult> hashResults)
    {
        long expectedCount = Math.addExact(Math.subtractExact(range.endInclusive(), range.startInclusive()), 1);
        if (hashResults.size() != expectedCount) {
            throw new IllegalStateException("Bitcoin block hash response count does not match its height range");
        }
        List<RemoteOperation> blockRequests = new ArrayList<>(hashResults.size());
        for (RemoteResult hashResult : hashResults) {
            String hash = normalizeHash(hashResult.value(), "Bitcoin getblockhash response");
            blockRequests.add(new RemoteOperation("getblock", List.of(hash, tableName.equals("blocks") ? 1 : 2)));
        }
        return runtime.executeBatchWithMetrics(blockRequests)
                .map(results -> decode(tableName, range, results));
    }

    static List<ChainRow> decode(String tableName, RangeChainSplit range, List<RemoteResult> results)
    {
        long expectedCount = Math.addExact(Math.subtractExact(range.endInclusive(), range.startInclusive()), 1);
        if (results.size() != expectedCount) {
            throw new IllegalStateException("Bitcoin block response count does not match its height range");
        }
        List<ChainRow> rows = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            JsonNode block = requireObject(results.get(index).value(), "Bitcoin block response");
            long expectedHeight = Math.addExact(range.startInclusive(), index);
            if (requiredLong(block, "height") != expectedHeight) {
                throw new IllegalStateException("Bitcoin block height does not match its request");
            }
            String blockHash = normalizeHash(block.get("hash"), "Bitcoin block hash");
            switch (tableName) {
                case "blocks" -> rows.add(blockRow(block, expectedHeight, blockHash));
                case "transactions" -> decodeTransactions(rows, block, expectedHeight, blockHash);
                case "inputs" -> decodeInputs(rows, block, expectedHeight, blockHash);
                case "outputs" -> decodeOutputs(rows, block, expectedHeight, blockHash);
                default -> throw new IllegalArgumentException("unknown executable Bitcoin table " + tableName);
            }
        }
        return List.copyOf(rows);
    }

    private static ChainRow blockRow(JsonNode block, long height, String blockHash)
    {
        return new ChainRow(Map.of(
                "height", LongNode.valueOf(height),
                "hash", TextNode.valueOf(blockHash),
                "previous_block_hash", optionalHash(block.get("previousblockhash")),
                "time", optionalLong(block.get("time")),
                "transaction_count", LongNode.valueOf(requiredArray(block, "tx").size())));
    }

    private static void decodeTransactions(List<ChainRow> rows, JsonNode block, long height, String blockHash)
    {
        for (JsonNode transaction : requiredArray(block, "tx")) {
            JsonNode tx = requireObject(transaction, "Bitcoin transaction");
            rows.add(new ChainRow(Map.of(
                    "txid", TextNode.valueOf(normalizeHash(tx.get("txid"), "Bitcoin transaction id")),
                    "block_hash", TextNode.valueOf(blockHash),
                    "block_height", LongNode.valueOf(height),
                    "version", LongNode.valueOf(requiredLong(tx, "version")),
                    "lock_time", LongNode.valueOf(requiredLong(tx, "locktime")),
                    "input_count", LongNode.valueOf(requiredArray(tx, "vin").size()),
                    "output_count", LongNode.valueOf(requiredArray(tx, "vout").size()))));
        }
    }

    private static void decodeInputs(List<ChainRow> rows, JsonNode block, long height, String blockHash)
    {
        for (JsonNode transaction : requiredArray(block, "tx")) {
            JsonNode tx = requireObject(transaction, "Bitcoin transaction");
            String txid = normalizeHash(tx.get("txid"), "Bitcoin transaction id");
            JsonNode inputs = requiredArray(tx, "vin");
            for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
                JsonNode input = requireObject(inputs.get(inputIndex), "Bitcoin transaction input");
                JsonNode coinbase = input.get("coinbase");
                rows.add(new ChainRow(Map.of(
                        "txid", TextNode.valueOf(txid),
                        "block_hash", TextNode.valueOf(blockHash),
                        "block_height", LongNode.valueOf(height),
                        "input_index", LongNode.valueOf(inputIndex),
                        "previous_txid", coinbase == null ? optionalHash(input.get("txid")) : NullNode.instance,
                        "previous_vout", coinbase == null ? optionalLong(input.get("vout")) : NullNode.instance,
                        "coinbase", coinbase == null ? NullNode.instance : TextNode.valueOf(requiredText(input, "coinbase")),
                        "sequence", LongNode.valueOf(requiredLong(input, "sequence")))));
            }
        }
    }

    private static void decodeOutputs(List<ChainRow> rows, JsonNode block, long height, String blockHash)
    {
        for (JsonNode transaction : requiredArray(block, "tx")) {
            JsonNode tx = requireObject(transaction, "Bitcoin transaction");
            String txid = normalizeHash(tx.get("txid"), "Bitcoin transaction id");
            JsonNode outputs = requiredArray(tx, "vout");
            for (JsonNode output : outputs) {
                JsonNode script = requireObject(output.get("scriptPubKey"), "Bitcoin output scriptPubKey");
                rows.add(new ChainRow(Map.of(
                        "txid", TextNode.valueOf(txid),
                        "block_hash", TextNode.valueOf(blockHash),
                        "block_height", LongNode.valueOf(height),
                        "output_index", LongNode.valueOf(requiredLong(output, "n")),
                        "value_satoshis", LongNode.valueOf(toSatoshis(output.get("value"))),
                        "script_pubkey_hex", TextNode.valueOf(requiredText(script, "hex")),
                        "address", optionalText(script.get("address")))));
            }
        }
    }

    static long toSatoshis(JsonNode value)
    {
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException("Bitcoin output value is missing or not numeric");
        }
        try {
            return value.decimalValue().multiply(BigDecimal.valueOf(SATOSHIS_PER_BTC)).longValueExact();
        }
        catch (ArithmeticException e) {
            throw new IllegalStateException("Bitcoin output value is not an exact satoshi amount", e);
        }
    }

    private static JsonNode requireObject(JsonNode value, String field)
    {
        if (value == null || !value.isObject()) {
            throw new IllegalStateException(field + " is missing or not an object");
        }
        return value;
    }

    private static JsonNode requiredArray(JsonNode value, String field)
    {
        JsonNode result = value.get(field);
        if (result == null || !result.isArray()) {
            throw new IllegalStateException("Bitcoin " + field + " is missing or not an array");
        }
        return result;
    }

    private static String requiredText(JsonNode value, String field)
    {
        JsonNode result = value.get(field);
        if (result == null || !result.isTextual() || result.textValue().isBlank()) {
            throw new IllegalStateException("Bitcoin " + field + " is missing or not text");
        }
        return result.textValue();
    }

    private static long requiredLong(JsonNode value, String field)
    {
        JsonNode result = value.get(field);
        if (result == null || !result.isIntegralNumber() || !result.canConvertToLong()) {
            throw new IllegalStateException("Bitcoin " + field + " is missing or not a bounded integer");
        }
        return result.longValue();
    }

    private static JsonNode optionalLong(JsonNode value)
    {
        if (value == null || value.isNull()) {
            return NullNode.instance;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalStateException("Bitcoin optional field is not a valid integer");
        }
        return LongNode.valueOf(value.longValue());
    }

    private static JsonNode optionalText(JsonNode value)
    {
        if (value == null || value.isNull()) {
            return NullNode.instance;
        }
        if (!value.isTextual()) {
            throw new IllegalStateException("Bitcoin optional field is not valid text");
        }
        return TextNode.valueOf(value.textValue());
    }

    private static JsonNode optionalHash(JsonNode value)
    {
        return value == null || value.isNull() ? NullNode.instance : TextNode.valueOf(normalizeHash(value, "Bitcoin optional hash"));
    }

    private static String normalizeHash(JsonNode value, String field)
    {
        String hash = value != null && value.isTextual() ? value.textValue() : "";
        if (!hash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException(field + " is not a 32-byte hash");
        }
        return hash.toLowerCase(java.util.Locale.ROOT);
    }

}
