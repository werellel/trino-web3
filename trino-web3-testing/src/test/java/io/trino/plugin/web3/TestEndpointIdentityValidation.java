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
package io.trino.plugin.web3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.trino.Session;
import io.trino.testing.StandaloneQueryRunner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestEndpointIdentityValidation
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void testAcceptsMatchingEthereumEndpoints()
            throws Exception
    {
        HttpServer primary = jsonRpcServer("0x1");
        HttpServer fallback = jsonRpcServer("0x01");
        try {
            primary.start();
            fallback.start();
            try (StandaloneQueryRunner queryRunner = queryRunner()) {
                queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                        "web3.ethereum.rpc-url", endpoint(primary),
                        "web3.ethereum.rpc-fallback-urls", endpoint(fallback)));

                assertThat(queryRunner.execute("SELECT configured_provider_count FROM web3.system.chains WHERE schema_name = 'ethereum'").getOnlyColumn())
                        .containsExactly(2L);
            }
        }
        finally {
            primary.stop(0);
            fallback.stop(0);
        }
    }

    @Test
    public void testAcceptsBaseEndpointWithCanonicalChainId()
            throws Exception
    {
        HttpServer server = jsonRpcServer("0x2105");
        try {
            server.start();
            try (StandaloneQueryRunner queryRunner = queryRunner()) {
                queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                        "web3.base.rpc-url", endpoint(server)));
                assertThat(queryRunner.execute("SELECT configured_provider_count FROM web3.system.chains WHERE schema_name = 'base'").getOnlyColumn())
                        .containsExactly(1L);
            }
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    public void testAcceptsOptimismEndpointWithCanonicalChainId()
            throws Exception
    {
        HttpServer server = jsonRpcServer("0xa");
        try {
            server.start();
            try (StandaloneQueryRunner queryRunner = queryRunner()) {
                queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                        "web3.optimism.rpc-url", endpoint(server)));
                assertThat(queryRunner.execute("SELECT configured_provider_count FROM web3.system.chains WHERE schema_name = 'optimism'").getOnlyColumn())
                        .containsExactly(1L);
            }
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    public void testAcceptsAdditionalEvmEndpointIdentities()
            throws Exception
    {
        List<String[]> networks = List.of(
                new String[] {"gnosis", "web3.gnosis.rpc-url", "0x64"},
                new String[] {"kaia", "web3.kaia.rpc-url", "0x2019"},
                new String[] {"arc", "web3.arc.rpc-url", "0x13b2"},
                new String[] {"story", "web3.story.rpc-url", "0x5ea"},
                new String[] {"boba", "web3.boba.rpc-url", "0x120"},
                new String[] {"celo", "web3.celo.rpc-url", "0xa4ec"},
                new String[] {"hyperevm", "web3.hyperevm.rpc-url", "0x3e7"},
                new String[] {"abstract", "web3.abstract.rpc-url", "0xab5"},
                new String[] {"anime", "web3.anime.rpc-url", "0x10d88"},
                new String[] {"apechain", "web3.apechain.rpc-url", "0x8173"},
                new String[] {"degen", "web3.degen.rpc-url", "0x27bc86aa"},
                new String[] {"ink", "web3.ink.rpc-url", "0xdef1"},
                new String[] {"jovay", "web3.jovay.rpc-url", "0x578227"},
                new String[] {"crossfi", "web3.crossfi.rpc-url", "0x103e"},
                new String[] {"linea", "web3.linea.rpc-url", "0xe708"});
        List<HttpServer> servers = new ArrayList<>();
        try (StandaloneQueryRunner queryRunner = queryRunner()) {
            for (String[] network : networks) {
                HttpServer server = jsonRpcServer(network[2]);
                servers.add(server);
                server.start();
                queryRunner.createCatalog(network[0], Web3ConnectorFactory.CONNECTOR_NAME, Map.of(network[1], endpoint(server)));
                assertThat(queryRunner.execute("SELECT configured_provider_count FROM " + network[0] + ".system.chains WHERE schema_name = '" + network[0] + "'").getOnlyColumn())
                        .containsExactly(1L);
            }
        }
        finally {
            servers.forEach(server -> server.stop(0));
        }
    }

    @Test
    public void testRejectsMismatchedEthereumEndpointsWithoutLeakingCredential()
            throws Exception
    {
        HttpServer primary = jsonRpcServer("0x1");
        HttpServer fallback = jsonRpcServer("0x2");
        String secret = "do-not-leak-identity-token";
        try {
            primary.start();
            fallback.start();
            try (StandaloneQueryRunner queryRunner = queryRunner()) {
                assertThatThrownBy(() -> queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                        "web3.ethereum.rpc-url", "http://" + secret + "@127.0.0.1:" + primary.getAddress().getPort(),
                        "web3.ethereum.rpc-fallback-urls", endpoint(fallback))))
                        .hasMessageContaining("configured endpoints do not have the same chain identity for schema ethereum")
                        .hasMessageNotContaining(secret);
            }
        }
        finally {
            primary.stop(0);
            fallback.stop(0);
        }
    }

    @Test
    public void testRejectsMalformedEthereumIdentity()
            throws Exception
    {
        HttpServer primary = jsonRpcServer("not-a-chain-id");
        HttpServer fallback = jsonRpcServer("0x1");
        try {
            primary.start();
            fallback.start();
            try (StandaloneQueryRunner queryRunner = queryRunner()) {
                assertThatThrownBy(() -> queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                        "web3.ethereum.rpc-url", endpoint(primary),
                        "web3.ethereum.rpc-fallback-urls", endpoint(fallback))))
                        .hasMessageContaining("endpoint returned an invalid chain identity for schema ethereum");
            }
        }
        finally {
            primary.stop(0);
            fallback.stop(0);
        }
    }

    @Test
    public void testRejectsMismatchedSolanaEndpoints()
            throws Exception
    {
        HttpServer primary = jsonRpcServer("4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY");
        HttpServer fallback = jsonRpcServer("5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp5Z4oP6p9A6fF");
        try {
            primary.start();
            fallback.start();
            try (StandaloneQueryRunner queryRunner = queryRunner()) {
                assertThatThrownBy(() -> queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                        "web3.solana.rpc-url", endpoint(primary),
                        "web3.solana.rpc-fallback-urls", endpoint(fallback))))
                        .hasMessageContaining("configured endpoints do not have the same chain identity for schema solana");
            }
        }
        finally {
            primary.stop(0);
            fallback.stop(0);
        }
    }

    @Test
    public void testAcceptsTronNativeApiEndpoint()
            throws Exception
    {
        HttpServer server = tronServer();
        try {
            server.start();
            try (StandaloneQueryRunner queryRunner = queryRunner()) {
                queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                        "web3.tron.api-url", endpoint(server)));
                assertThat(queryRunner.execute("SELECT configured_provider_count FROM web3.system.chains WHERE schema_name = 'tron'").getOnlyColumn())
                        .containsExactly(1L);
            }
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    public void testAcceptsCosmosFamilyRestEndpointIdentities()
            throws Exception
    {
        HttpServer cosmos = cosmosServer("cosmoshub-4");
        HttpServer osmosis = cosmosServer("osmosis-1");
        HttpServer injective = cosmosServer("injective-1");
        try {
            cosmos.start();
            osmosis.start();
            injective.start();
            try (StandaloneQueryRunner queryRunner = queryRunner()) {
                queryRunner.createCatalog("cosmos", Web3ConnectorFactory.CONNECTOR_NAME, Map.of("web3.cosmos.rest-url", endpoint(cosmos)));
                queryRunner.createCatalog("osmosis", Web3ConnectorFactory.CONNECTOR_NAME, Map.of("web3.osmosis.rest-url", endpoint(osmosis)));
                queryRunner.createCatalog("injective", Web3ConnectorFactory.CONNECTOR_NAME, Map.of("web3.injective.rest-url", endpoint(injective)));

                assertThat(queryRunner.execute("SELECT configured_provider_count FROM cosmos.system.chains WHERE schema_name = 'cosmos'").getOnlyColumn())
                        .containsExactly(1L);
                assertThat(queryRunner.execute("SELECT configured_provider_count FROM osmosis.system.chains WHERE schema_name = 'osmosis'").getOnlyColumn())
                        .containsExactly(1L);
                assertThat(queryRunner.execute("SELECT configured_provider_count FROM injective.system.chains WHERE schema_name = 'injective'").getOnlyColumn())
                        .containsExactly(1L);
            }
        }
        finally {
            cosmos.stop(0);
            osmosis.stop(0);
            injective.stop(0);
        }
    }

    @Test
    public void testRejectsMismatchedAptosEndpoints()
            throws Exception
    {
        HttpServer primary = aptosServer(1);
        HttpServer fallback = aptosServer(2);
        try {
            primary.start();
            fallback.start();
            try (StandaloneQueryRunner queryRunner = queryRunner()) {
                assertThatThrownBy(() -> queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                        "web3.aptos.rest-url", endpoint(primary),
                        "web3.aptos.rest-fallback-urls", endpoint(fallback))))
                        .hasMessageContaining("configured endpoints do not have the same chain identity for schema aptos");
            }
        }
        finally {
            primary.stop(0);
            fallback.stop(0);
        }
    }

    @Test
    public void testRejectsBitcoinEndpointWithWrongNodeIdentity()
            throws Exception
    {
        HttpServer primary = bitcoinServer("/Satoshi:29.3.0/");
        HttpServer fallback = bitcoinServer("/Litecoin Core:0.21.3/");
        try {
            primary.start();
            fallback.start();
            try (StandaloneQueryRunner queryRunner = queryRunner()) {
                assertThatThrownBy(() -> queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                        "web3.bitcoin.rpc-url", endpoint(primary),
                        "web3.bitcoin.rpc-fallback-urls", endpoint(fallback))))
                        .hasMessageContaining("endpoint returned an invalid chain identity for schema bitcoin");
            }
        }
        finally {
            primary.stop(0);
            fallback.stop(0);
        }
    }

    private static StandaloneQueryRunner queryRunner()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").build();
        StandaloneQueryRunner queryRunner = new StandaloneQueryRunner(session);
        queryRunner.installPlugin(new Web3Plugin());
        return queryRunner;
    }

    private static HttpServer jsonRpcServer(String identity)
            throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> writeJsonRpcIdentity(exchange, identity));
        return server;
    }

    private static HttpServer aptosServer(int chainId)
            throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            byte[] body = OBJECT_MAPPER.writeValueAsBytes(Map.of("chain_id", chainId));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }

    private static HttpServer tronServer()
            throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/wallet/getnowblock", exchange -> {
            ObjectNode response = OBJECT_MAPPER.createObjectNode()
                    .put("blockID", "0000000000000000000000000000000000000000000000000000000000000001");
            response.putObject("block_header").putObject("raw_data").put("number", 1);
            byte[] body = OBJECT_MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }

    private static HttpServer cosmosServer(String chainId)
            throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cosmos/base/tendermint/v1beta1/blocks/latest", exchange -> {
            ObjectNode response = OBJECT_MAPPER.createObjectNode();
            response.putObject("block").putObject("header").put("chain_id", chainId);
            byte[] body = OBJECT_MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }

    private static HttpServer bitcoinServer(String subversion)
            throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode requestDocument = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            JsonNode request = requestDocument.isArray() ? requestDocument.get(0) : requestDocument;
            ObjectNode result = OBJECT_MAPPER.createObjectNode().put("subversion", subversion);
            ObjectNode response = OBJECT_MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.put("id", request.path("id").asLong());
            response.set("result", result);
            JsonNode responseDocument = requestDocument.isArray() ? OBJECT_MAPPER.createArrayNode().add(response) : response;
            byte[] body = OBJECT_MAPPER.writeValueAsBytes(responseDocument);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }

    private static void writeJsonRpcIdentity(HttpExchange exchange, String identity)
            throws IOException
    {
        JsonNode requestDocument = OBJECT_MAPPER.readTree(exchange.getRequestBody());
        JsonNode request = requestDocument.isArray() ? requestDocument.get(0) : requestDocument;
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", request.path("id").asLong());
        response.put("result", identity);
        JsonNode responseDocument = requestDocument.isArray() ? OBJECT_MAPPER.createArrayNode().add(response) : response;
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(responseDocument);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String endpoint(HttpServer server)
    {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
