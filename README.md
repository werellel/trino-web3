# trino-web3

`trino-web3` is a Trino connector for querying remote blockchain data as
native Trino relations. The project will preserve each chain's native data
model rather than forcing non-EVM chains into an EVM schema.

## Status

Milestones M0 and M1 are complete. The repository provides a catalog that can
be loaded by Trino and queried with:

```sql
SHOW SCHEMAS FROM web3;
```

The M1 vertical slice exposes `web3.ethereum.blocks` and
`web3.ethereum.transactions`. Blocks provide `block_number` (`BIGINT`) and
`block_hash` (`VARCHAR`). Transactions provide `hash`, `block_number`,
`from_address`, and `to_address`. Both tables require an equality or bounded
range predicate on `block_number`; unbounded scans are rejected before remote
work is scheduled.

This slice uses standard Ethereum JSON-RPC `eth_getBlockByNumber` batches.
It does not yet implement receipts, logs, caching, retries, rate limiting,
provider profiles, or non-EVM chains.

## Ethereum blocks configuration

Configure an Ethereum-compatible JSON-RPC endpoint for a catalog that will
query blocks:

```properties
connector.name=web3
web3.ethereum.rpc-url=http://127.0.0.1:8545
web3.maximum-blocks-per-split=100
web3.maximum-blocks-per-query=10000
web3.maximum-rpc-request-bytes=1048576
web3.maximum-rpc-response-bytes=16777216
```

`web3.ethereum.rpc-url` is optional when only loading the catalog or reading
metadata. A query of `ethereum.blocks` without it fails explicitly.
The connector enforces hard upper bounds of 1,000 blocks per split, 10,000
blocks per query, 1 MiB per RPC request, and 64 MiB per RPC response.

Build `trino-web3-plugin/target/trino-web3-plugin-0.1-SNAPSHOT-plugin.zip`
with `mvn package`, then extract it as one Trino plugin directory. The ZIP
contains the plugin, core, EVM, runtime, and runtime library JARs.

```sql
SELECT block_number, block_hash
FROM web3.ethereum.blocks
WHERE block_number BETWEEN 23000000 AND 23000100;

SELECT hash, block_number, from_address, to_address
FROM web3.ethereum.transactions
WHERE block_number BETWEEN 23000000 AND 23000010;
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

The test suite starts an in-process Trino runner and a deterministic local
JSON-RPC mock. It does not contact an RPC provider and requires no credentials
or external blockchain network.

## Module layout

```text
trino-web3-core     Trino planning handles and bounded range splitting
trino-web3-runtime  Standard JSON-RPC batch transport
trino-web3-evm      Ethereum blocks schema, request mapping, and decoding
trino-web3-plugin   Trino SPI metadata, splits, and page sources
trino-web3-testing  Catalog, local-RPC, and plugin-archive integration tests
```

Additional EVM tables, non-EVM chains, cache, and production runtime behavior
will be added only when their respective roadmap milestones begin.

## Development rules

Read [AGENTS.md](AGENTS.md), [ARCHITECTURE.md](ARCHITECTURE.md),
[ROADMAP.md](ROADMAP.md), and [PLANS.md](PLANS.md) before significant changes.
The main invariants are bounded remote work, native chain data models, and a
provider-independent runtime.
