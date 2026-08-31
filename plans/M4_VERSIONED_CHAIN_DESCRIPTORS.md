# Plan: M4 versioned chain adapters and declarative descriptors

Status: descriptor-foundation increment implemented; full M4 remains in progress.

## Goal

Create the smallest production-safe extension boundary that allows chain
schemas, tables, remote methods, request bindings, and response mappings to be
declared as versioned data without forcing Bitcoin, EVM, Solana, Aptos, Tron,
Sui, and Near into one relational model.

This first M4 increment must use the new boundary for existing Ethereum
metadata. It must not leave an unused framework beside the production path.

## Scope

- Add a Trino-independent `trino-web3-chain` module.
- Define a versioned chain descriptor format.
- Describe native tables, SQL type signatures, JSON-RPC or REST operations,
  bounded request bindings, and response field mappings.
- Validate descriptor size, names, uniqueness, protocol invariants, JSON
  pointers, column mappings, nullability, and supported format versions.
- Require adapter and table version increments for changed definitions.
- Provide an immutable adapter registry and a declarative response mapper.
- Require an explicit row limit for every declarative response mapping call.
- Load the built-in Ethereum metadata from a descriptor resource.
- Make Trino schema, table, and column discovery use the registry.
- Preserve the existing Ethereum pushdown, splits, execution, cache, finality,
  cancellation, and PageSource behavior.
- Add deterministic tests for registry composition and schema evolution.

## Non-goals

- A universal blockchain transaction or block model.
- Arbitrary scripts, expressions, reflection, or executable code in a
  descriptor.
- Runtime hot reload while queries are active. Registry composition is fixed
  for a connector instance so Trino metadata remains stable.
- Exposing a descriptor-only table that has no executable chain adapter.
- A generic REST transport or declarative PageSource in this increment.
- Production Solana, Aptos, Bitcoin, Tron, Sui, or Near scans in this increment.
- Provider endpoints, credentials, headers, retry, rate, failover, or cache
  storage policy in chain descriptors.

## Current state

`Web3Metadata` imports Ethereum table classes directly. Table definitions are
Java constants, and response field names are embedded in decoders. The runtime
accepts provider-independent logical operations but its current wire transport
is JSON-RPC. There is no adapter registry or descriptor compatibility contract.

## Proposed design

The new dependency shape is:

```text
plugin ───────────────→ chain descriptor API
  │                         ↑
  ├→ core                   │
  └→ EVM adapter ───────────┘
           └──────────────→ runtime
```

`trino-web3-chain` has no Trino SPI, chain implementation, provider, or
transport dependency. A `ChainAdapter` supplies one immutable
`ChainDescriptor`. `ChainRegistry` validates unique chain and schema ownership
and provides deterministic discovery order.

The initial format identifier is `web3.trino.io/v1alpha1`. The format version
governs descriptor syntax. A separate positive adapter version governs a
chain's schema and mapping evolution. Unsupported format versions fail at
catalog construction rather than during a query.

Remote methods declare:

- logical operation name;
- protocol (`JSON_RPC` or `REST`);
- RPC method or read-only HTTP action;
- optional REST path;
- bounded request bindings from a split, predicate, projection, or literal;
- response cardinality, row JSON pointer, and per-column JSON pointers.

Unknown fields in a provider response are ignored because only declared field
pointers are read. Missing optional fields become JSON null. Missing required
fields, incompatible row cardinality, and malformed mappings fail explicitly
without including the raw response in the error.

Descriptor parsing is strict. Unknown descriptor properties are rejected to
catch configuration typos. Descriptors cannot contain endpoints, secrets,
provider headers, or executable expressions.

## Affected modules

- Root POM: add `trino-web3-chain`.
- `trino-web3-chain`: descriptor API, codec, registry, response mapper, tests.
- `trino-web3-evm`: built-in Ethereum descriptor and adapter.
- `trino-web3-plugin`: resolve Trino metadata from the registry.
- `trino-web3-testing`: prove existing catalog and query behavior is unchanged.
- Architecture, chain model, runtime, testing, and research documentation.

No runtime scheduler or cache implementation changes are required.

## Correctness risks

| Risk | Control |
| --- | --- |
| One schema is owned by two adapters | Reject the registry at construction. |
| Descriptor typo silently changes behavior | Reject unknown fields and invalid names. |
| Optional remote field becomes required accidentally | Validate nullability and test missing fields. |
| Provider adds response fields | Ignore fields not named by mappings. |
| Method mapping references a missing column | Reject the table descriptor. |
| A descriptor bypasses bounded scans | Request bindings are declarative only in this increment; existing split enforcement remains authoritative. |
| REST definition enables side effects | Allow only `GET` and `POST` read operations. |
| Descriptor leaks credentials | Endpoint, header, and secret fields are not part of the format. |
| Metadata changes during a query | Registry is immutable for the connector lifetime. |

## Required tests

- Descriptor JSON round trip.
- Unsupported format version rejection.
- Unknown descriptor property rejection.
- Duplicate chain, schema, table, column, method, binding, and response mapping
  rejection.
- JSON-RPC and REST protocol validation.
- Unknown provider response fields ignored.
- Missing optional response fields mapped to null.
- Missing required response fields rejected without payload disclosure.
- Registry addition/removal through immutable composition.
- Ethereum descriptor metadata equals the existing public schema.
- `SHOW SCHEMAS`, `SHOW TABLES`, metadata, bounded queries, cache tests, and ZIP
  loading remain green.

## Implementation steps

1. Add this plan and the descriptor ADR.
2. Add the chain module and immutable validated descriptor records.
3. Add the strict JSON codec, immutable registry, and response mapper.
4. Add an Ethereum descriptor resource and adapter.
5. Resolve Web3 metadata through the registry and Trino `TypeManager`.
6. Add unit and connector regression tests.
7. Update architecture and operator/developer documentation.
8. Run the complete validation matrix.

## Validation commands

```bash
mvn validate
mvn -pl trino-web3-chain test
mvn verify
git diff --check
unzip -Z1 trino-web3-plugin/target/trino-web3-plugin-0.1-SNAPSHOT-plugin.zip
```

All tests must use local data or in-memory JSON. No external chain endpoint or
credential is required.

## Follow-up M4 increments

1. Add a transport-neutral remote request/response contract and REST transport. Implemented.
2. Route PageSources through executable chain adapters rather than table-name
   conditionals. Implemented.
3. Add a Solana JSON-RPC vertical slice.
4. Add an Aptos REST vertical slice. `aptos.transactions` and `aptos.events` implemented.
5. Add Bitcoin, Tron, Sui, and Near adapters using the same registry.
6. Permit operator-supplied descriptor packs only after their tables can be
   executed, bounded, cancelled, and tested end to end.
