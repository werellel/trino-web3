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
package io.trino.plugin.web3.evm;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestEvmChainAdapters
{
    @Test
    void testBuiltInNetworksAndChainIdentity()
    {
        List<EvmChainAdapter> adapters = List.of(
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
                new UnichainChainAdapter(),
                new TempoChainAdapter(),
                new RobinhoodChainAdapter(),
                new ModeChainAdapter(),
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
                new UnichainSepoliaChainAdapter(),
                new TempoModeratoChainAdapter(),
                new RobinhoodTestnetChainAdapter(),
                new ModeSepoliaChainAdapter());

        assertThat(adapters).extracting(adapter -> adapter.descriptor().schemaName())
                .containsExactly("ethereum", "base", "optimism", "arbitrum", "bnb", "polygon", "avalanche", "gnosis", "kaia", "arc", "story", "boba", "celo", "hyperevm", "abstract", "anime", "apechain", "degen", "ink", "jovay", "crossfi", "linea", "unichain", "tempo", "robinhood", "mode", "ethereum_sepolia", "base_sepolia", "optimism_sepolia", "arbitrum_sepolia", "bnb_testnet", "polygon_amoy", "avalanche_fuji", "gnosis_chiado", "kaia_kairos", "arc_testnet", "story_aeneid", "boba_sepolia", "celo_sepolia", "hyperevm_testnet", "abstract_sepolia", "anime_testnet", "apechain_curtis", "ink_sepolia", "jovay_sepolia", "crossfi_testnet", "linea_sepolia", "unichain_sepolia", "tempo_moderato", "robinhood_testnet", "mode_sepolia");
        assertThat(adapters).allSatisfy(adapter -> {
            assertThat(adapter.descriptor().table("blocks")).isPresent();
            assertThat(adapter.descriptor().table("transactions")).isPresent();
            assertThat(adapter.descriptor().table("blocks").orElseThrow().column("raw_json")).isPresent();
        });

        assertThat(new BaseChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x2105"))).isEqualTo("0x2105");
        assertThat(new OptimismChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xA"))).isEqualTo("0xa");
        assertThat(new ArbitrumChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xA4B1"))).isEqualTo("0xa4b1");
        assertThat(new BnbChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x38"))).isEqualTo("0x38");
        assertThat(new PolygonChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x89"))).isEqualTo("0x89");
        assertThat(new AvalancheChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xA86A"))).isEqualTo("0xa86a");
        assertThat(new GnosisChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x64"))).isEqualTo("0x64");
        assertThat(new KaiaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x2019"))).isEqualTo("0x2019");
        assertThat(new ArcChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x13B2"))).isEqualTo("0x13b2");
        assertThat(new StoryChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x5EA"))).isEqualTo("0x5ea");
        assertThat(new BobaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x120"))).isEqualTo("0x120");
        assertThat(new CeloChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xA4EC"))).isEqualTo("0xa4ec");
        assertThat(new HyperEvmChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x3E7"))).isEqualTo("0x3e7");
        assertThat(new AbstractChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xAB5"))).isEqualTo("0xab5");
        assertThat(new AnimeChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x10D88"))).isEqualTo("0x10d88");
        assertThat(new ApeChainChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x8173"))).isEqualTo("0x8173");
        assertThat(new DegenChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x27BC86AA"))).isEqualTo("0x27bc86aa");
        assertThat(new InkChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xDEF1"))).isEqualTo("0xdef1");
        assertThat(new JovayChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x578227"))).isEqualTo("0x578227");
        assertThat(new CrossFiChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x103E"))).isEqualTo("0x103e");
        assertThat(new LineaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xE708"))).isEqualTo("0xe708");
        assertThat(new UnichainChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x82"))).isEqualTo("0x82");
        assertThat(new TempoChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x1079"))).isEqualTo("0x1079");
        assertThat(new RobinhoodChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x1237"))).isEqualTo("0x1237");
        assertThat(new ModeChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x868b"))).isEqualTo("0x868b");
        assertThat(new EthereumSepoliaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xAA36A7"))).isEqualTo("0xaa36a7");
        assertThat(new BaseSepoliaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x14A34"))).isEqualTo("0x14a34");
        assertThat(new OptimismSepoliaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xAA37DC"))).isEqualTo("0xaa37dc");
        assertThat(new ArbitrumSepoliaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x66EEE"))).isEqualTo("0x66eee");
        assertThat(new BnbTestnetChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x61"))).isEqualTo("0x61");
        assertThat(new PolygonAmoyChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x13882"))).isEqualTo("0x13882");
        assertThat(new AvalancheFujiChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xA869"))).isEqualTo("0xa869");
        assertThat(new GnosisChiadoChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x27D8"))).isEqualTo("0x27d8");
        assertThat(new KaiaKairosChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x3E9"))).isEqualTo("0x3e9");
        assertThat(new ArcTestnetChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x4CEF52"))).isEqualTo("0x4cef52");
        assertThat(new StoryAeneidChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x523"))).isEqualTo("0x523");
        assertThat(new BobaSepoliaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x70D2"))).isEqualTo("0x70d2");
        assertThat(new CeloSepoliaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xAA044C"))).isEqualTo("0xaa044c");
        assertThat(new HyperEvmTestnetChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x3E6"))).isEqualTo("0x3e6");
        assertThat(new AbstractSepoliaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x2B74"))).isEqualTo("0x2b74");
        assertThat(new AnimeTestnetChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x872"))).isEqualTo("0x872");
        assertThat(new ApeChainCurtisChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x8157"))).isEqualTo("0x8157");
        assertThat(new InkSepoliaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xBA5ED"))).isEqualTo("0xba5ed");
        assertThat(new JovaySepoliaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x1ED1BF"))).isEqualTo("0x1ed1bf");
        assertThat(new CrossFiTestnetChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x103D"))).isEqualTo("0x103d");
        assertThat(new LineaSepoliaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xE705"))).isEqualTo("0xe705");
        assertThat(new UnichainSepoliaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x515"))).isEqualTo("0x515");
        assertThat(new TempoModeratoChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xA5BF"))).isEqualTo("0xa5bf");
        assertThat(new RobinhoodTestnetChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0xB626"))).isEqualTo("0xb626");
        assertThat(new ModeSepoliaChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x397"))).isEqualTo("0x397");
    }

    @Test
    void testRejectsWrongNetworkIdentity()
    {
        assertThatThrownBy(() -> new PolygonChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }
}
