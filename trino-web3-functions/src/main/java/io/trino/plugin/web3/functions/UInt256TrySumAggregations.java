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

@AggregationFunction("try_sum")
public final class UInt256TrySumAggregations
{
    private UInt256TrySumAggregations() {}

    @InputFunction
    public static void input(@AggregationState UInt256TrySumState state, @SqlType("uint256") Slice value)
    {
        if (state.isOverflow()) {
            return;
        }
        if (state.isNull()) {
            state.setValue(value);
            state.setNull(false);
            return;
        }
        try {
            state.setValue(Web3IntegerCodec.encodeUnsigned(
                    Web3IntegerCodec.decodeUnsigned(state.getValue()).add(Web3IntegerCodec.decodeUnsigned(value))));
        }
        catch (ArithmeticException e) {
            state.setOverflow(true);
        }
    }

    @CombineFunction
    public static void combine(@AggregationState UInt256TrySumState state, @AggregationState UInt256TrySumState otherState)
    {
        if (otherState.isOverflow()) {
            state.setOverflow(true);
            return;
        }
        if (!otherState.isNull()) {
            input(state, otherState.getValue());
        }
    }

    @OutputFunction("uint256")
    public static void output(@AggregationState UInt256TrySumState state, BlockBuilder out)
    {
        if (state.isNull() || state.isOverflow()) {
            out.appendNull();
        }
        else {
            UInt256Type.UINT256.writeSlice(out, state.getValue());
        }
    }
}
