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
import io.trino.plugin.web3.runtime.JsonRpcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.lang.Long.parseUnsignedLong;
import static java.util.Objects.requireNonNull;

public final class EthereumBlockClient
{
    private final JsonRpcClient jsonRpcClient;

    public EthereumBlockClient(JsonRpcClient jsonRpcClient)
    {
        this.jsonRpcClient = requireNonNull(jsonRpcClient, "jsonRpcClient is null");
    }

    public CompletableFuture<List<EthereumBlock>> getBlocks(BlockRange range)
    {
        List<JsonRpcClient.JsonRpcRequest> requests = new ArrayList<>();
        for (long blockNumber = range.startInclusive(); blockNumber <= range.endInclusive(); blockNumber++) {
            requests.add(new JsonRpcClient.JsonRpcRequest(
                    blockNumber,
                    "eth_getBlockByNumber",
                    List.of(toHex(blockNumber), false)));
            if (blockNumber == Long.MAX_VALUE) {
                break;
            }
        }
        CompletableFuture<List<JsonNode>> responses = jsonRpcClient.executeBatch(requests);
        CompletableFuture<List<EthereumBlock>> blocks = responses.thenApply(results -> decode(range, results));
        blocks.whenComplete((value, failure) -> {
            if (blocks.isCancelled()) {
                responses.cancel(true);
            }
        });
        return blocks;
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
            long actualNumber = parseHex(number.textValue());
            if (actualNumber != expectedNumber) {
                throw new IllegalStateException("Ethereum block number does not match its request");
            }
            blocks.add(new EthereumBlock(actualNumber, hash.textValue()));
        }
        return List.copyOf(blocks);
    }

    private static String toHex(long value)
    {
        return "0x" + Long.toHexString(value);
    }

    private static long parseHex(String value)
    {
        if (!value.startsWith("0x")) {
            throw new IllegalStateException("Ethereum quantity is not hexadecimal");
        }
        return parseUnsignedLong(value.substring(2), 16);
    }

    public record EthereumBlock(long number, String hash)
    {
        public EthereumBlock
        {
            if (number < 0) {
                throw new IllegalArgumentException("number is negative");
            }
            requireNonNull(hash, "hash is null");
        }
    }
}
