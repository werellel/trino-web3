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
package io.trino.plugin.web3.bitcoin;

import com.fasterxml.jackson.databind.JsonNode;
import io.trino.plugin.web3.adapter.ChainDataClient;
import io.trino.plugin.web3.chain.ChainDescriptor;
import io.trino.plugin.web3.chain.ChainDescriptorCodec;
import io.trino.plugin.web3.utxo.UtxoChainAdapter;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;

import java.io.InputStream;

public final class BitcoinChainAdapter
        extends UtxoChainAdapter
{
    private static final String DESCRIPTOR_RESOURCE = "bitcoin-chain.json";
    private static final ChainDescriptor DESCRIPTOR = loadDescriptor();

    public BitcoinChainAdapter()
    {
        super(DESCRIPTOR, "bitcoin", subversion -> subversion.startsWith("/Satoshi:"));
    }

    @Override
    public ChainDataClient createDataClient(RemoteExecutionRuntime runtime)
    {
        return new BitcoinChainDataClient(runtime);
    }

    static String chainName(JsonNode response)
    {
        JsonNode subversion = response.get("subversion");
        if (subversion == null || !subversion.isTextual() || !subversion.textValue().startsWith("/Satoshi:")) {
            throw new IllegalArgumentException("invalid Bitcoin node identity");
        }
        return "bitcoin";
    }

    private static ChainDescriptor loadDescriptor()
    {
        try (InputStream input = BitcoinChainAdapter.class.getResourceAsStream(DESCRIPTOR_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing Bitcoin chain descriptor");
            }
            return ChainDescriptorCodec.fromJson(input);
        }
        catch (java.io.IOException e) {
            throw new IllegalStateException("failed to load Bitcoin chain descriptor", e);
        }
    }
}
