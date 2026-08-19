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

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** One immutable decoded row keyed by descriptor column name. */
public final class ChainRow
{
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    private final Map<String, JsonNode> values;

    public ChainRow(Map<String, JsonNode> values)
    {
        requireNonNull(values, "values is null");
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        values.forEach((column, value) -> {
            if (column == null || !SQL_IDENTIFIER.matcher(column).matches()) {
                throw new IllegalArgumentException("row column must be a lower-case SQL identifier");
            }
            copy.put(column, requireNonNull(value, "row value is null").deepCopy());
        });
        this.values = Collections.unmodifiableMap(copy);
    }

    public JsonNode value(String column)
    {
        requireNonNull(column, "column is null");
        if (!values.containsKey(column)) {
            throw new IllegalStateException("chain row is missing declared column " + column);
        }
        return values.get(column).deepCopy();
    }

    public Map<String, JsonNode> values()
    {
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        values.forEach((column, value) -> copy.put(column, value.deepCopy()));
        return Collections.unmodifiableMap(copy);
    }

    public long retainedSizeInBytes()
    {
        long size = 48;
        for (Map.Entry<String, JsonNode> entry : values.entrySet()) {
            size = Math.addExact(size, 40L + (long) entry.getKey().length() * Character.BYTES);
            size = Math.addExact(size, estimatedNodeSize(entry.getValue()));
        }
        return size;
    }

    private static long estimatedNodeSize(JsonNode node)
    {
        if (node.isTextual()) {
            return 40L + (long) node.textValue().length() * Character.BYTES;
        }
        if (node.isNumber() || node.isBoolean() || node.isNull()) {
            return 24;
        }
        long size = 32;
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            size = Math.addExact(size, 40L + (long) field.getKey().length() * Character.BYTES);
            size = Math.addExact(size, estimatedNodeSize(field.getValue()));
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                size = Math.addExact(size, estimatedNodeSize(element));
            }
        }
        return size;
    }
}
