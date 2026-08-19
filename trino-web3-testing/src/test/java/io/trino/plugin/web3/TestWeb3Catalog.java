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

import io.airlift.slice.Slices;
import io.trino.Session;
import io.trino.plugin.web3.core.Web3TableHandle;
import io.trino.plugin.web3.evm.EthereumTransactionsTable;
import io.trino.spi.connector.Constraint;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.testing.MaterializedResult;
import io.trino.testing.StandaloneQueryRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.trino.spi.type.VarcharType.VARCHAR;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestWeb3Catalog
{
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
            assertThat(result.getOnlyColumn()).containsExactly("ethereum", "information_schema");
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
                    .containsExactly("ethereum", "information_schema");

            assertThatThrownBy(() -> queryRunner.createCatalog("invalid_hash_limit", Web3ConnectorFactory.CONNECTOR_NAME, java.util.Map.of(
                    "web3.maximum-transaction-hashes-per-query", "0")))
                    .hasMessageContaining("web3.maximum-transaction-hashes-per-query must be between 1 and 10000");

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
        }
    }

    @Test
    public void testRejectsTransactionHashLimitDuringMetadataPushdown()
    {
        Web3Metadata metadata = new Web3Metadata(1);
        Web3TableHandle table = new Web3TableHandle("ethereum", "transactions", Optional.empty());
        Constraint constraint = new Constraint(TupleDomain.withColumnDomains(Map.of(
                EthereumTransactionsTable.HASH_COLUMN,
                Domain.multipleValues(VARCHAR, List.of(Slices.utf8Slice(FIRST_HASH), Slices.utf8Slice(SECOND_HASH))))));

        assertThatThrownBy(() -> metadata.applyFilter(null, table, constraint))
                .hasMessageContaining("hash predicate exceeds the configured query limit of 1");
    }
}
