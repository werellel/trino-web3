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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/** Bounded asynchronous transport for read-oriented JSON REST endpoints. */
public final class RestClient
        implements RestTransport
{
    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final int maximumRequestBytes;
    private final int maximumResponseBytes;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RestClient(
            HttpClient httpClient,
            URI endpoint,
            Duration requestTimeout,
            int maximumRequestBytes,
            int maximumResponseBytes)
    {
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.endpoint = validateEndpoint(endpoint);
        this.requestTimeout = requireNonNull(requestTimeout, "requestTimeout is null");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maximumRequestBytes < 1 || maximumResponseBytes < 1) {
            throw new IllegalArgumentException("REST message size limits must be positive");
        }
        this.maximumRequestBytes = maximumRequestBytes;
        this.maximumResponseBytes = maximumResponseBytes;
    }

    @Override
    public CompletableFuture<JsonNode> execute(RestRemoteRequest remoteRequest)
    {
        requireNonNull(remoteRequest, "request is null");
        byte[] payload;
        try {
            payload = remoteRequest.body()
                    .map(this::serialize)
                    .orElseGet(() -> new byte[0]);
        }
        catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (payload.length > maximumRequestBytes) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("REST request exceeds maximumRequestBytes"));
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(requestUri(remoteRequest))
                .timeout(requestTimeout)
                .header("Accept", "application/json");
        if (remoteRequest.method().equals("GET")) {
            builder.GET();
        }
        else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        }

        CompletableFuture<HttpResponse<InputStream>> response = httpClient.sendAsync(
                builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        CompletableFuture<HttpResponse<InputStream>> sanitizedResponse = response.handle((value, failure) -> {
            if (failure != null) {
                throw RemoteTransportException.sanitize(failure);
            }
            return value;
        });
        CompletableFuture<JsonNode> result = sanitizedResponse.thenApply(this::readResponse);
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) {
                response.cancel(true);
            }
        });
        return result;
    }

    private byte[] serialize(JsonNode body)
    {
        try {
            return objectMapper.writeValueAsBytes(body);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("REST request body cannot be serialized");
        }
    }

    private URI requestUri(RestRemoteRequest request)
    {
        String query = request.queryParameters().entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .map(value -> encode(entry.getKey()) + "=" + encode(value)))
                .collect(Collectors.joining("&"));
        String origin = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
        return URI.create(origin + request.path() + (query.isEmpty() ? "" : "?" + query));
    }

    private JsonNode readResponse(HttpResponse<InputStream> response)
    {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            closeQuietly(response.body());
            throw new RemoteHttpException(response.statusCode(), response.headers().firstValue("Retry-After"));
        }
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(Math.addExact(maximumResponseBytes, 1));
            if (bytes.length > maximumResponseBytes) {
                throw new IllegalStateException("REST response exceeds maximumResponseBytes");
            }
            JsonNode value = objectMapper.readTree(bytes);
            if (value == null) {
                throw new IllegalStateException("REST endpoint returned an empty JSON response");
            }
            return value;
        }
        catch (IOException e) {
            throw new IllegalStateException("REST endpoint returned malformed JSON");
        }
    }

    private static URI validateEndpoint(URI endpoint)
    {
        requireNonNull(endpoint, "endpoint is null");
        String path = endpoint.getRawPath();
        if (!endpoint.isAbsolute() ||
                !(endpoint.getScheme().equals("http") || endpoint.getScheme().equals("https")) ||
                endpoint.getHost() == null ||
                endpoint.getRawUserInfo() != null ||
                endpoint.getRawQuery() != null ||
                endpoint.getRawFragment() != null ||
                !(path.isEmpty() || path.equals("/"))) {
            throw new IllegalArgumentException("REST endpoint must be an HTTP(S) origin without credentials, path, query, or fragment");
        }
        return endpoint;
    }

    private static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void closeQuietly(InputStream body)
    {
        try {
            body.close();
        }
        catch (IOException ignored) {
        }
    }
}
