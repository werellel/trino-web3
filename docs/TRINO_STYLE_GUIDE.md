# Trino Style Guide for trino-web3

## 1. Purpose

This document defines coding conventions for `trino-web3`.

The goal is not to invent a separate house style.

`trino-web3` should look and behave like a well-maintained Trino connector:

* familiar to Trino contributors
* explicit about ownership and lifecycle
* conservative about concurrency
* strict about resource bounds
* easy to test
* easy to review
* resistant to accidental abstraction growth

When this document conflicts with the coding conventions used by the pinned Trino version, follow the Trino convention unless doing so violates a documented architectural decision.

---

# 2. General principles

Prefer code that is:

```text
explicit
immutable
bounded
testable
cancellation-aware
small
predictable
```

Avoid code that is:

```text
clever
implicitly stateful
unbounded
reflection-heavy
framework-heavy
difficult to cancel
difficult to test
```

Prefer boring infrastructure code over sophisticated infrastructure code.

For connector code, operational predictability matters more than reducing a few lines of implementation.

---

# 3. Java version and language features

Use the Java version required by the supported Trino release.

Do not independently upgrade the Java language level without updating the Trino compatibility policy.

Modern Java features are encouraged when they simplify the code without obscuring behavior.

Appropriate uses may include:

* records for immutable value objects
* pattern matching where supported
* switch expressions
* local variable type inference when the type remains obvious

Avoid using new language features merely for novelty.

---

# 4. Formatting

Follow the formatting conventions used by the Trino codebase.

Do not manually introduce a competing formatter configuration unless necessary.

General expectations:

* four-space indentation
* no tabs
* braces for control-flow bodies
* one top-level public type per file
* imports grouped consistently
* no wildcard imports
* remove unused imports
* avoid excessively long methods
* keep related expressions readable rather than maximizing line density

Formatting disputes should be resolved by repository validation tooling rather than personal preference.

---

# 5. Naming

Names should express domain meaning.

Prefer:

```java
BlockRange
RemoteOperation
ProviderCapabilities
EthereumBlock
Web3Split
RetryPolicy
```

Avoid:

```java
Manager
Helper
Util
Handler
Processor
Thing
Data
Common
Misc
```

unless the name accurately describes a recognized responsibility.

Names such as `RpcManager` or `Web3Utils` usually indicate that responsibilities have not been separated clearly enough.

---

# 6. Class naming

Use role-oriented suffixes only when the role is meaningful.

Examples:

```text
...Factory
...Provider
...Decoder
...Planner
...Policy
...Adapter
...Handle
...Split
...Config
...Module
```

Examples:

```java
Web3ConnectorFactory
EthereumChainAdapter
RpcBatchPlanner
ProviderRetryPolicy
EthereumBlockDecoder
Web3TableHandle
Web3Split
Web3Config
```

Do not create interfaces and implementations with meaningless names such as:

```text
Foo
FooImpl
```

Prefer names that distinguish behavior.

Example:

```text
ProviderSelector
PrimaryFirstProviderSelector
LatencyAwareProviderSelector
```

---

# 7. Method naming

Methods should describe behavior rather than implementation mechanics.

Prefer:

```java
createSplits(...)
planScan(...)
decodeBlock(...)
selectProvider(...)
classifyFailure(...)
isFinalized(...)
```

Avoid:

```java
process(...)
handle(...)
executeLogic(...)
doStuff(...)
perform(...)
```

unless the broader verb is genuinely appropriate.

Boolean methods should usually read naturally:

```java
isFinalized()
isRetryable()
supportsBatching()
hasBlockRange()
```

---

# 8. Immutability

Prefer immutable objects by default.

Value objects such as:

```text
BlockRange
ProviderCapabilities
RemoteOperation
RemoteScan
ChainId
```

should normally be immutable.

Use constructors or static factories to establish valid state completely.

Avoid:

```java
object.setX(...)
object.setY(...)
object.setZ(...)
```

for domain objects.

Mutation should normally be limited to components that inherently maintain runtime state, such as:

* rate limiters
* caches
* provider health trackers
* metrics
* queues

Even there, ownership must be explicit.

---

# 9. Records

Records are appropriate for small immutable value objects when they provide clear semantics.

Example:

```java
public record BlockRange(long startInclusive, long endInclusive)
{
    public BlockRange
    {
        checkArgument(startInclusive >= 0, "startInclusive is negative");
        checkArgument(endInclusive >= startInclusive, "Invalid block range");
    }
}
```

Do not use records merely to avoid writing classes when:

* substantial behavior is required
* inheritance or lifecycle semantics matter
* representation should remain hidden
* framework compatibility requires a normal class

---

# 10. Null handling

Avoid `null` as part of public internal contracts.

Prefer:

* required constructor parameters
* `Optional<T>` for meaningful absence
* empty immutable collections for zero elements

Do not use `Optional` for every field automatically.

Use it when absence is part of the domain.

Good:

```java
Optional<Hash> blockHash
Optional<Duration> retryAfter
```

Potentially unnecessary:

```java
Optional<List<String>>
```

Prefer:

```java
List<String>
```

with an empty list.

---

# 11. Optional usage

`Optional` should represent semantic absence, not defensive uncertainty.

Prefer:

```java
Optional<Provider> fallbackProvider()
```

Avoid passing `Optional` deep through many layers if the caller can resolve the decision earlier.

Do not use:

```java
Optional.get()
```

without proving presence locally.

Prefer:

```java
orElseThrow(...)
map(...)
flatMap(...)
ifPresent(...)
```

when they improve readability.

Do not construct an `Optional` only to immediately unwrap it.

---

# 12. Collections

Prefer immutable collections for configuration and domain state.

Do not expose mutable internal collections.

Prefer:

```java
List.copyOf(...)
Map.copyOf(...)
Set.copyOf(...)
```

where ownership should be fixed.

Use mutable collections locally when building results if that is clearer or more efficient.

Example:

```java
ImmutableList.Builder<Web3Split> splits = ImmutableList.builder();
```

or an equivalent standard Java collection followed by an immutable copy.

Do not mutate caller-owned collections.

---

# 13. Preconditions and state validation

Validate inputs close to their ownership boundary.

Use argument validation for invalid caller input.

Use state validation for invalid object state.

Examples:

```java
checkArgument(startBlock >= 0, "startBlock is negative");
checkState(!closed, "Runtime is already closed");
```

Internal invariants may use verification-style checks where appropriate.

Error messages should include enough context to diagnose the failure without leaking secrets.

Bad:

```java
checkArgument(valid);
```

Better:

```java
checkArgument(
        endBlock >= startBlock,
        "endBlock (%s) is smaller than startBlock (%s)",
        endBlock,
        startBlock);
```

---

# 14. Constructors

Constructors should establish valid objects.

Avoid partially initialized objects.

Required dependencies should normally be constructor parameters.

Example:

```java
public RpcRuntime(
        HttpClient httpClient,
        ProviderSelector providerSelector,
        RetryPolicy retryPolicy,
        RpcMetrics metrics)
```

Avoid hidden dependency lookup through static global access.

---

# 15. Dependency injection

Use the same dependency-injection style as the rest of the connector.

Prefer constructor injection.

Avoid:

* field injection
* service locators
* mutable global registries
* static singleton state

Dependencies should be visible from constructors so ownership and tests remain understandable.

---

# 16. Configuration classes

Configuration should be represented by dedicated configuration classes rather than reading properties throughout the codebase.

Example:

```java
public class RpcConfig
{
    private Duration requestTimeout = new Duration(10, SECONDS);
    private int maxConcurrency = 16;
    private int maxBatchSize = 100;

    ...
}
```

Configuration classes should:

* have sensible conservative defaults
* validate bounds
* document units
* avoid embedding credentials into `toString()`
* expose behavior-independent values

Do not make every internal constant configurable.

Only expose configuration that users have a realistic operational reason to tune.

---

# 17. Configuration defaults

Defaults should optimize for safety.

Prefer:

```text
bounded concurrency
bounded batch size
finite timeout
bounded retry count
```

Never default to:

```text
unlimited concurrency
unlimited queue
infinite timeout
unbounded scan
infinite retry
```

---

# 18. Static state

Avoid mutable static state.

Do not use static fields for:

* HTTP clients
* caches
* provider health
* executors
* rate limiters
* configuration
* metrics

Constants are appropriate:

```java
private static final int DEFAULT_MAX_BATCH_SIZE = 100;
```

Runtime state should have explicit lifecycle ownership.

---

# 19. Utility classes

Avoid large generic utility classes.

Bad:

```text
Web3Utils
RpcUtils
JsonUtils
CommonUtils
```

Prefer putting behavior with its domain owner.

Examples:

```text
EthereumBlockDecoder
RpcRequestEncoder
ProviderErrorClassifier
BlockRangeSplitter
```

A small utility class is acceptable only when the operations are truly stateless and cohesive.

---

# 20. Trino handles

Trino handle types should contain only the information required to represent connector planning state.

Examples:

```text
Web3TableHandle
Web3ColumnHandle
Web3Split
```

Handles should generally be:

* immutable
* serializable as required by Trino
* independent of runtime-only resources

Never put into handles:

* HTTP clients
* caches
* provider clients
* executors
* open streams
* live cancellation handles

Prefer stable logical identifiers.

---

# 21. TableHandle design

A table handle may evolve as pushdown occurs.

Example:

```text
initial handle
    ↓
applyFilter
    ↓
handle with constrained BlockRange
    ↓
applyProjection
    ↓
handle with projected columns
```

Prefer creating a new immutable handle rather than mutating the existing one.

Planning state must remain deterministic.

---

# 22. ColumnHandle design

Column handles should describe columns, not runtime behavior.

Potential fields:

```text
name
Trino type
native chain field
ordinal
```

Avoid embedding decoder implementations or RPC clients into column handles.

---

# 23. Split design

A split represents bounded worker work.

A split must not mean:

```text
scan all remaining Ethereum history
```

Prefer:

```text
blocks 23,000,000 through 23,000,499
```

A split should contain only enough information for a worker to reconstruct its bounded remote scan.

Do not make split objects unnecessarily large.

---

# 24. Coordinator vs worker responsibilities

Keep coordinator work lightweight.

Coordinator-side code may:

* resolve metadata
* analyze supported predicates
* construct handles
* generate splits

Worker-side code should perform:

* remote RPC calls
* decoding
* page generation
* runtime caching

Avoid large blockchain network reads from:

```text
listSchemaNames
listTables
getTableMetadata
applyFilter
getSplits
```

unless a specific bounded metadata lookup is unavoidable and documented.

---

# 25. Metadata methods

Metadata methods should be deterministic and inexpensive where possible.

Do not use metadata discovery as an excuse to crawl remote blockchain state.

Example:

```sql
SHOW TABLES FROM web3.ethereum;
```

should not cause calls across millions of blocks.

Tables should normally come from configured chain adapters.

---

# 26. Predicate pushdown

Pushdown code must be conservative.

Only claim a constraint has been enforced remotely when it actually has been enforced.

If the connector cannot fully enforce a predicate, leave the remaining predicate for Trino.

Do not silently drop unsupported predicates.

Prefer explicit code for supported predicate classes over generic expression interpretation during early milestones.

---

# 27. Projection pushdown

Projection pushdown should reduce unnecessary remote work or decoding.

Do not complicate the implementation merely to avoid decoding a few trivial scalar fields.

Prioritize projections that avoid significant work, such as:

* nested transaction objects
* receipt fetching
* trace fetching
* large instruction payloads

Correctness comes before projection efficiency.

---

# 28. Exceptions

Do not expose raw provider or transport exceptions directly through the connector.

Normalize errors into connector-level categories.

Examples:

```text
CONFIGURATION_ERROR
REMOTE_THROTTLED
REMOTE_UNAVAILABLE
REMOTE_INVALID_RESPONSE
UNSUPPORTED_OPERATION
```

Exceptions should preserve useful causes.

Example:

```java
throw new TrinoException(
        WEB3_REMOTE_UNAVAILABLE,
        "Ethereum provider request failed",
        cause);
```

Do not include API keys or credential-bearing URLs in exception messages.

---

# 29. Exception messages

Exception messages should be actionable.

Bad:

```text
RPC failed
```

Better:

```text
Ethereum eth_getBlockByNumber failed after 3 attempts for provider 'primary'
```

Avoid dumping complete remote payloads into error messages.

Large or sensitive responses should be summarized.

---

# 30. Retry code

Retry logic belongs in the RPC runtime.

Do not scatter retry loops throughout:

* page sources
* chain adapters
* decoders
* provider implementations

Callers should issue one logical operation.

The runtime decides whether execution requires multiple physical attempts.

Retry policy must remain bounded and observable.

---

# 31. Threading

Thread creation must be centralized.

Never create:

* thread pools per query
* executors per split
* raw threads per RPC request

Prefer worker-scoped shared execution resources.

Executor ownership must have a defined shutdown lifecycle.

Thread pools must have bounded concurrency.

Queues must have bounded capacity or explicit backpressure.

---

# 32. Async code

Use asynchronous execution only when it improves remote I/O concurrency or cancellation behavior.

Do not convert straightforward code to asynchronous code for style reasons.

Async chains should remain readable.

Avoid deeply nested completion callbacks.

Separate:

```text
request planning
scheduling
transport
response decoding
```

rather than combining all stages into one callback chain.

---

# 33. Blocking

Do not block coordinator threads on bulk remote operations.

Worker-side blocking must be deliberate and compatible with Trino execution expectations.

When using asynchronous transport, do not immediately turn every call back into synchronous blocking without understanding the lifecycle implications.

Document unavoidable blocking boundaries.

---

# 34. HTTP clients

Reuse HTTP clients.

Do not create a new HTTP client:

* per request
* per batch
* per split
* per query

HTTP connection pooling should be worker/runtime scoped.

Transport configuration should be centralized.

---

# 35. Timeouts

Every remote operation must ultimately have a finite timeout.

Timeout ownership should be obvious.

Avoid multiple unrelated timeout layers that make effective behavior impossible to reason about.

If there are:

```text
connect timeout
request timeout
query cancellation
retry budget
```

their interaction should be documented.

---

# 36. Rate limiting

Rate limiting belongs in the shared runtime.

Do not implement provider quota control inside EVM or Solana table classes.

Rate limiters should have explicit ownership and scope.

Examples:

```text
per worker
per provider
per provider + operation class
```

Do not claim global distributed quota enforcement if only worker-local enforcement exists.

---

# 37. Batch processing

Batching should have clear limits for:

* maximum items
* maximum estimated bytes
* maximum wait time if batching by time
* provider capability

Never assume provider responses are ordered like requests.

For JSON-RPC:

```text
response.id
```

is authoritative.

Response array position is not.

---

# 38. JSON handling

Keep transport parsing separate from domain decoding.

Prefer:

```text
wire response
    ↓
validated RPC envelope
    ↓
chain-specific decoder
    ↓
domain/row values
```

Avoid passing unvalidated generic JSON trees throughout the connector.

Do not eagerly deserialize large fields that are not required by the projection when practical.

---

# 39. Domain types

Use dedicated domain types when primitive values could be confused.

Potential examples:

```text
BlockNumber
ChainId
ProviderId
RpcRequestId
```

Do not create wrapper types for every primitive automatically.

Introduce a domain type when it prevents realistic mistakes or improves API clarity.

---

# 40. Blockchain numeric types

Never assume blockchain numeric values fit into Java `int`.

Block numbers, balances, amounts, gas quantities, slot numbers, and ledger versions require explicit range analysis.

When converting remote numeric data into Trino types:

* detect overflow
* fail explicitly
* document mappings

Do not silently truncate.

---

# 41. Addresses and hashes

Do not normalize addresses, hashes, or identifiers without chain-specific rules.

EVM, Solana, and Aptos identifiers have different semantics.

Normalization belongs in chain-specific code.

Generic runtime code should normally treat identifiers as opaque values.

---

# 42. Logging

Logs should describe operations, not flood per-row details.

Good candidates:

* provider state transitions
* retry exhaustion
* failover
* severe throttling
* runtime initialization
* cache corruption
* configuration problems

Avoid logging every successful RPC call at normal log levels.

Use metrics for high-volume operational data.

---

# 43. Logging secrets

Never log:

* API tokens
* authorization headers
* raw credential-bearing provider URLs

Provider endpoint logging must sanitize credentials.

Do not depend on contributors remembering to redact secrets manually at every logging site.

Prefer centralized sanitization.

---

# 44. Metrics

Metric labels must have bounded cardinality.

Appropriate labels may include:

```text
chain
provider-name
RPC operation class
result category
```

Avoid labels such as:

```text
transaction hash
block hash
address
query ID
raw URL
error message
```

High-cardinality values belong in traces or debug logs when safe.

---

# 45. Resource lifecycle

Objects owning resources should make ownership explicit.

Examples:

* executors
* HTTP clients
* disk caches
* scheduled tasks

If a class owns something that requires shutdown, the lifecycle should be visible through the connector/runtime lifecycle.

Avoid resources whose cleanup depends on garbage collection.

---

# 46. Close semantics

`close()` should be:

* safe
* bounded
* idempotent where practical

Closing a page source should cancel outstanding work owned by that page source.

Closing the connector/runtime should stop shared background work.

Do not let cancelled queries continue consuming RPC quota.

---

# 47. Cache code

Cache access should be encapsulated.

Callers should not need to know whether a result came from:

```text
memory
disk
remote
```

unless the execution planner explicitly needs that information.

Cache values should carry sufficient identity to protect correctness.

Do not hide finality assumptions inside a generic cache implementation.

---

# 48. Dependency direction

Never import chain-specific modules into generic runtime code.

Forbidden examples:

```text
trino-web3-runtime → trino-web3-evm
trino-web3-runtime → trino-web3-solana
```

Likewise, runtime code should not depend on Trino metadata handles when a smaller runtime-domain abstraction can be used.

If dependency direction becomes awkward, reconsider responsibility ownership before introducing callbacks or global registries.

---

# 49. Interface design

Create interfaces at real architectural boundaries.

Good candidates:

```text
ChainAdapter
ProviderSelector
RemoteTransport
CacheStore
```

Avoid interfaces for classes with one implementation merely because mocking may someday be useful.

Tests should not dictate excessive interface creation.

Prefer testing concrete immutable components where practical.

---

# 50. Abstraction policy

The project intentionally rejects speculative abstractions.

Do not begin with:

```text
UniversalBlockchainEntity
UniversalTransaction
UniversalRpcProtocol
UniversalChainObject
```

before multiple chains prove the common concept.

Start from concrete EVM behavior.

Generalize only after another chain exposes a genuinely shared execution mechanic.

---

# 51. Comments

Comments should explain:

* why something is necessary
* non-obvious protocol behavior
* correctness assumptions
* Trino lifecycle constraints
* provider quirks

Do not restate code.

Bad:

```java
// Increment retries
retries++;
```

Good:

```java
// Trino may retry the task independently, so RPC retries are deliberately
// capped here to avoid multiplicative retry storms.
```

---

# 52. TODO comments

TODOs must be actionable.

Prefer:

```java
// TODO: Replace static split sizing after measured provider-aware limits exist.
```

Avoid:

```java
// TODO improve this
```

Do not use TODOs as substitutes for correctness.

If a missing behavior is required for correctness, implement it before merging.

---

# 53. Documentation on public abstractions

Architecturally important interfaces and configuration properties should explain their contract.

Document:

* ownership
* nullability/absence
* thread safety where non-obvious
* failure semantics
* whether operations may block
* whether results are immutable

Avoid excessive Javadoc on trivial getters.

---

# 54. Test naming

Tests should describe behavior.

Prefer:

```text
testCreatesBoundedSplitsForBlockRange
testRejectsUnboundedBlockScan
testRetriesAfterThrottle
testDoesNotRetryInvalidParameters
testMatchesBatchResponseByRequestId
testCancelsInflightRequestWhenPageSourceCloses
```

Avoid:

```text
testSplitManager1
testRpc
testFailure
```

---

# 55. Test structure

Tests should clearly distinguish:

```text
given
when
then
```

without requiring literal comments for every test.

Arrange setup so the behavior under test is obvious.

Prefer focused assertions over huge snapshot comparisons.

---

# 56. Deterministic testing

Tests must not rely on:

* latest Ethereum block
* public RPC availability
* provider latency
* current network state
* external rate limits

Use deterministic fixtures.

For time-dependent runtime behavior, inject a controllable clock or scheduler when necessary.

Avoid real sleeps when deterministic synchronization is possible.

---

# 57. Mocking policy

Prefer real small components plus deterministic fake infrastructure over deep mocking.

For RPC behavior, prefer the shared mock server.

Use mocks when they make an isolated contract test clearer.

Avoid tests that mock every internal method and therefore only test the mock setup.

---

# 58. Production vs experimental code

Experimental features must be obvious in:

* package/module placement
* configuration
* documentation

Examples:

```text
experimental.cost
experimental.materialization
```

Do not let research-only behavior silently become the stable default.

Production code must not depend on experimental code.

Experimental code may depend on stable runtime interfaces.

---

# 59. Package organization

Prefer packages by responsibility or domain, not by arbitrary technical layer explosion.

Example:

```text
io.trino.plugin.web3
io.trino.plugin.web3.metadata
io.trino.plugin.web3.split
io.trino.plugin.web3.evm
io.trino.plugin.web3.runtime
io.trino.plugin.web3.runtime.retry
io.trino.plugin.web3.runtime.cache
io.trino.plugin.web3.testing
```

Avoid deeply nested packages unless they represent meaningful separation.

---

# 60. Package-private visibility

Use the narrowest reasonable visibility.

Prefer package-private classes and methods when they do not form a real cross-package contract.

Do not make classes public solely to make tests easier.

Tests should normally live in an appropriate package when package-level access is justified.

---

# 61. Final classes

Classes not intended for inheritance should generally be final when consistent with surrounding Trino style.

Do not use inheritance as a general code-reuse mechanism.

Prefer composition.

Inheritance is appropriate when the domain contract genuinely models substitutable behavior.

---

# 62. Equality and identity

Value objects used in handles, splits, cache keys, or planning state require intentional equality semantics.

Do not rely on object identity for logical values.

Records are often useful here.

For security-sensitive or large objects, consider whether `toString()` may expose sensitive data.

---

# 63. toString()

`toString()` should help debugging but must not expose secrets.

Never include:

* authorization tokens
* provider API keys
* full sensitive URLs

For objects representing large RPC payloads, avoid dumping the entire payload.

---

# 64. Memory discipline

Assume remote responses may be unexpectedly large.

Avoid:

* unbounded accumulation of pages
* holding all splits in memory unnecessarily
* retaining complete JSON payloads after decoding
* large duplicate byte/string copies

When adding buffering, document its upper bound.

---

# 65. Backpressure

Any producer-consumer structure must define what happens when consumers are slower than producers.

Acceptable behaviors include:

* bounded blocking
* asynchronous backpressure
* rejecting additional work
* reducing scheduling concurrency

Unacceptable:

```text
keep adding requests to an unbounded queue
```

---

# 66. Scan safety

A query without a restrictive predicate must not accidentally trigger a scan of an entire chain history.

Tables with potentially massive history should define explicit behavior for unbounded scans.

Possible policies:

* reject
* require a configured maximum range
* cap automatically and fail clearly

The selected behavior must be documented.

Never silently run an effectively infinite scan.

---

# 67. Error recovery

Recovery behavior must be explicit.

Do not catch broad exceptions and continue unless the behavior is well-defined.

Avoid:

```java
catch (Exception e) {
    return Optional.empty();
}
```

Remote failure is not the same as absence.

Preserve that distinction throughout the codebase.

---

# 68. Catching exceptions

Catch the narrowest useful exception type.

Do not catch `Throwable`.

Avoid broad `Exception` catches unless at a deliberate boundary that classifies and rethrows failures.

Interrupted or cancellation-related failures must preserve cancellation semantics.

---

# 69. Provider-specific behavior

Provider quirks belong behind provider-specific abstractions.

Do not spread code such as:

```java
if (providerName.equals("alchemy")) {
    ...
}
```

through runtime or chain modules.

Prefer:

```java
ProviderProfile
ProviderCapabilities
ProviderErrorClassifier
```

Provider specialization should be data- or capability-driven when practical.

---

# 70. Capability checks

Prefer asking what a provider supports over asking which provider it is.

Prefer:

```java
capabilities.supportsJsonRpcBatch()
```

over:

```java
provider instanceof AlchemyProvider
```

Identity checks are appropriate only for genuinely vendor-specific behavior that cannot be represented as capability.

---

# 71. Version compatibility

Do not introduce compatibility hacks before they are needed.

When supporting multiple Trino versions:

* isolate compatibility code
* test every declared version
* document supported versions
* avoid reflection-based compatibility unless necessary

Prefer a smaller truthful compatibility matrix over broad untested claims.

---

# 72. Build discipline

The repository build should remain reproducible.

Avoid:

* tests requiring manual local services
* hidden environment dependencies
* downloading arbitrary binaries during tests
* relying on user-specific Maven configuration

External integration tests, if any, must be opt-in and clearly separated from deterministic CI tests.

---

# 73. Dependency discipline

Before adding a dependency, verify whether equivalent functionality already exists in:

* the JDK
* Trino dependencies
* existing project dependencies

Avoid dependency growth for trivial helpers.

Special care is required because connector plugins execute inside Trino's plugin/classloader environment.

Dependencies must not casually introduce conflicting versions or large runtime trees.

---

# 74. Changes to architecture

If code cannot be implemented cleanly while following `ARCHITECTURE.md`, do not hide the mismatch with indirection.

Instead:

1. identify the architectural conflict
2. propose an ADR
3. update architecture if the decision is accepted
4. then implement

The documentation should reflect the architecture that actually exists.

---

# 75. Code review questions

Before considering a change ready, ask:

```text
Does this responsibility belong here?

Is all remote work bounded?

Can the work be cancelled?

Are retries bounded?

Can this leak credentials?

Can this create unbounded memory or queue growth?

Does this accidentally perform RPC on the coordinator?

Does this preserve native chain semantics?

Does this add an abstraction without a current need?

Can this be tested without a real RPC provider?

Does failure remain distinguishable from missing data?
```

---

# 76. Recommended style for initial implementations

For an initial vertical slice, bias strongly toward simplicity.

Prefer:

```text
EthereumBlocksTable
EthereumBlockDecoder
BlockRange
Web3Split
SimpleRpcClient
```

over a large hierarchy such as:

```text
AbstractRemoteEntityTable
GenericBlockchainResource
UniversalRpcRequestPlanner
DynamicProtocolExecutionFactory
```

An initial slice should establish one clean vertical path.

Architecture should grow from measured requirements.

---

# 77. Initial adapter code-quality rules

For an initial chain adapter:

* support one bounded block-range query path well
* use immutable handles and splits
* use deterministic split sizes
* keep RPC execution behind the runtime boundary
* use a shared HTTP client
* enforce finite timeout
* support cancellation
* match batch responses by request ID
* use deterministic mock RPC tests
* reject or explicitly handle unbounded scans
* avoid adding provider-specific implementations prematurely

Do not add abstractions merely to make one chain appear generic.

---

# 78. Preferred decision heuristic

When two implementations are both correct, prefer the one that is easier to answer these questions about:

```text
Who owns this object?

Where does this code execute?

What bounds this work?

How does this stop?

How does this fail?

How is it tested?
```

If those answers are obvious from the code, the implementation is likely moving in the right direction.

---

# 79. Definition of Trino-style code for this project

For `trino-web3`, "Trino-style" means more than formatting.

It means:

```text
immutable planning state
explicit SPI ownership
bounded distributed work
lightweight coordinator behavior
worker-side data reads
clear resource lifecycle
strict failure semantics
deterministic testing
minimal abstraction
production-oriented operability
```

Code should feel as though an experienced Trino contributor could open the repository and understand where each responsibility belongs.

---

# 80. Final rule

Do not optimize for writing the most code.

Optimize for maintaining the smallest correct system that satisfies the current scope.

When uncertain:

```text
prefer explicit over clever
prefer bounded over convenient
prefer immutable over shared mutable state
prefer composition over inheritance
prefer capability over provider identity
prefer vertical slices over scaffolding
prefer measured behavior over speculation
```
