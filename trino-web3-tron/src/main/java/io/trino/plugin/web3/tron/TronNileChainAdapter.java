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
package io.trino.plugin.web3.tron;

import com.fasterxml.jackson.databind.JsonNode;
import io.trino.plugin.web3.adapter.ChainDataClient;
import io.trino.plugin.web3.adapter.ChainScan;
import io.trino.plugin.web3.adapter.ChainSplit;
import io.trino.plugin.web3.adapter.ChainSplitLimits;
import io.trino.plugin.web3.adapter.EndpointIdentityProbe;
import io.trino.plugin.web3.adapter.ExecutableChainAdapter;
import io.trino.plugin.web3.chain.ChainDescriptor;
import io.trino.plugin.web3.chain.ChainDescriptorCodec;
import io.trino.plugin.web3.runtime.RemoteExecutionRuntime;
import io.trino.plugin.web3.runtime.RestRemoteRequest;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TronNileChainAdapter implements ExecutableChainAdapter
{
    private final ChainDescriptor descriptor = loadDescriptor();
    private final TronChainAdapter delegate = new TronChainAdapter();

    @Override public ChainDescriptor descriptor() { return descriptor; }
    @Override public EndpointIdentityProbe endpointIdentityProbe() { return new EndpointIdentityProbe(new RestRemoteRequest("POST", "/jsonrpc", Map.of(), Optional.of(requestBody())), TronNileChainAdapter::identity); }
    @Override public List<ChainSplit> planSplits(ChainScan scan, ChainSplitLimits limits) { return delegate.planSplits(scan, limits); }
    @Override public ChainDataClient createDataClient(RemoteExecutionRuntime runtime) { return delegate.createDataClient(runtime); }
    private static String identity(JsonNode response)
    {
        if (!response.path("result").asText().equals("0xcd8690dc")) {
            throw new IllegalArgumentException("invalid Tron Nile node identity");
        }
        return response.path("result").textValue();
    }

    private static ObjectNode requestBody() { ObjectNode body = JsonNodeFactory.instance.objectNode(); body.put("jsonrpc", "2.0"); body.put("method", "eth_chainId"); body.set("params", JsonNodeFactory.instance.arrayNode()); body.put("id", 1); return body; }
    private static ChainDescriptor loadDescriptor() { try (InputStream input = TronNileChainAdapter.class.getResourceAsStream("tron-chain.json")) { ChainDescriptor base = ChainDescriptorCodec.fromJson(input); return new ChainDescriptor(base.apiVersion(), "tron_nile", "tron_nile", base.adapterVersion(), base.tables()); } catch (Exception e) { throw new IllegalStateException("failed to load Tron Nile descriptor", e); } }
}
