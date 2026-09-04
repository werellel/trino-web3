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

import java.math.BigInteger;
import java.util.Arrays;

import static java.util.Objects.requireNonNull;

final class Web3IntegerCodec
{
    static final int BYTES = 32;
    static final BigInteger UINT256_MAX = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
    static final BigInteger INT256_MIN = BigInteger.ONE.shiftLeft(255).negate();
    static final BigInteger INT256_MAX = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE);

    private Web3IntegerCodec() {}

    static BigInteger decodeUnsigned(Slice value)
    {
        return new BigInteger(1, bytes(value));
    }

    static BigInteger decodeSigned(Slice value)
    {
        return new BigInteger(bytes(value));
    }

    static Slice encodeUnsigned(BigInteger value)
    {
        requireRange(value, BigInteger.ZERO, UINT256_MAX, "UINT256");
        return encode(value, false);
    }

    static Slice encodeSigned(BigInteger value)
    {
        requireRange(value, INT256_MIN, INT256_MAX, "INT256");
        return encode(value, true);
    }

    static Slice decodeBytes(Slice value, boolean signed)
    {
        requireNonNull(value, "value is null");
        if (value.length() > BYTES) {
            throw new IllegalArgumentException("value exceeds 32 bytes");
        }
        byte[] input = value.getBytes();
        byte fill = signed && input.length > 0 && (input[0] & 0x80) != 0 ? (byte) 0xFF : 0;
        byte[] padded = new byte[BYTES];
        Arrays.fill(padded, fill);
        System.arraycopy(input, 0, padded, BYTES - input.length, input.length);
        return signed ? encodeSigned(new BigInteger(padded)) : encodeUnsigned(new BigInteger(1, padded));
    }

    static Slice encode(BigInteger value, boolean signed)
    {
        byte[] source = value.toByteArray();
        byte[] result = new byte[BYTES];
        byte fill = signed && value.signum() < 0 ? (byte) 0xFF : 0;
        Arrays.fill(result, fill);
        int sourceOffset = Math.max(0, source.length - BYTES);
        int length = Math.min(source.length, BYTES);
        System.arraycopy(source, sourceOffset, result, BYTES - length, length);
        return io.airlift.slice.Slices.wrappedBuffer(result);
    }

    private static void requireRange(BigInteger value, BigInteger min, BigInteger max, String type)
    {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new ArithmeticException(type + " overflow");
        }
    }

    private static byte[] bytes(Slice value)
    {
        requireNonNull(value, "value is null");
        if (value.length() != BYTES) {
            throw new IllegalArgumentException("canonical integer value must be exactly 32 bytes");
        }
        return value.getBytes();
    }
}
