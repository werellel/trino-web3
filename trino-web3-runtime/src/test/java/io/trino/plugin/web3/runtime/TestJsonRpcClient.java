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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestJsonRpcClient
{
    private HttpServer server;
    private byte[] response;
    private Duration responseDelay;

    @BeforeEach
    public void setUp()
            throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
    }

    @AfterEach
    public void tearDown()
    {
        server.stop(0);
    }

    @Test
    public void testRejectsMalformedResponse()
    {
        response = "{".getBytes();
        JsonRpcClient client = client(Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.executeBatch(List.of(request())).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testTimesOut()
    {
        response = "[]".getBytes();
        responseDelay = Duration.ofMillis(250);
        JsonRpcClient client = client(Duration.ofMillis(25));

        assertThatThrownBy(() -> client.executeBatch(List.of(request())).join())
                .isInstanceOf(CompletionException.class);
    }

    @Test
    public void testRejectsUnexpectedResponseId()
    {
        response = "[{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"0x1\"}]".getBytes();
        JsonRpcClient client = client(Duration.ofSeconds(1));

        assertThatThrownBy(() -> client.executeBatch(List.of(request())).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testRejectsResponseLargerThanLimit()
    {
        response = "[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x1234567890\"}]".getBytes();
        JsonRpcClient client = client(Duration.ofSeconds(1), 10);

        assertThatThrownBy(() -> client.executeBatch(List.of(request())).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    public void testRejectsRequestLargerThanLimit()
    {
        response = "[]".getBytes();
        JsonRpcClient client = new JsonRpcClient(
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1),
                1,
                1_024);

        assertThatThrownBy(() -> client.executeBatch(List.of(request())).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    private JsonRpcClient client(Duration timeout)
    {
        return client(timeout, 1_024);
    }

    private JsonRpcClient client(Duration timeout, int maximumResponseBytes)
    {
        return new JsonRpcClient(
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                timeout,
                1_024,
                maximumResponseBytes);
    }

    private static JsonRpcClient.JsonRpcRequest request()
    {
        return new JsonRpcClient.JsonRpcRequest(1, "eth_chainId", List.of());
    }

    private void handleRequest(HttpExchange exchange)
            throws IOException
    {
        if (responseDelay != null) {
            try {
                Thread.sleep(responseDelay);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
