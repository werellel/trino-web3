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
package io.trino.plugin.web3.chain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestChainDescriptorCodec
{
    @Test
    void testRoundTrip()
    {
        ChainDescriptor descriptor = TestingDescriptors.chain("test-chain", "test_chain");

        ChainDescriptor decoded = ChainDescriptorCodec.fromJson(ChainDescriptorCodec.toJson(descriptor));

        assertThat(decoded).isEqualTo(descriptor);
        assertThat(decoded.toString()).doesNotContain("chain_getItem", "/id");
    }

    @Test
    void testRejectsUnknownDescriptorFields()
    {
        String json = ChainDescriptorCodec.toJson(TestingDescriptors.chain("test-chain", "test_chain"));
        String withUnknownField = json.replaceFirst("\\{", "{\\\"unknown\\\":true,");

        assertThatThrownBy(() -> ChainDescriptorCodec.fromJson(withUnknownField))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid chain descriptor JSON")
                .rootCause()
                .hasMessageContaining("Unrecognized field \"unknown\"");
    }

    @Test
    void testRejectsUnsupportedApiVersion()
    {
        String json = ChainDescriptorCodec.toJson(TestingDescriptors.chain("test-chain", "test_chain"))
                .replace(ChainDescriptor.SUPPORTED_API_VERSION, "web3.trino.io/v2");

        assertThatThrownBy(() -> ChainDescriptorCodec.fromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid chain descriptor JSON")
                .rootCause()
                .hasMessage("unsupported chain descriptor apiVersion web3.trino.io/v2");
    }

    @Test
    void testRejectsTrailingJsonTokens()
    {
        String json = ChainDescriptorCodec.toJson(TestingDescriptors.chain("test-chain", "test_chain"));

        assertThatThrownBy(() -> ChainDescriptorCodec.fromJson(json + " true"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid chain descriptor JSON");
    }
}
