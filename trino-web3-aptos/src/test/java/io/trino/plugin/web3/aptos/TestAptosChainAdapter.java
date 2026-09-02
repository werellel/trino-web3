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
import io.trino.plugin.web3.adapter.KeyedRangeChainSplit;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.runtime.RestRemoteRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.Protocol.REST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestAptosChainAdapter
{
    private final AptosChainAdapter adapter = new AptosChainAdapter();

    @Test
    public void testDescriptorDefinesNativeTables()
    {
        var descriptor = adapter.descriptor();
        var table = descriptor.table("transactions").orElseThrow();

        assertThat(descriptor.name()).isEqualTo("aptos");
        assertThat(descriptor.schemaName()).isEqualTo("aptos");
        assertThat(table.columns()).extracting(column -> column.name())
                .containsExactly("ledger_version", "hash", "type", "success", "vm_status", "sender", "raw_json");
        assertThat(table.method("by-ledger-version-range").orElseThrow().protocol()).isEqualTo(REST);
        var events = descriptor.table("events").orElseThrow();
        assertThat(events.columns()).extracting(column -> column.name())
                .containsExactly("account_address", "creation_number", "sequence_number", "event_type", "data", "raw_json");
        assertThat(events.method("by-account-creation-number-sequence-range").orElseThrow().protocol()).isEqualTo(REST);
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
    public void testEndpointIdentityProbeRequiresAptosChainId()
    {
        var probe = adapter.endpointIdentityProbe();
        var response = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("chain_id", 1);

        assertThat(probe.request()).isEqualTo(new RestRemoteRequest("GET", "/v1", Map.of(), Optional.empty()));
        assertThat(probe.extractIdentity(response)).isEqualTo("1");
        assertThatThrownBy(() -> probe.extractIdentity(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("chain_id", 0)))
                .hasMessage("invalid Aptos chain identity");
    }

    @Test
    public void testTestnetUsesIndependentSchemaAndChainId()
    {
        AptosTestnetChainAdapter testnet = new AptosTestnetChainAdapter();
        assertThat(testnet.descriptor().schemaName()).isEqualTo("aptos_testnet");
        assertThat(testnet.endpointIdentityProbe().extractIdentity(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("chain_id", 2))).isEqualTo("2");
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

    @Test
    public void testPlansBoundedAccountEventStreamSplits()
    {
        ChainScan scan = new ChainScan(
                "events",
                Optional.of("by-account-creation-number-sequence-range"),
                Map.of("sequence_number", new ChainScan.LongRange(10, 12)),
                Map.of(
                        "account_address", List.of("0x0001"),
                        "creation_number", List.of("007")));

        assertThat(adapter.planSplits(scan, new ChainSplitLimits(2, 10, 100)))
                .containsExactly(
                        new KeyedRangeChainSplit(Map.of("account_address", "0x1", "creation_number", "7"), "sequence_number", 10, 11),
                        new KeyedRangeChainSplit(Map.of("account_address", "0x1", "creation_number", "7"), "sequence_number", 12, 12));
    }

    @Test
    public void testRejectsUnboundedOrAmbiguousEventScans()
    {
        ChainSplitLimits limits = new ChainSplitLimits(10, 10, 10);
        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan("events", Map.of(), Map.of(
                        "account_address", List.of("0x1"),
                        "creation_number", List.of("7"))),
                limits))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("aptos.events requires a bounded sequence_number predicate");
        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan(
                        "events",
                        Map.of("sequence_number", new ChainScan.LongRange(0, 1)),
                        Map.of(
                                "account_address", List.of("0x1", "0x2"),
                                "creation_number", List.of("7"))),
                limits))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("aptos.events requires exactly one account_address predicate");
        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan(
                        "events",
                        Map.of("sequence_number", new ChainScan.LongRange(0, 1)),
                        Map.of(
                                "account_address", List.of("invalid"),
                                "creation_number", List.of("7"))),
                limits))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("aptos.events has an invalid account_address");
        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan(
                        "events",
                        Map.of("sequence_number", new ChainScan.LongRange(0, 1)),
                        Map.of(
                                "account_address", List.of("0x1"),
                                "creation_number", List.of("18446744073709551616"))),
                limits))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("aptos.events has an invalid creation_number");
    }
}
