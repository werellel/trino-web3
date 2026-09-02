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

import io.trino.plugin.web3.adapter.ChainDataClient;
import io.trino.plugin.web3.adapter.ChainPlanningException;
import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import io.trino.plugin.web3.adapter.DiscreteValueChainSplit;
import io.trino.plugin.web3.adapter.EndpointIdentityProbe;
import io.trino.plugin.web3.adapter.ExecutableChainAdapter;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.chain.ChainDescriptor;
import io.trino.plugin.web3.chain.ChainDescriptorCodec;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteOperation;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

public final class EthereumChainAdapter
        implements ExecutableChainAdapter
{
    private static final String DESCRIPTOR_RESOURCE = "ethereum-chain.json";
    private static final String BLOCKS_TABLE = "blocks";
    private static final String TRANSACTIONS_TABLE = "transactions";
    private static final String BLOCK_NUMBER_COLUMN = "block_number";
    private static final String HASH_COLUMN = "hash";
    private static final ChainDescriptor DESCRIPTOR = loadDescriptor();

    @Override
    public ChainDescriptor descriptor()
    {
        return DESCRIPTOR;
    }

    @Override
    public EndpointIdentityProbe endpointIdentityProbe()
    {
        return new EndpointIdentityProbe(new RemoteOperation("eth_chainId", List.of()), EthereumChainAdapter::chainId);
    }

    @Override
    public List<ChainSplit> planSplits(ChainScan scan, ChainSplitLimits limits)
    {
        if (descriptor().table(scan.tableName()).isEmpty()) {
            throw new ChainPlanningException("unknown Ethereum table " + scan.tableName());
        }
        if (scan.tableName().equals(TRANSACTIONS_TABLE) && scan.discreteValues().containsKey(HASH_COLUMN)) {
            verifyMethod(scan, "by-hash");
            verifyPredicateColumns(scan, Set.of(), Set.of(HASH_COLUMN));
            List<String> hashes = scan.discreteValues().get(HASH_COLUMN);
            if (hashes.isEmpty()) {
                throw new ChainPlanningException("ethereum.transactions requires at least one transaction hash");
            }
            if (hashes.size() > limits.maximumDiscreteValuesPerQuery()) {
                throw new ChainPlanningException(
                        "ethereum.transactions hash predicate exceeds the configured query limit of " + limits.maximumDiscreteValuesPerQuery());
            }
            return hashes.stream()
                    .map(EthereumChainAdapter::normalizeTransactionHash)
                    .map(hash -> new DiscreteValueChainSplit(HASH_COLUMN, hash))
                    .map(ChainSplit.class::cast)
                    .toList();
        }

        verifyMethod(scan, "by-block-number");
        verifyPredicateColumns(scan, Set.of(BLOCK_NUMBER_COLUMN), Set.of());
        ChainScan.LongRange range = scan.ranges().get(BLOCK_NUMBER_COLUMN);
        if (range == null) {
            throw new ChainPlanningException(
                    "ethereum." + scan.tableName() + " requires a bounded block_number predicate" +
                            (scan.tableName().equals(TRANSACTIONS_TABLE) ? " or transaction hash equality/IN predicate" : ""));
        }
        if (range.endInclusive() - range.startInclusive() >= limits.maximumRangeItemsPerQuery()) {
            throw new ChainPlanningException("block range exceeds maximumBlocksPerQuery");
        }
        return splitRange(range, limits.maximumRangeItemsPerSplit());
    }

    @Override
    public ChainDataClient createDataClient(RemoteExecutionRuntime runtime)
    {
        return new EthereumChainDataClient(runtime);
    }

    private static void verifyPredicateColumns(ChainScan scan, Set<String> ranges, Set<String> discreteValues)
    {
        if (!ranges.containsAll(scan.ranges().keySet()) || !discreteValues.containsAll(scan.discreteValues().keySet())) {
            throw new ChainPlanningException("unsupported pushed predicates for ethereum." + scan.tableName());
        }
    }

    private static void verifyMethod(ChainScan scan, String expectedMethod)
    {
        if (scan.methodName().isPresent() && !scan.methodName().orElseThrow().equals(expectedMethod)) {
            throw new ChainPlanningException(
                    "descriptor method " + scan.methodName().orElseThrow() + " does not match predicates for ethereum." + scan.tableName());
        }
    }

    private static String normalizeTransactionHash(String hash)
    {
        try {
            return EthereumJson.normalizeHash(hash, "transaction hash");
        }
        catch (IllegalStateException e) {
            throw new ChainPlanningException("ethereum.transactions hash predicate contains an invalid transaction hash");
        }
    }

    private static String chainId(com.fasterxml.jackson.databind.JsonNode response)
    {
        if (!response.isTextual() || !response.textValue().matches("0x[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("invalid EVM chain identity");
        }
        try {
            java.math.BigInteger value = new java.math.BigInteger(response.textValue().substring(2), 16);
            if (value.signum() <= 0) {
                throw new IllegalArgumentException("invalid EVM chain identity");
            }
            return "0x" + value.toString(16);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid EVM chain identity");
        }
    }

    private static List<ChainSplit> splitRange(ChainScan.LongRange range, long maximumSplitSize)
    {
        java.util.ArrayList<ChainSplit> splits = new java.util.ArrayList<>();
        long start = range.startInclusive();
        while (true) {
            long remaining = range.endInclusive() - start;
            long end = remaining < maximumSplitSize ? range.endInclusive() : Math.addExact(start, maximumSplitSize - 1);
            splits.add(new RangeChainSplit(BLOCK_NUMBER_COLUMN, start, end));
            if (end == range.endInclusive()) {
                return List.copyOf(splits);
            }
            start = Math.addExact(end, 1);
        }
    }

    private static ChainDescriptor loadDescriptor()
    {
        InputStream input = EthereumChainAdapter.class.getResourceAsStream(DESCRIPTOR_RESOURCE);
        if (input == null) {
            throw new IllegalStateException("built-in Ethereum chain descriptor is missing");
        }
        return ChainDescriptorCodec.fromJson(input);
    }
}
