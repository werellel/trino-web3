# trino-web3

`trino-web3` is a Trino connector for querying remote blockchain data as
native Trino relations. The project will preserve each chain's native data
model rather than forcing non-EVM chains into an EVM schema.

## Status

Milestones M0 through M3 are complete. M3 adds an opt-in, worker-local cache
with EVM finality and reorganization correctness. The repository provides a
catalog that can be loaded by Trino and queried with:

```sql
SHOW SCHEMAS FROM web3;
```

The M1 vertical slice exposes `web3.ethereum.blocks` and
`web3.ethereum.transactions`. Blocks provide `block_number` (`BIGINT`) and
`block_hash` (`VARCHAR`). Transactions provide `hash`, `block_number`,
`from_address`, and `to_address`. Both tables accept an equality or bounded
range predicate on `block_number`. `ethereum.transactions` also accepts
bounded equality or `IN` predicates on `hash`. Unbounded scans are rejected
before remote work is scheduled.

This slice uses standard Ethereum JSON-RPC `eth_getBlockByNumber` requests.
The worker-local runtime bounds concurrency, queue size, batch size, retries,
and rate admission; it handles generic endpoint failover and `429`
`Retry-After`. It does not implement receipts, logs, vendor-specific provider
profiles, or non-EVM chains.

## Ethereum blocks configuration

Configure an Ethereum-compatible JSON-RPC endpoint for a catalog that will
query blocks:

```properties
connector.name=web3
web3.ethereum.rpc-url=http://127.0.0.1:8545
web3.ethereum.rpc-fallback-urls=http://127.0.0.1:8546,http://127.0.0.1:8547
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
metadata. A query of `ethereum.blocks` without it fails explicitly.
The connector enforces hard upper bounds of 1,000 blocks per split, 10,000
blocks per query, 1 MiB per RPC request, and 64 MiB per RPC response.
Fallback URLs are optional and are used in declaration order after a retryable
primary-provider failure. They are generic JSON-RPC endpoints: no provider
credentials, vendor headers, or provider-specific behavior are configured.
All endpoints in one catalog must address the same EVM chain. M3 preserves this
as an explicit configuration precondition; endpoint-by-endpoint `eth_chainId`
verification is part of the M5 configuration-hardening scope.
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
the runtime lifecycle owner. Their operator-facing `system.cache_stats`
surface remains an M5 deliverable because those values cannot be attributed to
one PageSource truthfully.

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

SELECT hash, block_number, from_address, to_address
FROM web3.ethereum.transactions
WHERE hash IN ('0x...', '0x...');
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
trino-web3-runtime  Bounded generic JSON-RPC execution, transport, and metrics
trino-web3-evm      Ethereum blocks schema, request mapping, and decoding
trino-web3-plugin   Trino SPI metadata, splits, and page sources
trino-web3-testing  Catalog, local-RPC, and plugin-archive integration tests
```

Additional EVM tables, non-EVM chains, disk/shared cache, and later runtime behavior
will be added only when their respective roadmap milestones begin.

## Development rules

Read [AGENTS.md](AGENTS.md), [ARCHITECTURE.md](ARCHITECTURE.md),
[ROADMAP.md](ROADMAP.md), and [PLANS.md](PLANS.md) before significant changes.
The main invariants are bounded remote work, native chain data models, and a
provider-independent runtime.
