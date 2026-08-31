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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestChainDescriptorCompatibility
{
    @Test
    void testAllowsUnchangedAndExplicitlyVersionedChanges()
    {
        ChainDescriptor previous = TestingDescriptors.chain("solana", "solana");
        ChainTableDescriptor changedTable = withOptionalColumn(previous.tables().getFirst(), "provider_note");
        ChainDescriptor current = withVersions(previous, 2, changedTable);

        assertThatCode(() -> ChainDescriptorCompatibility.verifyEvolution(previous, previous))
                .doesNotThrowAnyException();
        assertThatCode(() -> ChainDescriptorCompatibility.verifyEvolution(previous, current))
                .doesNotThrowAnyException();
    }

    @Test
    void testRejectsRemovalOfExistingTable()
    {
        ChainDescriptor previous = TestingDescriptors.chain("solana", "solana");
        ChainDescriptor current = new ChainDescriptor(
                previous.apiVersion(),
                previous.name(),
                previous.schemaName(),
                2,
                List.of(new ChainTableDescriptor(
                        "other_items",
                        1,
                        previous.tables().getFirst().columns(),
                        previous.tables().getFirst().methods())));

        assertThatThrownBy(() -> ChainDescriptorCompatibility.verifyEvolution(previous, current))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("existing table cannot be removed from chain descriptor items");
    }

    @Test
    void testRejectsChangedChainIdentity()
    {
        ChainDescriptor previous = TestingDescriptors.chain("solana", "solana");
        ChainDescriptor current = TestingDescriptors.chain("solana-next", "solana");

        assertThatThrownBy(() -> ChainDescriptorCompatibility.verifyEvolution(previous, current))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("chain name and schema cannot change across adapter versions");
    }

    @Test
    void testRejectsChangedDescriptorWithoutAdapterVersionIncrement()
    {
        ChainDescriptor previous = TestingDescriptors.chain("solana", "solana");
        ChainDescriptor current = withVersions(previous, 1, withTableVersion(previous.tables().getFirst(), 2));

        assertThatThrownBy(() -> ChainDescriptorCompatibility.verifyEvolution(previous, current))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("changed chain descriptor must increment adapterVersion");
    }

    @Test
    void testRejectsChangedTableWithoutTableVersionIncrement()
    {
        ChainDescriptor previous = TestingDescriptors.chain("solana", "solana");
        ChainTableDescriptor table = previous.tables().getFirst();
        ChainTableDescriptor changedTable = new ChainTableDescriptor(
                table.name(),
                table.version(),
                List.of(new ChainColumnDescriptor("id", "bigint", false)),
                table.methods());
        ChainDescriptor current = withVersions(previous, 2, changedTable);

        assertThatThrownBy(() -> ChainDescriptorCompatibility.verifyEvolution(previous, current))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("changed table descriptor must increment version for items");
    }

    private static ChainDescriptor withVersions(ChainDescriptor descriptor, int adapterVersion, ChainTableDescriptor table)
    {
        return new ChainDescriptor(
                descriptor.apiVersion(),
                descriptor.name(),
                descriptor.schemaName(),
                adapterVersion,
                List.of(table));
    }

    private static ChainTableDescriptor withTableVersion(ChainTableDescriptor table, int version)
    {
        return new ChainTableDescriptor(table.name(), version, table.columns(), table.methods());
    }

    private static ChainTableDescriptor withOptionalColumn(ChainTableDescriptor table, String columnName)
    {
        List<ChainColumnDescriptor> columns = new java.util.ArrayList<>(table.columns());
        columns.add(new ChainColumnDescriptor(columnName, "varchar", true));
        RemoteMethodDescriptor method = table.methods().getFirst();
        List<RemoteMethodDescriptor.ResponseField> fields = new java.util.ArrayList<>(method.response().fields());
        fields.add(new RemoteMethodDescriptor.ResponseField(columnName, "/" + columnName, false));
        RemoteMethodDescriptor updatedMethod = new RemoteMethodDescriptor(
                method.name(),
                method.protocol(),
                method.action(),
                method.path(),
                method.bindings(),
                new RemoteMethodDescriptor.ResponseMapping(
                        method.response().cardinality(),
                        method.response().rowsPointer(),
                        fields));
        return new ChainTableDescriptor(table.name(), table.version() + 1, columns, List.of(updatedMethod));
    }
}
