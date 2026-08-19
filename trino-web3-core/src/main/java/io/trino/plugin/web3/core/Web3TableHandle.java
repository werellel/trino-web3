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
package io.trino.plugin.web3.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.connector.ConnectorTableHandle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

public record Web3TableHandle(
        String schemaName,
        String tableName,
        Optional<String> methodName,
        Map<String, BlockRange> ranges,
        Map<String, List<String>> discreteValues)
        implements ConnectorTableHandle
{
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final Pattern LOGICAL_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,127}");
    private static final int MAXIMUM_PREDICATES = 64;
    private static final int MAXIMUM_DISCRETE_VALUES = 100_000;

    public Web3TableHandle(String schemaName, String tableName)
    {
        this(schemaName, tableName, Optional.empty(), Map.of(), Map.of());
    }

    public Web3TableHandle(String schemaName, String tableName, Optional<BlockRange> blockRange)
    {
        this(
                schemaName,
                tableName,
                requireNonNull(blockRange, "blockRange is null").isPresent() ?
                        Optional.of("by-block-number") : Optional.empty(),
                requireNonNull(blockRange, "blockRange is null")
                        .map(range -> Map.of("block_number", range))
                        .orElseGet(Map::of),
                Map.of());
    }

    public Web3TableHandle(String schemaName, String tableName, Optional<BlockRange> blockRange, List<String> transactionHashes)
    {
        this(
                schemaName,
                tableName,
                requireNonNull(blockRange, "blockRange is null").isPresent() ?
                        Optional.of("by-block-number") :
                        (requireNonNull(transactionHashes, "transactionHashes is null").isEmpty() ? Optional.empty() : Optional.of("by-hash")),
                requireNonNull(blockRange, "blockRange is null")
                        .map(range -> Map.of("block_number", range))
                        .orElseGet(Map::of),
                requireNonNull(transactionHashes, "transactionHashes is null").isEmpty() ?
                        Map.of() : Map.of("hash", transactionHashes));
        if (blockRange.isPresent() && !transactionHashes.isEmpty()) {
            throw new IllegalArgumentException("blockRange and transactionHashes are mutually exclusive");
        }
    }

    @JsonCreator
    public Web3TableHandle(
            @JsonProperty("schemaName") String schemaName,
            @JsonProperty("tableName") String tableName,
            @JsonProperty("methodName") Optional<String> methodName,
            @JsonProperty("ranges") Map<String, BlockRange> ranges,
            @JsonProperty("discreteValues") Map<String, List<String>> discreteValues)
    {
        this.schemaName = identifier(schemaName, "schemaName");
        this.tableName = identifier(tableName, "tableName");
        this.methodName = requireNonNull(methodName, "methodName is null")
                .map(Web3TableHandle::logicalName);
        this.ranges = immutableRanges(ranges);
        this.discreteValues = immutableDiscreteValues(discreteValues);
        if (this.ranges.size() + this.discreteValues.size() > MAXIMUM_PREDICATES) {
            throw new IllegalArgumentException("table predicates exceed maximum of " + MAXIMUM_PREDICATES);
        }
        if (this.ranges.keySet().stream().anyMatch(this.discreteValues::containsKey)) {
            throw new IllegalArgumentException("range and discrete predicates must use different columns");
        }
        if (this.methodName.isEmpty() && (!this.ranges.isEmpty() || !this.discreteValues.isEmpty())) {
            throw new IllegalArgumentException("constrained table handle requires methodName");
        }
    }

    public Web3TableHandle withPredicates(
            String methodName,
            Map<String, BlockRange> ranges,
            Map<String, List<String>> discreteValues)
    {
        return new Web3TableHandle(
                schemaName,
                tableName,
                Optional.of(requireNonNull(methodName, "methodName is null")),
                ranges,
                discreteValues);
    }

    @Override
    public String toString()
    {
        if (methodName.isEmpty()) {
            return schemaName + "." + tableName;
        }
        int valueCount = discreteValues.values().stream().mapToInt(List::size).sum();
        return schemaName + "." + tableName +
                "[method=" + methodName.orElseThrow() +
                ", ranges=" + ranges.size() +
                ", discreteValues=" + valueCount + "]";
    }

    private static Map<String, BlockRange> immutableRanges(Map<String, BlockRange> ranges)
    {
        requireNonNull(ranges, "ranges is null");
        Map<String, BlockRange> copy = new LinkedHashMap<>();
        ranges.forEach((column, range) -> copy.put(
                identifier(column, "range column"),
                requireNonNull(range, "range is null")));
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, List<String>> immutableDiscreteValues(Map<String, List<String>> discreteValues)
    {
        requireNonNull(discreteValues, "discreteValues is null");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        int valueCount = 0;
        for (Map.Entry<String, List<String>> entry : discreteValues.entrySet()) {
            String column = identifier(entry.getKey(), "discrete-value column");
            List<String> values = List.copyOf(requireNonNull(entry.getValue(), "discrete values are null"));
            for (String value : values) {
                if (requireNonNull(value, "discrete value is null").length() > 4_096) {
                    throw new IllegalArgumentException("discrete value exceeds maximum length of 4096");
                }
            }
            valueCount = Math.addExact(valueCount, values.size());
            if (valueCount > MAXIMUM_DISCRETE_VALUES) {
                throw new IllegalArgumentException("table discrete values exceed maximum of " + MAXIMUM_DISCRETE_VALUES);
            }
            copy.put(column, values);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String identifier(String value, String field)
    {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lower-case SQL identifier");
        }
        return value;
    }

    private static String logicalName(String value)
    {
        if (value == null || !LOGICAL_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("methodName must be a lower-case logical name");
        }
        return value;
    }
}
