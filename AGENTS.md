# trino-web3 Agent Instructions

## Project goal

`trino-web3` is a production-grade multi-chain remote data connector for Trino.

The initial supported chains are:

* Ethereum / EVM
* Solana
* Aptos

Each chain should expose its native blockchain data model through Trino schemas and tables.

Examples:

* `ethereum.blocks`
* `ethereum.transactions`
* `ethereum.receipts`
* `ethereum.logs`
* `solana.blocks`
* `solana.transactions`
* `solana.instructions`
* `aptos.transactions`
* `aptos.events`

Do not force non-EVM chains into an EVM-compatible relational model.

The long-term research direction is broader than blockchain: remote APIs should be queryable as virtual relations, with cost-aware decisions between direct access, caching, and materialization.

## Architecture boundaries

Preserve this dependency direction:

```text
Trino SPI
    ↓
Connector Core
    ↓
Chain Adapter
    ↓
RPC Runtime
    ↓
Provider / Transport
```

Responsibilities:

### Trino SPI / Connector Core

Responsible for:

* schema and table metadata
* table and column handles
* predicate and projection pushdown
* split planning
* Trino lifecycle integration
* query cancellation propagation

Must not:

* contain provider-specific logic
* implement chain-specific decoding
* perform large RPC reads from metadata methods
* create per-query thread pools

### Chain Adapter

Responsible for:

* chain-native table definitions
* chain-native type mappings
* mapping logical scans to remote operations
* decoding RPC responses
* chain-specific finality semantics

Must not:

* implement HTTP transport
* own retry policies
* own rate limiting
* own provider failover

### RPC Runtime

Responsible for:

* batching
* concurrency control
* rate limiting
* retry and backoff
* provider selection
* failover
* request deduplication
* caching
* cancellation
* metrics and tracing

Must not:

* depend on Trino table metadata
* define blockchain schemas

### Provider Adapter

Responsible for:

* provider capabilities
* quota interpretation
* provider-specific HTTP headers
* provider-specific error classification
* provider-specific batch or request limits

Must not:

* define Trino tables
* define blockchain schemas
* contain query planning logic

## Execution model

Remote scans should follow this model where possible:

```text
SQL
→ constraint / predicate pushdown
→ chain capability analysis
→ bounded split generation
→ RPC batch planning
→ provider selection
→ rate limiting
→ cache lookup
→ remote execution
→ decoding
→ Trino Page
```

Do not perform unbounded blockchain scans.

Large reads must always be represented as bounded Trino splits.

Push predicates to the remote system whenever the remote API supports them.

Avoid request-per-row execution when batching or range-based execution is possible.

## Multi-chain principles

Do not over-generalize chain models.

EVM, Solana, and Aptos may share execution infrastructure, but they do not share the same data model.

Prefer:

```text
common runtime
+ chain-specific adapters
```

over:

```text
one universal blockchain schema
```

New chains should be added primarily through a chain adapter, without modifying provider-independent runtime behavior.

## RPC runtime rules

All remote calls must have explicit limits for:

* timeout
* concurrency
* request size
* batch size
* retry count
* rate or quota budget

Do not assume that all chains support JSON-RPC batching.

Do not assume that all providers have identical semantics.

Handle partial batch failure explicitly.

Match JSON-RPC batch responses by request ID, never by response ordering.

Respect `Retry-After` and provider-specific throttling signals when available.

RPC retries must be bounded.

Do not retry permanent failures such as invalid parameters or unsupported methods.

## Cache correctness

Prefer immutable identifiers for cache keys.

For blockchain data, prefer block hash or transaction hash over block number when possible.

Differentiate at least:

* pending / head
* safe
* finalized

Finalized immutable data may use long-lived cache entries.

Recent mutable data must use short-lived or validation-based cache policies.

RPC transport errors must never be cached as missing data.

Negative caching must be short-lived and used only when absence is semantically meaningful.

Cache must be an optimization, never the source of truth.

If cache data is missing or corrupted, the system must be able to fall back to the remote provider.

## Reorg and finality

Correctness around chain reorganization must be explicit.

Do not treat block height as permanently immutable near the chain head.

Code handling block-number lookups must account for the possibility that the canonical block hash changes.

Finality semantics belong to the chain adapter, not the generic runtime.

Tests must cover reorg-sensitive behavior where applicable.

## Trino development principles

Follow current Trino coding and SPI conventions.

Prefer:

* immutable objects
* explicit ownership of resources
* small focused classes
* constructor injection
* reusable HTTP clients
* bounded memory usage
* cancellation-aware asynchronous work

Avoid:

* nullable shared state
* hidden global state
* per-query executors
* per-split HTTP clients
* blocking coordinator operations
* expensive network calls from metadata enumeration
* implicit retries
* unbounded queues

Do not add abstractions purely for future possibilities.

Prefer the smallest design that satisfies the current milestone while preserving documented architecture boundaries.

## Testing requirements

Tests must not require paid external RPC providers.

Provide deterministic local or mock servers for remote protocol behavior.

Changes to RPC execution must include tests for relevant failure modes, including where applicable:

* timeout
* HTTP 429
* `Retry-After`
* connection failure
* partial batch failure
* malformed responses
* provider failover
* cancellation
* cache hit and miss
* reorg behavior

Connector tests should cover:

* schema discovery
* table discovery
* metadata
* predicate pushdown
* split generation
* query execution
* cancellation
* distributed execution where relevant

Behavioral fixes must include regression tests.

## Security and secrets

Never commit:

* RPC API keys
* bearer tokens
* credentials
* private endpoints containing secrets

Secrets must not appear in logs, exceptions, metrics, or test snapshots.

Provider URLs should be sanitized before logging if they may contain credentials.

## Performance rules

Do not optimize without measurement, but protect known hot paths.

Do not materialize full remote payloads when projection can avoid unnecessary decoding.

Avoid unnecessary copies of large JSON or binary payloads.

Do not fetch data that can be eliminated through predicate or projection pushdown.

RPC batching, caching, and concurrency changes should include benchmark evidence when they affect hot paths.

## Research boundary

Production correctness takes priority over experimental research behavior.

Experimental features such as:

* adaptive batching
* adaptive concurrency
* cost-based provider selection
* automatic materialization
* query-vs-cache-vs-materialize planning

must be isolated behind explicit configuration or experimental modules until proven stable.

Do not introduce experimental planner behavior into the default production path without documentation and tests.

## Required reading

Before changing Trino SPI integration, read:

* `docs/TRINO_STYLE_GUIDE.md`
* `docs/CONNECTOR_SPI.md`
* `ARCHITECTURE.md`

Before changing RPC execution, read:

* `docs/RPC_RUNTIME.md`
* `docs/CACHE_AND_FINALITY.md`

Before adding a new chain, read:

* `docs/CHAIN_MODEL.md`

Before large architectural changes, read:

* `docs/RESEARCH.md`

## Change discipline

For changes spanning multiple modules or affecting architecture:

1. inspect existing relevant code
2. read the applicable architecture documents
3. write a short implementation plan
4. identify correctness risks
5. identify required tests
6. implement incrementally
7. run validation
8. update documentation when behavior or architecture changes

Do not silently change architecture conventions.

If implementation requirements conflict with documented architecture, update the design documentation explicitly rather than working around it.

## Completion criteria

Before considering a task complete:

* code compiles
* Maven validation passes
* relevant unit tests pass
* relevant connector integration tests pass
* no external paid provider is required for tests
* no secrets are present
* cancellation and resource cleanup are preserved
* new behavior has tests
* documentation is updated when contracts or architecture changed

Do not report a task complete if required validation was not run.

If some validation cannot be run, state exactly what was not validated and why.
