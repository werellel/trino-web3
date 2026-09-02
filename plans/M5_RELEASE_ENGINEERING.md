# M5 release engineering and benchmark coverage

## Scope

Make the Trino 475 line reproducible for external contributors and tagged
releases. Keep release builds deterministic and independent of public RPC
providers.

## Changes

* Add an explicit Trino 475 CI compatibility matrix.
* Add Semantic Versioning, compatibility, changelog, and release procedure
  documentation.
* Add a tag-triggered GitHub release workflow that verifies and attaches the
  assembled plugin ZIP using a temporary non-SNAPSHOT version.
* Add in-process JMH coverage for single-request, JSON-RPC batch, and
  batch-with-rate-admission execution, alongside the existing cache benchmark.

## Validation

Run `mvn --batch-mode --errors -Dtrino.version=475 verify` for the CI-equivalent
test gate. Compile benchmark classes with `mvn -pl trino-web3-runtime
clean test-compile`, build the test classpath, and run the documented JMH
commands. No benchmark opens a network connection or requires credentials.
