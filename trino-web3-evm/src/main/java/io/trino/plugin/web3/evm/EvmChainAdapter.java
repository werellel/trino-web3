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
import io.trino.plugin.web3.chain.ChainColumnDescriptor;
import io.trino.plugin.web3.chain.ChainDescriptorCodec;
import io.trino.plugin.web3.chain.ChainTableDescriptor;
import io.trino.plugin.web3.chain.RemoteMethodDescriptor;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteOperation;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Shared EVM planning and execution wiring for EVM-compatible chains. */
public class EvmChainAdapter implements ExecutableChainAdapter
{
    private static final String BLOCKS_TABLE = "blocks";
    private static final String TRANSACTIONS_TABLE = "transactions";
    private static final String BLOCK_NUMBER_COLUMN = "block_number";
    private static final String HASH_COLUMN = "hash";
    private static final String RECEIPT_HASH_COLUMN = "transaction_hash";

    private final String chainName;
    private final ChainDescriptor descriptor;
    private final Optional<String> expectedChainId;

    protected EvmChainAdapter(String chainName, String descriptorResource, Optional<String> expectedChainId)
    {
        this.chainName = requireNonNull(chainName, "chainName is null");
        requireNonNull(descriptorResource, "descriptorResource is null");
        this.expectedChainId = requireNonNull(expectedChainId, "expectedChainId is null").map(EvmChainAdapter::normalizeChainId);
        ChainDescriptor loadedDescriptor = withReceiptAndLogTables(loadDescriptor(chainName, descriptorResource));
        this.descriptor = loadedDescriptor.schemaName().equals(chainName) ? loadedDescriptor : new ChainDescriptor(
                loadedDescriptor.apiVersion(),
                chainName,
                chainName,
                loadedDescriptor.adapterVersion(),
                loadedDescriptor.tables());
    }

    @Override
    public final ChainDescriptor descriptor()
    {
        return descriptor;
    }

    @Override
    public final EndpointIdentityProbe endpointIdentityProbe()
    {
        return new EndpointIdentityProbe(new RemoteOperation("eth_chainId", List.of()), response -> chainId(response, expectedChainId));
    }

    @Override
    public final List<ChainSplit> planSplits(ChainScan scan, ChainSplitLimits limits)
    {
        if (descriptor.table(scan.tableName()).isEmpty()) {
            throw new ChainPlanningException("unknown " + chainName + " table " + scan.tableName());
        }
        String hashColumn = scan.tableName().equals("receipts") ? RECEIPT_HASH_COLUMN : HASH_COLUMN;
        if (Set.of(TRANSACTIONS_TABLE, "receipts").contains(scan.tableName()) && scan.discreteValues().containsKey(hashColumn)) {
            verifyMethod(scan, "by-hash");
            verifyPredicateColumns(scan, Set.of(), Set.of(hashColumn));
            List<String> hashes = scan.discreteValues().get(hashColumn);
            if (hashes.isEmpty()) {
                throw new ChainPlanningException(chainName + "." + scan.tableName() + " requires at least one transaction hash");
            }
            if (hashes.size() > limits.maximumDiscreteValuesPerQuery()) {
                throw new ChainPlanningException(chainName + "." + scan.tableName() + " hash predicate exceeds the configured query limit of " + limits.maximumDiscreteValuesPerQuery());
            }
            return hashes.stream()
                    .map(hash -> normalizeTransactionHash(chainName, scan.tableName(), hash))
                    .map(hash -> new DiscreteValueChainSplit(hashColumn, hash))
                    .map(ChainSplit.class::cast)
                    .toList();
        }

        if (scan.tableName().equals("receipts")) {
            throw new ChainPlanningException(chainName + ".receipts requires a transaction hash equality/IN predicate");
        }

        verifyMethod(scan, "by-block-number");
        verifyPredicateColumns(scan, Set.of(BLOCK_NUMBER_COLUMN), Set.of());
        ChainScan.LongRange range = scan.ranges().get(BLOCK_NUMBER_COLUMN);
        if (range == null) {
            throw new ChainPlanningException(chainName + "." + scan.tableName() + " requires a bounded block_number predicate" +
                    (scan.tableName().equals(TRANSACTIONS_TABLE) ? " or transaction hash equality/IN predicate" : ""));
        }
        if (range.endInclusive() - range.startInclusive() >= limits.maximumRangeItemsPerQuery()) {
            throw new ChainPlanningException("block range exceeds maximumBlocksPerQuery");
        }
        return splitRange(range, limits.maximumRangeItemsPerSplit());
    }

    @Override
    public final ChainDataClient createDataClient(RemoteExecutionRuntime runtime)
    {
        return new EthereumChainDataClient(runtime, chainName);
    }

    private void verifyPredicateColumns(ChainScan scan, Set<String> ranges, Set<String> discreteValues)
    {
        if (!ranges.containsAll(scan.ranges().keySet()) || !discreteValues.containsAll(scan.discreteValues().keySet())) {
            throw new ChainPlanningException("unsupported pushed predicates for " + chainName + "." + scan.tableName());
        }
    }

    private void verifyMethod(ChainScan scan, String expectedMethod)
    {
        if (scan.methodName().isPresent() && !scan.methodName().orElseThrow().equals(expectedMethod)) {
            throw new ChainPlanningException("descriptor method " + scan.methodName().orElseThrow() + " does not match predicates for " + chainName + "." + scan.tableName());
        }
    }

    private static String normalizeTransactionHash(String chainName, String tableName, String hash)
    {
        try {
            return EthereumJson.normalizeHash(hash, "transaction hash");
        }
        catch (IllegalStateException e) {
            throw new ChainPlanningException(chainName + "." + tableName + " hash predicate contains an invalid transaction hash");
        }
    }

    private static String chainId(JsonNode response, Optional<String> expected)
    {
        if (!response.isTextual() || !response.textValue().matches("0x[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("invalid EVM chain identity");
        }
        String normalized = normalizeChainId(response.textValue());
        if (expected.isPresent() && !expected.orElseThrow().equals(normalized)) {
            throw new IllegalArgumentException("EVM endpoint chain identity does not match " + expected.orElseThrow());
        }
        return normalized;
    }

    private static String normalizeChainId(String value)
    {
        try {
            BigInteger id = new BigInteger(value.substring(2), 16);
            if (id.signum() <= 0) {
                throw new IllegalArgumentException("invalid EVM chain identity");
            }
            return "0x" + id.toString(16);
        }
        catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid EVM chain identity");
        }
    }

    private static List<ChainSplit> splitRange(ChainScan.LongRange range, long maximumSplitSize)
    {
        ArrayList<ChainSplit> splits = new ArrayList<>();
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

    private static ChainDescriptor loadDescriptor(String chainName, String resource)
    {
        InputStream input = EvmChainAdapter.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException("built-in " + chainName + " chain descriptor is missing");
        }
        return ChainDescriptorCodec.fromJson(input);
    }

    private static ChainDescriptor withReceiptAndLogTables(ChainDescriptor descriptor)
    {
        List<ChainTableDescriptor> tables = new ArrayList<>(descriptor.tables());
        if (descriptor.table("receipts").isEmpty()) {
            tables.add(receiptsTable());
        }
        if (descriptor.table("logs").isEmpty()) {
            tables.add(logsTable());
        }
        return new ChainDescriptor(descriptor.apiVersion(), descriptor.name(), descriptor.schemaName(), descriptor.adapterVersion() + 1, tables);
    }

    private static ChainTableDescriptor receiptsTable()
    {
        List<ChainColumnDescriptor> columns = List.of(
                new ChainColumnDescriptor("transaction_hash", "varchar", false),
                new ChainColumnDescriptor("transaction_index", "bigint", true),
                new ChainColumnDescriptor("block_number", "bigint", true),
                new ChainColumnDescriptor("block_hash", "varchar", true),
                new ChainColumnDescriptor("from_address", "varchar", false),
                new ChainColumnDescriptor("to_address", "varchar", true),
                new ChainColumnDescriptor("contract_address", "varchar", true),
                new ChainColumnDescriptor("cumulative_gas_used", "bigint", true),
                new ChainColumnDescriptor("gas_used", "bigint", true),
                new ChainColumnDescriptor("status", "bigint", true),
                new ChainColumnDescriptor("logs_bloom", "varchar", true),
                new ChainColumnDescriptor("raw_json", "varchar", false));
        List<RemoteMethodDescriptor.ResponseField> fields = List.of(
                new RemoteMethodDescriptor.ResponseField("transaction_hash", "/transactionHash", true),
                new RemoteMethodDescriptor.ResponseField("transaction_index", "/transactionIndex", false),
                new RemoteMethodDescriptor.ResponseField("block_number", "/blockNumber", false),
                new RemoteMethodDescriptor.ResponseField("block_hash", "/blockHash", false),
                new RemoteMethodDescriptor.ResponseField("from_address", "/from", true),
                new RemoteMethodDescriptor.ResponseField("to_address", "/to", false),
                new RemoteMethodDescriptor.ResponseField("contract_address", "/contractAddress", false),
                new RemoteMethodDescriptor.ResponseField("cumulative_gas_used", "/cumulativeGasUsed", false),
                new RemoteMethodDescriptor.ResponseField("gas_used", "/gasUsed", false),
                new RemoteMethodDescriptor.ResponseField("status", "/status", false),
                new RemoteMethodDescriptor.ResponseField("logs_bloom", "/logsBloom", false),
                new RemoteMethodDescriptor.ResponseField("raw_json", "", true));
        RemoteMethodDescriptor method = new RemoteMethodDescriptor(
                "by-hash", RemoteMethodDescriptor.Protocol.JSON_RPC, "eth_getTransactionReceipt", "",
                List.of(new RemoteMethodDescriptor.RequestBinding("0", RemoteMethodDescriptor.RequestKind.PREDICATE, "transaction_hash", RemoteMethodDescriptor.RequestLocation.PARAMETER, true)),
                new RemoteMethodDescriptor.ResponseMapping(RemoteMethodDescriptor.Cardinality.SINGLE, "", fields));
        return new ChainTableDescriptor("receipts", 1, columns, List.of(method));
    }

    private static ChainTableDescriptor logsTable()
    {
        List<ChainColumnDescriptor> columns = List.of(
                new ChainColumnDescriptor("block_number", "bigint", false),
                new ChainColumnDescriptor("block_hash", "varchar", true),
                new ChainColumnDescriptor("transaction_hash", "varchar", false),
                new ChainColumnDescriptor("transaction_index", "bigint", true),
                new ChainColumnDescriptor("log_index", "bigint", false),
                new ChainColumnDescriptor("address", "varchar", false),
                new ChainColumnDescriptor("topic0", "varchar", true),
                new ChainColumnDescriptor("topic1", "varchar", true),
                new ChainColumnDescriptor("topic2", "varchar", true),
                new ChainColumnDescriptor("topic3", "varchar", true),
                new ChainColumnDescriptor("data", "varchar", false),
                new ChainColumnDescriptor("removed", "boolean", true),
                new ChainColumnDescriptor("raw_json", "varchar", false));
        List<RemoteMethodDescriptor.ResponseField> fields = List.of(
                new RemoteMethodDescriptor.ResponseField("block_number", "/blockNumber", true),
                new RemoteMethodDescriptor.ResponseField("block_hash", "/blockHash", false),
                new RemoteMethodDescriptor.ResponseField("transaction_hash", "/transactionHash", true),
                new RemoteMethodDescriptor.ResponseField("transaction_index", "/transactionIndex", false),
                new RemoteMethodDescriptor.ResponseField("log_index", "/logIndex", true),
                new RemoteMethodDescriptor.ResponseField("address", "/address", true),
                new RemoteMethodDescriptor.ResponseField("topic0", "/topics/0", false),
                new RemoteMethodDescriptor.ResponseField("topic1", "/topics/1", false),
                new RemoteMethodDescriptor.ResponseField("topic2", "/topics/2", false),
                new RemoteMethodDescriptor.ResponseField("topic3", "/topics/3", false),
                new RemoteMethodDescriptor.ResponseField("data", "/data", true),
                new RemoteMethodDescriptor.ResponseField("removed", "/removed", false),
                new RemoteMethodDescriptor.ResponseField("raw_json", "", true));
        RemoteMethodDescriptor method = new RemoteMethodDescriptor(
                "by-block-number", RemoteMethodDescriptor.Protocol.JSON_RPC, "eth_getLogs", "",
                List.of(new RemoteMethodDescriptor.RequestBinding("0", RemoteMethodDescriptor.RequestKind.SPLIT, "block_number", RemoteMethodDescriptor.RequestLocation.PARAMETER, true)),
                new RemoteMethodDescriptor.ResponseMapping(RemoteMethodDescriptor.Cardinality.ARRAY, "", fields));
        return new ChainTableDescriptor("logs", 1, columns, List.of(method));
    }
}
