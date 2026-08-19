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

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

import static java.lang.Long.parseUnsignedLong;

final class EthereumJson
{
    private EthereumJson() {}

    public static long requiredQuantity(JsonNode node, String fieldName)
    {
        String value = requiredText(node, fieldName);
        if (!value.matches("0x(0|[1-9a-fA-F][0-9a-fA-F]*)")) {
            throw new IllegalStateException("Ethereum " + fieldName + " is not a canonical hexadecimal quantity");
        }
        return parseUnsignedLong(value.substring(2), 16);
    }

    public static String requiredText(JsonNode node, String fieldName)
    {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("Ethereum response is missing " + fieldName);
        }
        return value.textValue();
    }

    public static String normalizeHash(String value, String fieldName)
    {
        if (value == null || !value.matches("0x[0-9a-fA-F]{64}")) {
            throw new IllegalStateException("Ethereum " + fieldName + " is not a 32-byte hexadecimal hash");
        }
        return value.toLowerCase(Locale.ENGLISH);
    }

    public static String toQuantity(long value)
    {
        if (value < 0) {
            throw new IllegalArgumentException("value is negative");
        }
        return "0x" + Long.toHexString(value);
    }
}
