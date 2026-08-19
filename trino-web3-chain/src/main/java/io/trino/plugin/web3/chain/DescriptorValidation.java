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
package io.trino.plugin.web3.chain;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

final class DescriptorValidation
{
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final Pattern LOGICAL_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,127}");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private DescriptorValidation() {}

    public static String sqlIdentifier(String value, String field)
    {
        if (value == null || !SQL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lower-case SQL identifier");
        }
        return value;
    }

    public static String logicalName(String value, String field)
    {
        if (value == null || !LOGICAL_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lower-case logical name");
        }
        return value;
    }

    public static String nonBlank(String value, String field, int maximumLength)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is blank");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds maximum length of " + maximumLength);
        }
        return value;
    }

    public static String jsonPointer(String value, String field)
    {
        if (value == null) {
            throw new IllegalArgumentException(field + " is null");
        }
        if (value.length() > 2_048) {
            throw new IllegalArgumentException(field + " exceeds maximum length of 2048");
        }
        try {
            JsonPointer.compile(value);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " is not a valid JSON pointer", e);
        }
        return value;
    }

    public static String jsonLiteral(String value, String field)
    {
        nonBlank(value, field, 4_096);
        try {
            OBJECT_MAPPER.readTree(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(field + " is not valid JSON", e);
        }
        return value;
    }
}
