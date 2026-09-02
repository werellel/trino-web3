# Plan: M5.3 structured runtime metrics

## Goal

Make bounded runtime telemetry available through Trino's page-source metrics and
the existing safe system-table surface without introducing a plugin-owned global
metrics registry.

## Scope

The runtime will retain physical-attempt counters for each generated provider
role in addition to its schema-wide counters. `system.providers` will expose
those counters next to the existing local cooldown state. Page sources will
publish a documented, stable set of Trino SPI `Metrics` entries for their
execution-scoped counters.

## Non-goals

This increment does not add a global JMX MBean, OpenTelemetry exporter, provider
health probe, metric labels derived from endpoints or requests, or a new remote
execution path. Trino 475's connector SPI has no safe plugin-owned JMX
registration hook; direct process-global MBean registration would not preserve
catalog lifecycle ownership.

## Current state

The runtime already has exact schema-wide physical counters and isolated
execution scopes. Page sources already return Trino `Metrics`, while the five
M5.1 system tables return safe snapshots. Provider rows currently expose only
protocol capability and cooldown state.

## Proposed design

Each `RemoteExecutionRuntime` creates one atomic metric scope for every
generated role (`primary`, `fallback-N`). A wire attempt updates exactly one
provider scope as well as the existing runtime scope. The attempt fan-out keeps
execution-scoped metrics unchanged. Provider snapshots carry only fixed counters
and the generated role; no URI, provider brand, method, parameter, error
message, hash, address, or query identifier is retained.

`Web3Metrics` owns the stable Trino SPI metric keys and conversion from an
execution snapshot. `system.providers` adds the physical provider counters,
while `system.rpc_metrics` remains the schema-wide aggregate.

## Affected modules

* `trino-web3-runtime`: provider metric scopes and immutable snapshots
* `trino-web3-plugin`: stable PageSource metric keys and provider system-table
  columns
* `trino-web3-testing`: runtime, PageSource, catalog, and secret-safety tests
* `README.md`, `docs/RPC_RUNTIME.md`, `docs/SYSTEM_TABLES.md`, and a metrics
  reference: stable operator contract

## Correctness and validation

Every physical wire attempt must be counted once by its selected provider and
once by the schema aggregate. Retry, throttle, and failover attribution belongs
to the provider that observed the failure. A provider counter must not include a
failed-over request it did not execute. Tests use deterministic transports and
local mock servers, including primary-to-fallback execution. Run `mvn validate`,
the relevant runtime and connector tests, and `mvn clean verify`.
