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
}
