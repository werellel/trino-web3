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
package io.trino.plugin.web3.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class TestWeb3TableHandle
{
    @Test
    public void testTransactionHashDisplayIsBounded()
    {
        String hash = "0x" + "a".repeat(64);
        Web3TableHandle handle = new Web3TableHandle("ethereum", "transactions", Optional.empty(), List.of(hash));

        assertThat(handle.toString())
                .isEqualTo("ethereum.transactions[transactionHashes=1]")
                .doesNotContain(hash);
    }
}
