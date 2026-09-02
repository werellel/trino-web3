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
package io.trino.plugin.web3.cosmos;

import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestCosmosChainAdapters
{
    @Test
    void testNativeDescriptorsAndBoundedPlanning()
    {
        assertThat(new CosmosChainAdapter().descriptor().schemaName()).isEqualTo("cosmos");
        assertThat(new OsmosisChainAdapter().descriptor().schemaName()).isEqualTo("osmosis");
        assertThat(new InjectiveChainAdapter().descriptor().schemaName()).isEqualTo("injective");
        assertThat(new CosmosTestnetChainAdapter().descriptor().schemaName()).isEqualTo("cosmos_testnet");
        assertThat(new OsmosisTestnetChainAdapter().descriptor().schemaName()).isEqualTo("osmosis_testnet");
        assertThat(new InjectiveTestnetChainAdapter().descriptor().schemaName()).isEqualTo("injective_testnet");
        var injectiveIdentity = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        injectiveIdentity.putObject("block").putObject("header").put("chain_id", "injective-888");
        assertThat(new InjectiveTestnetChainAdapter().endpointIdentityProbe().extractIdentity(injectiveIdentity))
                .isEqualTo("injective-888");
        var cosmosIdentity = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        cosmosIdentity.putObject("block").putObject("header").put("chain_id", "theta-testnet-001");
        assertThat(new CosmosTestnetChainAdapter().endpointIdentityProbe().extractIdentity(cosmosIdentity))
                .isEqualTo("theta-testnet-001");
        var osmosisIdentity = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        osmosisIdentity.putObject("block").putObject("header").put("chain_id", "osmo-test-5");
        assertThat(new OsmosisTestnetChainAdapter().endpointIdentityProbe().extractIdentity(osmosisIdentity))
                .isEqualTo("osmo-test-5");
        assertThat(new CosmosChainAdapter().planSplits(
                new ChainScan("blocks", Map.of("height", new ChainScan.LongRange(10, 11)), Map.of()),
                new ChainSplitLimits(1, 100, 100))).hasSize(2);
    }

    @Test
    void testRejectsUnboundedRead()
    {
        assertThatThrownBy(() -> new OsmosisChainAdapter().planSplits(
                new ChainScan("transactions", Map.of(), Map.of()), new ChainSplitLimits(100, 100, 100)))
                .hasMessage("osmosis.transactions requires a bounded height predicate");
    }
}
