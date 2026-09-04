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
import io.trino.spi.function.ScalarOperator;

import static io.trino.spi.function.OperatorType.COMPARISON_UNORDERED_LAST;
import static io.trino.spi.function.OperatorType.LESS_THAN;
import static io.trino.spi.function.OperatorType.LESS_THAN_OR_EQUAL;

public final class UInt256Type
        extends Web3Int256Type
{
    public static final UInt256Type UINT256 = new UInt256Type();

    private UInt256Type()
    {
        super("uint256", false, Operators.class);
    }

    private static final class Operators
    {
        @ScalarOperator(COMPARISON_UNORDERED_LAST)
        public static long compare(Slice left, Slice right)
        {
            return left.compareTo(right);
        }

        @ScalarOperator(LESS_THAN)
        public static boolean lessThan(Slice left, Slice right)
        {
            return left.compareTo(right) < 0;
        }

        @ScalarOperator(LESS_THAN_OR_EQUAL)
        public static boolean lessThanOrEqual(Slice left, Slice right)
        {
            return left.compareTo(right) <= 0;
        }
    }
}
