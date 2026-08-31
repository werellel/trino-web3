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

import java.util.Map;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

/** Validates explicit version increments between two releases of one adapter. */
public final class ChainDescriptorCompatibility
{
    private ChainDescriptorCompatibility() {}

    public static void verifyEvolution(ChainDescriptor previous, ChainDescriptor current)
    {
        requireNonNull(previous, "previous is null");
        requireNonNull(current, "current is null");
        if (!previous.name().equals(current.name()) || !previous.schemaName().equals(current.schemaName())) {
            throw new IllegalArgumentException("chain name and schema cannot change across adapter versions");
        }
        if (current.adapterVersion() < previous.adapterVersion()) {
            throw new IllegalArgumentException("adapterVersion cannot decrease");
        }
        if (!current.equals(previous) && current.adapterVersion() == previous.adapterVersion()) {
            throw new IllegalArgumentException("changed chain descriptor must increment adapterVersion");
        }

        Map<String, ChainTableDescriptor> previousTables = previous.tables().stream()
                .collect(toMap(ChainTableDescriptor::name, Function.identity()));
        Map<String, ChainTableDescriptor> currentTables = current.tables().stream()
                .collect(toMap(ChainTableDescriptor::name, Function.identity()));
        for (String tableName : previousTables.keySet()) {
            if (!currentTables.containsKey(tableName)) {
                throw new IllegalArgumentException("existing table cannot be removed from chain descriptor " + tableName);
            }
        }
        for (ChainTableDescriptor currentTable : current.tables()) {
            ChainTableDescriptor previousTable = previousTables.get(currentTable.name());
            if (previousTable == null) {
                continue;
            }
            if (currentTable.version() < previousTable.version()) {
                throw new IllegalArgumentException("table version cannot decrease for " + currentTable.name());
            }
            if (!currentTable.equals(previousTable) && currentTable.version() == previousTable.version()) {
                throw new IllegalArgumentException("changed table descriptor must increment version for " + currentTable.name());
            }
        }
    }
}
