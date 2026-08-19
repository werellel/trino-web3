# Plan: M3 cache, finality, and reorg correctness

Status: implemented and validated on `m3-cache-finality-reorg`.

## Goal

Add a bounded worker-local cache that reduces repeated RPC work for finalized
EVM data without treating a mutable block number as immutable. Preserve the M2
asynchronous scheduler, cancellation, single-flight, retry, failover, and
execution-scoped metric contracts.

## Scope

- Opt-in L1 worker-local memory caching.
- Immutable block responses keyed by canonical block hash.
- Bounded equality/IN pushdown on `ethereum.transactions.hash`, using
  `eth_getTransactionByHash`, so transaction-hash entries have a real SQL path.
- Immutable finalized transaction responses keyed by transaction hash.
- Canonical cache parameter normalization.
- Weight-based eviction and an optional operational TTL.
- EVM `HEAD`, `SAFE`, and `FINALIZED` classification.
- Finalized block-number to canonical-hash references.
- Near-head revalidation and deterministic reorganization tests.
- Cache metrics and benchmark evidence.

## Non-goals

- L2 worker-local disk caching. It remains an optional roadmap layer and needs
  a separate persistence and corruption-recovery design before it is enabled.
- A shared or distributed cache.
- Negative caching. M3 will not cache missing results, which is stricter than
  the roadmap allowance for explicitly safe absence semantics.
- Provider-specific cache rules, headers, or quota behavior.
- Solana, Aptos, new EVM SQL tables, transaction submission, adaptive cache
  policy, or cost-based planning.
- A general connector dependency-injection rewrite.

## Trino 475 baseline

M3 will use the Trino 475 BOM-managed `io.trino:trino-cache` artifact and its
`EvictableCacheBuilder`, following the same pattern used by Trino 475's memory
filesystem and metastore caches:

- every cache has a size or weight bound;
- disabled behavior is selected explicitly;
- statistics are recorded;
- TTL is applied only when configured;
- invalidation-safe Trino cache utilities are preferred over a private cache
  implementation;
- cache loading is not delegated to a blocking worker. The existing M2
  asynchronous scheduler and single-flight registry load misses.

The implementation remains pinned to Trino 475 and Java 23. The plugin ZIP
test must prove that `trino-cache` and its runtime dependencies are packaged
and load through Trino's plugin classloader.

## Correctness contract

### Ownership

`trino-web3-runtime` owns cache storage, bounds, statistics, and integration
with single-flight. It treats keys and values as provider-independent opaque
remote data.

`trino-web3-evm` owns Ethereum quantity/hash normalization, finality boundary
resolution, block-number classification, canonical-hash selection, and the
decision whether a completed result is immutable and cacheable.

The plugin owns catalog configuration and the lifetime of one cache-bearing
runtime per connector instance. Closing the connector clears cache references
and stops the existing scheduler; no per-query cache or executor is created.

### Cache keys

An immutable result key contains:

```text
chain namespace
+ RPC operation
+ canonical parameters
+ response representation
+ decoder/cache format version
```

The current cacheable operations use only one normalized EVM quantity or
fixed-size hash as their parameter identity: quantities are lower-case minimal
hexadecimal and hashes are lower case. The `fullTransactions` representation
bit is part of a block key. A general JSON canonicalizer is deliberately not
introduced before a current operation needs composite JSON identity. Raw URLs,
credentials, provider names, request IDs, SQL query IDs, addresses, and
unconstrained user text are never key dimensions or metric labels.

Block payloads are stored under their canonical block hash. A finalized
block-number reference is a small, separately bounded mapping to that hash; it
is not the cached block payload. Near-head and safe number references are not
retained across scans.

Transaction payloads are keyed only when addressed by transaction hash and the
returned inclusion block is classified `FINALIZED`. A pending or near-head
transaction can acquire or change inclusion metadata during a reorg, so its
hash alone does not make the returned RPC object immutable. Such results are
revalidated. The current block-range transaction scan reuses the immutable
full-block payload. Equality/IN pushdown on the existing transaction `hash`
column makes the transaction-hash cache path production-reachable without
adding a table.

### Cache values

Successful JSON-RPC result values are serialized to bounded UTF-8 JSON bytes
only after adapter validation and before admission. This prevents mutable
`JsonNode` instances returned to an adapter from mutating a cached value and
provides an exact serialized payload size. Each hit is decoded into a fresh value.
Entries larger than the configured maximum entry size bypass the cache and are
still returned to the caller.

The eviction weight is the serialized payload size plus a deterministic key
size estimate and fixed entry overhead. The retained-byte metric reports this
same weight; it does not claim to measure JVM object layout exactly.

Admission is deliberately two phase. The runtime returns a successful remote
result as uncommitted cache work; the adapter validates and decodes the complete
block or transaction, derives its immutable key and finality, and explicitly
commits admission. The commit is idempotent for single-flight subscribers. A
batch decoder failure admits none of that logical batch. This keeps
chain-specific validation out of the runtime while ensuring that an RPC-valid
but EVM-invalid payload is not retained.

Provider provenance is not part of semantic cache identity. Cache-hit metrics
report that no provider served the value; the adapter result does not invent
provider provenance for a hit.

### Finality and EVM block lookup

When caching is enabled, the EVM adapter resolves the standard `safe` and
`finalized` block tags and classifies requested numbers as follows:

```text
number <= finalized boundary       FINALIZED
number <= safe boundary            SAFE
number above safe boundary         HEAD
```

Invalid or inconsistent boundaries are never used to make data more
cacheable. If a provider does not support these tags, the adapter conservatively
classifies the affected scan as `HEAD`; query correctness is preserved at the
cost of cache misses. It does not substitute a configured confirmation count
or embed Ethereum finality assumptions in the generic runtime.

The lookup flow is:

```text
block number
-> classify finality
-> FINALIZED: consult finalized number-to-hash reference
-> hash hit: consult immutable block-hash result
-> otherwise: execute eth_getBlockByNumber and validate number/hash
-> store payload by returned hash
-> store number-to-hash reference only when FINALIZED
```

`SAFE` and `HEAD` lookups execute by block number on every scan. Their returned
payload may be admitted under the returned hash, but the mutable number is not
bound to that hash for a later scan. Thus a reorg from hash A to hash B leaves
the immutable A entry harmless and the next number lookup returns B.

Finality boundary reads use M2 single-flight while in flight and a completed
snapshot for at most one second. An older boundary is conservative because it
can only delay classification of newly safe or finalized data. Unsupported or
malformed boundaries share the same short lifetime. This prevents every warm
split from issuing finality RPC while refreshing promptly enough for new data.

### Failures and absence

Only an adapter-committed, fully validated successful result is admitted. The
cache never stores:

- timeout, cancellation, connection, HTTP 429, HTTP 4xx/5xx, or retry failure;
- JSON-RPC error, partial batch failure, malformed JSON, missing response ID,
  oversized response, decoder failure, or a null/missing block;
- a cancelled load for which no live subscriber remains.

Cache read or deserialization failure invalidates that entry and falls back to
the remote provider in the same bounded execution. Cache failures do not turn
remote data into a missing row and do not consume the RPC retry budget.

## Single-flight and cancellation

When an immutable key is already known, cache lookup occurs before admission to
the M2 shared-operation registry. A miss enters the existing registry using the
canonical identity so equivalent misses share one remote execution. A
block-number request whose hash is not yet known continues to single-flight by
its normalized remote operation and is admitted under the validated returned
hash. A cancelled subscriber detaches without cancelling work needed by another
subscriber. Cancellation of the last subscriber removes queued work or cancels
the transport as in M2 and does not admit an incomplete value.

Cache hits return already-completed asynchronous results and allocate no worker
task. Cache serialization after a successful remote response is bounded by the
maximum response and maximum entry sizes.

Each execution tracks cancellation independently. Cache serialization runs
outside the scheduler lock, and final insertion is serialized with that
execution's cancellation state. A cancelled execution therefore cannot admit a
late value even when a completion callback was already running. Cache reads
also have an aggregate execution budget equal to the configured maximum RPC
response size; a hit beyond the remaining budget follows the bounded remote
path.

## Configuration contract

All new properties are catalog properties, non-secret, and optional:

| Property | Type | Default | Validation | Scope |
| --- | --- | --- | --- | --- |
| `web3.cache.enabled` | boolean | `false` | `true` or `false` | catalog/worker |
| `web3.cache.maximum-size` | data size | `128MB` | 1 MB to 1 GB when enabled | catalog/worker |
| `web3.cache.maximum-entry-size` | data size | `8MB` | at least 1 KB; no greater than cache or RPC response maximum when enabled | catalog/worker |
| `web3.cache.ttl` | duration | unset | positive when present | immutable-entry operational eviction |
| `web3.maximum-transaction-hashes-per-query` | integer | `1000` | 1 to 10000 | bounded hash predicate |

Disabling the cache preserves M2 request behavior and still preserves
single-flight. No secret or endpoint value is copied into cache configuration,
keys, errors, or metrics. Human-readable data sizes and durations follow Trino
configuration conventions; exact defaults and validation are covered by
configuration tests and documented in `README.md`.

## Metrics

Execution-scoped Trino PageSource metrics add:

- `web3.cache.hits`
- `web3.cache.misses`
- `web3.cache.revalidations`
- `web3.cache.bytes-read`
- `web3.cache.bytes-written`

The runtime also exposes a worker-global cache snapshot containing entry count,
currently retained bytes, and eviction count. Eviction and current-byte state
come from the shared cache and cannot be truthfully attributed to one
PageSource, so they are not mixed into execution-scoped counters. M3 does not
add the `system.cache_stats` table reserved for M5. Metrics have fixed names and
no hash, address, endpoint, provider, or query labels.

M3 requires all endpoints configured in one catalog to address the same EVM
chain. Validating every endpoint requires a provider-targeted runtime operation;
implementing direct EVM HTTP probes would violate the transport boundary. M5
therefore owns endpoint-specific `eth_chainId` verification together with the
operator-facing `system.cache_stats` surface. These are explicit configuration
and observability boundaries, not silent M3 claims.

## Affected modules

- `trino-web3-runtime`: immutable cache key/value model, Trino 475 cache
  dependency, bounded cache, single-flight integration,
  scoped/global metrics, corruption fallback, and deterministic cache tests.
- `trino-web3-evm`: EVM canonicalization, finality resolver, finalized canonical
  references, block/full-block hash lookup, transaction-hash lookup and
  decoding, and reorg-sensitive tests.
- `trino-web3-core`: the minimal serializable scan/split variant needed to
  represent either a bounded block range or bounded transaction hashes.
- `trino-web3-plugin`: validated cache configuration, connector lifecycle,
  transaction hash equality/IN pushdown and split planning, PageSource cache
  metrics and memory reporting, case-sensitive residual hash predicates, and
  unchanged cancellation propagation.
- `trino-web3-testing`: mutable deterministic chain fixture, finalized reuse,
  near-head reorg, distributed worker-local behavior, and ZIP packaging tests.
- `README.md`, `docs/CACHE_AND_FINALITY.md`, `docs/RPC_RUNTIME.md`, and
  `docs/TESTING.md`: configuration, contracts, tests, and operational behavior.
- `pom.xml` and module POMs: only BOM-managed Trino cache/configuration and
  benchmark dependencies actually required by the implementation.

No new Maven module is required.

## Correctness risks and required tests

| Risk | Required proof |
| --- | --- |
| Block number aliases a reorged hash | Query N as A, switch mock canonical hash to B, query again, and assert B with a revalidation. |
| Finality boundary is unsupported or malformed | Fall back to HEAD behavior and return correct remote data without cache admission by number. |
| Safe/finalized boundaries are inverted | Reject the snapshot for caching and use conservative HEAD behavior. |
| Transaction hash is pending or reorged | Do not cache until its inclusion block is FINALIZED; repeat lookup and assert changed inclusion is visible. |
| Cache key collisions or parameter variants | Unit tests for EVM quantity/hash normalization and representation separation. |
| Mutable result corrupts later hits | Mutate a returned tree and prove the cached serialized value remains unchanged. |
| RPC-valid payload fails EVM decoding | Fail adapter validation and prove no entry is admitted; a repeated query performs remote work again. |
| Errors become cached absence | Repeat timeout, 429, 5xx, malformed, partial-batch, and null-result cases and prove each performs remote work again. |
| Concurrent miss duplicates RPC work | Many identical callers produce one remote operation and independent subscriber cancellation. |
| Last subscriber cancels a miss | No cache admission and no queued/in-flight resource leak. |
| Entry or cache exceeds memory bounds | Weight eviction, maximum-entry bypass, exact retained-byte checks, and overflow validation. |
| Cache TTL silently retains an expired value | Advance an injected Trino-cache ticker and prove the entry disappears without a wall-clock sleep. |
| Logical batch limit of one disables finality | Submit safe and finalized as separate logical operations and prove finalized cache reuse with a maximum batch size of one. |
| Large transaction hash domain reaches split planning | Reject while Metadata extracts the discrete domain and retain the SplitManager defense. |
| Cache-enabled remote failure is admitted | Repeat timeout, HTTP 429, and partial-batch failure and prove zero retained entries. |
| Cache dependency is absent from distribution | Load and query the assembled plugin ZIP with cache enabled. |
| Metrics leak across PageSources | Concurrent executions retain isolated hit/miss/revalidation/read/write byte counters; shared eviction/retained-byte state remains explicitly global. |
| Worker-local cache is mistaken for distributed | Distributed test allows one warm-up per worker and asserts result correctness, not a cluster-global hit. |

All protocol tests use deterministic local transports or HTTP servers and no
external RPC endpoint or credential.

## Benchmark plan

Record both functional request reduction and local cache overhead:

1. A deterministic finalized-range integration workload runs the same bounded
   query cold and warm and records RPC operation count, response bytes, and
   elapsed time. The warm run must return byte-for-byte equivalent rows with
   fewer remote data operations.
2. A Trino-style JMH benchmark in test sources measures immutable cache hit,
   miss/bypass, serialization, and deserialization costs for representative
   block payload sizes. It follows Trino 475 benchmark annotations and is not
   part of normal unit-test timing assertions.

No latency acceptance threshold is placed in a flaky integration test. The
committed benchmark command and observed local result are recorded before M3
is declared complete.

## Implementation steps

1. Add cache/finality models, canonicalization, configuration validation, and
   focused unit tests without changing the default execution path.
2. Add the bounded Trino cache store and scoped/global cache metrics; verify
   disabled mode and cache corruption fallback.
3. Integrate cache lookup/admission with M2 single-flight and cancellation.
4. Add EVM finality resolution and finalized number-to-hash references, then
   migrate blocks and full-block transaction scans to immutable hash entries.
5. Add bounded transaction-hash equality/IN pushdown and cache only finalized
   `eth_getTransactionByHash` results.
6. Add deterministic finalized reuse, unsupported-finality, malformed-boundary,
   block/transaction reorg, failure non-admission, and distributed worker-local
   tests.
7. Expose PageSource metrics, validate lifecycle cleanup, and extend the plugin
   ZIP classloader execution test with cache enabled.
8. Add benchmark coverage, update operator/developer documentation, and run the
   complete validation matrix.

Each step must keep `web3.cache.enabled=false` behavior passing before the next
step begins.

## Validation commands

```bash
mvn validate
mvn -pl trino-web3-runtime test
mvn -pl trino-web3-evm -am test
mvn -pl trino-web3-testing -am test
mvn verify
git diff --check
```

Targeted tests will include cache canonicalization/bounds, single-flight and
cancellation, finality classification, block and transaction reorg, transaction
hash pushdown, error non-admission, connector metrics/configuration, distributed
worker locality, and packaged-plugin loading.
The benchmark command is documented in `docs/TESTING.md` and runs separately
from `mvn verify`.

An implementation smoke run on Java 23.0.2 with one 100 ms warmup and one
100 ms measurement iteration produced the following non-release figures. They
prove that the benchmark is runnable; they are not stable performance claims:

| Operation | 1 KiB | 64 KiB |
| --- | ---: | ---: |
| cache hit (including fresh JSON decode) | 665 ns/op | 58,859 ns/op |
| cache miss | 4.1 ns/op | 4.0 ns/op |
| serialization/admission attempt | 2,268 ns/op | 81,750 ns/op |

## Completion criteria

- All M0-M2 behavior remains compatible with cache disabled.
- Repeated finalized-range queries return identical rows with no remote data or
  finality operation while the finality snapshot is warm.
- A near-head A-to-B reorg returns B and never follows a stale number mapping.
- Cache memory and entry size are bounded; disabled behavior is explicit.
- Cache admission occurs only after complete adapter validation.
- Every cacheable failure class is proven not to be admitted.
- Cancellation and runtime shutdown release queued/in-flight work and cache
  ownership without a per-query executor.
- Metrics are bounded-cardinality and execution scopes remain isolated.
- Benchmark evidence is recorded.
- Maven validation, relevant module tests, connector integration tests, and
  full `mvn verify` pass without a paid provider or secret.

## Primary references

- [Trino 475 `EvictableCacheBuilder`](https://github.com/trinodb/trino/blob/475/lib/trino-cache/src/main/java/io/trino/cache/EvictableCacheBuilder.java)
- [Trino 475 memory filesystem cache](https://github.com/trinodb/trino/blob/475/lib/trino-filesystem/src/main/java/io/trino/filesystem/memory/MemoryFileSystemCache.java)
- [Trino 475 memory cache configuration](https://github.com/trinodb/trino/blob/475/lib/trino-filesystem/src/main/java/io/trino/filesystem/memory/MemoryFileSystemCacheConfig.java)
- [Ethereum execution API: `eth_getBlockByNumber`](https://ethereum.github.io/execution-apis/api/methods/eth_getBlockByNumber/)
