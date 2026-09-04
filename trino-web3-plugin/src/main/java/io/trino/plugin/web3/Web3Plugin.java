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

import io.trino.plugin.web3.functions.Int256Aggregations;
import io.trino.plugin.web3.functions.Int256MaxAggregations;
import io.trino.plugin.web3.functions.Int256MinAggregations;
import io.trino.plugin.web3.functions.Int256TrySumAggregations;
import io.trino.plugin.web3.functions.Int256Type;
import io.trino.plugin.web3.functions.UInt256Aggregations;
import io.trino.plugin.web3.functions.UInt256MaxAggregations;
import io.trino.plugin.web3.functions.UInt256MinAggregations;
import io.trino.plugin.web3.functions.UInt256TrySumAggregations;
import io.trino.plugin.web3.functions.UInt256Type;
import io.trino.plugin.web3.functions.Web3Base58Functions;
import io.trino.plugin.web3.functions.Web3IntegerFunctions;
import io.trino.spi.Plugin;
import io.trino.spi.connector.ConnectorFactory;
import io.trino.spi.type.Type;

import java.util.List;
import java.util.Set;

public final class Web3Plugin
        implements Plugin
{
    @Override
    public Iterable<ConnectorFactory> getConnectorFactories()
    {
        return List.of(new Web3ConnectorFactory());
    }

    @Override
    public Iterable<Type> getTypes()
    {
        return List.of(UInt256Type.UINT256, Int256Type.INT256);
    }

    @Override
    public Set<Class<?>> getFunctions()
    {
        return Set.of(
                Web3IntegerFunctions.class,
                Web3Base58Functions.class,
                UInt256Aggregations.class,
                Int256Aggregations.class,
                UInt256TrySumAggregations.class,
                Int256TrySumAggregations.class,
                UInt256MinAggregations.class,
                UInt256MaxAggregations.class,
                Int256MinAggregations.class,
                Int256MaxAggregations.class);
    }
}
