# trino-web3 Implementation Planning Guide

## 1. Purpose

`PLANS.md` defines how implementation work should be planned, reviewed, executed, and validated in the `trino-web3` repository.

This file is not a product roadmap.

Use:

* `ROADMAP.md` to understand **what** should be built.
* `ARCHITECTURE.md` to understand **how the system is structured**.
* `AGENTS.md` to understand **which rules must never be violated**.
* `PLANS.md` to understand **how an implementation task should be carried out**.

The goal is to keep Codex and human contributors from making large, unstructured changes that accidentally violate architecture, correctness, or Trino conventions.

---

# 2. When an implementation plan is required

Write a plan before modifying code when a task:

* spans multiple modules
* modifies Trino SPI behavior
* changes split planning
* changes predicate or projection pushdown
* changes RPC execution behavior
* changes retry or rate limiting
* changes cache semantics
* changes finality or reorg handling
* introduces a new provider
* introduces a new chain
* changes configuration contracts
* changes public interfaces
* affects concurrency or memory ownership
* affects cancellation behavior
* adds a major dependency
* adds experimental planner behavior
* requires significant migration or compatibility work

A plan is usually unnecessary for:

* typo fixes
* documentation-only changes
* isolated test additions
* trivial refactoring with no behavioral change
* obvious local bug fixes with minimal blast radius

When uncertain, prefer writing a short plan.

---

# 3. Planning principles

Plans must optimize for:

```text
correctness
→ architecture
→ operability
→ testability
→ performance
→ implementation speed
```

Do not optimize for implementation speed at the cost of correctness or architecture.

Prefer vertical slices over broad scaffolding.

Prefer:

```text
one working table
→ one working split path
→ one working RPC path
→ tests
```

over:

```text
all interfaces
→ all chain abstractions
→ all provider abstractions
→ no end-to-end query
```

Do not introduce abstractions solely because they may be useful later.

Every abstraction should solve a current or near-term concrete problem.

---

# 4. Required context before planning

Before writing a plan, inspect the repository.

At minimum:

1. read `AGENTS.md`
2. read `ARCHITECTURE.md`
3. read the relevant milestone in `ROADMAP.md`
4. read module-specific documentation
5. inspect the existing implementation
6. inspect nearby tests
7. identify relevant Trino SPI contracts

For Trino SPI changes, additionally read:

```text
docs/TRINO_STYLE_GUIDE.md
docs/CONNECTOR_SPI.md
```

For RPC runtime changes:

```text
docs/RPC_RUNTIME.md
```

For cache, block identity, or finality changes:

```text
docs/CACHE_AND_FINALITY.md
```

For chain-specific changes:

```text
docs/CHAIN_MODEL.md
```

Do not design against assumptions when the current implementation can be inspected.

---

# 5. Standard implementation plan format

Use the following structure for substantial changes.

```md
# Plan: <short task name>

## Goal

What user-visible or system behavior should exist after this change?

## Scope

What is included?

## Non-goals

What is explicitly excluded?

## Current state

How does the relevant code work today?

## Proposed design

What should change?

## Affected modules

Which modules and key classes will be modified?

## Execution flow

How does data/control move through the changed system?

## Correctness considerations

What invariants must remain true?

## Failure modes

What can fail and how should failure be handled?

## Concurrency and cancellation

Does the change affect worker threads, queues, asynchronous work, or cancellation?

## Resource bounds

What bounds memory, concurrency, request count, queue size, or scan size?

## Compatibility

Does this change affect Trino version compatibility, configuration, public API, or stored data?

## Testing

What unit, integration, distributed, or fault tests are required?

## Observability

Which metrics, logs, or system-table fields are required?

## Implementation steps

Ordered list of small implementation steps.

## Validation

Exact commands and scenarios required before completion.

## Documentation

Which documents must be updated?
```

Not every section needs to be long.

For small changes, one or two sentences per section are sufficient.

---

# 6. Goal definition

The goal must describe behavior, not code structure.

Good:

```text
Allow bounded Ethereum block-range queries to execute as Trino splits
and return block rows through a mock RPC endpoint.
```

Bad:

```text
Create BlockManager, RpcHelper, BlockService, and BlockFactory.
```

Class names are implementation choices.

The goal should remain valid even if the implementation changes.

---

# 7. Scope and non-goals

Every significant plan must define non-goals.

Example:

```text
Scope

- ethereum.blocks
- block_number range pushdown
- bounded splits
- batched eth_getBlockByNumber
- deterministic integration tests

Non-goals

- transactions table
- Solana
- disk cache
- adaptive batching
- provider failover
```

This prevents milestone creep.

Do not silently implement adjacent roadmap items because they appear convenient.

---

# 8. Current-state inspection

Before proposing changes, identify:

* existing modules
* relevant interfaces
* dependency direction
* existing tests
* existing configuration
* incomplete scaffolding
* nearby conventions

Plans must not assume an empty repository once implementation has started.

Prefer extending existing patterns unless those patterns violate documented architecture.

---

# 9. Architecture check

Every plan must explicitly confirm the relevant architectural boundaries.

Check:

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

Ask:

* Is chain-specific logic entering generic runtime code?
* Is provider-specific behavior entering Trino SPI?
* Is Trino metadata leaking into the RPC runtime?
* Is transport policy leaking into chain adapters?
* Is a new dependency direction being introduced?

If yes, stop and reconsider the design.

If the architecture truly needs to change, create or update an ADR before implementing.

---

# 10. Trino SPI planning

For connector-facing changes, identify which SPI stage owns the behavior.

Typical flow:

```text
getTableHandle()
    ↓
applyFilter()
    ↓
applyProjection()
    ↓
getSplits()
    ↓
createPageSource()
    ↓
Page
```

The plan should identify:

* what executes on the coordinator
* what executes on workers
* what information is stored in table handles
* what information is stored in splits
* what information reaches page sources
* where network I/O occurs

Avoid network-heavy operations in metadata methods.

Large data reads belong in worker execution.

---

# 11. Split planning requirements

Changes affecting scans must answer:

* Is the scan bounded?
* What determines split boundaries?
* Is split generation deterministic?
* Can a single split produce unbounded work?
* Can provider constraints affect split size?
* What happens for missing range predicates?
* What happens if a requested range is too large?

Initial implementations should prefer predictable static splitting.

Adaptive splitting must remain experimental until explicitly enabled.

---

# 12. RPC execution planning

Any RPC-related plan must explicitly specify:

```text
timeout
batch size
concurrency
queue bound
retry limit
rate limit
cancellation
```

Never leave these values conceptually unbounded.

The plan must distinguish:

```text
logical operation
logical batch
wire request
provider request
```

Do not assume these are identical.

For JSON-RPC batches, responses must be matched by request ID.

Do not rely on response order.

---

# 13. Retry planning

Plans involving retries must identify failure classes.

Example:

```text
timeout
→ retryable

connection reset
→ retryable

429
→ retryable after provider delay

5xx
→ conditionally retryable

invalid params
→ not retryable

unsupported RPC method
→ not retryable
```

Retry policies must be bounded.

Consider interaction with:

* Trino task retries
* query retries
* provider failover

Avoid multiplicative retry behavior.

---

# 14. Cancellation planning

Changes introducing remote or asynchronous work must explain cancellation.

Expected lifecycle:

```text
query cancellation
→ task cancellation
→ page source close
→ queued RPC work removed
→ in-flight work cancelled when possible
```

A plan is incomplete if it introduces long-running remote work without defining cancellation semantics.

---

# 15. Concurrency planning

For concurrent code, identify:

* owner of each executor
* executor lifecycle
* queue bounds
* per-worker concurrency
* per-provider concurrency
* synchronization requirements
* immutable vs mutable state
* shutdown behavior

Do not create:

* an executor per query
* an executor per split
* an HTTP client per request
* unbounded thread pools
* unbounded queues

Prefer shared worker-level runtime resources.

---

# 16. Cache planning

Any cache-related plan must define:

* cache key
* value
* ownership
* eviction
* TTL if applicable
* invalidation
* corruption behavior
* reorg behavior
* failure behavior

Explicitly answer:

> Can the cached value become incorrect if the canonical chain changes?

Prefer immutable identifiers such as block hash when possible.

Never treat remote transport failures as cached absence.

---

# 17. Finality and reorg planning

For block-sensitive operations, plans must identify:

* whether data is pending, head, safe, or finalized
* whether block number is sufficient identity
* whether hash resolution is required
* whether cache validation is needed
* what happens during reorganization

Finality policy belongs to the chain adapter.

Generic runtime code must not embed Ethereum-specific finality assumptions.

---

# 18. Multi-chain planning

When introducing a new chain, do not start by generalizing existing EVM classes.

First identify:

* chain-native data model
* remote protocol
* pagination/range model
* finality semantics
* available filtering
* batching capability
* identifier model

Then determine what is genuinely reusable.

Prefer:

```text
shared execution mechanics
+
native chain semantics
```

over:

```text
universal blockchain abstraction
```

---

# 19. Configuration planning

Configuration changes must specify:

* property name
* type
* default
* validation
* scope
* whether secret
* backwards compatibility

Do not add configuration knobs without a clear operational need.

Avoid exposing internal implementation details unnecessarily.

Secrets must never be logged.

---

# 20. Dependency planning

Before introducing a library, answer:

* Is functionality already available through Trino or existing dependencies?
* What is the runtime footprint?
* Does it introduce conflicting transitive dependencies?
* Is it actively maintained?
* Does it create shading or classloader risk?
* Is it necessary in the plugin runtime?

Prefer existing Trino ecosystem dependencies where appropriate.

Do not add libraries for trivial functionality.

---

# 21. Testing plan

Every behavioral change must identify appropriate test layers.

## Unit tests

For:

* parsers
* decoders
* policies
* range calculations
* retry classification
* provider capability logic

## Integration tests

For:

* connector metadata
* split generation
* RPC request generation
* page decoding
* predicate pushdown

## Distributed tests

For:

* worker execution
* multiple splits
* cancellation
* distributed scan behavior

## Fault injection

For:

* timeout
* `429`
* `Retry-After`
* malformed payload
* partial batch failure
* connection failure
* provider outage
* cache corruption
* reorg

Tests must be deterministic.

CI must not depend on public blockchain infrastructure.

---

# 22. Test-first expectations

For bug fixes, prefer:

```text
reproduce failure
→ add regression test
→ implement fix
→ prove regression test passes
```

For new behavior:

```text
define expected contract
→ write focused tests
→ implement minimal behavior
→ expand integration coverage
```

Do not create large test suites before the behavioral contract is understood.

---

# 23. Mock server requirements

Remote execution plans should normally include deterministic mock behavior.

The mock RPC infrastructure should be reusable.

Avoid one-off handwritten mocks when behavior can be represented by the shared mock server.

The mock server should eventually support:

```text
normal response
batch response
out-of-order batch response
429
Retry-After
timeout
delayed response
malformed response
partial failure
disconnect
provider recovery
reorg
```

---

# 24. Observability planning

Operationally significant features should define metrics before implementation.

Ask:

* How will we know this works in production?
* How will we know it is failing?
* How will we know it is throttled?
* How will we know cache is helping?
* How will we know a provider is unhealthy?

Prefer bounded-cardinality metrics.

Do not use raw:

* block hashes
* transaction hashes
* addresses
* query IDs

as metric labels unless explicitly justified.

---

# 25. Performance planning

Do not claim performance improvement without measurement.

Performance-sensitive changes should define:

* baseline
* workload
* metrics
* expected improvement
* acceptable regression

Relevant metrics include:

```text
query latency
throughput
RPC requests
batch size
bytes transferred
CPU
memory
cache hit ratio
429 count
provider cost
```

Avoid micro-optimizing cold paths before end-to-end profiling.

---

# 26. Compatibility planning

Any change touching Trino SPI must consider supported Trino versions.

Record:

* current supported version
* whether SPI changed
* whether compatibility code is required
* whether build matrix must change

Do not silently broaden or narrow Trino compatibility.

Configuration or API-breaking changes should be documented explicitly.

---

# 27. Security planning

Before completing a plan involving provider connectivity, verify:

* credentials are externally configured
* credentials are not serialized into handles or splits unnecessarily
* URLs are sanitized before logging
* errors do not expose tokens
* test fixtures contain no real secrets

The first production releases should remain read-only.

Transaction submission and side-effecting blockchain operations require a separate architecture review.

---

# 28. Implementation step design

Implementation steps should be small enough to validate incrementally.

Good:

```text
1. Add immutable BlockRange model.
2. Add blocks table metadata.
3. Add block_number constraint extraction.
4. Add deterministic split generation.
5. Add one-block mock RPC response.
6. Add page source decoding.
7. Add distributed integration test.
8. Add batching.
```

Bad:

```text
1. Build complete EVM connector.
```

Each step should leave the repository in a coherent state where possible.

---

# 29. Vertical-slice rule

Prefer a narrow end-to-end implementation before breadth.

For example, the first useful path is:

```sql
SELECT
    block_number,
    block_hash
FROM web3.ethereum.blocks
WHERE block_number BETWEEN 100 AND 110;
```

That path should exercise:

```text
metadata
→ pushdown
→ split
→ RPC
→ decode
→ Page
```

before implementing:

* every EVM table
* every provider
* Solana
* Aptos
* adaptive planning

---

# 30. Refactoring policy

Refactoring must have a concrete reason.

Valid reasons include:

* duplication across proven implementations
* architecture violation
* difficult testing
* lifecycle bug
* measurable performance issue
* new requirement incompatible with current design

Do not refactor merely to create more generic abstractions.

Prefer deleting abstractions that no longer provide value.

---

# 31. ADR requirement

Create an ADR under:

```text
docs/DECISIONS/
```

when changing:

* module boundaries
* dependency direction
* cache identity model
* split strategy
* provider coordination model
* supported persistence model
* public chain abstraction
* experimental vs production boundary

Suggested filename:

```text
0005-<decision-name>.md
```

ADR structure:

```md
# <Decision title>

## Status

Proposed / Accepted / Superseded

## Context

Why is a decision required?

## Decision

What are we choosing?

## Alternatives considered

What else was considered?

## Consequences

What becomes easier or harder?
```

---

# 32. Research feature planning

Research features require stronger isolation.

Examples:

* cost-aware planning
* adaptive concurrency
* adaptive batching
* workload-driven materialization
* automatic provider selection

Plans must specify:

* feature flag
* stable default behavior
* experimental behavior
* benchmark baseline
* metrics
* rollback path

Production defaults must remain conservative.

Research experimentation must never silently alter correctness semantics.

---

# 33. Validation requirements

Before a task is complete, run the relevant repository checks.

At minimum:

```bash
./mvnw validate
```

and relevant tests.

For substantial implementation work:

```bash
./mvnw test
```

or the narrowest reliable Maven module/test command covering the change.

For connector behavior, run the relevant integration tests.

For changes to distributed execution, run distributed tests.

For performance changes, run the defined benchmark.

Do not report successful validation unless it actually ran.

---

# 34. Failed validation

If validation fails:

1. determine whether failure was introduced by the change
2. fix introduced regressions
3. rerun the failed validation
4. do not hide unrelated pre-existing failures

If a failure is demonstrably pre-existing, record:

```text
command
failure
why it is believed to be pre-existing
```

Do not modify unrelated code merely to produce a green build unless required.

---

# 35. Completion report

After a substantial implementation task, summarize:

```text
Implemented
- ...

Tests
- ...

Validation
- ...

Documentation
- ...

Known limitations
- ...
```

Keep the report factual.

Do not claim:

* production readiness
* performance improvement
* compatibility
* failure safety

unless the corresponding validation exists.

---

# 36. Example plan — M0 connector bootstrap

```md
# Plan: Bootstrap Web3 connector

## Goal

Create a minimal Trino plugin that loads a `web3` catalog and supports
basic schema discovery without blockchain access.

## Scope

- Maven modules
- Web3Plugin
- Web3ConnectorFactory
- minimal Connector implementation
- basic configuration
- integration test

## Non-goals

- Ethereum RPC
- tables
- batching
- cache

## Affected modules

- trino-web3-plugin
- trino-web3-testing

## Correctness considerations

Plugin must follow Trino connector lifecycle and release all resources.

## Testing

Start Trino test server, install plugin, verify catalog loads.

## Implementation steps

1. establish parent Maven build
2. create plugin module
3. implement plugin registration
4. implement connector factory
5. implement minimal connector
6. add test catalog
7. verify SHOW SCHEMAS

## Validation

- ./mvnw validate
- plugin integration test
```

---

# 37. Example plan — Ethereum blocks vertical slice

```md
# Plan: Ethereum blocks bounded scan

## Goal

Execute a bounded block-number range query against a deterministic
Ethereum RPC mock and return Trino rows.

## Scope

- ethereum.blocks
- block_number predicate pushdown
- static bounded splits
- eth_getBlockByNumber
- page decoding

## Non-goals

- transactions
- logs
- caching
- provider failover
- adaptive split sizing

## Execution flow

SQL
→ applyFilter
→ block range in table handle
→ split manager
→ bounded block splits
→ page source
→ EVM adapter
→ runtime
→ mock RPC
→ decoder
→ Page

## Correctness

- no unbounded scans
- response matched to requested block
- block number/hash mapping deterministic
- cancellation propagates

## Failure modes

- timeout
- missing block
- malformed block
- RPC error

## Tests

- range pushdown
- split boundaries
- normal RPC response
- malformed response
- cancellation
- distributed query

## Validation

- module unit tests
- connector integration test
- Maven validation
```

---

# 38. Immediate planning workflow

The original bootstrap sequence was:

```text
M0 plan
    ↓
M0 implementation
    ↓
M0 validation
    ↓
M1 ethereum.blocks plan
    ↓
first vertical slice
    ↓
M1 hardening
```

Implementation planning stops at the current M5 release boundary. Future
research work is intentionally outside this repository snapshot.

Implementation plans should describe near-term reality.

---

# 39. Core rule

Before making a substantial change, answer four questions:

```text
1. Where does this responsibility belong?
2. What bounds the work?
3. How does it fail and cancel?
4. How will we prove it works?
```

If any of these answers are unclear, the task is not ready for implementation.
