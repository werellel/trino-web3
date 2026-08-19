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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** A restricted, read-only remote operation definition. */
public record RemoteMethodDescriptor(
        String name,
        Protocol protocol,
        String action,
        String path,
        List<RequestBinding> bindings,
        ResponseMapping response)
{
    private static final int MAXIMUM_BINDINGS = 128;

    public RemoteMethodDescriptor
    {
        name = DescriptorValidation.logicalName(name, "method name");
        protocol = requireNonNull(protocol, "protocol is null");
        action = DescriptorValidation.nonBlank(action, "method action", 256);
        path = requireNonNull(path, "path is null");
        bindings = List.copyOf(requireNonNull(bindings, "bindings is null"));
        response = requireNonNull(response, "response is null");
        if (bindings.size() > MAXIMUM_BINDINGS) {
            throw new IllegalArgumentException("method bindings exceed maximum of " + MAXIMUM_BINDINGS);
        }
        if (protocol == Protocol.JSON_RPC) {
            if (!path.isEmpty()) {
                throw new IllegalArgumentException("JSON-RPC method path must be empty");
            }
            if (bindings.stream().anyMatch(binding -> binding.location() != RequestLocation.PARAMETER)) {
                throw new IllegalArgumentException("JSON-RPC bindings must use PARAMETER location");
            }
        }
        else {
            String restPath = path;
            if (!(action.equals("GET") || action.equals("POST"))) {
                throw new IllegalArgumentException("REST action must be GET or POST");
            }
            if (!restPath.startsWith("/") || restPath.contains("?") || restPath.contains("#") || restPath.contains("://")) {
                throw new IllegalArgumentException("REST path must be an absolute path without query, fragment, or scheme");
            }
            if (bindings.stream().anyMatch(binding -> binding.location() == RequestLocation.PARAMETER)) {
                throw new IllegalArgumentException("REST bindings must not use PARAMETER location");
            }
            bindings.stream()
                    .filter(binding -> binding.location() == RequestLocation.PATH)
                    .filter(binding -> !restPath.contains("{" + binding.target() + "}"))
                    .findFirst()
                    .ifPresent(binding -> {
                        throw new IllegalArgumentException("REST path does not contain binding target " + binding.target());
                    });
        }
        Set<String> targets = new HashSet<>();
        for (RequestBinding binding : bindings) {
            String key = binding.location() + ":" + binding.target();
            if (!targets.add(key)) {
                throw new IllegalArgumentException("duplicate request binding target " + binding.target());
            }
            if (protocol == Protocol.JSON_RPC) {
                try {
                    if (Integer.parseInt(binding.target()) < 0) {
                        throw new IllegalArgumentException("JSON-RPC parameter target is negative");
                    }
                }
                catch (NumberFormatException e) {
                    throw new IllegalArgumentException("JSON-RPC parameter target must be a non-negative integer", e);
                }
            }
        }
    }

    public enum Protocol
    {
        JSON_RPC,
        REST,
    }

    public enum RequestKind
    {
        SPLIT,
        PREDICATE,
        PROJECTION,
        LITERAL,
    }

    public enum RequestLocation
    {
        PARAMETER,
        PATH,
        QUERY,
        BODY,
    }

    public enum Cardinality
    {
        SINGLE,
        ARRAY,
    }

    public record RequestBinding(String target, RequestKind kind, String value, RequestLocation location, boolean required)
    {
        public RequestBinding
        {
            target = DescriptorValidation.nonBlank(target, "request binding target", 128);
            kind = requireNonNull(kind, "request binding kind is null");
            location = requireNonNull(location, "request binding location is null");
            value = kind == RequestKind.LITERAL ?
                    DescriptorValidation.jsonLiteral(value, "request literal") :
                    DescriptorValidation.sqlIdentifier(value, "request binding source");
        }
    }

    public record ResponseMapping(Cardinality cardinality, String rowsPointer, List<ResponseField> fields)
    {
        public ResponseMapping
        {
            cardinality = requireNonNull(cardinality, "response cardinality is null");
            rowsPointer = DescriptorValidation.jsonPointer(rowsPointer, "response rowsPointer");
            fields = List.copyOf(requireNonNull(fields, "response fields is null"));
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("response fields are empty");
            }
            if (fields.size() > 512) {
                throw new IllegalArgumentException("response fields exceed maximum of 512");
            }
            Set<String> columns = new HashSet<>();
            for (ResponseField field : fields) {
                if (!columns.add(field.column())) {
                    throw new IllegalArgumentException("duplicate response field for column " + field.column());
                }
            }
        }
    }

    public record ResponseField(String column, String pointer, boolean required)
    {
        public ResponseField
        {
            column = DescriptorValidation.sqlIdentifier(column, "response column");
            pointer = DescriptorValidation.jsonPointer(pointer, "response field pointer");
        }
    }
}
