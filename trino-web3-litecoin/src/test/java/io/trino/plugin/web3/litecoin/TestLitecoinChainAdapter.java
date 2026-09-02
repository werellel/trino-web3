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
package io.trino.plugin.web3.litecoin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class TestLitecoinChainAdapter
{
    @Test
    public void testTestnetSchema()
    {
        assertThat(new LitecoinTestnetChainAdapter().descriptor().schemaName()).isEqualTo("litecoin_testnet");
    }

    @Test
    public void testPlansBoundedNativeRangeAndValidatesIdentity()
            throws Exception
    {
        LitecoinChainAdapter adapter = new LitecoinChainAdapter();
        List<ChainSplit> splits = adapter.planSplits(
                new ChainScan("blocks", Map.of("height", new ChainScan.LongRange(10, 11)), Map.of()),
                new ChainSplitLimits(1, 10, 10));
        assertThat(splits).hasSize(2);
        assertThat(adapter.endpointIdentityProbe().extractIdentity(new ObjectMapper().readTree("{\"subversion\":\"/Litecoin Core:0.21.3/\"}")))
                .isEqualTo("litecoin");
    }
}
