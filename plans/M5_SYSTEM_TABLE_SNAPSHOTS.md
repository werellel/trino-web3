# Plan: M5.1 system table snapshots

## Goal

Expose safe operator-visible state for configured chain runtimes without adding
a remote execution path.

## Scope

Register the five M5 target system tables and add a runtime-owned immutable
snapshot containing effective policy, global metrics, cache metrics, and local
provider cooldown state.

## Non-goals

This increment does not add endpoint identity validation, health probes,
provider-specific metrics, disk cache, new configuration, or query-path policy.

## Design

`Web3Connector` owns a fixed set of Trino `SINGLE_COORDINATOR` system tables.
At cursor creation each table reads descriptor data and `RemoteExecutionRuntime`
snapshots only. `RemoteRuntimeSnapshot` deliberately contains no `URI`, request,
or request result. A provider is `COOLDOWN` only when the existing generic retry
logic temporarily avoids it; otherwise it is `AVAILABLE`.

## Affected modules

* `trino-web3-runtime`: safe immutable runtime snapshot
* `trino-web3-plugin`: Trino 475 system-table SPI integration
* `trino-web3-testing`: catalog-level contract and secret-exposure tests

## Correctness and validation

Snapshots must not trigger remote work, expose secrets, or use PageSource-local
metrics. Tests assert all table names, unconfigured-chain rows, effective
configured policy, zero-state counters, and absence of a credential marker.
Run `mvn validate`, the relevant catalog tests, and `mvn clean verify`.
