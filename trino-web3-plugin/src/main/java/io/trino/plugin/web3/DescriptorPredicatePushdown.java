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
package io.trino.plugin.web3;

import io.airlift.slice.Slice;
import io.trino.plugin.web3.chain.RemoteMethodDescriptor;
import io.trino.plugin.web3.core.BlockRange;
import io.trino.plugin.web3.core.Web3ColumnHandle;
import io.trino.plugin.web3.core.Web3TableHandle;
import io.trino.spi.StandardErrorCode;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.Range;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.VarcharType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.RequestKind.PREDICATE;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.RequestKind.SPLIT;
import static io.trino.spi.type.BigintType.BIGINT;
import static java.lang.Math.addExact;
import static java.lang.Math.subtractExact;
import static java.util.Objects.requireNonNull;

final class DescriptorPredicatePushdown
{
    private final int maximumDiscreteValuesPerQuery;

    public DescriptorPredicatePushdown(int maximumDiscreteValuesPerQuery)
    {
        if (maximumDiscreteValuesPerQuery < 1) {
            throw new IllegalArgumentException("maximumDiscreteValuesPerQuery must be positive");
        }
        this.maximumDiscreteValuesPerQuery = maximumDiscreteValuesPerQuery;
    }

    public Optional<Result> apply(
            ChainMetadataRegistry.ResolvedTable table,
            Web3TableHandle handle,
            TupleDomain<ColumnHandle> summary)
    {
        requireNonNull(table, "table is null");
        requireNonNull(handle, "handle is null");
        requireNonNull(summary, "summary is null");
        if (summary.isNone()) {
            return Optional.empty();
        }
        Map<ColumnHandle, Domain> domains = summary.getDomains().orElseGet(Map::of);
        Candidate best = null;
        for (RemoteMethodDescriptor method : table.descriptor().methods()) {
            if (handle.methodName().isPresent() && !handle.methodName().orElseThrow().equals(method.name())) {
                continue;
            }
            Optional<Candidate> candidate = candidate(table, handle, method, domains);
            if (candidate.isPresent() && (best == null || candidate.orElseThrow().isBetterThan(best))) {
                best = candidate.orElseThrow();
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        Web3TableHandle pushedHandle = handle.withPredicates(best.methodName(), best.ranges(), best.discreteValues());
        if (pushedHandle.equals(handle)) {
            return Optional.empty();
        }
        java.util.Set<ColumnHandle> enforcedRanges = best.ranges().keySet().stream()
                .map(table::column)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return Optional.of(new Result(
                pushedHandle,
                summary.filter((column, domain) -> !enforcedRanges.contains(column))));
    }

    private Optional<Candidate> candidate(
            ChainMetadataRegistry.ResolvedTable table,
            Web3TableHandle handle,
            RemoteMethodDescriptor method,
            Map<ColumnHandle, Domain> domains)
    {
        Map<String, BlockRange> ranges = new LinkedHashMap<>(handle.ranges());
        Map<String, List<String>> discreteValues = new LinkedHashMap<>(handle.discreteValues());
        List<RemoteMethodDescriptor.RequestBinding> inputBindings = method.bindings().stream()
                .filter(binding -> binding.kind() == SPLIT || binding.kind() == PREDICATE)
                .toList();
        if (inputBindings.isEmpty()) {
            return Optional.empty();
        }

        for (RemoteMethodDescriptor.RequestBinding binding : inputBindings) {
            Web3ColumnHandle column = table.column(binding.value());
            Domain domain = domains.get(column);
            boolean satisfied;
            if (binding.kind() == SPLIT) {
                Optional<BlockRange> extracted = extractRange(table, column, domain);
                if (ranges.containsKey(binding.value()) && extracted.isPresent()) {
                    extracted = intersect(ranges.get(binding.value()), extracted.orElseThrow());
                    if (extracted.isEmpty()) {
                        return Optional.empty();
                    }
                }
                extracted.ifPresent(range -> ranges.put(binding.value(), range));
                satisfied = ranges.containsKey(binding.value());
            }
            else {
                if (!discreteValues.containsKey(binding.value())) {
                    extractDiscreteValues(table, column, domain)
                            .ifPresent(values -> discreteValues.put(binding.value(), values));
                }
                satisfied = discreteValues.containsKey(binding.value());
            }
            if (binding.required() && !satisfied) {
                return Optional.empty();
            }
        }

        int constraintCount = ranges.size() + discreteValues.size();
        if (constraintCount == 0) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(
                method.name(),
                Map.copyOf(ranges),
                immutableDiscreteValues(discreteValues),
                constraintCount,
                discreteValues.size()));
    }

    private static Optional<BlockRange> extractRange(
            ChainMetadataRegistry.ResolvedTable table,
            Web3ColumnHandle column,
            Domain domain)
    {
        if (!table.columnMetadata(column).getType().equals(BIGINT) ||
                domain == null || domain.isNullAllowed() || domain.getValues().isNone() || domain.getValues().isAll()) {
            return Optional.empty();
        }
        List<Range> ranges = domain.getValues().getRanges().getOrderedRanges();
        if (ranges.size() != 1) {
            return Optional.empty();
        }
        Range range = ranges.getFirst();
        if (range.isLowUnbounded() || range.isHighUnbounded()) {
            return Optional.empty();
        }
        try {
            long start = (long) range.getLowBoundedValue();
            long end = (long) range.getHighBoundedValue();
            if (!range.isLowInclusive()) {
                start = addExact(start, 1);
            }
            if (!range.isHighInclusive()) {
                end = subtractExact(end, 1);
            }
            if (start < 0 || end < start) {
                return Optional.empty();
            }
            return Optional.of(new BlockRange(start, end));
        }
        catch (ArithmeticException e) {
            return Optional.empty();
        }
    }

    private Optional<List<String>> extractDiscreteValues(
            ChainMetadataRegistry.ResolvedTable table,
            Web3ColumnHandle column,
            Domain domain)
    {
        if (!(table.columnMetadata(column).getType() instanceof VarcharType) ||
                domain == null || domain.isNullAllowed() || !domain.getValues().isDiscreteSet()) {
            return Optional.empty();
        }
        TreeSet<String> values = new TreeSet<>();
        for (Object value : domain.getValues().getDiscreteSet()) {
            if (!(value instanceof Slice slice)) {
                return Optional.empty();
            }
            values.add(slice.toStringUtf8());
            if (values.size() > maximumDiscreteValuesPerQuery) {
                throw new TrinoException(
                        StandardErrorCode.NOT_SUPPORTED,
                        column.name() + " predicate exceeds the configured query limit of " + maximumDiscreteValuesPerQuery +
                                " for " + table.metadata().getTable());
            }
        }
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(values));
    }

    private static Optional<BlockRange> intersect(BlockRange left, BlockRange right)
    {
        long start = Math.max(left.startInclusive(), right.startInclusive());
        long end = Math.min(left.endInclusive(), right.endInclusive());
        if (end < start) {
            return Optional.empty();
        }
        return Optional.of(new BlockRange(start, end));
    }

    private static Map<String, List<String>> immutableDiscreteValues(Map<String, List<String>> values)
    {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((column, columnValues) -> copy.put(column, List.copyOf(columnValues)));
        return Map.copyOf(copy);
    }

    public record Result(Web3TableHandle handle, TupleDomain<ColumnHandle> remainingFilter)
    {
        public Result
        {
            requireNonNull(handle, "handle is null");
            requireNonNull(remainingFilter, "remainingFilter is null");
        }
    }

    private record Candidate(
            String methodName,
            Map<String, BlockRange> ranges,
            Map<String, List<String>> discreteValues,
            int constraintCount,
            int discretePredicateCount)
    {
        private Candidate
        {
            requireNonNull(methodName, "methodName is null");
            ranges = Map.copyOf(requireNonNull(ranges, "ranges is null"));
            discreteValues = immutableDiscreteValues(discreteValues);
        }

        public boolean isBetterThan(Candidate other)
        {
            if (constraintCount != other.constraintCount) {
                return constraintCount > other.constraintCount;
            }
            return discretePredicateCount > other.discretePredicateCount;
        }
    }
}
