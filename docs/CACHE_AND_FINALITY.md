# Cache and Finality Boundary

M2 does not cache remote results. Single-flight shares only currently executing
operations and removes them after success, failure, or cancellation. Transport
errors, throttling responses, malformed payloads, and missing data are never
stored.

Cache identity, finality classifications, and reorganization handling belong to
M3. Near-head block numbers must not be treated as immutable cache keys. The M2
provider health cooldown is execution state only and has no blockchain finality
meaning.
