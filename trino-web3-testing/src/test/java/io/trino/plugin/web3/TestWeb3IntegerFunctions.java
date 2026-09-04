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

public class TestWeb3IntegerFunctions
{
    @Test
    public void testUInt256ArithmeticAndAggregation()
    {
        try (StandaloneQueryRunner queryRunner = queryRunner()) {
            MaterializedResult result = queryRunner.execute("""
                    SELECT
                        CAST(web3_uint256('115792089237316195423570985008687907853269984665640564039457584007913129639935') AS varchar) AS max_value,
                        CAST(web3_uint256('10') + web3_uint256('2') AS varchar) AS sum_value,
                        CAST(sum(value) AS varchar) AS aggregate_value
                    FROM (VALUES web3_uint256('1'), web3_uint256('2')) AS t(value)
                    """);
            assertThat(result.getMaterializedRows().get(0).getField(0))
                    .isEqualTo("115792089237316195423570985008687907853269984665640564039457584007913129639935");
            assertThat(result.getMaterializedRows().get(0).getField(1)).isEqualTo("12");
            assertThat(result.getMaterializedRows().get(0).getField(2)).isEqualTo("3");
        }
    }

    @Test
    public void testInt256SignedOrderingAndAggregation()
    {
        try (StandaloneQueryRunner queryRunner = queryRunner()) {
            MaterializedResult result = queryRunner.execute("""
                    SELECT
                        CAST(web3_int256('-2') + web3_int256('5') AS varchar),
                        CAST(sum(value) AS varchar)
                    FROM (VALUES web3_int256('-2'), web3_int256('5')) AS t(value)
                    """);
            assertThat(result.getMaterializedRows().get(0).getField(0)).isEqualTo("3");
            assertThat(result.getMaterializedRows().get(0).getField(1)).isEqualTo("3");
            assertThat(queryRunner.execute("""
                    SELECT CAST(value AS varchar)
                    FROM (VALUES web3_int256('-1'), web3_int256('1')) AS t(value)
                    ORDER BY value
                    """).getOnlyColumn()).containsExactly("-1", "1");
        }
    }

    @Test
    public void testIntegerBoundsAndBinaryConversion()
    {
        try (StandaloneQueryRunner queryRunner = queryRunner()) {
            assertThat(queryRunner.execute("SELECT CAST(web3_varbinary_to_uint256(from_hex('ff')) AS varchar)").getOnlyColumn())
                    .containsExactly("255");
            assertThat(queryRunner.execute("SELECT CAST(web3_varbinary_to_int256(from_hex('ff')) AS varchar)").getOnlyColumn())
                    .containsExactly("-1");
            assertThatThrownBy(() -> queryRunner.execute("SELECT web3_uint256('-1')"))
                    .hasMessageContaining("Invalid 256-bit integer");
            assertThatThrownBy(() -> queryRunner.execute("SELECT web3_uint256('115792089237316195423570985008687907853269984665640564039457584007913129639935') + web3_uint256('1')"))
                    .hasMessageContaining("256-bit integer addition overflow");
            assertThatThrownBy(() -> queryRunner.execute("SELECT web3_int256('-1') / web3_int256('0')"))
                    .hasMessageContaining("INT256 division by zero");
        }
    }

    @Test
    public void testBase58RoundTripAndValidation()
    {
        try (StandaloneQueryRunner queryRunner = queryRunner()) {
            assertThat(queryRunner.execute("SELECT to_hex(web3_from_base58('11111111111111111111111111111111'))").getOnlyColumn())
                    .containsExactly("0000000000000000000000000000000000000000000000000000000000000000");
            assertThat(queryRunner.execute("SELECT web3_to_base58(from_hex('00000102ff'))").getOnlyColumn())
                    .containsExactly("11LiA");
            assertThat(queryRunner.execute("SELECT web3_to_base58(web3_from_base58('3MN'))").getOnlyColumn())
                    .containsExactly("3MN");
            assertThatThrownBy(() -> queryRunner.execute("SELECT web3_from_base58('0')"))
                    .hasMessageContaining("Invalid Base58 character");
        }
    }

    @Test
    public void testTrySumMinAndMax()
    {
        try (StandaloneQueryRunner queryRunner = queryRunner()) {
            MaterializedResult result = queryRunner.execute("""
                    SELECT
                        CAST(min(value) AS varchar),
                        CAST(max(value) AS varchar),
                        CAST(try_sum(value) AS varchar)
                    FROM (VALUES web3_uint256('9'), web3_uint256('2'), web3_uint256('7')) AS t(value)
                    """);
            assertThat(result.getMaterializedRows().get(0).getField(0)).isEqualTo("2");
            assertThat(result.getMaterializedRows().get(0).getField(1)).isEqualTo("9");
            assertThat(result.getMaterializedRows().get(0).getField(2)).isEqualTo("18");

            assertThat(queryRunner.execute("""
                    SELECT try_sum(value)
                    FROM (VALUES
                        web3_uint256('115792089237316195423570985008687907853269984665640564039457584007913129639935'),
                        web3_uint256('1')) AS t(value)
                    """).getOnlyColumn()).containsExactly((Object) null);
        }
    }

    private static StandaloneQueryRunner queryRunner()
    {
        Session session = testSessionBuilder().setCatalog("web3").build();
        StandaloneQueryRunner queryRunner = new StandaloneQueryRunner(session);
        queryRunner.installPlugin(new Web3Plugin());
        return queryRunner;
    }
}
