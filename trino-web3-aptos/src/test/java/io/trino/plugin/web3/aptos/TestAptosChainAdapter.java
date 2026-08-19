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
package io.trino.plugin.web3.aptos;

import io.trino.plugin.web3.adapter.ChainPlanningException;
import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.Protocol.REST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestAptosChainAdapter
{
    private final AptosChainAdapter adapter = new AptosChainAdapter();

    @Test
    public void testDescriptorDefinesNativeTransactionsTable()
    {
        var descriptor = adapter.descriptor();
        var table = descriptor.table("transactions").orElseThrow();

        assertThat(descriptor.name()).isEqualTo("aptos");
        assertThat(descriptor.schemaName()).isEqualTo("aptos");
        assertThat(table.columns()).extracting(column -> column.name())
                .containsExactly("ledger_version", "hash", "type", "success", "vm_status", "sender");
        assertThat(table.method("by-ledger-version-range").orElseThrow().protocol()).isEqualTo(REST);
    }

    @Test
    public void testPlansBoundedLedgerVersionSplits()
    {
        ChainScan scan = new ChainScan(
                "transactions",
                Optional.of("by-ledger-version-range"),
                Map.of("ledger_version", new ChainScan.LongRange(10, 14)),
                Map.of());

        assertThat(adapter.planSplits(scan, new ChainSplitLimits(2, 10, 100)))
                .containsExactly(
                        new RangeChainSplit("ledger_version", 10, 11),
                        new RangeChainSplit("ledger_version", 12, 13),
                        new RangeChainSplit("ledger_version", 14, 14));
    }

    @Test
    public void testRejectsUnboundedAndMismatchedScans()
    {
        ChainSplitLimits limits = new ChainSplitLimits(10, 10, 10);
        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan("transactions", Map.of(), Map.of()),
                limits))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("aptos.transactions requires a bounded ledger_version predicate");
        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan(
                        "transactions",
                        Optional.of("by-hash"),
                        Map.of("ledger_version", new ChainScan.LongRange(1, 1)),
                        Map.of()),
                limits))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("descriptor method does not match predicates for aptos.transactions");
        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan(
                        "transactions",
                        Optional.of("by-ledger-version-range"),
                        Map.of("ledger_version", new ChainScan.LongRange(1, 11)),
                        Map.of()),
                limits))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("ledger version range exceeds the configured query limit");
    }

    @Test
    public void testCapsSplitsAtAptosTransactionsPageSize()
    {
        ChainScan scan = new ChainScan(
                "transactions",
                Optional.of("by-ledger-version-range"),
                Map.of("ledger_version", new ChainScan.LongRange(1, 101)),
                Map.of());

        assertThat(adapter.planSplits(scan, new ChainSplitLimits(1_000, 1_000, 100)))
                .containsExactly(
                        new RangeChainSplit("ledger_version", 1, 100),
                        new RangeChainSplit("ledger_version", 101, 101));
    }
}
