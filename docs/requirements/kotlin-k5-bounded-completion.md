# Kotlin K5 bounded non-move completion gate

Status: sealed candidate requirement. This contract does not include or replace the separately sealed move-next gate.

## Bound requirement identities

The candidate is the conjunction of these immutable inputs:

| Requirement | SHA-256 |
|---|---|
| `kotlin-jvm-organize-imports-callables-formatting.md` | `0d28daa17f8d1a92097503125b405775d1ff2ff73361e1674b57643bec3c9a6e` |
| `kotlin-jvm-change-signature-parameter-rename.md` | `a605ea53b029cbbc003c455419bf99cbc720c5c3df7411f9e6585dde36260e95` |
| `kotlin-jvm-bounded-extract-inline.md` | `16386a71f2eb579f5f126664f53fba25a775a87f0e680e86ad102146a4b51b77` |
| `kotlin-advanced-shapes-and-platform-matrices.md` | `5d5960b1e0f90a1f2502b48114179a1a289cc7c09ee144fa4e1933970c5b0238` |

Any content change requires an explicit non-weakening approved-change record, new hashes, a new candidate revision, four new receipts and fresh review.

## Exact source oracle

Native qualification executes exactly eighteen tests with zero skip, failure, error, duplicate, missing case or unrelated report.

`org.refactorkit.kotlin.KotlinCompilerDiagnosticsTest` (twelve):

1. `organizeImportsRefusesCommentAttachedToImportBlock()`
2. `organizeImportsRemovesCompilerProvenUnusedTypeAndSortsCrLfBlock()`
3. `organizeImportsUsesCounterfactualK2EvidenceForExternalCallables()`
4. `organizeImportsRefusesCompilingCallableBindingSubstitution()`
5. `organizeImportsUsesSnapshotBoundEditorConfigLayoutForSourceCallables()`
6. `organizeImportsRefusesStaleOrUnsupportedProjectStyleWithoutEdits()`
7. `overrideFamiliesAreExactAndExcludeSameSignatureUnrelatedMethods()`
8. `namedArgumentsResolveToExactOverloadParameterSymbols()`
9. `changeSignatureRefusesCompilerProvenExternalOverrideBoundary()`
10. `compilerModelsAdvancedKotlinJvmShapesAndRefusesDelegatedPropertiesExplicitly()`
11. `boundedExtractAndInlineUseExactCompilerExpressionRangesAndRollback()`
12. `extractAndInlineRefuseUnprovenControlAndUsageShapesWithoutEdits()`

`org.refactorkit.jvm.KotlinJavaPublicTypeRenamePlannerTest` (two):

13. `kotlinParameterRenameUpdatesOverrideNamedArgumentsAndPreservesJavaCaller()`
14. `publicKotlinParameterRenameRequiresExternalConsumerApproval()`

`org.refactorkit.jvm.KotlinMoveDeclarationDiagnosticsRouteTest` (one):

15. `kotlinChangeSignatureUsesLazyMixedOperationDiagnostics()`

`org.refactorkit.kotlin.KotlinLanguageAdapterTest` (one):

16. `descriptorPromotesBoundedCompilerReadsAndPrivateTypeRenameProposalOnly()`

`org.refactorkit.kotlin.KotlinJvmBuildModelTest` (two):

17. `androidAndCompilerPluginFacetsRemainSeparateFailClosedCapabilityCases()`
18. `unsupportedKotlinPlatformFailsClosed()`

## Packaged and native gate

`scripts/smoke-packaged-k5-completion.py` must run from the exact packaged subject and end with one exact terminal marker. It proves:

- compiler-clean advanced-shape catalogue execution;
- read-only CLI preview for callable imports, parameter rename, extract and inline;
- daemon preview/apply/operation-owned diagnostics/rollback for organize imports, mixed Kotlin/Java parameter rename and extract;
- MCP preview/apply/rollback for inline;
- byte-exact source restoration after every transaction; and
- exact refusal-oriented capability projection for the unsupported matrices.

Required hosts are Linux x86-64, Windows x86-64, macOS x86-64 and macOS arm64. Host and embedded runtimes are exact Temurin/OpenJDK `21.0.11+10`. A receipt binds one clean non-merge commit, platform, requirement hashes, focused XML hashes, packaged-smoke hash, subject-file hashes and packaged subject-JAR hashes. Installed-runtime, signed assets, whole-repository CI and release publication are not implied.

No roadmap row closes until all four receipts pass and an independent post-native semantic review returns `PASS_FOR_PROMOTION`. Promotion may close only the five non-move K5 rows covered by the bound requirements: callable import/style completion, bounded parameter-name change signature, bounded extract/inline, advanced shape modeling, and the separate platform/framework matrices (the latter two are distinct roadmap rows, yielding five rows). No broader formatting, signature, flow, shape mutation, platform, plugin or framework support is authorized.
