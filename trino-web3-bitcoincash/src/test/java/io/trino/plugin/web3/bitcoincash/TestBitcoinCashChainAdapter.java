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
package io.trino.plugin.web3.bitcoincash;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TestBitcoinCashChainAdapter
{
    @Test
    public void testTestnetSchema()
    {
        assertThat(new BitcoinCashTestnetChainAdapter().descriptor().schemaName()).isEqualTo("bitcoincash_testnet");
    }

    @Test
    public void testRecognizesBitcoinCashNodeIdentity()
            throws Exception
    {
        assertThat(new BitcoinCashChainAdapter().endpointIdentityProbe().extractIdentity(
                new ObjectMapper().readTree("{\"subversion\":\"/Bitcoin Cash Node:27.0.0/\"}")))
                .isEqualTo("bitcoincash");
    }
}
