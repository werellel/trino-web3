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
package io.trino.plugin.web3.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** Immutable endpoint-relative REST request value. Transport support is added separately. */
public final class RestRemoteRequest
        implements RemoteRequest
{
    private static final int MAXIMUM_BODY_BYTES = 1_048_576;
    private static final int MAXIMUM_QUERY_VALUES = 1_024;
    private static final Pattern QUERY_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String method;
    private final String path;
    private final Map<String, List<String>> queryParameters;
    private final Optional<JsonNode> body;

    public RestRemoteRequest(String method, String path, Map<String, List<String>> queryParameters, Optional<JsonNode> body)
    {
        this.method = requireNonNull(method, "method is null");
        if (!(method.equals("GET") || method.equals("POST"))) {
            throw new IllegalArgumentException("REST method must be GET or POST");
        }
        this.path = validatePath(path);
        this.queryParameters = immutableQueryParameters(queryParameters);
        requireNonNull(body, "body is null");
        this.body = body.map(JsonNode::deepCopy);
        if (method.equals("GET") && this.body.isPresent()) {
            throw new IllegalArgumentException("REST GET request must not contain a body");
        }
        this.body.ifPresent(RestRemoteRequest::validateBodySize);
    }

    @Override
    public Protocol protocol()
    {
        return Protocol.REST;
    }

    @Override
    public String operationName()
    {
        return method + " " + path;
    }

    public String method()
    {
        return method;
    }

    public String path()
    {
        return path;
    }

    public Map<String, List<String>> queryParameters()
    {
        return queryParameters;
    }

    public Optional<JsonNode> body()
    {
        return body.map(JsonNode::deepCopy);
    }

    private static String validatePath(String path)
    {
        requireNonNull(path, "path is null");
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("\\") || path.length() > 4_096) {
            throw new IllegalArgumentException("REST path must be a bounded endpoint-relative absolute path");
        }
        if (path.chars().anyMatch(character -> Character.isISOControl(character) || Character.isWhitespace(character))) {
            throw new IllegalArgumentException("REST path contains whitespace or control characters");
        }
        try {
            URI uri = new URI(path);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("REST path must be a bounded endpoint-relative absolute path");
            }
        }
        catch (URISyntaxException e) {
            throw new IllegalArgumentException("REST path must be a bounded endpoint-relative absolute path", e);
        }
        return path;
    }

    private static Map<String, List<String>> immutableQueryParameters(Map<String, List<String>> parameters)
    {
        requireNonNull(parameters, "queryParameters is null");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        int valueCount = 0;
        for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
            if (entry.getKey() == null || !QUERY_NAME.matcher(entry.getKey()).matches()) {
                throw new IllegalArgumentException("invalid REST query parameter name");
            }
            List<String> values = List.copyOf(requireNonNull(entry.getValue(), "query parameter values are null"));
            for (String value : values) {
                if (requireNonNull(value, "query parameter value is null").length() > 4_096) {
                    throw new IllegalArgumentException("REST query parameter value exceeds maximum length of 4096");
                }
            }
            valueCount = Math.addExact(valueCount, values.size());
            if (valueCount > MAXIMUM_QUERY_VALUES) {
                throw new IllegalArgumentException("REST query parameter values exceed maximum of " + MAXIMUM_QUERY_VALUES);
            }
            copy.put(entry.getKey(), values);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void validateBodySize(JsonNode body)
    {
        try {
            if (OBJECT_MAPPER.writeValueAsBytes(body).length > MAXIMUM_BODY_BYTES) {
                throw new IllegalArgumentException("REST body exceeds maximum size of 1 MiB");
            }
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("REST body cannot be serialized", e);
        }
    }

    @Override
    public boolean equals(Object other)
    {
        return this == other || (other instanceof RestRemoteRequest request &&
                method.equals(request.method) &&
                path.equals(request.path) &&
                queryParameters.equals(request.queryParameters) &&
                body.equals(request.body));
    }

    @Override
    public int hashCode()
    {
        return java.util.Objects.hash(method, path, queryParameters, body);
    }

    @Override
    public String toString()
    {
        return "RestRemoteRequest{operation=" + operationName() + ", queryParameters=" + queryParameters.size() + ", bodyPresent=" + body.isPresent() + "}";
    }
}
