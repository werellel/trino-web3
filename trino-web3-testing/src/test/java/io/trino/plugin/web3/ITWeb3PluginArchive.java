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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Key;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.trino.Session;
import io.trino.metadata.HandleResolver;
import io.trino.spi.Plugin;
import io.trino.server.PluginManager;
import io.trino.testing.StandaloneQueryRunner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.zip.ZipFile;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

public class ITWeb3PluginArchive
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void testPluginArchiveRegistersPlugin()
            throws Exception
    {
        try (PluginDistribution pluginDistribution = unpackPluginDistribution();
                var classLoader = PluginManager.createClassLoader("web3", pluginDistribution.jars())) {
            List<String> pluginClassNames = ServiceLoader.load(Plugin.class, classLoader)
                    .stream()
                    .map(provider -> provider.type().getName())
                    .toList();

            assertThat(pluginClassNames).containsExactly("io.trino.plugin.web3.Web3Plugin");
            assertThat(classLoader.loadClass("io.trino.plugin.web3.core.Web3TableHandle")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.EthereumBlockClient")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.runtime.JsonRpcClient")).isNotNull();
        }
    }

    @Test
    public void testPluginDistributionExecutesBoundedQuery()
            throws Exception
    {
        HttpServer rpcServer = createRpcServer();
        try (PluginDistribution pluginDistribution = unpackPluginDistribution();
                var classLoader = PluginManager.createClassLoader("web3", pluginDistribution.jars())) {
            rpcServer.start();
            Plugin plugin = ServiceLoader.load(Plugin.class, classLoader).findFirst().orElseThrow();
            Session session = testSessionBuilder().setCatalog("web3").setSchema("ethereum").build();

            try (StandaloneQueryRunner queryRunner = new StandaloneQueryRunner(session)) {
                queryRunner.getCoordinator().getInstance(Key.get(HandleResolver.class)).registerClassLoader(classLoader);
                queryRunner.installPlugin(plugin);
                queryRunner.createCatalog("web3", "web3", Map.of(
                        "web3.ethereum.rpc-url", "http://127.0.0.1:" + rpcServer.getAddress().getPort()));

                assertThat(queryRunner.execute("""
                        SELECT block_hash
                        FROM web3.ethereum.blocks
                        WHERE block_number = 23000000
                        """).getOnlyColumn()).containsExactly("0x15ef3c0");
            }
        }
        finally {
            rpcServer.stop(0);
        }
    }

    private static PluginDistribution unpackPluginDistribution()
            throws Exception
    {
        Path pluginArchive = Path.of("..", "trino-web3-plugin", "target", "trino-web3-plugin-0.1-SNAPSHOT-plugin.zip").toRealPath();
        Path pluginDirectory = Files.createTempDirectory("trino-web3-plugin-");
        List<java.net.URL> pluginJars = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(pluginArchive.toFile())) {
            zipFile.stream().forEach(entry -> {
                if (entry.isDirectory() || !entry.getName().endsWith(".jar")) {
                    return;
                }
                try {
                    Path target = pluginDirectory.resolve(Path.of(entry.getName()).getFileName());
                    Files.copy(zipFile.getInputStream(entry), target);
                    pluginJars.add(target.toUri().toURL());
                }
                catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return new PluginDistribution(pluginDirectory, List.copyOf(pluginJars));
    }

    private static HttpServer createRpcServer()
            throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ITWeb3PluginArchive::handleRpcRequest);
        return server;
    }

    private static void handleRpcRequest(HttpExchange exchange)
            throws IOException
    {
        JsonNode requests = OBJECT_MAPPER.readTree(exchange.getRequestBody());
        ArrayNode responses = OBJECT_MAPPER.createArrayNode();
        for (JsonNode request : requests) {
            String quantity = request.path("params").get(0).asText();
            ObjectNode response = responses.addObject();
            response.put("jsonrpc", "2.0");
            response.put("id", request.path("id").asLong());
            ObjectNode block = response.putObject("result");
            block.put("number", quantity);
            block.put("hash", "0x" + Long.toHexString(Long.parseUnsignedLong(quantity.substring(2), 16)));
        }
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(responses);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record PluginDistribution(Path directory, List<java.net.URL> jars)
            implements AutoCloseable
    {
        @Override
        public void close()
                throws IOException
        {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
