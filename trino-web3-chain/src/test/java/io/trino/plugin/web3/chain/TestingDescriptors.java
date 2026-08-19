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

import java.util.List;

import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.Cardinality.SINGLE;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.Protocol.JSON_RPC;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.RequestKind.SPLIT;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.RequestLocation.PARAMETER;

final class TestingDescriptors
{
    private TestingDescriptors() {}

    public static ChainDescriptor chain(String name, String schemaName)
    {
        RemoteMethodDescriptor method = new RemoteMethodDescriptor(
                "by-id",
                JSON_RPC,
                "chain_getItem",
                "",
                List.of(new RemoteMethodDescriptor.RequestBinding("0", SPLIT, "id", PARAMETER, true)),
                new RemoteMethodDescriptor.ResponseMapping(
                        SINGLE,
                        "",
                        List.of(new RemoteMethodDescriptor.ResponseField("id", "/id", true))));
        return new ChainDescriptor(
                ChainDescriptor.SUPPORTED_API_VERSION,
                name,
                schemaName,
                1,
                List.of(new ChainTableDescriptor(
                        "items",
                        1,
                        List.of(new ChainColumnDescriptor("id", "varchar", false)),
                        List.of(method))));
    }
}
