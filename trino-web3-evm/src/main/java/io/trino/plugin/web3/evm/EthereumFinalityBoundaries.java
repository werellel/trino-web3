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

import java.util.OptionalLong;

import static java.util.Objects.requireNonNull;

public record EthereumFinalityBoundaries(OptionalLong safeBlock, OptionalLong finalizedBlock)
{
    public EthereumFinalityBoundaries
    {
        requireNonNull(safeBlock, "safeBlock is null");
        requireNonNull(finalizedBlock, "finalizedBlock is null");
        if (safeBlock.isPresent() != finalizedBlock.isPresent()) {
            throw new IllegalArgumentException("finality boundaries must both be present or absent");
        }
        if (safeBlock.isPresent() && (finalizedBlock.orElseThrow() < 0 || safeBlock.orElseThrow() < finalizedBlock.orElseThrow())) {
            throw new IllegalArgumentException("finality boundaries are invalid");
        }
    }

    public static EthereumFinalityBoundaries unavailable()
    {
        return new EthereumFinalityBoundaries(OptionalLong.empty(), OptionalLong.empty());
    }

    public EthereumFinality classify(long blockNumber)
    {
        if (blockNumber < 0) {
            throw new IllegalArgumentException("blockNumber is negative");
        }
        if (finalizedBlock.isPresent() && blockNumber <= finalizedBlock.orElseThrow()) {
            return EthereumFinality.FINALIZED;
        }
        if (safeBlock.isPresent() && blockNumber <= safeBlock.orElseThrow()) {
            return EthereumFinality.SAFE;
        }
        return EthereumFinality.HEAD;
    }
}
