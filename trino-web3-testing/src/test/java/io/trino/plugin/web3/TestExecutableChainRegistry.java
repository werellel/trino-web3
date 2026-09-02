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

import io.trino.plugin.web3.adapter.ExecutableChainRegistry;
import io.trino.plugin.web3.aptos.AptosChainAdapter;
import io.trino.plugin.web3.bitcoin.BitcoinChainAdapter;
import io.trino.plugin.web3.bitcoincash.BitcoinCashChainAdapter;
import io.trino.plugin.web3.dogecoin.DogecoinChainAdapter;
import io.trino.plugin.web3.evm.EthereumChainAdapter;
import io.trino.plugin.web3.evm.ArbitrumChainAdapter;
import io.trino.plugin.web3.evm.AvalancheChainAdapter;
import io.trino.plugin.web3.evm.BaseChainAdapter;
import io.trino.plugin.web3.evm.BnbChainAdapter;
import io.trino.plugin.web3.evm.PolygonChainAdapter;
import io.trino.plugin.web3.evm.OptimismChainAdapter;
import io.trino.plugin.web3.evm.GnosisChainAdapter;
import io.trino.plugin.web3.evm.KaiaChainAdapter;
import io.trino.plugin.web3.evm.ArcChainAdapter;
import io.trino.plugin.web3.evm.StoryChainAdapter;
import io.trino.plugin.web3.evm.BobaChainAdapter;
import io.trino.plugin.web3.evm.CeloChainAdapter;
import io.trino.plugin.web3.evm.HyperEvmChainAdapter;
import io.trino.plugin.web3.evm.AbstractChainAdapter;
import io.trino.plugin.web3.evm.AnimeChainAdapter;
import io.trino.plugin.web3.evm.ApeChainChainAdapter;
import io.trino.plugin.web3.evm.DegenChainAdapter;
import io.trino.plugin.web3.evm.InkChainAdapter;
import io.trino.plugin.web3.evm.JovayChainAdapter;
import io.trino.plugin.web3.evm.CrossFiChainAdapter;
import io.trino.plugin.web3.evm.LineaChainAdapter;
import io.trino.plugin.web3.tron.TronChainAdapter;
import io.trino.plugin.web3.sui.SuiChainAdapter;
import io.trino.plugin.web3.cosmos.CosmosChainAdapter;
import io.trino.plugin.web3.cosmos.OsmosisChainAdapter;
import io.trino.plugin.web3.cosmos.InjectiveChainAdapter;
import io.trino.plugin.web3.litecoin.LitecoinChainAdapter;
import io.trino.plugin.web3.solana.SolanaChainAdapter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class TestExecutableChainRegistry
{
    @Test
    void testComposesExecutableJsonRpcAndRestAdaptersByNativeSchema()
    {
        ExecutableChainRegistry registry = ExecutableChainRegistry.of(
                new EthereumChainAdapter(),
                new BaseChainAdapter(),
                new OptimismChainAdapter(),
                new ArbitrumChainAdapter(),
                new BnbChainAdapter(),
                new PolygonChainAdapter(),
                new AvalancheChainAdapter(),
                new GnosisChainAdapter(),
                new KaiaChainAdapter(),
                new ArcChainAdapter(),
                new StoryChainAdapter(),
                new BobaChainAdapter(),
                new CeloChainAdapter(),
                new HyperEvmChainAdapter(),
                new AbstractChainAdapter(),
                new AnimeChainAdapter(),
                new ApeChainChainAdapter(),
                new DegenChainAdapter(),
                new InkChainAdapter(),
                new JovayChainAdapter(),
                new CrossFiChainAdapter(),
                new LineaChainAdapter(),
                new TronChainAdapter(),
                new SuiChainAdapter(),
                new CosmosChainAdapter(),
                new OsmosisChainAdapter(),
                new InjectiveChainAdapter(),
                new SolanaChainAdapter(),
                new AptosChainAdapter(),
                new BitcoinChainAdapter(),
                new LitecoinChainAdapter(),
                new DogecoinChainAdapter(),
                new BitcoinCashChainAdapter());

        assertThat(registry.descriptors().descriptors())
                .extracting(descriptor -> descriptor.schemaName())
                .containsExactly("abstract", "anime", "apechain", "aptos", "arbitrum", "arc", "avalanche", "base", "bitcoin", "bitcoincash", "bnb", "boba", "celo", "cosmos", "crossfi", "degen", "dogecoin", "ethereum", "gnosis", "hyperevm", "injective", "ink", "jovay", "kaia", "linea", "litecoin", "optimism", "osmosis", "polygon", "solana", "story", "sui", "tron");
        assertThat(registry.adapterForSchema("ethereum")).isInstanceOf(EthereumChainAdapter.class);
        assertThat(registry.adapterForSchema("base")).isInstanceOf(BaseChainAdapter.class);
        assertThat(registry.adapterForSchema("optimism")).isInstanceOf(OptimismChainAdapter.class);
        assertThat(registry.adapterForSchema("arbitrum")).isInstanceOf(ArbitrumChainAdapter.class);
        assertThat(registry.adapterForSchema("bnb")).isInstanceOf(BnbChainAdapter.class);
        assertThat(registry.adapterForSchema("polygon")).isInstanceOf(PolygonChainAdapter.class);
        assertThat(registry.adapterForSchema("avalanche")).isInstanceOf(AvalancheChainAdapter.class);
        assertThat(registry.adapterForSchema("tron")).isInstanceOf(TronChainAdapter.class);
        assertThat(registry.adapterForSchema("sui")).isInstanceOf(SuiChainAdapter.class);
        assertThat(registry.adapterForSchema("cosmos")).isInstanceOf(CosmosChainAdapter.class);
        assertThat(registry.adapterForSchema("osmosis")).isInstanceOf(OsmosisChainAdapter.class);
        assertThat(registry.adapterForSchema("injective")).isInstanceOf(InjectiveChainAdapter.class);
        assertThat(registry.adapterForSchema("solana")).isInstanceOf(SolanaChainAdapter.class);
        assertThat(registry.adapterForSchema("aptos")).isInstanceOf(AptosChainAdapter.class);
        assertThat(registry.adapterForSchema("bitcoin")).isInstanceOf(BitcoinChainAdapter.class);
        assertThat(registry.adapterForSchema("litecoin")).isInstanceOf(LitecoinChainAdapter.class);
        assertThat(registry.adapterForSchema("dogecoin")).isInstanceOf(DogecoinChainAdapter.class);
        assertThat(registry.adapterForSchema("bitcoincash")).isInstanceOf(BitcoinCashChainAdapter.class);
        assertThat(new Web3Metadata(10).listSchemaNames(null))
                .containsExactlyElementsOf(registry.descriptors().descriptors().stream()
                        .map(descriptor -> descriptor.schemaName())
                        .toList());
    }
}
