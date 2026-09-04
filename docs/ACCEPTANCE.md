# External-user acceptance

This checklist is the repository-local acceptance gate for the Trino 475
release line. It uses only deterministic in-process Trino runners and local
HTTP mock servers; no paid provider, credential, or public blockchain network
is required.

## Verified behavior

The following checks are covered by `mvn clean verify`:

* all reactor modules compile with Java 23 and pass Checkstyle, Enforcer, and
  unit tests;
* an empty catalog loads and `SHOW SCHEMAS FROM web3` returns the installed
  chain and system schemas;
* Ethereum bounded block queries execute through a deterministic JSON-RPC
  mock, including fallback and cache behavior;
* Aptos transactions/events and Solana blocks/transactions/instructions use
  their native bounded query paths;
* all five system tables expose safe local snapshots without endpoints,
  credentials, request data, hashes, or addresses;
* endpoint identity validation, cancellation, retry limits, rate admission,
  cache corruption handling, and lifecycle cleanup remain covered by tests;
* the assembled plugin ZIP loads through an isolated Trino classloader and
  executes a bounded query.

Run the acceptance gate with:

```bash
mvn --batch-mode --errors clean verify
```

## Release operations requiring repository access

The tag-triggered workflow in `.github/workflows/release.yml` must still be
run by a repository maintainer for a real release tag such as `v0.1.0`. That
external run verifies the tag-derived version and publishes the tested plugin
ZIP as a GitHub release asset. Maven Central publication is intentionally not
enabled until repository ownership, signing, and artifact retention policy
are approved.
