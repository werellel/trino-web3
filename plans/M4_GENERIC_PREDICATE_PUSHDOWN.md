# Plan: M4 descriptor-driven predicate pushdown

## Goal

Represent bounded chain-native predicates in Trino table handles without
Ethereum-specific fields, and select a descriptor-declared remote access path
without performing coordinator-side network work.

## Scope

- Store named long ranges and named discrete string values in an immutable
  table handle.
- Carry the selected descriptor method name into the adapter scan.
- Derive candidate access paths from required `SPLIT` and `PREDICATE`
  bindings.
- Prefer a complete path using more constrained bindings, with discrete lookup
  as the deterministic tie-breaker.
- Keep range and discrete-value counts bounded.
- Preserve Ethereum block-range and transaction-hash query behavior.
- Keep chain-specific hash validation and normalization in the EVM adapter.

## Non-goals

- REST transport or a production Aptos adapter.
- Solana or Aptos tables.
- Arbitrary Trino expression interpretation.
- Decimal, binary, array, or nested predicate serialization.
- Projection pushdown or adaptive access-path selection.

## Current state

`Web3Metadata` recognizes only `ethereum.blocks` and
`ethereum.transactions`. `Web3TableHandle` stores `blockRange` and
`transactionHashes`, and `Web3SplitManager` translates those names back into
the generic adapter scan.

## Proposed design

The handle stores `methodName`, `ranges`, and `discreteValues`. Metadata
examines one descriptor method at a time. A method is eligible only when every
required split or predicate binding is satisfied by an existing handle value
or an extractable Trino domain. The selected immutable handle is passed to the
split manager without chain-name branching.

BIGINT equality/bounded ranges are supported for `SPLIT` bindings. VARCHAR
equality/`IN` domains are supported for `PREDICATE` bindings. Range predicates
are removed from the remaining tuple domain because bounded adapter splits
enforce them exactly. Discrete predicates remain as residual filters because
identifier equality and normalization are chain-specific.

## Affected modules

- `trino-web3-core`: generic immutable table-handle predicate state.
- `trino-web3-adapter`: selected method in `ChainScan`.
- `trino-web3-plugin`: descriptor-driven access-path selection and generic
  scan construction.
- `trino-web3-evm`: method consistency and transaction-hash validation.
- `trino-web3-testing`: metadata and end-to-end regressions.

## Correctness and bounds

- Metadata performs no RPC.
- A method missing a required binding is never selected.
- Unsupported domains remain in Trino.
- Handles contain at most 64 predicate columns and 100,000 discrete values;
  catalog query limits may be lower.
- Split planning repeats query-limit and method checks.
- Raw predicate values never appear in handle `toString()`.
- Existing runtime cancellation, retry, cache, and resource ownership are
  unchanged.

## Tests

- Generic handle immutability, validation, and bounded display.
- Descriptor-driven range pushdown for a non-EVM column name.
- Descriptor-driven discrete pushdown and residual preservation.
- Incomplete required bindings and unsupported domains are not pushed.
- Ethereum block/hash queries, bounds, case-sensitive residuals, cache,
  cancellation, distributed execution, and packaged plugin remain green.

## Validation

```bash
mvn -pl trino-web3-core,trino-web3-adapter,trino-web3-evm,trino-web3-plugin,trino-web3-testing -am test -DskipITs
mvn validate
mvn clean verify
git diff --check
```
