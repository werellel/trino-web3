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

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** A complete versioned native model for one chain adapter. */
public record ChainDescriptor(
        String apiVersion,
        String name,
        String schemaName,
        int adapterVersion,
        List<ChainTableDescriptor> tables)
{
    public static final String SUPPORTED_API_VERSION = "web3.trino.io/v1alpha1";

    public ChainDescriptor
    {
        apiVersion = requireNonNull(apiVersion, "apiVersion is null");
        if (!apiVersion.equals(SUPPORTED_API_VERSION)) {
            throw new IllegalArgumentException("unsupported chain descriptor apiVersion " + apiVersion);
        }
        name = DescriptorValidation.logicalName(name, "chain name");
        schemaName = DescriptorValidation.sqlIdentifier(schemaName, "schema name");
        if (adapterVersion < 1) {
            throw new IllegalArgumentException("adapterVersion must be positive");
        }
        tables = List.copyOf(requireNonNull(tables, "tables is null"));
        if (tables.isEmpty() || tables.size() > 256) {
            throw new IllegalArgumentException("chain tables must contain between 1 and 256 entries");
        }
        Set<String> names = new HashSet<>();
        for (ChainTableDescriptor table : tables) {
            if (!names.add(table.name())) {
                throw new IllegalArgumentException("duplicate table " + table.name());
            }
        }
    }

    public Optional<ChainTableDescriptor> table(String name)
    {
        return tables.stream().filter(table -> table.name().equals(name)).findFirst();
    }

    @Override
    public String toString()
    {
        return "ChainDescriptor{name=" + name + ", schemaName=" + schemaName + ", adapterVersion=" + adapterVersion + ", tables=" + tables.size() + "}";
    }
}
