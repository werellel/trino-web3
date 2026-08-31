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

import io.trino.plugin.web3.adapter.ChainPlanningException;
import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.chain.ChainTableDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestSolanaChainAdapter
{
    @Test
    void testBuiltInDescriptorDefinesNativeTables()
    {
        var descriptor = new SolanaChainAdapter().descriptor();

        assertThat(descriptor.name()).isEqualTo("solana");
        assertThat(descriptor.schemaName()).isEqualTo("solana");
        assertThat(descriptor.adapterVersion()).isEqualTo(1);
        assertThat(descriptor.tables()).extracting(ChainTableDescriptor::name)
                .containsExactly("blocks", "transactions", "instructions");
        assertThat(descriptor.table("instructions").orElseThrow().columns())
                .extracting(column -> column.name() + ":" + column.type())
                .containsExactly(
                        "slot:bigint",
                        "transaction_signature:varchar",
                        "instruction_index:bigint",
                        "program_id:varchar",
                        "account_indices:varchar",
                        "data:varchar");
    }

    @Test
    void testPlansBoundedSlotSplitsForEveryTable()
    {
        SolanaChainAdapter adapter = new SolanaChainAdapter();
        ChainScan scan = new ChainScan("instructions", Map.of("slot", new ChainScan.LongRange(10, 14)), Map.of());

        assertThat(adapter.planSplits(scan, new ChainSplitLimits(2, 10, 10)))
                .containsExactly(
                        new RangeChainSplit("slot", 10, 11),
                        new RangeChainSplit("slot", 12, 13),
                        new RangeChainSplit("slot", 14, 14));
    }

    @Test
    void testRejectsUnboundedAndUnsupportedSlotScans()
    {
        SolanaChainAdapter adapter = new SolanaChainAdapter();

        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan("blocks", Map.of(), Map.of()),
                new ChainSplitLimits(2, 10, 10)))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("solana.blocks requires a bounded slot predicate");
        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan("transactions", Map.of("block_number", new ChainScan.LongRange(1, 1)), Map.of()),
                new ChainSplitLimits(2, 10, 10)))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("unsupported pushed predicates for solana.transactions");
    }
}
