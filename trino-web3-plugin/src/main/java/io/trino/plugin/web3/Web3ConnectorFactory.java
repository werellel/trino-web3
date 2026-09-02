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

import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorContext;
import io.trino.spi.connector.ConnectorFactory;
import io.trino.plugin.web3.runtime.ExecutionPolicy;
import io.airlift.units.DataSize;
import io.trino.plugin.web3.runtime.RemoteCacheConfig;

import java.time.Duration;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public final class Web3ConnectorFactory
        implements ConnectorFactory
{
    public static final String CONNECTOR_NAME = "web3";
    private static final String ETHEREUM_RPC_URL = "web3.ethereum.rpc-url";
    private static final String ETHEREUM_RPC_FALLBACK_URLS = "web3.ethereum.rpc-fallback-urls";
    private static final String BASE_RPC_URL = "web3.base.rpc-url";
    private static final String BASE_RPC_FALLBACK_URLS = "web3.base.rpc-fallback-urls";
    private static final String OPTIMISM_RPC_URL = "web3.optimism.rpc-url";
    private static final String OPTIMISM_RPC_FALLBACK_URLS = "web3.optimism.rpc-fallback-urls";
    private static final String ARBITRUM_RPC_URL = "web3.arbitrum.rpc-url";
    private static final String ARBITRUM_RPC_FALLBACK_URLS = "web3.arbitrum.rpc-fallback-urls";
    private static final String BNB_RPC_URL = "web3.bnb.rpc-url";
    private static final String BNB_RPC_FALLBACK_URLS = "web3.bnb.rpc-fallback-urls";
    private static final String POLYGON_RPC_URL = "web3.polygon.rpc-url";
    private static final String POLYGON_RPC_FALLBACK_URLS = "web3.polygon.rpc-fallback-urls";
    private static final String AVALANCHE_RPC_URL = "web3.avalanche.rpc-url";
    private static final String AVALANCHE_RPC_FALLBACK_URLS = "web3.avalanche.rpc-fallback-urls";
    private static final String GNOSIS_RPC_URL = "web3.gnosis.rpc-url";
    private static final String GNOSIS_RPC_FALLBACK_URLS = "web3.gnosis.rpc-fallback-urls";
    private static final String KAIA_RPC_URL = "web3.kaia.rpc-url";
    private static final String KAIA_RPC_FALLBACK_URLS = "web3.kaia.rpc-fallback-urls";
    private static final String ARC_RPC_URL = "web3.arc.rpc-url";
    private static final String ARC_RPC_FALLBACK_URLS = "web3.arc.rpc-fallback-urls";
    private static final String STORY_RPC_URL = "web3.story.rpc-url";
    private static final String STORY_RPC_FALLBACK_URLS = "web3.story.rpc-fallback-urls";
    private static final String BOBA_RPC_URL = "web3.boba.rpc-url";
    private static final String BOBA_RPC_FALLBACK_URLS = "web3.boba.rpc-fallback-urls";
    private static final String CELO_RPC_URL = "web3.celo.rpc-url";
    private static final String CELO_RPC_FALLBACK_URLS = "web3.celo.rpc-fallback-urls";
    private static final String HYPEREVM_RPC_URL = "web3.hyperevm.rpc-url";
    private static final String HYPEREVM_RPC_FALLBACK_URLS = "web3.hyperevm.rpc-fallback-urls";
    private static final String ABSTRACT_RPC_URL = "web3.abstract.rpc-url";
    private static final String ABSTRACT_RPC_FALLBACK_URLS = "web3.abstract.rpc-fallback-urls";
    private static final String ANIME_RPC_URL = "web3.anime.rpc-url";
    private static final String ANIME_RPC_FALLBACK_URLS = "web3.anime.rpc-fallback-urls";
    private static final String APECHAIN_RPC_URL = "web3.apechain.rpc-url";
    private static final String APECHAIN_RPC_FALLBACK_URLS = "web3.apechain.rpc-fallback-urls";
    private static final String DEGEN_RPC_URL = "web3.degen.rpc-url";
    private static final String DEGEN_RPC_FALLBACK_URLS = "web3.degen.rpc-fallback-urls";
    private static final String INK_RPC_URL = "web3.ink.rpc-url";
    private static final String INK_RPC_FALLBACK_URLS = "web3.ink.rpc-fallback-urls";
    private static final String JOVAY_RPC_URL = "web3.jovay.rpc-url";
    private static final String JOVAY_RPC_FALLBACK_URLS = "web3.jovay.rpc-fallback-urls";
    private static final String CROSSFI_RPC_URL = "web3.crossfi.rpc-url";
    private static final String CROSSFI_RPC_FALLBACK_URLS = "web3.crossfi.rpc-fallback-urls";
    private static final String LINEA_RPC_URL = "web3.linea.rpc-url";
    private static final String LINEA_RPC_FALLBACK_URLS = "web3.linea.rpc-fallback-urls";
    private static final String ETHEREUM_SEPOLIA_RPC_URL = "web3.ethereum-sepolia.rpc-url";
    private static final String ETHEREUM_SEPOLIA_RPC_FALLBACK_URLS = "web3.ethereum-sepolia.rpc-fallback-urls";
    private static final String BASE_SEPOLIA_RPC_URL = "web3.base-sepolia.rpc-url";
    private static final String BASE_SEPOLIA_RPC_FALLBACK_URLS = "web3.base-sepolia.rpc-fallback-urls";
    private static final String OPTIMISM_SEPOLIA_RPC_URL = "web3.optimism-sepolia.rpc-url";
    private static final String OPTIMISM_SEPOLIA_RPC_FALLBACK_URLS = "web3.optimism-sepolia.rpc-fallback-urls";
    private static final String ARBITRUM_SEPOLIA_RPC_URL = "web3.arbitrum-sepolia.rpc-url";
    private static final String ARBITRUM_SEPOLIA_RPC_FALLBACK_URLS = "web3.arbitrum-sepolia.rpc-fallback-urls";
    private static final String BNB_TESTNET_RPC_URL = "web3.bnb-testnet.rpc-url";
    private static final String BNB_TESTNET_RPC_FALLBACK_URLS = "web3.bnb-testnet.rpc-fallback-urls";
    private static final String POLYGON_AMOY_RPC_URL = "web3.polygon-amoy.rpc-url";
    private static final String POLYGON_AMOY_RPC_FALLBACK_URLS = "web3.polygon-amoy.rpc-fallback-urls";
    private static final String AVALANCHE_FUJI_RPC_URL = "web3.avalanche-fuji.rpc-url";
    private static final String AVALANCHE_FUJI_RPC_FALLBACK_URLS = "web3.avalanche-fuji.rpc-fallback-urls";
    private static final String GNOSIS_CHIADO_RPC_URL = "web3.gnosis-chiado.rpc-url";
    private static final String GNOSIS_CHIADO_RPC_FALLBACK_URLS = "web3.gnosis-chiado.rpc-fallback-urls";
    private static final String KAIA_KAIROS_RPC_URL = "web3.kaia-kairos.rpc-url";
    private static final String KAIA_KAIROS_RPC_FALLBACK_URLS = "web3.kaia-kairos.rpc-fallback-urls";
    private static final String ARC_TESTNET_RPC_URL = "web3.arc-testnet.rpc-url";
    private static final String ARC_TESTNET_RPC_FALLBACK_URLS = "web3.arc-testnet.rpc-fallback-urls";
    private static final String STORY_AENEID_RPC_URL = "web3.story-aeneid.rpc-url";
    private static final String STORY_AENEID_RPC_FALLBACK_URLS = "web3.story-aeneid.rpc-fallback-urls";
    private static final String BOBA_SEPOLIA_RPC_URL = "web3.boba-sepolia.rpc-url";
    private static final String BOBA_SEPOLIA_RPC_FALLBACK_URLS = "web3.boba-sepolia.rpc-fallback-urls";
    private static final String CELO_SEPOLIA_RPC_URL = "web3.celo-sepolia.rpc-url";
    private static final String CELO_SEPOLIA_RPC_FALLBACK_URLS = "web3.celo-sepolia.rpc-fallback-urls";
    private static final String HYPEREVM_TESTNET_RPC_URL = "web3.hyperevm-testnet.rpc-url";
    private static final String HYPEREVM_TESTNET_RPC_FALLBACK_URLS = "web3.hyperevm-testnet.rpc-fallback-urls";
    private static final String ABSTRACT_SEPOLIA_RPC_URL = "web3.abstract-sepolia.rpc-url";
    private static final String ABSTRACT_SEPOLIA_RPC_FALLBACK_URLS = "web3.abstract-sepolia.rpc-fallback-urls";
    private static final String ANIME_TESTNET_RPC_URL = "web3.anime-testnet.rpc-url";
    private static final String ANIME_TESTNET_RPC_FALLBACK_URLS = "web3.anime-testnet.rpc-fallback-urls";
    private static final String APECHAIN_CURTIS_RPC_URL = "web3.apechain-curtis.rpc-url";
    private static final String APECHAIN_CURTIS_RPC_FALLBACK_URLS = "web3.apechain-curtis.rpc-fallback-urls";
    private static final String INK_SEPOLIA_RPC_URL = "web3.ink-sepolia.rpc-url";
    private static final String INK_SEPOLIA_RPC_FALLBACK_URLS = "web3.ink-sepolia.rpc-fallback-urls";
    private static final String JOVAY_SEPOLIA_RPC_URL = "web3.jovay-sepolia.rpc-url";
    private static final String JOVAY_SEPOLIA_RPC_FALLBACK_URLS = "web3.jovay-sepolia.rpc-fallback-urls";
    private static final String CROSSFI_TESTNET_RPC_URL = "web3.crossfi-testnet.rpc-url";
    private static final String CROSSFI_TESTNET_RPC_FALLBACK_URLS = "web3.crossfi-testnet.rpc-fallback-urls";
    private static final String LINEA_SEPOLIA_RPC_URL = "web3.linea-sepolia.rpc-url";
    private static final String LINEA_SEPOLIA_RPC_FALLBACK_URLS = "web3.linea-sepolia.rpc-fallback-urls";
    private static final String SOLANA_RPC_URL = "web3.solana.rpc-url";
    private static final String SOLANA_RPC_FALLBACK_URLS = "web3.solana.rpc-fallback-urls";
    private static final String APTOS_REST_URL = "web3.aptos.rest-url";
    private static final String APTOS_REST_FALLBACK_URLS = "web3.aptos.rest-fallback-urls";
    private static final String TRON_API_URL = "web3.tron.api-url";
    private static final String TRON_API_FALLBACK_URLS = "web3.tron.api-fallback-urls";
    private static final String SUI_RPC_URL = "web3.sui.rpc-url";
    private static final String SUI_RPC_FALLBACK_URLS = "web3.sui.rpc-fallback-urls";
    private static final String COSMOS_REST_URL = "web3.cosmos.rest-url";
    private static final String COSMOS_REST_FALLBACK_URLS = "web3.cosmos.rest-fallback-urls";
    private static final String OSMOSIS_REST_URL = "web3.osmosis.rest-url";
    private static final String OSMOSIS_REST_FALLBACK_URLS = "web3.osmosis.rest-fallback-urls";
    private static final String INJECTIVE_REST_URL = "web3.injective.rest-url";
    private static final String INJECTIVE_REST_FALLBACK_URLS = "web3.injective.rest-fallback-urls";
    private static final String SOLANA_DEVNET_RPC_URL = "web3.solana-devnet.rpc-url";
    private static final String SOLANA_DEVNET_RPC_FALLBACK_URLS = "web3.solana-devnet.rpc-fallback-urls";
    private static final String APTOS_TESTNET_REST_URL = "web3.aptos-testnet.rest-url";
    private static final String APTOS_TESTNET_REST_FALLBACK_URLS = "web3.aptos-testnet.rest-fallback-urls";
    private static final String TRON_NILE_API_URL = "web3.tron-nile.api-url";
    private static final String TRON_NILE_API_FALLBACK_URLS = "web3.tron-nile.api-fallback-urls";
    private static final String TRON_SHASTA_API_URL = "web3.tron-shasta.api-url";
    private static final String TRON_SHASTA_API_FALLBACK_URLS = "web3.tron-shasta.api-fallback-urls";
    private static final String SUI_TESTNET_RPC_URL = "web3.sui-testnet.rpc-url";
    private static final String SUI_TESTNET_RPC_FALLBACK_URLS = "web3.sui-testnet.rpc-fallback-urls";
    private static final String COSMOS_TESTNET_REST_URL = "web3.cosmos-testnet.rest-url";
    private static final String COSMOS_TESTNET_REST_FALLBACK_URLS = "web3.cosmos-testnet.rest-fallback-urls";
    private static final String OSMOSIS_TESTNET_REST_URL = "web3.osmosis-testnet.rest-url";
    private static final String OSMOSIS_TESTNET_REST_FALLBACK_URLS = "web3.osmosis-testnet.rest-fallback-urls";
    private static final String INJECTIVE_TESTNET_REST_URL = "web3.injective-testnet.rest-url";
    private static final String INJECTIVE_TESTNET_REST_FALLBACK_URLS = "web3.injective-testnet.rest-fallback-urls";
    private static final String BITCOIN_RPC_URL = "web3.bitcoin.rpc-url";
    private static final String BITCOIN_RPC_FALLBACK_URLS = "web3.bitcoin.rpc-fallback-urls";
    private static final String LITECOIN_RPC_URL = "web3.litecoin.rpc-url";
    private static final String LITECOIN_RPC_FALLBACK_URLS = "web3.litecoin.rpc-fallback-urls";
    private static final String DOGECOIN_RPC_URL = "web3.dogecoin.rpc-url";
    private static final String DOGECOIN_RPC_FALLBACK_URLS = "web3.dogecoin.rpc-fallback-urls";
    private static final String BITCOINCASH_RPC_URL = "web3.bitcoincash.rpc-url";
    private static final String BITCOINCASH_RPC_FALLBACK_URLS = "web3.bitcoincash.rpc-fallback-urls";
    private static final String BITCOIN_TESTNET_RPC_URL = "web3.bitcoin-testnet.rpc-url";
    private static final String BITCOIN_TESTNET_RPC_FALLBACK_URLS = "web3.bitcoin-testnet.rpc-fallback-urls";
    private static final String LITECOIN_TESTNET_RPC_URL = "web3.litecoin-testnet.rpc-url";
    private static final String LITECOIN_TESTNET_RPC_FALLBACK_URLS = "web3.litecoin-testnet.rpc-fallback-urls";
    private static final String DOGECOIN_TESTNET_RPC_URL = "web3.dogecoin-testnet.rpc-url";
    private static final String DOGECOIN_TESTNET_RPC_FALLBACK_URLS = "web3.dogecoin-testnet.rpc-fallback-urls";
    private static final String BITCOINCASH_TESTNET_RPC_URL = "web3.bitcoincash-testnet.rpc-url";
    private static final String BITCOINCASH_TESTNET_RPC_FALLBACK_URLS = "web3.bitcoincash-testnet.rpc-fallback-urls";
    private static final String MAXIMUM_BLOCKS_PER_SPLIT = "web3.maximum-blocks-per-split";
    private static final String MAXIMUM_BLOCKS_PER_QUERY = "web3.maximum-blocks-per-query";
    private static final String MAXIMUM_TRANSACTION_HASHES_PER_QUERY = "web3.maximum-transaction-hashes-per-query";
    private static final String MAXIMUM_RPC_REQUEST_BYTES = "web3.maximum-rpc-request-bytes";
    private static final String MAXIMUM_RPC_RESPONSE_BYTES = "web3.maximum-rpc-response-bytes";
    private static final String MAXIMUM_RPC_CONCURRENCY = "web3.rpc.maximum-concurrency";
    private static final String MAXIMUM_RPC_QUEUE_SIZE = "web3.rpc.maximum-queue-size";
    private static final String MAXIMUM_RPC_BATCH_SIZE = "web3.rpc.maximum-batch-size";
    private static final String MAXIMUM_RPC_ATTEMPTS = "web3.rpc.maximum-attempts";
    private static final String RPC_REQUESTS_PER_SECOND = "web3.rpc.requests-per-second";
    private static final String RPC_INITIAL_BACKOFF_MILLIS = "web3.rpc.initial-backoff-millis";
    private static final String RPC_MAXIMUM_BACKOFF_MILLIS = "web3.rpc.maximum-backoff-millis";
    private static final String RPC_PROVIDER_COOLDOWN_MILLIS = "web3.rpc.provider-cooldown-millis";
    private static final String RPC_JSON_RPC_BATCH_ENABLED = "web3.rpc.json-rpc-batch-enabled";
    private static final String CACHE_ENABLED = "web3.cache.enabled";
    private static final String CACHE_MAXIMUM_SIZE = "web3.cache.maximum-size";
    private static final String CACHE_MAXIMUM_ENTRY_SIZE = "web3.cache.maximum-entry-size";
    private static final String CACHE_TTL = "web3.cache.ttl";
    private static final long DEFAULT_MAXIMUM_BLOCKS_PER_SPLIT = 100;
    private static final long DEFAULT_MAXIMUM_BLOCKS_PER_QUERY = 10_000;
    private static final int DEFAULT_MAXIMUM_TRANSACTION_HASHES_PER_QUERY = 1_000;
    private static final int DEFAULT_MAXIMUM_RPC_REQUEST_BYTES = 1_048_576;
    private static final int DEFAULT_MAXIMUM_RPC_RESPONSE_BYTES = 16 * 1_048_576;

    @Override
    public String getName()
    {
        return CONNECTOR_NAME;
    }

    @Override
    public Connector create(String catalogName, Map<String, String> config, ConnectorContext context)
    {
        requireNonNull(catalogName, "catalogName is null");
        requireNonNull(config, "config is null");
        requireNonNull(context, "context is null");

        if (!config.keySet().stream().allMatch(Web3ConnectorFactory::isSupportedProperty)) {
            throw new IllegalArgumentException("Unsupported Web3 connector configuration property");
        }

        List<URI> ethereumEndpoints = parseEndpoints(config, ETHEREUM_RPC_URL, ETHEREUM_RPC_FALLBACK_URLS, false);
        if (ethereumEndpoints.size() > 8) {
            throw new IllegalArgumentException("web3.ethereum.rpc-url and fallback URLs must contain at most 8 endpoints");
        }
        List<URI> baseEndpoints = parseEndpoints(config, BASE_RPC_URL, BASE_RPC_FALLBACK_URLS, false);
        List<URI> optimismEndpoints = parseEndpoints(config, OPTIMISM_RPC_URL, OPTIMISM_RPC_FALLBACK_URLS, false);
        List<URI> arbitrumEndpoints = parseEndpoints(config, ARBITRUM_RPC_URL, ARBITRUM_RPC_FALLBACK_URLS, false);
        List<URI> bnbEndpoints = parseEndpoints(config, BNB_RPC_URL, BNB_RPC_FALLBACK_URLS, false);
        List<URI> polygonEndpoints = parseEndpoints(config, POLYGON_RPC_URL, POLYGON_RPC_FALLBACK_URLS, false);
        List<URI> avalancheEndpoints = parseEndpoints(config, AVALANCHE_RPC_URL, AVALANCHE_RPC_FALLBACK_URLS, false);
        if (baseEndpoints.size() > 8 || optimismEndpoints.size() > 8 || arbitrumEndpoints.size() > 8 || bnbEndpoints.size() > 8 || polygonEndpoints.size() > 8 || avalancheEndpoints.size() > 8) {
            throw new IllegalArgumentException("EVM RPC URLs and fallback URLs must contain at most 8 endpoints per chain");
        }
        List<URI> gnosisEndpoints = parseEndpoints(config, GNOSIS_RPC_URL, GNOSIS_RPC_FALLBACK_URLS, false);
        List<URI> kaiaEndpoints = parseEndpoints(config, KAIA_RPC_URL, KAIA_RPC_FALLBACK_URLS, false);
        List<URI> arcEndpoints = parseEndpoints(config, ARC_RPC_URL, ARC_RPC_FALLBACK_URLS, false);
        List<URI> storyEndpoints = parseEndpoints(config, STORY_RPC_URL, STORY_RPC_FALLBACK_URLS, false);
        List<URI> bobaEndpoints = parseEndpoints(config, BOBA_RPC_URL, BOBA_RPC_FALLBACK_URLS, false);
        List<URI> celoEndpoints = parseEndpoints(config, CELO_RPC_URL, CELO_RPC_FALLBACK_URLS, false);
        List<URI> hyperEvmEndpoints = parseEndpoints(config, HYPEREVM_RPC_URL, HYPEREVM_RPC_FALLBACK_URLS, false);
        List<URI> abstractEndpoints = parseEndpoints(config, ABSTRACT_RPC_URL, ABSTRACT_RPC_FALLBACK_URLS, false);
        List<URI> animeEndpoints = parseEndpoints(config, ANIME_RPC_URL, ANIME_RPC_FALLBACK_URLS, false);
        List<URI> apeChainEndpoints = parseEndpoints(config, APECHAIN_RPC_URL, APECHAIN_RPC_FALLBACK_URLS, false);
        List<URI> degenEndpoints = parseEndpoints(config, DEGEN_RPC_URL, DEGEN_RPC_FALLBACK_URLS, false);
        List<URI> inkEndpoints = parseEndpoints(config, INK_RPC_URL, INK_RPC_FALLBACK_URLS, false);
        List<URI> jovayEndpoints = parseEndpoints(config, JOVAY_RPC_URL, JOVAY_RPC_FALLBACK_URLS, false);
        List<URI> crossFiEndpoints = parseEndpoints(config, CROSSFI_RPC_URL, CROSSFI_RPC_FALLBACK_URLS, false);
        List<URI> lineaEndpoints = parseEndpoints(config, LINEA_RPC_URL, LINEA_RPC_FALLBACK_URLS, false);
        List<URI> ethereumSepoliaEndpoints = parseEndpoints(config, ETHEREUM_SEPOLIA_RPC_URL, ETHEREUM_SEPOLIA_RPC_FALLBACK_URLS, false);
        List<URI> baseSepoliaEndpoints = parseEndpoints(config, BASE_SEPOLIA_RPC_URL, BASE_SEPOLIA_RPC_FALLBACK_URLS, false);
        List<URI> optimismSepoliaEndpoints = parseEndpoints(config, OPTIMISM_SEPOLIA_RPC_URL, OPTIMISM_SEPOLIA_RPC_FALLBACK_URLS, false);
        List<URI> arbitrumSepoliaEndpoints = parseEndpoints(config, ARBITRUM_SEPOLIA_RPC_URL, ARBITRUM_SEPOLIA_RPC_FALLBACK_URLS, false);
        List<URI> bnbTestnetEndpoints = parseEndpoints(config, BNB_TESTNET_RPC_URL, BNB_TESTNET_RPC_FALLBACK_URLS, false);
        List<URI> polygonAmoyEndpoints = parseEndpoints(config, POLYGON_AMOY_RPC_URL, POLYGON_AMOY_RPC_FALLBACK_URLS, false);
        List<URI> avalancheFujiEndpoints = parseEndpoints(config, AVALANCHE_FUJI_RPC_URL, AVALANCHE_FUJI_RPC_FALLBACK_URLS, false);
        List<URI> gnosisChiadoEndpoints = parseEndpoints(config, GNOSIS_CHIADO_RPC_URL, GNOSIS_CHIADO_RPC_FALLBACK_URLS, false);
        List<URI> kaiaKairosEndpoints = parseEndpoints(config, KAIA_KAIROS_RPC_URL, KAIA_KAIROS_RPC_FALLBACK_URLS, false);
        List<URI> arcTestnetEndpoints = parseEndpoints(config, ARC_TESTNET_RPC_URL, ARC_TESTNET_RPC_FALLBACK_URLS, false);
        List<URI> storyAeneidEndpoints = parseEndpoints(config, STORY_AENEID_RPC_URL, STORY_AENEID_RPC_FALLBACK_URLS, false);
        List<URI> bobaSepoliaEndpoints = parseEndpoints(config, BOBA_SEPOLIA_RPC_URL, BOBA_SEPOLIA_RPC_FALLBACK_URLS, false);
        List<URI> celoSepoliaEndpoints = parseEndpoints(config, CELO_SEPOLIA_RPC_URL, CELO_SEPOLIA_RPC_FALLBACK_URLS, false);
        List<URI> hyperEvmTestnetEndpoints = parseEndpoints(config, HYPEREVM_TESTNET_RPC_URL, HYPEREVM_TESTNET_RPC_FALLBACK_URLS, false);
        List<URI> abstractSepoliaEndpoints = parseEndpoints(config, ABSTRACT_SEPOLIA_RPC_URL, ABSTRACT_SEPOLIA_RPC_FALLBACK_URLS, false);
        List<URI> animeTestnetEndpoints = parseEndpoints(config, ANIME_TESTNET_RPC_URL, ANIME_TESTNET_RPC_FALLBACK_URLS, false);
        List<URI> apeChainCurtisEndpoints = parseEndpoints(config, APECHAIN_CURTIS_RPC_URL, APECHAIN_CURTIS_RPC_FALLBACK_URLS, false);
        List<URI> inkSepoliaEndpoints = parseEndpoints(config, INK_SEPOLIA_RPC_URL, INK_SEPOLIA_RPC_FALLBACK_URLS, false);
        List<URI> jovaySepoliaEndpoints = parseEndpoints(config, JOVAY_SEPOLIA_RPC_URL, JOVAY_SEPOLIA_RPC_FALLBACK_URLS, false);
        List<URI> crossFiTestnetEndpoints = parseEndpoints(config, CROSSFI_TESTNET_RPC_URL, CROSSFI_TESTNET_RPC_FALLBACK_URLS, false);
        List<URI> lineaSepoliaEndpoints = parseEndpoints(config, LINEA_SEPOLIA_RPC_URL, LINEA_SEPOLIA_RPC_FALLBACK_URLS, false);
        if (List.of(gnosisEndpoints, kaiaEndpoints, arcEndpoints, storyEndpoints, bobaEndpoints, celoEndpoints, hyperEvmEndpoints, abstractEndpoints, animeEndpoints, apeChainEndpoints, degenEndpoints, inkEndpoints, jovayEndpoints, crossFiEndpoints, lineaEndpoints, ethereumSepoliaEndpoints, baseSepoliaEndpoints, optimismSepoliaEndpoints, arbitrumSepoliaEndpoints, bnbTestnetEndpoints, polygonAmoyEndpoints, avalancheFujiEndpoints, gnosisChiadoEndpoints, kaiaKairosEndpoints, arcTestnetEndpoints, storyAeneidEndpoints, bobaSepoliaEndpoints, celoSepoliaEndpoints, hyperEvmTestnetEndpoints, abstractSepoliaEndpoints, animeTestnetEndpoints, apeChainCurtisEndpoints, inkSepoliaEndpoints, jovaySepoliaEndpoints, crossFiTestnetEndpoints, lineaSepoliaEndpoints).stream().anyMatch(endpoints -> endpoints.size() > 8)) {
            throw new IllegalArgumentException("Additional EVM RPC URLs and fallback URLs must contain at most 8 endpoints per chain");
        }
        List<URI> solanaEndpoints = parseEndpoints(config, SOLANA_RPC_URL, SOLANA_RPC_FALLBACK_URLS, false);
        if (solanaEndpoints.size() > 8) {
            throw new IllegalArgumentException("web3.solana.rpc-url and fallback URLs must contain at most 8 endpoints");
        }
        List<URI> aptosEndpoints = parseEndpoints(config, APTOS_REST_URL, APTOS_REST_FALLBACK_URLS, true);
        if (aptosEndpoints.size() > 8) {
            throw new IllegalArgumentException("web3.aptos.rest-url and fallback URLs must contain at most 8 endpoints");
        }
        List<URI> tronEndpoints = parseEndpoints(config, TRON_API_URL, TRON_API_FALLBACK_URLS, true);
        if (tronEndpoints.size() > 8) {
            throw new IllegalArgumentException("web3.tron.api-url and fallback URLs must contain at most 8 endpoints");
        }
        List<URI> suiEndpoints = parseEndpoints(config, SUI_RPC_URL, SUI_RPC_FALLBACK_URLS, false);
        if (suiEndpoints.size() > 8) {
            throw new IllegalArgumentException("web3.sui.rpc-url and fallback URLs must contain at most 8 endpoints");
        }
        List<URI> cosmosEndpoints = parseEndpoints(config, COSMOS_REST_URL, COSMOS_REST_FALLBACK_URLS, true);
        List<URI> osmosisEndpoints = parseEndpoints(config, OSMOSIS_REST_URL, OSMOSIS_REST_FALLBACK_URLS, true);
        List<URI> injectiveEndpoints = parseEndpoints(config, INJECTIVE_REST_URL, INJECTIVE_REST_FALLBACK_URLS, true);
        List<URI> solanaDevnetEndpoints = parseEndpoints(config, SOLANA_DEVNET_RPC_URL, SOLANA_DEVNET_RPC_FALLBACK_URLS, false);
        List<URI> aptosTestnetEndpoints = parseEndpoints(config, APTOS_TESTNET_REST_URL, APTOS_TESTNET_REST_FALLBACK_URLS, true);
        List<URI> tronNileEndpoints = parseEndpoints(config, TRON_NILE_API_URL, TRON_NILE_API_FALLBACK_URLS, true);
        List<URI> tronShastaEndpoints = parseEndpoints(config, TRON_SHASTA_API_URL, TRON_SHASTA_API_FALLBACK_URLS, true);
        List<URI> suiTestnetEndpoints = parseEndpoints(config, SUI_TESTNET_RPC_URL, SUI_TESTNET_RPC_FALLBACK_URLS, false);
        List<URI> cosmosTestnetEndpoints = parseEndpoints(config, COSMOS_TESTNET_REST_URL, COSMOS_TESTNET_REST_FALLBACK_URLS, true);
        List<URI> osmosisTestnetEndpoints = parseEndpoints(config, OSMOSIS_TESTNET_REST_URL, OSMOSIS_TESTNET_REST_FALLBACK_URLS, true);
        List<URI> injectiveTestnetEndpoints = parseEndpoints(config, INJECTIVE_TESTNET_REST_URL, INJECTIVE_TESTNET_REST_FALLBACK_URLS, true);
        if (cosmosEndpoints.size() > 8 || osmosisEndpoints.size() > 8 || injectiveEndpoints.size() > 8) {
            throw new IllegalArgumentException("Cosmos REST URLs and fallback URLs must contain at most 8 endpoints per chain");
        }
        List<URI> bitcoinEndpoints = parseEndpoints(config, BITCOIN_RPC_URL, BITCOIN_RPC_FALLBACK_URLS, false);
        if (bitcoinEndpoints.size() > 8) {
            throw new IllegalArgumentException("web3.bitcoin.rpc-url and fallback URLs must contain at most 8 endpoints");
        }
        List<URI> litecoinEndpoints = parseEndpoints(config, LITECOIN_RPC_URL, LITECOIN_RPC_FALLBACK_URLS, false);
        if (litecoinEndpoints.size() > 8) {
            throw new IllegalArgumentException("web3.litecoin.rpc-url and fallback URLs must contain at most 8 endpoints");
        }
        List<URI> dogecoinEndpoints = parseEndpoints(config, DOGECOIN_RPC_URL, DOGECOIN_RPC_FALLBACK_URLS, false);
        if (dogecoinEndpoints.size() > 8) {
            throw new IllegalArgumentException("web3.dogecoin.rpc-url and fallback URLs must contain at most 8 endpoints");
        }
        List<URI> bitcoinCashEndpoints = parseEndpoints(config, BITCOINCASH_RPC_URL, BITCOINCASH_RPC_FALLBACK_URLS, false);
        if (bitcoinCashEndpoints.size() > 8) {
            throw new IllegalArgumentException("web3.bitcoincash.rpc-url and fallback URLs must contain at most 8 endpoints");
        }
        List<URI> bitcoinTestnetEndpoints = parseEndpoints(config, BITCOIN_TESTNET_RPC_URL, BITCOIN_TESTNET_RPC_FALLBACK_URLS, false);
        List<URI> litecoinTestnetEndpoints = parseEndpoints(config, LITECOIN_TESTNET_RPC_URL, LITECOIN_TESTNET_RPC_FALLBACK_URLS, false);
        List<URI> dogecoinTestnetEndpoints = parseEndpoints(config, DOGECOIN_TESTNET_RPC_URL, DOGECOIN_TESTNET_RPC_FALLBACK_URLS, false);
        List<URI> bitcoinCashTestnetEndpoints = parseEndpoints(config, BITCOINCASH_TESTNET_RPC_URL, BITCOINCASH_TESTNET_RPC_FALLBACK_URLS, false);
        if (List.of(solanaDevnetEndpoints, aptosTestnetEndpoints, tronNileEndpoints, tronShastaEndpoints, suiTestnetEndpoints, cosmosTestnetEndpoints, osmosisTestnetEndpoints, injectiveTestnetEndpoints, bitcoinTestnetEndpoints, litecoinTestnetEndpoints, dogecoinTestnetEndpoints, bitcoinCashTestnetEndpoints).stream().anyMatch(endpoints -> endpoints.size() > 8)) {
            throw new IllegalArgumentException("non-EVM testnet endpoints must contain at most 8 endpoints per chain");
        }
        long maximumBlocksPerSplit = Optional.ofNullable(config.get(MAXIMUM_BLOCKS_PER_SPLIT))
                .map(value -> parseBoundedPositiveLong(value, MAXIMUM_BLOCKS_PER_SPLIT, 1_000))
                .orElse(DEFAULT_MAXIMUM_BLOCKS_PER_SPLIT);
        long maximumBlocksPerQuery = Optional.ofNullable(config.get(MAXIMUM_BLOCKS_PER_QUERY))
                .map(value -> parseBoundedPositiveLong(value, MAXIMUM_BLOCKS_PER_QUERY, 10_000))
                .orElse(DEFAULT_MAXIMUM_BLOCKS_PER_QUERY);
        int maximumTransactionHashesPerQuery = parseConfiguredLong(
                config,
                MAXIMUM_TRANSACTION_HASHES_PER_QUERY,
                DEFAULT_MAXIMUM_TRANSACTION_HASHES_PER_QUERY,
                10_000);
        int maximumRequestBytes = Optional.ofNullable(config.get(MAXIMUM_RPC_REQUEST_BYTES))
                .map(value -> Math.toIntExact(parseBoundedPositiveLong(value, MAXIMUM_RPC_REQUEST_BYTES, 1_048_576)))
                .orElse(DEFAULT_MAXIMUM_RPC_REQUEST_BYTES);
        int maximumResponseBytes = Optional.ofNullable(config.get(MAXIMUM_RPC_RESPONSE_BYTES))
                .map(value -> Math.toIntExact(parseBoundedPositiveLong(value, MAXIMUM_RPC_RESPONSE_BYTES, 64 * 1_048_576L)))
                .orElse(DEFAULT_MAXIMUM_RPC_RESPONSE_BYTES);
        ExecutionPolicy defaults = ExecutionPolicy.defaults();
        ExecutionPolicy executionPolicy = new ExecutionPolicy(
                parseConfiguredLong(config, MAXIMUM_RPC_CONCURRENCY, defaults.maximumConcurrency(), 64),
                parseConfiguredLong(config, MAXIMUM_RPC_QUEUE_SIZE, defaults.maximumQueueSize(), 4_096),
                parseConfiguredLong(config, MAXIMUM_RPC_BATCH_SIZE, defaults.maximumBatchSize(), 100),
                parseConfiguredLong(config, MAXIMUM_RPC_ATTEMPTS, defaults.maximumAttempts(), 5),
                parseConfiguredLong(config, RPC_REQUESTS_PER_SECOND, defaults.requestsPerSecond(), 10_000),
                Duration.ofMillis(parseConfiguredLong(config, RPC_INITIAL_BACKOFF_MILLIS, defaults.initialBackoff().toMillis(), 30_000)),
                Duration.ofMillis(parseConfiguredLong(config, RPC_MAXIMUM_BACKOFF_MILLIS, defaults.maximumBackoff().toMillis(), 30_000)),
                Duration.ofMillis(parseConfiguredLong(config, RPC_PROVIDER_COOLDOWN_MILLIS, defaults.providerCooldown().toMillis(), 30_000)));
        boolean jsonRpcBatchEnabled = Optional.ofNullable(config.get(RPC_JSON_RPC_BATCH_ENABLED))
                .map(value -> parseBoolean(value, RPC_JSON_RPC_BATCH_ENABLED))
                .orElse(true);
        boolean cacheEnabled = Optional.ofNullable(config.get(CACHE_ENABLED))
                .map(value -> parseBoolean(value, CACHE_ENABLED))
                .orElse(false);
        long cacheMaximumSize = Optional.ofNullable(config.get(CACHE_MAXIMUM_SIZE))
                .map(value -> parseDataSize(value, CACHE_MAXIMUM_SIZE, 1_048_576, 1_073_741_824))
                .orElse(128L * 1_048_576);
        int cacheMaximumEntrySize = Math.toIntExact(Optional.ofNullable(config.get(CACHE_MAXIMUM_ENTRY_SIZE))
                .map(value -> parseDataSize(value, CACHE_MAXIMUM_ENTRY_SIZE, 1_024, 1_073_741_824))
                .orElse(8L * 1_048_576));
        if (cacheEnabled && cacheMaximumEntrySize > cacheMaximumSize) {
            throw new IllegalArgumentException("web3.cache.maximum-entry-size must not exceed web3.cache.maximum-size");
        }
        if (cacheEnabled && cacheMaximumEntrySize > maximumResponseBytes) {
            throw new IllegalArgumentException("web3.cache.maximum-entry-size must not exceed web3.maximum-rpc-response-bytes");
        }
        Optional<Duration> cacheTtl = Optional.ofNullable(config.get(CACHE_TTL))
                .map(value -> parseDuration(value, CACHE_TTL));
        RemoteCacheConfig cacheConfig = new RemoteCacheConfig(cacheEnabled, cacheMaximumSize, cacheMaximumEntrySize, cacheTtl);
        return new Web3Connector(
                maximumBlocksPerSplit,
                maximumBlocksPerQuery,
                maximumTransactionHashesPerQuery,
                maximumRequestBytes,
                maximumResponseBytes,
                ethereumEndpoints,
                baseEndpoints,
                optimismEndpoints,
                arbitrumEndpoints,
                bnbEndpoints,
                polygonEndpoints,
                avalancheEndpoints,
                solanaEndpoints,
                aptosEndpoints,
                tronEndpoints,
                suiEndpoints,
                cosmosEndpoints,
                osmosisEndpoints,
                injectiveEndpoints,
                bitcoinEndpoints,
                litecoinEndpoints,
                dogecoinEndpoints,
                bitcoinCashEndpoints,
                jsonRpcBatchEnabled,
                executionPolicy,
                cacheConfig,
                context.getTypeManager(),
                Map.ofEntries(
                        Map.entry("gnosis", gnosisEndpoints),
                        Map.entry("kaia", kaiaEndpoints),
                        Map.entry("arc", arcEndpoints),
                        Map.entry("story", storyEndpoints),
                        Map.entry("boba", bobaEndpoints),
                        Map.entry("celo", celoEndpoints),
                        Map.entry("hyperevm", hyperEvmEndpoints),
                        Map.entry("abstract", abstractEndpoints),
                        Map.entry("anime", animeEndpoints),
                        Map.entry("apechain", apeChainEndpoints),
                        Map.entry("degen", degenEndpoints),
                        Map.entry("ink", inkEndpoints),
                        Map.entry("jovay", jovayEndpoints),
                        Map.entry("crossfi", crossFiEndpoints),
                        Map.entry("linea", lineaEndpoints),
                        Map.entry("ethereum_sepolia", ethereumSepoliaEndpoints),
                        Map.entry("base_sepolia", baseSepoliaEndpoints),
                        Map.entry("optimism_sepolia", optimismSepoliaEndpoints),
                        Map.entry("arbitrum_sepolia", arbitrumSepoliaEndpoints),
                        Map.entry("bnb_testnet", bnbTestnetEndpoints),
                        Map.entry("polygon_amoy", polygonAmoyEndpoints),
                        Map.entry("avalanche_fuji", avalancheFujiEndpoints),
                        Map.entry("gnosis_chiado", gnosisChiadoEndpoints),
                        Map.entry("kaia_kairos", kaiaKairosEndpoints),
                        Map.entry("arc_testnet", arcTestnetEndpoints),
                        Map.entry("story_aeneid", storyAeneidEndpoints),
                        Map.entry("boba_sepolia", bobaSepoliaEndpoints),
                        Map.entry("celo_sepolia", celoSepoliaEndpoints),
                        Map.entry("hyperevm_testnet", hyperEvmTestnetEndpoints),
                        Map.entry("abstract_sepolia", abstractSepoliaEndpoints),
                        Map.entry("anime_testnet", animeTestnetEndpoints),
                        Map.entry("apechain_curtis", apeChainCurtisEndpoints),
                        Map.entry("ink_sepolia", inkSepoliaEndpoints),
                        Map.entry("jovay_sepolia", jovaySepoliaEndpoints),
                        Map.entry("crossfi_testnet", crossFiTestnetEndpoints),
                        Map.entry("linea_sepolia", lineaSepoliaEndpoints),
                        Map.entry("solana_devnet", solanaDevnetEndpoints),
                        Map.entry("aptos_testnet", aptosTestnetEndpoints),
                        Map.entry("tron_nile", tronNileEndpoints),
                        Map.entry("tron_shasta", tronShastaEndpoints),
                        Map.entry("sui_testnet", suiTestnetEndpoints),
                        Map.entry("cosmos_testnet", cosmosTestnetEndpoints),
                        Map.entry("osmosis_testnet", osmosisTestnetEndpoints),
                        Map.entry("injective_testnet", injectiveTestnetEndpoints),
                        Map.entry("bitcoin_testnet", bitcoinTestnetEndpoints),
                        Map.entry("litecoin_testnet", litecoinTestnetEndpoints),
                        Map.entry("dogecoin_testnet", dogecoinTestnetEndpoints),
                        Map.entry("bitcoincash_testnet", bitcoinCashTestnetEndpoints)));
    }

    private static boolean isSupportedProperty(String key)
    {
        return key.equals(ETHEREUM_RPC_URL) ||
                key.equals(ETHEREUM_RPC_FALLBACK_URLS) ||
                key.equals(BASE_RPC_URL) ||
                key.equals(BASE_RPC_FALLBACK_URLS) ||
                key.equals(OPTIMISM_RPC_URL) ||
                key.equals(OPTIMISM_RPC_FALLBACK_URLS) ||
                key.equals(ARBITRUM_RPC_URL) ||
                key.equals(ARBITRUM_RPC_FALLBACK_URLS) ||
                key.equals(BNB_RPC_URL) ||
                key.equals(BNB_RPC_FALLBACK_URLS) ||
                key.equals(POLYGON_RPC_URL) ||
                key.equals(POLYGON_RPC_FALLBACK_URLS) ||
                key.equals(AVALANCHE_RPC_URL) ||
                key.equals(AVALANCHE_RPC_FALLBACK_URLS) ||
                key.equals(GNOSIS_RPC_URL) ||
                key.equals(GNOSIS_RPC_FALLBACK_URLS) ||
                key.equals(KAIA_RPC_URL) ||
                key.equals(KAIA_RPC_FALLBACK_URLS) ||
                key.equals(ARC_RPC_URL) ||
                key.equals(ARC_RPC_FALLBACK_URLS) ||
                key.equals(STORY_RPC_URL) ||
                key.equals(STORY_RPC_FALLBACK_URLS) ||
                key.equals(BOBA_RPC_URL) ||
                key.equals(BOBA_RPC_FALLBACK_URLS) ||
                key.equals(CELO_RPC_URL) ||
                key.equals(CELO_RPC_FALLBACK_URLS) ||
                key.equals(HYPEREVM_RPC_URL) ||
                key.equals(HYPEREVM_RPC_FALLBACK_URLS) ||
                key.equals(ABSTRACT_RPC_URL) ||
                key.equals(ABSTRACT_RPC_FALLBACK_URLS) ||
                key.equals(ANIME_RPC_URL) ||
                key.equals(ANIME_RPC_FALLBACK_URLS) ||
                key.equals(APECHAIN_RPC_URL) ||
                key.equals(APECHAIN_RPC_FALLBACK_URLS) ||
                key.equals(DEGEN_RPC_URL) ||
                key.equals(DEGEN_RPC_FALLBACK_URLS) ||
                key.equals(INK_RPC_URL) ||
                key.equals(INK_RPC_FALLBACK_URLS) ||
                key.equals(JOVAY_RPC_URL) ||
                key.equals(JOVAY_RPC_FALLBACK_URLS) ||
                key.equals(CROSSFI_RPC_URL) ||
                key.equals(CROSSFI_RPC_FALLBACK_URLS) ||
                key.equals(LINEA_RPC_URL) ||
                key.equals(LINEA_RPC_FALLBACK_URLS) ||
                key.equals(ETHEREUM_SEPOLIA_RPC_URL) ||
                key.equals(ETHEREUM_SEPOLIA_RPC_FALLBACK_URLS) ||
                key.equals(BASE_SEPOLIA_RPC_URL) ||
                key.equals(BASE_SEPOLIA_RPC_FALLBACK_URLS) ||
                key.equals(OPTIMISM_SEPOLIA_RPC_URL) ||
                key.equals(OPTIMISM_SEPOLIA_RPC_FALLBACK_URLS) ||
                key.equals(ARBITRUM_SEPOLIA_RPC_URL) ||
                key.equals(ARBITRUM_SEPOLIA_RPC_FALLBACK_URLS) ||
                key.equals(BNB_TESTNET_RPC_URL) ||
                key.equals(BNB_TESTNET_RPC_FALLBACK_URLS) ||
                key.equals(POLYGON_AMOY_RPC_URL) ||
                key.equals(POLYGON_AMOY_RPC_FALLBACK_URLS) ||
                key.equals(AVALANCHE_FUJI_RPC_URL) ||
                key.equals(AVALANCHE_FUJI_RPC_FALLBACK_URLS) ||
                key.equals(GNOSIS_CHIADO_RPC_URL) ||
                key.equals(GNOSIS_CHIADO_RPC_FALLBACK_URLS) ||
                key.equals(KAIA_KAIROS_RPC_URL) ||
                key.equals(KAIA_KAIROS_RPC_FALLBACK_URLS) ||
                key.equals(ARC_TESTNET_RPC_URL) ||
                key.equals(ARC_TESTNET_RPC_FALLBACK_URLS) ||
                key.equals(STORY_AENEID_RPC_URL) ||
                key.equals(STORY_AENEID_RPC_FALLBACK_URLS) ||
                key.equals(BOBA_SEPOLIA_RPC_URL) ||
                key.equals(BOBA_SEPOLIA_RPC_FALLBACK_URLS) ||
                key.equals(CELO_SEPOLIA_RPC_URL) ||
                key.equals(CELO_SEPOLIA_RPC_FALLBACK_URLS) ||
                key.equals(HYPEREVM_TESTNET_RPC_URL) ||
                key.equals(HYPEREVM_TESTNET_RPC_FALLBACK_URLS) ||
                key.equals(ABSTRACT_SEPOLIA_RPC_URL) ||
                key.equals(ABSTRACT_SEPOLIA_RPC_FALLBACK_URLS) ||
                key.equals(ANIME_TESTNET_RPC_URL) ||
                key.equals(ANIME_TESTNET_RPC_FALLBACK_URLS) ||
                key.equals(APECHAIN_CURTIS_RPC_URL) ||
                key.equals(APECHAIN_CURTIS_RPC_FALLBACK_URLS) ||
                key.equals(INK_SEPOLIA_RPC_URL) ||
                key.equals(INK_SEPOLIA_RPC_FALLBACK_URLS) ||
                key.equals(JOVAY_SEPOLIA_RPC_URL) ||
                key.equals(JOVAY_SEPOLIA_RPC_FALLBACK_URLS) ||
                key.equals(CROSSFI_TESTNET_RPC_URL) ||
                key.equals(CROSSFI_TESTNET_RPC_FALLBACK_URLS) ||
                key.equals(LINEA_SEPOLIA_RPC_URL) ||
                key.equals(LINEA_SEPOLIA_RPC_FALLBACK_URLS) ||
                key.equals(SOLANA_RPC_URL) ||
                key.equals(SOLANA_RPC_FALLBACK_URLS) ||
                key.equals(APTOS_REST_URL) ||
                key.equals(APTOS_REST_FALLBACK_URLS) ||
                key.equals(TRON_API_URL) ||
                key.equals(TRON_API_FALLBACK_URLS) ||
                key.equals(SUI_RPC_URL) ||
                key.equals(SUI_RPC_FALLBACK_URLS) ||
                key.equals(COSMOS_REST_URL) ||
                key.equals(COSMOS_REST_FALLBACK_URLS) ||
                key.equals(OSMOSIS_REST_URL) ||
                key.equals(OSMOSIS_REST_FALLBACK_URLS) ||
                key.equals(INJECTIVE_REST_URL) ||
                key.equals(INJECTIVE_REST_FALLBACK_URLS) ||
                key.equals(SOLANA_DEVNET_RPC_URL) ||
                key.equals(SOLANA_DEVNET_RPC_FALLBACK_URLS) ||
                key.equals(APTOS_TESTNET_REST_URL) ||
                key.equals(APTOS_TESTNET_REST_FALLBACK_URLS) ||
                key.equals(TRON_NILE_API_URL) ||
                key.equals(TRON_NILE_API_FALLBACK_URLS) ||
                key.equals(TRON_SHASTA_API_URL) ||
                key.equals(TRON_SHASTA_API_FALLBACK_URLS) ||
                key.equals(SUI_TESTNET_RPC_URL) ||
                key.equals(SUI_TESTNET_RPC_FALLBACK_URLS) ||
                key.equals(COSMOS_TESTNET_REST_URL) ||
                key.equals(COSMOS_TESTNET_REST_FALLBACK_URLS) ||
                key.equals(OSMOSIS_TESTNET_REST_URL) ||
                key.equals(OSMOSIS_TESTNET_REST_FALLBACK_URLS) ||
                key.equals(INJECTIVE_TESTNET_REST_URL) ||
                key.equals(INJECTIVE_TESTNET_REST_FALLBACK_URLS) ||
                key.equals(BITCOIN_RPC_URL) ||
                key.equals(BITCOIN_RPC_FALLBACK_URLS) ||
                key.equals(LITECOIN_RPC_URL) ||
                key.equals(LITECOIN_RPC_FALLBACK_URLS) ||
                key.equals(DOGECOIN_RPC_URL) ||
                key.equals(DOGECOIN_RPC_FALLBACK_URLS) ||
                key.equals(BITCOINCASH_RPC_URL) ||
                key.equals(BITCOINCASH_RPC_FALLBACK_URLS) ||
                key.equals(BITCOIN_TESTNET_RPC_URL) ||
                key.equals(BITCOIN_TESTNET_RPC_FALLBACK_URLS) ||
                key.equals(LITECOIN_TESTNET_RPC_URL) ||
                key.equals(LITECOIN_TESTNET_RPC_FALLBACK_URLS) ||
                key.equals(DOGECOIN_TESTNET_RPC_URL) ||
                key.equals(DOGECOIN_TESTNET_RPC_FALLBACK_URLS) ||
                key.equals(BITCOINCASH_TESTNET_RPC_URL) ||
                key.equals(BITCOINCASH_TESTNET_RPC_FALLBACK_URLS) ||
                key.equals(MAXIMUM_BLOCKS_PER_SPLIT) ||
                key.equals(MAXIMUM_BLOCKS_PER_QUERY) ||
                key.equals(MAXIMUM_TRANSACTION_HASHES_PER_QUERY) ||
                key.equals(MAXIMUM_RPC_REQUEST_BYTES) ||
                key.equals(MAXIMUM_RPC_RESPONSE_BYTES) ||
                key.equals(MAXIMUM_RPC_CONCURRENCY) ||
                key.equals(MAXIMUM_RPC_QUEUE_SIZE) ||
                key.equals(MAXIMUM_RPC_BATCH_SIZE) ||
                key.equals(MAXIMUM_RPC_ATTEMPTS) ||
                key.equals(RPC_REQUESTS_PER_SECOND) ||
                key.equals(RPC_INITIAL_BACKOFF_MILLIS) ||
                key.equals(RPC_MAXIMUM_BACKOFF_MILLIS) ||
                key.equals(RPC_PROVIDER_COOLDOWN_MILLIS) ||
                key.equals(RPC_JSON_RPC_BATCH_ENABLED) ||
                key.equals(CACHE_ENABLED) ||
                key.equals(CACHE_MAXIMUM_SIZE) ||
                key.equals(CACHE_MAXIMUM_ENTRY_SIZE) ||
                key.equals(CACHE_TTL);
    }

    private static List<URI> parseEndpoints(
            Map<String, String> config,
            String primaryProperty,
            String fallbackProperty,
            boolean requireOrigin)
    {
        List<URI> endpoints = new ArrayList<>();
        Optional.ofNullable(config.get(primaryProperty))
                .map(value -> parseEndpoint(value, primaryProperty, requireOrigin))
                .ifPresent(endpoints::add);

        String fallbackValues = config.get(fallbackProperty);
        if (fallbackValues != null) {
            if (endpoints.isEmpty()) {
                throw new IllegalArgumentException(fallbackProperty + " requires " + primaryProperty);
            }
            for (String value : fallbackValues.split(",", -1)) {
                String endpoint = value.trim();
                if (endpoint.isEmpty()) {
                    throw new IllegalArgumentException(fallbackProperty + " must not contain empty endpoints");
                }
                endpoints.add(parseEndpoint(endpoint, fallbackProperty, requireOrigin));
            }
        }
        if (new HashSet<>(endpoints).size() != endpoints.size()) {
            throw new IllegalArgumentException(primaryProperty + " and " + fallbackProperty + " must not contain duplicate endpoints");
        }
        return List.copyOf(endpoints);
    }

    private static URI parseEndpoint(String value, String propertyName, boolean requireOrigin)
    {
        URI uri = parseHttpUri(value, propertyName);
        String path = uri.getRawPath();
        if (requireOrigin && (uri.getRawUserInfo() != null || uri.getRawQuery() != null || !(path.isEmpty() || path.equals("/")))) {
            throw new IllegalArgumentException(propertyName + " must contain HTTP(S) origins without credentials, path, query, or fragment");
        }
        return uri;
    }

    private static URI parseHttpUri(String value, String propertyName)
    {
        URI uri;
        try {
            uri = URI.create(value);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(propertyName + " contains an invalid URL");
        }
        if (!uri.isAbsolute() || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")) || uri.getHost() == null || uri.getFragment() != null) {
            throw new IllegalArgumentException(propertyName + " must contain absolute HTTP(S) URLs without fragments");
        }
        return uri;
    }

    private static int parseConfiguredLong(Map<String, String> config, String propertyName, long defaultValue, long maximum)
    {
        return Math.toIntExact(Optional.ofNullable(config.get(propertyName))
                .map(value -> parseBoundedPositiveLong(value, propertyName, maximum))
                .orElse(defaultValue));
    }

    private static long parseBoundedPositiveLong(String value, String propertyName, long maximum)
    {
        long parsed;
        try {
            parsed = Long.parseLong(value);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(propertyName + " must contain an integer");
        }
        if (parsed < 1 || parsed > maximum) {
            throw new IllegalArgumentException(propertyName + " must be between 1 and " + maximum);
        }
        return parsed;
    }

    private static boolean parseBoolean(String value, String propertyName)
    {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(propertyName + " must be true or false");
    }

    private static long parseDataSize(String value, String propertyName, long minimumBytes, long maximumBytes)
    {
        long bytes;
        try {
            bytes = DataSize.valueOf(value).toBytes();
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(propertyName + " must contain a valid data size");
        }
        if (bytes < minimumBytes || bytes > maximumBytes) {
            throw new IllegalArgumentException(propertyName + " must be between " + DataSize.ofBytes(minimumBytes) + " and " + DataSize.ofBytes(maximumBytes));
        }
        return bytes;
    }

    private static Duration parseDuration(String value, String propertyName)
    {
        Duration duration;
        try {
            duration = io.airlift.units.Duration.valueOf(value).toJavaTime();
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(propertyName + " must contain a valid duration");
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
        return duration;
    }
}
