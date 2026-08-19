# trino-web3 Roadmap

## 1. Vision

`trino-web3` will evolve from a production-grade multi-chain RPC connector for Trino into a research platform for cost-aware remote query execution and query-driven ingestion.

The project should progress in this order:

```text
Connector correctness
        ↓
Production RPC runtime
        ↓
Cache / finality correctness
        ↓
Multi-chain support
        ↓
Cost model
        ↓
Adaptive execution
        ↓
Query-driven materialization
```

Do not skip production correctness in order to reach research features faster.

The first major objective is:

> Make live blockchain RPC endpoints behave like reliable, bounded, queryable Trino data sources.

The long-term objective is:

> Let the system decide whether remote data should be queried directly, cached, or materialized based on cost, freshness, and workload reuse.

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
* production features separated from experimental research features

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
M6  RPC cost model
M7  Adaptive RPC execution
M8  Query-driven materialization
M9  Research evaluation
M10 Publication and ecosystem adoption
```

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

Implementation status: the versioned adapter foundation and the first Aptos
`transactions` REST vertical slice are implemented. Aptos events and the
Solana vertical slice remain before M4 acceptance is complete.

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

Bitcoin, Tron, Sui, Near, and additional chains should subsequently use this
same extension boundary. Their support does not change the M4 acceptance floor
of meaningful Solana and Aptos vertical slices.

## Acceptance criteria

The following should work from one Trino deployment:

```sql
SHOW TABLES FROM web3.ethereum;
SHOW TABLES FROM web3.solana;
SHOW TABLES FROM web3.aptos;
```

with native tables for each chain.

---

# 9. M5 — Production hardening and OSS v1

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

# 10. M6 — RPC cost model

## Goal

Introduce the first research-oriented planning layer.

The system should estimate the cost of remote execution alternatives.

## Initial cost inputs

Model at least:

* expected remote request count
* expected latency
* batch size
* provider quota
* throttling penalty
* bytes transferred
* cache hit probability

Optional:

* monetary provider cost
* freshness penalty
* failure probability

## Initial planning alternatives

Compare:

```text
direct RPC
cached RPC
```

Do not add automatic persistent materialization yet.

## Planner mode

Production default:

```text
static
```

Experimental:

```text
cost-aware
```

Example configuration:

```properties
web3.execution.planner=static
```

or:

```properties
web3.execution.planner=experimental-cost
```

## Evaluation

Compare:

* static execution
* naive batch execution
* cost-based plan selection

## Acceptance criteria

The planner must produce measurable improvement on at least one non-trivial workload without degrading correctness.

---

# 11. M7 — Adaptive RPC execution

## Goal

Use runtime feedback to adjust execution behavior.

## Feedback signals

Observe:

* latency
* `429` frequency
* remaining quota
* provider health
* response size
* cache behavior

## Adaptive controls

Potential controls:

* batch size
* concurrency
* provider choice
* split pacing
* retry delay

Example:

```text
batch=100
concurrency=16

429 increases

→ batch=40
→ concurrency=6
```

## Safety

Adaptive behavior must:

* remain bounded
* converge toward safe execution
* never bypass configured hard limits
* be disableable

## Research questions

Measure:

* adaptation speed
* stability
* throughput
* tail latency
* throttling reduction
* provider cost

## Acceptance criteria

Under synthetic provider degradation, adaptive execution must outperform a fixed policy on defined metrics.

---

# 12. M8 — Query-driven materialization

## Goal

Allow the system to choose between querying remote data and materializing data locally.

This milestone begins the broader research direction.

## Execution alternatives

Support planning among:

```text
direct remote query
local cache
temporary materialization
persistent materialization
```

## Target storage

Initial persistent target:

```text
Iceberg
```

through Trino.

## Example

Conceptually:

```sql
SELECT *
FROM web3.ethereum.transactions
WHERE block_number BETWEEN ...;
```

may trigger one of:

```text
direct RPC
cached remote read
temporary local materialization
persistent Iceberg reuse
```

depending on policy.

## Workload signals

Potential signals:

* repeated range access
* repeated projection patterns
* finalized status
* expected reuse
* storage cost
* freshness requirements

## Explicit user mode first

Before automatic materialization, support explicit experimentation:

```text
query remote
→ record cost
→ materialize
→ compare reuse
```

Automatic materialization should only follow after the cost model is validated.

---

# 13. M9 — Research evaluation

## Goal

Build an evaluation package suitable for a database research paper.

## Baselines

At minimum compare against:

1. naive request-per-item RPC
2. static batch RPC
3. batch + TTL cache
4. pre-index/materialize everything
5. static rule-based materialization
6. proposed adaptive cost-based approach

## Workloads

Include:

### Ad hoc

Mostly one-off queries.

### Repeated dashboard

Repeated hot ranges and projections.

### Historical backfill

Large sequential scans.

### Freshness-sensitive

Recent mutable ranges.

### Mixed workload

Hot historical + fresh head data.

### Provider degradation

Latency spikes, `429`, provider outage.

## Domains

Primary:

* Ethereum
* Solana

For generalization, evaluate at least one non-blockchain remote API if the research thesis claims general remote API applicability.

## Metrics

Measure:

* end-to-end latency
* throughput
* tail latency
* remote request count
* throttling events
* provider cost
* bytes transferred
* cache hit ratio
* storage consumed
* materialization reuse
* freshness
* planner overhead
* cluster scalability

## Ablation studies

Disable individually:

* batching
* cache
* cost model
* adaptive concurrency
* provider switching
* materialization

to measure each contribution.

---

# 14. M10 — Publication and ecosystem adoption

## Goal

Turn the system into both a credible OSS project and a publishable research artifact.

## Trino ecosystem goals

Target:

* contributor call presentation
* Community Broadcast
* Trino Summit submission
* external connector ecosystem listing
* feedback from Trino maintainers

## Research paths

Depending on maturity:

```text
VLDB Demo
→ working connector and live system demo

VLDB Industrial
→ real production deployment and operational results

VLDB Research
→ cost model + adaptive execution + materialization + strong evaluation
```

## OSS goals

Track:

* external users
* external contributors
* issue response time
* release cadence
* compatibility maintenance
* production references

## Acceptance criteria

A successful M10 should produce at least two of:

* Trino community presentation
* external production user
* external contributor
* ecosystem listing
* research submission
* peer-reviewed publication

---

# 15. Suggested implementation order within milestones

When a milestone contains multiple features, prefer vertical slices.

Example for M1:

```text
1. blocks metadata
2. blocks split
3. one RPC request
4. page decoding
5. integration test

then

6. batching
7. transactions
8. additional pushdown
```

Avoid implementing all abstractions before one real query works end to end.

---

# 16. Feature gates

Experimental behavior must be explicitly gated.

Examples:

```properties
web3.execution.planner=static
web3.rpc.adaptive.enabled=false
web3.materialization.enabled=false
```

The stable default path should remain conservative.

---

# 17. Definition of done

A roadmap item is complete only when:

* implementation is merged
* unit tests exist
* integration tests exist where relevant
* failure modes are tested
* metrics exist where appropriate
* docs are updated
* architecture remains valid
* no paid provider is required for CI
* secrets are not logged
* cancellation works
* resource cleanup is verified

Performance-related features also require benchmark evidence.

---

# 18. Suggested release mapping

Possible release progression:

```text
0.1.0
- empty plugin
- EVM blocks

0.2.0
- transactions
- bounded splits
- predicate pushdown
- batch RPC

0.3.0
- rate limiting
- retries
- cancellation
- metrics

0.4.0
- cache
- reorg/finality

0.5.0
- Solana

0.6.0
- Aptos

1.0.0
- production-beta hardening complete
- public compatibility policy
- stable configuration model

1.x experimental
- cost planner
- adaptive execution

2.0 research candidate
- query-driven materialization
```

Version numbers are illustrative and may change.

---

# 19. Priority rules

When roadmap goals conflict, use this priority order:

```text
1. correctness
2. bounded resource usage
3. cancellation and failure safety
4. maintainable architecture
5. operability
6. performance
7. feature breadth
8. research novelty
```

Do not sacrifice correctness or production safety to accelerate experimental features.

---

# 20. Immediate next milestone

The immediate target is M0 followed by M1.

Do not begin Solana, Aptos, cache, or adaptive planning until the following vertical slice works reliably:

```sql
SELECT
    block_number,
    block_hash
FROM web3.ethereum.blocks
WHERE block_number BETWEEN ? AND ?;
```

This query must demonstrate:

```text
Trino metadata
→ predicate pushdown
→ bounded split generation
→ worker execution
→ batched RPC
→ cancellation-aware remote call
→ deterministic decoding
→ Trino Page
```

Once this path is stable, all later work should extend it rather than bypass it.
