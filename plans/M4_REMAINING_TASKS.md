# Plan: M4 remaining multi-chain tasks

Status: Aptos `transactions` and `events` are implemented; the tasks below
remain before M4 acceptance.

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

Add a Solana-native adapter for `solana.blocks`, `solana.transactions`, and
`solana.instructions` using Solana JSON-RPC semantics. Do not translate
instructions or accounts into EVM concepts. Use bounded slot ranges, explicit
request limits, descriptor mappings, local mock JSON-RPC tests, cancellation,
and packaged-plugin integration tests.

### 3. Strengthen descriptor compatibility tests

Cover descriptor version evolution, optional fields, unknown response fields,
unsupported methods, schema/table ownership conflicts, and adapter registry
composition across multiple protocols. Verify that malformed descriptors fail
at catalog construction and never expose endpoint values or response payloads.

### 4. Prepare the extension boundary for additional chains

Document and test the adapter checklist for Bitcoin, Tron, Sui, and Near. Do
not add metadata-only tables or operator descriptor packs until each table has
an executable, bounded, cancelled, and tested path. Provider-specific policy
must remain in the runtime/provider layers.

## M4 acceptance checklist

- [ ] `SHOW TABLES FROM web3.ethereum` succeeds.
- [x] `SHOW TABLES FROM web3.aptos` exposes `transactions` and `events`.
- [ ] `SHOW TABLES FROM web3.solana` exposes native Solana tables.
- [ ] Aptos and Solana scans use bounded splits and protocol-appropriate
      runtime execution.
- [ ] No paid external provider is required by tests.
- [ ] Unit, connector, cancellation, failure-mode, and ZIP loading tests pass.
- [ ] Maven validation, static checks, and documentation checks pass.

## Non-goals for this plan

- Bitcoin, Tron, Sui, and Near production scans.
- Universal blockchain schemas.
- Descriptor hot reload during active queries.
- Automatic cache admission without a finality contract.
- Cost-based or adaptive execution research features.

## Validation commands

```bash
mvn validate
mvn -pl trino-web3-runtime,trino-web3-aptos,trino-web3-evm,trino-web3-testing -am test -DskipITs
mvn clean verify
git diff --check
unzip -Z1 trino-web3-plugin/target/trino-web3-plugin-0.1-SNAPSHOT-plugin.zip
```
