# REQ-JAVA-CLI-RESULT Approved Change Receipt 002

- Change ID: `REQ-JAVA-CLI-RESULT-APPROVED-CHANGE-002`
- Approved: 2026-08-08
- Initial baseline SHA-256: `8f29946c5dcb0128075764fbcd2756156d61edfad4d0fffe2f49afa526c68957`
- Prior approved-change SHA-256: `7c8555ba5c3f393f1809136a38e86da9419234962864513bd4515001557c250c`
- Approval evidence: user explicitly approved catalogue v2 as the default, exact v1 preservation through `commands --json --schema-version 1`, and atomic co-release of `REQ-JAVA-CLI-CATALOG-002` with `REQ-JAVA-CLI-RESULT-001`.

## Approved dependency correction

The prior exclusion that kept the command catalogue unchanged is replaced only to the extent necessary for truthful atomic co-release:

- `REQ-JAVA-CLI-CATALOG-002` owns the default closed v2 catalogue;
- v2 reports `jsonSupport: "preview-only"` for `java.renameMavenModule` only;
- `REQ-JAVA-CLI-CATALOG-001` preserves exact v1 through explicit `--schema-version 1` selection;
- RESULT-001 owns the successful source-built preview result itself;
- the catalogue and result protocol remain separate documents and neither embeds the other.

`preview-only` is a bounded claim: it covers only the successful preview/common-envelope behavior already authorized by approved-change receipt 001. It does not authorize or claim RESULT-002 through RESULT-006.

## Atomic production and promotion boundary

CATALOG-001 compatibility, CATALOG-002 default v2, and RESULT-001 preview may be implemented in one atomic production changeset only after each affected executable requirement has meaningful RED evidence. Each receives a separate independent revision-bound conformance review. None is promoted until all pass; promotion is status-only and followed by joint verification.

## Unchanged boundaries

All other scope, protocol, authority, no-write, human compatibility, installed-runtime, platform, language, SURFACE-002, release, and historical-evidence exclusions remain unchanged.
