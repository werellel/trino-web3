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
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlType;

import java.math.BigInteger;
import java.util.Arrays;

import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static io.trino.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.trino.spi.type.StandardTypes.VARBINARY;

public final class Web3Base58Functions
{
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(ALPHABET.length());

    private Web3Base58Functions() {}

    @ScalarFunction("web3_from_base58")
    @SqlType(VARBINARY)
    public static Slice fromBase58(@SqlType("varchar") Slice value)
    {
        String encoded = value.toStringUtf8();
        if (encoded.isEmpty()) {
            return wrappedBuffer(new byte[0]);
        }

        BigInteger number = BigInteger.ZERO;
        int leadingZeros = 0;
        while (leadingZeros < encoded.length() && encoded.charAt(leadingZeros) == ALPHABET.charAt(0)) {
            leadingZeros++;
        }
        for (int i = 0; i < encoded.length(); i++) {
            int digit = ALPHABET.indexOf(encoded.charAt(i));
            if (digit < 0) {
                throw new TrinoException(INVALID_FUNCTION_ARGUMENT, "Invalid Base58 character at position " + i);
            }
            number = number.multiply(BASE).add(BigInteger.valueOf(digit));
        }

        byte[] magnitude = number.signum() == 0 ? new byte[0] : number.toByteArray();
        if (magnitude.length > 0 && magnitude[0] == 0) {
            magnitude = Arrays.copyOfRange(magnitude, 1, magnitude.length);
        }
        byte[] result = new byte[leadingZeros + magnitude.length];
        System.arraycopy(magnitude, 0, result, leadingZeros, magnitude.length);
        return wrappedBuffer(result);
    }

    @ScalarFunction("web3_to_base58")
    @SqlType("varchar")
    public static Slice toBase58(@SqlType(VARBINARY) Slice value)
    {
        byte[] bytes = value.getBytes();
        int leadingZeros = 0;
        while (leadingZeros < bytes.length && bytes[leadingZeros] == 0) {
            leadingZeros++;
        }
        BigInteger number = bytes.length == 0 ? BigInteger.ZERO : new BigInteger(1, bytes);
        StringBuilder encoded = new StringBuilder();
        while (number.signum() > 0) {
            BigInteger[] division = number.divideAndRemainder(BASE);
            encoded.append(ALPHABET.charAt(division[1].intValue()));
            number = division[0];
        }
        for (int i = 0; i < leadingZeros; i++) {
            encoded.append(ALPHABET.charAt(0));
        }
        return utf8Slice(encoded.reverse().toString());
    }
}
