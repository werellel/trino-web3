# Research boundary

Production correctness and bounded execution take priority over experimental
behavior. Research features must be isolated behind explicit configuration or
an experimental module and must not change the default connector path.

Examples include adaptive batching, adaptive concurrency, cost-based provider
selection, automatic materialization, and runtime descriptor hot reload.

The versioned descriptor format is a production extension contract, not an
experimental planner. It is intentionally declarative and contains no scripts
or adaptive decisions. Operator-supplied descriptor packs remain disabled until
their transport, planning, cancellation, security, and compatibility contracts
are validated end to end.
