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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** One native table and the remote methods that can produce its rows. */
public record ChainTableDescriptor(
        String name,
        int version,
        List<ChainColumnDescriptor> columns,
        List<RemoteMethodDescriptor> methods)
{
    public ChainTableDescriptor
    {
        name = DescriptorValidation.sqlIdentifier(name, "table name");
        if (version < 1) {
            throw new IllegalArgumentException("table version must be positive");
        }
        columns = List.copyOf(requireNonNull(columns, "columns is null"));
        methods = List.copyOf(requireNonNull(methods, "methods is null"));
        if (columns.isEmpty() || columns.size() > 512) {
            throw new IllegalArgumentException("table columns must contain between 1 and 512 entries");
        }
        if (methods.isEmpty() || methods.size() > 128) {
            throw new IllegalArgumentException("table methods must contain between 1 and 128 entries");
        }

        Map<String, ChainColumnDescriptor> columnsByName = new LinkedHashMap<>();
        for (ChainColumnDescriptor column : columns) {
            if (columnsByName.put(column.name(), column) != null) {
                throw new IllegalArgumentException("duplicate column " + column.name());
            }
        }
        Set<String> methodNames = new HashSet<>();
        for (RemoteMethodDescriptor method : methods) {
            if (!methodNames.add(method.name())) {
                throw new IllegalArgumentException("duplicate method " + method.name());
            }
            Set<String> mappedColumns = new HashSet<>();
            for (RemoteMethodDescriptor.ResponseField field : method.response().fields()) {
                ChainColumnDescriptor column = columnsByName.get(field.column());
                if (column == null) {
                    throw new IllegalArgumentException("method " + method.name() + " maps unknown column " + field.column());
                }
                if (!field.required() && !column.nullable()) {
                    throw new IllegalArgumentException("optional response field requires nullable column " + field.column());
                }
                mappedColumns.add(field.column());
            }
            if (!mappedColumns.equals(columnsByName.keySet())) {
                throw new IllegalArgumentException("method " + method.name() + " must map every table column");
            }
            method.bindings().stream()
                    .filter(binding -> binding.kind() != RemoteMethodDescriptor.RequestKind.LITERAL)
                    .filter(binding -> !columnsByName.containsKey(binding.value()))
                    .findFirst()
                    .ifPresent(binding -> {
                        throw new IllegalArgumentException("method " + method.name() + " binding references unknown column " + binding.value());
                    });
        }
    }

    public Optional<ChainColumnDescriptor> column(String name)
    {
        return columns.stream().filter(column -> column.name().equals(name)).findFirst();
    }

    public Optional<RemoteMethodDescriptor> method(String name)
    {
        return methods.stream().filter(method -> method.name().equals(name)).findFirst();
    }
}
