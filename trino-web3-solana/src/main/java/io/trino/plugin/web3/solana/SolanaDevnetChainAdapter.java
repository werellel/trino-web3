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
package io.trino.plugin.web3.solana;

import io.trino.plugin.web3.adapter.ChainDataClient;
import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import io.trino.plugin.web3.adapter.EndpointIdentityProbe;
import io.trino.plugin.web3.adapter.ExecutableChainAdapter;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteOperation;
import io.trino.plugin.web3.chain.ChainDescriptor;
import io.trino.plugin.web3.chain.ChainDescriptorCodec;

import java.io.InputStream;
import java.util.List;

public final class SolanaDevnetChainAdapter implements ExecutableChainAdapter
{
    private static final String DEVNET_GENESIS_HASH = "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG";
    private final ChainDescriptor descriptor = loadDescriptor();
    private final SolanaChainAdapter delegate = new SolanaChainAdapter();

    @Override
    public ChainDescriptor descriptor() { return descriptor; }

    @Override
    public EndpointIdentityProbe endpointIdentityProbe()
    {
        return new EndpointIdentityProbe(new RemoteOperation("getGenesisHash", List.of()), SolanaDevnetChainAdapter::genesisHash);
    }

    @Override
    public List<ChainSplit> planSplits(ChainScan scan, ChainSplitLimits limits) { return delegate.planSplits(scan, limits); }

    @Override
    public ChainDataClient createDataClient(RemoteExecutionRuntime runtime) { return delegate.createDataClient(runtime); }

    private static String genesisHash(com.fasterxml.jackson.databind.JsonNode response)
    {
        if (!response.isTextual() || !response.textValue().equals(DEVNET_GENESIS_HASH)) {
            throw new IllegalArgumentException("invalid Solana devnet chain identity");
        }
        return DEVNET_GENESIS_HASH;
    }

    private static ChainDescriptor loadDescriptor()
    {
        try (InputStream input = SolanaDevnetChainAdapter.class.getResourceAsStream("solana-chain.json")) {
            ChainDescriptor base = ChainDescriptorCodec.fromJson(input);
            return new ChainDescriptor(base.apiVersion(), "solana_devnet", "solana_devnet", base.adapterVersion(), base.tables());
        }
        catch (Exception e) { throw new IllegalStateException("failed to load Solana devnet descriptor", e); }
    }
}
