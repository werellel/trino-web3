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
import io.airlift.slice.Slices;
import io.trino.Session;
import io.trino.plugin.web3.core.Web3ColumnHandle;
import io.trino.plugin.web3.core.Web3TableHandle;
import io.trino.spi.connector.Constraint;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.testing.MaterializedResult;
import io.trino.testing.StandaloneQueryRunner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.VarcharType.VARCHAR;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestWeb3Catalog
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String FIRST_HASH = "0x" + "a".repeat(64);
    private static final String SECOND_HASH = "0x" + "b".repeat(64);

    @Test
    public void testShowSchemas()
            throws Exception
    {
        Session session = testSessionBuilder()
                .setCatalog("web3")
                .build();

        try (StandaloneQueryRunner queryRunner = new StandaloneQueryRunner(session)) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, java.util.Map.of());

            MaterializedResult result = queryRunner.execute("SHOW SCHEMAS FROM web3");
            assertThat(result.getOnlyColumn()).containsExactly("abstract", "abstract_sepolia", "anime", "anime_testnet", "apechain", "apechain_curtis", "aptos", "aptos_testnet", "arbitrum", "arbitrum_sepolia", "arc", "arc_testnet", "avalanche", "avalanche_fuji", "base", "base_sepolia", "bitcoin", "bitcoin_testnet", "bitcoincash", "bitcoincash_testnet", "bnb", "bnb_testnet", "boba", "boba_sepolia", "celo", "celo_sepolia", "cosmos", "cosmos_testnet", "crossfi", "crossfi_testnet", "degen", "dogecoin", "dogecoin_testnet", "ethereum", "ethereum_sepolia", "gnosis", "gnosis_chiado", "hyperevm", "hyperevm_testnet", "information_schema", "injective", "injective_testnet", "ink", "ink_sepolia", "jovay", "jovay_sepolia", "kaia", "kaia_kairos", "linea", "linea_sepolia", "litecoin", "litecoin_testnet", "mode", "mode_sepolia", "optimism", "optimism_sepolia", "osmosis", "osmosis_testnet", "polygon", "polygon_amoy", "robinhood", "robinhood_testnet", "solana", "solana_devnet", "story", "story_aeneid", "sui", "sui_testnet", "system", "tempo", "tempo_moderato", "tron", "tron_nile", "tron_shasta", "unichain", "unichain_sepolia");
            assertThat(queryRunner.execute("SHOW TABLES FROM web3.bitcoin").getOnlyColumn())
                    .containsExactly("blocks", "inputs", "outputs", "transactions");
            assertThat(queryRunner.execute("SHOW TABLES FROM web3.litecoin").getOnlyColumn())
                    .containsExactly("blocks", "inputs", "outputs", "transactions");
            assertThat(queryRunner.execute("SHOW TABLES FROM web3.dogecoin").getOnlyColumn())
                    .containsExactly("blocks", "inputs", "outputs", "transactions");
            assertThat(queryRunner.execute("SHOW TABLES FROM web3.bitcoincash").getOnlyColumn())
                    .containsExactly("blocks", "inputs", "outputs", "transactions");
            assertThat(queryRunner.execute("SHOW TABLES FROM web3.aptos").getOnlyColumn())
                    .containsExactly("events", "transactions");
            assertThat(queryRunner.execute("SHOW TABLES FROM web3.ethereum").getOnlyColumn())
                    .containsExactly("blocks", "logs", "receipts", "transactions");
            assertThat(queryRunner.execute("SHOW TABLES FROM web3.base").getOnlyColumn())
                    .containsExactly("blocks", "logs", "receipts", "transactions");
            assertThat(queryRunner.execute("SHOW TABLES FROM web3.optimism").getOnlyColumn())
                    .containsExactly("blocks", "logs", "receipts", "transactions");
            for (String schema : List.of("gnosis", "kaia", "arc", "story", "boba", "celo", "hyperevm", "abstract", "anime", "apechain", "degen", "ink", "jovay", "crossfi", "linea", "unichain", "tempo", "robinhood", "mode")) {
                assertThat(queryRunner.execute("SHOW TABLES FROM web3." + schema).getOnlyColumn())
                        .containsExactly("blocks", "logs", "receipts", "transactions");
            }
            for (String schema : List.of("ethereum_sepolia", "base_sepolia", "optimism_sepolia", "arbitrum_sepolia", "bnb_testnet", "polygon_amoy", "avalanche_fuji", "gnosis_chiado", "kaia_kairos", "arc_testnet", "story_aeneid", "boba_sepolia", "celo_sepolia", "hyperevm_testnet", "abstract_sepolia", "anime_testnet", "apechain_curtis", "ink_sepolia", "jovay_sepolia", "crossfi_testnet", "linea_sepolia", "unichain_sepolia", "tempo_moderato", "robinhood_testnet", "mode_sepolia")) {
                assertThat(queryRunner.execute("SHOW TABLES FROM web3." + schema).getOnlyColumn())
                        .containsExactly("blocks", "logs", "receipts", "transactions");
            }
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
            assertThat(queryRunner.execute("DESCRIBE web3.polygon.blocks").getMaterializedRows())
                    .extracting(row -> row.getField(0))
                    .containsExactly("block_number", "block_hash", "raw_json");
            assertThat(queryRunner.execute("DESCRIBE web3.aptos.transactions").getMaterializedRows())
                    .extracting(row -> row.getField(0))
                    .containsExactly("ledger_version", "hash", "type", "success", "vm_status", "sender", "raw_json");
            assertThat(queryRunner.execute("DESCRIBE web3.aptos.events").getMaterializedRows())
                    .extracting(row -> row.getField(0))
                    .containsExactly("account_address", "creation_number", "sequence_number", "event_type", "data", "raw_json");
            assertThat(queryRunner.execute("SHOW TABLES FROM web3.solana").getOnlyColumn())
                    .containsExactly("blocks", "instructions", "transactions");
            assertThat(queryRunner.execute("DESCRIBE web3.solana.instructions").getMaterializedRows())
                    .extracting(row -> row.getField(0))
                    .containsExactly("slot", "transaction_signature", "instruction_index", "program_id", "account_indices", "data", "raw_json");
            assertThat(queryRunner.execute("SHOW TABLES FROM web3.system").getOnlyColumn())
                    .containsExactly("cache_stats", "chains", "providers", "rate_limits", "rpc_metrics");
            assertThat(queryRunner.execute("SELECT schema_name, runtime_configured, configured_provider_count, cache_enabled FROM web3.system.chains ORDER BY schema_name").getMaterializedRows())
                    .extracting(row -> row.getField(0), row -> row.getField(1), row -> row.getField(2), row -> row.getField(3))
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("abstract", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("abstract_sepolia", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("anime", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("anime_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("apechain", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("apechain_curtis", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("aptos", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("aptos_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("arbitrum", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("arbitrum_sepolia", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("arc", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("arc_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("avalanche", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("avalanche_fuji", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("base", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("base_sepolia", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("bitcoin", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("bitcoin_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("bitcoincash", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("bitcoincash_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("bnb", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("bnb_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("boba", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("boba_sepolia", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("celo", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("celo_sepolia", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("cosmos", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("cosmos_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("crossfi", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("crossfi_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("degen", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("dogecoin", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("dogecoin_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("ethereum", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("ethereum_sepolia", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("gnosis", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("gnosis_chiado", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("hyperevm", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("hyperevm_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("injective", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("injective_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("ink", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("ink_sepolia", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("jovay", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("jovay_sepolia", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("kaia", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("kaia_kairos", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("linea", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("linea_sepolia", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("litecoin", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("litecoin_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("mode", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("mode_sepolia", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("optimism", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("optimism_sepolia", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("osmosis", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("osmosis_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("polygon", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("polygon_amoy", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("robinhood", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("robinhood_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("solana", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("solana_devnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("story", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("story_aeneid", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("sui", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("sui_testnet", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("tempo", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("tempo_moderato", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("tron", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("tron_nile", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("tron_shasta", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("unichain", false, 0L, false),
                            org.assertj.core.groups.Tuple.tuple("unichain_sepolia", false, 0L, false));
        }
    }

    @Test
    public void testSystemTablesExposeSafeConfiguredRuntimeSnapshots()
            throws Exception
    {
        String secret = "do-not-leak-system-table-secret";
        HttpServer rpcServer = jsonRpcServer("0x1");
        Session session = testSessionBuilder().setCatalog("web3").build();
        rpcServer.start();
        try (StandaloneQueryRunner queryRunner = new StandaloneQueryRunner(session)) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.ethereum.rpc-url", endpoint(rpcServer),
                    "web3.rpc.maximum-concurrency", "3",
                    "web3.rpc.maximum-queue-size", "7",
                    "web3.rpc.maximum-batch-size", "5",
                    "web3.rpc.maximum-attempts", "2",
                    "web3.rpc.requests-per-second", "11",
                    "web3.cache.enabled", "true",
                    "web3.cache.maximum-size", "1MB",
                    "web3.cache.maximum-entry-size", "64kB"));

            assertThat(queryRunner.execute("SELECT provider_name, protocol, json_rpc_batch_enabled, state, cooldown_remaining_millis, request_count, failure_count, retry_count, throttled_count, in_flight_request_count, failover_count, batch_count, batch_item_count FROM web3.system.providers WHERE schema_name = 'ethereum'").getMaterializedRows())
                    .extracting(
                            row -> row.getField(0),
                            row -> row.getField(1),
                            row -> row.getField(2),
                            row -> row.getField(3),
                            row -> row.getField(4),
                            row -> row.getField(5),
                            row -> row.getField(6),
                            row -> row.getField(7),
                            row -> row.getField(8),
                            row -> row.getField(9),
                            row -> row.getField(10),
                            row -> row.getField(11),
                            row -> row.getField(12))
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("primary", "JSON_RPC", true, "AVAILABLE", 0L, 1L, 0L, 0L, 0L, 0L, 0L, 1L, 1L));
            assertThat(queryRunner.execute("SELECT maximum_concurrency, maximum_queue_size, maximum_batch_size, maximum_attempts, requests_per_second FROM web3.system.rate_limits WHERE schema_name = 'ethereum'").getMaterializedRows())
                    .extracting(row -> row.getField(0), row -> row.getField(1), row -> row.getField(2), row -> row.getField(3), row -> row.getField(4))
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(3L, 7L, 5L, 2L, 11L));
            assertThat(queryRunner.execute("SELECT cache_enabled, entry_count, retained_bytes, eviction_count FROM web3.system.cache_stats WHERE schema_name = 'ethereum'").getMaterializedRows())
                    .extracting(row -> row.getField(0), row -> row.getField(1), row -> row.getField(2), row -> row.getField(3))
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(true, 0L, 0L, 0L));
            assertThat(queryRunner.execute("SELECT request_count, failure_count, retry_count, throttled_count, in_flight_request_count FROM web3.system.rpc_metrics WHERE schema_name = 'ethereum'").getMaterializedRows())
                    .extracting(row -> row.getField(0), row -> row.getField(1), row -> row.getField(2), row -> row.getField(3), row -> row.getField(4))
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 0L, 0L, 0L, 0L));

            List<String> values = List.of("chains", "providers", "rpc_metrics", "rate_limits", "cache_stats").stream()
                    .flatMap(table -> queryRunner.execute("SELECT * FROM web3.system." + table).getMaterializedRows().stream())
                    .flatMap(row -> row.getFields().stream())
                    .map(String::valueOf)
                    .toList();
            assertThat(values).noneMatch(value -> value.contains(secret));
        }
        finally {
            rpcServer.stop(0);
        }
    }

    @Test
    public void testRejectsUnsafeRuntimeConfigurationWithoutLeakingSecret()
            throws Exception
    {
        Session session = testSessionBuilder().build();
        try (StandaloneQueryRunner queryRunner = new StandaloneQueryRunner(session)) {
            queryRunner.installPlugin(new Web3Plugin());

            assertThatThrownBy(() -> queryRunner.createCatalog("invalid_limit", Web3ConnectorFactory.CONNECTOR_NAME, java.util.Map.of(
                    "web3.rpc.maximum-concurrency", "65")))
                    .hasMessageContaining("web3.rpc.maximum-concurrency must be between 1 and 64");

            assertThatThrownBy(() -> queryRunner.createCatalog("invalid_batch", Web3ConnectorFactory.CONNECTOR_NAME, java.util.Map.of(
                    "web3.rpc.json-rpc-batch-enabled", "sometimes")))
                    .hasMessageContaining("web3.rpc.json-rpc-batch-enabled must be true or false");

            assertThatThrownBy(() -> queryRunner.createCatalog("invalid_cache_boolean", Web3ConnectorFactory.CONNECTOR_NAME, java.util.Map.of(
                    "web3.cache.enabled", "sometimes")))
                    .hasMessageContaining("web3.cache.enabled must be true or false");

            assertThatThrownBy(() -> queryRunner.createCatalog("invalid_cache_size", Web3ConnectorFactory.CONNECTOR_NAME, java.util.Map.of(
                    "web3.cache.enabled", "true",
                    "web3.cache.maximum-size", "1kB")))
                    .hasMessageContaining("web3.cache.maximum-size must be between");

            assertThatThrownBy(() -> queryRunner.createCatalog("invalid_cache_entry", Web3ConnectorFactory.CONNECTOR_NAME, java.util.Map.of(
                    "web3.cache.enabled", "true",
                    "web3.cache.maximum-size", "1MB",
                    "web3.cache.maximum-entry-size", "2MB")))
                    .hasMessageContaining("web3.cache.maximum-entry-size must not exceed web3.cache.maximum-size");

            assertThatThrownBy(() -> queryRunner.createCatalog("invalid_cache_ttl", Web3ConnectorFactory.CONNECTOR_NAME, java.util.Map.of(
                    "web3.cache.enabled", "true",
                    "web3.cache.ttl", "0s")))
                    .hasMessageContaining("web3.cache.ttl must be positive");

            queryRunner.createCatalog("disabled_cache", Web3ConnectorFactory.CONNECTOR_NAME, java.util.Map.of(
                    "web3.cache.enabled", "false",
                    "web3.cache.maximum-size", "1MB"));
            assertThat(queryRunner.execute("SHOW SCHEMAS FROM disabled_cache").getOnlyColumn())
                    .containsExactly("abstract", "abstract_sepolia", "anime", "anime_testnet", "apechain", "apechain_curtis", "aptos", "aptos_testnet", "arbitrum", "arbitrum_sepolia", "arc", "arc_testnet", "avalanche", "avalanche_fuji", "base", "base_sepolia", "bitcoin", "bitcoin_testnet", "bitcoincash", "bitcoincash_testnet", "bnb", "bnb_testnet", "boba", "boba_sepolia", "celo", "celo_sepolia", "cosmos", "cosmos_testnet", "crossfi", "crossfi_testnet", "degen", "dogecoin", "dogecoin_testnet", "ethereum", "ethereum_sepolia", "gnosis", "gnosis_chiado", "hyperevm", "hyperevm_testnet", "information_schema", "injective", "injective_testnet", "ink", "ink_sepolia", "jovay", "jovay_sepolia", "kaia", "kaia_kairos", "linea", "linea_sepolia", "litecoin", "litecoin_testnet", "mode", "mode_sepolia", "optimism", "optimism_sepolia", "osmosis", "osmosis_testnet", "polygon", "polygon_amoy", "robinhood", "robinhood_testnet", "solana", "solana_devnet", "story", "story_aeneid", "sui", "sui_testnet", "system", "tempo", "tempo_moderato", "tron", "tron_nile", "tron_shasta", "unichain", "unichain_sepolia");

            assertThatThrownBy(() -> queryRunner.createCatalog("invalid_hash_limit", Web3ConnectorFactory.CONNECTOR_NAME, java.util.Map.of(
                    "web3.maximum-transaction-hashes-per-query", "0")))
                    .hasMessageContaining("web3.maximum-transaction-hashes-per-query must be between 1 and 10000");

            assertThatThrownBy(() -> queryRunner.createCatalog("fallback_without_primary", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.ethereum.rpc-fallback-urls", "http://127.0.0.1:8000")))
                    .hasMessageContaining("web3.ethereum.rpc-fallback-urls requires web3.ethereum.rpc-url");

            assertThatThrownBy(() -> queryRunner.createCatalog("empty_fallback", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.ethereum.rpc-url", "http://127.0.0.1:8000",
                    "web3.ethereum.rpc-fallback-urls", "http://127.0.0.1:8001,")))
                    .hasMessageContaining("web3.ethereum.rpc-fallback-urls must not contain empty endpoints");

            assertThatThrownBy(() -> queryRunner.createCatalog("duplicate_endpoint", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.ethereum.rpc-url", "http://127.0.0.1:8000",
                    "web3.ethereum.rpc-fallback-urls", "http://127.0.0.1:8000")))
                    .hasMessageContaining("must not contain duplicate endpoints");

            String secret = "do-not-leak-token";
            assertThatThrownBy(() -> queryRunner.createCatalog("invalid_url", Web3ConnectorFactory.CONNECTOR_NAME, java.util.Map.of(
                    "web3.ethereum.rpc-url", "http://" + secret + "@[invalid")))
                    .hasMessageContaining("web3.ethereum.rpc-url contains an invalid URL")
                    .hasMessageNotContaining(secret);

            assertThatThrownBy(() -> queryRunner.createCatalog("too_many_endpoints", Web3ConnectorFactory.CONNECTOR_NAME, java.util.Map.of(
                    "web3.ethereum.rpc-url", "http://127.0.0.1:8000",
                    "web3.ethereum.rpc-fallback-urls", java.util.stream.IntStream.range(1, 9)
                            .mapToObj(index -> "http://127.0.0.1:" + (8000 + index))
                            .collect(java.util.stream.Collectors.joining(",")))))
                    .hasMessageContaining("must contain at most 8 endpoints");

            String aptosSecret = "do-not-leak-aptos-token";
            assertThatThrownBy(() -> queryRunner.createCatalog("invalid_aptos_url", Web3ConnectorFactory.CONNECTOR_NAME, java.util.Map.of(
                    "web3.aptos.rest-url", "https://" + aptosSecret + "@example.com")))
                    .hasMessageContaining("web3.aptos.rest-url must contain HTTP(S) origins")
                    .hasMessageNotContaining(aptosSecret);

            String solanaSecret = "do-not-leak-solana-token";
            assertThatThrownBy(() -> queryRunner.createCatalog("invalid_solana_url", Web3ConnectorFactory.CONNECTOR_NAME, java.util.Map.of(
                    "web3.solana.rpc-url", "http://" + solanaSecret + "@[invalid")))
                    .hasMessageContaining("web3.solana.rpc-url contains an invalid URL")
                    .hasMessageNotContaining(solanaSecret);

            String numericSecret = "do-not-leak-numeric-token";
            assertThatThrownBy(() -> queryRunner.createCatalog("invalid_numeric_value", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.rpc.maximum-concurrency", numericSecret)))
                    .hasMessageContaining("web3.rpc.maximum-concurrency must contain an integer")
                    .hasMessageNotContaining(numericSecret);

            String sizeSecret = "do-not-leak-size-token";
            assertThatThrownBy(() -> queryRunner.createCatalog("invalid_size_value", Web3ConnectorFactory.CONNECTOR_NAME, Map.of(
                    "web3.cache.maximum-size", sizeSecret)))
                    .hasMessageContaining("web3.cache.maximum-size must contain a valid data size")
                    .hasMessageNotContaining(sizeSecret);
        }
    }

    @Test
    public void testAptosReadRequiresConfiguredEndpoint()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("aptos").build();
        try (StandaloneQueryRunner queryRunner = new StandaloneQueryRunner(session)) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of());

            assertThatThrownBy(() -> queryRunner.execute("SELECT ledger_version FROM transactions WHERE ledger_version = 10"))
                    .hasStackTraceContaining("no remote endpoint is configured for schema aptos");
        }
    }

    @Test
    public void testSolanaReadRequiresConfiguredEndpoint()
            throws Exception
    {
        Session session = testSessionBuilder().setCatalog("web3").setSchema("solana").build();
        try (StandaloneQueryRunner queryRunner = new StandaloneQueryRunner(session)) {
            queryRunner.installPlugin(new Web3Plugin());
            queryRunner.createCatalog("web3", Web3ConnectorFactory.CONNECTOR_NAME, Map.of());

            assertThatThrownBy(() -> queryRunner.execute("SELECT slot FROM blocks WHERE slot = 10"))
                    .hasStackTraceContaining("no remote endpoint is configured for schema solana");
        }
    }

    @Test
    public void testRejectsTransactionHashLimitDuringMetadataPushdown()
    {
        Web3Metadata metadata = new Web3Metadata(1);
        Web3TableHandle table = new Web3TableHandle("ethereum", "transactions", Optional.empty());
        Constraint constraint = new Constraint(TupleDomain.withColumnDomains(Map.of(
                new Web3ColumnHandle("hash", 0),
                Domain.multipleValues(VARCHAR, List.of(Slices.utf8Slice(FIRST_HASH), Slices.utf8Slice(SECOND_HASH))))));

        assertThatThrownBy(() -> metadata.applyFilter(null, table, constraint))
                .hasMessageContaining("hash predicate exceeds the configured query limit of 1");
    }

    @Test
    public void testPrefersDiscreteAccessPathWhenMultipleMethodsAreBounded()
    {
        Web3Metadata metadata = new Web3Metadata(10);
        Web3TableHandle table = new Web3TableHandle("ethereum", "transactions");
        Constraint constraint = new Constraint(TupleDomain.withColumnDomains(Map.of(
                new Web3ColumnHandle("hash", 0), Domain.singleValue(VARCHAR, Slices.utf8Slice(FIRST_HASH)),
                new Web3ColumnHandle("block_number", 1), Domain.singleValue(BIGINT, 23_000_000L))));

        var result = metadata.applyFilter(null, table, constraint).orElseThrow();
        Web3TableHandle pushed = (Web3TableHandle) result.getHandle();

        assertThat(pushed.methodName()).contains("by-hash");
        assertThat(pushed.discreteValues()).containsEntry("hash", List.of(FIRST_HASH));
        assertThat(pushed.ranges()).isEmpty();
        assertThat(result.getRemainingFilter().getDomains().orElseThrow())
                .containsKey(new Web3ColumnHandle("hash", 0));
    }

    private static HttpServer jsonRpcServer(String identity)
            throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> writeJsonRpcIdentity(exchange, identity));
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
        JsonNode responseDocument;
        if (requestDocument.isArray()) {
            responseDocument = OBJECT_MAPPER.createArrayNode().add(response);
        }
        else {
            responseDocument = response;
        }
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
