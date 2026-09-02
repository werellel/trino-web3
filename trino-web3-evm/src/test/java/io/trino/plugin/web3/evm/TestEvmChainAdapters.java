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
                new LineaChainAdapter());

        assertThat(adapters).extracting(adapter -> adapter.descriptor().schemaName())
                .containsExactly("ethereum", "base", "optimism", "arbitrum", "bnb", "polygon", "avalanche", "gnosis", "kaia", "arc", "story", "boba", "celo", "hyperevm", "abstract", "anime", "apechain", "degen", "ink", "jovay", "crossfi", "linea");
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
    }

    @Test
    void testRejectsWrongNetworkIdentity()
    {
        assertThatThrownBy(() -> new PolygonChainAdapter().endpointIdentityProbe().extractIdentity(TextNode.valueOf("0x1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }
}
