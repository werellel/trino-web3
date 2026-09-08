# Releasing

## Version policy

Use Semantic Versioning (`MAJOR.MINOR.PATCH`) for released artifacts. While
the connector remains below 1.0, a minor release may still include documented
configuration or schema evolution, but existing supported behavior should not
be removed without a migration note. Patch releases are limited to compatible
fixes and documentation or build corrections.

The repository stays on a `0.1-SNAPSHOT` development version. A release is
created from a clean commit by pushing a tag such as `v0.1.0`; CI derives the
non-SNAPSHOT Maven version in its ephemeral checkout.

## Release gate

Before tagging:

1. Update `CHANGELOG.md` and user-facing documentation.
2. Run `mvn clean verify` with Java 23 and Maven 3.9 or newer.
3. Confirm no credentials, private endpoints, or generated target files are
   staged.
4. Review [`docs/CHAIN_SUPPORT.md`](CHAIN_SUPPORT.md). Do not describe a
   schema as endpoint-verified unless its immutable probe record includes the
   expected identity, chain-specific smoke result, and UTC timestamp.
5. Confirm the tag is `vMAJOR.MINOR.PATCH` and points at the intended commit.

The GitHub release workflow repeats the full verification, builds the plugin
distribution, and attaches `trino-web3-plugin-<version>-plugin.zip`. It uses
the repository-provided GitHub token only for release creation and publishes no
Maven Central artifacts until a repository and signing policy are explicitly
approved.

## Rollback

If a release is invalid, mark the GitHub release as a draft or remove the tag
according to repository policy, then publish a higher patch version after the
fix. Never overwrite an already published version.
