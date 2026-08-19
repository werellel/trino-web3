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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestChainRegistry
{
    @Test
    void testRegistryIsDeterministicAndSupportsCopyOnWriteChanges()
    {
        ChainAdapter solana = new DeclarativeChainAdapter(TestingDescriptors.chain("solana", "solana"));
        ChainAdapter aptos = new DeclarativeChainAdapter(TestingDescriptors.chain("aptos", "aptos"));

        ChainRegistry registry = ChainRegistry.of(solana).withAdapter(aptos);

        assertThat(registry.adapters().stream().map(adapter -> adapter.descriptor().name()))
                .containsExactly("aptos", "solana");
        assertThat(registry.adapterForSchema("aptos")).contains(aptos);
        assertThat(registry.withoutSchema("aptos").adapters()).containsExactly(solana);
        assertThat(registry.adapters()).containsExactly(aptos, solana);
    }

    @Test
    void testRejectsDuplicateNamesAndSchemas()
    {
        ChainAdapter first = new DeclarativeChainAdapter(TestingDescriptors.chain("chain-a", "shared"));
        ChainAdapter sameName = new DeclarativeChainAdapter(TestingDescriptors.chain("chain-a", "other"));
        ChainAdapter sameSchema = new DeclarativeChainAdapter(TestingDescriptors.chain("chain-b", "shared"));

        assertThatThrownBy(() -> new ChainRegistry(List.of(first, sameName)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate chain adapter name chain-a");
        assertThatThrownBy(() -> new ChainRegistry(List.of(first, sameSchema)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate chain adapter schema shared");
    }
}
