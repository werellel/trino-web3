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

import io.trino.plugin.web3.adapter.ChainDataClient;
import io.trino.plugin.web3.adapter.ChainPlanningException;
import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import io.trino.plugin.web3.adapter.EndpointIdentityProbe;
import io.trino.plugin.web3.adapter.ExecutableChainAdapter;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.chain.ChainDescriptor;
import io.trino.plugin.web3.chain.ChainDescriptorCodec;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteOperation;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class BitcoinChainAdapter
        implements ExecutableChainAdapter
{
    private static final String DESCRIPTOR_RESOURCE = "bitcoin-chain.json";
    private static final String HEIGHT_COLUMN = "height";
    private static final String BLOCK_HEIGHT_COLUMN = "block_height";
    private static final String BY_HEIGHT_METHOD = "by-height";
    private static final Set<String> TABLES = Set.of("blocks", "transactions", "inputs", "outputs");
    private static final ChainDescriptor DESCRIPTOR = loadDescriptor();

    @Override
    public ChainDescriptor descriptor()
    {
        return DESCRIPTOR;
    }

    @Override
    public EndpointIdentityProbe endpointIdentityProbe()
    {
        return new EndpointIdentityProbe(new RemoteOperation("getblockchaininfo", List.of()), BitcoinChainAdapter::chainName);
    }

    @Override
    public List<ChainSplit> planSplits(ChainScan scan, ChainSplitLimits limits)
    {
        if (!TABLES.contains(scan.tableName()) || descriptor().table(scan.tableName()).isEmpty()) {
            throw new ChainPlanningException("unknown Bitcoin table " + scan.tableName());
        }
        if (scan.methodName().isPresent() && !scan.methodName().orElseThrow().equals(BY_HEIGHT_METHOD)) {
            throw new ChainPlanningException("descriptor method does not match predicates for bitcoin." + scan.tableName());
        }
        String rangeColumn = scan.tableName().equals("blocks") ? HEIGHT_COLUMN : BLOCK_HEIGHT_COLUMN;
        if (!Set.of(rangeColumn).containsAll(scan.ranges().keySet()) || !scan.discreteValues().isEmpty()) {
            throw new ChainPlanningException("unsupported pushed predicates for bitcoin." + scan.tableName());
        }
        ChainScan.LongRange range = scan.ranges().get(rangeColumn);
        if (range == null) {
            throw new ChainPlanningException("bitcoin." + scan.tableName() + " requires a bounded " + rangeColumn + " predicate");
        }
        if (range.endInclusive() - range.startInclusive() >= limits.maximumRangeItemsPerQuery()) {
            throw new ChainPlanningException("Bitcoin height range exceeds the configured query limit");
        }
        List<ChainSplit> splits = new ArrayList<>();
        long start = range.startInclusive();
        while (true) {
            long remaining = range.endInclusive() - start;
            long end = remaining < limits.maximumRangeItemsPerSplit() ? range.endInclusive() : Math.addExact(start, limits.maximumRangeItemsPerSplit() - 1);
            splits.add(new RangeChainSplit(rangeColumn, start, end));
            if (end == range.endInclusive()) {
                return List.copyOf(splits);
            }
            start = Math.addExact(end, 1);
        }
    }

    @Override
    public ChainDataClient createDataClient(RemoteExecutionRuntime runtime)
    {
        return new BitcoinChainDataClient(runtime);
    }

    static String chainName(com.fasterxml.jackson.databind.JsonNode response)
    {
        String chain = response.path("chain").isTextual() ? response.path("chain").textValue() : "";
        if (!chain.matches("main|test|regtest|signet")) {
            throw new IllegalArgumentException("invalid Bitcoin chain identity");
        }
        return chain;
    }

    private static ChainDescriptor loadDescriptor()
    {
        try (InputStream input = BitcoinChainAdapter.class.getResourceAsStream(DESCRIPTOR_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing Bitcoin chain descriptor");
            }
            return ChainDescriptorCodec.fromJson(input);
        }
        catch (java.io.IOException e) {
            throw new IllegalStateException("failed to load Bitcoin chain descriptor", e);
        }
    }
}
