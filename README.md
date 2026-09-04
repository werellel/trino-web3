# trino-web3

`trino-web3` is a Trino 475 connector for querying remote blockchain data as
native Trino schemas and tables. Each chain family keeps its own data model;
non-EVM chains are not forced into an Ethereum-shaped schema.

## What it provides

- bounded remote scans with predicate pushdown and cancellation-aware RPC/REST
  execution;
- typed columns for stable fields and a `raw_json` column containing the
  complete source object, so additive provider fields remain queryable;
- shared limits for timeout, concurrency, queue size, request/response bytes,
  retries, rate admission, batching, failover, and metrics;
- versioned chain descriptors for metadata and code-based executable adapters
  for planning, validation, decoding, finality, and reorganization rules;
- coordinator-local `web3.system` snapshots for chains, providers, RPC
  metrics, rate limits, and cache statistics.

Unbounded scans are rejected before remote work is scheduled. Tests use local
deterministic protocol fixtures and do not require paid providers or API keys.

## Supported schemas

EVM networks expose `blocks`, `transactions`, `receipts`, and `logs` with
network-specific schemas and chain-identity checks:

- Mainnets: `ethereum`, `base`, `optimism`, `arbitrum`, `bnb`, `polygon`,
  `avalanche`, `gnosis`, `kaia`, `arc`, `story`, `boba`, `celo`, `hyperevm`,
  `abstract`, `anime`, `apechain`, `degen`, `ink`, `jovay`, `crossfi`, `linea`,
  `unichain`, `tempo`, `robinhood`, and `mode`.
- Testnets: `ethereum_sepolia`, `base_sepolia`, `optimism_sepolia`,
  `arbitrum_sepolia`, `bnb_testnet`, `polygon_amoy`, `avalanche_fuji`,
  `gnosis_chiado`, `kaia_kairos`, `arc_testnet`, `story_aeneid`,
  `boba_sepolia`, `celo_sepolia`, `hyperevm_testnet`, `abstract_sepolia`,
  `anime_testnet`, `apechain_curtis`, `ink_sepolia`, `jovay_sepolia`,
  `crossfi_testnet`, `linea_sepolia`, `unichain_sepolia`, `tempo_moderato`,
  `robinhood_testnet`, and `mode_sepolia`.

Native non-EVM schemas are also available:

| Family | Schemas | Tables |
| --- | --- | --- |
| Solana | `solana`, `solana_devnet` | `blocks`, `transactions`, `instructions` |
| Aptos | `aptos`, `aptos_testnet` | `transactions`, `events` |
| Tron | `tron`, `tron_nile`, `tron_shasta` | `blocks`, `transactions` |
| Sui | `sui`, `sui_testnet` | `checkpoints`, `transactions` |
| Cosmos SDK | `cosmos`, `cosmos_testnet`, `osmosis`, `osmosis_testnet`, `injective`, `injective_testnet` | `blocks`, `transactions` |
| Bitcoin Core family | `bitcoin`, `bitcoin_testnet`, `litecoin`, `litecoin_testnet`, `dogecoin`, `dogecoin_testnet`, `bitcoincash`, `bitcoincash_testnet` | `blocks`, `transactions`, `inputs`, `outputs` |

The exact table columns and protocol contracts are documented in
[`docs/CHAIN_MODEL.md`](docs/CHAIN_MODEL.md). Endpoint properties are
independent per schema, so a mainnet endpoint cannot accidentally serve a
testnet schema.

## Architecture

```text
Trino SPI / connector core
        |
        v
chain adapter (native schema, bounded planning, decoding)
        |
        v
RPC runtime (limits, batching, retry, rate, failover, cache, metrics)
        |
        v
provider transport (HTTP JSON-RPC or native REST)
```

The runtime never defines blockchain tables, and adapters never own HTTP
transport or provider policy. A descriptor declares versioned metadata,
methods, bindings, and simple response mappings. An executable adapter supplies
the chain-specific behavior that cannot safely be expressed declaratively.
Descriptor-only entries are not executable and cannot expose a table by
themselves.

## Adding a chain

Add a chain as a small, isolated module following the existing native adapter
pattern:

1. Define the native schema, tables, columns, bounds, identity, and finality
   rules. Do not reuse EVM columns for a different data model.
2. Add a versioned descriptor resource under `trino-web3-chain` or the chain
   module. Keep endpoints, credentials, retry policy, provider headers, and
   scripts out of descriptors.
3. Implement `ExecutableChainAdapter` in a dedicated chain module. Translate
   bounded `ChainScan` values into remote operations, validate the complete
   native response, and emit immutable `ChainRow` values including `raw_json`.
4. Reuse `RemoteExecution` and the connector-owned runtime. Do not create a
   per-query executor, HTTP client, retry loop, rate limiter, or provider
   failover implementation.
5. Register the adapter in the plugin's executable registry and add one
   catalog property namespace per network. Keep mainnet and testnet schemas
   separate.
6. Add deterministic tests for descriptor evolution, predicate/split bounds,
   response decoding and malformed/partial responses, identity validation,
   cancellation, and Trino metadata plus bounded SQL execution. Add the
   adapter to the packaged-plugin loading test.

Before registering the adapter, use the complete
[new-chain checklist](docs/NEW_CHAIN_ADAPTER_CHECKLIST.md). The descriptor
contract is `web3.trino.io/v1alpha1`; existing chain and table identities must
not be removed or silently repurposed. Add a typed column only for a stable
relational contract—otherwise callers can use `json_parse(raw_json)` for new
provider fields.

## Configure a catalog

Create a Trino catalog file such as `etc/catalog/web3.properties`:

```properties
connector.name=web3
web3.ethereum.rpc-url=https://your-node.example/v2/${ENV:API_KEY}
web3.ethereum.rpc-fallback-urls=https://backup-node.example
web3.aptos.rest-url=https://fullnode.mainnet.aptoslabs.com
web3.solana.rpc-url=https://api.mainnet-beta.solana.com
web3.maximum-blocks-per-split=100
web3.maximum-blocks-per-query=10000
web3.rpc.maximum-concurrency=16
web3.rpc.maximum-queue-size=1024
web3.rpc.maximum-batch-size=100
web3.rpc.maximum-attempts=3
web3.rpc.requests-per-second=100
web3.cache.enabled=false
```

Every configured endpoint is validated against its native network identity
when peer endpoints are present. Credential-bearing URLs must use Trino's
environment substitution and are never written to logs, exceptions, metrics,
or system tables. See [`docs/SECURITY.md`](docs/SECURITY.md) and
[`docs/RPC_RUNTIME.md`](docs/RPC_RUNTIME.md) for the full property contract.

## Run locally with Docker

The repository includes a Trino 475 image and a four-node Compose cluster (one
coordinator and three workers). The image installs the assembled plugin ZIP at
`/usr/lib/trino/plugin/web3`, matching Trino's plugin layout.

Prerequisites: Java 23, Maven 3.9+, Docker, and Docker Compose.

```bash
cp .env.example .env
# Set ALCHEMY_API_KEY in .env, or export it in the shell.
./docker/verify.sh
```

The script builds the plugin and image, starts the cluster, checks all three
workers, runs `SHOW SCHEMAS`, and executes a bounded Ethereum query. Set
`KEEP_CLUSTER=1` to keep it running:

```bash
KEEP_CLUSTER=1 ./docker/verify.sh
docker compose exec coordinator trino --catalog web3
docker compose down
```

The checked-in catalog uses environment substitution for optional credentials
and public/official endpoints for the configured networks. Replace endpoint
properties with nodes available to you; an endpoint is not contacted while
metadata is loaded unless identity comparison requires multiple configured
peers.

## Query examples

```sql
SHOW SCHEMAS FROM web3;

SELECT block_number, block_hash
FROM web3.ethereum.blocks
WHERE block_number BETWEEN 23000000 AND 23000100;

SELECT hash, block_number, from_address, to_address, raw_json
FROM web3.ethereum.transactions
WHERE hash IN ('0x...', '0x...');

SELECT transaction_hash, status, raw_json
FROM web3.ethereum.receipts
WHERE transaction_hash = '0x...';

SELECT block_number, transaction_hash, topic0, data, raw_json
FROM web3.ethereum.logs
WHERE block_number BETWEEN 23000000 AND 23000010;

SELECT ledger_version, hash, type, success, vm_status, sender, data
FROM web3.aptos.transactions
WHERE ledger_version BETWEEN 1000 AND 1099;

SELECT slot, transaction_signature, instruction_index, program_id, raw_json
FROM web3.solana.instructions
WHERE slot BETWEEN 1000 AND 1099;
```

Use bounds appropriate for the configured node. Aptos nodes may prune old
ledger versions, and some providers restrict archive or historical requests.
Such remote errors are surfaced; they are not converted into empty results.
More native examples are in [`docs/EXAMPLES.md`](docs/EXAMPLES.md).

For exact blockchain-width arithmetic and binary conversion, see
[`docs/NUMERIC_AND_BINARY_FUNCTIONS.md`](docs/NUMERIC_AND_BINARY_FUNCTIONS.md).

## Project layout

```text
trino-web3-chain       Versioned descriptors and adapter registry
trino-web3-core        Trino-independent handles and bounded split model
trino-web3-adapter     Executable adapter, scan, split, and row contracts
trino-web3-runtime     JSON-RPC/REST execution, transport, limits, metrics
trino-web3-functions   Exact UINT256/INT256 types, operators, and aggregates
trino-web3-evm         EVM descriptors, planning, requests, and decoding
trino-web3-aptos       Aptos transactions and event streams
trino-web3-solana      Solana blocks, transactions, and instructions
trino-web3-tron        Tron native REST blocks and transactions
trino-web3-sui         Sui checkpoints and transactions
trino-web3-cosmos      Cosmos SDK adapters
trino-web3-utxo        Shared bounded Bitcoin Core-family execution
trino-web3-bitcoin     Bitcoin native decoding
trino-web3-litecoin    Litecoin native decoding
trino-web3-dogecoin    Dogecoin native decoding
trino-web3-bitcoincash  Bitcoin Cash native decoding
trino-web3-plugin      Trino SPI plugin, metadata, splits, page sources
trino-web3-testing     Local protocol and packaged-plugin integration tests
```

## Build, test, and release

The project targets one explicit compatibility line: Trino 475, Java 23, and
Maven 3.9 or newer. The Trino BOM is imported by the root POM and dependency
versions are managed centrally.

```bash
mvn validate
mvn test
mvn clean verify
mvn package  # creates trino-web3-plugin/target/*-plugin.zip
```

`mvn clean verify` runs Checkstyle, Maven Enforcer, unit tests, connector
integration tests, and the packaged-plugin classloader test. All remote
protocol tests use local fixtures. See [testing](docs/TESTING.md),
[compatibility](docs/COMPATIBILITY.md), and [releasing](docs/RELEASING.md) for
the contributor and release gates.

Runtime, cache/finality, metrics, system-table, and security contracts are
documented in [`docs/`](docs/). These documents describe the current public
behavior; generated build output and credentials should never be committed.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
