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
import io.trino.plugin.web3.adapter.KeyedRangeChainSplit;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.runtime.RemoteExecution;
import io.trino.plugin.web3.runtime.RemoteCacheKey;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime.ExecutionContext;
import io.trino.plugin.web3.runtime.RestRemoteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;

public final class AptosChainDataClient
        implements ChainDataClient
{
    private static final String TRANSACTIONS_TABLE = "transactions";
    private static final String EVENTS_TABLE = "events";
    private static final String LEDGER_VERSION_COLUMN = "ledger_version";
    private static final String SEQUENCE_NUMBER_COLUMN = "sequence_number";
    private static final String ACCOUNT_ADDRESS_COLUMN = "account_address";
    private static final String CREATION_NUMBER_COLUMN = "creation_number";

    private final RemoteExecutionRuntime runtime;

    public AptosChainDataClient(RemoteExecutionRuntime runtime)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    @Override
    public RemoteExecution<List<ChainRow>> execute(String tableName, ChainSplit split)
    {
        return switch (tableName) {
            case TRANSACTIONS_TABLE -> executeTransactions(split);
            case EVENTS_TABLE -> executeEvents(split);
            default -> throw new IllegalArgumentException("unknown executable Aptos table " + tableName);
        };
    }

    private RemoteExecution<List<ChainRow>> executeTransactions(ChainSplit split)
    {
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
        return executeCacheable(
                transactionsCacheKey(range),
                request,
                response -> decodeTransactions(range, response));
    }

    private RemoteExecution<List<ChainRow>> executeEvents(ChainSplit split)
    {
        if (!(split instanceof KeyedRangeChainSplit range) ||
                !range.rangeColumn().equals(SEQUENCE_NUMBER_COLUMN) ||
                !range.keys().keySet().equals(Set.of(ACCOUNT_ADDRESS_COLUMN, CREATION_NUMBER_COLUMN))) {
            throw new IllegalArgumentException("aptos.events requires an account creation-number sequence range split");
        }
        String accountAddress = AptosChainAdapter.normalizeAddress(range.keys().get(ACCOUNT_ADDRESS_COLUMN));
        String creationNumber = AptosChainAdapter.normalizeUnsignedDecimal(range.keys().get(CREATION_NUMBER_COLUMN), CREATION_NUMBER_COLUMN);
        long limit = Math.addExact(Math.subtractExact(range.endInclusive(), range.startInclusive()), 1);
        RestRemoteRequest request = new RestRemoteRequest(
                "GET",
                "/v1/accounts/" + accountAddress + "/events/" + creationNumber,
                Map.of(
                        "start", List.of(Long.toString(range.startInclusive())),
                        "limit", List.of(Long.toString(limit))),
                Optional.empty());
        return executeCacheable(
                eventsCacheKey(range),
                request,
                response -> decodeEvents(range, response));
    }

    private RemoteExecution<List<ChainRow>> executeCacheable(RemoteCacheKey key, RestRemoteRequest request, ResponseDecoder decoder)
    {
        ExecutionContext context = runtime.newExecutionContext();
        Optional<JsonNode> cached = context.getCached(key);
        if (cached.isPresent()) {
            try {
                return context.execution(CompletableFuture.completedFuture(decoder.decode(cached.orElseThrow())));
            }
            catch (IllegalStateException e) {
                context.invalidate(key);
            }
        }

        CompletableFuture<List<ChainRow>> result = context.execute(request)
                .future()
                .thenApply(response -> {
                    List<ChainRow> rows = decoder.decode(response.value());
                    context.admit(key, response.value());
                    return rows;
                });
        return context.execution(result);
    }

    private static RemoteCacheKey transactionsCacheKey(RangeChainSplit range)
    {
        return new RemoteCacheKey(
                "aptos",
                "rest.transactions.by-ledger-version-range",
                "start=%s&end=%s".formatted(range.startInclusive(), range.endInclusive()),
                "aptos-rest-v1/transactions-projected-v1",
                1);
    }

    private static RemoteCacheKey eventsCacheKey(KeyedRangeChainSplit range)
    {
        String accountAddress = AptosChainAdapter.normalizeAddress(range.keys().get(ACCOUNT_ADDRESS_COLUMN));
        String creationNumber = AptosChainAdapter.normalizeUnsignedDecimal(range.keys().get(CREATION_NUMBER_COLUMN), CREATION_NUMBER_COLUMN);
        return new RemoteCacheKey(
                "aptos",
                "rest.events.by-account-creation-number-sequence-range",
                "account_address=%s&creation_number=%s&start=%s&end=%s".formatted(
                        accountAddress,
                        creationNumber,
                        range.startInclusive(),
                        range.endInclusive()),
                "aptos-rest-v1/events-projected-v1",
                1);
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
                            .orElse(NullNode.instance),
                    "raw_json", TextNode.valueOf(transaction.toString()))));
        }
        return List.copyOf(rows);
    }

    static List<ChainRow> decodeEvents(KeyedRangeChainSplit range, JsonNode response)
    {
        requireNonNull(range, "range is null");
        requireNonNull(response, "response is null");
        if (!response.isArray()) {
            throw new IllegalStateException("Aptos events response is not an array");
        }
        long expectedCount = Math.addExact(Math.subtractExact(range.endInclusive(), range.startInclusive()), 1);
        if (response.size() != expectedCount) {
            throw new IllegalStateException("Aptos events response does not contain the requested sequence range");
        }
        String requestedAddress = AptosChainAdapter.normalizeAddress(range.keys().get(ACCOUNT_ADDRESS_COLUMN));
        String requestedCreationNumber = AptosChainAdapter.normalizeUnsignedDecimal(range.keys().get(CREATION_NUMBER_COLUMN), CREATION_NUMBER_COLUMN);

        List<ChainRow> rows = new ArrayList<>(response.size());
        for (int index = 0; index < response.size(); index++) {
            JsonNode event = response.get(index);
            if (!event.isObject()) {
                throw new IllegalStateException("Aptos event response entry is not an object");
            }
            JsonNode guid = event.get("guid");
            if (guid == null || !guid.isObject()) {
                throw new IllegalStateException("Aptos event has an invalid guid");
            }
            String accountAddress = AptosChainAdapter.normalizeAddress(requiredText(guid, "account_address"));
            String creationNumber = AptosChainAdapter.normalizeUnsignedDecimal(requiredText(guid, "creation_number"), CREATION_NUMBER_COLUMN);
            if (!accountAddress.equals(requestedAddress) || !creationNumber.equals(requestedCreationNumber)) {
                throw new IllegalStateException("Aptos event guid does not match its request");
            }
            long expectedSequenceNumber = Math.addExact(range.startInclusive(), index);
            long sequenceNumber = requiredSequenceNumber(event);
            if (sequenceNumber != expectedSequenceNumber) {
                throw new IllegalStateException("Aptos event sequence number does not match its request");
            }
            JsonNode data = event.get("data");
            if (data == null || data.isNull()) {
                throw new IllegalStateException("Aptos event has an invalid data");
            }
            rows.add(new ChainRow(Map.of(
                    ACCOUNT_ADDRESS_COLUMN, TextNode.valueOf(accountAddress),
                    CREATION_NUMBER_COLUMN, TextNode.valueOf(creationNumber),
                    SEQUENCE_NUMBER_COLUMN, LongNode.valueOf(sequenceNumber),
                    "event_type", TextNode.valueOf(requiredText(event, "type")),
                    "data", TextNode.valueOf(data.toString()),
                    "raw_json", TextNode.valueOf(event.toString()))));
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

    private static long requiredSequenceNumber(JsonNode event)
    {
        String value = requiredText(event, "sequence_number");
        try {
            long sequenceNumber = Long.parseLong(value);
            if (sequenceNumber < 0) {
                throw new NumberFormatException("negative sequence number");
            }
            return sequenceNumber;
        }
        catch (NumberFormatException e) {
            throw new IllegalStateException("Aptos event has an invalid sequence_number", e);
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

    @FunctionalInterface
    private interface ResponseDecoder
    {
        List<ChainRow> decode(JsonNode response);
    }
}
