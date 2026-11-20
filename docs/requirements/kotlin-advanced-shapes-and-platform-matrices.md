# Kotlin advanced-shape evidence and unsupported-platform matrices

Status: candidate requirement; this document authorizes read-only modeling and stable refusal only, not additional mutation authority.

Integrated architecture/status authority is the [ARC42 requirements appendix](../arc42/appendix-requirements.adoc); this satellite owns Kotlin-specific evidence boundaries and refusal acceptance.

## REQ-KOTLIN-SHAPES-001 — Compiler-backed JVM shape facts

After a successful bounded K2 compilation, declaration evidence records additive immutable facts for:

- extension receivers on supported function declarations;
- suspend functions;
- companion objects;
- data, sealed and value classes;
- an exact JVM-name effect when compiler source name and generated JVM method name differ; and
- the already-proven JVM owner/name/descriptor, visibility and exact PSI ranges.

Value-class primary constructors are not fabricated as ordinary JVM constructors; the value type and field-backed constructor property remain modeled from their actual class-file evidence. These shape facts are `EXPERIMENTAL`, compiler-backed, read-only capabilities with `MutationAuthority.NONE`. They do not promote rename, move, change-signature, extract, inline, formatting, or any other operation for the shape.

Delegated properties are detected by compiler PSI and return stable `kotlin.symbolDelegatedPropertyUnsupported` instead of disappearing from an apparently complete catalogue. No delegated-property mutation is authorized.

## REQ-KOTLIN-MATRIX-001 — Separate fail-closed capability cases

The descriptor and build projection publish distinct cases for:

| Case | Qualified status | Stable boundary |
|---|---|---|
| Kotlin/JVM saved `.kt` | bounded compiler evidence | operation-specific requirements only |
| `expect`/`actual` and Multiplatform/common/native/JS | refused | `kotlin.platformUnsupported` / `expectActual` + `multiplatform` capabilities |
| Android Kotlin | refused | Android platform facet plus `kotlin.platformUnsupported` |
| compiler plugins, kapt/KSP semantics | refused | `kotlin.compilerPluginsUnsupported` / `compilerPluginSemantics` |
| generated-code mutation | refused | `generatedCodeMutation` |
| framework-aware Spring/JPA/Jackson semantics | refused | `frameworkAwareSemantics` |
| IDE-generated simple Gradle Kotlin DSL templates | verified by the IDE | template generation only; no script-semantic authority |
| Generic Kotlin scripts (`.kts`, including script-host dependencies) | refused / unavailable | `scriptSemantics` remains refused; return `kotlin.scriptSemanticsUnsupported` until generic host/context evidence qualifies |
| Gradle Kotlin DSL (`settings.gradle.kts`, `build.gradle.kts`) | refused / unavailable | no semantic source ownership or reference binding until the exact Gradle DSL context qualifies |

A JVM source merely containing an annotation or framework-like name does not acquire framework authority. Android, Multiplatform, plugin and framework rows never inherit JVM mutation support. Detection/refusal is a supported safety outcome, not successful transformation. Listing `.kts` as a scanner/source extension means only that the file is recognized; it does not advertise Kotlin script semantics or Build Model ownership.

#### `.kts` script semantics qualification

This is an extension of `REQ-KOTLIN-MATRIX-001`, not a new requirement. Status is
`actionable`; generic Kotlin scripts and Gradle Kotlin DSL are separate qualification
families. Until a family passes its own evidence gates, semantic results are
`unavailable` and the existing `scriptSemantics=refused` capability remains in
force. The IDE has verified generation of simple Gradle Kotlin DSL templates. That
consumer-side evidence covers template generation only: it does not qualify
arbitrary `.kts` projects, plugin builds, implicit-receiver semantics, plugin or
script-dependency resolution, or generated Gradle DSL accessors. RefactorKit now
has executable fail-closed acceptance for standalone script dependencies and
implicit receivers, script-input changes, Gradle settings/project scripts,
nested host receivers, and changed Gradle host context. These stories qualify
recognition and typed refusal only; they do not qualify script semantic results
or mutation support.
Existing RefactorKit evidence also includes the generic refusal row in
[`kotlin-jvm-move-top-level-function.feature`](../../features/kotlin-jvm-move-top-level-function.feature)
and unit checks in `KotlinCompilerDiagnosticsTest`, `KotlinJvmBuildModelTest`, and
`KotlinLanguageAdapterTest`.

The refusal acceptance is split into these independent story identities under
this requirement. Each has its own pre-edit receipt/inventory and is run as one
scenario; the shared Cucumber runner is
[`KotlinScriptSemanticsMatrixCucumberTest.kt`](../../modules/refactorkit-jvm/src/test/kotlin/org/refactorkit/jvm/kotlinmatrix/KotlinScriptSemanticsMatrixCucumberTest.kt):

| Story identity | Fixture family | Acceptance outcome |
|---|---|---|
| `REQ-KOTLIN-MATRIX-001#standalone-script-refusal` | A standalone `.kts` script with `@file:DependsOn` | `kotlin.scriptSemanticsUnsupported` is returned before compiler launch; no script symbols, references, edits, or managed plans are produced. |
| `REQ-KOTLIN-MATRIX-001#standalone-implicit-receiver-refusal` | A standalone `.kts` script with an unbound host-provided receiver | The generic script host remains refused before compiler launch; no receiver binding, symbols, references, edits, or managed plans are fabricated. |
| `REQ-KOTLIN-MATRIX-001#standalone-script-change-refusal` | A standalone `.kts` script whose declared host dependency version changes | The new projection has a different snapshot hash and returns the same typed refusal; no stale semantic result or edit authority is returned. |
| `REQ-KOTLIN-MATRIX-001#gradle-settings-script-refusal` | `settings.gradle.kts` in a Gradle Kotlin DSL project | The exact settings script is captured as an auxiliary Gradle host input, excluded from Kotlin/JVM source ownership, and refused before compiler launch; no settings/plugin resolution, execution, semantic output, or edit authority is inferred. |
| `REQ-KOTLIN-MATRIX-001#gradle-project-script-refusal` | Project `build.gradle.kts` with a plugin-provided accessor shape | The exact project script is captured as an auxiliary Gradle host input, excluded from Kotlin/JVM source ownership, and refused before compiler launch; generated accessors, semantic output, and edit authority are not fabricated. |
| `REQ-KOTLIN-MATRIX-001#gradle-host-receiver-scope-refusal` | A two-example outline for nested receivers in `settings.gradle.kts` and `build.gradle.kts` | Both script kinds remain independently refused, each is captured as one auxiliary input, and neither receives Kotlin/JVM source ownership or fabricated DSL bindings. |
| `REQ-KOTLIN-MATRIX-001#gradle-build-context-change-refusal` | A Gradle project with Kotlin settings and project scripts whose repository and plugin-version inputs change | The refreshed snapshot hash changes and typed refusal remains; both scripts remain auxiliary inputs. Semantic-result cache invalidation remains unqualified because no semantic result is produced. |

Each scenario carries its own exact requirement-and-scenario tag; no bare shared
`@REQ-KOTLIN-MATRIX-001` tag selects multiple cases. A current-source verification
on 2026-10-24 ran seven exact tags in separate offline JDK 21 Gradle invocations
against feature SHA-256
`333c39689cdd6ce0ecf2eaf13c9262432c9fb2e3bc59c648d874f7ad3d993caf`. All seven
scenario identities passed across eight expanded Cucumber cases with zero failed,
undefined, or ambiguous steps. Each invocation used the one shared Kotlin script
matrix runner. The source-current manifest, invocation logs, and Cucumber/JUnit
results are retained under
`.refactorkit/runs/kotlin-script-matrix-acceptance-gap-r006-current-source/`;
each implementation slice also has its own §18.6 receipt and pre/post inventory
under the corresponding `kotlin-*-r001/` run directory. The earlier `r005`
evidence remains valid for its historical three-story feature hash, but `r006`
supersedes it for current-source acceptance.

After `JavaProjectScanner.kt` changed, the seven tags were rerun individually
against the current source at scanner SHA-256
`37f338323860f38feb32d9d059f6b738d5758ec3571ac84047816a196185ca8d`. The same
feature hash still selected seven identities and eight expanded cases; all
passed. Fresh bounded analyzer inventories report one shared runner, zero
effective-glue ambiguities, and no new or ambiguous steps for every identity.
This `r007-current-source` run supersedes `r006` for source currency; its
manifest, per-tag Cucumber output, logs, and inventories are under
`.refactorkit/runs/kotlin-script-matrix-acceptance-gap-r007-current-source/`.
The independently captured per-slice §18.6 pre-edit inventories and receipts
remain under each `kotlin-*-r001/` directory. Both script families remain
refused, with `MutationAuthority.NONE`.

After another change to `JavaProjectScanner.kt`, current-source verification
reran all seven exact tags separately on offline JDK 21 / Gradle 9.6.1. The
current scanner SHA-256 is
`84403f907170e7a6a75a80f97ef3c2e148dd09fcda1a54a02c6454cb4632ca61`; all seven
identities and eight expanded cases passed. The feature, runner, step-definition,
and probe hashes match `r007`, so the installed analyzer inventories and
per-slice §18.6 receipts remain valid without glue changes. The renewed
source-current manifest, per-tag output and logs are in
`.refactorkit/runs/kotlin-script-matrix-acceptance-gap-r008-current-source/`.
This supersedes `r007` for current-source test evidence only. Both families
remain unavailable/refused, with `MutationAuthority.NONE`.

After the next `JavaProjectScanner.kt` change, a source-current replay ran the
seven exact tags together against the captured 837-file source projection; the
projection matches every current workspace input. All seven identities and all
eight expanded cases passed on offline JDK 21 / Gradle 9.6.1 (41/41 steps,
zero failed, errored, or skipped cases). The scanner SHA-256 is
`ba49c40764b6f6d90e8103538bc5d923f1fd872bd286364006f671e6c903171b`; feature,
runner, glue, and probe hashes remain unchanged. Fresh per-identity analyzer
inventories bind every step exactly, with one shared runner and no ambiguity or
duplicate effective expressions. The current-source manifest, inventories,
Cucumber/JUnit reports, log, and checksums are under
`.refactorkit/runs/kotlin-script-matrix-acceptance-gap-r009-current-source/`.
This supersedes `r008` for current-source test evidence only. Both script
families remain unavailable/refused, with `MutationAuthority.NONE`.

The Gradle cases additionally assert that their specific `settings.gradle.kts` or
`build.gradle.kts` is captured exactly once in `ProjectSnapshot.auxiliaryFiles`,
does not occur in Kotlin/JVM-owned source files, and remains under a Gradle model
whose build-code execution and network access are denied. This makes their host
boundary acceptance distinct from standalone `.kts` refusal.

#### Approved change 001 — 10-file Story BDD override

- Change ID: `REQ-KOTLIN-MATRIX-001-APPROVED-CHANGE-001`
- Approval authority: the user's explicit reply, “Override BDD 10 file”.
- Scope: separate requirement-first refusal stories for the standalone-script and
  Gradle Kotlin DSL matrices, including exact host-input boundaries and changed-
  input refusal.

The maximum for this matrix closure is exactly ten workspace files, including
this approval record. The override keeps the source scanner, Kotlin
projection/compiler refusal, seven matrix-specific Story identities, and the
ARC42, requirement, and release narratives consistent in one reviewable change.
Generated receipts, inventories, logs, and reports under `.refactorkit/runs/` are
local evidence and are excluded from this workspace-file count.

1. `modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaProjectScanner.kt`
2. `modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinJvmBuildModel.kt`
3. `modules/refactorkit-kotlin/src/main/kotlin/org/refactorkit/kotlin/KotlinCompilerDiagnostics.kt`
4. `features/kotlin-script-semantics-matrix.feature`
5. `modules/refactorkit-jvm/src/test/kotlin/org/refactorkit/jvm/kotlinmatrix/KotlinScriptSemanticsMatrixCucumberTest.kt`
6. `modules/refactorkit-jvm/src/test/kotlin/org/refactorkit/jvm/kotlinmatrix/KotlinScriptSemanticsMatrixSteps.kt`
7. `modules/refactorkit-jvm/src/test/kotlin/org/refactorkit/jvm/kotlinmatrix/KotlinScriptSemanticsMatrixProbe.kt`
8. `docs/requirements/kotlin-advanced-shapes-and-platform-matrices.md`
9. `docs/arc42/appendix-requirements.adoc`
10. `docs/releases/v0.8.0-progress.md`

No larger changed-file budget is authorized by this record.

The settings and project-script cases are separate because their Gradle hosts,
plugin/accessor environments, and invalidation inputs differ. Their refusal must
not be collapsed into the standalone-script row even if production currently
uses the same `kotlin.scriptSemanticsUnsupported` code. A scenario must assert
the actual typed outcome exposed by the relevant adapter/build-model surface;
do not introduce a new refusal code without extending this requirement first.
The seven refusal identities are implemented and locally validated. Script
semantic qualification remains `actionable`; the existing `moveDeclaration`
refusal example remains supplementary regression evidence and does not replace
the dedicated matrix stories.

Java LSP `textDocument/rename` and Kotlin `changeSignature.renameParameter` are
different contracts. Evidence for JDT LS symbol rename does not qualify Kotlin
value-parameter signature change, which has its own requirement and K2/JDT
binding proof in
[`kotlin-jvm-change-signature-parameter-rename.md`](kotlin-jvm-change-signature-parameter-rename.md).

Before any script semantic capability is advertised, acceptance must establish an
exact family-specific context. The generic-script and Gradle-DSL matrices below
are independent qualification gates: passing one never qualifies the other. All
rows remain `unavailable`/`refused` for semantic results; their Story cases
qualify recognition and fail-closed refusal only.

### Generic Kotlin script qualification matrix

| Matrix case | Context evidence required | Acceptance when support is proposed | Current evidence/status |
|---|---|---|---|
| Standalone `.kts` with `@file:DependsOn` | Script kind, exact host/template, path and content hash, compiler/toolchain identity, and each resolved script dependency artifact | Bind against only the recorded host and dependency set; missing, conflicting, unavailable, or stale artifacts produce a typed refusal without inventing a classpath | `#standalone-script-refusal` proves refusal before compiler launch and no semantic/edit authority; dependency binding is unavailable |
| Host-provided implicit receivers | Exact receiver set and order for the recorded host/template | Prove receiver precedence, nested receivers, DSL-marker restrictions, shadowing, overloads, unresolved names, and ambiguity; refuse any result that cannot bind uniquely | `#standalone-implicit-receiver-refusal` verifies refusal before compiler launch with no receiver binding or semantic output; the family remains refused |
| Script or environment change | Prior script hash, host/template identity, compiler/toolchain identity, and dependency evidence | Script, host, toolchain, or dependency changes invalidate prior results with a typed stale/refusal code; no lexical fallback is returned | `#standalone-script-change-refusal` changes the declared dependency version, verifies a different snapshot hash, and repeats typed refusal; semantic-result cache invalidation remains unqualified because no semantic result exists |

### Gradle Kotlin DSL qualification matrix

| Matrix case | Context evidence required | Acceptance when support is proposed | Current evidence/status |
|---|---|---|---|
| `settings.gradle.kts` | Exact Gradle version, settings-script kind/path/hash, settings and plugin-management inputs, plugin-resolution evidence, and host receiver set | Bind only settings-script references for this exact host; unknown plugin/build context refuses without executing the script or resolving plugins implicitly | `#gradle-settings-script-refusal` proves auxiliary host-input capture, exclusion from Kotlin/JVM source ownership, and refusal; DSL binding is unavailable |
| Project `build.gradle.kts` with generated accessors | Exact Gradle version, project-script kind/path/hash, applied plugin set, version catalogs, resolved plugin/build classpaths, and generated accessor inputs | Resolve accessors against the exact generated set; dynamic or unqualified references refuse when binding cannot be established | `#gradle-project-script-refusal` proves refusal for a plugin-accessor shape and denied build execution/network; accessor binding is unavailable |
| Host receivers and nested DSL scopes | Exact receiver chain, precedence, and DSL-marker metadata for settings/project scripts | Prove nested receiver lookup, shadowing, overload selection, DSL-marker restrictions, unresolved names, and ambiguity independently for each host kind | `#gradle-host-receiver-scope-refusal` exercises two outline cases, one each for settings and project DSL, and verifies exact auxiliary capture plus refusal; receiver semantics remain unavailable |
| Build-context invalidation | Gradle/toolchain identity, settings/build hashes, plugin set and versions, version-catalog state, and generated accessor evidence | Changes to scripts, settings, plugins, catalogs, toolchain, Gradle version, or generated accessors invalidate prior results with a typed stale/refusal code; no lexical fallback is returned | `#gradle-build-context-change-refusal` changes settings-repository and project-plugin-version inputs, verifies a new snapshot hash, and repeats typed refusal; invalidation of semantic results remains unqualified because no semantic result exists |

Unknown script definitions, unsupported host/toolchain versions, arbitrary
unqualified plugin builds, and incomplete generated-accessor or dependency models
must remain typed refusals. Gradle model discovery for these cases must not run
script bodies, plugin resolution, or build tasks; an explicitly authorized
`project.sync` remains a separate build-tool contract.

The generic-script and Gradle-DSL capabilities remain `unavailable`/`refused` until
their respective required matrix cases have qualified evidence. Any later partial
support must be advertised only for its exact qualified script/host/toolchain/plugin
context; generic script evidence must not imply Gradle DSL support, Gradle DSL evidence
must not imply arbitrary script support, and neither may imply support for arbitrary
Gradle builds.

Script analysis is read-only and must not execute the script body or build tasks.
Any authorized Gradle model synchronization remains the separate build-tool
`project.sync` contract. Script semantics do not establish Java module/source
ownership or replace the language-neutral Build Model used by M2; the IDE consumes
RefactorKit and build-tool context rather than maintaining a parallel project
semantic model. No script mutation authority is implied by this acceptance.

## Non-claims

No general Kotlin Analysis API session, IDE formatter, coroutine/control-flow reasoning, extension/suspend mutation, delegated-property transformation, arbitrary JVM annotation semantics, `expect`/`actual` linking, Android variant resolution, Multiplatform source-set linking, compiler-plugin execution, generated-code ownership, or framework-aware string/configuration update is claimed.
