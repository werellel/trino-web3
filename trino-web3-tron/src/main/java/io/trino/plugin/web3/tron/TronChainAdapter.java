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
package io.trino.plugin.web3.tron;

import com.fasterxml.jackson.databind.JsonNode;
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
import io.trino.plugin.web3.runtime.RestRemoteRequest;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TronChainAdapter
        implements ExecutableChainAdapter
{
    private static final String DESCRIPTOR_RESOURCE = "tron-chain.json";
    private static final ChainDescriptor DESCRIPTOR = loadDescriptor();

    @Override
    public ChainDescriptor descriptor()
    {
        return DESCRIPTOR;
    }

    @Override
    public EndpointIdentityProbe endpointIdentityProbe()
    {
        return new EndpointIdentityProbe(
                new RestRemoteRequest("GET", "/wallet/getnowblock", Map.of(), Optional.empty()),
                TronChainAdapter::chainIdentity);
    }

    @Override
    public List<ChainSplit> planSplits(ChainScan scan, ChainSplitLimits limits)
    {
        if (!scan.tableName().equals("blocks") && !scan.tableName().equals("transactions")) {
            throw new ChainPlanningException("unknown Tron table " + scan.tableName());
        }
        if (scan.methodName().isPresent() && !scan.methodName().orElseThrow().equals("by-block-number")) {
            throw new ChainPlanningException("descriptor method does not match predicates for tron." + scan.tableName());
        }
        if (!scan.discreteValues().isEmpty()) {
            throw new ChainPlanningException("unsupported pushed predicates for tron." + scan.tableName());
        }
        ChainScan.LongRange range = scan.ranges().get("block_number");
        if (range == null) {
            throw new ChainPlanningException("tron." + scan.tableName() + " requires a bounded block_number predicate");
        }
        if (!scan.ranges().keySet().equals(java.util.Set.of("block_number"))) {
            throw new ChainPlanningException("unsupported pushed predicates for tron." + scan.tableName());
        }
        if (range.endInclusive() - range.startInclusive() >= limits.maximumRangeItemsPerQuery()) {
            throw new ChainPlanningException("block range exceeds maximumBlocksPerQuery");
        }
        List<ChainSplit> splits = new ArrayList<>();
        long start = range.startInclusive();
        while (true) {
            long remaining = range.endInclusive() - start;
            long end = remaining < limits.maximumRangeItemsPerSplit() ? range.endInclusive() : Math.addExact(start, limits.maximumRangeItemsPerSplit() - 1);
            splits.add(new RangeChainSplit("block_number", start, end));
            if (end == range.endInclusive()) {
                return List.copyOf(splits);
            }
            start = Math.addExact(end, 1);
        }
    }

    @Override
    public ChainDataClient createDataClient(RemoteExecutionRuntime runtime)
    {
        return new TronChainDataClient(runtime);
    }

    private static String chainIdentity(JsonNode response)
    {
        if (!response.isObject() || !response.path("blockID").isTextual() || !response.path("block_header").path("raw_data").isObject()) {
            throw new IllegalArgumentException("invalid Tron node identity");
        }
        return "tron";
    }

    private static ChainDescriptor loadDescriptor()
    {
        InputStream input = TronChainAdapter.class.getResourceAsStream(DESCRIPTOR_RESOURCE);
        if (input == null) {
            throw new IllegalStateException("built-in Tron chain descriptor is missing");
        }
        return ChainDescriptorCodec.fromJson(input);
    }
}
