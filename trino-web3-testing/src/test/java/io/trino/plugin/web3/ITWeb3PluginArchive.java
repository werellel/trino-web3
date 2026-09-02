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
import java.util.concurrent.atomic.AtomicInteger;
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
            assertThat(classLoader.loadClass("io.trino.plugin.web3.chain.ChainDescriptor")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.adapter.ExecutableChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.aptos.AptosChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.aptos.AptosTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.bitcoin.BitcoinChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.bitcoin.BitcoinTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.utxo.UtxoChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.litecoin.LitecoinChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.dogecoin.DogecoinChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.bitcoincash.BitcoinCashChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.bitcoincash.BitcoinCashTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.solana.SolanaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.solana.SolanaDevnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.tron.TronChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.tron.TronNileChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.tron.TronShastaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.sui.SuiChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.sui.SuiTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.cosmos.CosmosChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.cosmos.OsmosisChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.cosmos.InjectiveChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.cosmos.CosmosTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.cosmos.OsmosisTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.cosmos.InjectiveTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.litecoin.LitecoinTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.dogecoin.DogecoinTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.GnosisChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.KaiaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.ArcChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.StoryChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.BobaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.CeloChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.HyperEvmChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.AbstractChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.AnimeChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.ApeChainChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.DegenChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.InkChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.JovayChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.CrossFiChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.LineaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.UnichainChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.TempoChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.RobinhoodChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.ModeChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.EthereumSepoliaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.BaseSepoliaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.OptimismSepoliaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.ArbitrumSepoliaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.BnbTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.PolygonAmoyChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.AvalancheFujiChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.GnosisChiadoChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.KaiaKairosChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.ArcTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.StoryAeneidChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.BobaSepoliaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.CeloSepoliaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.HyperEvmTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.AbstractSepoliaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.AnimeTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.ApeChainCurtisChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.InkSepoliaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.JovaySepoliaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.CrossFiTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.LineaSepoliaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.UnichainSepoliaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.TempoModeratoChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.RobinhoodTestnetChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.ModeSepoliaChainAdapter")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.core.Web3TableHandle")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.evm.EthereumBlockClient")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.runtime.JsonRpcClient")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.plugin.web3.runtime.RestClient")).isNotNull();
            assertThat(classLoader.loadClass("io.trino.cache.EvictableCacheBuilder")).isNotNull();
        }
    }

    @Test
    public void testPluginDistributionExecutesBoundedQuery()
            throws Exception
    {
        AtomicInteger blockDataRequests = new AtomicInteger();
        HttpServer rpcServer = createRpcServer(blockDataRequests);
        HttpServer unavailablePrimary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        unavailablePrimary.createContext("/", exchange -> {
            JsonNode requestDocument = OBJECT_MAPPER.readTree(exchange.getRequestBody());
            JsonNode request = requestDocument.isArray() ? requestDocument.get(0) : requestDocument;
            if (request.path("method").asText().equals("eth_chainId")) {
                writeChainId(exchange, requestDocument);
                return;
            }
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        try (PluginDistribution pluginDistribution = unpackPluginDistribution();
                var classLoader = PluginManager.createClassLoader("web3", pluginDistribution.jars())) {
            rpcServer.start();
            unavailablePrimary.start();
            Plugin plugin = ServiceLoader.load(Plugin.class, classLoader).findFirst().orElseThrow();
            Session session = testSessionBuilder().setCatalog("web3").setSchema("ethereum").build();

            try (StandaloneQueryRunner queryRunner = new StandaloneQueryRunner(session)) {
                queryRunner.getCoordinator().getInstance(Key.get(HandleResolver.class)).registerClassLoader(classLoader);
                queryRunner.installPlugin(plugin);
                queryRunner.createCatalog("web3", "web3", Map.of(
                        "web3.ethereum.rpc-url", "http://127.0.0.1:" + unavailablePrimary.getAddress().getPort(),
                        "web3.ethereum.rpc-fallback-urls", "http://127.0.0.1:" + rpcServer.getAddress().getPort(),
                        "web3.rpc.json-rpc-batch-enabled", "false",
                        "web3.rpc.initial-backoff-millis", "1",
                        "web3.rpc.provider-cooldown-millis", "1",
                        "web3.cache.enabled", "true"));

                assertThat(queryRunner.execute("SHOW TABLES FROM web3.aptos").getOnlyColumn())
                        .containsExactly("events", "transactions");
                assertThat(queryRunner.execute("SHOW TABLES FROM web3.solana").getOnlyColumn())
                        .containsExactly("blocks", "instructions", "transactions");
                assertThat(queryRunner.execute("SHOW TABLES FROM web3.tron").getOnlyColumn())
                        .containsExactly("blocks", "transactions");
                assertThat(queryRunner.execute("SHOW TABLES FROM web3.sui").getOnlyColumn())
                        .containsExactly("checkpoints", "transactions");
                assertThat(queryRunner.execute("SHOW TABLES FROM web3.cosmos").getOnlyColumn())
                        .containsExactly("blocks", "transactions");
                assertThat(queryRunner.execute("SHOW TABLES FROM web3.osmosis").getOnlyColumn())
                        .containsExactly("blocks", "transactions");
                assertThat(queryRunner.execute("SHOW TABLES FROM web3.injective").getOnlyColumn())
                        .containsExactly("blocks", "transactions");
                for (String schema : List.of("gnosis", "kaia", "arc", "story", "boba", "celo", "hyperevm", "abstract", "anime", "apechain", "degen", "ink", "jovay", "crossfi", "linea", "unichain", "tempo", "robinhood", "mode")) {
                    assertThat(queryRunner.execute("SHOW TABLES FROM web3." + schema).getOnlyColumn())
                            .containsExactly("blocks", "transactions");
                }
                for (String schema : List.of("ethereum_sepolia", "base_sepolia", "optimism_sepolia", "arbitrum_sepolia", "bnb_testnet", "polygon_amoy", "avalanche_fuji", "gnosis_chiado", "kaia_kairos", "arc_testnet", "story_aeneid", "boba_sepolia", "celo_sepolia", "hyperevm_testnet", "abstract_sepolia", "anime_testnet", "apechain_curtis", "ink_sepolia", "jovay_sepolia", "crossfi_testnet", "linea_sepolia", "unichain_sepolia", "tempo_moderato", "robinhood_testnet", "mode_sepolia")) {
                    assertThat(queryRunner.execute("SHOW TABLES FROM web3." + schema).getOnlyColumn())
                            .containsExactly("blocks", "transactions");
                }
                assertThat(queryRunner.execute("SHOW TABLES FROM web3.bitcoin").getOnlyColumn())
                        .containsExactly("blocks", "inputs", "outputs", "transactions");
                assertThat(queryRunner.execute("SHOW TABLES FROM web3.litecoin").getOnlyColumn())
                        .containsExactly("blocks", "inputs", "outputs", "transactions");
                assertThat(queryRunner.execute("SHOW TABLES FROM web3.dogecoin").getOnlyColumn())
                        .containsExactly("blocks", "inputs", "outputs", "transactions");
                assertThat(queryRunner.execute("SHOW TABLES FROM web3.bitcoincash").getOnlyColumn())
                        .containsExactly("blocks", "inputs", "outputs", "transactions");
                for (String schema : List.of("bitcoin_testnet", "litecoin_testnet", "dogecoin_testnet", "bitcoincash_testnet")) {
                    assertThat(queryRunner.execute("SHOW TABLES FROM web3." + schema).getOnlyColumn())
                            .containsExactly("blocks", "inputs", "outputs", "transactions");
                }
                assertThat(queryRunner.execute("SHOW TABLES FROM web3.aptos_testnet").getOnlyColumn())
                        .containsExactly("events", "transactions");
                assertThat(queryRunner.execute("SHOW TABLES FROM web3.solana_devnet").getOnlyColumn())
                        .containsExactly("blocks", "instructions", "transactions");
                for (String schema : List.of("tron_nile", "tron_shasta", "cosmos_testnet", "osmosis_testnet", "injective_testnet")) {
                    assertThat(queryRunner.execute("SHOW TABLES FROM web3." + schema).getOnlyColumn())
                            .containsExactly("blocks", "transactions");
                }
                assertThat(queryRunner.execute("SHOW TABLES FROM web3.sui_testnet").getOnlyColumn())
                        .containsExactly("checkpoints", "transactions");
                assertThat(queryRunner.execute("SHOW TABLES FROM web3.system").getOnlyColumn())
                        .containsExactly("cache_stats", "chains", "providers", "rate_limits", "rpc_metrics");

                String query = """
                        SELECT block_hash
                        FROM web3.ethereum.blocks
                        WHERE block_number = 23000000
                        """;
                assertThat(queryRunner.execute(query).getOnlyColumn()).containsExactly(hash('a'));
                assertThat(queryRunner.execute(query).getOnlyColumn()).containsExactly(hash('a'));
                assertThat(blockDataRequests).hasValue(1);
            }
        }
        finally {
            rpcServer.stop(0);
            unavailablePrimary.stop(0);
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

    private static HttpServer createRpcServer(AtomicInteger blockDataRequests)
            throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handleRpcRequest(exchange, blockDataRequests));
        return server;
    }

    private static void handleRpcRequest(HttpExchange exchange, AtomicInteger blockDataRequests)
            throws IOException
    {
        JsonNode requestDocument = OBJECT_MAPPER.readTree(exchange.getRequestBody());
        Iterable<JsonNode> requests = requestDocument.isArray() ? requestDocument : List.of(requestDocument);
        ArrayNode responses = OBJECT_MAPPER.createArrayNode();
        for (JsonNode request : requests) {
            ObjectNode response = responses.addObject();
            response.put("jsonrpc", "2.0");
            response.put("id", request.path("id").asLong());
            if (request.path("method").asText().equals("eth_chainId")) {
                response.put("result", "0x1");
                continue;
            }
            String quantity = request.path("params").get(0).asText();
            ObjectNode block = response.putObject("result");
            if (quantity.equals("safe") || quantity.equals("finalized")) {
                block.put("number", "0x15ef3c0");
                block.put("hash", hash('b'));
            }
            else {
                blockDataRequests.incrementAndGet();
                block.put("number", "0x15ef3c0");
                block.put("hash", hash('a'));
            }
        }
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(requestDocument.isArray() ? responses : responses.get(0));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void writeChainId(HttpExchange exchange, JsonNode requestDocument)
            throws IOException
    {
        JsonNode request = requestDocument.isArray() ? requestDocument.get(0) : requestDocument;
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", request.path("id").asLong());
        response.put("result", "0x1");
        JsonNode responseDocument = requestDocument.isArray() ? OBJECT_MAPPER.createArrayNode().add(response) : response;
        byte[] body = OBJECT_MAPPER.writeValueAsBytes(responseDocument);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String hash(char value)
    {
        return "0x" + String.valueOf(value).repeat(64);
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
