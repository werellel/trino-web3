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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.util.Objects.requireNonNull;

public final class JsonRpcClient
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

    public CompletableFuture<List<JsonNode>> executeBatch(List<JsonRpcRequest> requests)
    {
        if (requests.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(requests);
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
        CompletableFuture<List<JsonNode>> results = response.thenApply(value -> parseResponses(value, requests));
        results.whenComplete((value, failure) -> {
            if (results.isCancelled()) {
                response.cancel(true);
            }
        });
        return results;
    }

    private List<JsonNode> parseResponses(HttpResponse<InputStream> response, List<JsonRpcRequest> requests)
    {
        if (response.statusCode() != 200) {
            throw new IllegalStateException("JSON-RPC endpoint returned HTTP " + response.statusCode());
        }
        JsonNode responseNodes;
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(Math.addExact(maximumResponseBytes, 1));
            if (bytes.length > maximumResponseBytes) {
                throw new IllegalStateException("JSON-RPC response exceeds maximumResponseBytes");
            }
            responseNodes = objectMapper.readTree(bytes);
        }
        catch (IOException e) {
            throw new IllegalStateException("JSON-RPC endpoint returned malformed JSON", e);
        }
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
            JsonNode id = responseNode.get("id");
            if (id == null || !id.canConvertToLong()) {
                throw new IllegalStateException("JSON-RPC response has no numeric id");
            }
            if (!requestsById.containsKey(id.asLong())) {
                throw new IllegalStateException("JSON-RPC response contains an unexpected id " + id.asLong());
            }
            if (responseNode.has("error") == responseNode.has("result")) {
                throw new IllegalStateException("JSON-RPC response must contain exactly one of result or error");
            }
            if (responseNode.has("error")) {
                throw new IllegalStateException("JSON-RPC response contains an error for id " + id.asLong());
            }
            if (responsesById.containsKey(id.asLong())) {
                throw new IllegalStateException("JSON-RPC response contains a duplicate id " + id.asLong());
            }
            responsesById.put(id.asLong(), responseNode.get("result"));
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
}
