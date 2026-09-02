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
import com.fasterxml.jackson.databind.node.TextNode;
import io.trino.plugin.web3.core.BlockRange;
import io.trino.plugin.web3.runtime.RemoteCacheKey;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime.ExecutionContext;
import io.trino.plugin.web3.runtime.RemoteOperation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static io.trino.plugin.web3.evm.EthereumJson.normalizeHash;
import static io.trino.plugin.web3.evm.EthereumJson.requiredQuantity;
import static io.trino.plugin.web3.evm.EthereumJson.requiredText;
import static io.trino.plugin.web3.evm.EthereumJson.toQuantity;
import static java.util.Objects.requireNonNull;

final class EthereumBlockResponseLoader
{
    private EthereumBlockResponseLoader() {}

    public static CompletableFuture<List<JsonNode>> load(
            ExecutionContext context,
            BlockRange range,
            EthereumFinalityBoundaries boundaries,
            boolean fullTransactions)
    {
        return load(context, range, boundaries, fullTransactions, "ethereum");
    }

    public static CompletableFuture<List<JsonNode>> load(
            ExecutionContext context,
            BlockRange range,
            EthereumFinalityBoundaries boundaries,
            boolean fullTransactions,
            String chainName)
    {
        requireNonNull(chainName, "chainName is null");
        int count = Math.toIntExact(range.size());
        List<JsonNode> values = new ArrayList<>(Collections.nCopies(count, null));
        List<CacheMiss> misses = new ArrayList<>();

        for (int index = 0; index < count; index++) {
            long blockNumber = range.startInclusive() + index;
            EthereumFinality finality = boundaries.classify(blockNumber);
            if (finality != EthereumFinality.FINALIZED) {
                context.revalidation();
                misses.add(CacheMiss.byNumber(index, blockNumber, finality, fullTransactions));
                continue;
            }

            RemoteCacheKey referenceKey = blockReferenceKey(chainName, blockNumber);
            Optional<JsonNode> reference = context.getCached(referenceKey);
            if (reference.isEmpty() || !reference.orElseThrow().isTextual()) {
                reference.ifPresent(ignored -> context.invalidate(referenceKey));
                context.revalidation();
                misses.add(CacheMiss.byNumber(index, blockNumber, finality, fullTransactions));
                continue;
            }

            String hash;
            try {
                hash = normalizeHash(reference.orElseThrow().textValue(), "block hash");
            }
            catch (IllegalStateException e) {
                context.invalidate(referenceKey);
                context.revalidation();
                misses.add(CacheMiss.byNumber(index, blockNumber, finality, fullTransactions));
                continue;
            }

            RemoteCacheKey payloadKey = blockPayloadKey(chainName, hash, fullTransactions);
            Optional<JsonNode> cached = context.getCached(payloadKey);
            if (cached.isPresent()) {
                try {
                    validateBlock(cached.orElseThrow(), blockNumber, hash);
                    validateRepresentation(cached.orElseThrow(), blockNumber, fullTransactions);
                    values.set(index, cached.orElseThrow());
                    continue;
                }
                catch (IllegalStateException e) {
                    context.invalidate(payloadKey);
                }
            }
            misses.add(CacheMiss.byHash(index, blockNumber, hash, finality, fullTransactions));
        }

        if (misses.isEmpty()) {
            return CompletableFuture.completedFuture(List.copyOf(values));
        }

        return context.executeBatch(misses.stream().map(CacheMiss::operation).toList()).map(results -> {
            List<DecodedMiss> decoded = new ArrayList<>();
            for (int index = 0; index < misses.size(); index++) {
                CacheMiss miss = misses.get(index);
                JsonNode value = results.get(index).value();
                String hash = validateBlock(value, miss.blockNumber(), miss.expectedHash());
                validateRepresentation(value, miss.blockNumber(), fullTransactions);
                decoded.add(new DecodedMiss(miss, hash, value));
            }
            for (DecodedMiss item : decoded) {
                CacheMiss miss = item.miss();
                context.admit(blockPayloadKey(chainName, item.hash(), fullTransactions), item.value());
                if (miss.finality() == EthereumFinality.FINALIZED) {
                    context.admit(blockReferenceKey(chainName, miss.blockNumber()), TextNode.valueOf(item.hash()));
                }
                values.set(miss.index(), item.value());
            }
            return List.copyOf(values);
        }).future();
    }

    public static String validateBlock(JsonNode result, long expectedNumber, String expectedHash)
    {
        if (result == null || result.isNull()) {
            throw new IllegalStateException("Ethereum block is missing for " + expectedNumber);
        }
        long actualNumber = requiredQuantity(result, "number");
        if (actualNumber != expectedNumber) {
            throw new IllegalStateException("Ethereum block number does not match its request");
        }
        String actualHash = normalizeHash(requiredText(result, "hash"), "block hash");
        if (expectedHash != null && !actualHash.equals(expectedHash)) {
            throw new IllegalStateException("Ethereum block hash does not match its request");
        }
        return actualHash;
    }

    private static RemoteCacheKey blockReferenceKey(String chainName, long blockNumber)
    {
        return new RemoteCacheKey(chainName, "canonical-block-reference", toQuantity(blockNumber), "hash", 1);
    }

    private static void validateRepresentation(JsonNode block, long expectedBlockNumber, boolean fullTransactions)
    {
        if (fullTransactions) {
            EthereumTransactionClient.validateCacheableTransactionBlock(block, expectedBlockNumber);
        }
    }

    private static RemoteCacheKey blockPayloadKey(String chainName, String blockHash, boolean fullTransactions)
    {
        return new RemoteCacheKey(
                chainName,
                "eth_getBlockByHash",
                normalizeHash(blockHash, "block hash"),
                "fullTransactions=" + fullTransactions,
                1);
    }

    private record CacheMiss(int index, long blockNumber, String expectedHash, EthereumFinality finality, RemoteOperation operation)
    {
        private CacheMiss
        {
            requireNonNull(finality, "finality is null");
            requireNonNull(operation, "operation is null");
        }

        public static CacheMiss byNumber(int index, long blockNumber, EthereumFinality finality, boolean fullTransactions)
        {
            return new CacheMiss(index, blockNumber, null, finality,
                    new RemoteOperation("eth_getBlockByNumber", List.of(toQuantity(blockNumber), fullTransactions)));
        }

        public static CacheMiss byHash(int index, long blockNumber, String hash, EthereumFinality finality, boolean fullTransactions)
        {
            return new CacheMiss(index, blockNumber, hash, finality,
                    new RemoteOperation("eth_getBlockByHash", List.of(hash, fullTransactions)));
        }
    }

    private record DecodedMiss(CacheMiss miss, String hash, JsonNode value)
    {
        private DecodedMiss
        {
            requireNonNull(miss, "miss is null");
            requireNonNull(hash, "hash is null");
            requireNonNull(value, "value is null");
        }
    }
}
