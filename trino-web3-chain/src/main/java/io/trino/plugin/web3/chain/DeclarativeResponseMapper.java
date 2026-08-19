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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Applies only declared field mappings and ignores unrelated provider fields. */
public final class DeclarativeResponseMapper
{
    private DeclarativeResponseMapper() {}

    public static List<Map<String, JsonNode>> map(RemoteMethodDescriptor method, JsonNode response, int maximumRows)
    {
        requireNonNull(method, "method is null");
        requireNonNull(response, "response is null");
        if (maximumRows < 1 || maximumRows > 1_000_000) {
            throw new IllegalArgumentException("maximumRows must be between 1 and 1000000");
        }
        RemoteMethodDescriptor.ResponseMapping mapping = method.response();
        JsonNode rows = response.at(mapping.rowsPointer());
        if (rows.isMissingNode()) {
            throw new IllegalStateException("remote response rows are missing for operation " + method.name());
        }

        List<JsonNode> rowValues;
        if (mapping.cardinality() == RemoteMethodDescriptor.Cardinality.ARRAY) {
            if (!rows.isArray()) {
                throw new IllegalStateException("remote response rows are not an array for operation " + method.name());
            }
            if (rows.size() > maximumRows) {
                throw new IllegalStateException("remote response row count exceeds limit for operation " + method.name());
            }
            rowValues = new ArrayList<>();
            rows.forEach(rowValues::add);
        }
        else {
            rowValues = List.of(rows);
        }

        List<Map<String, JsonNode>> mappedRows = new ArrayList<>();
        for (JsonNode row : rowValues) {
            Map<String, JsonNode> fields = new LinkedHashMap<>();
            for (RemoteMethodDescriptor.ResponseField field : mapping.fields()) {
                JsonNode value = row.at(field.pointer());
                if (value.isMissingNode()) {
                    if (field.required()) {
                        throw new IllegalStateException("remote response is missing required column " + field.column() + " for operation " + method.name());
                    }
                    value = NullNode.instance;
                }
                fields.put(field.column(), value.deepCopy());
            }
            mappedRows.add(Collections.unmodifiableMap(fields));
        }
        return List.copyOf(mappedRows);
    }
}
