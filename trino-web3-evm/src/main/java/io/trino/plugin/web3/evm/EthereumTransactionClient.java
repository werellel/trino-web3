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
import io.trino.plugin.web3.core.BlockRange;
import io.trino.plugin.web3.runtime.RemoteExecution;
import io.trino.plugin.web3.runtime.RemoteCacheKey;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime.ExecutionContext;
import io.trino.plugin.web3.runtime.RemoteOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static io.trino.plugin.web3.evm.EthereumJson.normalizeHash;
import static io.trino.plugin.web3.evm.EthereumJson.requiredQuantity;
import static io.trino.plugin.web3.evm.EthereumJson.requiredText;
import static io.trino.plugin.web3.evm.EthereumJson.toQuantity;
import static java.util.Objects.requireNonNull;

public final class EthereumTransactionClient
{
    private final RemoteExecutionRuntime runtime;
    private final EthereumFinalityResolver finalityResolver;

    public EthereumTransactionClient(RemoteExecutionRuntime runtime)
    {
        this(runtime, new EthereumFinalityResolver());
    }

    EthereumTransactionClient(RemoteExecutionRuntime runtime, EthereumFinalityResolver finalityResolver)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.finalityResolver = requireNonNull(finalityResolver, "finalityResolver is null");
    }

    public RemoteExecution<List<EthereumTransaction>> getTransactions(BlockRange range)
    {
        requireNonNull(range, "range is null");
        if (!runtime.isCacheEnabled()) {
            return getTransactionsWithoutCache(range);
        }

        ExecutionContext context = runtime.newExecutionContext();
        AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();
        CompletableFuture<EthereumFinalityBoundaries> boundaries = finalityResolver.resolve(context);
        active.set(boundaries);
        CompletableFuture<List<EthereumTransaction>> result = boundaries.thenCompose(finality -> {
            CompletableFuture<List<EthereumTransaction>> transactions = EthereumBlockResponseLoader.load(context, range, finality, true)
                    .thenApply(results -> decode(range, results));
            active.set(transactions);
            return transactions;
        });
        propagateCancellation(result, active);
        return context.execution(result);
    }

    private RemoteExecution<List<EthereumTransaction>> getTransactionsWithoutCache(BlockRange range)
    {
        List<RemoteOperation> requests = new ArrayList<>();
        for (long blockNumber = range.startInclusive(); blockNumber <= range.endInclusive(); blockNumber++) {
            requests.add(new RemoteOperation(
                    "eth_getBlockByNumber",
                    List.of(toQuantity(blockNumber), true)));
            if (blockNumber == Long.MAX_VALUE) {
                break;
            }
        }
        RemoteExecution<List<JsonNode>> responses = runtime.executeBatchWithMetrics(requests)
                .map(results -> results.stream().map(result -> result.value()).toList());
        return responses.map(results -> decode(range, results));
    }

    public RemoteExecution<List<EthereumTransaction>> getTransaction(String transactionHash)
    {
        String hash = normalizeHash(transactionHash, "transaction hash");
        ExecutionContext context = runtime.newExecutionContext();
        RemoteCacheKey key = transactionKey(hash);
        Optional<JsonNode> cached = context.getCached(key);
        if (cached.isPresent()) {
            try {
                return context.execution(CompletableFuture.completedFuture(List.of(decodeTransaction(cached.orElseThrow(), hash))));
            }
            catch (IllegalStateException e) {
                context.invalidate(key);
            }
        }

        AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();
        CompletableFuture<JsonNode> transaction = context.executeBatch(List.of(
                        new RemoteOperation("eth_getTransactionByHash", List.of(hash))))
                .map(results -> results.getFirst().value())
                .future();
        CompletableFuture<EthereumFinalityBoundaries> boundaries = runtime.isCacheEnabled() ?
                finalityResolver.resolve(context) :
                CompletableFuture.completedFuture(EthereumFinalityBoundaries.unavailable());
        CompletableFuture<Void> combined = CompletableFuture.allOf(transaction, boundaries);
        combined.whenComplete((value, failure) -> {
            if (combined.isCancelled()) {
                transaction.cancel(true);
                boundaries.cancel(true);
            }
        });
        active.set(combined);
        CompletableFuture<List<EthereumTransaction>> result = combined.thenApply(ignored -> {
            JsonNode value = transaction.join();
            if (value.isNull()) {
                return List.of();
            }
            EthereumTransaction decoded = decodeTransaction(value, hash);
            EthereumFinality finality = decoded.blockNumber() == null ?
                    EthereumFinality.HEAD :
                    boundaries.join().classify(decoded.blockNumber());
            if (runtime.isCacheEnabled() && finality == EthereumFinality.FINALIZED) {
                context.admit(key, value);
            }
            else {
                context.revalidation();
            }
            return List.of(decoded);
        });
        propagateCancellation(result, active);
        return context.execution(result);
    }

    private static List<EthereumTransaction> decode(BlockRange range, List<JsonNode> results)
    {
        List<EthereumTransaction> transactions = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            JsonNode block = results.get(index);
            long expectedBlockNumber = range.startInclusive() + index;
            validateTransactionBlock(block, expectedBlockNumber);
            JsonNode blockTransactions = block.get("transactions");
            for (JsonNode transaction : blockTransactions) {
                transactions.add(decodeTransaction(transaction, null));
            }
        }
        return List.copyOf(transactions);
    }

    static void validateTransactionBlock(JsonNode block, long expectedBlockNumber)
    {
        if (block == null || block.isNull() || requiredQuantity(block, "number") != expectedBlockNumber) {
            throw new IllegalStateException("Ethereum transaction response does not match its block request");
        }
        JsonNode blockTransactions = block.get("transactions");
        if (blockTransactions == null || !blockTransactions.isArray()) {
            throw new IllegalStateException("Ethereum block response is missing transactions");
        }
        for (JsonNode transaction : blockTransactions) {
            if (requiredQuantity(transaction, "blockNumber") != expectedBlockNumber) {
                throw new IllegalStateException("Ethereum transaction block number does not match its block response");
            }
            requiredText(transaction, "hash");
            requiredText(transaction, "from");
            optionalText(transaction, "to");
        }
    }

    static void validateCacheableTransactionBlock(JsonNode block, long expectedBlockNumber)
    {
        validateTransactionBlock(block, expectedBlockNumber);
        String blockHash = normalizeHash(requiredText(block, "hash"), "block hash");
        for (JsonNode transaction : block.get("transactions")) {
            normalizeHash(requiredText(transaction, "hash"), "transaction hash");
            String transactionBlockHash = normalizeHash(requiredText(transaction, "blockHash"), "transaction block hash");
            if (!transactionBlockHash.equals(blockHash)) {
                throw new IllegalStateException("Ethereum transaction block hash does not match its block response");
            }
        }
    }

    private static EthereumTransaction decodeTransaction(JsonNode transaction, String expectedHash)
    {
        String hash = requiredText(transaction, "hash");
        if (expectedHash != null) {
            hash = normalizeHash(hash, "transaction hash");
            if (!hash.equals(expectedHash)) {
                throw new IllegalStateException("Ethereum transaction hash does not match its request");
            }
        }
        Long blockNumber = optionalQuantity(transaction, "blockNumber");
        if (expectedHash != null && blockNumber != null) {
            normalizeHash(requiredText(transaction, "blockHash"), "block hash");
        }
        return new EthereumTransaction(
                hash,
                blockNumber,
                requiredText(transaction, "from"),
                optionalText(transaction, "to"),
                transaction.toString());
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

    private static RemoteCacheKey transactionKey(String transactionHash)
    {
        return new RemoteCacheKey(
                "ethereum",
                "eth_getTransactionByHash",
                transactionHash.toLowerCase(Locale.ENGLISH),
                "transaction-with-inclusion",
                1);
    }

    private static void propagateCancellation(CompletableFuture<?> result, AtomicReference<CompletableFuture<?>> active)
    {
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                Optional.ofNullable(active.get()).ifPresent(future -> future.cancel(true));
            }
        });
    }

    public record EthereumTransaction(String hash, Long blockNumber, String fromAddress, String toAddress, String rawJson)
    {
        public EthereumTransaction(String hash, Long blockNumber, String fromAddress, String toAddress)
        {
            this(hash, blockNumber, fromAddress, toAddress, "{}");
        }

        public EthereumTransaction
        {
            requireNonNull(hash, "hash is null");
            if (blockNumber != null && blockNumber < 0) {
                throw new IllegalArgumentException("blockNumber is negative");
            }
            requireNonNull(fromAddress, "fromAddress is null");
            requireNonNull(rawJson, "rawJson is null");
        }

        @Override
        public boolean equals(Object other)
        {
            return other instanceof EthereumTransaction transaction &&
                    java.util.Objects.equals(hash, transaction.hash) &&
                    java.util.Objects.equals(blockNumber, transaction.blockNumber) &&
                    java.util.Objects.equals(fromAddress, transaction.fromAddress) &&
                    java.util.Objects.equals(toAddress, transaction.toAddress);
        }

        @Override
        public int hashCode()
        {
            return java.util.Objects.hash(hash, blockNumber, fromAddress, toAddress);
        }
    }
}
