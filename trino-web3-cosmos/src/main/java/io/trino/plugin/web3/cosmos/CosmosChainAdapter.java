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
package io.trino.plugin.web3.cosmos;

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
import java.util.Set;

public class CosmosChainAdapter
        implements ExecutableChainAdapter
{
    private final ChainDescriptor descriptor;
    private final String chainIdPrefix;

    protected CosmosChainAdapter(String descriptorResource, String chainIdPrefix)
    {
        this(descriptorResource, chainIdPrefix, null);
    }

    protected CosmosChainAdapter(String descriptorResource, String chainIdPrefix, String schemaName)
    {
        ChainDescriptor base = loadDescriptor(descriptorResource);
        descriptor = schemaName == null ? base : new ChainDescriptor(base.apiVersion(), schemaName, schemaName, base.adapterVersion(), base.tables());
        this.chainIdPrefix = chainIdPrefix;
    }

    public CosmosChainAdapter()
    {
        this("cosmos-chain.json", "cosmoshub-");
    }

    @Override
    public ChainDescriptor descriptor()
    {
        return descriptor;
    }

    @Override
    public EndpointIdentityProbe endpointIdentityProbe()
    {
        return new EndpointIdentityProbe(
                new RestRemoteRequest("GET", "/cosmos/base/tendermint/v1beta1/blocks/latest", Map.of(), Optional.empty()),
                response -> chainIdentity(response, chainIdPrefix));
    }

    @Override
    public List<ChainSplit> planSplits(ChainScan scan, ChainSplitLimits limits)
    {
        if (descriptor.table(scan.tableName()).isEmpty()) {
            throw new ChainPlanningException("unknown " + descriptor.schemaName() + " table " + scan.tableName());
        }
        if (scan.methodName().isPresent() && !scan.methodName().orElseThrow().equals("by-height")) {
            throw new ChainPlanningException("descriptor method does not match predicates for " + descriptor.schemaName() + "." + scan.tableName());
        }
        if (!scan.discreteValues().isEmpty() || !Set.of("height").containsAll(scan.ranges().keySet())) {
            throw new ChainPlanningException("unsupported pushed predicates for " + descriptor.schemaName() + "." + scan.tableName());
        }
        ChainScan.LongRange range = scan.ranges().get("height");
        if (range == null) {
            throw new ChainPlanningException(descriptor.schemaName() + "." + scan.tableName() + " requires a bounded height predicate");
        }
        if (range.endInclusive() - range.startInclusive() >= limits.maximumRangeItemsPerQuery()) {
            throw new ChainPlanningException("height range exceeds the configured query limit");
        }
        List<ChainSplit> splits = new ArrayList<>();
        for (long start = range.startInclusive(); start <= range.endInclusive(); ) {
            long end = Math.min(range.endInclusive(), Math.addExact(start, limits.maximumRangeItemsPerSplit() - 1));
            splits.add(new RangeChainSplit("height", start, end));
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
        return new CosmosChainDataClient(runtime, descriptor.schemaName());
    }

    private static String chainIdentity(JsonNode response, String prefix)
    {
        JsonNode chainId = response.path("block").path("header").path("chain_id");
        if (!chainId.isTextual() || !chainId.textValue().startsWith(prefix) || chainId.textValue().length() > 128) {
            throw new IllegalArgumentException("invalid Cosmos chain identity");
        }
        return chainId.textValue();
    }

    private static ChainDescriptor loadDescriptor(String resource)
    {
        try (InputStream input = CosmosChainAdapter.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing Cosmos chain descriptor " + resource);
            }
            return ChainDescriptorCodec.fromJson(input);
        }
        catch (java.io.IOException e) {
            throw new IllegalStateException("failed to load Cosmos chain descriptor", e);
        }
    }
}
