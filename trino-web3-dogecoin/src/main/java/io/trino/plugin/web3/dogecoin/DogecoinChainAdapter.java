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
package io.trino.plugin.web3.dogecoin;

import io.trino.plugin.web3.chain.ChainDescriptor;
import io.trino.plugin.web3.chain.ChainDescriptorCodec;
import io.trino.plugin.web3.utxo.UtxoChainAdapter;

import java.io.InputStream;

public final class DogecoinChainAdapter
        extends UtxoChainAdapter
{
    private static final ChainDescriptor DESCRIPTOR = loadDescriptor();

    public DogecoinChainAdapter()
    {
        super(DESCRIPTOR, "dogecoin", subversion -> subversion.startsWith("/Dogecoin Core:"));
    }

    private static ChainDescriptor loadDescriptor()
    {
        try (InputStream input = DogecoinChainAdapter.class.getResourceAsStream("dogecoin-chain.json")) {
            if (input == null) {
                throw new IllegalStateException("missing Dogecoin chain descriptor");
            }
            return ChainDescriptorCodec.fromJson(input);
        }
        catch (java.io.IOException e) {
            throw new IllegalStateException("failed to load Dogecoin chain descriptor", e);
        }
    }
}
