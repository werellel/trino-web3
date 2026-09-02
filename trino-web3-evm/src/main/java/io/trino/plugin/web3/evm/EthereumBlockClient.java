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
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime.ExecutionContext;
import io.trino.plugin.web3.runtime.RemoteOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static io.trino.plugin.web3.evm.EthereumJson.toQuantity;
import static java.util.Objects.requireNonNull;

public final class EthereumBlockClient
{
    private final RemoteExecutionRuntime runtime;
    private final EthereumFinalityResolver finalityResolver;

    public EthereumBlockClient(RemoteExecutionRuntime runtime)
    {
        this(runtime, new EthereumFinalityResolver());
    }

    EthereumBlockClient(RemoteExecutionRuntime runtime, EthereumFinalityResolver finalityResolver)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.finalityResolver = requireNonNull(finalityResolver, "finalityResolver is null");
    }

    public RemoteExecution<List<EthereumBlock>> getBlocks(BlockRange range)
    {
        requireNonNull(range, "range is null");
        if (!runtime.isCacheEnabled()) {
            return getBlocksWithoutCache(range);
        }

        ExecutionContext context = runtime.newExecutionContext();
        AtomicReference<CompletableFuture<?>> active = new AtomicReference<>();
        CompletableFuture<EthereumFinalityBoundaries> boundaries = finalityResolver.resolve(context);
        active.set(boundaries);
        CompletableFuture<List<EthereumBlock>> result = boundaries.thenCompose(finality -> {
            CompletableFuture<List<EthereumBlock>> blocks = EthereumBlockResponseLoader.load(context, range, finality, false)
                    .thenApply(results -> decodeCached(range, results));
            active.set(blocks);
            return blocks;
        });
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                Optional.ofNullable(active.get()).ifPresent(future -> future.cancel(true));
            }
        });
        return context.execution(result);
    }

    private RemoteExecution<List<EthereumBlock>> getBlocksWithoutCache(BlockRange range)
    {
        List<RemoteOperation> requests = new ArrayList<>();
        for (long blockNumber = range.startInclusive(); blockNumber <= range.endInclusive(); blockNumber++) {
            requests.add(new RemoteOperation(
                    "eth_getBlockByNumber",
                    List.of(toQuantity(blockNumber), false)));
            if (blockNumber == Long.MAX_VALUE) {
                break;
            }
        }
        RemoteExecution<List<JsonNode>> responses = runtime.executeBatchWithMetrics(requests)
                .map(results -> results.stream().map(result -> result.value()).toList());
        return responses.map(results -> decode(range, results));
    }

    private static List<EthereumBlock> decode(BlockRange range, List<JsonNode> results)
    {
        List<EthereumBlock> blocks = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            JsonNode result = results.get(index);
            if (result == null || result.isNull()) {
                throw new IllegalStateException("Ethereum block is missing for " + (range.startInclusive() + index));
            }
            JsonNode number = result.get("number");
            JsonNode hash = result.get("hash");
            if (number == null || !number.isTextual() || hash == null || !hash.isTextual()) {
                throw new IllegalStateException("Ethereum block response is missing number or hash");
            }
            long expectedNumber = range.startInclusive() + index;
            long actualNumber = parseQuantity(number.textValue());
            if (actualNumber != expectedNumber) {
                throw new IllegalStateException("Ethereum block number does not match its request");
            }
            blocks.add(new EthereumBlock(actualNumber, hash.textValue(), result.toString()));
        }
        return List.copyOf(blocks);
    }

    private static List<EthereumBlock> decodeCached(BlockRange range, List<JsonNode> results)
    {
        List<EthereumBlock> blocks = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            long blockNumber = range.startInclusive() + index;
            String hash = EthereumBlockResponseLoader.validateBlock(results.get(index), blockNumber, null);
            blocks.add(new EthereumBlock(blockNumber, hash, results.get(index).toString()));
        }
        return List.copyOf(blocks);
    }

    private static long parseQuantity(String value)
    {
        if (!value.startsWith("0x")) {
            throw new IllegalStateException("Ethereum quantity is not hexadecimal");
        }
        return Long.parseUnsignedLong(value.substring(2), 16);
    }

    public record EthereumBlock(long number, String hash, String rawJson)
    {
        public EthereumBlock(long number, String hash)
        {
            this(number, hash, "{}");
        }

        public EthereumBlock
        {
            if (number < 0) {
                throw new IllegalArgumentException("number is negative");
            }
            requireNonNull(hash, "hash is null");
            requireNonNull(rawJson, "rawJson is null");
        }

        @Override
        public boolean equals(Object other)
        {
            return other instanceof EthereumBlock block && number == block.number && hash.equals(block.hash);
        }

        @Override
        public int hashCode()
        {
            return java.util.Objects.hash(number, hash);
        }
    }

}
