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
package io.trino.plugin.web3.adapter;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** A bounded range scoped by a small, immutable set of chain-native keys. */
public record KeyedRangeChainSplit(Map<String, String> keys, String rangeColumn, long startInclusive, long endInclusive)
        implements ChainSplit
{
    private static final Pattern COLUMN_NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final int MAXIMUM_KEYS = 8;
    private static final int MAXIMUM_VALUE_LENGTH = 4_096;

    public KeyedRangeChainSplit
    {
        requireNonNull(keys, "keys is null");
        if (keys.isEmpty() || keys.size() > MAXIMUM_KEYS) {
            throw new IllegalArgumentException("split keys must contain between 1 and " + MAXIMUM_KEYS + " entries");
        }
        Map<String, String> copy = new TreeMap<>();
        keys.forEach((key, value) -> {
            if (key == null || !COLUMN_NAME.matcher(key).matches()) {
                throw new IllegalArgumentException("split key must be a lower-case SQL identifier");
            }
            value = requireNonNull(value, "split key value is null");
            if (value.isEmpty() || value.length() > MAXIMUM_VALUE_LENGTH) {
                throw new IllegalArgumentException("split key value has an invalid length");
            }
            copy.put(key, value);
        });
        keys = Collections.unmodifiableMap(copy);
        if (rangeColumn == null || !COLUMN_NAME.matcher(rangeColumn).matches()) {
            throw new IllegalArgumentException("rangeColumn must be a lower-case SQL identifier");
        }
        if (startInclusive < 0 || endInclusive < startInclusive) {
            throw new IllegalArgumentException("invalid non-negative split range");
        }
    }
}
