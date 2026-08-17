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

public class TestBlockRange
{
    @Test
    public void testSize()
    {
        assertThat(new BlockRange(10, 12).size()).isEqualTo(3);
    }

    @Test
    public void testRejectsInvalidBounds()
    {
        assertThatThrownBy(() -> new BlockRange(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new BlockRange(2, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("smaller");
    }

    @Test
    public void testSplitRange()
    {
        assertThat(BlockRangeSplitter.split(new BlockRange(100, 109), 4, 10))
                .extracting(Web3Split::blockRange)
                .containsExactly(
                        new BlockRange(100, 103),
                        new BlockRange(104, 107),
                        new BlockRange(108, 109));
    }

    @Test
    public void testRejectsRangeLargerThanQueryLimit()
    {
        assertThatThrownBy(() -> BlockRangeSplitter.split(new BlockRange(100, 110), 4, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("block range exceeds maximumBlocksPerQuery");
    }
}
