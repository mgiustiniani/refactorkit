# REQ-JAVA-CLI-RESULT Approved Change Receipt 001

- Change ID: `REQ-JAVA-CLI-RESULT-APPROVED-CHANGE-001`
- Approved: 2026-08-08
- Initial baseline: `REQ-JAVA-CLI-RESULT-001-BASELINE-001`
- Initial baseline SHA-256: `8f29946c5dcb0128075764fbcd2756156d61edfad4d0fffe2f49afa526c68957`
- Baseline Git commit: `8b361e3a4ac9d83ef2b9b4e797a7a4ea569d42dc`
- Approval evidence: user instructed `procedi` after receiving the explicit six-slice decomposition and recommendation to approve it.

## Approved decomposition

The originally authorized `refactorkit.cli-result/v1` behavior is decomposed into six repository-unique Story BDD requirements so that each requirement owns one coherent semantic slice:

1. `REQ-JAVA-CLI-RESULT-001` — preview/common envelope and canonical plan projection;
2. `REQ-JAVA-CLI-RESULT-002` — successful applied transaction correlation;
3. `REQ-JAVA-CLI-RESULT-003` — application-level refusal projection;
4. `REQ-JAVA-CLI-RESULT-004` — usage and operational error projection;
5. `REQ-JAVA-CLI-RESULT-005` — unexpected internal error projection;
6. `REQ-JAVA-CLI-RESULT-006` — closure, limits, redaction, and truncation boundaries.

## Current authorized implementation slice

Only `REQ-JAVA-CLI-RESULT-001` is authorized for implementation now. It covers:

- public source-built `java rename-module ... --json` preview;
- exact top-level `refactorkit.cli-result/v1` common envelope;
- caller-supplied or generated bounded opaque `requestId`;
- `outcome=preview`, non-null canonical plan projection, null transaction;
- validation diagnostics that are empty for the qualified fixture;
- exact compact UTF-8 JSON plus one LF, empty stderr, exit 0;
- deterministic bytes apart from explicitly generated request correlation;
- closed top-level, plan, change, diagnostic, and location field contracts;
- no workspace lock, WAL, transaction, managed write, rollback claim, or installed-runtime interaction;
- unchanged human-mode preview behavior.

The plan projection is the admitted closed v1 shape:

```text
sha256, snapshotSha256, requiresApproval, changeCount, changes
```

Each visible change is:

```text
kind, path, previousPath
```

The canonical plan SHA-256 commits the complete normalized edit, including ranges and replacement bytes, while the visible changes remain a bounded projection. It is not a plan ID, apply handle, approval, transaction identity, or semantic authority.

## Deferred approved slices

Applied transaction, refusal, usage/operational error, internal error, and truncation/redaction behaviors remain approved only as future requirement IDs 002 through 006. They must not be implemented, tested as completed, or promoted in the current slice.

## Unchanged exclusions

Installed/package parity, installation promotion, fresh SURFACE-002 qualification, other Java commands, non-Java languages, wider platforms, release claims, and changes to existing promoted requirement history remain excluded.
