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
package io.trino.plugin.web3.solana;

import io.trino.plugin.web3.adapter.ChainDataClient;
import io.trino.plugin.web3.adapter.ChainPlanningException;
import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import io.trino.plugin.web3.adapter.ExecutableChainAdapter;
import io.trino.plugin.web3.adapter.EndpointIdentityProbe;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.chain.ChainDescriptor;
import io.trino.plugin.web3.chain.ChainDescriptorCodec;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteOperation;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class SolanaChainAdapter
        implements ExecutableChainAdapter
{
    private static final String DESCRIPTOR_RESOURCE = "solana-chain.json";
    private static final String SLOT_COLUMN = "slot";
    private static final String BY_SLOT_METHOD = "by-slot";
    private static final ChainDescriptor DESCRIPTOR = loadDescriptor();

    @Override
    public ChainDescriptor descriptor()
    {
        return DESCRIPTOR;
    }

    @Override
    public EndpointIdentityProbe endpointIdentityProbe()
    {
        return new EndpointIdentityProbe(new RemoteOperation("getGenesisHash", List.of()), SolanaChainAdapter::genesisHash);
    }

    @Override
    public List<ChainSplit> planSplits(ChainScan scan, ChainSplitLimits limits)
    {
        if (descriptor().table(scan.tableName()).isEmpty()) {
            throw new ChainPlanningException("unknown Solana table " + scan.tableName());
        }
        if (scan.methodName().isPresent() && !scan.methodName().orElseThrow().equals(BY_SLOT_METHOD)) {
            throw new ChainPlanningException("descriptor method does not match predicates for solana." + scan.tableName());
        }
        if (!scan.discreteValues().isEmpty() || !Set.of(SLOT_COLUMN).containsAll(scan.ranges().keySet())) {
            throw new ChainPlanningException("unsupported pushed predicates for solana." + scan.tableName());
        }
        ChainScan.LongRange range = scan.ranges().get(SLOT_COLUMN);
        if (range == null) {
            throw new ChainPlanningException("solana." + scan.tableName() + " requires a bounded slot predicate");
        }
        if (range.endInclusive() - range.startInclusive() >= limits.maximumRangeItemsPerQuery()) {
            throw new ChainPlanningException("slot range exceeds the configured query limit");
        }

        long maximumItemsPerSplit = limits.maximumRangeItemsPerSplit();
        List<ChainSplit> splits = new ArrayList<>();
        for (long start = range.startInclusive(); start <= range.endInclusive(); ) {
            long end = Math.min(range.endInclusive(), Math.addExact(start, maximumItemsPerSplit - 1));
            splits.add(new RangeChainSplit(SLOT_COLUMN, start, end));
            if (end == Long.MAX_VALUE) {
                break;
            }
            start = end + 1;
        }
        return List.copyOf(splits);
    }

    @Override
    public ChainDataClient createDataClient(RemoteExecutionRuntime runtime)
    {
        return new SolanaChainDataClient(runtime);
    }

    private static ChainDescriptor loadDescriptor()
    {
        try (InputStream input = SolanaChainAdapter.class.getResourceAsStream(DESCRIPTOR_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing Solana chain descriptor");
            }
            return ChainDescriptorCodec.fromJson(input);
        }
        catch (java.io.IOException e) {
            throw new IllegalStateException("failed to load Solana chain descriptor", e);
        }
    }

    private static String genesisHash(com.fasterxml.jackson.databind.JsonNode response)
    {
        if (!response.isTextual() || !response.textValue().matches("[1-9A-HJ-NP-Za-km-z]{32,44}")) {
            throw new IllegalArgumentException("invalid Solana chain identity");
        }
        return response.textValue();
    }
}
