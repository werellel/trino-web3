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
package io.trino.plugin.web3.aptos;

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
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RestRemoteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public final class AptosChainDataClient
        implements ChainDataClient
{
    private static final String TRANSACTIONS_TABLE = "transactions";
    private static final String LEDGER_VERSION_COLUMN = "ledger_version";

    private final RemoteExecutionRuntime runtime;

    public AptosChainDataClient(RemoteExecutionRuntime runtime)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    @Override
    public RemoteExecution<List<ChainRow>> execute(String tableName, ChainSplit split)
    {
        if (!tableName.equals(TRANSACTIONS_TABLE)) {
            throw new IllegalArgumentException("unknown executable Aptos table " + tableName);
        }
        if (!(split instanceof RangeChainSplit range) || !range.column().equals(LEDGER_VERSION_COLUMN)) {
            throw new IllegalArgumentException("aptos.transactions requires a ledger_version range split");
        }
        long limit = Math.addExact(Math.subtractExact(range.endInclusive(), range.startInclusive()), 1);
        RestRemoteRequest request = new RestRemoteRequest(
                "GET",
                "/v1/transactions",
                Map.of(
                        "start", List.of(Long.toString(range.startInclusive())),
                        "limit", List.of(Long.toString(limit))),
                Optional.empty());
        return runtime.newExecutionContext()
                .execute(request)
                .map(result -> decodeTransactions(range, result.value()));
    }

    static List<ChainRow> decodeTransactions(RangeChainSplit range, JsonNode response)
    {
        requireNonNull(range, "range is null");
        requireNonNull(response, "response is null");
        if (!response.isArray()) {
            throw new IllegalStateException("Aptos transactions response is not an array");
        }
        long expectedCount = Math.addExact(Math.subtractExact(range.endInclusive(), range.startInclusive()), 1);
        if (response.size() != expectedCount) {
            throw new IllegalStateException("Aptos transactions response does not contain the requested ledger version range");
        }

        List<ChainRow> rows = new ArrayList<>(response.size());
        for (int index = 0; index < response.size(); index++) {
            JsonNode transaction = response.get(index);
            if (!transaction.isObject()) {
                throw new IllegalStateException("Aptos transaction response entry is not an object");
            }
            long expectedVersion = Math.addExact(range.startInclusive(), index);
            long version = requiredLedgerVersion(transaction);
            if (version != expectedVersion) {
                throw new IllegalStateException("Aptos transaction ledger version does not match its request");
            }
            rows.add(new ChainRow(Map.of(
                    "ledger_version", LongNode.valueOf(version),
                    "hash", TextNode.valueOf(requiredText(transaction, "hash")),
                    "type", TextNode.valueOf(requiredText(transaction, "type")),
                    "success", BooleanNode.valueOf(requiredBoolean(transaction, "success")),
                    "vm_status", TextNode.valueOf(requiredText(transaction, "vm_status")),
                    "sender", optionalText(transaction, "sender")
                            .<JsonNode>map(TextNode::valueOf)
                            .orElse(NullNode.instance))));
        }
        return List.copyOf(rows);
    }

    private static long requiredLedgerVersion(JsonNode transaction)
    {
        String value = requiredText(transaction, "version");
        try {
            long version = Long.parseLong(value);
            if (version < 0) {
                throw new NumberFormatException("negative ledger version");
            }
            return version;
        }
        catch (NumberFormatException e) {
            throw new IllegalStateException("Aptos transaction has an invalid ledger version", e);
        }
    }

    private static String requiredText(JsonNode value, String field)
    {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.textValue().isEmpty()) {
            throw new IllegalStateException("Aptos transaction has an invalid " + field);
        }
        return node.textValue();
    }

    private static Optional<String> optionalText(JsonNode value, String field)
    {
        JsonNode node = value.get(field);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isTextual() || node.textValue().isEmpty()) {
            throw new IllegalStateException("Aptos transaction has an invalid " + field);
        }
        return Optional.of(node.textValue());
    }

    private static boolean requiredBoolean(JsonNode value, String field)
    {
        JsonNode node = value.get(field);
        if (node == null || !node.isBoolean()) {
            throw new IllegalStateException("Aptos transaction has an invalid " + field);
        }
        return node.booleanValue();
    }
}
