# trino-web3

`trino-web3` is a Trino connector for querying remote blockchain data as
native Trino relations. The project will preserve each chain's native data
model rather than forcing non-EVM chains into an EVM schema.

## Status

Milestone M0 is complete. The repository provides an empty `web3` catalog
that can be loaded by Trino and queried with:

```sql
SHOW SCHEMAS FROM web3;
```

Ethereum RPC, chain tables, caching, and provider-specific behavior are not
implemented yet. They are intentionally deferred to later milestones.

## Compatibility

| Component | Version |
| --- | --- |
| Trino SPI | 475 |
| Java | 23 |
| Maven | 3.9 or newer |

## Build and test

Run the full local validation, including static checks, unit tests, and the
packaged-plugin integration test:

```bash
mvn verify
```

The test suite starts an in-process Trino runner. It does not contact an RPC
provider and requires no credentials or external blockchain network.

## Module layout

```text
trino-web3-plugin   Trino SPI plugin and empty catalog bootstrap
trino-web3-testing  Catalog and plugin-archive integration tests
```

Additional chain, runtime, and cache modules will be added only when their
respective roadmap milestones begin.

## Development rules

Read [AGENTS.md](AGENTS.md), [ARCHITECTURE.md](ARCHITECTURE.md),
[ROADMAP.md](ROADMAP.md), and [PLANS.md](PLANS.md) before significant changes.
The main invariants are bounded remote work, native chain data models, and a
provider-independent runtime.
