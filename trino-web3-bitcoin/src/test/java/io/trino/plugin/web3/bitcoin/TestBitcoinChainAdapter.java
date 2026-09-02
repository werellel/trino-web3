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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestBitcoinChainAdapter
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final BitcoinChainAdapter adapter = new BitcoinChainAdapter();

    @Test
    public void testDescriptorAndBoundedSplits()
    {
        assertThat(adapter.descriptor().schemaName()).isEqualTo("bitcoin");
        assertThat(adapter.descriptor().tables()).extracting(table -> table.name())
                .containsExactly("blocks", "transactions", "inputs", "outputs");

        List<ChainSplit> splits = adapter.planSplits(
                new ChainScan("blocks", Map.of("height", new ChainScan.LongRange(100, 104)), Map.of()),
                new ChainSplitLimits(2, 10, 1_000));
        assertThat(splits).containsExactly(
                new RangeChainSplit("height", 100, 101),
                new RangeChainSplit("height", 102, 103),
                new RangeChainSplit("height", 104, 104));
    }

    @Test
    public void testRejectsUnboundedAndWrongPredicates()
    {
        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan("transactions", Map.of(), Map.of()),
                new ChainSplitLimits(100, 1_000, 1_000)))
                .hasMessageContaining("requires a bounded block_height predicate");
        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan("blocks", Map.of("height", new ChainScan.LongRange(1, 2)), Map.of("hash", List.of("x"))),
                new ChainSplitLimits(100, 1_000, 1_000)))
                .hasMessageContaining("unsupported pushed predicates");
    }

    @Test
    public void testIdentityProbeRecognizesBitcoinNetworks()
    {
        assertThat(BitcoinChainAdapter.chainName(OBJECT_MAPPER.createObjectNode().put("chain", "main"))).isEqualTo("main");
        assertThatThrownBy(() -> BitcoinChainAdapter.chainName(OBJECT_MAPPER.createObjectNode().put("chain", "unknown")))
                .hasMessage("invalid Bitcoin chain identity");
    }
}
