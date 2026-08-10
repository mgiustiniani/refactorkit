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

## Bound approved-change history

| Approved change | SHA-256 |
|---|---|
| `kotlin-k5-bounded-completion-approved-change-001.md` | `1cffdf674d54d9178b6685e992dbaa0cbbcd4639a17a7903b87f9c008b8db907` |
| `kotlin-k5-bounded-completion-approved-change-002.md` | `935d57dee8be66f70a578b25ce8b150feb0a50d442e268cfeb1eabf29b3abca7` |
| `kotlin-k5-bounded-completion-approved-change-003.md` | `69a9669712dd0a227e89636c25919d3d8c07d8d143bff489212c259fdc5bab82` |

These records preserve rejected-candidate evidence and authorize only
non-weakening refusals before a replacement native run. They do not authorize
promotion or reuse of any historical receipt.

## Exact source oracle

Native qualification executes exactly twenty-four tests with zero skip, failure, error, duplicate, missing case or unrelated report.

`org.refactorkit.kotlin.KotlinCompilerDiagnosticsTest` (eighteen):

1. `organizeImportsRefusesCommentAttachedToImportBlock()`
2. `organizeImportsRemovesCompilerProvenUnusedTypeAndSortsCrLfBlock()`
3. `organizeImportsUsesCounterfactualK2EvidenceForExternalCallables()`
4. `organizeImportsRefusesCompilingCallableBindingSubstitution()`
5. `organizeImportsRefusesUnmodeledExternalJavaFieldRatherThanRemovingUsedImport()`
6. `organizeImportsRefusesUnmodeledEnumAndAliasedPropertyRebound()`
7. `organizeImportsRefusesUnmodeledTypeAliasRebound()`
8. `organizeImportsUsesSnapshotBoundEditorConfigLayoutForSourceCallables()`
9. `organizeImportsRefusesStaleOrUnsupportedProjectStyleWithoutEdits()`
10. `overrideFamiliesAreExactAndExcludeSameSignatureUnrelatedMethods()`
11. `namedArgumentsResolveToExactOverloadParameterSymbols()`
12. `changeSignatureRefusesCompilerProvenExternalOverrideBoundary()`
13. `changeSignatureRefusesPreexistingNewNameTokenThatCouldCaptureBindings()`
14. `compilerModelsAdvancedKotlinJvmShapesAndRefusesDelegatedPropertiesExplicitly()`
15. `boundedExtractAndInlineUseExactCompilerExpressionRangesAndRollback()`
16. `extractRefusesWhenInsertedCallDoesNotBindToNewHelper()`
17. `extractAndInlineRefuseGeneratedSourceOwnershipWithoutEdits()`
18. `extractAndInlineRefuseUnprovenControlAndUsageShapesWithoutEdits()`

`org.refactorkit.jvm.KotlinJavaPublicTypeRenamePlannerTest` (two):

19. `kotlinParameterRenameUpdatesOverrideNamedArgumentsAndPreservesJavaCaller()`
20. `publicKotlinParameterRenameRequiresExternalConsumerApproval()`

`org.refactorkit.jvm.KotlinMoveDeclarationDiagnosticsRouteTest` (one):

21. `kotlinChangeSignatureUsesLazyMixedOperationDiagnostics()`

`org.refactorkit.kotlin.KotlinLanguageAdapterTest` (one):

22. `descriptorPromotesBoundedCompilerReadsAndPrivateTypeRenameProposalOnly()`

`org.refactorkit.kotlin.KotlinJvmBuildModelTest` (two):

23. `androidAndCompilerPluginFacetsRemainSeparateFailClosedCapabilityCases()`
24. `unsupportedKotlinPlatformFailsClosed()`

## Packaged and native gate

`scripts/smoke-packaged-k5-completion.py` and the pre-existing
`scripts/smoke-packaged-kotlin.py` must run from the exact packaged subject and
each end with one exact terminal marker. The latter binds operation-less legacy
add-parameter CLI compatibility. Together they prove:

- compiler-clean advanced-shape catalogue execution;
- read-only CLI preview for callable imports, parameter rename, extract and inline;
- daemon preview/apply/operation-owned diagnostics/rollback for organize imports, mixed Kotlin/Java parameter rename and extract;
- MCP preview/apply/rollback for inline;
- byte-exact source restoration after every transaction; and
- exact refusal-oriented capability projection for the unsupported matrices; and
- no-write typealias-rebound and Maven `-Xplugin` refusals.

Required hosts are Linux x86-64, Windows x86-64, macOS x86-64 and macOS arm64. Host and embedded runtimes are exact Temurin/OpenJDK `21.0.11+10`. A receipt binds one clean non-merge commit, platform, requirement hashes, focused XML hashes, packaged-smoke hash, subject-file hashes and packaged subject-JAR hashes. Installed-runtime, signed assets, whole-repository CI and release publication are not implied.

No roadmap row closes until all four receipts pass and an independent post-native semantic review returns `PASS_FOR_PROMOTION`. Promotion may close only the five non-move K5 rows covered by the bound requirements: callable import/style completion, bounded parameter-name change signature, bounded extract/inline, advanced shape modeling, and the separate platform/framework matrices (the latter two are distinct roadmap rows, yielding five rows). No broader formatting, signature, flow, shape mutation, platform, plugin or framework support is authorized.
