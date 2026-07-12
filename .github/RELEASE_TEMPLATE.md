# RefactorKit {version} Release Notes

## Highlights

- {Primary user-visible change or release goal}
- {Important safety, refactoring, packaging, or integration improvement}
- {Documentation or compatibility note}

## Verification

Complete before publishing the release:

- [ ] GitHub Actions CI is green for the release commit/tag.
- [ ] `./gradlew build`
- [ ] `./refactorkit test-golden`
- [ ] `./gradlew packageCliRuntime`
- [ ] Packaged CLI smoke test: `modules/refactorkit-cli/build/package/refactorkit/bin/refactorkit --help`
- [ ] Packaged CLI smoke test: `modules/refactorkit-cli/build/package/refactorkit/bin/refactorkit scan samples/java-maven-simple`
- [ ] `scripts/verify-runtime-archive.py` passes for every platform asset.
- [ ] Release notes list known limitations and risks for this version.
- [ ] macOS Developer ID/notarization status is stated explicitly.
- [ ] Windows Authenticode/SmartScreen publisher status is stated explicitly.

## Artifacts

- Source tag: `<tag>`
- Commit: `<commit-sha>`
- CLI distribution: `<artifact-name-or-link>`
- Checksums/signatures: `<checksum-or-signature-location>`
- macOS signing/notarization: `<signed-and-notarized|unsigned>`
- Windows Authenticode: `<signed|unsigned>`
- Build/SBOM attestations: `<attestation-location>`
- Additional artifacts: `<optional>`

## Limitations and known risks

Document all relevant risks instead of hiding them. For `v0.1.0-alpha`, review at least:

- Java analysis is mostly lexical and not yet fully type-resolved.
- Refactoring previews must be reviewed before apply.
- Framework/string/reflection references may require manual review.
- `organize-imports` may not remove unused imports.
- `safe-delete`, `extract-method`, and `change-signature` remain conservative MVP features.
- Daemon, LSP, MCP, and CLI contracts may change before a stable release.

## Rollback

- For RefactorKit-managed workspace edits, use `refactorkit patch rollback <transaction-id> --root <path>`.
- If a published release must be withdrawn, mark the GitHub release as withdrawn,
  document the replacement version, and leave the Git tag history auditable.
- If consumers must downgrade, document the last known compatible version and any
  required cleanup of generated `.refactorkit/` transaction metadata.

## Upgrade notes

- Breaking changes from the previous release: `<none-or-list>`
- CLI command changes: `<none-or-list>`
- JSON-RPC/LSP/MCP contract changes: `<none-or-list>`
- Migration steps for users or automation: `<none-or-list>`
- Documentation links: `README.md`, `CHANGELOG.md`, `docs/release-plan.md`
