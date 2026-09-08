# Chain support and endpoint verification

This is the canonical support-status record for the built-in chain schemas.
It separates connector implementation evidence from endpoint configuration and
live network verification. A URL in `docker/trino/catalog/web3.properties` is
not, by itself, evidence that a provider is reachable or suitable for every
table operation.

## Status vocabulary

### Connector evidence

| Status | Meaning |
| --- | --- |
| `metadata` | The adapter/descriptor is registered and the schema can be enumerated. |
| `adapter_tested` | Descriptor, bounded planning, and network-identity checks are covered; remote decoding is not proven for this schema. |
| `protocol_tested` | Deterministic local fixtures cover the adapter's remote protocol path. |

### Endpoint state

| Status | Meaning |
| --- | --- |
| `configured_not_verified` | A catalog endpoint property exists; no live probe is committed to CI. |
| `not_configured` | No endpoint property is shipped; the schema is metadata-only until an operator supplies one. |

### Live verification result

| Status | Meaning |
| --- | --- |
| `not_run` | No live public-node probe has been run as part of the repository validation. |
| `passed` | The endpoint responded to the documented probe and returned the expected network identity. |
| `failed` | The probe ran and returned an unexpected status, response, or network identity. |
| `stale` | A previous pass is older than the 30-day review interval and must be rerun. |

The current repository deliberately records public endpoints as
`configured_not_verified`/`not_run`. Deterministic tests use local protocol
fixtures and do not require paid providers or public network access.

### Operational status

Operational status is derived from the three axes above:

| Status | Derivation | Meaning |
| --- | --- | --- |
| `metadata_only` | Endpoint state is `not_configured` | The schema is discoverable, but scans require an operator-supplied endpoint. |
| `configured_unverified` | Endpoint state is `configured_not_verified` and live result is `not_run` or `stale` | A scan can be attempted with the shipped catalog, but availability and historical coverage are not attested. |
| `configured_failed` | Live result is `failed` | The last recorded probe did not satisfy the identity or protocol contract; do not advertise the endpoint as ready. |
| `configured_verified` | Live result is `passed` | The recorded identity and chain-specific smoke probes succeeded at the stated time. |

No schema in this revision is `configured_verified`.

At this revision there are 74 registered schemas: 51 EVM schemas and 23
native non-EVM schemas. 61 have a catalog endpoint property (46 EVM and 15
non-EVM); the remaining 13 are intentionally `metadata_only`.

## Current matrix

The catalog property column uses the key pattern from
`docker/trino/catalog/web3.properties`. Mainnet and testnet schemas are
separate identities even when they use the same adapter implementation.

| Family / network | Schemas | Connector evidence | Endpoint state | Live result | Operational status |
| --- | --- | --- | --- | --- | --- |
| EVM mainnets with catalog endpoints | `ethereum`, `base`, `optimism`, `arbitrum`, `bnb`, `polygon`, `avalanche`, `gnosis`, `kaia`, `story`, `boba`, `celo`, `hyperevm`, `abstract`, `anime`, `apechain`, `ink`, `jovay`, `linea`, `unichain`, `tempo`, `robinhood`, `mode` | `adapter_tested`; Ethereum remote path also has local fixtures | `configured_not_verified` (`web3.<schema>.rpc-url`) | `not_run` | `configured_unverified` |
| EVM mainnets without a shipped endpoint | `arc`, `degen`, `crossfi` | `adapter_tested` | `not_configured` | `not_run` | `metadata_only` |
| EVM testnets with catalog endpoints | `ethereum_sepolia`, `base_sepolia`, `optimism_sepolia`, `arbitrum_sepolia`, `bnb_testnet`, `polygon_amoy`, `avalanche_fuji`, `gnosis_chiado`, `kaia_kairos`, `arc_testnet`, `story_aeneid`, `boba_sepolia`, `celo_sepolia`, `abstract_sepolia`, `apechain_curtis`, `ink_sepolia`, `jovay_sepolia`, `hyperevm_testnet`, `linea_sepolia`, `unichain_sepolia`, `tempo_moderato`, `robinhood_testnet`, `mode_sepolia` | `adapter_tested` | `configured_not_verified` (`web3.<hyphenated-schema>.rpc-url`) | `not_run` | `configured_unverified` |
| EVM testnets without a shipped endpoint | `anime_testnet`, `crossfi_testnet` | `adapter_tested` | `not_configured` | `not_run` | `metadata_only` |
| Aptos | `aptos`, `aptos_testnet` | `protocol_tested` with local REST fixtures | `configured_not_verified` (`web3.aptos*.rest-url`) | `not_run` | `configured_unverified` |
| Solana | `solana`, `solana_devnet` | `protocol_tested` with local JSON-RPC fixtures | `configured_not_verified` (`web3.solana*.rpc-url`) | `not_run` | `configured_unverified` |
| Tron | `tron`, `tron_nile`, `tron_shasta` | `protocol_tested` with local REST fixtures | `configured_not_verified` (`web3.tron*.api-url`) | `not_run` | `configured_unverified` |
| Sui | `sui`, `sui_testnet` | `protocol_tested` with local JSON-RPC fixtures | `configured_not_verified` (`web3.sui*.rpc-url`) | `not_run` | `configured_unverified` |
| Cosmos SDK | `cosmos`, `osmosis`, `injective`, `osmosis_testnet`, `injective_testnet` | `protocol_tested` with local REST fixtures | `configured_not_verified` (`web3.<schema>.rest-url`) | `not_run` | `configured_unverified` |
| Cosmos SDK without a shipped endpoint | `cosmos_testnet` | `protocol_tested` with shared local REST fixtures | `not_configured` | `not_run` | `metadata_only` |
| Bitcoin Core family with catalog endpoint | `bitcoin` | `protocol_tested` with local JSON-RPC fixtures | `configured_not_verified` (`web3.bitcoin.rpc-url`) | `not_run` | `configured_unverified` |
| Bitcoin Core family requiring an operator endpoint | `bitcoin_testnet`, `litecoin`, `litecoin_testnet`, `dogecoin`, `dogecoin_testnet`, `bitcoincash`, `bitcoincash_testnet` | `adapter_tested`; no shared live endpoint | `not_configured` | `not_run` | `metadata_only` |

The matrix intentionally does not label any public endpoint `passed` yet. The
connector's deterministic acceptance gate proves schema loading, bounded
planning, decoding, fallback, cancellation, and package loading; it does not
prove the availability, archival depth, quota, or method coverage of a remote
provider. `docker/verify.sh` performs an operator-triggered Ethereum smoke
query, but its result is not a per-chain attestation and is not committed to
this matrix.

## Verification record format

When a live probe is run, record one immutable entry in the change or release
notes using this shape:

```yaml
schema: ethereum
catalog_property: web3.ethereum.rpc-url
endpoint_role: primary
endpoint_reference: https://provider.example/<redacted>
checked_at_utc: 2026-09-08T00:00:00Z
probe:
  protocol: JSON_RPC
  method: eth_chainId
  expected_identity: "0x1"
  observed_identity: "0x1"
  http_status: 200
result: passed
notes: "Identity probe only; table pagination and archive coverage require separate checks."
```

Never store API keys, bearer tokens, full credential-bearing URLs, response
payloads, or request identifiers in this record. A successful identity probe
does not imply that every table or historical range is supported.

## Promotion rules

Promote a schema from `metadata` to executable support only after its bounded
adapter path and deterministic protocol tests exist. Record `passed` as the
live result (and derive `configured_verified` as the operational status) only
after the identity probe and the table-specific smoke probes required by that
chain have succeeded. Re-run probes after endpoint, provider, chain upgrade,
or finality-policy changes.

The release checklist should cite this matrix and include the exact probe
timestamp and result for every endpoint advertised as verified.

## Evidence sources

The matrix is based on repository evidence, not provider marketing claims:

| Evidence | Repository source |
| --- | --- |
| Adapter and descriptor registration | `Web3ConnectorFactory.endpointDefinitions()` and the chain adapter modules |
| EVM descriptor, identity, and bounded planning coverage | `trino-web3-evm/src/test/.../TestEvmChainAdapters.java` |
| Native protocol fixtures | `TestAptosChainDataClient`, `TestSolanaChainDataClient`, `TestTronChainDataClient`, `TestSuiChainDataClient`, `TestCosmosChainDataClient`, and `TestBitcoinChainDataClient` |
| Packaged plugin and Trino metadata loading | `trino-web3-testing/src/test/.../ITWeb3PluginArchive.java` |
| Endpoint properties and transport type | `docker/trino/catalog/web3.properties` and `Web3ConnectorFactory.EndpointDefinition` |

The `...` segments above mean the corresponding `src/test/java` path in the
named module; they are shortened intentionally so this document remains
readable when rendered on GitHub.
