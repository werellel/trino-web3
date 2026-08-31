# Plan: M4 Solana vertical slice

## Scope

Expose bounded, read-only Solana-native tables through the executable chain
adapter registry:

- `solana.blocks`
- `solana.transactions`
- `solana.instructions`

## Contract

All tables require a bounded BIGINT `slot` predicate and generate deterministic
range splits. The adapter executes Solana JSON-RPC `getBlock` through the shared
runtime with `commitment=finalized`. A null result for an unavailable slot is an
empty relational result, not an error or negative-cache entry.

`solana.instructions` represents compiled top-level message instructions only.
It retains the transaction's first signature, instruction order, resolved
program ID, compact JSON account-index list, and native instruction data. It
does not expose inner instructions, parsed instructions, or versioned
account-key object encodings in this slice.

## Finality and cache

`commitment=finalized` is the remote-read commitment for this slice. It is not
a cache contract. The adapter does not admit Solana results to the runtime
cache. A later change must define a canonical blockhash cache identity, slot
revalidation behavior, and reorganization semantics before enabling admission.

## Validation

- strict descriptor parsing and bounded split-planning unit tests;
- decoder tests for native rows, null blocks, and malformed program indices;
- deterministic local JSON-RPC Trino integration tests;
- packaged-plugin class and schema loading tests;
- Maven static checks and full verification.
