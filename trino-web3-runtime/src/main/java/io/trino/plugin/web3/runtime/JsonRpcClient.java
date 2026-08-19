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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;

public final class JsonRpcClient
        implements JsonRpcTransport
{
    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final int maximumRequestBytes;
    private final int maximumResponseBytes;
    private final ObjectMapper objectMapper;

    public JsonRpcClient(
            HttpClient httpClient,
            URI endpoint,
            Duration requestTimeout,
            int maximumRequestBytes,
            int maximumResponseBytes)
    {
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.endpoint = requireNonNull(endpoint, "endpoint is null");
        this.requestTimeout = requireNonNull(requestTimeout, "requestTimeout is null");
        if (maximumRequestBytes < 1 || maximumResponseBytes < 1) {
            throw new IllegalArgumentException("RPC message size limits must be positive");
        }
        this.maximumRequestBytes = maximumRequestBytes;
        this.maximumResponseBytes = maximumResponseBytes;
        objectMapper = new ObjectMapper();
    }

    @Override
    public CompletableFuture<JsonNode> execute(JsonRpcRequest request)
    {
        requireNonNull(request, "request is null");
        return executeRequest(request, response -> parseResponse(response, request));
    }

    @Override
    public CompletableFuture<List<JsonNode>> executeBatch(List<JsonRpcRequest> requests)
    {
        if (requests.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return executeRequest(requests, response -> parseResponses(response, requests));
    }

    private <T> CompletableFuture<T> executeRequest(Object requestValue, java.util.function.Function<HttpResponse<InputStream>, T> parser)
    {
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(requestValue);
        }
        catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (payload.length > maximumRequestBytes) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("JSON-RPC request exceeds maximumRequestBytes"));
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        CompletableFuture<HttpResponse<InputStream>> response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        CompletableFuture<T> results = response.thenApply(parser);
        results.whenComplete((value, failure) -> {
            if (results.isCancelled()) {
                response.cancel(true);
            }
        });
        return results;
    }

    private JsonNode parseResponse(HttpResponse<InputStream> response, JsonRpcRequest request)
    {
        JsonNode responseNode = readResponse(response);
        return parseResponseNode(responseNode, Map.of(request.id(), request));
    }

    private List<JsonNode> parseResponses(HttpResponse<InputStream> response, List<JsonRpcRequest> requests)
    {
        JsonNode responseNodes = readResponse(response);
        if (!responseNodes.isArray()) {
            throw new IllegalStateException("JSON-RPC batch response is not an array");
        }

        Map<Long, JsonNode> responsesById = new HashMap<>();
        Map<Long, JsonRpcRequest> requestsById = new HashMap<>();
        for (JsonRpcRequest request : requests) {
            if (requestsById.put(request.id(), request) != null) {
                throw new IllegalArgumentException("JSON-RPC batch contains a duplicate request id " + request.id());
            }
        }
        for (JsonNode responseNode : responseNodes) {
            long id = responseId(responseNode, requestsById);
            if (responsesById.put(id, parseResponseNode(responseNode, requestsById)) != null) {
                throw new IllegalStateException("JSON-RPC response contains a duplicate id " + id);
            }
        }
        return requests.stream()
                .map(request -> {
                    JsonNode result = responsesById.get(request.id());
                    if (result == null) {
                        throw new IllegalStateException("JSON-RPC response is missing id " + request.id());
                    }
                    return result;
                })
                .toList();
    }

    private JsonNode readResponse(HttpResponse<InputStream> response)
    {
        if (response.statusCode() != 200) {
            throw new JsonRpcHttpException(response.statusCode(), response.headers().firstValue("Retry-After"));
        }
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(Math.addExact(maximumResponseBytes, 1));
            if (bytes.length > maximumResponseBytes) {
                throw new IllegalStateException("JSON-RPC response exceeds maximumResponseBytes");
            }
            return objectMapper.readTree(bytes);
        }
        catch (IOException e) {
            throw new IllegalStateException("JSON-RPC endpoint returned malformed JSON", e);
        }
    }

    private static JsonNode parseResponseNode(JsonNode responseNode, Map<Long, JsonRpcRequest> requestsById)
    {
        long id = responseId(responseNode, requestsById);
        if (responseNode.has("error") == responseNode.has("result")) {
            throw new IllegalStateException("JSON-RPC response must contain exactly one of result or error");
        }
        if (responseNode.has("error")) {
            JsonNode code = responseNode.path("error").get("code");
            if (code == null || !code.canConvertToInt()) {
                throw new IllegalStateException("JSON-RPC response error has no numeric code");
            }
            throw new JsonRpcResponseException(code.asInt(), id);
        }
        return responseNode.get("result");
    }

    private static long responseId(JsonNode responseNode, Map<Long, JsonRpcRequest> requestsById)
    {
        JsonNode id = responseNode.get("id");
        if (id == null || !id.canConvertToLong()) {
            throw new IllegalStateException("JSON-RPC response has no numeric id");
        }
        if (!requestsById.containsKey(id.asLong())) {
            throw new IllegalStateException("JSON-RPC response contains an unexpected id " + id.asLong());
        }
        return id.asLong();
    }

    public record JsonRpcRequest(long id, String method, List<Object> params)
    {
        public JsonRpcRequest
        {
            requireNonNull(method, "method is null");
            requireNonNull(params, "params is null");
        }

        @JsonProperty("jsonrpc")
        public String jsonrpc()
        {
            return "2.0";
        }
    }

    public static final class JsonRpcHttpException
            extends RemoteHttpException
    {
        private static final long serialVersionUID = 1L;

        public JsonRpcHttpException(int statusCode, Optional<String> retryAfter)
        {
            super("JSON-RPC endpoint returned HTTP " + statusCode, statusCode, retryAfter);
        }
    }

    public static final class JsonRpcResponseException
            extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        private final int errorCode;

        public JsonRpcResponseException(int errorCode, long requestId)
        {
            super("JSON-RPC endpoint returned error code " + errorCode + " for id " + requestId);
            this.errorCode = errorCode;
        }

        public int errorCode()
        {
            return errorCode;
        }
    }
}
