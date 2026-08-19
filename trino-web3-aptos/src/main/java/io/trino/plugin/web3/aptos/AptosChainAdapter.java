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
package io.trino.plugin.web3.aptos;

import io.trino.plugin.web3.adapter.ChainDataClient;
import io.trino.plugin.web3.adapter.ChainPlanningException;
import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import io.trino.plugin.web3.adapter.ExecutableChainAdapter;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.chain.ChainDescriptor;
import io.trino.plugin.web3.chain.ChainDescriptorCodec;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class AptosChainAdapter
        implements ExecutableChainAdapter
{
    private static final String DESCRIPTOR_RESOURCE = "aptos-chain.json";
    private static final String TRANSACTIONS_TABLE = "transactions";
    private static final String LEDGER_VERSION_COLUMN = "ledger_version";
    private static final String RANGE_METHOD = "by-ledger-version-range";
    private static final int MAXIMUM_TRANSACTIONS_PAGE_SIZE = 100;
    private static final ChainDescriptor DESCRIPTOR = loadDescriptor();

    @Override
    public ChainDescriptor descriptor()
    {
        return DESCRIPTOR;
    }

    @Override
    public List<ChainSplit> planSplits(ChainScan scan, ChainSplitLimits limits)
    {
        if (!scan.tableName().equals(TRANSACTIONS_TABLE)) {
            throw new ChainPlanningException("unknown Aptos table " + scan.tableName());
        }
        if (scan.methodName().isPresent() && !scan.methodName().orElseThrow().equals(RANGE_METHOD)) {
            throw new ChainPlanningException("descriptor method does not match predicates for aptos.transactions");
        }
        if (!Set.of(LEDGER_VERSION_COLUMN).containsAll(scan.ranges().keySet()) || !scan.discreteValues().isEmpty()) {
            throw new ChainPlanningException("unsupported pushed predicates for aptos.transactions");
        }
        ChainScan.LongRange range = scan.ranges().get(LEDGER_VERSION_COLUMN);
        if (range == null) {
            throw new ChainPlanningException("aptos.transactions requires a bounded ledger_version predicate");
        }
        if (range.endInclusive() - range.startInclusive() >= limits.maximumRangeItemsPerQuery()) {
            throw new ChainPlanningException("ledger version range exceeds the configured query limit");
        }
        return splitRange(range, Math.min(limits.maximumRangeItemsPerSplit(), MAXIMUM_TRANSACTIONS_PAGE_SIZE));
    }

    @Override
    public ChainDataClient createDataClient(RemoteExecutionRuntime runtime)
    {
        return new AptosChainDataClient(runtime);
    }

    private static List<ChainSplit> splitRange(ChainScan.LongRange range, long maximumSplitSize)
    {
        List<ChainSplit> splits = new ArrayList<>();
        long start = range.startInclusive();
        while (true) {
            long remaining = range.endInclusive() - start;
            long end = remaining < maximumSplitSize ? range.endInclusive() : Math.addExact(start, maximumSplitSize - 1);
            splits.add(new RangeChainSplit(LEDGER_VERSION_COLUMN, start, end));
            if (end == range.endInclusive()) {
                return List.copyOf(splits);
            }
            start = Math.addExact(end, 1);
        }
    }

    private static ChainDescriptor loadDescriptor()
    {
        InputStream input = AptosChainAdapter.class.getResourceAsStream(DESCRIPTOR_RESOURCE);
        if (input == null) {
            throw new IllegalStateException("built-in Aptos chain descriptor is missing");
        }
        return ChainDescriptorCodec.fromJson(input);
    }
}
