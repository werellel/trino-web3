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
import io.trino.plugin.web3.adapter.KeyedRangeChainSplit;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.chain.ChainDescriptor;
import io.trino.plugin.web3.chain.ChainDescriptorCodec;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AptosChainAdapter
        implements ExecutableChainAdapter
{
    private static final String DESCRIPTOR_RESOURCE = "aptos-chain.json";
    private static final String TRANSACTIONS_TABLE = "transactions";
    private static final String EVENTS_TABLE = "events";
    private static final String LEDGER_VERSION_COLUMN = "ledger_version";
    private static final String SEQUENCE_NUMBER_COLUMN = "sequence_number";
    private static final String ACCOUNT_ADDRESS_COLUMN = "account_address";
    private static final String CREATION_NUMBER_COLUMN = "creation_number";
    private static final String TRANSACTIONS_RANGE_METHOD = "by-ledger-version-range";
    private static final String EVENTS_RANGE_METHOD = "by-account-creation-number-sequence-range";
    private static final int MAXIMUM_REST_PAGE_SIZE = 100;
    private static final ChainDescriptor DESCRIPTOR = loadDescriptor();

    @Override
    public ChainDescriptor descriptor()
    {
        return DESCRIPTOR;
    }

    @Override
    public List<ChainSplit> planSplits(ChainScan scan, ChainSplitLimits limits)
    {
        return switch (scan.tableName()) {
            case TRANSACTIONS_TABLE -> planTransactionSplits(scan, limits);
            case EVENTS_TABLE -> planEventSplits(scan, limits);
            default -> throw new ChainPlanningException("unknown Aptos table " + scan.tableName());
        };
    }

    @Override
    public ChainDataClient createDataClient(RemoteExecutionRuntime runtime)
    {
        return new AptosChainDataClient(runtime);
    }

    private static List<ChainSplit> planTransactionSplits(ChainScan scan, ChainSplitLimits limits)
    {
        if (scan.methodName().isPresent() && !scan.methodName().orElseThrow().equals(TRANSACTIONS_RANGE_METHOD)) {
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
        return splitRange(LEDGER_VERSION_COLUMN, range, Math.min(limits.maximumRangeItemsPerSplit(), MAXIMUM_REST_PAGE_SIZE));
    }

    private static List<ChainSplit> planEventSplits(ChainScan scan, ChainSplitLimits limits)
    {
        if (scan.methodName().isPresent() && !scan.methodName().orElseThrow().equals(EVENTS_RANGE_METHOD)) {
            throw new ChainPlanningException("descriptor method does not match predicates for aptos.events");
        }
        if (!Set.of(SEQUENCE_NUMBER_COLUMN).containsAll(scan.ranges().keySet()) ||
                !Set.of(ACCOUNT_ADDRESS_COLUMN, CREATION_NUMBER_COLUMN).containsAll(scan.discreteValues().keySet())) {
            throw new ChainPlanningException("unsupported pushed predicates for aptos.events");
        }
        ChainScan.LongRange range = scan.ranges().get(SEQUENCE_NUMBER_COLUMN);
        if (range == null) {
            throw new ChainPlanningException("aptos.events requires a bounded sequence_number predicate");
        }
        String accountAddress = singleValue(scan, ACCOUNT_ADDRESS_COLUMN);
        String creationNumber = singleValue(scan, CREATION_NUMBER_COLUMN);
        if (range.endInclusive() - range.startInclusive() >= limits.maximumRangeItemsPerQuery()) {
            throw new ChainPlanningException("event sequence range exceeds the configured query limit");
        }
        Map<String, String> keys = Map.of(
                ACCOUNT_ADDRESS_COLUMN, normalizeAddress(accountAddress),
                CREATION_NUMBER_COLUMN, normalizeUnsignedDecimal(creationNumber, "creation_number"));
        return splitEventRange(keys, range, Math.min(limits.maximumRangeItemsPerSplit(), MAXIMUM_REST_PAGE_SIZE));
    }

    private static String singleValue(ChainScan scan, String column)
    {
        List<String> values = scan.discreteValues().get(column);
        if (values == null || values.size() != 1) {
            throw new ChainPlanningException("aptos.events requires exactly one " + column + " predicate");
        }
        return values.getFirst();
    }

    static String normalizeAddress(String value)
    {
        if (value == null || !value.matches("0x[0-9a-fA-F]{1,64}")) {
            throw new ChainPlanningException("aptos.events has an invalid account_address");
        }
        String digits = value.substring(2).replaceFirst("^0+(?!$)", "").toLowerCase(java.util.Locale.ROOT);
        return "0x" + digits;
    }

    static String normalizeUnsignedDecimal(String value, String field)
    {
        if (value == null || !value.matches("[0-9]{1,20}")) {
            throw new ChainPlanningException("aptos.events has an invalid " + field);
        }
        try {
            java.math.BigInteger number = new java.math.BigInteger(value);
            if (number.bitLength() > 64) {
                throw new NumberFormatException("value exceeds unsigned 64-bit range");
            }
            return number.toString();
        }
        catch (NumberFormatException e) {
            throw new ChainPlanningException("aptos.events has an invalid " + field);
        }
    }

    private static List<ChainSplit> splitRange(String column, ChainScan.LongRange range, long maximumSplitSize)
    {
        List<ChainSplit> splits = new ArrayList<>();
        long start = range.startInclusive();
        while (true) {
            long remaining = range.endInclusive() - start;
            long end = remaining < maximumSplitSize ? range.endInclusive() : Math.addExact(start, maximumSplitSize - 1);
            splits.add(new RangeChainSplit(column, start, end));
            if (end == range.endInclusive()) {
                return List.copyOf(splits);
            }
            start = Math.addExact(end, 1);
        }
    }

    private static List<ChainSplit> splitEventRange(Map<String, String> keys, ChainScan.LongRange range, long maximumSplitSize)
    {
        List<ChainSplit> splits = new ArrayList<>();
        long start = range.startInclusive();
        while (true) {
            long remaining = range.endInclusive() - start;
            long end = remaining < maximumSplitSize ? range.endInclusive() : Math.addExact(start, maximumSplitSize - 1);
            splits.add(new KeyedRangeChainSplit(keys, SEQUENCE_NUMBER_COLUMN, start, end));
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
