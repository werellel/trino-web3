# trino-web3

`trino-web3` is a Trino connector for querying remote blockchain data as
native Trino relations. The project will preserve each chain's native data
model rather than forcing non-EVM chains into an EVM schema.

## Status

Milestones M0 through M4 are complete. M5.1 adds safe coordinator-local runtime
snapshots through `web3.system`, M5.2 validates endpoint-native network identity,
M5.3 publishes safe runtime metrics, and M5.4 hardens configuration, endpoint
secrecy, and shutdown behavior. M4 established a versioned declarative contract,
an executable adapter registry, and routes Ethereum metadata, bounded split
planning, and row decoding through the same
code-based adapter. Descriptor method bindings now also select bounded access
paths into named range and discrete-value table-handle predicates, without
Ethereum fields in the generic Trino planning state. The M4 vertical
slice also exposes native `aptos.transactions` and account-scoped
`aptos.events` through bounded REST paths, plus native `solana.blocks`,
`solana.transactions`, and `solana.instructions` through bounded JSON-RPC
`getBlock` paths. This proves that the shared runtime is not EVM- or
JSON-RPC-only. M3 adds an opt-in,
worker-local cache with
EVM finality and reorganization correctness. The repository provides a
catalog that can be loaded by Trino and queried with:

```sql
SHOW SCHEMAS FROM web3;
```

The EVM adapters expose the same native tables (`blocks` and `transactions`)
for Ethereum and the supported EVM networks: Base, Optimism, Arbitrum One, BNB
Smart Chain, Polygon, Avalanche C-Chain, Gnosis, Kaia, Arc, Story, Boba, Celo,
HyperEVM, Abstract, AnimeChain, ApeChain, Degen, Ink, Jovay, CrossFi, Linea,
Unichain, Tempo, Robinhood Chain, Celo, Gnosis, and Mode.
Each network has its own schema and chain-identity check, while sharing the
provider-independent EVM runtime. Optimism is registered once even when it is
listed more than once in external chain catalogs.

The same model is used for canonical testnets, each with an independent schema:
`ethereum_sepolia`, `base_sepolia`, `optimism_sepolia`, `arbitrum_sepolia`,
`bnb_testnet`, `polygon_amoy`, `avalanche_fuji`, `gnosis_chiado`, `kaia_kairos`,
`arc_testnet`, `story_aeneid`, `boba_sepolia`, `celo_sepolia`,
`hyperevm_testnet`, `abstract_sepolia`, `anime_testnet`, `apechain_curtis`,
`ink_sepolia`, `jovay_sepolia`, `crossfi_testnet`, `linea_sepolia`,
`unichain_sepolia`, `tempo_moderato`, `robinhood_testnet`, and `mode_sepolia`.
Degen testnet is intentionally not registered until its canonical chain ID and
endpoint contract are published.

Non-EVM testnets are also isolated by native schema: `solana_devnet`,
`aptos_testnet`, `tron_nile`, `tron_shasta`, `sui_testnet`,
`cosmos_testnet`, `osmosis_testnet`, `injective_testnet`,
`bitcoin_testnet`, `litecoin_testnet`, `dogecoin_testnet`, and
`bitcoincash_testnet`. They retain each chain's native table model and use
separate REST or JSON-RPC endpoint properties with network identity checks.

Tron is exposed through its native REST API as `web3.tron.blocks` and
`web3.tron.transactions`. It is a separate non-EVM adapter: bounded
`block_number` predicates are translated to `/wallet/getblockbynum`, and the
complete native block or transaction object remains available through
`raw_json`.

Sui is exposed through its native JSON-RPC API as
`web3.sui.checkpoints` and `web3.sui.transactions`. Both tables require a
bounded `checkpoint_sequence_number` predicate; checkpoint rows are fetched
with `sui_getCheckpoint`, and transaction rows use checkpoint digests with
`sui_getTransactionBlock`. Native response objects remain available through
`raw_json`.

Cosmos Hub, Osmosis, and Injective are exposed through separate native Cosmos
SDK REST adapters. Each provides bounded `blocks` and `transactions` tables
using `/cosmos/base/tendermint/v1beta1/blocks/{height}`; transaction payloads
remain as native base64 values and are preserved in `raw_json`.

The M1 vertical slice exposes `web3.ethereum.blocks` and
`web3.ethereum.transactions`. Blocks provide `block_number` (`BIGINT`),
`block_hash` (`VARCHAR`), and `raw_json` (a compact JSON document in
`VARCHAR`). Transactions provide `hash`, `block_number`, `from_address`,
`to_address`, and the same `raw_json` column. The raw document is the complete
block or transaction object returned by the node, so additive provider fields
remain queryable without changing the stable typed schema; use
`json_parse(raw_json)` with Trino's JSON functions when structured access is
needed. Both tables accept an equality or bounded
range predicate on `block_number`. `ethereum.transactions` also accepts
bounded equality or `IN` predicates on `hash`. Unbounded scans are rejected
before remote work is scheduled.

The Bitcoin vertical slice exposes native UTXO-oriented `web3.bitcoin.blocks`,
`web3.bitcoin.transactions`, `web3.bitcoin.inputs`, and `web3.bitcoin.outputs`.
All four tables require a bounded `height` or `block_height` predicate. Bitcoin
Core reads use bounded `getblockhash` plus `getblock` operations; satoshi values
are represented as integer `BIGINT` values.

Litecoin, Dogecoin, and Bitcoin Cash use the same bounded UTXO execution path
with separate native schemas: `web3.litecoin`, `web3.dogecoin`, and
`web3.bitcoincash`. Each exposes `blocks`, `transactions`, `inputs`, and
`outputs`, validates the node's `getnetworkinfo.subversion`, and keeps cache
admission disabled until a chain-specific reorganization-safe identity contract
is defined. The shared decoder accepts the Bitcoin Core-compatible `address`
and legacy `addresses` script shapes without adding provider-specific behavior.

This slice uses standard Ethereum JSON-RPC `eth_getBlockByNumber` requests.
The worker-local runtime bounds concurrency, queue size, batch size, retries,
and rate admission; it handles generic endpoint failover and `429`
`Retry-After`. It does not implement receipts, logs, vendor-specific provider
profiles, Solana inner instructions, or additional Aptos tables beyond
transactions and events.

Every remote chain table also exposes a `raw_json` `VARCHAR` containing the
complete source object for the row (the block, transaction, event, input,
output, or instruction). This compatibility column preserves additive provider
fields across all JSON-RPC and REST integrations. It contains valid compact
JSON and can be queried with `json_parse(raw_json)`; the JSON-RPC envelope is
not included.

## Runtime snapshots

The following system tables expose configured local runtime state without
performing remote calls: `web3.system.chains`, `web3.system.providers`,
`web3.system.rpc_metrics`, `web3.system.rate_limits`, and
`web3.system.cache_stats`. They never expose endpoints, credentials, request
data, hashes, or addresses. See [system table snapshots](docs/SYSTEM_TABLES.md)
for the complete contract.

Runtime and page-source metric names, units, aggregation, and privacy guarantees
are documented in [metrics](docs/METRICS.md).
Endpoint handling, secret-safety, and lifecycle guarantees are documented in
[security guidance](docs/SECURITY.md).
Supported versions are listed in the [compatibility matrix](docs/COMPATIBILITY.md);
release and upgrade steps are in [releasing](docs/RELEASING.md), with changes
tracked in [CHANGELOG.md](CHANGELOG.md).
Reproducible local performance measurements are described in
[benchmarks](docs/BENCHMARKS.md).
The repository-local release acceptance gate is documented in
[acceptance](docs/ACCEPTANCE.md).

## Chain endpoint configuration

Configure an Ethereum-compatible JSON-RPC endpoint for a catalog that will
query blocks:

```properties
connector.name=web3
web3.ethereum.rpc-url=http://127.0.0.1:8545
web3.ethereum.rpc-fallback-urls=http://127.0.0.1:8546,http://127.0.0.1:8547
web3.base.rpc-url=http://127.0.0.1:8545
# All EVM schemas, including *_sepolia, *_testnet, *_amoy, *_fuji, *_chiado,
# *_kairos, *_aeneid, *_curtis, and *_testnet variants, use the same JSON-RPC
# contract with a schema-specific rpc-url and chain-identity check.
web3.aptos.rest-url=http://127.0.0.1:8080
web3.aptos.rest-fallback-urls=http://127.0.0.1:8081,http://127.0.0.1:8082
web3.solana.rpc-url=http://127.0.0.1:8899
web3.solana.rpc-fallback-urls=http://127.0.0.1:8900,http://127.0.0.1:8901
web3.tron.api-url=http://127.0.0.1:8090
web3.tron.api-fallback-urls=http://127.0.0.1:8091
web3.bitcoin.rpc-url=http://127.0.0.1:8332
web3.bitcoin.rpc-fallback-urls=http://127.0.0.1:18332
web3.litecoin.rpc-url=http://127.0.0.1:9332
web3.litecoin.rpc-fallback-urls=http://127.0.0.1:19332
web3.dogecoin.rpc-url=http://127.0.0.1:22555
web3.dogecoin.rpc-fallback-urls=http://127.0.0.1:22556
web3.bitcoincash.rpc-url=http://127.0.0.1:8332
web3.bitcoincash.rpc-fallback-urls=http://127.0.0.1:18332
# Non-EVM testnets use separate properties and schemas:
# web3.solana-devnet.rpc-url, web3.aptos-testnet.rest-url,
# web3.tron-nile.api-url, web3.tron-shasta.api-url, web3.sui-testnet.rpc-url,
# web3.cosmos-testnet.rest-url, web3.osmosis-testnet.rest-url,
# web3.injective-testnet.rest-url, web3.bitcoin-testnet.rpc-url,
# web3.litecoin-testnet.rpc-url, web3.dogecoin-testnet.rpc-url,
# web3.bitcoincash-testnet.rpc-url (each also accepts a fallback-urls property).
web3.maximum-blocks-per-split=100
web3.maximum-blocks-per-query=10000
web3.maximum-transaction-hashes-per-query=1000
web3.maximum-rpc-request-bytes=1048576
web3.maximum-rpc-response-bytes=16777216
web3.rpc.maximum-concurrency=16
web3.rpc.maximum-queue-size=1024
web3.rpc.maximum-batch-size=100
web3.rpc.json-rpc-batch-enabled=true
web3.rpc.maximum-attempts=3
web3.rpc.requests-per-second=100
web3.rpc.initial-backoff-millis=100
web3.rpc.maximum-backoff-millis=30000
web3.rpc.provider-cooldown-millis=30000
web3.cache.enabled=false
web3.cache.maximum-size=128MB
web3.cache.maximum-entry-size=8MB
# web3.cache.ttl=1h
```

Cache size relationships are enforced when `web3.cache.enabled=true`. When the
cache is disabled, its sizing values are inactive and do not prevent an M2-only
catalog from loading.

`web3.ethereum.rpc-url` is optional when only loading the catalog or reading
metadata. A query of `ethereum.blocks` without it fails explicitly. All EVM
endpoint properties follow the same rule and validate `eth_chainId` against
their canonical network ID.
Fallback URLs require their chain's primary URL, must not contain blank or
duplicate entries, and are limited to eight endpoints including the primary.
Ethereum and Solana provider URLs may use a provider-specific path or query;
those values can carry credentials and are therefore never rendered in
connector errors, logs, metrics, or system tables. Keep catalog property files
and their filesystem permissions secret-safe. Aptos intentionally accepts only
credential-free HTTP(S) origins because the adapter owns its fixed REST paths.
`web3.aptos.rest-url` follows the same metadata-only rule and must be an
HTTP(S) origin without credentials, a path, query, or fragment. Aptos REST
requests share the configured concurrency, queue, rate, retry, cooldown,
failover, request-size, and response-size limits, but are never placed in a
JSON-RPC batch envelope. Aptos cache admission uses committed range identities
after complete native-response validation.
`web3.solana.rpc-url` follows Ethereum's metadata-only rule. Solana scans use
`getBlock` with `commitment=finalized`; every table requires a bounded `slot`
predicate. A null block result produces no rows. The initial instruction table
contains compiled top-level instructions only; it intentionally excludes inner
instructions and parsed instruction variants. Solana cache admission is disabled
until a stable cache identity and reorganization policy are defined.
`web3.tron.api-url` follows the REST origin-only rule used by Aptos. Tron
scans require a bounded `block_number` predicate and use the native
`GET /wallet/getnowblock` identity probe plus `POST /wallet/getblockbynum`
block reads. Tron is not sent through a JSON-RPC batch envelope; additive
native response fields remain available through `raw_json`.
`web3.bitcoin.rpc-url`, `web3.litecoin.rpc-url`, `web3.dogecoin.rpc-url`, and
`web3.bitcoincash.rpc-url` follow Ethereum's JSON-RPC endpoint rules. Their
Bitcoin Core-family identities are validated with `getnetworkinfo.subversion`
against the corresponding node product prefix. All four schemas require
bounded height predicates and use native UTXO-oriented rows. Satoshi amounts
are returned as integer `BIGINT` values, and cache admission is disabled until
reorganization-safe identity semantics are defined for each chain.
The connector enforces hard upper bounds of 1,000 blocks per split, 10,000
blocks per query, 1 MiB per RPC request, and 64 MiB per RPC response.
Fallback URLs are optional and are used in declaration order after a retryable
primary-provider failure. They are generic JSON-RPC endpoints: no provider
credentials, vendor headers, or provider-specific behavior are configured.
When a schema has a primary endpoint and one or more fallbacks, catalog creation
verifies that every configured endpoint identifies the same native network:
`eth_chainId` for Ethereum, `getGenesisHash` for Solana, and Aptos REST
`GET /v1` `chain_id` for Aptos. A mismatch, malformed identity, or unavailable
configured endpoint rejects catalog creation without exposing endpoint or
credential details. A lone endpoint has no peer to compare and is not probed.
Set `web3.rpc.json-rpc-batch-enabled=false` when an endpoint does not support
JSON-RPC batch arrays. The runtime then plans one operation per wire request;
queue, concurrency, rate, retry, health, and failover limits remain unchanged.

Caching is disabled by default. When enabled, each connector instance owns a
bounded L1 memory cache. Finalized block payloads are keyed by canonical block
hash, and only finalized block numbers retain number-to-hash references.
`SAFE` and `HEAD` numbers are revalidated on each scan, so a near-head reorg
cannot be hidden by a stale number mapping. Transaction-hash responses are
cached only after their inclusion block is finalized; pending transactions
remain visible with a null `block_number` and are revalidated. Missing results
and RPC or decoding failures are never negative cached. The optional TTL is an
operational eviction bound, not a substitute for finality validation. The EVM
adapter keeps finality boundaries for at most one second. Reusing an older
boundary is conservative: newly safe or finalized data is treated as less
final until the next refresh, while warm finalized cache hits avoid an RPC.

Transaction hashes are normalized only for the remote lookup and cache key.
The original `VARCHAR` predicate remains in Trino, so SQL equality and `IN`
retain their case-sensitive semantics.

The runtime exposes page-source metrics through the Trino 475 metrics SPI for
requests, failures, retries, throttling, in-flight requests, failovers, latency,
batch count, batch item count, cache hits, cache misses, revalidations, and
cache bytes read/written. Metrics are scoped to the remote execution
owned by that PageSource, including shared single-flight attempts it observes;
concurrent unrelated PageSources cannot contaminate them. It never places URLs,
credentials, request IDs, hashes, or addresses into metric dimensions.
Decoded cache reads are bounded per execution by the configured maximum RPC
response size, and PageSources report memory retained by decoded rows while
they own those rows.
Worker-global entry count, retained bytes, and eviction count are available to
the runtime lifecycle owner through `web3.system.cache_stats`; they cannot be
attributed truthfully to one PageSource.

Each configured schema has one worker-local runtime. The connector creates one
shared JDK HTTP client for those runtimes, reusing its connection pool rather
than creating clients per query or split. `Connector.shutdown()` cancels queued
and in-flight runtime work, clears retained cache entries, and stops each
runtime scheduler; it is safe when called more than once.

Build `trino-web3-plugin/target/trino-web3-plugin-0.1-SNAPSHOT-plugin.zip`
with `mvn package`, then extract it as one Trino plugin directory. The ZIP
contains the plugin, chain descriptor API, adapter execution API, core, EVM,
Solana, Aptos, Bitcoin, runtime, and runtime library JARs.

```sql
SELECT block_number, block_hash
FROM web3.ethereum.blocks
WHERE block_number BETWEEN 23000000 AND 23000100;

SELECT hash, block_number, from_address, to_address
FROM web3.ethereum.transactions
WHERE block_number BETWEEN 23000000 AND 23000010;

SELECT hash, block_number, from_address, to_address
FROM web3.ethereum.transactions
WHERE hash IN ('0x...', '0x...');

SELECT ledger_version, hash, type, success, vm_status, sender
FROM web3.aptos.transactions
WHERE ledger_version BETWEEN 1000 AND 1099;

SELECT account_address, creation_number, sequence_number, event_type, data
FROM web3.aptos.events
WHERE account_address = '0x1'
  AND creation_number = '7'
  AND sequence_number BETWEEN 0 AND 99;

SELECT slot, transaction_signature, instruction_index, program_id, account_indices, data
FROM web3.solana.instructions
WHERE slot BETWEEN 1000 AND 1099;
```

## Compatibility

| Component | Version |
| --- | --- |
| Trino SPI | 475 |
| Java | 23 |
| Maven | 3.9 or newer |

## Build and test

Run the full local validation, including static checks, unit tests, and the
packaged-plugin integration test:

```bash
mvn verify
```

The supported release line is tested in CI through an explicit Trino 475
matrix. See [compatibility](docs/COMPATIBILITY.md) for the policy and
[releasing](docs/RELEASING.md) for tagged builds.

The test suite starts in-process Trino runners and deterministic local JSON-RPC
and REST mocks. It does not contact a provider and requires no credentials or
external blockchain network.

## Module layout

```text
trino-web3-chain    Versioned descriptors, registry, evolution checks, and response mapping
trino-web3-core     Trino planning handles and bounded range splitting
trino-web3-adapter  Transport-neutral executable adapter, scan, split, and row contracts
trino-web3-runtime  Bounded JSON-RPC/REST execution, transport, and metrics
trino-web3-aptos    Aptos-native transaction/event planning, REST mapping, and decoding
trino-web3-utxo      Shared bounded Bitcoin Core-family UTXO planning and decoding
trino-web3-bitcoin   Bitcoin Core UTXO-native block, transaction, input, and output decoding
trino-web3-litecoin  Litecoin Core UTXO-native block, transaction, input, and output decoding
trino-web3-dogecoin  Dogecoin Core UTXO-native block, transaction, input, and output decoding
trino-web3-bitcoincash Bitcoin Cash Node/Bitcoin ABC UTXO-native decoding
trino-web3-evm      EVM chain descriptors, request mapping, and decoding
trino-web3-solana   Solana-native block, transaction, and instruction decoding
trino-web3-tron     Tron-native REST block and transaction decoding
trino-web3-sui      Sui-native checkpoint and transaction decoding
trino-web3-cosmos   Cosmos SDK REST adapters for Cosmos, Osmosis, and Injective
trino-web3-plugin   Trino SPI metadata, splits, and page sources
trino-web3-testing  Catalog, local-RPC, and plugin-archive integration tests
```

The descriptor format is `web3.trino.io/v1alpha1`. It declares native metadata,
remote method inventory, restricted request bindings, and response mappings;
it does not contain endpoints, secrets, provider policy, finality logic, or
scripts. Registry composition is fixed for a connector lifetime. Production
execution is dispatched by schema through the executable registry; adapter
splits retain their predicate column when crossing Trino's serialized split
boundary. The runtime executes both bounded JSON-RPC and endpoint-relative
REST request values through the same policy state machine. Aptos transactions
and account event streams are REST vertical slices. Solana uses bounded
`getBlock` JSON-RPC reads, and Bitcoin-family adapters use bounded Bitcoin Core
`getblockhash`/`getblock` reads. Litecoin, Dogecoin, and Bitcoin Cash share only
the transport-neutral UTXO runtime and decoder; each keeps a separate descriptor
and node identity matcher. Tron and Sui use native protocol adapters; Near
remains follow-up chain-adapter work.

## Development rules

Read [AGENTS.md](AGENTS.md), [ARCHITECTURE.md](ARCHITECTURE.md),
[ROADMAP.md](ROADMAP.md), and [PLANS.md](PLANS.md) before significant changes.
The main invariants are bounded remote work, native chain data models, and a
provider-independent runtime. New production chain adapters must also satisfy
[the extension checklist](docs/NEW_CHAIN_ADAPTER_CHECKLIST.md); descriptors
alone never expose metadata-only tables.
