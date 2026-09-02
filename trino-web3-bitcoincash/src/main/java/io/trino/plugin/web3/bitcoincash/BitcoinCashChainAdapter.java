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
package io.trino.plugin.web3.bitcoincash;

import io.trino.plugin.web3.chain.ChainDescriptor;
import io.trino.plugin.web3.chain.ChainDescriptorCodec;
import io.trino.plugin.web3.utxo.UtxoChainAdapter;

import java.io.InputStream;

public final class BitcoinCashChainAdapter
        extends UtxoChainAdapter
{
    private static final ChainDescriptor DESCRIPTOR = loadDescriptor();

    public BitcoinCashChainAdapter()
    {
        super(DESCRIPTOR, "bitcoincash", subversion -> subversion.startsWith("/Bitcoin Cash Node:") || subversion.startsWith("/Bitcoin ABC:"));
    }

    private static ChainDescriptor loadDescriptor()
    {
        try (InputStream input = BitcoinCashChainAdapter.class.getResourceAsStream("bitcoincash-chain.json")) {
            if (input == null) {
                throw new IllegalStateException("missing Bitcoin Cash chain descriptor");
            }
            return ChainDescriptorCodec.fromJson(input);
        }
        catch (java.io.IOException e) {
            throw new IllegalStateException("failed to load Bitcoin Cash chain descriptor", e);
        }
    }
}
