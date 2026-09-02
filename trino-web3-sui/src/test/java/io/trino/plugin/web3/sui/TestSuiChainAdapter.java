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
package io.trino.plugin.web3.sui;

import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestSuiChainAdapter
{
    @Test
    void testDescriptorAndBoundedPlanning()
    {
        SuiChainAdapter adapter = new SuiChainAdapter();
        assertThat(adapter.descriptor().schemaName()).isEqualTo("sui");
        assertThat(adapter.descriptor().tables()).extracting(table -> table.name()).containsExactly("checkpoints", "transactions");
        assertThat(adapter.planSplits(new ChainScan("checkpoints", Map.of("checkpoint_sequence_number", new ChainScan.LongRange(10, 12)), Map.of()), new ChainSplitLimits(2, 100, 100)))
                .extracting(ChainSplit::toString)
                .containsExactly("RangeChainSplit[column=checkpoint_sequence_number, startInclusive=10, endInclusive=11]", "RangeChainSplit[column=checkpoint_sequence_number, startInclusive=12, endInclusive=12]");
    }

    @Test
    void testRejectsUnboundedScan()
    {
        assertThatThrownBy(() -> new SuiChainAdapter().planSplits(new ChainScan("transactions", Map.of(), Map.of()), new ChainSplitLimits(100, 100, 100)))
                .hasMessage("sui.transactions requires a bounded checkpoint_sequence_number predicate");
    }

    @Test
    void testTestnetUsesIndependentSchemaAndChainIdentity()
    {
        SuiTestnetChainAdapter testnet = new SuiTestnetChainAdapter();
        assertThat(testnet.descriptor().schemaName()).isEqualTo("sui_testnet");
        assertThat(testnet.endpointIdentityProbe().extractIdentity(
                com.fasterxml.jackson.databind.node.TextNode.valueOf("4c78adac"))).isEqualTo("4c78adac");
        assertThatThrownBy(() -> testnet.endpointIdentityProbe().extractIdentity(
                com.fasterxml.jackson.databind.node.TextNode.valueOf("35834a8a")))
                .hasMessage("invalid Sui testnet chain identity");
    }
}
