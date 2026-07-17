# Kotlin/JVM organize-imports requirement

Status: active bounded K5 slice with local library apply/rollback acceptance.
CLI/daemon/MCP routing, packaged smoke and four-platform qualification remain
pending.

## Purpose

`organizeImports` removes compiler-proven unused Kotlin imports and sorts the
remaining bounded import block without changing declarations or usages. It
produces one previewable, diagnostic-gated and rollbackable text edit.

## Supported first row

The operation accepts one saved `.kt` file only when:

1. the file belongs to one authoritative, non-generated Kotlin/JVM source set;
2. the hash-bound K2 snapshot compiles without errors;
3. imports form one contiguous block after the exact package declaration, with one
   explicit non-aliased type directive per line and no comments inside or
   attached to the block;
4. K2 resolves each imported source/external JVM type and publishes complete usage
   evidence; a directive is unused only when its sole target usage is the exact
   import token itself;
5. all remaining directives can be sorted deterministically by their complete
   directive text.

Scripts, compiler plugins, conditional/generated sources and formatter inference
are outside this row.

## Exact edit contract

The preview contains at most one text edit replacing the complete import block.
It shall:

- delete only directives whose K2-resolved type has no non-import usage;
- sort remaining directive lines by Unicode code-point order of the complete
  trimmed directive;
- preserve each retained directive byte-for-byte apart from line order;
- preserve the file's LF or CRLF convention;
- preserve comments, declarations, package text and all non-import bytes;
- refuse a no-op rather than publishing an empty mutation plan.

## Evidence and staged validation

Before planning, K2 diagnostics/usages, snapshot, toolchain and build projection
must be complete and hash-bound. Each parsed import must map one-to-one to a
resolved source or external type usage on its exact import line; duplicate,
missing or out-of-block evidence refuses.

The staged overlay must compile with K2, introduce no errors and preserve complete
usage evidence for every retained import in the target file. Preview must not
write workspace or transaction metadata.

## Stable refusal boundaries

The operation refuses with structured codes for at least:

- missing/non-Kotlin/generated/ambiguous source ownership;
- baseline compiler errors or incomplete K2 evidence;
- default-package, split, commented, multiline, star, alias, callable or malformed
  import blocks;
- resolved import evidence that does not map one-to-one to exact import lines;
- no-op organization;
- staged diagnostics regression or remaining unused imports;
- stale snapshot, lease or index authority.

## Mutation and transports

Apply requires explicit authorization and uses `PatchEngine`, WAL, recovery and
byte-exact rollback. Daemon API `0.2`, CLI and MCP shall expose the same operation
additively after the library row is executable-qualified.

## Acceptance

Promotion requires executable success/refusal tests, CRLF coverage, preview
read-only verification, apply/rollback, packaged CLI/daemon/MCP smoke and all four
native platforms.
