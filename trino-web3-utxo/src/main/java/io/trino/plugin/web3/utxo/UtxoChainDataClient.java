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
package io.trino.plugin.web3.utxo;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Shared Bitcoin-family decoder for the common Bitcoin Core UTXO RPC shape. */
public class UtxoChainDataClient
        implements ChainDataClient
{
    private static final Set<String> TABLES = Set.of("blocks", "transactions", "inputs", "outputs");
    private static final int SATOSHIS_PER_COIN = 100_000_000;

    private final RemoteExecutionRuntime runtime;
    private final String chainName;

    public UtxoChainDataClient(RemoteExecutionRuntime runtime, String chainName)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.chainName = requireNonNull(chainName, "chainName is null");
        if (chainName.isBlank()) {
            throw new IllegalArgumentException("chainName is blank");
        }
    }

    @Override
    public RemoteExecution<List<ChainRow>> execute(String tableName, ChainSplit split)
    {
        requireNonNull(tableName, "tableName is null");
        if (!TABLES.contains(tableName) || !(split instanceof RangeChainSplit range)) {
            throw new IllegalArgumentException(chainName + "." + tableName + " requires a height range split");
        }
        String column = tableName.equals("blocks") ? "height" : "block_height";
        if (!range.column().equals(column)) {
            throw new IllegalArgumentException(chainName + "." + tableName + " requires a " + column + " range split");
        }
        List<RemoteOperation> hashRequests = new ArrayList<>();
        for (long height = range.startInclusive(); height <= range.endInclusive(); height++) {
            hashRequests.add(new RemoteOperation("getblockhash", List.of(height)));
            if (height == Long.MAX_VALUE) {
                break;
            }
        }
        return runtime.executeBatchWithMetrics(hashRequests)
                .flatMap(hashes -> fetchBlocks(tableName, range, hashes));
    }

    private RemoteExecution<List<ChainRow>> fetchBlocks(String tableName, RangeChainSplit range, List<RemoteResult> hashes)
    {
        long expectedCount = Math.addExact(Math.subtractExact(range.endInclusive(), range.startInclusive()), 1);
        if (hashes.size() != expectedCount) {
            throw new IllegalStateException(chainName + " block hash response count does not match its height range");
        }
        List<RemoteOperation> requests = new ArrayList<>(hashes.size());
        for (RemoteResult result : hashes) {
            String hash = normalizeHash(result.value(), chainName + " getblockhash response");
            requests.add(new RemoteOperation("getblock", List.of(hash, tableName.equals("blocks") ? 1 : 2)));
        }
        return runtime.executeBatchWithMetrics(requests)
                .map(results -> decode(chainName, tableName, range, results));
    }

    public static List<ChainRow> decode(String chainName, String tableName, RangeChainSplit range, List<RemoteResult> results)
    {
        requireNonNull(chainName, "chainName is null");
        long expectedCount = Math.addExact(Math.subtractExact(range.endInclusive(), range.startInclusive()), 1);
        if (results.size() != expectedCount) {
            throw new IllegalStateException(chainName + " block response count does not match its height range");
        }
        List<ChainRow> rows = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            JsonNode block = object(results.get(index).value(), chainName + " block response");
            long height = Math.addExact(range.startInclusive(), index);
            if (longField(block, "height") != height) {
                throw new IllegalStateException(chainName + " block height does not match its request");
            }
            String blockHash = normalizeHash(block.get("hash"), chainName + " block hash");
            switch (tableName) {
                case "blocks" -> rows.add(blockRow(block, height, blockHash, chainName));
                case "transactions" -> transactions(rows, block, height, blockHash, chainName);
                case "inputs" -> inputs(rows, block, height, blockHash, chainName);
                case "outputs" -> outputs(rows, block, height, blockHash, chainName);
                default -> throw new IllegalArgumentException("unknown UTXO table " + tableName);
            }
        }
        return List.copyOf(rows);
    }

    private static ChainRow blockRow(JsonNode block, long height, String hash, String chainName)
    {
        return new ChainRow(Map.of(
                "height", LongNode.valueOf(height),
                "hash", TextNode.valueOf(hash),
                "previous_block_hash", optionalHash(block.get("previousblockhash")),
                "time", optionalLong(block.get("time")),
                "transaction_count", LongNode.valueOf(array(block, "tx").size()),
                "raw_json", TextNode.valueOf(block.toString())));
    }

    private static void transactions(List<ChainRow> rows, JsonNode block, long height, String blockHash, String chainName)
    {
        for (JsonNode value : array(block, "tx")) {
            JsonNode tx = object(value, chainName + " transaction");
            rows.add(new ChainRow(Map.of(
                    "txid", TextNode.valueOf(normalizeHash(tx.get("txid"), chainName + " transaction id")),
                    "block_hash", TextNode.valueOf(blockHash),
                    "block_height", LongNode.valueOf(height),
                    "version", LongNode.valueOf(longField(tx, "version")),
                    "lock_time", LongNode.valueOf(longField(tx, "locktime")),
                    "input_count", LongNode.valueOf(array(tx, "vin").size()),
                    "output_count", LongNode.valueOf(array(tx, "vout").size()),
                    "raw_json", TextNode.valueOf(tx.toString()))));
        }
    }

    private static void inputs(List<ChainRow> rows, JsonNode block, long height, String blockHash, String chainName)
    {
        for (JsonNode value : array(block, "tx")) {
            JsonNode tx = object(value, chainName + " transaction");
            String txid = normalizeHash(tx.get("txid"), chainName + " transaction id");
            JsonNode values = array(tx, "vin");
            for (int index = 0; index < values.size(); index++) {
                JsonNode input = object(values.get(index), chainName + " transaction input");
                JsonNode coinbase = input.get("coinbase");
                rows.add(new ChainRow(Map.of(
                        "txid", TextNode.valueOf(txid),
                        "block_hash", TextNode.valueOf(blockHash),
                        "block_height", LongNode.valueOf(height),
                        "input_index", LongNode.valueOf(index),
                        "previous_txid", coinbase == null ? optionalHash(input.get("txid")) : NullNode.instance,
                        "previous_vout", coinbase == null ? optionalLong(input.get("vout")) : NullNode.instance,
                        "coinbase", coinbase == null ? NullNode.instance : TextNode.valueOf(text(input, "coinbase")),
                        "sequence", LongNode.valueOf(longField(input, "sequence")),
                        "raw_json", TextNode.valueOf(input.toString()))));
            }
        }
    }

    private static void outputs(List<ChainRow> rows, JsonNode block, long height, String blockHash, String chainName)
    {
        for (JsonNode value : array(block, "tx")) {
            JsonNode tx = object(value, chainName + " transaction");
            String txid = normalizeHash(tx.get("txid"), chainName + " transaction id");
            for (JsonNode output : array(tx, "vout")) {
                JsonNode script = object(output.get("scriptPubKey"), chainName + " output scriptPubKey");
                rows.add(new ChainRow(Map.of(
                        "txid", TextNode.valueOf(txid),
                        "block_hash", TextNode.valueOf(blockHash),
                        "block_height", LongNode.valueOf(height),
                        "output_index", LongNode.valueOf(longField(output, "n")),
                        "value_satoshis", LongNode.valueOf(toSatoshis(output.get("value"))),
                        "script_pubkey_hex", TextNode.valueOf(text(script, "hex")),
                        "address", address(script),
                        "raw_json", TextNode.valueOf(output.toString()))));
            }
        }
    }

    public static long toSatoshis(JsonNode value)
    {
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException("UTXO output value is missing or not numeric");
        }
        try {
            return value.decimalValue().multiply(BigDecimal.valueOf(SATOSHIS_PER_COIN)).longValueExact();
        }
        catch (ArithmeticException e) {
            throw new IllegalStateException("UTXO output value is not an exact satoshi amount", e);
        }
    }

    private static JsonNode object(JsonNode value, String field)
    {
        if (value == null || !value.isObject()) {
            throw new IllegalStateException(field + " is missing or not an object");
        }
        return value;
    }

    private static JsonNode array(JsonNode value, String field)
    {
        JsonNode result = value.get(field);
        if (result == null || !result.isArray()) {
            throw new IllegalStateException("UTXO " + field + " is missing or not an array");
        }
        return result;
    }

    private static String text(JsonNode value, String field)
    {
        JsonNode result = value.get(field);
        if (result == null || !result.isTextual() || result.textValue().isBlank()) {
            throw new IllegalStateException("UTXO " + field + " is missing or not text");
        }
        return result.textValue();
    }

    private static long longField(JsonNode value, String field)
    {
        JsonNode result = value.get(field);
        if (result == null || !result.isIntegralNumber() || !result.canConvertToLong()) {
            throw new IllegalStateException("UTXO " + field + " is missing or not a bounded integer");
        }
        return result.longValue();
    }

    private static JsonNode optionalLong(JsonNode value)
    {
        if (value == null || value.isNull()) {
            return NullNode.instance;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalStateException("UTXO optional field is not a valid integer");
        }
        return LongNode.valueOf(value.longValue());
    }

    private static JsonNode optionalHash(JsonNode value)
    {
        return value == null || value.isNull() ? NullNode.instance : TextNode.valueOf(normalizeHash(value, "UTXO optional hash"));
    }

    private static JsonNode address(JsonNode script)
    {
        JsonNode value = script.get("address");
        if (value != null && !value.isNull()) {
            return value.isTextual() ? TextNode.valueOf(value.textValue()) : invalidAddress();
        }
        JsonNode addresses = script.get("addresses");
        if (addresses != null && addresses.isArray() && !addresses.isEmpty()) {
            JsonNode first = addresses.get(0);
            if (first.isTextual()) {
                return TextNode.valueOf(first.textValue());
            }
            return invalidAddress();
        }
        return NullNode.instance;
    }

    private static JsonNode invalidAddress()
    {
        throw new IllegalStateException("UTXO optional address is not valid text");
    }

    private static String normalizeHash(JsonNode value, String field)
    {
        String hash = value != null && value.isTextual() ? value.textValue() : "";
        if (!hash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalStateException(field + " is not a 32-byte hash");
        }
        return hash.toLowerCase(Locale.ROOT);
    }
}
