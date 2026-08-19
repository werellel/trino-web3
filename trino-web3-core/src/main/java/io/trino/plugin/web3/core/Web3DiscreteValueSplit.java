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
package io.trino.plugin.web3.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.connector.ConnectorSplit;

import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

public record Web3DiscreteValueSplit(String column, String value)
        implements ConnectorSplit
{
    private static final Pattern COLUMN_NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    @JsonCreator
    public Web3DiscreteValueSplit(
            @JsonProperty("column") String column,
            @JsonProperty("value") String value)
    {
        if (column == null || !COLUMN_NAME.matcher(column).matches()) {
            throw new IllegalArgumentException("column must be a lower-case SQL identifier");
        }
        this.column = column;
        this.value = requireNonNull(value, "value is null");
        if (value.length() > 4_096) {
            throw new IllegalArgumentException("split value exceeds maximum length of 4096");
        }
    }
}
