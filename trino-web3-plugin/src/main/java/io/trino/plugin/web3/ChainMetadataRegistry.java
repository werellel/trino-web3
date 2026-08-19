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

import io.trino.plugin.web3.chain.ChainColumnDescriptor;
import io.trino.plugin.web3.chain.ChainRegistry;
import io.trino.plugin.web3.chain.ChainTableDescriptor;
import io.trino.plugin.web3.core.Web3ColumnHandle;
import io.trino.plugin.web3.core.Web3TableHandle;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.type.Type;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

final class ChainMetadataRegistry
{
    private final ChainRegistry chainRegistry;
    private final Map<SchemaTableName, ResolvedTable> tables;

    public ChainMetadataRegistry(ChainRegistry chainRegistry, Function<String, Type> typeResolver)
    {
        this.chainRegistry = requireNonNull(chainRegistry, "chainRegistry is null");
        requireNonNull(typeResolver, "typeResolver is null");
        Map<SchemaTableName, ResolvedTable> resolvedTables = new LinkedHashMap<>();
        chainRegistry.descriptors().forEach(descriptor -> {
            String schemaName = descriptor.schemaName();
            descriptor.tables().forEach(table -> {
                SchemaTableName tableName = new SchemaTableName(schemaName, table.name());
                ResolvedTable previous = resolvedTables.put(tableName, resolve(tableName, table, typeResolver));
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate chain table " + tableName);
                }
            });
        });
        tables = Collections.unmodifiableMap(resolvedTables);
    }

    public List<String> schemas()
    {
        return chainRegistry.descriptors().stream()
                .map(descriptor -> descriptor.schemaName())
                .toList();
    }

    public List<SchemaTableName> listTables(Optional<String> schemaName)
    {
        requireNonNull(schemaName, "schemaName is null");
        return tables.keySet().stream()
                .filter(table -> schemaName.isEmpty() || table.getSchemaName().equals(schemaName.orElseThrow()))
                .toList();
    }

    public Optional<ResolvedTable> table(SchemaTableName tableName)
    {
        return Optional.ofNullable(tables.get(requireNonNull(tableName, "tableName is null")));
    }

    public Optional<ResolvedTable> table(Web3TableHandle table)
    {
        requireNonNull(table, "table is null");
        return table(new SchemaTableName(table.schemaName(), table.tableName()));
    }

    private static ResolvedTable resolve(
            SchemaTableName tableName,
            ChainTableDescriptor descriptor,
            Function<String, Type> typeResolver)
    {
        List<ColumnMetadata> columns = descriptor.columns().stream()
                .map(column -> new ColumnMetadata(column.name(), resolveType(column, typeResolver)))
                .toList();
        Map<String, ColumnHandle> handles = new LinkedHashMap<>();
        for (int ordinal = 0; ordinal < descriptor.columns().size(); ordinal++) {
            ChainColumnDescriptor column = descriptor.columns().get(ordinal);
            handles.put(column.name(), new Web3ColumnHandle(column.name(), ordinal));
        }
        return new ResolvedTable(
                descriptor,
                new ConnectorTableMetadata(tableName, columns),
                Collections.unmodifiableMap(handles));
    }

    private static Type resolveType(ChainColumnDescriptor column, Function<String, Type> typeResolver)
    {
        try {
            return requireNonNull(typeResolver.apply(column.type()), "type resolver returned null");
        }
        catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid Trino type for descriptor column " + column.name() + ": " + column.type(), e);
        }
    }

    public record ResolvedTable(
            ChainTableDescriptor descriptor,
            ConnectorTableMetadata metadata,
            Map<String, ColumnHandle> columnHandles)
    {
        public ResolvedTable
        {
            requireNonNull(descriptor, "descriptor is null");
            requireNonNull(metadata, "metadata is null");
            columnHandles = Map.copyOf(requireNonNull(columnHandles, "columnHandles is null"));
        }

        public Web3ColumnHandle column(String name)
        {
            ColumnHandle handle = columnHandles.get(requireNonNull(name, "name is null"));
            if (!(handle instanceof Web3ColumnHandle web3Column)) {
                throw new IllegalArgumentException("unknown column " + name + " for table " + metadata.getTable());
            }
            return web3Column;
        }

        public ColumnMetadata columnMetadata(Web3ColumnHandle column)
        {
            requireNonNull(column, "column is null");
            if (column.ordinal() < 0 || column.ordinal() >= metadata.getColumns().size()) {
                throw new IllegalArgumentException("invalid column ordinal for table " + metadata.getTable());
            }
            ColumnMetadata columnMetadata = metadata.getColumns().get(column.ordinal());
            if (!columnMetadata.getName().equals(column.name())) {
                throw new IllegalArgumentException("column handle does not belong to table " + metadata.getTable());
            }
            return columnMetadata;
        }
    }
}
