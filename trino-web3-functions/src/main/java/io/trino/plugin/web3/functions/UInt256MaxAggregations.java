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
package io.trino.plugin.web3.functions;

import io.airlift.slice.Slice;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.function.AggregationFunction;
import io.trino.spi.function.AggregationState;
import io.trino.spi.function.CombineFunction;
import io.trino.spi.function.InputFunction;
import io.trino.spi.function.OutputFunction;
import io.trino.spi.function.SqlType;

@AggregationFunction("max")
public final class UInt256MaxAggregations
{
    private UInt256MaxAggregations() {}

    @InputFunction
    public static void input(@AggregationState UInt256State state, @SqlType("uint256") Slice value)
    {
        if (state.isNull() || value.compareTo(state.getValue()) > 0) {
            state.setValue(value);
            state.setNull(false);
        }
    }

    @CombineFunction
    public static void combine(@AggregationState UInt256State state, @AggregationState UInt256State otherState)
    {
        if (!otherState.isNull()) {
            input(state, otherState.getValue());
        }
    }

    @OutputFunction("uint256")
    public static void output(@AggregationState UInt256State state, BlockBuilder out)
    {
        if (state.isNull()) {
            out.appendNull();
        }
        else {
            UInt256Type.UINT256.writeSlice(out, state.getValue());
        }
    }
}
