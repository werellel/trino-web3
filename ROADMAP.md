# trino-web3 Roadmap

## 1. Vision

`trino-web3` is a production-grade multi-chain RPC connector for Trino.

The supported product scope progresses in this order:

```text
Connector correctness
        ↓
Production RPC runtime
        ↓
Cache / finality correctness
        ↓
Multi-chain support
```

Every change must preserve production correctness and bounded execution.

The first major objective is:

> Make live blockchain RPC endpoints behave like reliable, bounded, queryable Trino data sources.

---

# 2. Guiding principles

All roadmap work must preserve the following:

* Trino-style connector architecture
* bounded remote scans
* native chain data models
* provider-independent runtime
* cancellation-aware execution
* bounded retries
* explicit quota control
* deterministic testing without paid providers

A milestone is not complete only because the happy path works.

Each milestone must include:

* implementation
* tests
* observability
* documentation
* failure behavior
* upgrade considerations

---

# 3. Milestone summary

```text
M0  Repository and Trino baseline
M1  EVM vertical slice
M2  Production RPC runtime
M3  Cache, finality, and reorg correctness
M4  Multi-chain architecture
M5  Production hardening and OSS release
```

## Current release boundary

M0 through M5 are complete and constitute the supported connector release:
bounded multi-chain reads, cache/finality correctness, system snapshots,
runtime metrics, endpoint validation, and configuration/lifecycle hardening.
The roadmap ends at M5 for this repository snapshot; future research work is
out of scope for the supported release.

---

# 4. M0 — Repository and Trino baseline

## Goal

Create the smallest maintainable Trino plugin project that can be built, tested, and loaded before implementing blockchain functionality.

## Scope

Create:

```text
trino-web3/
├── AGENTS.md
├── ARCHITECTURE.md
├── ROADMAP.md
├── PLANS.md
├── docs/
├── trino-web3-core/
├── trino-web3-runtime/
├── trino-web3-evm/
├── trino-web3-testing/
├── trino-web3-plugin/
└── pom.xml
```

Initial support should target one explicit Trino version.

Document the supported version in:

* README
* build configuration
* CI

## Deliverables

* Maven multi-module build
* empty `Web3Plugin`
* `Web3ConnectorFactory`
* minimal connector bootstrap
* basic configuration validation
* CI build
* unit-test infrastructure
* style and validation checks

## Acceptance criteria

The following must be possible:

```text
Trino starts
→ plugin loads
→ catalog is created
→ SHOW SCHEMAS succeeds
```

No blockchain network is required yet.

## Non-goals

Do not implement:

* Ethereum tables
* batching
* cache
* provider profiles
* research planner

---

# 5. M1 — EVM vertical slice

## Goal

Implement the first complete production-style read path from Trino SQL to Ethereum RPC and back to Trino pages.

The first vertical slice should prove:

```text
SQL
→ predicate pushdown
→ bounded splits
→ RPC execution
→ decoding
→ rows
```

## Initial schema

```text
ethereum
```

## Initial tables

Required:

```text
blocks
transactions
```

Optional if the implementation remains simple:

```text
receipts
logs
```

Do not expand table count at the cost of architecture quality.

## Required queries

At minimum:

```sql
SHOW TABLES FROM web3.ethereum;
```

```sql
SELECT *
FROM web3.ethereum.blocks
WHERE block_number BETWEEN 23000000 AND 23000100;
```

```sql
SELECT
    hash,
    block_number,
    from_address,
    to_address
FROM web3.ethereum.transactions
WHERE block_number BETWEEN 23000000 AND 23000010;
```

## Required pushdown

For `blocks`:

* block number equality
* bounded block number range
* block hash equality where practical

For `transactions`:

* bounded block range

Transaction hash pushdown may be added if it fits the first implementation cleanly.

## Split model

Large block ranges must become deterministic bounded splits.

Example:

```text
23,000,000 → 23,009,999
```

may become:

```text
23,000,000 → 23,000,499
23,000,500 → 23,000,999
...
```

Split sizing should initially be static and configurable.

## RPC

Implement:

* Ethereum JSON-RPC client
* logical request abstraction
* bounded batch requests
* response matching by JSON-RPC ID
* timeout
* cancellation

No sophisticated adaptive scheduling yet.

## Testing

Required:

* table discovery
* metadata
* block range pushdown
* split generation
* batch response ordering independence
* malformed RPC response
* timeout
* query cancellation

Use a deterministic mock Ethereum RPC server.

## Acceptance criteria

A distributed Trino test must execute a bounded Ethereum block query successfully without contacting a paid or public RPC endpoint.

---

# 6. M2 — Production RPC runtime

## Goal

Turn the basic RPC client into a reusable production runtime shared by chain adapters.

This milestone is where `trino-web3` should stop behaving like a simple API wrapper.

## Required capabilities

Implement:

* logical batching
* concurrency control
* bounded queues
* worker-local rate limiting
* `429` handling
* `Retry-After`
* exponential backoff
* bounded retry policy
* single-flight request deduplication
* provider health state
* provider failover
* cancellation propagation
* metrics

## Runtime abstractions

Stabilize concepts such as:

```text
RemoteOperation
ProviderProfile
ProviderCapabilities
ExecutionPolicy
RetryPolicy
RateLimitPolicy
RemoteResult
```

Avoid premature abstraction beyond actual M2 requirements.

## Provider model

Initial providers:

```text
generic
```

Add vendor profiles only when they produce real behavioral differences.

Candidates:

* Alchemy
* QuickNode
* Infura

Do not add provider profiles that only wrap a URL with no distinct capability logic.

## Failure policy

Explicitly classify:

* timeout
* connection failure
* `429`
* `5xx`
* malformed response
* unsupported method
* invalid parameters
* partial batch failure

## Observability

Expose at least:

* request count
* failure count
* retry count
* throttled count
* request latency
* batch size
* in-flight requests
* provider failover count

## Acceptance criteria

The test suite must demonstrate:

```text
normal provider
→ successful query

primary provider unavailable
→ fallback succeeds

provider returns 429
→ bounded backoff
→ eventual success or explicit failure

query cancelled
→ queued and in-flight work stops
```

---

# 7. M3 — Cache, finality, and reorg correctness

## Goal

Introduce caching without compromising blockchain correctness.

## Cache layers

Initial:

```text
L1 worker-local memory cache
L2 optional worker-local disk cache
L3 remote provider
```

Shared distributed cache is not required.

## Required cache behavior

Support:

* block-hash-based immutable entries
* transaction-hash-based entries
* canonical parameter normalization
* size-based eviction
* optional TTL where required
* single-flight integration

## Finality model

Introduce generic classifications:

```text
HEAD
SAFE
FINALIZED
```

Each chain adapter determines the actual semantics.

## EVM behavior

Near-head block number lookups must not be treated as permanently immutable.

Preferred model:

```text
block number
→ resolve canonical hash
→ cache immutable block data by hash
```

## Negative cache

Allowed only for explicitly safe absence semantics.

Never cache:

* timeout
* `429`
* server error
* malformed response

as missing data.

## Reorg testing

The mock RPC infrastructure must simulate:

```text
height N → hash A

later:

height N → hash B
```

Tests must verify correct cache behavior.

## Metrics

Add:

* cache hits
* cache misses
* cache evictions
* cache bytes
* revalidation count

## Acceptance criteria

Repeated finalized-range queries must demonstrate reduced remote requests without changing query results.

Near-head reorg tests must remain correct.

---

# 8. M4 — Multi-chain architecture

## Goal

Prove that the architecture is genuinely multi-chain rather than EVM-specific.

Implementation status: the versioned adapter foundation, Aptos
`transactions`/`events` REST vertical slices, and Solana
`blocks`/`transactions`/`instructions` JSON-RPC vertical slices are implemented.
Solana's initial slice uses bounded finalized-slot `getBlock` reads and does not
admit cache entries until its adapter-defined identity and reorganization
contract are complete.

The ordered remaining work is tracked in
[`plans/M4_REMAINING_TASKS.md`](plans/M4_REMAINING_TASKS.md): completed
vertical slices, descriptor compatibility coverage, and the checklist for
subsequent chain adapters.

Add:

```text
Solana
Aptos
```

at least to meaningful read-only coverage.

## Solana

Initial tables:

```text
blocks
transactions
instructions
```

Potential later tables:

```text
rewards
accounts
```

Support Solana-native semantics.

Do not map:

```text
instruction → EVM log
```

or otherwise force EVM concepts.

## Aptos

Initial tables:

```text
transactions
events
```

Potential later tables:

```text
blocks
resources
modules
account_transactions
```

Support REST-style execution where appropriate.

This milestone must prove:

```text
logical runtime abstraction
≠
JSON-RPC-only runtime
```

## ChainAdapter maturity

M4 begins with a versioned declarative adapter contract. It must provide:

* strict format-version validation;
* explicit adapter and table evolution versions;
* native schema, table, and type declarations;
* JSON-RPC and REST method inventories;
* restricted split, predicate, projection, and literal request bindings;
* tolerant declared-field response mapping;
* immutable connector-lifetime registry composition.

This foundation must be used by an existing production path before additional
chains are added. It is not permission to expose metadata-only tables or to put
scripts, credentials, transport policy, or finality semantics into descriptors.

By the end of M4, adding a new chain should primarily require:

* table definitions
* type mappings
* remote operation mapping
* decoding
* finality behavior

and should not require rewriting:

* rate limiter
* retry system
* cache
* provider failover
* Trino page execution

Bitcoin now uses this extension boundary through an initial native UTXO
vertical slice (`blocks`, `transactions`, `inputs`, and `outputs`) with bounded
Bitcoin Core reads. Litecoin, Dogecoin, and Bitcoin Cash use the same
transport-neutral UTXO runtime and decoder, but retain separate descriptors,
schemas, and node identity matchers. This family extension does not add
provider-specific behavior or cache admission. Tron, Sui, Near, and additional
chains should subsequently use the same adapter boundary. Their support does
not change the M4 acceptance floor of meaningful Solana and Aptos vertical
slices.

## Acceptance criteria

The following should work from one Trino deployment:

```sql
SHOW TABLES FROM web3.ethereum;
SHOW TABLES FROM web3.solana;
SHOW TABLES FROM web3.aptos;
SHOW TABLES FROM web3.bitcoin;
SHOW TABLES FROM web3.litecoin;
SHOW TABLES FROM web3.dogecoin;
SHOW TABLES FROM web3.bitcoincash;
```

with native tables for each configured chain.

---

# 9. M5 — Production hardening and OSS v1

## Implementation status

M5.1 delivers safe local runtime snapshots through the five target system
tables. M5.2 validates native network identity across every configured primary
and fallback endpoint without bypassing the runtime. M5.3 publishes stable
Trino page-source metrics and safe provider-role runtime counters. M5.4 closes
configuration, endpoint secrecy, and connector/runtime lifecycle hardening.
Release engineering is documented and automated for the Trino 475 line; the
reproducible benchmark suite covers remote execution and cache paths. The
repository-local external-user acceptance gate is complete. Executing a real
tag release and deciding on Maven Central publication remain maintainer-owned
deployment operations, not unimplemented connector behavior.

## Goal

Reach a quality level appropriate for public production beta usage.

## Compatibility

Support:

* one primary Trino release line
* optionally one previous compatible release

CI should test declared compatibility explicitly.

## Operational features

Add:

* system tables
* structured metrics
* configuration validation
* credential masking
* endpoint sanitization
* endpoint-by-endpoint chain identity validation
* graceful shutdown
* disk cache corruption handling
* bounded memory behavior
* HTTP connection reuse
* resource leak tests

## System tables

Target:

```text
system.chains
system.providers
system.rpc_metrics
system.rate_limits
system.cache_stats
```

## Documentation

Required:

* installation
* configuration
* provider configuration
* chain configuration
* table reference
* pushdown behavior
* limitations
* troubleshooting
* performance tuning
* security guidance
* upgrade policy

## Release engineering

Add:

* semantic versioning policy
* changelog
* GitHub release workflow
* Maven artifact publication if appropriate
* compatibility matrix

## Benchmark suite

Create reproducible benchmarks for:

* request-per-item baseline
* batch RPC
* batch + rate limiting
* batch + cache

Metrics:

* latency
* throughput
* remote call count
* bytes transferred
* throttling events

## Acceptance criteria

The repository should be usable by an external engineer without author assistance.

---
