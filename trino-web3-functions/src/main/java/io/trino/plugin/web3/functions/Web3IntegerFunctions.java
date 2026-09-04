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
import io.trino.spi.TrinoException;
import io.trino.spi.function.LiteralParameter;
import io.trino.spi.function.LiteralParameters;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.ScalarOperator;
import io.trino.spi.function.SqlType;

import java.math.BigInteger;

import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.spi.StandardErrorCode.DIVISION_BY_ZERO;
import static io.trino.spi.StandardErrorCode.INVALID_CAST_ARGUMENT;
import static io.trino.spi.StandardErrorCode.NUMERIC_VALUE_OUT_OF_RANGE;
import static io.trino.spi.function.OperatorType.ADD;
import static io.trino.spi.function.OperatorType.CAST;
import static io.trino.spi.function.OperatorType.DIVIDE;
import static io.trino.spi.function.OperatorType.MODULUS;
import static io.trino.spi.function.OperatorType.MULTIPLY;
import static io.trino.spi.function.OperatorType.NEGATION;
import static io.trino.spi.function.OperatorType.SUBTRACT;
import static io.trino.spi.type.StandardTypes.VARBINARY;
import static java.lang.String.format;

public final class Web3IntegerFunctions
{
    private Web3IntegerFunctions() {}

    @ScalarFunction("web3_uint256")
    @SqlType("uint256")
    public static Slice uint256(@SqlType("varchar") Slice value)
    {
        return parse(value, false);
    }

    @ScalarFunction("web3_int256")
    @SqlType("int256")
    public static Slice int256(@SqlType("varchar") Slice value)
    {
        return parse(value, true);
    }

    @ScalarFunction("web3_varbinary_to_uint256")
    @SqlType("uint256")
    public static Slice varbinaryToUInt256(@SqlType(VARBINARY) Slice value)
    {
        return Web3IntegerCodec.decodeBytes(value, false);
    }

    @ScalarFunction("web3_varbinary_to_int256")
    @SqlType("int256")
    public static Slice varbinaryToInt256(@SqlType(VARBINARY) Slice value)
    {
        return Web3IntegerCodec.decodeBytes(value, true);
    }

    @ScalarFunction("web3_uint256_to_decimal")
    @SqlType("varchar")
    public static Slice uint256ToDecimal(@SqlType("uint256") Slice value)
    {
        return utf8Slice(Web3IntegerCodec.decodeUnsigned(value).toString());
    }

    @ScalarFunction("web3_int256_to_decimal")
    @SqlType("varchar")
    public static Slice int256ToDecimal(@SqlType("int256") Slice value)
    {
        return utf8Slice(Web3IntegerCodec.decodeSigned(value).toString());
    }

    @ScalarOperator(CAST)
    @SqlType(VARBINARY)
    public static Slice castUInt256ToVarbinary(@SqlType("uint256") Slice value)
    {
        return value;
    }

    @ScalarOperator(CAST)
    @SqlType(VARBINARY)
    public static Slice castInt256ToVarbinary(@SqlType("int256") Slice value)
    {
        return value;
    }

    @ScalarOperator(CAST)
    @SqlType("uint256")
    public static Slice castVarbinaryToUInt256(@SqlType(VARBINARY) Slice value)
    {
        return Web3IntegerCodec.decodeBytes(value, false);
    }

    @ScalarOperator(CAST)
    @SqlType("int256")
    public static Slice castVarbinaryToInt256(@SqlType(VARBINARY) Slice value)
    {
        return Web3IntegerCodec.decodeBytes(value, true);
    }

    @ScalarOperator(CAST)
    @LiteralParameters("x")
    @SqlType("varchar(x)")
    public static Slice castUInt256ToVarchar(@LiteralParameter("x") long length, @SqlType("uint256") Slice value)
    {
        return castToVarchar(length, Web3IntegerCodec.decodeUnsigned(value));
    }

    @ScalarOperator(CAST)
    @LiteralParameters("x")
    @SqlType("varchar(x)")
    public static Slice castInt256ToVarchar(@LiteralParameter("x") long length, @SqlType("int256") Slice value)
    {
        return castToVarchar(length, Web3IntegerCodec.decodeSigned(value));
    }

    @ScalarOperator(ADD)
    @SqlType("uint256")
    public static Slice addUInt256(@SqlType("uint256") Slice left, @SqlType("uint256") Slice right)
    {
        return unsignedBinary(left, right, BigInteger::add, "addition");
    }

    @ScalarOperator(SUBTRACT)
    @SqlType("uint256")
    public static Slice subtractUInt256(@SqlType("uint256") Slice left, @SqlType("uint256") Slice right)
    {
        return unsignedBinary(left, right, BigInteger::subtract, "subtraction");
    }

    @ScalarOperator(MULTIPLY)
    @SqlType("uint256")
    public static Slice multiplyUInt256(@SqlType("uint256") Slice left, @SqlType("uint256") Slice right)
    {
        return unsignedBinary(left, right, BigInteger::multiply, "multiplication");
    }

    @ScalarOperator(DIVIDE)
    @SqlType("uint256")
    public static Slice divideUInt256(@SqlType("uint256") Slice left, @SqlType("uint256") Slice right)
    {
        return unsignedDivide(left, right, false);
    }

    @ScalarOperator(MODULUS)
    @SqlType("uint256")
    public static Slice modulusUInt256(@SqlType("uint256") Slice left, @SqlType("uint256") Slice right)
    {
        return unsignedDivide(left, right, true);
    }

    @ScalarOperator(ADD)
    @SqlType("int256")
    public static Slice addInt256(@SqlType("int256") Slice left, @SqlType("int256") Slice right)
    {
        return signedBinary(left, right, BigInteger::add, "addition");
    }

    @ScalarOperator(SUBTRACT)
    @SqlType("int256")
    public static Slice subtractInt256(@SqlType("int256") Slice left, @SqlType("int256") Slice right)
    {
        return signedBinary(left, right, BigInteger::subtract, "subtraction");
    }

    @ScalarOperator(MULTIPLY)
    @SqlType("int256")
    public static Slice multiplyInt256(@SqlType("int256") Slice left, @SqlType("int256") Slice right)
    {
        return signedBinary(left, right, BigInteger::multiply, "multiplication");
    }

    @ScalarOperator(DIVIDE)
    @SqlType("int256")
    public static Slice divideInt256(@SqlType("int256") Slice left, @SqlType("int256") Slice right)
    {
        return signedDivide(left, right, false);
    }

    @ScalarOperator(MODULUS)
    @SqlType("int256")
    public static Slice modulusInt256(@SqlType("int256") Slice left, @SqlType("int256") Slice right)
    {
        return signedDivide(left, right, true);
    }

    @ScalarOperator(NEGATION)
    @SqlType("int256")
    public static Slice negateInt256(@SqlType("int256") Slice value)
    {
        try {
            BigInteger decoded = Web3IntegerCodec.decodeSigned(value);
            if (decoded.equals(Web3IntegerCodec.INT256_MIN)) {
                throw new ArithmeticException("INT256 negation overflow");
            }
            return Web3IntegerCodec.encodeSigned(decoded.negate());
        }
        catch (ArithmeticException e) {
            throw overflow("negation", e);
        }
    }

    private static Slice parse(Slice value, boolean signed)
    {
        String text = value.toStringUtf8().trim();
        if (text.isEmpty() || text.startsWith("-")) {
            if (!signed) {
                throw invalid(text);
            }
        }
        try {
            BigInteger parsed = new BigInteger(text);
            return signed ? Web3IntegerCodec.encodeSigned(parsed) : Web3IntegerCodec.encodeUnsigned(parsed);
        }
        catch (NumberFormatException | ArithmeticException e) {
            throw invalid(text, e);
        }
    }

    private static Slice unsignedBinary(Slice left, Slice right, java.util.function.BinaryOperator<BigInteger> operation, String name)
    {
        try {
            return Web3IntegerCodec.encodeUnsigned(operation.apply(Web3IntegerCodec.decodeUnsigned(left), Web3IntegerCodec.decodeUnsigned(right)));
        }
        catch (ArithmeticException e) {
            throw overflow(name, e);
        }
    }

    private static Slice signedBinary(Slice left, Slice right, java.util.function.BinaryOperator<BigInteger> operation, String name)
    {
        try {
            return Web3IntegerCodec.encodeSigned(operation.apply(Web3IntegerCodec.decodeSigned(left), Web3IntegerCodec.decodeSigned(right)));
        }
        catch (ArithmeticException e) {
            throw overflow(name, e);
        }
    }

    private static Slice unsignedDivide(Slice left, Slice right, boolean modulus)
    {
        BigInteger divisor = Web3IntegerCodec.decodeUnsigned(right);
        if (divisor.signum() == 0) {
            throw new TrinoException(DIVISION_BY_ZERO, "UINT256 division by zero");
        }
        BigInteger dividend = Web3IntegerCodec.decodeUnsigned(left);
        return Web3IntegerCodec.encodeUnsigned(modulus ? dividend.remainder(divisor) : dividend.divide(divisor));
    }

    private static Slice signedDivide(Slice left, Slice right, boolean modulus)
    {
        BigInteger divisor = Web3IntegerCodec.decodeSigned(right);
        if (divisor.signum() == 0) {
            throw new TrinoException(DIVISION_BY_ZERO, "INT256 division by zero");
        }
        try {
            BigInteger dividend = Web3IntegerCodec.decodeSigned(left);
            return Web3IntegerCodec.encodeSigned(modulus ? dividend.remainder(divisor) : dividend.divide(divisor));
        }
        catch (ArithmeticException e) {
            throw overflow("division", e);
        }
    }

    private static Slice castToVarchar(long length, BigInteger value)
    {
        String text = value.toString();
        if (text.length() > length) {
            throw new TrinoException(INVALID_CAST_ARGUMENT, format("Value %s cannot be represented as varchar(%s)", value, length));
        }
        return utf8Slice(text);
    }

    private static TrinoException invalid(String value)
    {
        return invalid(value, null);
    }

    private static TrinoException invalid(String value, Throwable cause)
    {
        return new TrinoException(INVALID_CAST_ARGUMENT, "Invalid 256-bit integer: " + value, cause);
    }

    private static TrinoException overflow(String operation, Throwable cause)
    {
        return new TrinoException(NUMERIC_VALUE_OUT_OF_RANGE, "256-bit integer " + operation + " overflow", cause);
    }
}
