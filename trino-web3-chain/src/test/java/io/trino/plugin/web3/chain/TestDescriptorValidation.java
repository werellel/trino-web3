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

import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.Cardinality.SINGLE;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.Protocol.JSON_RPC;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.Protocol.REST;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.RequestKind.LITERAL;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.RequestKind.SPLIT;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.RequestLocation.PARAMETER;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.RequestLocation.PATH;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestDescriptorValidation
{
    @Test
    void testRejectsDuplicateTablesColumnsAndMethods()
    {
        ChainDescriptor descriptor = TestingDescriptors.chain("near", "near");
        ChainTableDescriptor table = descriptor.tables().getFirst();

        assertThatThrownBy(() -> new ChainDescriptor(
                descriptor.apiVersion(), descriptor.name(), descriptor.schemaName(), 1, List.of(table, table)))
                .hasMessage("duplicate table items");
        assertThatThrownBy(() -> new ChainTableDescriptor(
                table.name(), 1, List.of(table.columns().getFirst(), table.columns().getFirst()), table.methods()))
                .hasMessage("duplicate column id");
        assertThatThrownBy(() -> new ChainTableDescriptor(
                table.name(), 1, table.columns(), List.of(table.methods().getFirst(), table.methods().getFirst())))
                .hasMessage("duplicate method by-id");
    }

    @Test
    void testRejectsDuplicateBindingsAndResponseMappings()
    {
        RemoteMethodDescriptor.RequestBinding binding = new RemoteMethodDescriptor.RequestBinding("0", SPLIT, "id", PARAMETER, true);
        RemoteMethodDescriptor.ResponseField field = new RemoteMethodDescriptor.ResponseField("id", "/id", true);

        assertThatThrownBy(() -> new RemoteMethodDescriptor(
                "by-id", JSON_RPC, "get", "", List.of(binding, binding), response(field)))
                .hasMessage("duplicate request binding target 0");
        assertThatThrownBy(() -> response(field, field))
                .hasMessage("duplicate response field for column id");
    }

    @Test
    void testRejectsProtocolSpecificInvalidDefinitions()
    {
        RemoteMethodDescriptor.ResponseField field = new RemoteMethodDescriptor.ResponseField("id", "/id", true);

        assertThatThrownBy(() -> new RemoteMethodDescriptor(
                "rpc", JSON_RPC, "get", "/path", List.of(), response(field)))
                .hasMessage("JSON-RPC method path must be empty");
        assertThatThrownBy(() -> new RemoteMethodDescriptor(
                "rest", REST, "DELETE", "/items", List.of(), response(field)))
                .hasMessage("REST action must be GET or POST");
        assertThatThrownBy(() -> new RemoteMethodDescriptor(
                "rest", REST, "GET", "/items/{id}",
                List.of(new RemoteMethodDescriptor.RequestBinding("missing", SPLIT, "id", PATH, true)),
                response(field)))
                .hasMessage("REST path does not contain binding target missing");
    }

    @Test
    void testRejectsInvalidLiteralAndUnknownReferences()
    {
        assertThatThrownBy(() -> new RemoteMethodDescriptor.RequestBinding("0", LITERAL, "true false", PARAMETER, true))
                .hasMessage("request literal is not valid JSON");

        ChainTableDescriptor table = TestingDescriptors.chain("sui", "sui").tables().getFirst();
        RemoteMethodDescriptor invalid = new RemoteMethodDescriptor(
                "invalid",
                JSON_RPC,
                "get",
                "",
                List.of(new RemoteMethodDescriptor.RequestBinding("0", SPLIT, "missing", PARAMETER, true)),
                table.methods().getFirst().response());
        assertThatThrownBy(() -> new ChainTableDescriptor(table.name(), 1, table.columns(), List.of(invalid)))
                .hasMessage("method invalid binding references unknown column missing");
    }

    private static RemoteMethodDescriptor.ResponseMapping response(RemoteMethodDescriptor.ResponseField... fields)
    {
        return new RemoteMethodDescriptor.ResponseMapping(SINGLE, "", List.of(fields));
    }
}
