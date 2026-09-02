# Plan: M5.2 endpoint identity validation

## Goal

Reject a catalog whose configured primary and fallback endpoints for one schema
do not identify the same native network.

## Scope

At connector construction, schemas with two or more configured endpoints probe
every endpoint through the existing runtime. The native adapter owns the probe
and canonical response parsing:

* Ethereum: JSON-RPC `eth_chainId`
* Solana: JSON-RPC `getGenesisHash`
* Aptos: REST `GET /v1` `chain_id`

The connector compares the canonical values. A mismatch, malformed identity,
or endpoint failure rejects catalog creation. Errors do not reveal endpoint
origins, credentials, or identity values. One endpoint is not probed because it
has no configured peer to compare.

## Non-goals

This increment does not infer a network from normal query responses, add a
provider health-check service, add a new catalog property, or support new
chains or providers.

## Design

`EndpointIdentityProbe` is an adapter-owned request and JSON extractor.
`EndpointIdentityVerifier` invokes it with the runtime's generated provider
names. `RemoteExecutionRuntime.executeOnProvider` reuses the normal queue,
concurrency, rate, request-size, response-size, cancellation, and bounded retry
controls, while pinning the request to exactly one provider. Targeted retries
never fail over and do not publish a cooldown, since a failed catalog creation
discards the temporary runtime.

## Affected modules

* `trino-web3-adapter`: adapter identity-probe contract and verification
* `trino-web3-evm`, `trino-web3-solana`, `trino-web3-aptos`: native probes and
  canonical identity parsing
* `trino-web3-runtime`: provider-targeted execution
* `trino-web3-plugin`: construction-time verification and failed-start cleanup
* `trino-web3-testing`: matching, mismatch, malformed, and secret-safety
  catalog tests

## Correctness and validation

Tests cover each current chain's identity contract, matching and mismatched
catalogs, targeted retry without failover or cooldown, and absence of a
credential marker from user-facing failures. Run `mvn validate`, relevant
runtime/adapter/catalog tests, and `mvn clean verify`.
