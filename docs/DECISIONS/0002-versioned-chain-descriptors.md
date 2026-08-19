# Version chain adapters and declarative descriptors

## Status

Accepted for incremental M4 implementation.

## Context

The connector must grow from one EVM adapter to heterogeneous JSON-RPC and REST
chains. Hard-coding every schema, method, and response field in connector core
would require core releases for routine chain schema changes. A fully dynamic
universal blockchain model would erase native semantics and move correctness
rules into an unsafe configuration language.

Trino metadata must also remain stable during planning and execution. Hot
reloading descriptors while queries are active would make handles and splits
refer to different table versions.

## Decision

Introduce a Trino-independent, versioned descriptor API and an immutable
connector-lifetime chain registry.

Descriptors declare chain-native schemas and tables, SQL type signatures,
remote method inventories, restricted request bindings, and explicit response
field mappings. Descriptors do not declare provider endpoints, credentials,
headers, retry, rate, failover, cache storage, or arbitrary executable logic.

The format version and adapter version are separate. Unsupported format
versions and invalid cross-references fail before a catalog is made available.
An existing definition cannot change without an adapter-version increment, and
an existing table cannot change without its own table-version increment.
Unknown remote response fields are ignored; missing required mapped fields fail
explicitly; missing optional mapped fields become null.

Complex planning, decoding, finality, reorganization, and binary protocol
semantics remain in code-based chain adapters. A declarative descriptor is a
stable assembly format, not a replacement for chain correctness code.

Registry composition occurs when the connector is created. Adding or removing
an adapter therefore requires creating a new connector instance or restarting
the catalog, not mutating metadata underneath active queries.

## Alternatives considered

### One universal blockchain schema

Rejected because Bitcoin UTXOs, EVM receipts/logs, Solana instructions, Aptos
events, Sui objects, and Near receipts do not share one correct relational
model.

### Arbitrary scripts in descriptors

Rejected because they make validation, cancellation, memory bounds, security,
and deterministic testing impossible to enforce at catalog construction.

### Keep every chain definition as Java constants

Rejected because routine method and optional response-field evolution would
require repeated connector-core changes and duplicate metadata logic.

### Hot reload descriptors during active queries

Rejected because table handles and splits must retain stable meanings for the
lifetime of a query.

## Consequences

Simple schema and response changes can be expressed as validated versioned
data. New chains still require code when they introduce real semantic or
transport behavior. Descriptor evolution requires compatibility tests, and a
future external descriptor-pack feature must not expose tables until an
executable bounded adapter is available.
