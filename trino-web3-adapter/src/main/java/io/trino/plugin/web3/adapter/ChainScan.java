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
package io.trino.plugin.web3.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** Immutable pushed predicates for one chain-native table scan. */
public record ChainScan(
        String tableName,
        Optional<String> methodName,
        Map<String, LongRange> ranges,
        Map<String, List<String>> discreteValues)
{
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final Pattern LOGICAL_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,127}");
    private static final int MAXIMUM_PREDICATES = 64;
    private static final int MAXIMUM_DISCRETE_VALUES = 100_000;

    public ChainScan(String tableName, Map<String, LongRange> ranges, Map<String, List<String>> discreteValues)
    {
        this(tableName, Optional.empty(), ranges, discreteValues);
    }

    public ChainScan
    {
        tableName = identifier(tableName, "tableName");
        methodName = requireNonNull(methodName, "methodName is null")
                .map(ChainScan::logicalName);
        ranges = immutableRanges(ranges);
        discreteValues = immutableDiscreteValues(discreteValues);
        if (ranges.size() + discreteValues.size() > MAXIMUM_PREDICATES) {
            throw new IllegalArgumentException("scan predicates exceed maximum of " + MAXIMUM_PREDICATES);
        }
    }

    private static Map<String, LongRange> immutableRanges(Map<String, LongRange> ranges)
    {
        requireNonNull(ranges, "ranges is null");
        Map<String, LongRange> copy = new LinkedHashMap<>();
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
                throw new IllegalArgumentException("scan discrete values exceed maximum of " + MAXIMUM_DISCRETE_VALUES);
            }
            copy.put(column, values);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String identifier(String value, String field)
    {
        if (value == null || !SQL_IDENTIFIER.matcher(value).matches()) {
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

    public record LongRange(long startInclusive, long endInclusive)
    {
        public LongRange
        {
            if (startInclusive < 0 || endInclusive < startInclusive) {
                throw new IllegalArgumentException("invalid non-negative long range");
            }
        }
    }
}
