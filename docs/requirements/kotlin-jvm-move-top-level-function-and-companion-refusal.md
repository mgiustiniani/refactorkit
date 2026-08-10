# Kotlin/JVM top-level function move and companion refusal

Status: requirement baseline; implementation and promotion require tests-only RED, local/package GREEN, four-platform execution, and independent review.

## Requirements

### REQ-KOTLIN-MOVE-FUNCTION-001

RefactorKit shall extend `moveDeclaration` by one bounded whole-file top-level-function shape.

1. The selected declaration shall be one compiler-proven public top-level Kotlin function (explicit or implicit public visibility) with exact compiler-reported file-facade owner independent of source-filename casing, JVM name, descriptor, source range, snapshot attestation, and matching generated class-file method.
2. Extension, suspend, overloaded (including a private same-name sibling), literal-`@JvmName`, default-argument, local/member, script, generated, plugin-dependent, delegated, annotation-evaluated, or non-public selected functions remain outside this row.
3. The source file shall contain the selected public function and only compiler-proven private top-level helper declarations. Other public/internal/protected declarations refuse.
4. In-workspace consumers shall be Kotlin sources with exactly one compiler-proven unaliased explicit import of the source callable FQN. Aliased, same-package implicit, package-star, fully-qualified, callable-reference, Java, generated, mixed, unresolved, recovered, or truncated consumer forms refuse.
5. The caller shall explicitly accept unknown external-consumer risk. Baseline K2/JDT evidence, source-root ownership, destination absence, staged K2/JDT diagnostics, exact new facade callable identity, and every retained consumer use shall be complete and clean. No same-name top-level function may already exist in the destination package. Every location-bound compiler-proven outbound source/external type/callable binding from the moved file shall retain its exact identity, except that declarations carried in the same file shall resolve to their exact computed post-move identities.
6. Preview shall edit only the package declaration, exact consumer import directives, and source-file path. Apply shall use `PatchEngine`, explicit authorization, operation diagnostics, WAL, post-image attestation, and exact rollback.
7. No source text other than the package/import tokens may be rewritten or formatted. Line endings and all private helper bytes remain exact.

### REQ-KOTLIN-MOVE-COMPANION-REFUSAL-001

Standalone companion-object selection shall never be represented as a package/file move.

1. With required external-risk acceptance and exact K2 PSI evidence explicitly identifying the declaration as a companion object, selection shall return `REFUSED` with stable code `kotlin.moveCompanionStandaloneUnsupported`; a nested non-companion object shall not receive this code.
2. The refusal shall contain no `WorkspaceEdit`, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation.
3. The enclosing top-level type/file may still move under its independently qualified whole-file contract; extracting or independently relocating a companion is a separate future operation.
4. Missing approval or invalid/missing semantic identity may retain their earlier fail-closed precedence; this row governs an otherwise exact approved companion selection.

## Bounded non-claims

This requirement does not qualify general function relocation, Java callers, aliased callable imports, overload families, source-file facade renaming, multifile facades, `@JvmName` facades, extension/suspend/default functions, callable references, member extraction, arbitrary declaration splitting, or general Kotlin move support.
