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
import io.trino.plugin.web3.aptos.AptosTestnetChainAdapter;
import io.trino.plugin.web3.bitcoin.BitcoinChainAdapter;
import io.trino.plugin.web3.bitcoin.BitcoinTestnetChainAdapter;
import io.trino.plugin.web3.bitcoincash.BitcoinCashChainAdapter;
import io.trino.plugin.web3.bitcoincash.BitcoinCashTestnetChainAdapter;
import io.trino.plugin.web3.dogecoin.DogecoinChainAdapter;
import io.trino.plugin.web3.dogecoin.DogecoinTestnetChainAdapter;
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
import io.trino.plugin.web3.evm.AbstractSepoliaChainAdapter;
import io.trino.plugin.web3.evm.AnimeTestnetChainAdapter;
import io.trino.plugin.web3.evm.ApeChainCurtisChainAdapter;
import io.trino.plugin.web3.evm.ArbitrumSepoliaChainAdapter;
import io.trino.plugin.web3.evm.ArcTestnetChainAdapter;
import io.trino.plugin.web3.evm.AvalancheFujiChainAdapter;
import io.trino.plugin.web3.evm.BaseSepoliaChainAdapter;
import io.trino.plugin.web3.evm.BnbTestnetChainAdapter;
import io.trino.plugin.web3.evm.BobaSepoliaChainAdapter;
import io.trino.plugin.web3.evm.CeloSepoliaChainAdapter;
import io.trino.plugin.web3.evm.CrossFiTestnetChainAdapter;
import io.trino.plugin.web3.evm.EthereumSepoliaChainAdapter;
import io.trino.plugin.web3.evm.GnosisChiadoChainAdapter;
import io.trino.plugin.web3.evm.HyperEvmTestnetChainAdapter;
import io.trino.plugin.web3.evm.InkSepoliaChainAdapter;
import io.trino.plugin.web3.evm.JovaySepoliaChainAdapter;
import io.trino.plugin.web3.evm.KaiaKairosChainAdapter;
import io.trino.plugin.web3.evm.LineaSepoliaChainAdapter;
import io.trino.plugin.web3.evm.OptimismSepoliaChainAdapter;
import io.trino.plugin.web3.evm.PolygonAmoyChainAdapter;
import io.trino.plugin.web3.evm.StoryAeneidChainAdapter;
import io.trino.plugin.web3.tron.TronChainAdapter;
import io.trino.plugin.web3.tron.TronNileChainAdapter;
import io.trino.plugin.web3.tron.TronShastaChainAdapter;
import io.trino.plugin.web3.sui.SuiChainAdapter;
import io.trino.plugin.web3.sui.SuiTestnetChainAdapter;
import io.trino.plugin.web3.cosmos.CosmosChainAdapter;
import io.trino.plugin.web3.cosmos.OsmosisChainAdapter;
import io.trino.plugin.web3.cosmos.InjectiveChainAdapter;
import io.trino.plugin.web3.cosmos.CosmosTestnetChainAdapter;
import io.trino.plugin.web3.cosmos.OsmosisTestnetChainAdapter;
import io.trino.plugin.web3.cosmos.InjectiveTestnetChainAdapter;
import io.trino.plugin.web3.litecoin.LitecoinChainAdapter;
import io.trino.plugin.web3.litecoin.LitecoinTestnetChainAdapter;
import io.trino.plugin.web3.solana.SolanaChainAdapter;
import io.trino.plugin.web3.solana.SolanaDevnetChainAdapter;
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
                new EthereumSepoliaChainAdapter(),
                new BaseSepoliaChainAdapter(),
                new OptimismSepoliaChainAdapter(),
                new ArbitrumSepoliaChainAdapter(),
                new BnbTestnetChainAdapter(),
                new PolygonAmoyChainAdapter(),
                new AvalancheFujiChainAdapter(),
                new GnosisChiadoChainAdapter(),
                new KaiaKairosChainAdapter(),
                new ArcTestnetChainAdapter(),
                new StoryAeneidChainAdapter(),
                new BobaSepoliaChainAdapter(),
                new CeloSepoliaChainAdapter(),
                new HyperEvmTestnetChainAdapter(),
                new AbstractSepoliaChainAdapter(),
                new AnimeTestnetChainAdapter(),
                new ApeChainCurtisChainAdapter(),
                new InkSepoliaChainAdapter(),
                new JovaySepoliaChainAdapter(),
                new CrossFiTestnetChainAdapter(),
                new LineaSepoliaChainAdapter(),
                new TronChainAdapter(),
                new TronNileChainAdapter(),
                new TronShastaChainAdapter(),
                new SuiChainAdapter(),
                new SuiTestnetChainAdapter(),
                new CosmosChainAdapter(),
                new CosmosTestnetChainAdapter(),
                new OsmosisChainAdapter(),
                new OsmosisTestnetChainAdapter(),
                new InjectiveChainAdapter(),
                new InjectiveTestnetChainAdapter(),
                new SolanaChainAdapter(),
                new SolanaDevnetChainAdapter(),
                new AptosChainAdapter(),
                new AptosTestnetChainAdapter(),
                new BitcoinChainAdapter(),
                new BitcoinTestnetChainAdapter(),
                new LitecoinChainAdapter(),
                new LitecoinTestnetChainAdapter(),
                new DogecoinChainAdapter(),
                new DogecoinTestnetChainAdapter(),
                new BitcoinCashChainAdapter(),
                new BitcoinCashTestnetChainAdapter());

        assertThat(registry.descriptors().descriptors())
                .extracting(descriptor -> descriptor.schemaName())
                .containsExactly("abstract", "abstract_sepolia", "anime", "anime_testnet", "apechain", "apechain_curtis", "aptos", "aptos_testnet", "arbitrum", "arbitrum_sepolia", "arc", "arc_testnet", "avalanche", "avalanche_fuji", "base", "base_sepolia", "bitcoin", "bitcoin_testnet", "bitcoincash", "bitcoincash_testnet", "bnb", "bnb_testnet", "boba", "boba_sepolia", "celo", "celo_sepolia", "cosmos", "cosmos_testnet", "crossfi", "crossfi_testnet", "degen", "dogecoin", "dogecoin_testnet", "ethereum", "ethereum_sepolia", "gnosis", "gnosis_chiado", "hyperevm", "hyperevm_testnet", "injective", "injective_testnet", "ink", "ink_sepolia", "jovay", "jovay_sepolia", "kaia", "kaia_kairos", "linea", "linea_sepolia", "litecoin", "litecoin_testnet", "optimism", "optimism_sepolia", "osmosis", "osmosis_testnet", "polygon", "polygon_amoy", "solana", "solana_devnet", "story", "story_aeneid", "sui", "sui_testnet", "tron", "tron_nile", "tron_shasta");
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
        assertThat(registry.adapterForSchema("solana_devnet")).isInstanceOf(SolanaDevnetChainAdapter.class);
        assertThat(registry.adapterForSchema("aptos_testnet")).isInstanceOf(AptosTestnetChainAdapter.class);
        assertThat(registry.adapterForSchema("tron_nile")).isInstanceOf(TronNileChainAdapter.class);
        assertThat(registry.adapterForSchema("tron_shasta")).isInstanceOf(TronShastaChainAdapter.class);
        assertThat(registry.adapterForSchema("sui_testnet")).isInstanceOf(SuiTestnetChainAdapter.class);
        assertThat(registry.adapterForSchema("cosmos_testnet")).isInstanceOf(CosmosTestnetChainAdapter.class);
        assertThat(registry.adapterForSchema("osmosis_testnet")).isInstanceOf(OsmosisTestnetChainAdapter.class);
        assertThat(registry.adapterForSchema("injective_testnet")).isInstanceOf(InjectiveTestnetChainAdapter.class);
        assertThat(registry.adapterForSchema("bitcoin_testnet")).isInstanceOf(BitcoinTestnetChainAdapter.class);
        assertThat(registry.adapterForSchema("litecoin_testnet")).isInstanceOf(LitecoinTestnetChainAdapter.class);
        assertThat(registry.adapterForSchema("dogecoin_testnet")).isInstanceOf(DogecoinTestnetChainAdapter.class);
        assertThat(registry.adapterForSchema("bitcoincash_testnet")).isInstanceOf(BitcoinCashTestnetChainAdapter.class);
        assertThat(new Web3Metadata(10).listSchemaNames(null))
                .containsExactlyElementsOf(registry.descriptors().descriptors().stream()
                        .map(descriptor -> descriptor.schemaName())
                        .toList());
    }
}
