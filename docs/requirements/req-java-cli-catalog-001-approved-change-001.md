# REQ-JAVA-CLI-CATALOG-001 Approved Change Receipt 001

- Change ID: `REQ-JAVA-CLI-CATALOG-001-APPROVED-CHANGE-001`
- Approved: 2026-08-08
- Initial baseline: `REQ-JAVA-CLI-CATALOG-001-BASELINE-001`
- Initial baseline SHA-256: `56485baf0ab096ad994e18f2fa29650bbf4bf2c2098d027ccd426c8a360d0009`
- Promoted implementation commit: `8b361e3a4ac9d83ef2b9b4e797a7a4ea569d42dc`
- Approval evidence: user explicitly approved catalogue v2 as the default, preservation of v1 through `commands --json --schema-version 1`, and atomic co-release with `REQ-JAVA-CLI-RESULT-001`.

## Approved compatibility change

The exact promoted `refactorkit.cli-command-catalog/v1` bytes and semantics remain supported through this explicit source-built route:

```text
refactorkit commands --json --schema-version 1
```

The original v1 document remains closed and unchanged. Its three entries retain `jsonSupport: "catalog-only"`, meaning that v1 makes no operation-result JSON claim. The selector does not open a workspace and does not grant preview, plan, approval, apply, transaction, rollback, or installation authority.

The unversioned route `refactorkit commands --json` is approved to move atomically to the separate `refactorkit.cli-command-catalog/v2` contract defined by `REQ-JAVA-CLI-CATALOG-002`. Until that atomic co-release passes independent review and promotion, the currently promoted unversioned v1 route remains the truthful production behavior.

## Executable-requirement change

The `REQ-JAVA-CLI-CATALOG-001` scenario may be changed only as follows before its new RED:

- replace the unversioned catalogue invocation with the exact explicit v1 selector;
- require the top-level help route to advertise `refactorkit commands --json [--schema-version 1]`;
- change its effective status from `@implemented-and-validated` to `@absent` because the explicit selector does not yet exist;
- preserve every exact v1 output byte, closed field set, validator rule, route inventory, capability separation, non-mutation condition, bounded platform claim, and installed-runtime exclusion.

All original baselines, checksums, RED/GREEN/post-promotion evidence, hashes, and the historical promoted feature revision remain immutable evidence and must not be rewritten.

## Validation and promotion rule

`REQ-JAVA-CLI-CATALOG-001`, `REQ-JAVA-CLI-CATALOG-002`, and `REQ-JAVA-CLI-RESULT-001` may share one atomic production changeset only after each has meaningful RED evidence. Each requirement requires its own revision-bound independent review. None may be promoted until all three pass, after which status-only promotion and joint post-promotion verification are required.

## Unchanged exclusions

Installed/package parity, installed-runtime promotion, fresh SURFACE-002 authority qualification, non-Java commands/languages, wider platforms, and broad release claims remain excluded.
