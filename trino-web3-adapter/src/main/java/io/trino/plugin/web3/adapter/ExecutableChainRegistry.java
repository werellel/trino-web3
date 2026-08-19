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

import io.trino.plugin.web3.chain.ChainRegistry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Immutable registry containing only adapters with bounded execution paths. */
public final class ExecutableChainRegistry
{
    private final ChainRegistry descriptors;
    private final List<ExecutableChainAdapter> adapters;
    private final Map<String, ExecutableChainAdapter> adaptersBySchema;

    public ExecutableChainRegistry(Collection<? extends ExecutableChainAdapter> adapters)
    {
        requireNonNull(adapters, "adapters is null");
        descriptors = new ChainRegistry(adapters);
        Map<String, ExecutableChainAdapter> bySchema = new LinkedHashMap<>();
        adapters.forEach(adapter -> {
            ExecutableChainAdapter value = requireNonNull(adapter, "adapter is null");
            bySchema.put(value.descriptor().schemaName(), value);
        });
        adaptersBySchema = Map.copyOf(bySchema);
        this.adapters = descriptors.adapters().stream()
                .map(ExecutableChainAdapter.class::cast)
                .toList();
    }

    public static ExecutableChainRegistry of(ExecutableChainAdapter... adapters)
    {
        return new ExecutableChainRegistry(java.util.List.of(requireNonNull(adapters, "adapters is null")));
    }

    public ChainRegistry descriptors()
    {
        return descriptors;
    }

    public List<ExecutableChainAdapter> adapters()
    {
        return adapters;
    }

    public ExecutableChainAdapter adapterForSchema(String schemaName)
    {
        ExecutableChainAdapter adapter = adaptersBySchema.get(requireNonNull(schemaName, "schemaName is null"));
        if (adapter == null) {
            throw new IllegalArgumentException("no executable chain adapter for schema " + schemaName);
        }
        return adapter;
    }
}
