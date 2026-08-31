# trino-web3 Architecture

## 1. Purpose

`trino-web3` is a production-grade multi-chain remote data connector for Trino.

Its primary goal is to expose blockchain networks as native Trino schemas and tables without requiring users to pre-index or pre-materialize blockchain data.

Example:

```sql
SHOW SCHEMAS FROM web3;

SHOW TABLES FROM web3.ethereum;

SELECT *
FROM web3.ethereum.blocks
WHERE block_number BETWEEN 23_000_000 AND 23_001_000;
```

The connector should support multiple blockchain families while preserving each chain's native data model.

Initial targets:

* Ethereum / EVM-compatible chains
* Solana
* Aptos

The long-term architecture should also support research into:

* cost-aware remote query execution
* adaptive RPC scheduling
* query-driven ingestion
* selective materialization
* workload-aware caching

These research concerns must remain isolated from the default production execution path until proven stable.

---

# 2. Architectural principles

The architecture follows these principles:

1. Preserve native chain semantics.
2. Separate Trino concerns from blockchain concerns.
3. Separate blockchain semantics from transport concerns.
4. Treat RPC as a distributed remote data source.
5. Make all scans bounded.
6. Make all remote work cancellable.
7. Make provider constraints explicit.
8. Treat cache as an optimization, not the source of truth.
9. Design for multiple providers and multiple chain families.
10. Keep experimental planning separate from production execution.

---

# 3. High-level architecture

```text
                    ┌─────────────────────────┐
                    │          Trino          │
                    │                         │
                    │ SQL / Optimizer / Tasks │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │   Connector SPI Layer   │
                    │                         │
                    │ Metadata                │
                    │ Table / Column Handles  │
                    │ Pushdown                │
                    │ Split Planning          │
                    │ Page Source             │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │     Connector Core      │
                    │                         │
                    │ Schema registry         │
                    │ Table registry          │
                    │ Scan model              │
                    │ Chain lookup            │
                    └────────────┬────────────┘
                                 │
                    ┌────────────┼────────────┐
                    │            │            │
                    ▼            ▼            ▼
             ┌───────────┐ ┌───────────┐ ┌───────────┐
             │    EVM    │ │  Solana   │ │   Aptos   │
             │ Adapter   │ │ Adapter   │ │ Adapter   │
             └─────┬─────┘ └─────┬─────┘ └─────┬─────┘
                   │             │             │
                   └─────────────┼─────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │       RPC Runtime       │
                    │                         │
                    │ Batching                │
                    │ Rate limiting           │
                    │ Retry / backoff         │
                    │ Provider selection      │
                    │ Failover                │
                    │ Single-flight           │
                    │ Cancellation            │
                    │ Metrics                 │
                    └────────────┬────────────┘
                                 │
                  ┌──────────────┼──────────────┐
                  │              │              │
                  ▼              ▼              ▼
            ┌──────────┐   ┌──────────┐   ┌──────────┐
            │  Cache   │   │ Provider │   │Transport │
            │  Layer   │   │ Profile  │   │ HTTP/RPC │
            └──────────┘   └──────────┘   └────┬─────┘
                                               │
                                               ▼
                                  ┌─────────────────────────┐
                                  │ Blockchain RPC Provider │
                                  │                         │
                                  │ Alchemy / QuickNode     │
                                  │ Helius / Generic nodes  │
                                  └─────────────────────────┘
```

---

# 4. Module layout

Recommended Maven module structure:

```text
trino-web3/
├── pom.xml
├── AGENTS.md
├── ARCHITECTURE.md
├── ROADMAP.md
├── PLANS.md
│
├── trino-web3-chain/
├── trino-web3-core/
├── trino-web3-adapter/
├── trino-web3-runtime/
├── trino-web3-evm/
├── trino-web3-solana/
├── trino-web3-aptos/
├── trino-web3-testing/
└── trino-web3-plugin/
```

Suggested responsibilities:

## `trino-web3-chain`

Contains the Trino-independent, versioned chain adapter contract.

Responsibilities:

* immutable chain, table, column, remote-method, request-binding, and response-mapping descriptors
* strict `web3.trino.io/v1alpha1` JSON parsing and validation
* connector-lifetime chain registry with unique chain and schema ownership
* explicit adapter and table evolution checks
* tolerant mapping of declared fields from evolving provider responses

Must not contain Trino SPI types, HTTP clients, provider configuration,
credentials, retry policy, finality policy, or executable scripts. Descriptors
are assembly data; complex chain semantics remain in code-based adapters.

---

## `trino-web3-adapter`

Contains the Trino-independent execution contract implemented by code-based
chain adapters.

Responsibilities:

* immutable pushed scan and bounded split models
* executable adapter registry keyed by native schema
* adapter-created data clients backed by the shared runtime
* named decoded rows with explicit missing/null distinction and retained-size accounting

It must not contain Trino SPI, HTTP transport, provider policy, retry, rate,
cache ownership, or a universal blockchain schema. Declarative descriptors
describe stable assembly data; code adapters own planning, decoding, finality,
and other chain semantics.

---

## `trino-web3-core`

Contains connector-independent domain abstractions shared across chain modules.

Examples:

* `ChainId`
* `RemoteScan`
* `RemoteOperation`
* `RemotePredicate`
* `BlockRange`
* generic execution descriptors

Must not contain:

* provider-specific behavior
* HTTP implementation
* Trino SPI implementation details where avoidable

---

## `trino-web3-runtime`

Contains the provider-independent remote execution runtime.

Responsibilities:

* RPC batching
* request scheduling
* rate limiting
* concurrency control
* retry
* backoff
* endpoint health
* provider selection
* failover
* request deduplication
* cache coordination
* metrics
* cancellation

This module must not depend on chain-specific table definitions.

It should ideally remain reusable by multiple chain implementations.

---

## `trino-web3-evm`

Contains EVM-specific semantics.

Responsibilities:

* EVM tables
* EVM type mappings
* JSON-RPC method mapping
* EVM response decoding
* EVM finality semantics
* EVM-specific pushdown rules

Initial tables:

```text
blocks
transactions
receipts
logs
```

Possible later tables:

```text
traces
contracts
storage
account_balances
```

---

## `trino-web3-solana`

Contains Solana-native semantics.

Responsibilities:

* Solana table definitions
* Solana type mapping
* Solana RPC mapping
* Solana response decoding
* Solana commitment semantics

Example tables:

```text
blocks
transactions
instructions
rewards
accounts
```

Do not force Solana data into an EVM-compatible schema.

---

## `trino-web3-aptos`

Contains Aptos-native semantics.

Responsibilities:

* Aptos tables
* REST or RPC mapping
* Aptos ledger version semantics
* response decoding
* Aptos-specific pagination

Example tables:

```text
blocks
transactions
events
resources
modules
account_transactions
```

The current M4 vertical slices implement `transactions` with a bounded
`ledger_version` range and `events` with account/creation-number keys plus a
bounded `sequence_number` range. The adapter caps REST pages at 100 entries and
validates the complete contiguous transaction response or the matching event
GUID/sequence response before row publication. Aptos endpoints and HTTP
lifecycle remain connector/runtime-owned. The Aptos adapter treats committed
range identities as finalized for cache admission after complete validation.

---

## `trino-web3-testing`

Contains shared testing infrastructure.

Examples:

* mock RPC server
* deterministic blockchain fixtures
* provider fault injector
* fake rate-limit responses
* reorg simulator
* malformed-response fixtures
* integration test helpers

Tests must not require a paid provider.

---

## `trino-web3-plugin`

Contains the actual Trino plugin integration.

Responsibilities:

* plugin registration
* connector factory
* configuration
* dependency injection
* Trino SPI implementations

Expected SPI-facing classes may include:

```text
Web3Plugin
Web3ConnectorFactory
Web3Connector
Web3Metadata
Web3SplitManager
Web3PageSourceProvider
```

---

# 5. Dependency direction

Dependencies must flow downward.

```text
trino-web3-plugin
 ├── trino-web3-chain
 ├── trino-web3-core
 └── chain modules
          ├── trino-web3-chain
          ├── trino-web3-core
          └── trino-web3-runtime
```

A more practical dependency graph may be:

```text
plugin
 ├── chain
 ├── core
 ├── evm
 ├── solana
 └── aptos

evm
 ├── chain
 ├── core
 └── runtime

solana
 ├── chain
 ├── core
 └── runtime

aptos
 ├── chain
 ├── core
 └── runtime

runtime
 └── core
```

Forbidden dependency directions:

```text
chain → trino SPI
chain → runtime
chain → chain implementations
runtime → trino SPI
runtime → EVM
runtime → Solana
runtime → Aptos

provider adapter → table metadata
provider adapter → Trino handles
```

---

# 6. Trino execution flow

A typical query:

```sql
SELECT
    block_number,
    transaction_hash,
    from_address
FROM web3.ethereum.transactions
WHERE block_number BETWEEN 23_000_000 AND 23_001_000;
```

should conceptually execute as:

```text
SQL
 ↓
Trino analysis
 ↓
Web3Metadata.getTableHandle()
 ↓
Web3Metadata.applyFilter()
 ↓
Web3Metadata.applyProjection()
 ↓
Web3SplitManager.getSplits()
 ↓
bounded block-range splits
 ↓
worker scheduling
 ↓
Web3PageSourceProvider.createPageSource()
 ↓
ChainAdapter creates RemoteOperations
 ↓
RpcRuntime schedules RPC work
 ↓
cache lookup
 ↓
provider execution
 ↓
decode
 ↓
Trino Page
```

---

# 7. Table and schema model

The default user-facing model is:

```text
catalog.schema.table
```

Example:

```text
web3.ethereum.blocks
web3.ethereum.transactions
web3.solana.blocks
web3.solana.instructions
web3.aptos.transactions
```

The schema should represent a configured chain/network endpoint.

A production deployment may use separate catalogs for environments:

```text
web3_mainnet.ethereum.blocks
web3_testnet.ethereum.blocks
```

This provides cleaner separation for:

* credentials
* permissions
* provider endpoints
* production vs test workloads

---

# 8. ChainAdapter abstraction

A chain adapter owns chain-native semantics.

The production adapter boundary starts with one immutable, versioned
descriptor:

```java
interface ChainAdapter
{
    ChainDescriptor descriptor();
}
```

`ChainDescriptor` declares native tables and types, JSON-RPC or REST method
inventory, bounded binding sources, and explicit JSON response pointers. The
format version controls syntax; adapter and table versions control evolution.
Changing a descriptor without the corresponding version increment is rejected
by compatibility validation.

The registry is immutable for a connector lifetime. There is no query-time hot
reload, and an operator descriptor is not exposed until an executable bounded
adapter exists for it.

The important boundary is semantic ownership.

A chain adapter may know:

* which tables exist
* which columns exist
* which predicates can be pushed down
* which RPC methods provide the data
* how responses map to relational rows
* what finality means

A chain adapter must not know:

* HTTP connection pool details
* retry policy
* provider credential handling
* global concurrency policy

Descriptors additionally cannot contain endpoints, secrets, provider headers,
arbitrary expressions, or executable code. Unknown provider response fields
are ignored, optional mapped fields become null, and missing required fields
fail without embedding the remote payload in the exception.

---

# 9. RemoteScan model

Do not pass raw SQL concepts deep into the runtime.

Convert Trino-level constraints into an intermediate remote scan model.

Example:

```text
RemoteScan

chain      = ethereum
table      = logs

blockRange = [23000000, 23001000]

filters:
  address = 0x...
  topic0  = 0xddf252...

projection:
  block_number
  transaction_hash
  address
  topics
  data
```

The chain adapter converts this scan into remote operations.

Example:

```text
eth_getLogs
fromBlock=23000000
toBlock=23001000
address=...
topics=[...]
```

---

# 10. Split planning

Every potentially large scan must be bounded.

Example:

```text
requested range:
23,000,000 → 23,010,000
```

may become:

```text
Split 1: 23,000,000 → 23,000,499
Split 2: 23,000,500 → 23,000,999
...
```

Split size should eventually be influenced by:

* expected response size
* remote method
* provider capability
* chain throughput
* runtime feedback

Initial production behavior should use deterministic static policies.

Adaptive split sizing belongs to the experimental planner until proven stable.

---

# 11. Predicate pushdown

Pushdown should be explicit.

Examples:

## Ethereum blocks

Supported:

```text
block_number =
block_number BETWEEN
block_hash =
```

## Ethereum logs

Supported where provider allows:

```text
block range
address
topic0
topic1
topic2
topic3
```

Unsupported predicates should remain in Trino.

Example:

```sql
WHERE input LIKE '%deadbeef%'
```

must not be translated to arbitrary RPC behavior unless the remote protocol natively supports it.

The production coordinator derives bounded access paths from descriptor method
bindings. Required `SPLIT` and `PREDICATE` inputs must all be satisfiable before
a method is selected. The immutable table handle stores the selected method and
named predicate values; it has no EVM-specific fields. Range domains removed
from the residual are enforced by adapter-generated splits. Discrete string
domains remain residual because native identifier equality and normalization
belong to the chain adapter.

---

# 12. Projection pushdown

Avoid fetching or decoding unused data when possible.

Example:

```sql
SELECT block_number, block_hash
FROM web3.ethereum.blocks
...
```

should not necessarily deserialize every nested transaction if the selected remote method can avoid it.

Projection decisions belong to:

```text
Trino metadata
→ chain adapter
→ remote operation selection
```

The runtime should execute the resulting operation without understanding relational columns.

---

# 13. RPC Runtime

The runtime accepts logical remote operations.

Example:

```text
RpcOperation

chain
provider capability requirement
method
parameters
estimated response size
priority
cancellation token
```

The runtime owns execution mechanics.

Pipeline:

```text
RemoteOperation
 ↓
Cache lookup
 ↓
Single-flight deduplication
 ↓
Batch planner
 ↓
Rate limiter
 ↓
Provider selector
 ↓
HTTP transport
 ↓
Response classification
 ↓
Retry / failover
 ↓
Cache write
 ↓
Result
```

---

# 14. Batching

Batching is a runtime concern, but batch semantics may vary by chain.

Examples:

```text
EVM
→ JSON-RPC batch

Solana
→ JSON-RPC batch or bounded parallel calls

Aptos
→ REST concurrency
```

One runtime instance serves one chain protocol and provider set. JSON-RPC
runtimes may coalesce compatible operations into a wire batch. REST runtimes
always dispatch one request per wire attempt while reusing the same bounded
queue, rate admission, retry, health, failover, cancellation, single-flight,
and metric machinery. A catalog may own multiple schema-specific runtimes, all
closed by the connector lifecycle.

Therefore the runtime should not assume a single wire-level batching format.

A useful abstraction is:

```text
logical batch
≠
JSON-RPC batch
```

---

# 15. Rate limiting

Provider quota must be treated as an explicit execution resource.

Rate limiting may depend on:

* provider
* endpoint
* RPC method
* batch size
* compute units
* response size

The runtime should support:

* configured limits
* provider profiles
* runtime throttling feedback
* HTTP `429`
* `Retry-After`
* provider-specific quota headers

The initial implementation may use worker-local rate limiting.

Strict distributed global quotas may later require an external coordination mechanism.

Such coordination must remain optional.

---

# 16. Provider model

Provider profiles encapsulate vendor behavior.

Conceptual interface:

```java
interface ProviderProfile
{
    String name();

    ProviderCapabilities capabilities();

    ErrorClassification classifyFailure(RemoteResponse response);

    Optional<Duration> retryAfter(RemoteResponse response);

    Optional<QuotaSnapshot> readQuota(RemoteResponse response);
}
```

Potential implementations:

```text
GenericEvmProvider
AlchemyProvider
QuickNodeProvider
InfuraProvider
HeliusProvider
```

Provider profiles must not define chain schemas.

---

# 17. Provider selection

A chain may have multiple providers.

Example:

```yaml
ethereum:
  providers:
    - name: primary
      type: alchemy
      url: ...
    - name: fallback
      type: generic
      url: ...
```

Initial selection policy may be:

```text
primary-first
```

Later policies may consider:

* latency
* error rate
* quota remaining
* provider capabilities
* monetary cost

Cost-aware provider selection belongs to the research planner until stable.

---

# 18. Retry semantics

Retries must be bounded and classified.

Retryable examples:

* timeout
* connection reset
* temporary server error
* throttling after delay

Non-retryable examples:

* invalid params
* unsupported method
* malformed user configuration

Do not treat every non-2xx response as retryable.

RPC-level retry must account for Trino task/query retry behavior.

Avoid multiplicative retry storms.

---

# 19. Cancellation

Query cancellation must propagate to remote work.

Expected flow:

```text
Trino query cancelled
 ↓
task cancelled
 ↓
page source closed
 ↓
remote operations cancelled
 ↓
queued requests removed
 ↓
in-flight HTTP requests cancelled when possible
```

No cancelled query should continue consuming provider quota unnecessarily.

---

# 20. Cache architecture

Initial cache hierarchy:

```text
L1: worker-local memory cache
L2: optional worker-local disk cache
L3: remote provider
```

Potential future layer:

```text
L3 materialized lakehouse storage
```

Cache must remain transparent to correctness.

---

# 21. Cache keys

Prefer immutable identities.

Example:

```text
network identity
+
operation
+
canonical parameters
+
immutable block hash where applicable
```

Avoid treating block height as permanently immutable near the chain head.

Potential key:

```text
chain-id /
genesis-hash /
rpc-method /
decoder-version /
sha256(canonical-parameters)
```

---

# 22. Finality model

Finality is chain-specific.

The generic runtime may understand a generic classification:

```text
HEAD
SAFE
FINALIZED
```

but each chain adapter defines how these states are determined.

Example policies:

```text
HEAD
→ very short TTL

SAFE
→ moderate TTL

FINALIZED
→ long-lived or size-evicted cache
```

Pending data should normally not be persistently cached.

---

# 23. Reorganization handling

Near-head block-number queries must not assume immutable identity.

Preferred sequence:

```text
block number
 ↓
canonical block hash resolution
 ↓
block hash-based cache
```

For finalized ranges, revalidation may be unnecessary depending on chain semantics.

Tests must simulate reorg behavior.

---

# 24. Error model

Errors should be classified into stable categories.

Example:

```text
USER_ERROR
CONFIGURATION_ERROR
REMOTE_THROTTLED
REMOTE_UNAVAILABLE
REMOTE_INVALID_RESPONSE
UNSUPPORTED_OPERATION
INTERNAL_ERROR
```

Provider-specific errors should be normalized before reaching Trino-facing code.

Error messages must not leak credentials.

---

# 25. Observability

Expose metrics for at least:

```text
rpc_requests_total
rpc_requests_failed
rpc_requests_retried
rpc_requests_throttled
rpc_latency
rpc_batch_size
rpc_inflight
provider_health
provider_failover_count

cache_hits
cache_misses
cache_evictions
cache_bytes

splits_created
splits_completed
rows_decoded
bytes_received
```

Potential system tables:

```text
system.chains
system.providers
system.rpc_metrics
system.rate_limits
system.cache_stats
```

---

# 26. Configuration

Configuration should distinguish:

* chain configuration
* provider configuration
* runtime configuration
* cache configuration

Example:

```properties
connector.name=web3

web3.config-file=/etc/trino/web3.yaml
```

Example YAML:

```yaml
chains:
  ethereum:
    type: evm
    network: mainnet
    chainId: 1

    providers:
      - name: primary
        type: alchemy
        url: ${ETHEREUM_RPC_URL}

    runtime:
      maxConcurrency: 16
      batchMaxItems: 100

    cache:
      memoryEnabled: true
      diskEnabled: true
```

Configuration values containing secrets must never appear in logs.

---

# 27. Testing architecture

Testing should be layered.

```text
Unit tests
    ↓
Chain adapter tests
    ↓
RPC runtime tests
    ↓
Connector integration tests
    ↓
Distributed Trino tests
    ↓
Fault injection
```

Required simulated failure scenarios:

* timeout
* `429`
* `Retry-After`
* malformed JSON
* partial batch failure
* connection reset
* provider failover
* cancellation
* reorg
* cache corruption

---

# 28. Mock RPC infrastructure

Production tests must not require live blockchain providers.

`trino-web3-testing` should provide a deterministic mock server capable of:

```text
serving blocks
serving transactions
serving logs
simulating batch responses
returning responses out of request order
returning 429
returning Retry-After
delaying responses
failing selected requests
simulating reorgs
simulating provider outage
```

The mock environment should be capable of executing full Trino integration tests.

---

# 29. Security

The connector is read-oriented.

Write-oriented blockchain operations such as:

```text
eth_sendRawTransaction
```

should not be exposed through relational tables in the initial architecture.

Reasons:

* query retries
* task retries
* duplicate side effects
* unclear transaction semantics

Future write functionality would require a separate design.

---

# 30. Research extension

Production connector flow:

```text
Query
 ↓
Static planning
 ↓
RPC / cache
```

Research extension:

```text
Query
 ↓
Remote cost model
 ↓
Execution alternatives

 ├── direct RPC
 ├── cached RPC
 ├── temporary materialization
 └── persistent materialization

 ↓
runtime feedback
 ↓
adaptive replanning
```

Possible cost dimensions:

```text
RPC monetary cost
latency
provider quota
throttling probability
network bytes
cache probability
expected query reuse
storage cost
freshness penalty
```

This layer must not become a mandatory dependency of the stable production runtime.

---

# 31. Query-driven materialization

The long-term research model is:

```text
query first
materialize later
```

Rather than:

```text
index everything first
query later
```

Potential policies:

```text
one-off query
→ direct RPC

repeated immutable range
→ cache

highly reused finalized range
→ persistent materialization

fresh mutable range
→ direct RPC or short-lived cache
```

This capability is explicitly out of scope for the first production milestone.

---

# 32. Milestone architecture

## M1

```text
Ethereum
blocks
transactions
bounded splits
predicate pushdown
JSON-RPC batch
```

No adaptive planner.

No automatic materialization.

Minimal cache only if necessary.

---

## M2

```text
production RPC runtime
rate limiting
retry
backoff
cancellation
provider failover
metrics
```

---

## M3

```text
cache
finality
reorg correctness
```

---

## M4

```text
Solana
Aptos
generic chain adapter model
```

---

## M5+

```text
cost model
adaptive scheduling
query-driven materialization
```

---

# 33. Non-goals

The initial project is not intended to be:

* a full blockchain indexer
* a blockchain archival node
* a Dune replacement
* a universal blockchain schema
* a transaction submission service
* a smart-contract execution environment
* a distributed cache product

The connector should remain focused on turning remote blockchain data into a well-behaved Trino data source.

---

# 34. Architectural decision process

Major architectural choices should be recorded under:

```text
docs/DECISIONS/
```

Suggested ADR format:

```text
0001-use-native-chain-schemas.md
0002-bounded-block-range-splits.md
0003-runtime-independent-from-trino-spi.md
0004-cache-by-immutable-identifiers.md
```

Each ADR should record:

* context
* decision
* alternatives
* consequences
* status

Do not make major dependency-boundary changes without recording an ADR.

---

# 35. Core invariants

These invariants should remain true as the project evolves:

1. Trino SPI does not contain provider-specific behavior.
2. Chain adapters preserve native chain semantics.
3. RPC runtime does not depend on Trino table metadata.
4. Provider adapters do not define chain schemas.
5. Large scans are bounded.
6. Remote operations are cancellable.
7. Retries are bounded.
8. Provider quotas are explicit.
9. Cache is never authoritative.
10. Finality semantics remain chain-specific.
11. Tests do not depend on paid providers.
12. Research features remain isolated from stable production execution.

If a proposed implementation violates one of these invariants, the architecture must be explicitly reconsidered before implementation.
