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

import java.util.ArrayList;
import java.util.List;

public final class BlockRangeSplitter
{
    private BlockRangeSplitter() {}

    public static List<Web3Split> split(BlockRange blockRange, long maximumSplitSize, long maximumBlocksPerQuery)
    {
        if (maximumSplitSize < 1) {
            throw new IllegalArgumentException("maximumSplitSize must be positive");
        }
        if (maximumBlocksPerQuery < 1) {
            throw new IllegalArgumentException("maximumBlocksPerQuery must be positive");
        }
        if (blockRange.endInclusive() - blockRange.startInclusive() >= maximumBlocksPerQuery) {
            throw new IllegalArgumentException("block range exceeds maximumBlocksPerQuery");
        }

        List<Web3Split> splits = new ArrayList<>();
        long start = blockRange.startInclusive();
        while (true) {
            long remaining = blockRange.endInclusive() - start;
            long end = remaining < maximumSplitSize ? blockRange.endInclusive() : Math.addExact(start, maximumSplitSize - 1);
            splits.add(new Web3Split(new BlockRange(start, end)));
            if (end == blockRange.endInclusive()) {
                return List.copyOf(splits);
            }
            start = Math.addExact(end, 1);
        }
    }
}
