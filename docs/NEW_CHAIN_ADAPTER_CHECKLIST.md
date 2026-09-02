# New chain adapter checklist

Use this checklist before registering a new chain such as Sui, Near, or
another chain in the connector. It is an entry gate for executable production
support, not a template for metadata-only tables.

## Native contract

- Define native schemas, tables, column types, identifiers, and nullability.
- Do not reuse EVM tables for UTXO, object, receipt, event, instruction, or
  account models.
- Version the descriptor, adapter, and changed tables. Preserve existing chain,
  schema, and table identities.
- Keep endpoints, credentials, provider headers, retry/rate policy, finality,
  and scripts out of the descriptor.

## Executable bounded path

- Implement `ExecutableChainAdapter`; `DeclarativeChainAdapter` is never
  eligible for the connector's executable registry.
- Define every accepted predicate and a finite split limit. Reject unbounded or
  unsupported scans before remote work is scheduled.
- Map each split to bounded remote operations through the shared runtime.
- Decode and validate native responses in the adapter. Treat partial or
  malformed results explicitly; never publish partially decoded rows.
- Reuse connector-owned clients and runtime policy. Do not create per-query
  executors, transports, retry loops, provider selection, or rate limiters.

## Correctness and lifecycle

- Define pending, head, safe, finalized, and reorganization semantics where
  the chain exposes them.
- Define immutable cache identity before enabling cache admission. Do not cache
  transport failures, malformed responses, or semantically ambiguous absence.
- Preserve PageSource cancellation through queued, in-flight, and decoded work.
- Ensure metrics have no endpoint, credential, request ID, or chain-data value
  dimensions.

## Tests and release gate

- Add strict descriptor, split-planning, decoder, and failure-mode unit tests.
- Add deterministic local protocol mocks; tests must not require a paid or
  public provider.
- Add Trino metadata, bounded SQL execution, cancellation, and missing-endpoint
  tests appropriate to the chain protocol.
- Prove the assembled plugin ZIP contains the adapter and loads it through the
  Trino plugin classloader.
- Run `mvn validate`, the affected reactor tests, `mvn clean verify`, and
  `git diff --check`.

Only after every applicable item is complete may the adapter be added to the
connector-lifetime executable registry. Operator-supplied descriptor packs and
metadata-only registration remain out of scope.
