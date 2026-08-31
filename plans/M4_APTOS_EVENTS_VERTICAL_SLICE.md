# Plan: M4 Aptos events REST vertical slice

Status: implemented.

## Goal

Expose a bounded, native Aptos account event stream through `aptos.events`
without treating events as EVM logs or adding provider-specific behavior to the
connector core or REST runtime.

## Supported access path

The table maps to Aptos REST account events by creation number:

```text
GET /v1/accounts/{account_address}/events/{creation_number}?start={sequence_number}&limit={limit}
```

Every scan requires exactly one `account_address`, exactly one
`creation_number`, and a bounded `sequence_number` BIGINT range. The adapter
normalizes valid address and unsigned-decimal creation-number request values,
caps every page at 100 events, and rejects a partial, non-contiguous, or
identity-mismatched response. This deliberately excludes head-aware partial
reads until Aptos finality semantics are defined.

## Schema

```text
aptos.events
├── account_address  varchar
├── creation_number  varchar
├── sequence_number  bigint
├── event_type       varchar
└── data             varchar
```

`data` contains compact JSON text. Aptos Move event payloads are type-specific
and are intentionally not flattened into a universal relational shape. Trino
475's public connector SPI does not expose a direct JSON type writer, so this
slice uses `varchar` rather than an internal Trino type.

## Design changes

- Add a small transport-neutral keyed-range split contract for a range scoped by
  immutable named keys.
- Add serializable keyed-range split conversion at the Trino connector boundary.
- Extend the built-in Aptos descriptor with the events table and the REST method
  inventory; increment the Aptos adapter version.
- Reuse the existing Aptos data client and shared REST runtime. No endpoint,
  HTTP client, retry, rate, failover, cache, or provider behavior is added to
  the adapter.

## Tests

- Descriptor schema and method contract.
- Bounded keyed-range planning, address/creation normalization, and invalid or
  ambiguous predicate rejection.
- REST path/query construction, full-response decoding, GUID and sequence
  identity checks, partial response rejection, and payload-safe errors.
- Trino metadata, local REST execution, native JSON-text payload projection,
  unbounded-scan rejection, and plugin archive table discovery.

## Non-goals

- Event-handle and field-name endpoint support.
- Cursor pagination or unbounded latest-event scans.
- Aptos finality, reorganization handling, or cache admission.
- Event payload schema inference or provider-specific indexing APIs.

## Validation

```bash
mvn -pl trino-web3-testing -am test -DskipITs
mvn clean verify
git diff --check
unzip -Z1 trino-web3-plugin/target/trino-web3-plugin-0.1-SNAPSHOT-plugin.zip
```
