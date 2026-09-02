# Compatibility matrix

The project currently supports one deliberately narrow release line. CI tests
the exact values in this table; compatibility with other versions must not be
inferred from a successful local build.

| Component | Supported value | CI coverage |
| --- | --- | --- |
| Trino SPI/runtime | 475 | `.github/workflows/verify.yml` matrix |
| Java | 23 | Maven Enforcer and GitHub Actions |
| Maven | 3.9 or newer | Maven Enforcer |

The connector targets Trino 475's SPI and metrics contracts. A future Trino
line requires an explicit matrix entry, dependency/BOM review, and a full
`mvn clean verify` run before it is advertised.

## Release compatibility

Release tags use `vMAJOR.MINOR.PATCH`. The release workflow temporarily sets
all reactor module versions to the tag version, runs the complete verification
suite, and attaches the resulting plugin ZIP. It does not commit generated
version changes back to the repository.
