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

import io.airlift.slice.Slices;
import io.trino.plugin.web3.chain.ChainColumnDescriptor;
import io.trino.plugin.web3.chain.ChainDescriptor;
import io.trino.plugin.web3.chain.ChainRegistry;
import io.trino.plugin.web3.chain.ChainTableDescriptor;
import io.trino.plugin.web3.chain.DeclarativeChainAdapter;
import io.trino.plugin.web3.chain.RemoteMethodDescriptor;
import io.trino.plugin.web3.core.Web3ColumnHandle;
import io.trino.plugin.web3.core.Web3TableHandle;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.Range;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.predicate.ValueSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.Cardinality.SINGLE;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.Protocol.REST;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.RequestKind.PREDICATE;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.RequestKind.SPLIT;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.RequestLocation.PATH;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.RequestLocation.QUERY;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestChainMetadataRegistry
{
    @Test
    void testMetadataIsBuiltFromRegisteredNativeChainDescriptors()
    {
        ChainRegistry registry = ChainRegistry.of(
                new DeclarativeChainAdapter(descriptor("aptos")),
                new DeclarativeChainAdapter(descriptor("solana")));
        Web3Metadata metadata = new Web3Metadata(10, registry, type -> {
            assertThat(type).isEqualTo("bigint");
            return BIGINT;
        });

        assertThat(metadata.listSchemaNames(null)).containsExactly("aptos", "solana");
        assertThat(metadata.listTables(null, Optional.empty())).containsExactly(
                new SchemaTableName("aptos", "transactions"),
                new SchemaTableName("solana", "transactions"));
        assertThat(metadata.listTables(null, Optional.of("solana")))
                .containsExactly(new SchemaTableName("solana", "transactions"));

        var table = metadata.getTableHandle(null, new SchemaTableName("solana", "transactions"), Optional.empty(), Optional.empty());
        assertThat(table).isNotNull();
        assertThat(metadata.getTableMetadata(null, table).getColumns())
                .extracting(column -> column.getName() + ":" + column.getType())
                .containsExactly("version:bigint");
        assertThat(metadata.getColumnHandles(null, table).get("version"))
                .isEqualTo(new Web3ColumnHandle("version", 0));
    }

    @Test
    void testPushesDescriptorSplitBindingWithNativeColumnName()
    {
        Web3Metadata metadata = metadata(descriptorWithBinding(
                "aptos",
                "ledger_version",
                "bigint",
                new RemoteMethodDescriptor.RequestBinding("ledger_version", SPLIT, "ledger_version", PATH, true),
                "/v1/transactions/{ledger_version}"));
        Web3TableHandle table = table(metadata, "aptos");
        Web3ColumnHandle column = new Web3ColumnHandle("ledger_version", 0);
        TupleDomain<io.trino.spi.connector.ColumnHandle> summary = TupleDomain.withColumnDomains(Map.of(
                column,
                Domain.create(ValueSet.ofRanges(Range.range(BIGINT, 10L, true, 12L, true)), false)));

        var result = metadata.applyFilter(null, table, new Constraint(summary)).orElseThrow();
        Web3TableHandle pushed = (Web3TableHandle) result.getHandle();

        assertThat(pushed.methodName()).contains("lookup");
        assertThat(pushed.ranges()).containsEntry("ledger_version", new io.trino.plugin.web3.core.BlockRange(10, 12));
        assertThat(pushed.discreteValues()).isEmpty();
        assertThat(result.getRemainingFilter()).isEqualTo(TupleDomain.all());
    }

    @Test
    void testPushesDescriptorPredicateBindingAndKeepsResidual()
    {
        Web3Metadata metadata = metadata(descriptorWithBinding(
                "solana",
                "signature",
                "varchar",
                new RemoteMethodDescriptor.RequestBinding("signature", PREDICATE, "signature", QUERY, true),
                "/v1/transactions"));
        Web3TableHandle table = table(metadata, "solana");
        Web3ColumnHandle column = new Web3ColumnHandle("signature", 0);
        TupleDomain<io.trino.spi.connector.ColumnHandle> summary = TupleDomain.withColumnDomains(Map.of(
                column,
                Domain.multipleValues(VARCHAR, List.of(Slices.utf8Slice("SignatureA"), Slices.utf8Slice("SignatureB")))));

        var result = metadata.applyFilter(null, table, new Constraint(summary)).orElseThrow();
        Web3TableHandle pushed = (Web3TableHandle) result.getHandle();

        assertThat(pushed.methodName()).contains("lookup");
        assertThat(pushed.ranges()).isEmpty();
        assertThat(pushed.discreteValues()).containsEntry("signature", List.of("SignatureA", "SignatureB"));
        assertThat(result.getRemainingFilter()).isEqualTo(summary);
    }

    @Test
    void testDoesNotSelectMethodWithoutRequiredBinding()
    {
        Web3Metadata metadata = metadata(descriptorWithBinding(
                "aptos",
                "ledger_version",
                "bigint",
                new RemoteMethodDescriptor.RequestBinding("ledger_version", SPLIT, "ledger_version", PATH, true),
                "/v1/transactions/{ledger_version}"));

        assertThat(metadata.applyFilter(null, table(metadata, "aptos"), new Constraint(TupleDomain.all())))
                .isEmpty();
    }

    @Test
    void testRejectsUnsupportedDescriptorTypeDuringMetadataConstructionWithoutLeakingValue()
    {
        String marker = "do-not-leak-response-payload";
        ChainDescriptor descriptor = descriptorWithBinding(
                "unsafe",
                "payload",
                marker,
                new RemoteMethodDescriptor.RequestBinding("payload", PREDICATE, "payload", QUERY, true),
                "/v1/items");

        assertThatThrownBy(() -> metadata(descriptor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid Trino type for descriptor column payload")
                .hasMessageNotContaining(marker);
    }

    private static Web3Metadata metadata(ChainDescriptor descriptor)
    {
        return new Web3Metadata(
                10,
                ChainRegistry.of(new DeclarativeChainAdapter(descriptor)),
                Web3Metadata::resolveBuiltInType);
    }

    private static Web3TableHandle table(Web3Metadata metadata, String schemaName)
    {
        return (Web3TableHandle) metadata.getTableHandle(
                null,
                new SchemaTableName(schemaName, "transactions"),
                Optional.empty(),
                Optional.empty());
    }

    private static ChainDescriptor descriptorWithBinding(
            String schemaName,
            String columnName,
            String type,
            RemoteMethodDescriptor.RequestBinding binding,
            String path)
    {
        RemoteMethodDescriptor method = new RemoteMethodDescriptor(
                "lookup",
                REST,
                "GET",
                path,
                List.of(binding),
                new RemoteMethodDescriptor.ResponseMapping(
                        SINGLE,
                        "",
                        List.of(new RemoteMethodDescriptor.ResponseField(columnName, "/" + columnName, true))));
        return new ChainDescriptor(
                ChainDescriptor.SUPPORTED_API_VERSION,
                schemaName,
                schemaName,
                1,
                List.of(new ChainTableDescriptor(
                        "transactions",
                        1,
                        List.of(new ChainColumnDescriptor(columnName, type, false)),
                        List.of(method))));
    }

    private static ChainDescriptor descriptor(String name)
    {
        RemoteMethodDescriptor method = new RemoteMethodDescriptor(
                "latest",
                REST,
                "GET",
                "/v1/transactions",
                List.of(),
                new RemoteMethodDescriptor.ResponseMapping(
                        SINGLE,
                        "",
                        List.of(new RemoteMethodDescriptor.ResponseField("version", "/version", true))));
        return new ChainDescriptor(
                ChainDescriptor.SUPPORTED_API_VERSION,
                name,
                name,
                1,
                List.of(new ChainTableDescriptor(
                        "transactions",
                        1,
                        List.of(new ChainColumnDescriptor("version", "bigint", false)),
                        List.of(method))));
    }
}
