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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestWeb3ChainSplits
{
    @Test
    public void testRangeSplitPreservesPredicateColumn()
    {
        assertThat(new Web3RangeSplit("ledger_version", 10, 12))
                .isEqualTo(new Web3RangeSplit("ledger_version", 10, 12));

        assertThatThrownBy(() -> new Web3RangeSplit("invalid-column", 10, 12))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Web3RangeSplit("ledger_version", 12, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testDiscreteSplitPreservesPredicateColumnAndBoundsValue()
    {
        assertThat(new Web3DiscreteValueSplit("signature", "value"))
                .isEqualTo(new Web3DiscreteValueSplit("signature", "value"));

        assertThatThrownBy(() -> new Web3DiscreteValueSplit("signature", "x".repeat(4_097)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("split value exceeds maximum length of 4096");
    }
}
