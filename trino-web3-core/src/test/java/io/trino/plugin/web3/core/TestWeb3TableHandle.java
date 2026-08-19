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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestWeb3TableHandle
{
    @Test
    public void testTransactionHashDisplayIsBounded()
    {
        String hash = "0x" + "a".repeat(64);
        Web3TableHandle handle = new Web3TableHandle("ethereum", "transactions", Optional.empty(), List.of(hash));

        assertThat(handle.toString())
                .isEqualTo("ethereum.transactions[method=by-hash, ranges=0, discreteValues=1]")
                .doesNotContain(hash);
    }

    @Test
    public void testNamedPredicatesAreImmutableAndBounded()
    {
        Map<String, BlockRange> ranges = new java.util.LinkedHashMap<>();
        ranges.put("ledger_version", new BlockRange(10, 12));
        List<String> signatures = new java.util.ArrayList<>(List.of("signature-1"));
        Map<String, List<String>> discreteValues = new java.util.LinkedHashMap<>();
        discreteValues.put("signature", signatures);

        Web3TableHandle handle = new Web3TableHandle(
                "solana",
                "transactions",
                Optional.of("by-ledger-version"),
                ranges,
                discreteValues);
        ranges.clear();
        signatures.clear();
        discreteValues.clear();

        assertThat(handle.ranges()).containsEntry("ledger_version", new BlockRange(10, 12));
        assertThat(handle.discreteValues()).containsEntry("signature", List.of("signature-1"));
        assertThatThrownBy(() -> handle.ranges().put("slot", new BlockRange(1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> handle.discreteValues().get("signature").add("signature-2"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(handle.toString()).doesNotContain("signature-1");
    }

    @Test
    public void testConstrainedHandleRequiresMethodAndDistinctPredicateColumns()
    {
        assertThatThrownBy(() -> new Web3TableHandle(
                "aptos",
                "transactions",
                Optional.empty(),
                Map.of("ledger_version", new BlockRange(1, 1)),
                Map.of()))
                .hasMessage("constrained table handle requires methodName");
        assertThatThrownBy(() -> new Web3TableHandle(
                "aptos",
                "transactions",
                Optional.of("by-version"),
                Map.of("ledger_version", new BlockRange(1, 1)),
                Map.of("ledger_version", List.of("1"))))
                .hasMessage("range and discrete predicates must use different columns");
    }
}
