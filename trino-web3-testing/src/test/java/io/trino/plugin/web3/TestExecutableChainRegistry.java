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

import io.trino.plugin.web3.adapter.ExecutableChainRegistry;
import io.trino.plugin.web3.aptos.AptosChainAdapter;
import io.trino.plugin.web3.bitcoin.BitcoinChainAdapter;
import io.trino.plugin.web3.bitcoincash.BitcoinCashChainAdapter;
import io.trino.plugin.web3.dogecoin.DogecoinChainAdapter;
import io.trino.plugin.web3.evm.EthereumChainAdapter;
import io.trino.plugin.web3.litecoin.LitecoinChainAdapter;
import io.trino.plugin.web3.solana.SolanaChainAdapter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class TestExecutableChainRegistry
{
    @Test
    void testComposesExecutableJsonRpcAndRestAdaptersByNativeSchema()
    {
        ExecutableChainRegistry registry = ExecutableChainRegistry.of(
                new EthereumChainAdapter(),
                new SolanaChainAdapter(),
                new AptosChainAdapter(),
                new BitcoinChainAdapter(),
                new LitecoinChainAdapter(),
                new DogecoinChainAdapter(),
                new BitcoinCashChainAdapter());

        assertThat(registry.descriptors().descriptors())
                .extracting(descriptor -> descriptor.schemaName())
                .containsExactly("aptos", "bitcoin", "bitcoincash", "dogecoin", "ethereum", "litecoin", "solana");
        assertThat(registry.adapterForSchema("ethereum")).isInstanceOf(EthereumChainAdapter.class);
        assertThat(registry.adapterForSchema("solana")).isInstanceOf(SolanaChainAdapter.class);
        assertThat(registry.adapterForSchema("aptos")).isInstanceOf(AptosChainAdapter.class);
        assertThat(registry.adapterForSchema("bitcoin")).isInstanceOf(BitcoinChainAdapter.class);
        assertThat(registry.adapterForSchema("litecoin")).isInstanceOf(LitecoinChainAdapter.class);
        assertThat(registry.adapterForSchema("dogecoin")).isInstanceOf(DogecoinChainAdapter.class);
        assertThat(registry.adapterForSchema("bitcoincash")).isInstanceOf(BitcoinCashChainAdapter.class);
        assertThat(new Web3Metadata(10).listSchemaNames(null))
                .containsExactlyElementsOf(registry.descriptors().descriptors().stream()
                        .map(descriptor -> descriptor.schemaName())
                        .toList());
    }
}
