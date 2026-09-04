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
public final class Int256TrySumAggregations
{
    private Int256TrySumAggregations() {}

    @InputFunction
    public static void input(@AggregationState Int256TrySumState state, @SqlType("int256") Slice value)
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
            state.setValue(Web3IntegerCodec.encodeSigned(
                    Web3IntegerCodec.decodeSigned(state.getValue()).add(Web3IntegerCodec.decodeSigned(value))));
        }
        catch (ArithmeticException e) {
            state.setOverflow(true);
        }
    }

    @CombineFunction
    public static void combine(@AggregationState Int256TrySumState state, @AggregationState Int256TrySumState otherState)
    {
        if (otherState.isOverflow()) {
            state.setOverflow(true);
            return;
        }
        if (!otherState.isNull()) {
            input(state, otherState.getValue());
        }
    }

    @OutputFunction("int256")
    public static void output(@AggregationState Int256TrySumState state, BlockBuilder out)
    {
        if (state.isNull() || state.isOverflow()) {
            out.appendNull();
        }
        else {
            Int256Type.INT256.writeSlice(out, state.getValue());
        }
    }
}
