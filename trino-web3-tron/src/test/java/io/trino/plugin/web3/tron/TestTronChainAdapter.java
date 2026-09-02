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
package io.trino.plugin.web3.tron;

import io.trino.plugin.web3.adapter.ChainPlanningException;
import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.chain.ChainTableDescriptor;
import io.trino.plugin.web3.runtime.RestRemoteRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.Protocol.REST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestTronChainAdapter
{
    private final TronChainAdapter adapter = new TronChainAdapter();

    @Test
    void testBuiltInDescriptorDefinesNativeTables()
    {
        var descriptor = adapter.descriptor();

        assertThat(descriptor.name()).isEqualTo("tron");
        assertThat(descriptor.schemaName()).isEqualTo("tron");
        assertThat(descriptor.adapterVersion()).isEqualTo(1);
        assertThat(descriptor.tables()).extracting(ChainTableDescriptor::name)
                .containsExactly("blocks", "transactions");
        assertThat(descriptor.table("blocks").orElseThrow().method("by-block-number").orElseThrow().protocol())
                .isEqualTo(REST);
        assertThat(descriptor.table("transactions").orElseThrow().columns())
                .extracting(column -> column.name() + ":" + column.type())
                .containsExactly("txid:varchar", "block_number:bigint", "contract_count:bigint", "raw_json:varchar");
    }

    @Test
    void testPlansBoundedBlockNumberSplits()
    {
        ChainScan scan = new ChainScan(
                "blocks",
                Optional.of("by-block-number"),
                Map.of("block_number", new ChainScan.LongRange(10, 14)),
                Map.of());

        assertThat(adapter.planSplits(scan, new ChainSplitLimits(2, 10, 100)))
                .containsExactly(
                        new RangeChainSplit("block_number", 10, 11),
                        new RangeChainSplit("block_number", 12, 13),
                        new RangeChainSplit("block_number", 14, 14));
    }

    @Test
    void testEndpointIdentityProbeRequiresNativeBlockShape()
    {
        var probe = adapter.endpointIdentityProbe();
        var response = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("blockID", "0000000000000000000000000000000000000000000000000000000000000001");
        response.putObject("block_header").putObject("raw_data").put("number", 1);

        assertThat(probe.request()).isEqualTo(new RestRemoteRequest("GET", "/wallet/getnowblock", Map.of(), Optional.empty()));
        assertThat(probe.extractIdentity(response)).isEqualTo("tron");
        assertThatThrownBy(() -> probe.extractIdentity(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()))
                .hasMessage("invalid Tron node identity");
    }

    @Test
    void testRejectsUnboundedAndUnsupportedScans()
    {
        ChainSplitLimits limits = new ChainSplitLimits(2, 10, 10);
        assertThatThrownBy(() -> adapter.planSplits(new ChainScan("blocks", Map.of(), Map.of()), limits))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("tron.blocks requires a bounded block_number predicate");
        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan("transactions", Optional.of("by-hash"), Map.of("block_number", new ChainScan.LongRange(1, 1)), Map.of()), limits))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("descriptor method does not match predicates for tron.transactions");
        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan("blocks", Map.of("block_number", new ChainScan.LongRange(1, 11)), Map.of()), limits))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("block range exceeds maximumBlocksPerQuery");
    }
}
