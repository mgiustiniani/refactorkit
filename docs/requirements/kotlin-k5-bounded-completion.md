# Kotlin K5 bounded non-move completion gate

Status: sealed current-input requirement; replacement candidate qualification pending. This contract does not include or replace the separately sealed move-next gate.

## Bound requirement identities

The candidate is the conjunction of these immutable inputs:

| Requirement | SHA-256 |
|---|---|
| `kotlin-jvm-organize-imports-callables-formatting.md` | `0d28daa17f8d1a92097503125b405775d1ff2ff73361e1674b57643bec3c9a6e` |
| `kotlin-jvm-change-signature-parameter-rename.md` | `632f20fcade6afd49ad5c86be6eede7b6a399db15401684ac75967cda5d8b8e4` |
| `kotlin-jvm-bounded-extract-inline.md` | `16386a71f2eb579f5f126664f53fba25a775a87f0e680e86ad102146a4b51b77` |
| `kotlin-advanced-shapes-and-platform-matrices.md` | `5d5960b1e0f90a1f2502b48114179a1a289cc7c09ee144fa4e1933970c5b0238` |

Any content change requires an explicit approved-change record, no unapproved weakening, new hashes, a new candidate revision, four new receipts and fresh review. The already-approved leaf supersessions in changes 011–013 are the bounded exceptions to the old refusal criteria; integration change 005 adds no further semantic exception.

## Bound approved-change history

| Approved change | SHA-256 |
|---|---|
| `kotlin-k5-bounded-completion-approved-change-001.md` | `1cffdf674d54d9178b6685e992dbaa0cbbcd4639a17a7903b87f9c008b8db907` |
| `kotlin-k5-bounded-completion-approved-change-002.md` | `935d57dee8be66f70a578b25ce8b150feb0a50d442e268cfeb1eabf29b3abca7` |
| `kotlin-k5-bounded-completion-approved-change-003.md` | `69a9669712dd0a227e89636c25919d3d8c07d8d143bff489212c259fdc5bab82` |
| `kotlin-k5-bounded-completion-approved-change-004.md` | `16855786e493bb59a35433ab72cd130bfb540c712a5c8477fc9803027d71ea9f` |
| `kotlin-k5-bounded-completion-approved-change-005.md` | `0b4a1311ac590a2d101919b0eb2592611ab390ff845e0a610abd0233fb7b9adc` |
| `kotlin-jvm-change-signature-parameter-rename-approved-change-011.md` | `22e9f50725f2bc475ee87c51e25d3d90ea10eb594892e167d6f013a6d2bb686e` |
| `kotlin-jvm-change-signature-parameter-rename-approved-change-012.md` | `c6c988b52bd0330a45aa5769a3e0f02fe0ef6d6d57eccfb14baa7e04c1881169` |
| `kotlin-jvm-change-signature-parameter-rename-approved-change-013.md` | `30bd4b7ae4686e221a2c3fd7dce9a4f2c1f14af2d608d5623b9fc4c16b6a4013` |

Integration change 005 updates only the previously stale parameter-rename input.
Its already-approved leaf changes do not remove any of the twenty-four source
cases or either packaged oracle below. The replacement must receive its own
candidate identity and receipts. See [ARC42](../arc42/appendix-requirements.adoc)
for the integrated closure status.

Changes 001–004 preserve rejected-candidate evidence and authorize only
non-weakening refusals. Leaf changes 011–013 separately supersede their enumerated
old refusal criteria with read-only semantic-preview or coalescing behavior;
change 005 integrates exactly those approvals and corrects the leaf's attribution
to change 012 for the four REQ-001 criteria. These records do not authorize
promotion or reuse of any historical receipt before a replacement native run.

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
- no-write direct/nested typealias-rebound and Maven `-Xplugin` refusals.

For `0.7.0` only, [V070-LOCAL-HOST-ACCEPTANCE-001](v0.7.0-local-host-acceptance-approved-change-001.md)
supersedes the repeated four-host requirement and the four-receipt closure clause
below: missing hosts are `WAIVED_BY_USER`, not PASS. Local native evidence, exact
candidate identity and independent post-native review remain mandatory.

The unwaived matrix hosts are Linux x86-64, Windows x86-64, macOS x86-64 and macOS arm64. Host and embedded runtimes are exact Temurin/OpenJDK `21.0.11+10`. A receipt binds one clean non-merge commit, platform, requirement hashes, focused XML hashes, packaged-smoke hash, subject-file hashes and packaged subject-JAR hashes. Installed-runtime, signed assets, whole-repository CI and release publication are not implied.

No roadmap row closes until all four receipts pass and an independent post-native semantic review returns `PASS_FOR_PROMOTION`. Promotion may close only the five non-move K5 rows covered by the bound requirements: callable import/style completion, bounded parameter-name change signature, bounded extract/inline, advanced shape modeling, and the separate platform/framework matrices (the latter two are distinct roadmap rows, yielding five rows). No broader formatting, signature, flow, shape mutation, platform, plugin or framework support is authorized.
