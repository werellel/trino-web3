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

import io.trino.Session;
import io.trino.testing.MaterializedResult;
import io.trino.testing.StandaloneQueryRunner;
import org.junit.jupiter.api.Test;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestWeb3Catalog
{
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
}
