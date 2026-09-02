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
package io.trino.plugin.web3.utxo;

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
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

/** Shared bounded planning and endpoint identity for Bitcoin Core-family chains. */
public class UtxoChainAdapter
        implements ExecutableChainAdapter
{
    private static final Set<String> TABLES = Set.of("blocks", "transactions", "inputs", "outputs");
    private static final String METHOD = "by-height";

    private final ChainDescriptor descriptor;
    private final String chainName;
    private final Predicate<String> subversionMatcher;

    public UtxoChainAdapter(ChainDescriptor descriptor, String chainName, Predicate<String> subversionMatcher)
    {
        this.descriptor = requireNonNull(descriptor, "descriptor is null");
        this.chainName = requireNonNull(chainName, "chainName is null");
        if (chainName.isBlank() || !descriptor.schemaName().equals(chainName)) {
            throw new IllegalArgumentException("UTXO adapter chain name must match its descriptor schema");
        }
        this.subversionMatcher = requireNonNull(subversionMatcher, "subversionMatcher is null");
    }

    @Override
    public ChainDescriptor descriptor()
    {
        return descriptor;
    }

    @Override
    public EndpointIdentityProbe endpointIdentityProbe()
    {
        return new EndpointIdentityProbe(new RemoteOperation("getnetworkinfo", List.of()), response -> identity(response));
    }

    @Override
    public List<ChainSplit> planSplits(ChainScan scan, ChainSplitLimits limits)
    {
        if (!TABLES.contains(scan.tableName()) || descriptor.table(scan.tableName()).isEmpty()) {
            throw new ChainPlanningException("unknown " + chainName + " table " + scan.tableName());
        }
        if (scan.methodName().isPresent() && !scan.methodName().orElseThrow().equals(METHOD)) {
            throw new ChainPlanningException("descriptor method does not match predicates for " + chainName + "." + scan.tableName());
        }
        String column = scan.tableName().equals("blocks") ? "height" : "block_height";
        if (!Set.of(column).containsAll(scan.ranges().keySet()) || !scan.discreteValues().isEmpty()) {
            throw new ChainPlanningException("unsupported pushed predicates for " + chainName + "." + scan.tableName());
        }
        ChainScan.LongRange range = scan.ranges().get(column);
        if (range == null) {
            throw new ChainPlanningException(chainName + "." + scan.tableName() + " requires a bounded " + column + " predicate");
        }
        if (range.endInclusive() - range.startInclusive() >= limits.maximumRangeItemsPerQuery()) {
            throw new ChainPlanningException(chainName + " height range exceeds the configured query limit");
        }
        List<ChainSplit> splits = new ArrayList<>();
        long start = range.startInclusive();
        while (true) {
            long remaining = range.endInclusive() - start;
            long end = remaining < limits.maximumRangeItemsPerSplit() ? range.endInclusive() : Math.addExact(start, limits.maximumRangeItemsPerSplit() - 1);
            splits.add(new RangeChainSplit(column, start, end));
            if (end == range.endInclusive()) {
                return List.copyOf(splits);
            }
            start = Math.addExact(end, 1);
        }
    }

    @Override
    public ChainDataClient createDataClient(RemoteExecutionRuntime runtime)
    {
        return new UtxoChainDataClient(runtime, chainName);
    }

    private String identity(JsonNode response)
    {
        JsonNode value = response.get("subversion");
        if (value == null || !value.isTextual() || !subversionMatcher.test(value.textValue())) {
            throw new IllegalArgumentException("invalid " + chainName + " node identity");
        }
        return chainName;
    }
}
