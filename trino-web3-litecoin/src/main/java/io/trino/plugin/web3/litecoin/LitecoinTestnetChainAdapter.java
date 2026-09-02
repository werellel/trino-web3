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
package io.trino.plugin.web3.litecoin;

import com.fasterxml.jackson.databind.JsonNode;
import io.trino.plugin.web3.adapter.ChainDataClient;
import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import io.trino.plugin.web3.adapter.EndpointIdentityProbe;
import io.trino.plugin.web3.adapter.ExecutableChainAdapter;
import io.trino.plugin.web3.chain.ChainDescriptorCodec;
import io.trino.plugin.web3.chain.ChainDescriptor;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RemoteOperation;
import io.trino.plugin.web3.utxo.UtxoChainDataClient;

import java.io.InputStream;
import java.util.List;

public final class LitecoinTestnetChainAdapter implements ExecutableChainAdapter
{
    private final ChainDescriptor descriptor = loadDescriptor();
    @Override public ChainDescriptor descriptor() { return descriptor; }
    @Override public EndpointIdentityProbe endpointIdentityProbe() { return new EndpointIdentityProbe(new RemoteOperation("getblockchaininfo", List.of()), LitecoinTestnetChainAdapter::identity); }
    @Override public List<ChainSplit> planSplits(ChainScan scan, ChainSplitLimits limits) { return new LitecoinChainAdapter().planSplits(scan, limits); }
    @Override public ChainDataClient createDataClient(RemoteExecutionRuntime runtime) { return new UtxoChainDataClient(runtime, "litecoin_testnet"); }
    private static String identity(JsonNode response)
    {
        if (!response.path("chain").asText().equals("test") || !response.path("subversion").asText().startsWith("/Litecoin Core:")) {
            throw new IllegalArgumentException("invalid Litecoin testnet node identity");
        }
        return "litecoin_testnet";
    }
    private static ChainDescriptor loadDescriptor() { try (InputStream input = LitecoinTestnetChainAdapter.class.getResourceAsStream("litecoin-chain.json")) { ChainDescriptor base = ChainDescriptorCodec.fromJson(input); return new ChainDescriptor(base.apiVersion(), "litecoin_testnet", "litecoin_testnet", base.adapterVersion(), base.tables()); } catch (Exception e) { throw new IllegalStateException("failed to load Litecoin testnet descriptor", e); } }
}
