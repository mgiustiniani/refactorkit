# Deterministic source formatting

Status: implemented for Java whole-file formatting in `v0.4.0`; required per stable language adapter by the
supreme `v1.0.0` roadmap.

## Goal

RefactorKit formats a selected source file according to explicit, reproducible
project style evidence while preserving the same preview, validation, approval,
managed apply, transaction, and rollback model used by refactorings.

Formatting is not an untracked post-processing side effect. A formatter proposes
content; `PatchEngine` remains the only managed filesystem writer.

## Initial Java scope

The first implementation formats one complete Java compilation unit through the
Eclipse JDT formatter. Commands:

```bash
refactorkit format-file path/to/Example.java --root .
refactorkit format-file path/to/Example.java --root . --apply
```

The preview reports formatter backend/version, style source, affected file,
content diff, evidence, warnings, and staged diagnostics.

The implementation refuses generated or syntactically invalid Java, preserves
UTF-8 BOM and LF/CRLF policy, discovers hash-bound Eclipse project preferences,
reports exact staged diagnostics, and leaves imports unchanged. Later additive
operations may include:

- selected-range formatting;
- changed-lines formatting;
- explicit organize-imports composition;
- alternative explicitly selected formatter backends.

## Style resolution

Style selection must be deterministic and visible. The Java adapter should
resolve, in documented precedence order:

1. explicit RefactorKit request/configuration;
2. project formatter configuration referenced by `refactorkit.yml`;
3. Eclipse/JDT project preferences such as `.settings/org.eclipse.jdt.core.prefs`;
4. the versioned RefactorKit default profile.

A formatter XML profile may be supported when explicitly configured. Host IDE or
user-global preferences are not silently consulted because they would make plans
machine-dependent.

The plan/snapshot evidence binds all relevant configuration files, normalized
options, formatter implementation identity, and formatter version. A changed
style file makes the plan stale.

## Patch and ownership modes

### Managed formatting

CLI, daemon, MCP, and the managed LSP command produce a `PatchPlan` and apply only
through `PatchEngine` after approval. Managed formatting is rollbackable.

Conceptual operation:

```json
{
  "operation": "formatFile",
  "arguments": {
    "file": "src/main/java/com/example/Example.java"
  }
}
```

### Native LSP formatting

`textDocument/formatting` returns versioned text edits for client application.
It remains explicitly client-managed and has no RefactorKit transaction or
rollback, consistent with the native LSP ownership boundary. Editors that require
managed rollback use `refactorkit.formatFile` preview/apply instead.

## Safety rules

- Refuse files outside the workspace or declared source scope.
- Refuse generated files under the shared generated-source policy.
- Validate strict UTF-8 and preserve the documented BOM policy.
- Make line-ending behavior explicit and deterministic.
- Do not organize imports unless requested as a separate/composed operation.
- Run exact staged diagnostics and refuse unapproved regressions.
- Revalidate snapshot and formatting configuration under the managed lock.
- Never overwrite concurrent editor or filesystem changes.
- Avoid full-file replacement when backend text edits can be safely normalized;
  otherwise report that the preview is a whole-file change.

## Acceptance

Java single-file formatting requires tests for:

- unformatted compilation unit success;
- already-formatted no-op;
- idempotence across repeated formatting;
- project preference discovery and precedence;
- stale style-configuration refusal;
- Java source levels 8 through 25;
- comments, Javadocs, annotations, records, sealed/pattern constructs and text
  blocks;
- CRLF/LF and UTF-8 BOM behavior;
- malformed UTF-8 and syntax diagnostics;
- generated-source refusal;
- open-buffer/version ownership in LSP;
- preview without writes, managed apply, diagnostics, rollback and hash restore;
- CLI, daemon, LSP, MCP, packaged-runtime and golden coverage.

## Multi-language requirement

Every deep adapter defines an idiomatic formatter backend and configuration
model. Equivalent support means deterministic style evidence, idempotence,
preview/diagnostics, safe managed apply/rollback, and explicit native-editor
ownership—not a requirement that every language share JDT options.
