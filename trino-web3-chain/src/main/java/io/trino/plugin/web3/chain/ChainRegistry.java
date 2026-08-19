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
package io.trino.plugin.web3.chain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Immutable connector-lifetime ownership of chain names and schemas. */
public final class ChainRegistry
{
    private final List<ChainAdapter> adapters;
    private final List<ChainDescriptor> descriptors;
    private final Map<String, ChainAdapter> adaptersBySchema;

    public ChainRegistry(Collection<? extends ChainAdapter> adapters)
    {
        requireNonNull(adapters, "adapters is null");
        List<RegisteredAdapter> sorted = adapters.stream()
                .map(adapter -> new RegisteredAdapter(
                        requireNonNull(adapter, "adapter is null"),
                        requireNonNull(adapter.descriptor(), "adapter descriptor is null")))
                .sorted(Comparator.comparing(entry -> entry.descriptor().schemaName()))
                .toList();
        Map<String, ChainAdapter> byName = new LinkedHashMap<>();
        Map<String, ChainAdapter> bySchema = new LinkedHashMap<>();
        for (RegisteredAdapter entry : sorted) {
            if (byName.put(entry.descriptor().name(), entry.adapter()) != null) {
                throw new IllegalArgumentException("duplicate chain adapter name " + entry.descriptor().name());
            }
            if (bySchema.put(entry.descriptor().schemaName(), entry.adapter()) != null) {
                throw new IllegalArgumentException("duplicate chain adapter schema " + entry.descriptor().schemaName());
            }
        }
        this.adapters = sorted.stream().map(RegisteredAdapter::adapter).toList();
        descriptors = sorted.stream().map(RegisteredAdapter::descriptor).toList();
        adaptersBySchema = Map.copyOf(bySchema);
    }

    public static ChainRegistry of(ChainAdapter... adapters)
    {
        return new ChainRegistry(List.of(requireNonNull(adapters, "adapters is null")));
    }

    public List<ChainAdapter> adapters()
    {
        return adapters;
    }

    public List<ChainDescriptor> descriptors()
    {
        return descriptors;
    }

    public Optional<ChainAdapter> adapterForSchema(String schemaName)
    {
        return Optional.ofNullable(adaptersBySchema.get(requireNonNull(schemaName, "schemaName is null")));
    }

    public ChainRegistry withAdapter(ChainAdapter adapter)
    {
        List<ChainAdapter> updated = new ArrayList<>(adapters);
        updated.add(requireNonNull(adapter, "adapter is null"));
        return new ChainRegistry(updated);
    }

    public ChainRegistry withoutSchema(String schemaName)
    {
        ChainAdapter removed = adaptersBySchema.get(requireNonNull(schemaName, "schemaName is null"));
        if (removed == null) {
            return this;
        }
        return new ChainRegistry(adapters.stream()
                .filter(adapter -> adapter != removed)
                .toList());
    }

    private record RegisteredAdapter(ChainAdapter adapter, ChainDescriptor descriptor) {}
}
