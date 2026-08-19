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

import io.trino.plugin.web3.adapter.ChainPlanningException;
import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import io.trino.plugin.web3.adapter.DiscreteValueChainSplit;
import io.trino.plugin.web3.adapter.RangeChainSplit;
import io.trino.plugin.web3.chain.ChainDescriptor;
import io.trino.plugin.web3.chain.ChainTableDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.Cardinality.ARRAY;
import static io.trino.plugin.web3.chain.RemoteMethodDescriptor.Protocol.JSON_RPC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestEthereumChainAdapter
{
    @Test
    void testBuiltInDescriptorContract()
    {
        ChainDescriptor descriptor = new EthereumChainAdapter().descriptor();

        assertThat(descriptor.apiVersion()).isEqualTo(ChainDescriptor.SUPPORTED_API_VERSION);
        assertThat(descriptor.name()).isEqualTo("ethereum");
        assertThat(descriptor.schemaName()).isEqualTo("ethereum");
        assertThat(descriptor.adapterVersion()).isEqualTo(1);
        assertThat(descriptor.tables()).extracting(ChainTableDescriptor::name)
                .containsExactly("blocks", "transactions");
        assertThat(descriptor.table("blocks").orElseThrow().columns())
                .extracting(column -> column.name() + ":" + column.type())
                .containsExactly("block_number:bigint", "block_hash:varchar");
        assertThat(descriptor.table("transactions").orElseThrow().columns())
                .extracting(column -> column.name() + ":" + column.type())
                .containsExactly("hash:varchar", "block_number:bigint", "from_address:varchar", "to_address:varchar");
    }

    @Test
    void testTransactionMethodsDescribeBothBoundedAccessPaths()
    {
        ChainTableDescriptor transactions = new EthereumChainAdapter().descriptor().table("transactions").orElseThrow();

        assertThat(transactions.methods()).allMatch(method -> method.protocol() == JSON_RPC);
        assertThat(transactions.method("by-block-number").orElseThrow().action()).isEqualTo("eth_getBlockByNumber");
        assertThat(transactions.method("by-block-number").orElseThrow().response().cardinality()).isEqualTo(ARRAY);
        assertThat(transactions.method("by-hash").orElseThrow().action()).isEqualTo("eth_getTransactionByHash");
    }

    @Test
    void testPlansBoundedBlockRangeSplits()
    {
        EthereumChainAdapter adapter = new EthereumChainAdapter();
        ChainScan scan = new ChainScan(
                "blocks",
                Map.of("block_number", new ChainScan.LongRange(10, 14)),
                Map.of());

        assertThat(adapter.planSplits(scan, new ChainSplitLimits(2, 10, 10)))
                .containsExactly(
                        new RangeChainSplit("block_number", 10, 11),
                        new RangeChainSplit("block_number", 12, 13),
                        new RangeChainSplit("block_number", 14, 14));
    }

    @Test
    void testPlansBoundedTransactionHashSplits()
    {
        EthereumChainAdapter adapter = new EthereumChainAdapter();
        String firstHash = "0x" + "A".repeat(64);
        String secondHash = "0x" + "b".repeat(64);
        ChainScan scan = new ChainScan(
                "transactions",
                Map.of(),
                Map.of("hash", List.of(firstHash, secondHash)));

        assertThat(adapter.planSplits(scan, new ChainSplitLimits(2, 10, 2)))
                .containsExactly(
                        new DiscreteValueChainSplit("hash", firstHash.toLowerCase(java.util.Locale.ENGLISH)),
                        new DiscreteValueChainSplit("hash", secondHash));
        assertThatThrownBy(() -> adapter.planSplits(scan, new ChainSplitLimits(2, 10, 1)))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("ethereum.transactions hash predicate exceeds the configured query limit of 1");
        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan("transactions", Map.of(), Map.of("hash", List.of("invalid"))),
                new ChainSplitLimits(2, 10, 2)))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("ethereum.transactions hash predicate contains an invalid transaction hash");
    }

    @Test
    void testRejectsUnboundedOrUnsupportedScans()
    {
        EthereumChainAdapter adapter = new EthereumChainAdapter();

        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan("blocks", Map.of(), Map.of()),
                new ChainSplitLimits(2, 10, 2)))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("ethereum.blocks requires a bounded block_number predicate");
        assertThatThrownBy(() -> adapter.planSplits(
                new ChainScan("blocks", Map.of("slot", new ChainScan.LongRange(1, 1)), Map.of()),
                new ChainSplitLimits(2, 10, 2)))
                .isInstanceOf(ChainPlanningException.class)
                .hasMessage("unsupported pushed predicates for ethereum.blocks");
    }
}
