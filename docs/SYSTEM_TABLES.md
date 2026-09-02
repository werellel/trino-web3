# System table snapshots

`trino-web3` exposes read-only coordinator-local snapshots in the `system`
schema. They are ordinary connector system tables, so a catalog named `web3`
is queried as `web3.system.<table>`.

These reads do not send an RPC request, do not validate endpoint identity, and
do not create a runtime, HTTP client, cache, or executor. Endpoint identity, if
needed for a multi-endpoint schema, is validated during catalog construction.
System tables take a bounded copy of already configured in-process state. Their
data is observational and may change between rows or queries.

## Tables

| Table | Purpose |
| --- | --- |
| `system.chains` | Installed descriptors and whether a runtime is configured |
| `system.providers` | Generated provider role, protocol capability, cooldown state, and local RPC counters |
| `system.rpc_metrics` | Node-local runtime RPC and cache counters |
| `system.rate_limits` | Effective execution-policy limits |
| `system.cache_stats` | Local runtime cache entry, retained-byte, and eviction snapshot |

`system.chains` includes every installed executable adapter. The other tables
include only schemas with configured endpoints.

Provider `state` has deliberately narrow meaning: `COOLDOWN` means the generic
runtime temporarily avoids that provider after a retryable failure; `AVAILABLE`
means it is not in that local cooldown. Neither value proves provider reachability
or chain identity.

`system.rpc_metrics` aggregates physical wire attempts for one configured
schema. `system.providers` attributes each such attempt to exactly one generated
provider role. Its RPC columns use the same names and meanings as
`system.rpc_metrics`; cache counters remain schema-wide because the cache is not
provider-owned. See [metrics](METRICS.md) for units and aggregation semantics.

## Security contract

System tables never expose endpoint URLs, credentials, request IDs, SQL query
text, RPC parameters, hashes, addresses, or provider-specific labels. Provider
names are connector-generated roles (`primary`, `fallback-N`), not endpoint or
vendor names. Use catalog configuration and the normal security controls for
credentials; system tables are not a secret-inspection interface.

## Examples

```sql
SELECT schema_name, runtime_configured, protocol, configured_provider_count
FROM web3.system.chains;

SELECT schema_name, provider_name, state, cooldown_remaining_millis
FROM web3.system.providers;

SELECT schema_name, request_count, failure_count, retry_count
FROM web3.system.rpc_metrics;
```
