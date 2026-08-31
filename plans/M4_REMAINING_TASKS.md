# Plan: M4 remaining multi-chain tasks

Status: Aptos `transactions`/`events`, Solana
`blocks`/`transactions`/`instructions`, descriptor compatibility coverage, and
the extension checklist are implemented.

## Objective

Complete the multi-chain proof without weakening the descriptor boundary or
forcing non-EVM chains into an EVM-shaped relational model. Every new table must
have bounded scans, deterministic local tests, cancellation, observability,
documentation, and an executable adapter path.

## Priority order

### 1. Define Aptos finality and cache identity

Completed. `aptos.transactions` uses committed `ledger_version` ranges as its
immutable cache identity; `aptos.events` uses canonical event
`(account_address, creation_number, sequence_number)` identity. The adapter
admits only fully decoded contiguous ranges, and rejects partial, malformed, or
mismatched responses without negative caching. The selected REST range
endpoints do not expose a moving head alias, so committed data is treated as
finalized and the optional cache TTL is operational only.

### 2. Add the Solana vertical slice

Completed. The Solana adapter exposes `solana.blocks`, `solana.transactions`,
and `solana.instructions` through bounded slot ranges and shared JSON-RPC
`getBlock` execution with `commitment=finalized`. It preserves native compiled
top-level instructions and returns no rows for a null block result. Local mock
tests cover batch execution, native rows, unbounded rejection, and ZIP loading.
The initial slice intentionally has no cache admission: cache identity and
reorganization behavior require a separate adapter contract.

### 3. Strengthen descriptor compatibility tests

Completed. Tests cover adapter/table version evolution, optional additive
fields, immutable chain identity, rejected existing-table removal, unknown
provider response fields, unsupported methods, schema ownership conflicts, and
mixed JSON-RPC/REST executable registry composition. Malformed transport
definitions fail through the codec without leaking descriptor values, while
unsupported declared types fail during metadata construction without exposing
the descriptor value.

### 4. Prepare the extension boundary for additional chains

Completed. [`docs/NEW_CHAIN_ADAPTER_CHECKLIST.md`](../docs/NEW_CHAIN_ADAPTER_CHECKLIST.md)
defines the native-model, bounded executable-path, finality/cache, cancellation,
metrics, mock-test, and ZIP gates for Bitcoin, Tron, Sui, Near, and future
adapters. The connector registry remains typed to `ExecutableChainAdapter`, and
tests ensure the default metadata schemas remain aligned with the built-in
executable registry. Metadata-only registration and operator descriptor packs
remain prohibited.

## M4 acceptance checklist

- [x] `SHOW TABLES FROM web3.ethereum` succeeds.
- [x] `SHOW TABLES FROM web3.aptos` exposes `transactions` and `events`.
- [x] `SHOW TABLES FROM web3.solana` exposes native Solana tables.
- [x] Aptos and Solana scans use bounded splits and protocol-appropriate
      runtime execution.
- [x] No paid external provider is required by tests.
- [x] Unit, connector, cancellation, failure-mode, and ZIP loading tests pass.
- [x] Maven validation, static checks, and documentation checks pass.

## Non-goals for this plan

- Bitcoin, Tron, Sui, and Near production scans.
- Universal blockchain schemas.
- Descriptor hot reload during active queries.
- Automatic cache admission without a finality contract.
- Cost-based or adaptive execution research features.

## Validation commands

```bash
mvn validate
mvn -pl trino-web3-runtime,trino-web3-solana,trino-web3-aptos,trino-web3-evm,trino-web3-testing -am test -DskipITs
mvn clean verify
git diff --check
unzip -Z1 trino-web3-plugin/target/trino-web3-plugin-0.1-SNAPSHOT-plugin.zip
```
