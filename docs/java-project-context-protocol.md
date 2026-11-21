# Java project context, API 0.2

Authority: the [request schema](api-0.2-project-context-request-schema.json),
[v2 response schema](api-0.2-project-context-v2-schema.json),
[v3 response schema](api-0.2-project-context-v3-schema.json),
[v4 response schema](api-0.2-project-context-v4-schema.json),
[v5 response schema](api-0.2-project-context-v5-schema.json),
[v6 metadata response schema](api-0.2-project-context-v6-schema.json),
[v7 response schema](api-0.2-project-context-v7-schema.json) and
[daemon catalogue](api-0.2-contract.json) describe the experimental wire contract.
The [project-context feature](../features/java-effective-project-context.feature) owns source
association acceptance; [ARC42](arc42/appendix-requirements.adoc) owns integrated
architecture/status. The v6/v7 schemas and metadata-only behavior are described below;
the closed v2-v5 property sets remain unchanged. The SDK API-profile value27 is qualified separately below. This satellite describes the current
delivery and its boundaries.

The endpoints are `project.context`, `project.context.aggregate`, and the explicitly
versioned `project.context.v4`, `project.context.v5`, `project.context.v6` and
`project.context.v7` over JSON-RPC 2.0 NDJSON. Pair-only `project.context` requests retain
`schemaVersion: "java-project-context-v2"`; requests with an explicit `providerId`
return `schemaVersion: "java-project-context-v3"`. `project.context.v4` requires
`providerId` and returns `schemaVersion: "java-project-context-v4"`. `project.context.v5`
also requires `providerId` and returns `schemaVersion: "java-project-context-v5"`.
`project.context.v6` requires `providerId`, `moduleId` and `sourceSetId` and returns
`schemaVersion: "java-project-context-v6"` for the explicit metadata-only boundary below.
`project.context.v7` uses the same exact selectors and returns
`schemaVersion: "java-project-context-v7"` for the Maven test-JVM fact and the
owner-only source association boundary described below.
All use
`status: available | partial | refused`.
It returns complete bounded metadata or a typed refusal, with no source writes,
build/plugin execution, credentials loading or implicit network opt-in.

Open the workspace with `project.open`. Its Java options are:

| Parameter | Meaning |
|---|---|
| `root` | Workspace path; required. |
| `mavenRepository` | Optional local repository path; defaults to `~/.m2/repository`. This does not load Maven settings. |
| `activeMavenProfiles`, `inactiveMavenProfiles` | Optional arrays of profile IDs; mutually exclusive, at most 64 total. |
| `resolveDependencies` | Optional boolean, default `false`; anonymous Maven network access requires explicit `true`. |

Provider discovery is automatic. SDK selection is explicit **per `project.context`
request**, rather than a stored `project.open` SDK setting.

| `project.context` parameter | Meaning |
|---|---|
| `expectedSnapshotHash` | Required SHA-256 from `project.open` or refreshed project state. |
| `sdkHome` | Absolute configured JDK home. Omitting it returns `java.context.sdkRequired`. |
| `expectedContextHash` | Optional previously returned context hash; pins SDK and ordered build environment evidence. |
| `moduleId`, `sourceSetId` | Optional exact pair selecting module/source-set scope. It must resolve to one provider row unless `providerId` is also supplied. |
| `moduleId`, `sourceSetKind` | Alternative selector for one source-set kind within a module. The kind must match exactly one row; zero matches return `java.context.sourceSetUnavailable`, multiple matches return `java.context.sourceSetKindAmbiguous`. |
| `providerId` | Optional provider selector, valid with `moduleId` and exactly one of `sourceSetId` or `sourceSetKind`. It selects within the exact provider and returns v3. |
| `sourceRelativePath` | Optional path relative to a declared source root, such as `pkg/Runner.java`. It is not a workspace-relative path. |
| `symbol` | Optional fully qualified source type, including secondary types whose filename differs from the type name. |
| `requiredFacts` | Optional unique list drawn from `source-root-provenance`, `compiler-options`, `artifact-coordinates`, `annotation-processors`, and `jpms`. Acquisition refuses with a typed code if any in-scope source set cannot prove every requested fact complete. |

`sourceSetKind` accepts `main`, `test`, `integration-test`, or `custom`. It resolves
through the provider's typed model and returns the concrete source-set ID in
`selectedSource`; it does not authorize Maven goal execution. To request the separate
Failsafe declaration DTO, call `project.context.v4` with the same context parameters
and a `providerId`.

For source/target analysis with a selected SDK, `java.context.compilerOptionsIncomplete`
indicates that effective compiler options are incomplete. The Maven compiler
plugin bound by the default lifecycle can vary with the Maven distribution.
With an omitted `sdkHome`, project context returns `java.context.sdkRequired`.

## Aggregate context after Gradle sync

After `project.sync` has explicitly evaluated a Gradle workspace, call
`project.context.aggregate` with `expectedSnapshotHash` and `sdkHome` (and, optionally,
`requiredFacts`). It returns the full provider/module context as
`java-project-context-v3`, without a selected source. Each Gradle module includes
its workspace-relative `buildRoot` and Gradle `projectPath`, so composite projects
remain uniquely addressable before the caller selects a provider or source set.
The endpoint is read-only and does not execute Gradle. It accepts no module, provider,
source-set, source-path or symbol selector. Pair-only `project.context` remains v2;
provider-selected `project.context` and `project.context.v4` keep their current shapes.
`project.context.v5` is the provider-qualified route for the additive complete-JPMS
schema described below.

## Failsafe declaration context, v4

`project.context.v4` reuses the project-context request fields and requires
`providerId`. Each returned source-set row contains a strict `mavenFailsafe` fact:
availability/refusal code and, for a unique associated execution, declared plugin
identity/version, execution id, goals, phase, profile origin, explicitly configured
selectors and report settings. Values are read from the effective Maven model only.
An absent declaration is `not-declared`; multiple executions are `ambiguous`; an
unresolved expression or unsupported configuration is `partial` or `unavailable`
with a typed code. Non-Maven providers report `not-applicable`.

The four-case Story receipt is
`.refactorkit/runs/java-maven-failsafe-context-v4-r001/`. It verifies configured,
duplicate, unresolved and inactive-profile cases against the closed v4 schema. The
DTO describes declarations only: it does not claim that Maven ran a task or test,
that reports exist, or that plugin defaults apply. Parent inheritance and all plugin
configuration merge combinations are not yet qualified. Routes `project.context`
and schemas v2/v3 remain unchanged; consumers must opt into the separately versioned
v4 route and schema.

The [module-bound Maven Failsafe acceptance scenario](../features/java-effective-project-context.feature)
adds a two-module `app`/`library` case. Each module declares its own active-profile
Build Helper integration-test root and a distinct Failsafe execution, selector and
report directory. Provider-qualified v3 and v4 requests select both module/source-set
pairs, verify root and output ownership, and keep the app's library dependency limited
to the library main output. Neither module receives the other's integration or test
roots through analysis/classpaths. This is provider projection evidence only: the
scenario does not execute Maven or Failsafe and does not qualify IDE task selection.

## Effective Maven test-JVM settings (schema v7)

`project.context.v7` adds a closed `mavenTestJvm` fact and leaves v2-v6 unchanged.
For source sets with proven ownership, it exposes direct same-POM Surefire settings
on `test` and direct same-POM Failsafe settings on a provider-declared
`integration-test` source set. It also supports one bounded inheritance shape: a
literal direct Surefire/Failsafe plugin declaration in the immediate workspace
parent POM, with no matching child declaration, parent chain, profile or
`pluginManagement` merge. The effective child model must retain exactly that
plugin/version. Failsafe executions inherited from that parent are associated
only with child integration-test roots whose ownership is independently proven.
An integration-test source set can also receive complete JVM facts from one
same-POM Failsafe profile. The effective model and the source-root declaration
must identify that profile and agree with its literal plugin version, named
integration-test execution, phase, goals and JVM settings. A competing base,
inherited or managed declaration keeps the facts partial. Origins identify the
profile POM layer.
Each execution reports its plugin version, execution, goals/phase, mode,
origin-bearing `forkCount`/`forkMode`/`reuseForks` and `argLine` settings,
completeness, and a typed refusal. Parent-file origins are explicit. Late tokens
such as `@{argLine}` are preserved. The provider does not execute Maven goals or
lifecycle code.

For Maven `jar` packaging, v7 also identifies the built-in Surefire lifecycle
binding when the POM has no direct Surefire declaration: it reports the structured
`default-test` execution, goal `test`, and phase `test`. Plugin defaults may complete
the JVM fact when the effective model agrees with a literal version in a direct
declaration, the qualified local Failsafe profile, or one workspace-contained
immediate parent `pluginManagement` entry that contains only that version.
RefactorKit then reads the
matching local plugin JAR without executing it. Its `META-INF/maven/plugin.xml`
must match the exact group, artifact, version and goal; the JAR is a no-follow regular
file within the configured local repository, and its SHA-256 is bound as an effective
model input in the project snapshot. The projected `forkCount`, `reuseForks` and
`argLine` settings carry that descriptor origin. The same rule can complete a direct
Failsafe execution whose version comes from that bounded parent declaration.
An explicitly present but nonliteral module `<version>` (for example
`${surefire.version}`) never falls back to the parent origin, even if the effective
value happens to equal the parent's literal version.

An empty `argLine` is complete only when the descriptor's configuration contains
the exact `${argLine}` user-property expression, the effective POM has no `argLine`
property or explicit plugin setting, and no active JaCoCo `prepare-agent` or
`prepare-agent-integration` execution contributes to it. An override or known
JaCoCo contributor leaves `argLine` unavailable and the overall fact partial, even
when fork defaults are known. Missing or mismatched local descriptors, absent or
unresolved versions, unsupported inheritance/profile layers, and incomplete plugin
settings keep `java.context.mavenTestJvmConfigurationIncomplete`. No Maven settings,
credentials, network access, plugin goals or lifecycle code are used. Other plugin
property mutation is outside this bounded qualification and must be considered by
the consumer before it constructs a launch command. Packaging other than `jar` does
not receive an inferred default Surefire binding.

`-DargLine` is a Maven-global user property. A `verify` invocation can apply it to
both Surefire and Failsafe, and `-pl <module> --also-make` can expose upstream
reactor modules to the same option. A consumer that injects a global test-JVM
option must therefore require complete facts for every Surefire/Failsafe test
source set in the invocation's reactor closure. If any applicable fact is partial,
unavailable or ambiguous, omit the global option or refuse the requested JVM
override. The v7 response projects module and source-set facts for every module in
the captured reactor under `modules`; it is not limited to the requested module.
This API does not record the selected Maven executable/version, so it cannot
complete the implicit Surefire lifecycle plugin version from an installation
identity that the caller did not provide.

This is deliberately a bounded model-derived view, not a claim that RefactorKit has
reconstructed every effective Maven test process. The inherited direct-parent
shape is limited to one workspace-contained POM with no parent of its own, no
matching profile or `pluginManagement` declaration, no child Surefire/Failsafe
declaration, and no inherited-false plugin/execution. Child overrides, multi-level
or external parents, merge combinations, profile layers, unresolved expressions,
duplicate executions, incomplete plugin versions, and settings that cannot be
associated with a declared source set remain partial, ambiguous, or unavailable.
The original six-case Story still covers direct declarations and fail-closed
default bindings; the separate six-case Story covers versioned local descriptor
defaults, inherited Failsafe metadata, missing parent version, the JaCoCo
contributor, a Failsafe declaration without a matching descriptor, and an explicit
property version whose value matches parent management but has no literal parent
origin. A separate two-case Story covers origin-bound Surefire and Failsafe JVM
configuration inherited from the supported direct-parent shape. All three Stories
assert unchanged project bytes and no Maven lifecycle execution. Exact scenario
receipts and inventories are under
`.refactorkit/runs/maven-parent-test-jvm-r001/`,
`.refactorkit/runs/maven-test-jvm-plugin-defaults-r004/`,
`.refactorkit/runs/maven-test-jvm-plugin-defaults-r003/`, and
`.refactorkit/runs/maven-test-jvm-context-v7-r001/`.

## Complete JPMS directive context, v5

`project.context.v5` requires the exact `providerId`, `moduleId`, and source-set
selector. Its closed schema retains the earlier source-set and Maven Failsafe facts
and adds the standard module directives `opens`, `uses`, and `provides` beside
`requires` and `exports`. Qualified opens targets, used service types, and provider
implementations are deterministic, snapshot-bound descriptor facts. When `symbol`
or `sourceRelativePath` is supplied with `requiredFacts: ["jpms"]`, the same v5
request performs configured JDT analysis and returns the selected source and its
binding evidence.

Schemas v2-v4 remain closed and do not gain those fields. Without a required JPMS
request they preserve their older response shape and mark a descriptor containing
any v5 directive `partial`. When a v2-v4 request requires JPMS facts for such a
descriptor, acquisition refuses with `java.context.jpmsDescriptorIncomplete`;
consumers that need these directives select v5 explicitly.

The `REQ-JAVA-PROJECT-CONTEXT-001` Stories
`maven-jpms-v5-publishes-standard-module-directives` and
`maven-jpms-v5-semantic-analysis-binds-uses-and-provides` verify the closed
projection, JDT binding and read-only context acquisition. The focused Kotest Spec
checks the typed ECJ `IBinaryModule` projection. The legacy v2-v4 incomplete-descriptor
refusal and positive Maven JPMS Stories run separately. Receipts are under
`.refactorkit/runs/java-jpms-v5-standard-directives-r001/` and
`.refactorkit/runs/java-jpms-v5-semantic-r001/`. This qualifies the Maven v5 path;
it does not claim complete JPMS qualification across every Java release or a
separate Gradle v5 acceptance matrix. Maven JPMS projects using
`maven-compiler-plugin` 3.11 or newer also report its effective `useModulePath` default.
For a named module at Java 9 or later, the resolver treats the already ordered Maven
compile classpath as module-path input only when every JAR has complete resolved-artifact
identity and fingerprint evidence. Explicit plugin configuration takes precedence;
unsupported plugin versions, invalid values, or incomplete artifact ownership remain
partial and refuse required JPMS facts.

Example params after opening a reactor:

```json
{
  "expectedSnapshotHash": "<hash from project.open>",
  "sdkHome": "/usr/lib/jvm/java-21-openjdk",
  "providerId": "maven-effective-v1",
  "moduleId": "app",
  "sourceSetId": "main",
  "sourceRelativePath": "pkg/Runner.java",
  "symbol": "pkg.Peer"
}
```

Maven `buildEvidence` includes every provider-captured reactor POM as
`declaration-file`, including parent and aggregator modules with `packaging=pom`
and no Java source roots. Parent/effective-model inputs outside that reactor set
retain `effective-model-input`; imported BOMs retain `imported-bom` evidence.
Each path/kind pair has a captured content fingerprint. Clients use those exact
public inputs to guard unsaved build-file drafts, without reconstructing Maven
inheritance. A saved input edit changes snapshot/context authority and requires
reacquisition. The existing closed schema and hash algorithms are unchanged.
Acceptance/status authority: [ARC42 Maven input evidence](arc42/appendix-requirements.adoc#maven-complete-build-input-evidence-2026-11-14).

The model contains `providers`, `modules[].sourceSets`, `sdk`, `buildEvidence`,
`analysis`, and an optional `selectedSource`. The provider/module projection is
**Java-scoped**: it includes Maven, Gradle, Java-named providers and providers
with explicit Java source-set facts/facets. Unsupported relevant Java providers
still make Java metadata partial. An unrelated TypeScript/JavaScript provider,
including a missing or broken configuration, does not decide Java completeness
or compiler environments. Source ownership selects this Java family before
applying the longest-root rule, including flat Java/TypeScript shared roots.

The snapshot remains the original global workspace snapshot. `project.summary`
continues to expose the other providers and their failures; global source,
provider and classpath evidence still participates in snapshot/context authority.
Changing report JavaScript therefore invalidates an old snapshot as before.
No IDE-specific report directory is excluded or silently removed from inventory.
This isolation does not qualify another language's operations or cross-language
mutation safety. The native Story uses report-shaped assets; actual TestNG
execution and its consumer acceptance are separate.

Evidence: `.refactorkit/runs/java-context-mixed-inventory-r001/`, linked to the
[ARC42 provider-isolation checkpoint](arc42/appendix-requirements.adoc#java-context-language-provider-isolation-2026-11-14).
Its immutable candidate has ZIP SHA256
`50b75129d5d1752d5d4e25b588bd9131e9106921682b8a49c12cf3ed60b53d06`
(93,378,347 bytes/222 files, app 0.7.0/API 0.2). Final local qualification passes
80 behavioral cases/320 steps, Java325 with five existing skips, daemon64 and C490.
The old118/f083/new comparison preserves identical global snapshot hashes;
the new candidate returns available Java context and `demo.Main` while retaining
TypeScript status in the global summary. Consumer TestNG acceptance, independent
release review and full M2/0.8 remain pending.

Source sets expose declared/materialized
roots as captured by the provider, generated roots, outputs, selected analysis roots,
ordered `compileClasspath` / `runtimeClasspath` entries and dependency scopes.
Classpath entries retain provider/module/source-set ownership, dependency scope and
captured fingerprint where available. Preserve array order; do not sort these paths.
Workspace paths are relative, while external repository/SDK paths remain absolute.
The association is per `sourceSets[]` row: its `sourceRoots` and `compileClasspath`
belong together under the containing provider/module, and each classpath entry repeats
that provider/module/source-set identity. Consumers must select the exact owning source
set for a file and must not union classpaths across source sets or modules. A partial or
refused context cannot establish this authority. `selectedSource`, when requested,
repeats the selected provider, module, source set and exact source root in v3. The v2
shape retains its original fields for pair-only compatibility.

An explicitly synchronized Gradle composite qualifies every Java project under the
opened workspace root. In v3, each Gradle `modules[]` row includes `buildRoot` and
`projectPath`: both are relative to the opened workspace, with `buildRoot: "."` for
the root build and the included-build directory for a composite member. `root`,
`sourceRoots`, `generatedSourceRoots`, and `outputDirectories` use the same workspace
base. A Gradle project's evaluated `projectDir` may sit outside its `buildRoot`, and
source/output directories may sit outside both; the required boundary is containment
within the opened workspace. The containing module row ties those paths to `buildRoot`
and the logical `projectPath`; `projectPath` is never converted into a filesystem path.
`sourceRelativePath` in the request remains relative to the selected source root; the
returned `selectedSource.path` is workspace-relative. Repository and JDK classpath
entries can remain absolute. Project classpath entries and
`moduleDependencies[].moduleId` carry the exact owning module identity.

For a composite, `modules[].id` is stable from the workspace-relative build root and
Gradle project path:

```text
gradle-<lowercase SHA-256 of UTF-8(buildRoot)>:<projectPath>
```

For example, the two projects whose Gradle path is `:lib` in `.` and `direct` have
different IDs, while both retain `projectPath: ":lib"`. The root, direct, nested and
unreferenced included Java builds are all reported once, including projects whose
custom `projectDir` is elsewhere in the workspace. Every project dependency edge
and classpath owner points to its exact qualified ID. Non-composite Gradle IDs retain
their existing project-path form. The v2 response shape and schema are unchanged; the
two composite identity fields are additive to v3 only.

A legacy selection using a repeated Gradle project path such as `moduleId: ":lib"`
refuses with `java.context.sourceAssociationAmbiguous`; the caller must select the exact
provider-qualified module ID from the returned rows. If any included build resolves
outside the opened workspace, `project.sync` refuses with
`gradle.sync.includedBuildOutsideWorkspace` and publishes no partial build model. A
change to captured included-build inputs invalidates the synchronized context and a
stale request refuses with `java.context.snapshotStale`. Composite coverage is part of
the existing `REQ-JAVA-GRADLE-CONTEXT-001` Story, not a new requirement. Its local
five-case acceptance and aggregate-v3 identity route pass on Gradle 9.6.1/JDK 21.
A separate repeated-sync Story selects an included module through its exact v3 ID,
syncs again against the unchanged source snapshot, then verifies that the second
aggregate response and `project.summary` preserve all five qualified identities.
The receipt and responses are under
`.refactorkit/runs/gradle-composite-repeat-sync-r001/`. A native IDE pass on Gradle
9.8.0/JDK 21 is reported in ARC42. The same Story has also passed against the
standard packaged daemon over NDJSON (one active scenario, five steps; Gradle 9.6.1,
embedded Java 21.0.12.1). The package tree SHA-256 is
`02d167eb3f93698258c9b528a8bd3d60960c5394258062a02683b1a68e9525d1`, and its
daemon JAR SHA-256 is `4f5afe008019dc8e929b2a47e97723219211d8751ee681923e50a50b51deed6f`;
the receipt and raw responses are in
`.refactorkit/runs/gradle-composite-repeat-sync-r002/`. This identifies the locally
qualified RefactorKit package. The daemon artifact loaded by the IDE and its consumer
refresh behavior remain unverified; Gradle 9.8/JDK 25 project/task/launch/coverage
mapping remains a consumer acceptance gate.

For Gradle project dependencies, a runtime classpath entry may name the target
module's main JAR instead of a source-set output directory. RefactorKit binds that
entry to the dependency module's `main` source set, not to the requesting source
set; its `moduleId` and `sourceSetId` therefore describe the artifact owner. The
existing Gradle context feature covers this on an offline Kotlin DSL multi-module
fixture with local Gradle 9.6.1/JDK 21 (four steps). It verifies both `:app/main`
and `:app/test` compile/runtime ownership and byte-preservation. This does not run
the fixture's build/test tasks, qualify Gradle 9.8, or establish Kotlin script
semantics. Receipt and public acceptance results are under
`.refactorkit/runs/java-gradle-kotlin-dsl-context-r001/`.

The latest IDE consumer report says a native Gradle 9.8 GUI lists the included
`app`/`library` projects without exposing `buildRoot`/`projectPath`, blocking
selection before Run. That reopens the existing native composite consumer
acceptance; it does not create another requirement. Since no loaded-daemon identity
or raw `project.summary` was supplied, the field-loss boundary remains unknown.
The current status and authority boundary are integrated in
[ARC42](arc42/appendix-requirements.adoc).

A non-generated Maven Build Helper `add-source` declaration outside the module's
existing main/test roots is exposed as a `custom` source-set row. Its
`sourceRoots` entry and matching `sourceRootEvidence` record are returned together;
the evidence retains `origin: "build-plugin"`, the declaring POM, provider, plugin,
execution, goal, phase, profile when present, and fact availability. For example,
`unrelated/src/extra/java` remains owned by the Build Helper source set rather than
being inferred from its path or copied into an IDE-side Maven model. An IDE that offers
`moveSourceRoot` must enumerate the roots from the selected provider response and may
offer review only for those declared roots. Requiring `source-root-provenance` makes
stale, incomplete or ambiguous ownership fail closed; stale workspace evidence returns
`java.context.snapshotStale`, and duplicate ownership returns
`java.context.sourceRootProvenanceIncomplete`. Context acquisition only reads the
effective descriptor model; it does not execute the Maven plugin.

The response's aggregate `status` reports build-model metadata availability. Optional
facts such as JPMS can remain `partial` while the metadata response is `available`;
requests requiring an incomplete fact refuse with its typed error. Consumers must
preserve and project this exact row and inspect its `sourceRootEvidence` instead of
inferring source-root completeness from unrelated optional facts. Native IDE run
`run-0SBBa7` records the received Maven context v2 rows
for both declared Build Helper `CUSTOM` roots with build-plugin provenance and passes
Maven/Gradle source-root preview, Apply, rollback and exact restoration on the local
source-built daemon (SHA-256
`21dcd303596607fd8287d30f0a674a925917856215f10dbb32c8e6e725262219`). The
applied-source Gradle fixture also passes offline `gradle test`. Its trace is
`test-results/native-desktop-story/run-0SBBa7/artifacts/java-project-context-snapshot-trace.jsonl`
in the IDE workspace. This closes that Linux/JDK 21 consumer cell only; broader
layouts and released packages remain open under
[ARC42's existing project-context requirement](arc42/appendix-requirements.adoc#JavaM2ProjectContextReconciliation),
not a second requirement.

JDT compilation context follows those provider-declared visibility facts. A Maven
main source context combines its conventional `MAIN` row with every same-module row
whose `visibility` is `main`; a test or integration-test context also combines all
same-module rows whose `visibility` is `test`. A dependent module contributes its
main-visible roots. The selected provider/module/source-set/path association remains
exact; combining roots for compilation does not change source ownership or infer a
new root. Discovery and diagnostics do not run Maven or its plugins.

Provider-qualified selection is implemented under the existing
`REQ-JAVA-PROJECT-CONTEXT-001`. Supplying the request's optional `providerId` selects
that exact provider row and produces v3, whose `selectedSource` echoes the provider
together with the exact module, source set and path. A legacy pair-only selector
remains v2-compatible only when it matches one
provider/module/source-set row; a collision refuses with
`java.context.sourceAssociationAmbiguous` and no `selectedSource`. Consumers must
never bind the first matching row. The feature exercises equal Maven/Gradle module and
source-set IDs, both provider-qualified selections, and the ambiguous legacy refusal.
The strict v2 schema is unchanged; the v3 schema closes the added provider field.

Schema v2 adds complete per-root provenance records; compiler-option vectors with
origin and redaction markers; Maven module GAV and resolved artifact coordinates,
scope, classifier, transitivity and fingerprints; annotation-processor mode, names,
options, declared paths and resolved dependency coordinates; and JPMS module-path
entries plus snapshot-bound `module-info.java` requires/exports facts. Each optional
fact carries an availability state. Top-level `status: available` means supported
build-model metadata was acquired; it does not certify every optional fact or imply
that the optional `analysis` field is present. A metadata-only request with a partial
JPMS descriptor remains available and omits JDT analysis when the descriptor cannot
be faithfully projected. A request that selects source for semantic analysis still
requires a complete analysis environment and refuses incomplete JPMS evidence.
Callers that depend on any optional fact must name it in `requiredFacts`.
The provider refuses missing, stale, partial or ambiguous required evidence instead
of inferring Maven ownership from JAR names or guessing a source-set association.
Fact failures use `java.context.sourceRootProvenanceIncomplete`,
`java.context.compilerOptionsIncomplete`, `java.context.artifactCoordinatesIncomplete`,
`java.context.annotationProcessorContextIncomplete`, or a more specific ambiguity/path
code. Stale project inputs return `java.context.snapshotStale`; stale SDK or context
facts return `java.context.contextStale`. A complete named-module context supplies
module-path entries separately from the ordinary classpath and gives JDT the owning
snapshot-bound `module-info.java`. Semantic source association can then return a JDT
binding; `requires` and `exports` govern readability and package access. Missing or
unmodeled directives, incomplete module-path inventory, stale inputs, or ambiguous
ownership still refuse or remain partial with a typed context code.
Dependency binary-to-source mappings remain handled by the separate
`java.dependencySource` lookup/read contract below.

For evaluated Gradle Java source sets, JPMS context also follows the effective
`JavaCompile.modularity.inferModulePath` setting. When Gradle enables inference and the
source set contains `module-info.java`, the provider separates modular compile
classpath entries (modular JARs and module output directories) from the ordinary
classpath. Descriptor ownership is scoped to the source set that contains the descriptor;
a main-source `module-info.java` is not copied into test or custom source-set JPMS facts.
An explicit compiler `--module-path` remains authoritative, and an explicit Gradle
inference opt-out does not synthesize a module path. The inferred paths remain bound to
the same evaluated task, classpath inputs and project snapshot. If Gradle's effective
setting or a candidate path cannot be inspected completely, required JPMS context is
refused instead of treating the entire compile classpath as a module path.

The bounded Gradle annotation-processor fact uses the existing v2
`annotationProcessors` records. After explicit sync, a selected main source set with a
local file-based `JavaCompile.options.annotationProcessorPath` reports its
workspace-relative path, processor names read statically from the JAR service
descriptor, and the input fingerprint through `buildEvidence`. The context route does
not compile sources or load/execute processor classes; the acceptance fixture proves
this by checking that a processor-generated marker is absent. Provider-backed,
custom-task, argument-override, or incomplete path capture remains partial and
refuses when the fact is required. File dependencies have no resolved dependency
coordinates. Receipt: `.refactorkit/runs/java-gradle-annotation-processor-r001/`.

The bounded Maven counterpart accepts an explicit compiler-plugin
`annotationProcessorPaths` entry when its coordinates identify one workspace reactor
module with JAR packaging, no classifier, a contained and already materialized output
directory, and no compile/provided/runtime/system dependencies. It reports that
module's output directory as the processor path and a resolved `classes` artifact with
the reactor coordinates; the path is bound by `buildEvidence`. A materialized
annotation-generated source root is also exposed with generated-root provenance.
Discovery reads the effective model and captured output only: it does not run Maven,
the compiler, or a processor, and it does not prove that the output was freshly built
or that a processor actually generated the captured source. Duplicate coordinates are
ambiguous; missing/unsafe output is unavailable; a processor module with dependencies
is partial because its runtime closure is not modeled. Requiring an incomplete
processor fact still refuses with its typed code. This extends the existing
`REQ-JAVA-PROJECT-CONTEXT-001`; receipt and focused report are under
`.refactorkit/runs/maven-reactor-processor-r001/`. The [ARC42 appendix](arc42/appendix-requirements.adoc)
records the acceptance boundary.

A second Story covers the workspace transition: when the reactor processor output
and generated source are absent, v7 returns the typed unavailable processor-path fact
and an empty generated-source-root list. After those outputs materialize, the same
daemon session explicitly reopens the project and repeats the provider-qualified v7
request; the new snapshot reports the complete processor path and generated root. This
proves explicit reopen/reacquisition in the CLI protocol, not watcher-driven refresh or
the separate native IDE M2 acceptance. The Story does not run Maven or a compiler.
Receipt and focused report are under
`.refactorkit/runs/maven-reactor-processor-refresh-r001/`.

The legacy v2-v4 JPMS wire is deliberately limited to module-path entry path, discovered
module names and fingerprint, plus descriptor path, module name, `open`, `requires`,
qualified/unqualified `exports`, and source-content SHA-256. The resolver treats any
directive outside `requires` and `exports` as unprojected for these schemas: it retains the supported
descriptor facts, reports JPMS as `partial` with
`java.context.jpmsDescriptorIncomplete`, and refuses `requiredFacts: ["JPMS"]` with
that code. The bounded Maven Story covers `opens`, `uses`, and `provides` without
changing the v2 wire; receipt `.refactorkit/runs/java-jpms-descriptor-completeness-r001/`.
Changing `module-info.java` after the project snapshot is opened also refuses with
`java.context.snapshotStale` before module facts are published; receipt
`.refactorkit/runs/java-jpms-stale-descriptor-r001/`. Changing a
compiler-configured Maven module-path JAR after open returns the same stale-snapshot
refusal before module facts are published; receipt
`.refactorkit/runs/java-jpms-stale-module-path-r001/`. These stale-input checks do not
qualify a complete module-path environment. Positive Maven and Gradle Stories compile
real named dependency JARs and verify
the public v2 JPMS fact is complete, including exact module name and snapshot-bound
file fingerprint, descriptor identity, `requires` modifiers, qualified exports and
content hash. The Gradle fixture uses literal `JavaCompile.options.compilerArgs`
module-path input; acceptance exposed missing Gradle compiler module-path
`ClasspathEvidence`, now captured by the scanner. Required-JPMS requests for those
older descriptors are not refused; those metadata-only receipts historically recorded overall context as `partial`
before JDT consumed the separate module path. Receipts:
`.refactorkit/runs/java-jpms-positive-module-path-r001/` and
`.refactorkit/runs/java-jpms-gradle-positive-module-path-r001/`. A separate Gradle
Story changes the compiler-configured module-path JAR after explicit sync and verifies
`java.context.snapshotStale` before module facts are published; receipt
`.refactorkit/runs/java-jpms-gradle-stale-module-path-r001/`. Maven and Gradle Stories
verify JDT-bound source association with one exported dependency type and one
non-exported package; the exported type binds and only the inaccessible reference
remains unresolved. Receipts:
`.refactorkit/runs/java-jpms-maven-semantic-context-r001/` and
`.refactorkit/runs/java-jpms-gradle-semantic-context-r001/`. Separate no-selection
Maven and Gradle Stories confirm a complete required-JPMS response now reports overall
`available` with JDT analysis present; receipts
`.refactorkit/runs/java-jpms-maven-context-available-r001/` and
`.refactorkit/runs/java-jpms-gradle-context-available-r001/`. Broader Maven/Gradle path
ownership and aggregation and native IDE navigation acceptance remain actionable.

Schema v2 also has no Failsafe execution/report DTO. Its `CUSTOM` or `INTEGRATION_TEST`
source-set row does not prove a Maven execution configuration or report destination.
Any future public DTO must bind declared plugin/version, execution identity/goals/phase,
profile provenance, and explicitly configured test-selector/report values to provider,
module and source set; preserve availability and origin; and avoid claims about executed
goals, generated reports or inferred plugin defaults. Because the v2 schema closes
objects against additional properties, this metadata requires an explicitly declared
versioned schema. Unsupported merges, unresolved expressions, stale evidence or
ambiguous ownership must remain unavailable or return a typed refusal when required.

Consumer-reported native IDE evidence under `REQ-JAVA-PROJECT-CONTEXT-001` confirms a
Failsafe target executes in its intended module/context on a two-module Maven reactor,
but a `project.context` request selecting kind `INTEGRATION_TEST` does not resolve. The
consumer attributes this to missing selector capability/data in the available Maven
model. The evidence applies only to `INTEGRATION_TEST`, does not qualify other
selectors, and does not establish a further root cause; it remains distinct from the
provider-side classification Story.

Native IDE rerun — 2026-10-21: the Linux limitation story
`pnpm test:story:req-ide-java-project-context-native-001:maven-failsafe-context`
passed against the Oct 21 release/no-bundle IDE and current source-built RefactorKit
daemon. Synchronized Maven `app` still exposed only `main` and `test`, with
`synchronizedMavenIntegrationTestSourceSetUnavailable=true` and
`synchronizedMavenFailsafeTestTaskRan=false`. No task or build output was produced;
the dirty draft and project inputs were preserved, the window closed normally, and
cleanup found no process survivors. This confirms the consumer gap in the current
checkout; it does not qualify synchronized Failsafe execution or identify a cause.
Keep the same gate: typed provider/module/source-set-bound Failsafe execution and
selector facts plus native task-selection acceptance are required before marking
`INTEGRATION_TEST` execution available.

Provider-qualified source-set-kind selector — 2026-10-22: `project.context` now accepts
`providerId + moduleId + sourceSetKind` when the kind resolves to exactly one source-set
row. The response echoes its concrete source-set ID; duplicate rows refuse with
`java.context.sourceSetKindAmbiguous`, an absent kind refuses with
`java.context.sourceSetUnavailable`, and cross-provider ambiguity still requires
`providerId`. The three-case Story passes for a unique Maven `INTEGRATION_TEST` row,
a duplicate-row refusal, and an unavailable kind when the Failsafe profile is inactive
(12/12 steps); receipt `.refactorkit/runs/java-failsafe-source-set-kind-r002/` supersedes
the earlier two-case receipt. The selector gap is closed for the bounded provider-side
case. The v4 Story separately qualifies bounded Failsafe declaration metadata; native
IDE task selection, coverage and report mapping remain unqualified.

Gradle compiler-option projection is implemented and validated for the bounded
`REQ-JAVA-PROJECT-CONTEXT-001` route. After explicit sync, the exporter preserves
the ordered literal `JavaCompile.options.compilerArgs` on each source set and binds
each record's origin to its compile-task path and source-set ID. It redacts sensitive
argument values before writing the temporary export. Completeness requires a bounded
argument inventory, a readable empty `compilerArgumentProviders` inventory, and only
Gradle's default Java compile task action. RefactorKit never calls
`CommandLineArgumentProvider.asArguments()` and does not execute compile tasks during
metadata export. Providers, custom task actions, unavailable inventories or exceeded
limits mark the fact partial with `java.context.compilerOptionsIncomplete`; a request
requiring `compiler-options` refuses with that code.

The public-wire Story passes four cases for preview enabled/absent, a non-empty
argument-provider inventory and sensitive-value redaction (16/16 steps). A separately
receipted Story passes for a custom `doFirst` action (4/4 steps), proving the action
does not run and its source-set fact refuses as partial. Receipts and pre/post glue
inventories are in `.refactorkit/runs/java-gradle-compiler-options-r001/` and
`.refactorkit/runs/java-gradle-task-action-r001/`. This qualifies the bounded Gradle
Java 25 case; it adds no wire fields. Java 27 remains outside the adapter range and is
refused during sync with `gradle.sync.sourceLevelUnsupported` before context projection.
JDT LS acceptance does not extend this RefactorKit boundary.

Native IDE compiler-options report — 2026-10-23: the IDE agent reports that a
source-built RefactorKit 0.7.0 provider, Gradle 9.6.1 and JDK 21 compiled a real
`classes` task with `options.release = 21` and `--enable-preview`, but the resulting
complete `project.context` compiler-option inventory omitted `--enable-preview`.
The IDE retained its JDT LS import settings and still showed the Java 21 unnamed-
pattern diagnostic. The report does not include a hash-bound raw request/response,
selected source-set identity or package manifest, so its provider-side cause is
unknown.

The current 0.7.0 source checkout now has a separate requirement-first acceptance
identity, `REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-21-preview-option-survives-a-real-classes-build-and-context-evaluation`.
On Gradle 9.6.1/JDK 21 it first compiles an unnamed-pattern fixture with release 21
and `--enable-preview`, then performs explicit sync and reads public `project.context`.
The response reports source level 21, the ordered `-Xlint:deprecation` and
`--enable-preview` compiler arguments, `complete` availability, and the exact
`compileJava`/`main` argument origin. The Story passes at
`.refactorkit/runs/ide-gradle-java21-preview-r001/`. This verifies the current
source-built provider path; reconciling the consumer report against its exact
installed artifact and raw context response remains `actionable`. It does not
qualify JDT LS import behavior or prove that the IDE applied the reported option.

A second public-wire Story now covers the reported inherited-task shape: the root
Gradle build applies `tasks.withType(JavaCompile).configureEach` to subprojects,
the real Java 21 `:app:classes` task succeeds, and the caller selects exactly
`gradle-effective-v1/:app/main`. Current-source `project.context` returns the
ordered `--enable-preview` argument as complete with origin
`gradle-java-compile::app:compileJava:sourceSet=main:compilerArgs[0]`. The selected
scenario passes independently under
`.refactorkit/runs/ide-gradle-subproject-preview-context-r001/`. This shows that
the current exporter preserves this multi-project task/configuration shape. The
native IDE artifact still lacks a hash-bound raw request/response and selected
source-set identity, so the installed-package discrepancy and consumer mapping
remain `actionable`; no JDT LS behavior is inferred from this provider Story.

A fresh offline self-contained Linux package has now been built from the current
working source state for that IDE replay. It is at
`modules/refactorkit-cli/build/package/refactorkit`; the deterministic SHA-256 of
its 216-file tree manifest is
`2ac00500d78ff5a40e6ff7fe31a168ff51112e0ac21b36f2ea7b7ccfa331737a`. The package
launches `bin/refactorkit --help` with host `JAVA_HOME` unset. Build receipt and
per-file hashes are in
`.refactorkit/runs/ide-java21-preview-package-r001/`. This hash is shared with the
IDE agent; native replay and the exact public `project.context` request/response
are still pending, so consumer reconciliation remains actionable.

An opt-in IDE production-boundary characterization named
`managed_jdtls_reports_gradle_java27_preview_import_mismatch` reports the same
consumer-side contrast: Gradle 9.8.1/JDK 27 compiles a release-27 preview fixture,
while Buildship/JDT LS imports source level 27 with preview disabled and reports
errors. Its JDT LS/JDK/Gradle qualification paths are unset in this RefactorKit
environment, so the characterization was not rerun here; the report does not
establish a provider result or root cause. RefactorKit's typed refusal before
`project.context` remains fail-closed and publishes no false `preview-disabled` fact.

For qualified source analysis, API `0.2` v2 already has the required ordered
per-source-set `compilerOptions` records (`name`, `value`, `origin`, `redacted`),
availability and refusal fields. The bounded Gradle provider projection and its
public-wire qualification are now complete; broader Maven compiler-plugin shapes
remain separate work. Java 27 source analysis remains unavailable; it requires a
separately qualified parser/JDT and platform baseline, not just a preview boolean or
Buildship setting.

### Metadata-only current-GA context v6

`project.context.v6` is an opt-in provider-qualified request with `expectedSnapshotHash`,
`sdkHome`, `providerId`, `moduleId` and `sourceSetId`. A source selection may be included
only to receive the typed source-analysis refusal. The response preserves the provider,
module and source-set metadata and adds
`semanticAnalysis: { "availability": "unqualified", "refusalCode":
"java.context.sourceAnalysisUnqualified" }` for current-GA Java 26/27 metadata-only
contexts. With no source selection, `status` may be `available` while `selectedSource`
and `analysis` are `null`. With a source selection, the response is `refused` with
`java.context.sourceAnalysisUnqualified` and publishes no selected source or analysis
result. This contract exposes the declared release, effective compiler options and
hash-attested JDK/platform metadata; it does not qualify Java 26/27 parsing or refactoring.
The provider-specific Maven and Gradle Stories are in
[`java-effective-project-context.feature`](../features/java-effective-project-context.feature).
ARC42 owns integrated status and source-language qualification.

In v7, a Java 27 request that supplies only `sourceRelativePath` may return a
source-root ownership association when the exact provider/module/source-set has
one unambiguous owner. The response is `available`; `selectedSource` identifies
the workspace path, provider, module, source set, source root and generated flag,
with `evidence: SOURCE_ROOT_OWNERSHIP`, while `symbol` and `bindingKey` are null.
`analysis` stays null and `semanticAnalysis.availability` stays `unqualified`.
This metadata lets an integration consumer locate a file in the correct source
set; it is not parser or binding evidence. A Java 27 `symbol` request still
returns `java.context.sourceAnalysisUnqualified`, and v6 preserves its original
refusal for source selection.

`expectedContextHash` pins the context projection returned by the same schema.
When moving from a v2 acquisition to this metadata-only v7 projection, keep the
`expectedSnapshotHash` and SDK selection but omit the old v2 `expectedContextHash`:
v2 may attest complete JPMS facts that v7 intentionally marks unavailable while
Java 27 source analysis is unqualified, so the hashes can differ without a project
change. The first v7 request can select the source directly without a context pin,
or acquire v7 metadata without `sourceRelativePath`; use a returned v7
`contextHash` to pin later v7 requests. A mismatched cross-schema pin returns
`java.context.contextStale`.

Schemas v6 and v7 accept metadata release values through Java 27, matching the
bounded provider contract; values above 27 remain outside this metadata range.
This numeric bound does not qualify source parsing: v6 refuses source selection,
and v7 permits only the owner-only `sourceRelativePath` case described above.
Symbol-based source analysis remains unqualified. The installed
product resolves JDT Core 3.47.0, whose DOM reports `JLS_Latest=26` and exposes
no `JLS27`; Java 27 therefore still needs a stable JDT/ECJ release with JLS27
plus provider-backed source, binding, and platform acceptance. The Maven and
Gradle metadata tests do not imply that parser qualification.

The current-context boundary above records the implementation in this API 0.2
revision. The bounded Java 26 Maven and Gradle providers pass positive
`project.context` and JDT-binding acceptance against the selected JDK/platform;
the provider-specific receipts are recorded in ARC42. Java 27 metadata has separate
Maven and Gradle v6 acceptance, plus provider-specific Maven and Gradle v7
owner-only source association.
Positive Java 27 source analysis remains `actionable`; this protocol document
does not claim source support. Further analysis
acceptance must bind the source/API release,
compiler identity/options, selected-JDK release metadata, the matching `ct.sym` entry
when `--release` applies, and the platform modules/API inputs used by the JDT baseline,
and must add any wire fields only in a declared schema version. V2-v5 wire shapes remain
closed. V6 preserves metadata-only source refusal; v7 adds owner-only selection without
changing the unqualified analysis boundary.

Maven release 27 now has a bounded typed refusal under
`REQ-JAVA-PROJECT-CONTEXT-001`. The effective-model reader retains an out-of-range
numeric release through scanning, and `project.context` returns
`java.context.sourceLevelUnsupported` before publishing modules or selected-source
facts. The receipt-bound scenario declares release 27 alongside source 21 and
asserts that no `sourceLevel=21` context is returned; it also verifies unchanged
project inputs and no build execution. Its receipt and reports are in
`.refactorkit/runs/java-maven-release-27-context-r001/`. This does not support Java
27. Unresolved Maven expressions and wider compiler source/target combinations
remain separate fail-closed work.

SDK27 native APIs are separately qualified with source/target8,17,25 on the
bounded Maven and evaluated Gradle9.8.1 compiler-diagnostics paths. Acquisition
and SDK revalidation distinguish this API purpose from source-language support.
`--release17` continues to select historical API17, including declared plain Java.
Custom system overrides, incomplete compiler facts and stale authority still refuse.
The installed JDT grammar ceiling remains26; source/release27 semantic analysis
remains unqualified. V2-v5 now admit SDK API-profile values8..25 or27, without
widening their source-level maximum25 or closed property sets. Native API26
remains unqualified in those legacy contracts. No endpoint, version or dependency
is added. See [contract value qualification](arc42/appendix-requirements.adoc#java-sdk27-context-schema-values-2026-11-14).
Legacy v2-v5 acquisition now refuses source26 with
`java.context.sourceLevelUnsupported`; it does not publish an out-of-schema level
or clamp it to25. V6 metadata and v7's separately qualified source26 policy remain
independent. [Legacy boundary authority](arc42/appendix-requirements.adoc#java-context-legacy-source-bound-native-2026-11-14).

Integrated authority: [ARC42 SDK27](arc42/appendix-requirements.adoc#java-sdk27-native-api-provider-matrix-2026-11-14).

`sdk` contains hash-attested API profiles. The legacy wire key `sdk[].sourceRelease`
identifies the **API-profile release**, while `modules[].sourceSets[].sourceLevel`
continues to identify source grammar. With `--release 8`, the API profile is 8 and
consumes the selected SDK's historical `ct.sym` view. With complete Maven compiler
facts `source=8,target=8`, no `release`, and explicit SDK 21, the API profile is 21:
JDT consumes that SDK's attested current system modules while parsing Java 8.
SDK 25 has the corresponding native profile 25. The existing SDK identity binds
the current `lib/modules` and JRT evidence as well as release metadata and `ct.sym`;
`analysis` and entry-point replies identify the profiles actually consumed.

One reactor can contain both source-8/API-8 and source-8/API-21 source sets. It
therefore retains two SDK profile identities; profiles are never collapsed by
source grammar. Source/target values and complete compiler options remain visible
on each source set, so the API profile is selected from its own compiler mode.
No wire key was added or renamed. Consumers must use source-set `sourceLevel`
for grammar, not infer it from `sdk[].sourceRelease`.

The bounded native Maven entry-point Story qualifies SDK 21/25 with source/target
8, retains the negative modern-API check under `--release 8`, and checks a mixed
reactor. Custom compiler/system/bootstrap/preview arguments, a substituted compiler,
and unqualified native SDK profiles refuse with `java.platform.compilerApiUnqualified`.
Missing explicit SDK still returns `java.context.sdkRequired`. The shared
`JavaBuildSourceSetContextResolver` supplies the same compile environment to the
public projection and JDT. Standalone `diagnostics` and platform/dependency-source
declaration selection now have their separately qualified SDK21/25 source8/target8
routes described below. The Gradle-specific API matrix, SDK inference and Maven
toolchain execution remain `actionable`; this entry-point Story does not qualify
those routes or arbitrary source/target combinations.
Evidence: `.refactorkit/runs/java-source-target-sdk-api-r001/`.
Its isolated candidate is identified by
`f083485e1331ce206df5e36eb6aff60e7028483b2d1d3f0cd02d82c56be23f2f`
(93,374,421 bytes, 222 files, app 0.7.0/API 0.2). The final frozen pipeline passes
68 behavioral cases/272 steps, Java 325 with five existing skips, daemon 64, and
C 490. A sealed old118/new comparison agrees with detached processor-disabled
javac oracles on SDK21/25 source8/target8 and the SDK21 release8 negative case.
Exact paths and remaining scope are in that run's `handoff.json`; this is local
same-agent qualification, not independent release review or full M2/0.8 acceptance.
Maven output handling is specified below under effective output directories.

Standalone daemon `diagnostics` selects the project SDK through `jdkHome`; CLI
uses `--jdk-home`. It reuses the same per-source-set API profile policy. The
embedded/runtime-configured home remains a legacy fallback for `--release`, while
source/target mode requires explicit project SDK selection. Complete compiler
facts and compiler SDK pins are checked before JDT; unsupported/custom API facts
remain structural refusals with derivative errors suppressed. Java-only module
selection also works when a TypeScript project has the same name. SDK authority
reads are reused within a single acquisition, with evidence revalidation retained.

The related neutral-core capture prerequisite promotes matching auxiliary/source
inputs only when their captured content agrees. It keeps one source classification,
other auxiliary values and the old source/source override contract. A disagreement
returns `INPUT_CHANGED` before evidence attachment. This fixes Maven report-JS
project.open without hiding files or excluding an IDE directory.

The two separately admitted Stories qualify an immutable local runtime:
`4b4f18037c2372b317377bbe84311904c653634e5e24cee1ec56ef3838ba2521`
(93,388,145 bytes/222 files, app 0.7.0/API 0.2). The frozen pipeline passes106
behavioral cases/457 steps, core131, TypeScript128, Java325 with5 existing skips,
daemon64 and C490. Ten sealed old50b/new daemon/CLI comparisons without global
Java confirm native SDK21/25, preserved release8 fallback/restriction, missing SDK
refusal and Maven report capture. Evidence:
`.refactorkit/runs/java-diagnostics-sdk-selection-r001/`, with authority in
[ARC42](arc42/appendix-requirements.adoc#java-standalone-diagnostics-sdk-selection-2026-11-14).
These are same-agent local checks, not independent release acceptance.

The source8/API21 `java.platformSources.platformStale` navigation gap is now
resolved in the separately qualified SDK-source checkpoint. SDK and dependency
queries select the owning Java source set's compiler API profile, rather than
indexing SDK authority by consumer grammar. Java ownership is selected before
root depth, so a flat Maven root shared with JavaScript has the correct owner.
The consumer still parses as source8; SDK/dependency declarations use their
attested API context. Legacy-grammar module identity comes from the exact native
JRT binary URI, never a package-name guess. Classfile/JVM descriptors, source
association hashes, archive integrity and closed editor-frame checks remain intact.

Its sealed ZIP SHA256 is
`afd6df0c33c8a357bf78954839a1d9aee12b89ff4ae0dabb8b517ef85dde1fa3`
(93,388,757 bytes/222 files, app0.7.0/API0.2). Qualification passes160 behavioral
cases/673 steps, including42 existing source-safety cases in-process, core131,
TypeScript128, Java325 with5 existing skips, daemon64 and fresh C490. Twelve
old4b4/new embedded-runtime comparison cells without global Java demonstrate the
fix and retain release8 historical-source refusal, release21 navigation and
missing-src.zip unavailability. Each artifact and the project inputs stay unchanged.
Exact paths, reports and bounds:
`.refactorkit/runs/java-sdk-source-api-selector-r003/handoff.json`, with authority in
[ARC42](arc42/appendix-requirements.adoc#java-source-navigation-api-profile-2026-11-14).
These are local same-agent checks; consumer acceptance remains pending.

The peer withdrew its earlier release21 `staleContext` report after fixing the
missing schema in its fixture. No context/schema/hash guard is waived. No wire
field, schema version, dependency or capability flag changes in this checkpoint.
That checkpoint does not provide editor diagnostics. The subsequent locally
qualified candidate adds this Java-only `intelligence.query` request:

```json
{
  "requestId": "java-editor-errors",
  "kind": "diagnostics",
  "languageId": "java",
  "path": "src/main/java/example/Main.java",
  "expectedSnapshotHash": "<saved-project-snapshot>",
  "expectedIndexGeneration": 1,
  "limit": 500,
  "javaContext": {
    "sdkHome": "<explicit-project-sdk>",
    "expectedContextHash": "<selected-sdk-context>",
    "contextSchemaVersion": "java-project-context-v2"
  },
  "sourceAuthority": {
    "kind": "immutable-editor-overlay",
    "documents": [
      {"path": "src/main/java/example/Main.java", "version": 7, "content": "<exact-draft>"}
    ]
  }
}
```

The generation is the current `index.status` generation, not a fixed constant.
Capability discovery is `javaImmutableEditorOverlayDiagnostics` under
`intelligence.query.features`. Ready results have `kind: "diagnostics"`,
`coverage: "compiler-errors"`, `complete: true`, `items`, `total`,
`analyzedSourceCount`, `provenanceHash` and exact SDK/context/providerSnapshot/frame
attestations. Each item has error severity, code/message and a path with UTF16
start/end range. Existing request fields and API 0.2 remain compatible.

`analyzedSourceCount` counts every Java source in providerSnapshot: the saved base
plus exact replacements for the supplied drafts. It can exceed documents.length;
saved companions need not be listed as draft documents. The consumer must capture
all open unsaved Java documents, pin their set/version/content, and discard a
result if any changes. The server cannot attest an unknown unsent buffer. Missing
compiler/source-set/classpath authority or partial analysis refuses; source/context/
SDK/version drift retains existing typed guards. No saved-text fallback is used.

The optional integer limit1..500 and bounded message budget refuse overflow as
`java.diagnostics.limitExceeded`, without partial items or `complete`. Queued
cancellation stays JSON-RPC `REQUEST_CANCELLED` (-32800), with registration cleanup
and request-ID reuse. Cross-file duplicate native binary types in the same Java
module/source set now produce errors in both saved and editor diagnostics. Module,
main/test and enclosing binary identities remain separate. Completeness covers
captured compiler errors, not lint warnings, full javac warning parity or a build.
`diagnostics.v2` still accepts TypeScript/JavaScript only.

The preceding editor-diagnostics checkpoint ZIP SHA256 is
`367719be1c70d5a7e531c3b842dae2d766b05a5482db3f7f28d01f5091df2023`
(93,412,498 bytes/222 files, app 0.7.0/API 0.2). Local qualification passes 190 cases/
793 steps, 36 archived XML gates, 16 direct old/new comparison cells and fresh C 490.
Actual replies pass an existing Ajv 6.15 shared-keyword Draft7 projection; this is
not full Draft2020 validation, whose capability remains missing. Exact runtime,
real messages and archived evidence are in
`.refactorkit/runs/java-editor-sdk-diagnostics-r002/handoff.json`.
[ARC42 editor diagnostics](arc42/appendix-requirements.adoc#java-editor-compiler-diagnostics-2026-11-14)
owns the integrated status. Independent consumer acceptance and wider SDK-source,
Gradle, overlay/resource, advanced main, M2 and full0.8 matrices remain open;
the installed/default runtime is unchanged.

The subsequent Gradle provenance checkpoint keeps the existing wire schema and
`javaImmutableEditorOverlayDiagnostics` flag. Explicit `project.sync` now exports
one exact `sourceRootEvidence` row per evaluated root, including native generated
output roots. `providerId` is `gradle-effective-v1`, `pluginId` records the installed
Java plugin, `executionId` records the source set's actual JavaCompile task path,
and `availability` preserves complete/partial evidence. Origins are `build-plugin`
for Java source-set roots and `generated` for native generated outputs; no root
classification is inferred from a path such as `build/` or `src/main/java`.
The enclosing source-set/task identity and exact normalized coverage are checked.
Task provenance does not mean the task ran. Missing/malformed/inconsistent exports
refuse `gradle.sync.sourceRootProvenanceUnavailable`; incomplete ownership retains
the diagnostics guard. No fallback in the IDE can supply these facts.

The candidate SHA256 is
`322450da8c0d0878303809522b1057bf7103d49e34b72b845605d07e13189781`
(93,415,688 bytes/222 files, app 0.7.0/API 0.2). Qualification covers SDK21/25 with
source8/target8 and release8, dirty companions, custom/pre-existing generated files,
Gradle composite/repeated synchronization and 8.11.1 JPMS behavior. After sync,
consumers use the returned snapshot and reacquire context: newly authoritative
model fields legitimately change those hashes. A caller build edit with a pinned
generation refuses `intelligence.indexStale` before the Java context guard; it
never returns ready diagnostics for the stale frame.

Sixty behavioral cases/246 steps pass, including 17 just-completed source cases
reused only with exact production/build/relevant-test hashes, plus 12 raw old367/new
comparison cells. Current module regressions pass core 131, TypeScript 128, Java 331
with 5 existing skips, daemon 64 and C 502. Actual wire context/sync/diagnostics data
pass existing Ajv 6.15 using shared Draft7 assertions and exact array-valued
`dependentRequired` translation; full Draft2020 validation remains unavailable,
not silently claimed. These captured cells run no project compilation; editor queries do not implicitly evaluate Gradle.
Evidence: `.refactorkit/runs/java-gradle-source-root-provenance-r003/handoff.json`.
[ARC42](arc42/appendix-requirements.adoc#gradle-evaluated-source-root-provenance-2026-11-14)
owns integrated qualification; independent IDE/native/GUI acceptance and wider
Gradle/SDK/refusal/resource matrices remain pending. No default installation,
advanced-main support, full M2/C catalogue or 0.8 release is promoted.

Fresh scans bracket acquisition; SDK evidence is rebound before returning. Build/source/
classpath drift yields `java.context.snapshotStale`; a mismatched context/SDK identity
yields `java.context.contextStale`. Ambiguous source association yields
`java.context.sourceAssociationAmbiguous`; SDK/provider failures retain typed codes.
Refused responses contain no model, selected source or analysis. Invalid parameter shapes
use JSON-RPC `INVALID_PARAMS`. Limits refuse rather than truncate: 16 providers, 1,024
modules, 64 source sets per module, 256 roots per set, bounded classpaths/source bytes and
65,536 expanded environment entries. The payload budget is 1,047,552 bytes, reserving the
maximum request/ID envelope allowance inside the IDE's 2 MiB response limit;
`java.context.transportLimitExceeded` reports overflow.

For an initial read-only acquisition, a `java.context.snapshotStale` refusal includes the
freshly scanned `snapshotHash`. A client may discard that attempt and retry once with the
returned hash, preserving the SDK and scope selectors and omitting any prior
`expectedContextHash`. If the retry is also stale, surface the refusal (or refresh the
workspace and begin a new acquisition); do not loop or rebase a context-bound request.
This recovery does not weaken the server's snapshot check.

`server.capabilities.project.context.features` advertises
`mavenEffectiveContext`, `gradleEffectiveContext`, `gradlePartialContext`,
`gradleCompositeBuildIdentity`,
`configuredSdkEvidence`, `sharedJdtBuildContext`, `orderedCompileRuntimeClasspaths`,
`sourceAssociation`, `snapshotBound`, `staleEvidenceRefusal`, `typedSchemaV2`,
`sourceRootProvenance`, `compilerOptions`, `resolvedArtifactCoordinates`,
`annotationProcessorContext`, `jpmsModulePath`, `requiredFactRefusals`,
`javaQueriesUseContextAuthority`, `javaRefactoringsUseContextAuthority`,
`immutableJavaEditorOverlays` and `managedMutationAuthority` as true.

`gradleEffectiveContext` applies after a successful explicit `project.sync`;
`gradlePartialContext` describes unsynchronized declarative Gradle metadata. Ordinary
`project.open` and `project.context` do not execute Gradle build scripts. The caller may
authorize execution through `project.sync` with `allowBuildExecution: true`, which binds
the exported model and selected SDK to the returned snapshot. Named JPMS/module-path
Maven reactors remain typed unsupported until a complete model is qualified.

Java queries and context-bound refactoring previews accept and pin `javaContext` for
their advertised operations. The context itself is not an apply handle: a mutation still
requires a retained preview plan and matching snapshot/context at apply. Default Java
routes without an explicit context do not claim configured-SDK authority, and these
capability flags do not imply that every Java operation is supported; consumers should
check the advertised operation list. Full IDE M2 import/sync qualification remains open.

The built local consumer entry point is
`modules/refactorkit-cli/build/package/refactorkit/bin/refactorkit-daemon` with its embedded
runtime. `build/install/refactorkit/bin/refactorkit` is the Gradle application launcher.
This does not update a globally installed tool or publish a release. Source/build remains
0.7.0 and API 0.2. Local development evidence lives in
`.refactorkit/runs/ide-java-project-context-r001/`. The IDE reports end-to-end consumption
of v1/v2 project-context fields and carries the context hash/schema version through
navigation and refactoring; consumer wire adoption for the current contract is no longer
an open gap. Full M2 import/sync qualification remains open, including Maven Failsafe
`INTEGRATION_TEST` selection resolution in the native IDE and unsupported Gradle
composite layouts. Bounded provider-side source-set classification now has its own
acceptance, but it does not close that consumer selection gap.

## Daily Java query authority

`intelligence.query` accepts `javaContext: {sdkHome, expectedContextHash,
contextSchemaVersion?}` with `languageId: "java"` and the usual
`expectedSnapshotHash`. `sdkHome` and `expectedContextHash` are required.
`contextSchemaVersion` may be `java-project-context-v2` through
`java-project-context-v5`; omission keeps the v2 default. The query validates and
returns the requested published context version, including provider-qualified v4/v5
context. Unsupported members
or schema versions are invalid parameters.
[The authority schema](api-0.2-java-context-authority-schema.json) defines the object.
The context is reacquired from current build evidence and the explicit SDK before
a saved-snapshot query. Hover, definition, references, document symbols and
workspace symbols consume the same authoritative JDT analysis. Successful
responses add `contextHash`, the requested `contextSchemaVersion`, and
`platformIdentities`. Workspace hashes remain disk identities; the JDT cache
separately binds the context identity to prevent reuse across SDK contexts.

SDK/build evidence is checked again after queries. A different SDK with the old
context hash returns `java.context.contextStale`; changed build/source inputs
return `java.context.snapshotStale`. Refusals use the existing intelligence
envelope `status: "refused", error: {code,message}` and carry no successful
authority attestation. Without `javaContext`, legacy behavior makes no configured
SDK claim. Java completion/signature help and editor overlays remain unsupported
at this delivery. Mutation authority is still pending; a query authorizes no patch.

## Java preview and managed apply authority

`refactor.preview` accepts the same `javaContext: {sdkHome, expectedContextHash,
contextSchemaVersion?}` and `expectedSnapshotHash`, with Java language authority.
When the consumer acquired a v3 `project.context`, it must pass that response's
`schemaVersion` as `javaContext.contextSchemaVersion`; the preview echoes the same
version alongside the independently revalidated context hash and SDK identities.
Omission retains the v2 default for existing clients. SDK-bound routes cover
rename class/member, organize imports, format file, safe delete, extract method,
move class/source root and the five change-signature parameter operations. Build
module/ownership changes and recipes are refused through this route until their
operation-specific gate can compose with SDK authority. This delivery does not
qualify every admitted route's semantic transformations; its Story qualifies
organize imports and class rename plus context/currency safeguards.

The preview binds its `snapshot.hash` to `expectedSnapshotHash` and sets
`snapshot.validatedOnApply: true`; it also adds `contextHash`, the matching
`contextSchemaVersion` and `platformIdentities`. `operation` echoes the requested
operation. Every JDT planner invocation and diagnostics analysis carries the explicit SDK.
The plan binds that context alongside its existing operation evidence; its
authoritative diagnostics gate is retained with the pending plan.

`refactor.apply` requires `planId` and the original `javaContext`, including its
context schema version, for such plans.
Optional `expectedSnapshotHash` must match the preview snapshot; omission uses
that exact preview identity. A missing authority is
`java.context.authorityRequired`, a different context/SDK/schema is
`java.context.contextStale`, and changed build/source inputs are
`java.context.snapshotStale`. JSON-RPC refusal data contains `refusalCode`; no
operation upgrades its authority by supplying a later context. Successful apply
adds `appliedContextHash`, identifying the preview context used for the write.
Reacquire context after apply because the disk snapshot has changed.

SDK evidence is checked by the retained authoritative gate under the workspace
lock before WAL creation, during staged diagnostics and after commit. A post-
commit drift refuses success and triggers managed compensation. Cucumber fault
probes verify a pre-WAL SDK drift produces no source writes or transaction intent
and a post-commit SDK drift restores original source bytes. These guard checks do
not execute builds. Editor overlays and cursor-to-mutation remain separate gates.


## Explicit effective Gradle synchronization

`project.sync` accepts `buildSystem: "gradle"`, `expectedSnapshotHash`, an absolute
`sdkHome`, an absolute `gradleExecutable`, and `allowBuildExecution: true`. `sdkHome`
selects the compiler/toolchain SDK and Java platform used by the resulting context.
Optional absolute `gradleRuntimeHome` selects the JVM that launches Gradle and defaults
to `sdkHome` for existing callers. Keeping these values separate allows a Gradle version
to run on a compatible JDK while an explicitly configured Java toolchain supplies a
newer compiler/platform; the source set's effective compiler home must still match
`sdkHome`. Optional `projectProperties` is a bounded string map; optional absolute
`gradleUserHome` selects the local resolution cache. [The sync schema](api-0.2-java-gradle-sync-schema.json)
defines request and result. No Gradle process runs during ordinary `project.open`
or `project.context`. Before synchronization, declarative Gradle remains partial.

Synchronization evaluates the caller-authorized project's build scripts and exports
source sets, generated roots, output directories, compiler release/SDK, and actual
ordered compile/runtime closures. It invokes one metadata task without requesting
compilation. Gradle configuration code and initialization scripts remain executable
build code. `--offline` disables dependency resolution downloads; it does not sandbox
arbitrary script network or credential access. RefactorKit does not return process
logs or credential contents. The exporter selects the SDK explicitly, disables
automatic toolchain downloads, bounds process time to 120 seconds and exported/log
bytes to 1 MiB, and refuses source mutation or drifting inputs. It is suitable for
projects whose build-code execution the caller authorizes. Both SDK homes are included
in tool evidence and stale-context checks.

A successful result has `schemaVersion: "java-gradle-sync-v1"`,
`status: "synchronized"`, `snapshotHash`, `providerId: "gradle-effective-v1"`,
`gradleVersion`, canonical `sdkHome`, and canonical `gradleRuntimeHome`. Use that new snapshot for `project.context`;
its available effective context feeds the same JDT query and mutation authority as
Maven. Source/output paths must belong to the open workspace; included builds outside
that root are refused, while in-workspace builds carry qualified identities.
Source-set compiler SDK must match the selected SDK.
Build/init/properties/tool/classpath evidence is checked on subsequent acquisitions;
a changed build requires another explicit sync. A refused result contains only
schema/status and `error: {code,message}` with an optional bounded `cause` on export failure.
The session owns one immutable receipt,
cleared on project reopen; no durable import cache or automatic build retry is implied.

The explicit-sync path inventories both `*.gradle` and `*.gradle.kts` inputs and
asks Gradle itself to evaluate the project. Kotlin DSL build scripts are therefore
not translated into a RefactorKit or IDE model. This qualifies the effective Java
build facts exported by Gradle, not semantic analysis or refactoring of arbitrary
Kotlin scripts.

Known sync refusals are typed in `error.code`:

| Code | Refusal condition |
| --- | --- |
| `gradle.sync.executionNotAuthorized` | The caller did not explicitly authorize build-script execution. |
| `java.context.snapshotStale` | The workspace snapshot changed before synchronization. |
| `gradle.sync.executableUnavailable`, `gradle.sync.sdkUnavailable` | The configured Gradle executable or SDK is unavailable. |
| `gradle.sync.launchEnvironmentUnsupported` | Ambient Gradle/JVM options lack a qualified launch policy. |
| `gradle.sync.exportFailed`, `gradle.sync.unavailable` | Gradle could not export a safe effective model, or an unexpected failure prevented publication. Offline-only dependency/plugin resolution can cause export failure. |
| `gradle.sync.sourceLevelUnavailable`, `gradle.sync.sourceLevelUnsupported` | The Java source level cannot be established or lies outside 8 through 25. |
| `gradle.sync.sdkMismatch`, `gradle.sync.compilerSdkMismatch` | The selected Gradle/compiler SDK does not match the explicitly requested SDK. |
| `gradle.sync.sourceOutsideWorkspace` | Source/output ownership leaves the workspace. |
| `gradle.sync.compositeBuildUnsupported` | A projected project-dependency edge does not map to an exported module identity. Composite exports otherwise include direct, nested and unreferenced included builds with qualified identities. |
| `gradle.sync.includedBuildOutsideWorkspace` | An included build resolves outside the opened workspace; synchronization refuses without publishing a partial model. |
| `gradle.sync.sourceAttachmentInvalid` | A dependency source mapping is not attached to the exact resolved compile component. |
| `gradle.sync.inputsChanged`, `gradle.sync.sourceMutationDetected` | Build/tool evidence drifted during sync, or configuration changed saved Java sources. |
| `gradle.sync.limitExceeded`, `gradle.sync.timeout`, `gradle.sync.outputLimitExceeded`, `gradle.sync.cancelled` | A bounded inventory, execution-time, output-size, or cancellation limit was reached. |

Consumers should preserve these codes and surface the associated message; a refused
sync publishes no effective model.

Capabilities now mark `gradleEffectiveContext`, `javaQueriesUseContextAuthority`,
`javaRefactoringsUseContextAuthority`, and `managedMutationAuthority` true.
`gradlePartialContext` remains true for unsynchronized projects. Top-level
`server.capabilities.javaContextRefactoring` contains `schemaVersion: 1`, the
canonical SDK-bound Java refactoring `operations`, per-operation
`operationCapabilities` (`stability`, `mutationAuthority`, and `evidence`),
`requiresExplicitContext: true`, `sourceAuthority: "saved-snapshot"`, and
`rollbackAuthority: true`. The eight stable managed refactorings and six
experimental proposal-only semantic planners are listed. Unnamespaced signature
aliases are not advertised. Maven project/build transforms such as
`java.moveAcrossMavenModules` use their separately qualified project-operation
route and do not appear in Java editor or SDK-bound semantic mutation discovery.
Consumers must check the per-operation authority; legacy/default-SDK routes do
not establish configured SDK authority. See the
link:arc42/index.adoc[ARC42 architecture narrative] and the bounded
link:integration-contracts.md[IDE Java refactoring backlog].

Documentation gaps: Java dirty overlays, exact cursor-to-mutation selection,
rendered retained-plan diff and SDK-qualified rollback are `actionable`. Effective
Gradle source/target-only builds without an explicit release still cannot establish
the qualified JDK release model and are `actionable`. The existing IDE package-move
entry and review flow have passed component tests; their earlier status as absent is
stale. The RefactorKit `java.createMavenModule` capability already advertises
stable preview, rendered diff, managed apply and rollback. The daemon also exposes
stable `java.createGradleModule` after explicit Gradle synchronization. Its
`buildRoot` is workspace-relative to the root opened by `project.open`; pass `.`
when that root is already the selected Gradle build. A path relative to a larger
IDE workspace must be rebased before it is sent, because the planner resolves and
validates it against the opened snapshot root. The operation accepts a canonical
nested Gradle project path, infers Groovy or Kotlin DSL from the selected settings
file, and returns preview/apply/rollback capability without running Gradle during
preview. The Kotlin/Groovy provider Story is
[`java-gradle-create-module.feature`](../features/java-gradle-create-module.feature).
Native IDE Add Module acceptance remains `actionable`. Composite Gradle provider acceptance now
qualifies root, direct, nested and unreferenced included builds with workspace-relative
roots, exact dependency IDs, duplicate-path refusal, and stale/outside-workspace
refusal. The remaining composite gap is native IDE end-to-end mapping of semantic
module IDs to project discovery, run/test selectors, launch/debug and selected-project
coverage. The IDE owns task and selector mapping, execution and coverage; RefactorKit
owns only semantic project identity. No parallel IDE project model is introduced.
These remaining gaps stay under the existing `REQ-JAVA-GRADLE-CONTEXT-001`; no
duplicate requirement is opened. Independent IDE acceptance is `actionable` until
consumer qualification.
These limitations keep full M2 open. Architecture authority remains the
[ARC42 appendix](arc42/appendix-requirements.adoc); this satellite owns only the wire
contract. Gradle API sources: [source sets](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.SourceSet.html),
[JavaCompile](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.compile.JavaCompile.html),
and [compiler options](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.compile.CompileOptions.html).


## Retained Java preview diff

`refactor.preview` returns `renderedDiff`, `diffTruncated`, and
`diffTruncationReasons` alongside the original `planId` and structured file changes.
The existing core renderer projects the exact retained plan's workspace edit against
its saved preview snapshot; no plan is regenerated to render the diff. The SDK context
stamp and plan identity are preserved. The diff has the core limits (512 KiB total
budget, 128 files, 2,000 lines per hunk) and explicit truncation metadata. A consumer
must not treat a truncated diff as a complete approval view. Three complete small
SDK previews (organize, rename class, format) apply the same edits; a large rename
proves truncation reporting and is discarded without writes. Architecture authority
remains ARC42; this section owns the additive preview wire contract.


## Durable SDK transaction evidence

New SDK-bound applies persist the original context hash, canonical SDK home,
source-release/platform identities, and rollback provider in checksum-protected
transaction journal v9. Journal corruption is quarantined with the existing typed
`transaction.quarantined` error. Versions 1 through 8 remain readable with their
original checksum layouts. Ordinary transactions and legacy rewrites retain v8;
only authority-bearing transactions require v9. The core
stores generic authority attributes and acquires no Java dependency. Two Story cases
verify durable SDK evidence after daemon closure and checksum refusal after tampering;
all 125 existing core tests pass. Earlier SDK transactions lacking this evidence do
not become SDK-qualified rollback transactions. Explicit SDK rollback remains a
separate pending gate at this delivery. ARC42 integrated-status reconciliation is
`actionable` and follows the completed rollback slice.


## SDK-protected rollback

`patch.rollback` accepts `transactionId`, the exact committed
`expectedSnapshotHash` (S1), and the original `javaContext` from the preview (S0).
The context hash identifies the original transaction while the snapshot identifies
the current committed image. It returns `rolledBackContextHash` and the restored
snapshot after success. Missing authority, changed SDK/context, and changed build
inputs refuse with `data.refusalCode`. An SDK request on a legacy transaction with
no durable SDK authority refuses instead of using the ordinary route.

The neutral core rollback-authority port evaluates under the workspace lock.
It verifies saved and simulated original snapshots, SDK diagnostics before/staged/
after restoration, and compensates to committed bytes if post-restoration authority
fails. Protected transactions reject ordinary core rollback without the provider and
reject forced restoration. Six Story cases with SDK21/25/restart pass, plus fault
checks for under-lock drift and compensated post-restoration drift. Core and daemon
regressions pass; the Java suite found seven schema-v8 compatibility failures after
the generic v9 rollout. The remaining correction is actionable: retain v8 for ordinary
transactions and select v9 only when durable authority requires it. Package delivery
and independent SDK rollback acceptance remain pending until that correction passes.


### Journal compatibility and completed protected rollback

The corrective Story preserves exact schema-v8 layout/checksum for ordinary workflows
and selects schema-v9 only for persisted authority attributes. All seven existing
Java schema-v8 checks now pass unchanged. Read compatibility remains v1 through v9;
ordinary legacy migrations remain v8. New SDK v9 transactions support protected
rollback after restart, under-lock SDK/build checks, staged/committed diagnostics,
no ordinary/force fallback and compensation after post-restoration SDK drift.
The former schema compatibility gap is resolved. Effective Gradle still requires its
same synchronized daemon session; reopening Gradle requires explicit synchronization.
Older SDK transactions without a durable receipt remain unqualified and are refused
if an SDK rollback is requested. Unfiltered release qualification and full M2 remain open.


## Immutable Java editor queries

With explicit `javaContext`, `intelligence.query` accepts
`sourceAuthority: {kind: "immutable-editor-overlay", documents: [{path,version,content}]}`
for Java hover, definition, references, document symbols, workspace symbols,
`typeDefinition`, `typeHierarchy`, and `callHierarchy`. Supply a workspace-relative
`path` present in the overlay; position queries also require zero-based `position`.
`implementations` resolves a selected source type to its concrete workspace-source
subtypes, or a selected source method to concrete overriding declarations, using
exact JDT type/override bindings. Recovered targets refuse; unresolved analysis and
truncated results are marked partial. The result declares `targetKind`,
`scope: "workspace-source"`, warnings, completeness and truncation. Type hierarchy
accepts `direction: "supertypes" | "subtypes" | "both"`; call
hierarchy accepts `direction: "incoming" | "outgoing"`. Every document must
already exist in the saved Java snapshot. The overlay closes all saved Java
sources plus the supplied replacements;
JDT never consults live saved source text to resolve dirty declarations.

The selected SDK/build context still identifies the saved base. The result keeps
`snapshotHash` and `contextHash` from that base and adds `providerSnapshotHash` and
`sourceAuthority: {kind,baseSnapshotHash,overlayHash,documents:[{path,version,contentSha256}]}`.
No document content is returned in the authority receipt. Temporary analysis roots
are private and removed; locations remain workspace-relative. Overlay provenance
binds the closed source content and SDK instead of temporary directory identities.
The saved workspace index is not replaced with dirty symbols. The four Java
navigation queries use the same closed SDK-bound overlay and preserve the base
`snapshotHash` and `contextHash`; type, direct-call and implementation results carry
the overlay provider snapshot and provenance receipt. They do not save editor content
or mutate transaction state.

The application session tracks at most 5,000 document identities. Lower versions,
or different content at the same version, refuse `java.overlay.versionStale`.
Project reopen resets this session history. Existing core bounds apply: at most
128 documents, per-source and aggregate UTF-8 limits. SDK/workspace currency is
checked after the query. Without explicit SDK authority, no Java dirty-query
fallback is offered. Java completion/signature help remain unsupported.

`project.context.features.immutableJavaEditorOverlays` is now true. Mutation
metadata explicitly keeps `dirtyEditorMutationAuthority: false`; dirty preview
requests refuse `java.context.dirtyMutationUnsupported`. No implicit save occurs.
An editor-mediated versioned edit/apply/undo/recovery contract remains actionable;
saved cursor selection is the next gate. Full M2 is still open. This satellite owns
the wire contract and links to the central [ARC42 appendix](arc42/appendix-requirements.adoc).


## Exact saved Java cursor mutation selection

`intelligence.query` adds SDK-only `kind: "mutationSelection"` with a saved-source
`sourceAuthority`, workspace-relative `path`, zero-based `position`, snapshot and
explicit `javaContext`. It returns the ordinary ready envelope plus `selection` at
`java-mutation-selection-v1`. The selector binds snapshot/context hashes, cursor
path/position/content hash, exact compiler symbol/binding, declaration location,
provenance and `selectorHash`. Only unrecovered JDT evidence in the selected source
set's actual visibility is accepted. Unbound/ambiguous/external/generated/constructor
selections refuse; no lexical word or first-overload guess is substituted.

Pass the exact `selection` object as `javaSelection` to `refactor.preview`, alongside
the same SDK/context and snapshot. `symbol` may be omitted; if present it must match
that exact selection. The backend recomputes and compares the complete selector,
checks operation/type compatibility, derives the canonical planner ID and retains
`selectionHash` in the SDK plan lease. Apply returns `appliedSelectionHash`; a later
selector never replaces pending plan authority. Class/field rename and exact signed
method-overload rename are qualified by this Story. File operations with a selector
must target its cursor document. Stale/manipulated selectors refuse with
`java.selection.selectorStale`; later source edits refuse snapshot authority.

Capabilities identify `selectionQueryKind: "mutationSelection"` and
`selectionSchemaVersion: "java-mutation-selection-v1"`. Consumers must compare the
selector's saved content hash with their current document and revoke actions on edits;
this delivery does not authorize dirty mutations or implicit saving. The previously
qualified overlay queries remain read-only. Editor-mediated versioned dirty preview/
apply/undo/recovery remains actionable, and full M2 remains open. This satellite owns
the wire contract and links to central ARC42; integrated status reconciliation follows
final package qualification.

Protected SDK rollback uses the exact original journal snapshot as its restoration
baseline. It may restore errors already present in that snapshot, including a
secondary-type diagnostic whose file path changed during rename. It does not compare
the inverse as a fresh forward transformation from the committed snapshot. Original
snapshot equality, current SDK/classpath evidence, staged and post-restoration
diagnostic identity, and compensation remain mandatory. No diagnostic code or module
ownership is suppressed. The baseline Story and native Runner qualification are
recorded centrally in ARC42.

Effective Gradle analysis orders visible own-main roots before test roots when its
exported compile closure includes the main output. This prevents a test filename
declaring only secondary types from hiding the main declaration in JDT lookup.
Declared source ownership, compile/runtime entry order and module visibility remain
unchanged; no dependency or main visibility is inferred outside that closure.

SDK rollback requires the exact committed `snapshotHash` (S1), including classpath
and build evidence. Rebuilding after apply may change this evidence and makes that
rollback refuse before writing. Qualify Apply → build and Apply → rollback → build
as separate flows. Do not refresh S1 arbitrarily or use ordinary/force rollback to
cross this boundary. Rollback after a changed classpath requires a distinct authority
contract and is currently unsupported.

### Add Parameter for a bound this reference

Discover `changeSignature.addParameter` under
`server.capabilities.javaContextRefactoring.operationCapabilities`.
Its `features.thisMethodReferenceMigration` flag admits the bound receiver
in an explicitly typed local SAM initializer. Pass the saved JDT `METHOD`
selection and the matching SDK, context and snapshot to `refactor.preview`.

```json
{
  "type": "int",
  "name": "extra",
  "default": "0",
  "migrateFunctionalReferences": true
}
```

The selected method is private and declared on the type of the unqualified
`this` receiver. The default is a compiler-recognized literal. The new lambda
keeps the SAM's arity and supplies the literal to the bound invocation.
Managed Apply uses the retained selection and the existing rollback contract.

An unsupported reference bound to the selected method refuses with `-32001`
and `data.refusalCode = "java.changeSignature.functionalReferenceUnsupported"`
before the daemon issues a plan. This covers other receiver expressions even
when another overload could accept the remaining reference. The
[ARC42 acceptance entry](arc42/appendix-requirements.adoc#java-add-parameter-this-reference)
records the verified scope.

### Extract Delegate

Discover `extractDelegate` in `server.capabilities.javaContextRefactoring.operationCapabilities`
with capability `stable`, mutation authority `managed-stable` and evidence `compiler`.
Use `refactor.preview` with the same SDK and snapshot as the saved JDT `METHOD`
selection. Pass the returned `javaSelection` and these arguments.

```json
{
  "typeName": "Calculation",
  "members": ["OFFSET", "adjust", "compute"]
}
```

`members` contains two to four distinct names in one top-level class, including
the selected method. The planner moves at least two private static methods with
primitive signatures, each returning a single bound primitive expression.
It can also move one private static final `int` with a literal initializer.
The members must form a connected group with a bound use in another ordinary
method body of the owner. The new type is private, static and final. Staged JDT
checks the moved signatures and reference counts before normal managed Apply
and SDK-protected rollback. The preview flags potential changes to private
reflection, stack traces and class-file identity.

Malformed argument types return `-32602`. Unsupported groups, unresolved or
unselected dependencies, unsupported initialization, and name collisions
return `-32001` with a `java.extractDelegate.*` refusal code before issuing a plan.
The [ARC42 requirement](arc42/appendix-requirements.adoc#java-extract-delegate)
records the supported shape and qualification evidence.

## Closed dirty Java mutation preview

Experimental `refactor.editor.preview` accepts `languageId: "java"`, `operation`,
`symbol` where required, primitive `arguments`, the saved `expectedSnapshotHash`,
explicit `javaContext` and immutable-editor-overlay documents. Supported preview
operations are class/member rename, formatting and organize imports. Supply every
affected existing source in the overlay, including unchanged buffers referenced by
the transformation; missing documents refuse `java.editor.documentsMissing`.
SDK/build/snapshot and session document-version guards are shared with overlay queries.
LSP document versions must fit a nonnegative signed 32-bit integer.

The response at `java-editor-refactoring-v1` binds saved base, SDK context, closed
provider snapshot, document version/content receipts, exact edit `planHash`, retained
`planId` and expected provider snapshot after edits. It includes bounded textual diff
and diagnostics with explicit truncation flags. `workspaceEdit.documentChanges`
contains versioned text edits and ordered source-file rename operations with overwrite
disabled. The shape follows the official
[LSP WorkspaceEdit](https://raw.githubusercontent.com/microsoft/language-server-protocol/gh-pages/_specifications/lsp/3.17/types/workspaceEdit.md)
and [TextDocumentEdit](https://raw.githubusercontent.com/microsoft/language-server-protocol/gh-pages/_specifications/lsp/3.17/types/textDocumentEdit.md)
definitions; the response schema is embedded in the API catalogue's method entry.

`contextSchemaVersion` carries the schema actually acquired from the request's
`javaContext.contextSchemaVersion`; an omitted request field uses the v2 default.
The response preserves that exact schema together with its `contextHash` and
SDK `platformIdentities`. The existing request range is v2 through v7; availability
of an editor operation still depends on its SDK/source and semantic authority.
The bounded SDK26 Maven/Gradle v2/v3/v7 qualification and limits are recorded in
[ARC42](arc42/appendix-requirements.adoc#java26-editor-context-schema-2026-11-12).

This delivery is preview-only: `editOwnership: "editor"`, `applyAvailable: false` and
`dirtyEditorMutationAuthority: false`. Ordinary `refactor.apply` cannot find or write
these separately retained editor plans. No source save or rename occurs during
preview. The application session retains at most 16 plans and 64 MiB of conservatively
counted source content; eviction/reopen invalidates the plan. Only saved file paths
already present in the base are accepted. Dirty cursor selection and editor-mediated
prepare/confirm/undo/recovery remain actionable before apply admission. Full M2 remains
open; this bounded wire satellite links to central ARC42 for integrated qualification.


## Exact closed-overlay Java cursor mutation selection

For `intelligence.query` kind `mutationSelection`, supply explicit SDK `javaContext`
and `sourceAuthority.kind: immutable-editor-overlay` with all captured Java document
contents and versions. The ready envelope binds saved S0, context C0, provider O0 and
the full overlay receipt. Its `selection` uses `java-editor-mutation-selection-v1`:
`baseSnapshotHash`, `overlayHash`, `documents` (path/version/content SHA-256),
`selectorHash`, and nested exact `java-mutation-selection-v1` compiler selection.
The nested selection's snapshot is O0. The wrapper hash also binds every document
version, so advancing a version without changing bytes still revokes the selector.

Pass the complete wrapper as `javaEditorSelection` to `refactor.editor.preview` with
the same SDK/base/closed-overlay authority and operation arguments. The symbol can
be omitted; the service derives the exact selected compiler ID. If supplied, it must
match. Class/member/file operations require a compatible selection. Modified binding,
receipt or document version refuses `java.selection.selectorStale`; same-version
changed content or older versions refuse `java.overlay.versionStale`. No saved-source
or lexical selection fallback occurs. Signed methods proceed through exact JDT
resolution, including inline declarations absent from the lexical member prescan.

The preview returns `selectionHash` when a selector was supplied, and its retained
`planHash` binds that wrapper hash. The query schema and API catalogue own executable
response shapes. This completes cursor-to-preview authority; editor-mediated
prepare/confirm/undo/recovery remains actionable and dirty apply stays false. The
central [ARC42 appendix](arc42/appendix-requirements.adoc) owns integrated qualification.


## Experimental editor-owned transaction handshake

The application broker adds `refactor.editor.prepare`, `refactor.editor.confirm`,
`refactor.editor.undo.prepare` and `refactor.editor.undo.confirm`. Requests supply
`languageId: java`, `approvedPlanHash` from the retained preview, explicit original
SDK/context C0, current disk `expectedSnapshotHash`, and all captured buffer contents/
versions as `immutable-editor-overlay`. Prepare selects `planId`; subsequent phases
select its returned `transactionId`. These methods never write source buffers/files.

Prepare requires the exact preview's S0/D0, complete review and current shared version
history, SDK, build and classpath evidence. It returns `java-editor-transaction-v1`,
`status: prepared`, `editOwnership: editor` and exact versioned `workspaceEdit`. The
client must atomically guard buffer versions and apply those edits itself. For file
rename, the editor moves original saved bytes without autosaving dirty content.
Confirm supplies complete observed post-buffers and exact structural disk snapshot.
Only the approved O1, SDK diagnostics and build/classpath evidence are admitted.
Same-URI changed buffers must advance versions; renamed URIs can reset their version
lifecycle only with proven lineage and exact proposed content.

Undo preparation requires the exact confirmed post-document versions/content and
structural saved bytes. Its versioned inverse restores the original dirty buffers D0
and original paths; it never replaces D0 with saved S0 text. Undo confirmation verifies
that exact result and SDK diagnostics. External edits or failed confirmation return
`status: recovery-required` with no overwrite proposal. Obsolete approval/prepare/undo
authority is refused with a structured code. Version history advances only after
verified confirmation. Application state is bounded to 16 records/64 MiB, separate
from preview retention; unresolved preparations/recovery block another preparation.

This first handshake is experimental and not yet durable across daemon restart:
`durableRecoveryAvailable: false` and dirty mutation capability remain false. Durable
receipt/recovery qualification is the next required slice before exposing dirty Apply.
The API catalogue owns response schemas; the central
[ARC42 appendix](arc42/appendix-requirements.adoc) owns integrated qualification.


## Durable editor receipts and explicit recovery

The journal at `.refactorkit/editor-transactions` uses `java-editor-journal-v1`,
binding original S0/C0/SDK, full saved source images, original dirty D0, exact edit,
selector/plan hash, O0/O1, SDK diagnostic identities, document versions and inverse
state. A complete checksum-bound receipt is atomically persisted before prepare
returns an edit, and before phase acknowledgement. Metadata updates use a file lock
and compare the previous receipt; external changes are refused. On supported POSIX
filesystems records are private, file/directory writes are flushed, and symlinked
journal paths are refused. No source writes or autosave occur. The journal is bounded
to 16 records and 64 MiB; exceeding its limits refuses a new preparation.

After `project.open`, `refactor.editor.transactions` discovers receipts at
`java-editor-transactions-v1`. Each transaction includes `documents`, the complete
original captured D0 inventory, including clean tabs unaffected by the approved edit.
It is ordered by `originalPath` and bounded to 1–128 entries. Each entry contains only:

```json
{
  "originalPath": "src/main/java/pkg/Runner.java",
  "finalPath": "src/main/java/pkg/Worker.java",
  "originalSavedContentSha256": "<64 lowercase hex characters>"
}
```

Paths are workspace-relative. `finalPath` follows the exact approved rename lineage,
even when the rename has not happened or the transaction is terminal; an unaffected
document retains its original path. The hash identifies its original saved UTF-8
source bytes in S0, **not its dirty D0 content**. Discovery exposes no source text or
document versions and derives this inventory from the existing checked journal;
it does not acquire a new SDK context or semantic model.

After an IDE restart, use this inventory to find and capture the actual documents,
combining persisted dirty checkpoints with actual clean file content. Do not treat
`finalPath` as the current physical path: application may be partial. Refuse ambiguous
or missing lineage, inspect the current saved-byte hashes, and retain current document
version guards. Discovery does not grant mutation authority. Supply
original SDK/C0, approved plan hash, current saved snapshot, transaction ID and
complete captured buffers to `refactor.editor.recover`. Gradle requires a new explicit
sync in that same daemon after restart; no persisted graph or implicit execution
fallback is trusted. Current SDK/build/classpath and exact saved structural images
must reconstruct the original authority. Rebuild/save changes to saved evidence
remain unsupported by this contract and refuse.

Recovery of intact D0/S0 aborts the unused preparation. Exact known per-document
original/approved/saved images and known rename lineage permit a guarded versioned
inverse to D0; partial text not matching a known whole-document image or unrelated
external content produces `recovery-required` with no overwrite proposal. A corrupt
journal refuses before mutation. Confirmed transactions can prepare their inverse
after restart with the same exact guards. Rename URI version resets require attested
lineage; a recovery proposal always guards the current captured versions. The
proposal source-authority base is the current structural disk snapshot; the outer
transaction base/context remains original S0/C0.

`durableRecoveryAvailable` is now true in experimental transaction responses. Dirty
Apply capability remains false pending final fault/admission qualification. Existing
ordinary `refactor.apply` never accepts these editor plans. The API catalogue owns
response schemas and the central [ARC42 appendix](arc42/appendix-requirements.adoc)
owns integrated backend/consumer status. Runtime method feature metadata now matches
the catalogue for preview and transaction methods.


## Qualified editor mutation admission (current contract)

`javaContextRefactoring.dirtyEditorMutationAuthority` is now true for exactly
`renameClass`, `renameMember`, `formatFile` and `organizeImports`, listed as
`editorMutationOperations`. All four pass complete durable prepare/confirm/inverse/
confirm roundtrips. Ordinary `refactor.preview`/`refactor.apply` retain saved-source
authority and do not accept editor plans.

For a mutating editor workflow, explicitly add
`editorMutationMode: "durable-editor-owned"` to `refactor.editor.preview`. Its v1
response includes that mode and `applyAvailable: true`; the client must use the
qualified versioned editor-owned prepare/confirm/undo/recovery contract. The default
request and explicit `read-only-preview` mode preserve v1 `applyAvailable: false`
without adding a mode response field, keeping existing read-only consumers compatible.
Unknown modes refuse JSON-RPC invalid params. Mutation admission does not write files
or autosave: the editor owns exact version-checked edits and physical source rename.
The explicit approved retained plan hash, original SDK/context, current disk snapshot
and complete captured buffer authorities are mandatory at transaction boundaries.

Qualification additionally covers SDK/build/classpath changes after editor edits,
late buffer edits before undo, a journal corrupted before acknowledgement, and daemon
restart after the client completed an inverse before confirmation. Known recovery
images preserve the user's original dirty content; unknown edits are never overwritten.
The journal remains bounded to 16 records/64 MiB and requires atomic supported
filesystem metadata persistence. Rebuild/explicit saves changing original saved
evidence require another authority contract and remain refused.

The IDE agent separately reports dirty selector/preview native Maven SDK21/25 and
Linux GUI SDK21/25 GREEN, with draft/disc preservation, 2,383 TS, 387 Rust and 124
structural checks (50 opt-in Rust exclusions). That consumer used the read-only
contract. Editor mutation/undo/discovery/recovery consumer qualification and shared
Gradle integration remain independent work; no installed-provider upgrade or full
M2/release completion is claimed. This wire satellite links to the central
[ARC42 appendix](arc42/appendix-requirements.adoc) for integrated status.

Final backend admission qualification: 442 selected CLI/Java/daemon tests pass,
with five Java skips and 457 CLI exclusions. The packaged runtime passes 16 native
roundtrips (four operations x SDK21/25 x Maven/Gradle), including public schemas,
exact runtime/catalogue feature equality, default read-only compatibility, durable
prepare/confirmation/inverse/confirmation, original dirty buffers and saved-byte
restoration. Earlier native restart/recovery proofs remain qualified. Inventory
retains 134 original-path sources, four exact steps and zero ambiguity. The package
is workspace-local; no global provider upgrade, version change or publication.

## Effective Maven output directories for consumers

For `maven-effective-v1`, `modules[].sourceSets[].outputDirectories` contains the
workspace-relative effective `build.outputDirectory` for main and
`build.testOutputDirectory` for test. Defaults, inherited build directories, ordinary
custom output declarations and properties from explicitly selected profiles are
resolved by the existing Maven effective-model builder. Profiles can vary build
directory/properties; output declarations remain in the ordinary build model as
specified by the [Maven POM reference](https://maven.apache.org/pom.html#Directories).
No plugin or lifecycle is executed, and no consumer semantic model is introduced.

Declared output locations remain visible before they exist. Compile/runtime
classpaths retain the existing materialization rule: only actual output directories
are included. Main compile/runtime starts with its effective main output; test
compile/runtime starts with effective test output, then main output, followed by
the scoped dependency closure. Test-before-main ordering follows
[MavenProject's classpath contract](https://maven.apache.org/ref/3.9.9/maven-core/xref/org/apache/maven/project/MavenProject.html#L340).
Reactor dependencies use their own effective main outputs, with compile/provided,
runtime/test and transitive scope restrictions preserved. Unrelated Gradle outputs,
and conventional Maven outputs replaced by custom declarations, are excluded from
these classpaths and output fingerprints.

Unresolved or outside-workspace output locations, escaping symlink ancestors and
existing non-directory locations make the effective model unavailable. A selected
source context returns `java.context.effectiveModelUnavailable`, without a guessed
output fallback. Output fingerprints bind the actual compiled bytes; compile/rebuild
changes invalidate earlier snapshot/context evidence.

Build and JVM execution remain consumer responsibilities. After compilation, refresh
the workspace and reacquire `project.context` with the configured SDK before using
its ordered runtime classpath for Run/Debug. Reacquire S0/C0 and regenerate affected
previews after a package update; corrected output evidence changes context identity.
Provider-qualified requests use the strict
[java-project-context-v3](api-0.2-project-context-v3-schema.json) response schema;
pair-only requests retain the strict [v2 schema](api-0.2-project-context-v2-schema.json)
for compatibility. The prior strict v1 response schema remains at
[api-0.2-project-context-schema.json](api-0.2-project-context-schema.json) for migration
reference. Use `requiredFacts` before relying on optional metadata. The context hash
domain remains v2 because the ordered provider/model evidence was already part of that
identity; v3 changes the selected-source wire identity, not the hashed project facts.
This wire satellite delegates integrated qualification to the central
[ARC42 appendix](arc42/appendix-requirements.adoc#JavaMavenEffectiveOutputs).


## Read-only dependency source navigation

ARC42 (`docs/arc42/appendix-requirements.adoc`) owns qualification status. This section
owns the experimental `java-dependency-source-v1` wire contract. The existing
`java-project-context-v2` response and context-hash attestations are versioned
separately from the `java-dependency-source-v1` lookup contract.
`server.capabilities` advertises `java.dependencySource.lookup` and
`java.dependencySource.read`; both require a project and report `writesWorkspace:false`.

Acquire `project.context` with the configured SDK, then call lookup with exactly:

```json
{
  "expectedSnapshotHash": "<S0 SHA-256>",
  "expectedContextHash": "<C0 SHA-256>",
  "sdkHome": "/configured/jdk",
  "path": "src/main/java/example/App.java",
  "position": {"line": 3, "character": 12}
}
```

The cursor must select a resolved JVM binding in a saved Java source. The engine
uses that source's unique provider/module/source-set compile environment, the
existing JDT JVM owner/member/descriptor, and the exact classfile `SourceFile`
attribute. Secondary and nested types therefore map to the real source filename.
No consumer dependency model or filename guess is required.

An `available` response includes S0/C0, configured SDK identities and `association`:
provider/module/source set, exact binary path/hash, source archive path/hash,
source entry/hash, JVM identity, provenance/component/SourceFile,
`associationHash`, `readOnly:true` and an opaque `refactorkit-java-source://` URI.
Lookup returns metadata. Read repeats the same request plus the exact
`expectedAssociationHash`, and returns UTF-8 `content`. Consumers use the provider
URI for a read-only document; they must not turn it into a workspace edit or open
arbitrary archive paths. No overload/member line is guessed: this increment opens
the owning source file and does not promise an exact member range.

S0/C0/SDK drift is refused by the existing Java context authority. Source archives
are additionally bound by `associationHash`; changed, removed or replaced source
bytes require a new lookup even when C0 is unchanged. `unavailable` means no
qualified local attachment or exact binding; `ambiguous` lists competing binary
classpath entries without selecting one; `refused` reports stale/unsafe/over-budget
inputs. All reasons have typed `code` and `message`.

The first qualified provider associates a selected fixed-version normal Maven JAR
with its locally materialized coordinate-matched `-sources.jar`. It reuses the
provider's selected dependency records and resolved classpath; it executes no Maven
plugins, build, repository resolution or network request. Provenance establishes
coordinate plus classfile/package/type identity, not proof that the published source
and binary have identical implementations. Maven snapshots/ranges/classifiers,
compiled directories, SDK `src.zip`, multi-release binaries and unqualified
providers report explicit unavailable/refused outcomes. Limits are 128 scoped
classpath entries, 32 MiB per archive, 32768 archive entries, 64 MiB cumulative
inflation and 128 KiB selected UTF-8 source. Entries are read in memory, never extracted.


Gradle effective contexts also attach source artifacts from the **existing resolved
compile graph** during explicit `project.sync` (`allowBuildExecution:true`, always
`--offline`). The exporter reselects a sources documentation variant for each
resolved external component and records the exact binary/source paths plus
component ID in Java provider metadata. It filters against the compiler's actual
classpath. Missing offline sources do not make the compile model fail: lookup
reports `unavailable`. File dependencies and reactor compiled directories have no
qualified external source mapping. Lookup/read never run Gradle or start resolution;
the IDE consumes the same association API. Provenance kind is
`GRADLE_RESOLVED_COMPONENT_SOURCES_VARIANT_AND_CLASSFILE_SOURCE_FILE`.
Multiple binary class owners return `ambiguous`; multiple source artifacts for one
component refuse with `java.sources.attachmentAmbiguous` rather than selecting one.
The source view uses Gradle's [artifact variant reselection API](https://docs.gradle.org/current/userguide/artifact_views.html);
this is bounded provider metadata, not a new client dependency graph.


Current qualification uses real packaged-daemon Maven/Gradle fixtures on configured
SDK21/25 with release17 binaries. Class, nested type, method and record owning-source
reads, missing attachments, ambiguous binary owners and stale source/binary reads pass.
Evidence is bounded by ARC42; this is backend qualification, not IDE GUI acceptance.
The extension package is separate from the prior validated execution delivery; its
manifest binds the launcher, owned JARs and exact schema/documentation hashes.

## Exact dependency declaration navigation

ARC42 owns qualification status; `daemon.javaDependencySourceDeclarations` in the
API catalogue owns the `java-dependency-source-declaration-v1` schema. The separate
experimental method `java.dependencySource.declaration` is advertised with
`writesWorkspace:false` and `exactJvmDeclarationRanges:true`. It accepts the same
saved cursor S0/C0/SDK/path/position request as lookup, plus optional
`expectedAssociationHash`. An omitted association guard observes and attests the
current source bytes; a supplied guard must match the previous lookup/read exactly.

An `available` response includes the same immutable source `association` and a
`declaration` with `declarationRange`, `nameRange`, `bindingKey` and
`evidence:"JDT_SOURCE_BINDING"`. Both ranges use zero-based UTF-16 line/character
positions and exclusive ends in the exact attested owning-source text. JDT resolves
source declarations using the configured SDK release and the selected source set's
actual compile environment. The existing owner/member/JVM descriptor must match a
real classfile member and the source declaration binding. Class/interface/annotation/
enum kind and member access/static modifiers are checked. Overloads and generic
erasure use semantic descriptors; secondary/nested types use classfile SourceFile.
No source line or overload is inferred from text matching.

Source reads bracket semantic analysis. S0/C0/SDK and source-byte association drift
refuse the result. Duplicate/clashing source declarations report `ambiguous` with
`java.sources.declarationAmbiguous` and **no guessed declaration/candidate ranges**
when JDT cannot bind each declaration. Invalid grammar or incomplete binding reports
`unavailable`. A missing explicit binary member, descriptor mismatch or incompatible
checked modifiers reports `refused` with `java.sources.declarationMismatch`.
Implicit default constructors and generated record methods identified by JDT report
`unavailable` because there is no explicit declaration range. Record types and explicit
compact/canonical constructors can return exact ranges. Constructor forms with hidden
JVM parameters, such as enclosing-instance/enum synthetic parameters, are not inferred:
a missing exact classfile descriptor refuses with `java.sources.binaryIdentityMismatch`.
This proves declaration identity, not binary/source implementation equivalence.

Consumers may call the existing guarded `java.dependencySource.read` with the returned
association to open the immutable resource and then use its exact range. When an exact
range is unavailable, the original owning-file API remains independently supported;
the consumer must label that result truthfully. The previous owning-source delivery
is immutable, and the range extension is a separate package. No client-side dependency
or semantic library model is required.

## Dependency source navigation from Java editor overlays

ARC42 owns integrated qualification; `daemon.javaEditorDependencySources.routes` in
the API catalogue owns the three separate experimental schemas. The methods are
`java.dependencySource.editor.lookup`, `java.dependencySource.editor.declaration` and
`java.dependencySource.editor.read`. All advertise `immutableEditorContext:true` and
`writesWorkspace:false`; the declaration method advertises exact JVM declaration
ranges. They extend the existing immutable Java editor context, using the same JDT
analyzer, SDK authority, staging and session version history.

First obtain a ready `intelligence.query` definition result for the selected Java
cursor and its complete current Java document inventory. Retain the returned
`sourceAuthority.overlayHash`. Send the same S0/C0/SDK/path/position to an editor
source method, adding exactly these fields:

```json
{
  "expectedOverlayHash": "<provider-selected overlay SHA-256>",
  "sourceAuthority": {
    "kind": "immutable-editor-overlay",
    "documents": [
      {"path": "src/main/java/example/App.java", "version": 44, "content": "<selected draft>"},
      {"path": "src/main/java/example/Selector.java", "version": 44, "content": "<other current draft>"}
    ]
  }
}
```

The consumer must capture and supply the complete Java document frame used for the
selection, including other drafts that can change resolution. The provider cannot
discover drafts omitted by the consumer. Every supplied path/version/content enters
the overlay proof and association. An omitted document or different frame cannot
reuse the selected proof. The selected path must belong to that inventory. Existing
closed-overlay limits apply (1–128 documents and at most 786432 total UTF-8 bytes).
No source buffers are saved or merged into the saved project.

Successful context acquisition returns the original saved `snapshotHash` (S0),
`contextHash` (C0) and configured SDK evidence. The separate `providerSnapshotHash`
identifies the staged draft content; `sourceAuthority` reports kind, base S0, overlay
hash and document path/version/content SHA-256 evidence without echoing draft text.
Lookup/read use `java-editor-dependency-source-v1`; declaration uses
`java-editor-dependency-source-declaration-v1`. Their association and exact range
rules are the same as saved dependency navigation, with the overlay hash additionally
bound into `associationHash`. Even a version-only change invalidates that association.

Guarded `editor.read` repeats the same complete documents, selected overlay hash and
returned `expectedAssociationHash`. Declaration optionally accepts that association
guard; lookup does not. Reads reanalyze the same immutable frame and revalidate saved
S0/C0/SDK, observed versions, exact binary/source bytes and provenance. Changed or
omitted documents refuse with `java.overlay.hashStale`; obsolete versions or different
text at an already observed version refuse with `java.overlay.versionStale`. A fresh
overlay selection with an old association refuses with `java.sources.associationStale`.
Missing guards or a different source-authority kind are invalid parameters.

When a declaration range is unavailable, an owning-file fallback can use
`editor.lookup` and `editor.read` with the same frame. Calling a saved-cursor API
instead can select another binary and is not a valid fallback for dirty navigation.
Unavailable/ambiguous/refused results retain the existing explicit limitations;
this extension adds no SDK `src.zip` or synthetic declaration support. The owning-
source and saved-range packages remain immutable; the editor extension has its own
package and manifest. Runtime/API versions remain 0.7.0/0.2 with experimental methods.

## Selected SDK platform source navigation

ARC42 owns qualification; `daemon.javaPlatformSources.routes` in the API catalogue
owns the separate experimental `java.platformSource.lookup`, `.declaration` and `.read`
contracts. Schemas are `java-platform-source-v1` and
`java-platform-source-declaration-v1`. This is independent of dependency attachments;
platform resources use `refactorkit-java-platform-source://` and `readOnly:true`.

All requests repeat S0/C0/SDK/path/UTF-16 position and require
`expectedPlatformIdentitySha256`, taken from the provider's SDK authority for the
owning source set's effective release. The provider verifies the unique owner and
actual platform identity; callers cannot supply an arbitrary release, module, binary
or source archive. Saved mode requires `sourceAuthority:{"kind":"saved-snapshot"}`.
Editor mode supplies the complete existing `immutable-editor-overlay` Java document
frame and mandatory selected `expectedOverlayHash`. That proof comes from a ready
definition query for the same cursor/frame. Original S0/C0 and SDK authority remain
separate from the editor `providerSnapshotHash` and document version/content proof.

Lookup returns an attested owning-source association; declaration adds exact JDT
declaration/name ranges. Read requires `expectedAssociationHash` and repeats the same
saved/editor authority. Declaration optionally accepts that association guard; lookup
does not. Read revalidates binary/source/frame evidence. Saved authority cannot replay
an editor association, and source navigation never saves drafts. The same consumer
responsibility for complete Java document inventory and observed version history
applies as for editor dependency navigation.

The JDT parser consumes the selected SDK, with daemon-host fallback disabled. Its
exact underlying binary binding must carry a JRT module/class origin from that
resolver; an owner name alone never qualifies platform provenance. The independent
closeable SDK reader consumes that selected module/class from the attested image.
The real classfile `SourceFile` attribute selects the standard configured SDK's
`lib/src.zip` module/package/source entry. No other SDK, filename guess, build,
network request or extraction is used. A distro source symlink is resolved and its
target identity additionally bound/revalidated.

The association reports owning provider/module/source set, selected SDK real path,
effective/platform releases and `platformIdentitySha256`, `platformModule`, exact
JVM identity, `binaryOrigin` (`CURRENT_JRT_IMAGE`, origin URI, class entry/raw SHA-256,
system-image path/raw SHA-256), source archive path/raw SHA-256, entry/raw source
SHA-256, classfile SourceFile and association hash. These are distinct hash domains;
none is interchangeable with an opaque project classpath fingerprint. The whole-file
association also requires parsed source package/owning type and valid grammar.

Exact declaration parsing runs in the actual named SDK module under the same JDT
environment. It checks exact owner/member/descriptor and source modifiers against
the selected classfile, brackets source/SDK evidence, and returns zero-based UTF-16
exclusive-end ranges into the exact guarded source content. Duplicate/clashing or
incomplete source evidence has no guessed range. Generated/default members without
an explicit declaration remain unavailable. Declaration identity does not claim
binary/source implementation equivalence.

SDK source budgets are independent: 128 MiB raw archive, 32768 entries, 512 MiB full
declared and actual inflation, 512 KiB selected UTF-8 source and 2 MiB selected class.
Central/local ZIP inventories, paths, checksums, sizes, compression metadata and
header evidence must agree; malformed/duplicate/unsafe or unsupported split/ZIP64
archives refuse. Reads decode bounded in-memory archive bytes. The dependency API's
32 MiB archive / 64 MiB inflation / 128 KiB source limits remain unchanged.

Initially only `effectiveRelease == platformRelease` is qualified for SDK source
navigation. Historical ct.sym signature authority does not attest current SDK source
as historical source: `java.platformSources.releaseSourceUnqualified` is unavailable.
A missing standard archive returns `sourceArchiveUnavailable`. Unknown SDK origin or
source identity is unavailable; wrong selected platform identity is `platformStale`.
Changed/removed source evidence or replayed frame associations refuse with
`associationStale`; SDK/S0/C0 and observed editor-version drift use existing context/
overlay refusal codes. Unsafe/over-budget input and source descriptor/modifier
mismatch are refused; ambiguity never selects a range.

Backend native qualification is stated in ARC42 with actual bounds. The installed
SDK21 has no src.zip, so its honest missing-source/refusal behavior is qualified;
positive SDK21 source navigation is not verified. SDK25 current-release sources are
locally available, including String.java larger than the dependency source budget.
Historical sources, additional hosts/layouts, patched modules and unqualified synthetic
descriptors remain unsupported. The SDK extension is a separate immutable package;
the previous execution/dependency/saved-range/editor deliveries remain unchanged.

## Classpath fingerprints and raw source/binary hashes

`project.context` classpath/evidence `fingerprint` is an **opaque provider token**.
It is not the raw JAR SHA-256 and must never be compared with
`association.binarySha256`, even when both strings resemble hashes. It can represent
artifact kind, directory structure or missing evidence; consumers must not infer its
encoding or recreate its algorithm. Validate provider/module/source-set/path ownership
and S0/C0/SDK through the provider contracts.

`binarySha256` and `sourceArchiveSha256` are SHA-256 of the actual archive bytes;
`sourceSha256` is SHA-256 of the exact UTF-8 entry bytes. `associationHash` binds these
hashes to provenance, JVM identity and S0/C0/SDK context. The guarded lookup/read
protocol validates that association and content. These hash domains, context hashes
and opaque classpath fingerprints are distinct and are not interchangeable.


SDK source delivery qualification is integrated in [ARC42](arc42/appendix-requirements.adoc).
The final independent SDK package passes 514 selected regressions and 263 native
protocol/compatibility checks. SDK25 source navigation is positively qualified for
Maven and offline synchronized Gradle. The installed SDK21 lacks src.zip; SDK21
checks qualify unavailable/refusal behavior only. Current SDK source cannot attest
historical-release source. The immutable SDK package, manifest and sealed guide,
catalogue, ARC42 and research artifacts are at
`/tmp/refactorkit-sdk-source-delivery-r001`. Consumer SDK adoption remains pending.

## Maven Add Module with partial Java context

`refactor.preview` for `java.createMavenModule` may include the usual explicit
`javaContext` (`sdkHome`, `expectedContextHash`, and `contextSchemaVersion`) when the
aggregate Java context is `partial`. This exception is limited to the Maven project
transformation: the operation uses its own staged Maven reactor diagnostics and does
not claim JDT semantic authority, Java source ownership, or Gradle module creation.
Gradle build-script execution remains denied during declarative discovery.

The preview response returns the supplied context hash/schema and configured platform
identities. Its operation lease binds those facts to the exact snapshot and generated
Maven edit. `refactor.apply` requires the same context and re-reads the current
snapshot/SDK evidence before applying. The transaction journal retains the structural
context metadata so normal `patch.rollback` can re-check the committed snapshot and
original SDK evidence before restoring the exact prior image. A Maven module added by
the transaction may introduce additional Java release rows; rollback accepts those
only when the original release identities remain present and the configured SDK home
is unchanged. Other Java semantic operations still require an effective Java context.

The bounded partial-context Story and §18.6 inventories are in
`.refactorkit/runs/maven-add-module-partial-context-r002/`. It exercises a Maven
reactor beside a declarative Gradle module, verifies no Gradle build code ran, then
previews, applies, builds the resulting Maven reactor offline, and normally rolls
back with exact input restoration. The [ARC42 appendix](arc42/appendix-requirements.adoc)
remains the integrated architecture and qualification-status authority.

### Bounded Gradle export failure hints within M2 — 2026-11-13

`gradle.sync.exportFailed` now preserves a fixed diagnostic hint from the actual
exporter process output in `error.cause: {code,message,evidence}`. `evidence` is
`process-output`; the primary `error.message` also includes the fixed hint.
The cause codes are:

| Cause code | Observed output |
| --- | --- |
| `gradle.sync.cause.cacheReadOnly` | A read-only filesystem marker on the same line as the configured or canonical Gradle user-home path. |
| `gradle.sync.cause.cachePermissionDenied` | A denied filesystem access marker on that cache path. |
| `gradle.sync.cause.filesystemReadOnly` | Read-only filesystem evidence without established cache-path ownership. |
| `gradle.sync.cause.filesystemPermissionDenied` | Denied access evidence without established cache-path ownership. |
| `gradle.sync.cause.offlinePluginResolutionFailed` | A Gradle plugin-not-found section reports that the plugin repositories could not resolve a plugin artifact during offline export. |
| `gradle.sync.cause.offlineDependencyUnavailable` | Gradle explicitly reports no cached version/resource available for offline mode. |
| `gradle.sync.cause.unknown` | No recognized evidence establishes one of the above hints. |

These are bounded hints about reported output, not certification of a system
root cause. The provider does not infer the cause of an earlier failed sync from
an independent Gradle run. Log reads are limited to 1 MiB plus one overflow byte;
a fast-exiting process obeys the same `gradle.sync.outputLimitExceeded` boundary.
No raw log excerpts, paths, URLs, arguments or credentials enter these fields.
Unrecognized output stays unknown. Existing refusal codes, authorization,
offline-only execution and no-partial-model behavior remain unchanged.

The existing `REQ-JAVA-GRADLE-CONTEXT-001` M2 requirement owns the six-case Story:
cache markers, a real missing offline dependency, unknown sensitive output,
unrelated-path ownership and a fast oversized log. Controlled output peers
exercise extraction/privacy; they are not a reproduction of the IDE's original
sandbox failure. Twelve Kotest specifications exercise the pure classification.
Source and extracted-candidate daemon stdio each pass 6/24; an existing positive
Java 26 Gradle context scenario passes 1/4 on both source and extracted daemon.
Java 325/five recorded skips, daemon
64 and MCP 32 pass. These bounded results do not close full M2 or qualify a
0.8.0 release. Architecture/status authority:
[existing M2 acceptance](arc42/appendix-requirements.adoc#JavaM2ProjectContextReconciliation),
[diagnostic checkpoint](arc42/appendix-requirements.adoc#m2-gradle-export-diagnostic-2026-11-13).
Evidence: `.refactorkit/runs/ide-gradle-export-diagnostic-r001/`.

### First-use Gradle plugin resolution — 2026-11-21

The plugin-specific hint belongs to the existing
`REQ-JAVA-PROJECT-CONTEXT-001` Story. Both parts of the evidence must
appear in one short diagnostics section emitted by the same offline export.
The fixed response identifies neither the plugin nor the repositories. It does
not establish whether a plugin is published, whether the coordinates are valid,
or whether connecting to the network would resolve it.

For first-use dependency bootstrap, the caller can deliberately run Gradle
dependency resolution in a trusted workspace with the same wrapper, JVM, and
Gradle user home, then retry the normal offline sync. A product-managed bootstrap
would need a separate explicit authorization for build execution and network
access scoped to that workspace and toolchain. Gradle's `--offline` governs
Gradle dependency resolution. Network confinement of project build code, when
required, needs a process-level control. `project.sync` has no automatic
online retry. Only a successful offline export establishes a synchronized model.

### Actual cache-permission output and stack traces — 2026-11-13

The explicit exporter now requests `--stacktrace` so native initialization can
report its nested cause before the log is deleted. The existing 1 MiB read/output
bound and fixed public cause fields are retained. Three real Gradle 9.6.1 cases
now live in the same six-case M2 Story: an initialized native cache with its real
lock made non-writable reports `cachePermissionDenied`; an empty mode-0500 cache
that yields only an unlocated `IOException: No such file or directory` remains
`unknown`; an absent offline dependency reports `offlineDependencyUnavailable`.
Both denied fixtures prove an actual write failure first. Native initialization
uses only `gradle --offline --version`, without compilation; a controlled wrapper
retains the actual failing process output for qualification, not for API display.

The final native lock case has RED 1/five controls without the flag and GREEN
6/24 with it. Source and extracted daemon each pass 6/24 and the existing positive
context 1/4; Java 325/five skips, daemon 64/MCP 32 pass. Earlier empty-cache
attempts remain recorded and do not justify inferring permission evidence.
The prior candidate remains unchanged; the new private ZIP SHA256 is
`a6f1b30e917ff375c6b57581193cb6e17e67b4bc645e4885be6946e0693add96`.
Evidence: `.refactorkit/runs/ide-gradle-export-diagnostic-r002/`.
Authority: [existing M2](arc42/appendix-requirements.adoc#JavaM2ProjectContextReconciliation),
[real-cache checkpoint](arc42/appendix-requirements.adoc#m2-gradle-real-cache-diagnostics-2026-11-13).
The IDE agent subsequently reports PASS8 native-service cases on JDK21/25
with Gradle9.8.1 and GUI Linux release/no-bundle PASS2 scenarios/16 steps on
JDK21 for refusal and explicit cache correction/recovery. Inputs are preserved
and processes close cleanly. These are peer-reported consumer results, recorded
separately from local qualification; full M2 and 0.8.0 remain open.

## Provider-owned Java entry-point query: first source increment

Status: **partial / classic native candidate qualified**, under the existing
REQ-JAVA-PROJECT-CONTEXT-001/M2. The classic-declared-v1 profile has in-process and
real NDJSON evidence, frame/ID limits, queued cancellation and an isolated sealed
candidate. Inherited/Java25/compact-preview and wider provider/SDK matrices remain
mandatory final scope. complete=false does not authorize a complete zero-main
claim. This is an experimental candidate, not a default installation or 0.8.0
release promotion. Native candidate authority:
[ARC42 wire/candidate](arc42/appendix-requirements.adoc#java-entry-point-native-wire-candidate-2026-11-13).
The historical probes returning queryUnsupported describe the pre-implementation
source; mainClasses/java.mainClasses are not aliases.
Authority: [ARC42 classic source increment](arc42/appendix-requirements.adoc#java-entry-point-classic-source-increment-2026-11-13).
Evidence: .refactorkit/runs/ide-java-entrypoint-implementation-r001/.

The single operation is `intelligence.query` with `kind: "entryPoints"`,
`languageId: "java"`, existing `requestId`/`expectedSnapshotHash`, explicit
`scope: {providerId, moduleId, sourceSetId}`, and `javaContext` containing the
selected `sdkHome`, exact `expectedContextHash` and `contextSchemaVersion`.
An optional owned source path filters Run-from-source candidates. Scope must be
used when reacquiring provider authority. It cannot be added inside today's
closed `javaContext` object or discarded by its current unscoped constructor.

Each result needs an opaque entry-point ID; exact provider/module/source-set;
native binary name and separate launch/declaring owners; owned source/range;
method descriptor/invocation/inheritance and launch-profile facts. The envelope
correlates snapshot, context/schema and platform/SDK identities, effective
source/target and explicit launcher release. Source ownership is provider-owned,
including custom/generated/test roots; the IDE must not derive binary names or
launchability from names, paths or workspace-symbol matches.

The classic-declared-v1 declared-main profile is the first source increment.
Inherited candidates, Java 25 extended/compact forms and qualified preview modes
remain in the final feature. Java 25 admits non-private static or instance main
candidates with zero arguments or String[]; parameterized candidates take
precedence, and instance invocation needs eligible instance creation. See
[JLS 25 §12.1.4](https://docs.oracle.com/javase/specs/jls/se25/html/jls-12.html#jls-12.1.4).
Source syntax, preview options and launcher release require separate evidence;
the implemented increment uses the same explicitly selected compilation/launch SDK.
Metadata-only Java 26/27 context does not qualify these semantic results.

Planned bounds are default 100/max 200 candidates, total serialized response at
most 1048576 UTF-8 bytes, binary name at most 1024 bytes, path at most 4096 bytes,
and nonnegative ordered int32 UTF-16 ranges checked against captured text.
Ordering is ordinal and stable by provider/module/sourceSet/binaryName/declaring
owner/path/range/descriptor. Never trim a name/path/range or cut JSON to fit.
Return an explicit bounded limit refusal or complete/truncated/coverage metadata.
`complete` is false for truncation or unqualified required coverage; a profile-only
list cannot masquerade as complete project-wide discovery. An empty list proves
zero mains only when coverage is fully qualified, complete and non-truncated.
Report exact totals only when computed, otherwise null with an observed lower bound.
Cancellation uses the existing requestId registration, SemanticCancellationToken
and scheduler, with checks around acquisition/enumeration/publication and no late
response or retained registration.

Missing/ambiguous ownership, recovered/incomplete bindings, missing native binary
name, stale snapshot/context/SDK, unsupported source/preview/launcher profile and
output limits must remain typed outcomes. Discovery must not compile, execute
build goals, load user classes or run main. SDK/build tools and the consumer keep
compile/run lifecycle. Capability/schema advertisement and each executable Story
are admitted only with implementation qualification; no new operation is advertised
by this document.

Documentation correction: the Java context authority schema now lists v2-v7,
matching the existing producer's accepted input versions. It retains its closed
three-field shape and default v2. This resolves the stale enum documentation;
it does not qualify every version/source/SDK combination or widen the producer.
Authority: [ARC42 schema reconciliation](arc42/appendix-requirements.adoc#java-context-authority-schema-reconciliation-2026-11-13).
The original proposal/observation remains historical evidence in
.refactorkit/runs/ide-java-entrypoint-contract-r001/.

### Current source wire details

The closed request adds launchProfile="classic-declared-v1" and optional integer
limit (default 100, maximum 200), workspace-relative normalized path, and positive
integer expectedIndexGeneration. It rejects unknown request/scope fields and
requires languageId=java. Scope contains exactly providerId, moduleId and
sourceSetId; javaContext retains its existing closed three-field shape.
The application reacquires the exact scope through JavaProjectContextRequest and
requires complete compiler options. The existing project-wide contextHash is not
redefined as a scope hash: scope is separately verified/echoed and bound into IDs.

A ready result has schemaVersion="java-entry-points-v1", the correlated request/
snapshot/index/context/schema, compiler provenance/platform identities,
launcherRelease, exact scope, launchProfile, scopeAnalysisComplete, complete,
coverage, candidates, returnedCount, totalEligibleCount,
observedEligibleLowerBound and truncated. Totals describe only the requested
profile/scope/path, not all Java launch forms. scopeAnalysisComplete describes
native enumeration; complete remains false while required forms are unqualified.

Candidates have opaque entryPointId (java-entry: plus SHA-256), native binaryName/
declaringBinaryName, JVM descriptor, invocation, inherited, generated, effective
sourceLevel/targetLevel, and source: {path, range}. Ranges are zero-based UTF-16.
The first profile returns descriptor ([Ljava/lang/String;)V, static invocation and
inherited=false. No application class is loaded and no main body executes.

Refusals use the existing intelligence envelope (schemaVersion=1, status=refused,
kind=entryPoints, error.code/message). Qualified controls include
java.context.contextStale, intelligence.cancelled and
java.entryPoints.profileUnqualified; incomplete compiler-option facts retain
the provider's typed refusal. Other authority/input/output boundaries still need
their declared qualification matrix. The bounded source payload is at most
1044480 UTF-8 bytes. The native transport now separately enforces a 1048576-byte
entire NDJSON frame, including its newline, for success/error/cancellation.
The encoded JSON-RPC ID is at most 1024 UTF-8 bytes (JSON quotes/escaping included);
invalid IDs yield INVALID_REQUEST with null response ID before registration.
The semantic requestId is a nonblank string of at most 128 characters.

server.capabilities for intelligence.query advertises
javaEntryPointsClassicDeclared/javaEntryPointsBoundedFrame/javaEntryPointsCancellation
as true; javaEntryPointsInherited/javaEntryPointsJava25 remain false. Native
cancellation proves queued disposal before a blocking reply, not-found after
cleanup, successful session recovery and clean EOF without late replies. The
generic daemon response budget stays unchanged for unrelated operations.

### Opt-in inherited and Java 25 response v2

The IDE's Java 25 source/cursor request extends the same
`REQ-JAVA-PROJECT-CONTEXT-001`; it does not create a second entry-point feature.
The [qualification satellite](requirements/java-entry-point-launch-profiles.md)
reuses `inherited-v1` and `java25-v1`. Current source code provides the separately
closed native response only with the explicit outer request field:

```json
{
  "kind": "entryPoints",
  "languageId": "java",
  "entryPointSchemaVersion": "java-entry-points-v2",
  "launchProfile": "java25-v1"
}
```

Supply the existing requestId, expectedSnapshotHash/index generation, exact
provider/module/source-set scope and javaContext SDK/context correlation fields.
Optional path remains relative to the workspace; optional position requires path
and uses UTF-16 coordinates. Copy selectors from the resolved context; do not infer
module identity from a folder or package. Omitting entryPointSchemaVersion retains
v1, including its refusal for the newer profiles. Unknown version selectors reject
instead of falling back.

The source qualification cell is SDK25, source25/target25 and non-JPMS, with an
exact provider-owned compile closure. Main/test and reactor source dependencies
remain distinct from candidate ownership. The response includes global
snapshotHash/contextHash, separate analysis.snapshotHash, ordered attested
classpath inputs, worker identity and selected launcher SDK/executable hashes.
Each candidate supplies the launch binaryName, main declaringBinaryName,
descriptor, invocation and inherited flag. source identifies the launch type's
path/range/form; declaration identifies the method's full source declaration or
actual attested binary URI/input hash. Instance candidates include proven
zero-argument construction; the engine never executes main to discover it.

Position selection uses the innermost native type. An inner type without main
does not fall back to an outer type. Invalid document offsets and split surrogate
pairs refuse. Scope/file enumeration reports known construction failures through
ineligibleTypes with source, binaryName and typed reason, retaining other runnable
types. ineligibleCount/ineligibleTypesTruncated and candidate counts/truncated
retain exact totals and output bounds; complete requires both lists to be complete.
Explicit cursor or single-type-source construction failures remain typed refusals.
Unsupported/unproved analysis cannot become a complete empty response.

The closed response is defined in the
[intelligence schema](api-0.2-intelligence-query-schema.json). IDs/provenance remain
stable for repeated identical inputs and exclude process IDs, timestamps, private
overlay paths and request IDs. Legacy capability flags still describe v1 and remain
unchanged. `server.capabilities` advertises `javaEntryPointsV2`,
`javaEntryPointsV2Inherited`, `javaEntryPointsV2Java25` and
`javaEntryPointsV2SavedSnapshot` for the opt-in source implementation. These flags
identify the operation/version; the response's qualified cell and exact context
still govern whether a particular query is supported. They do not qualify another
SDK/source/target/JPMS cell or immutable editor overlays. Independently exercised
packaging remains open.

[ARC42 owns integrated status](arc42/appendix-requirements.adoc#java-entry-point-java25-upgrade-contract-2026-11-15).
Native provider/protocol and real-launcher controls qualify only the recorded
source cells. Additional provider/source/target/launcher/JPMS, binary-origin and
limit transport cells, daemon stdio and independently exercised packaging remain
`actionable`. These source results do not authorize an IDE inference fallback or
replacement of the sealed v1 candidate described below.

The isolated candidate ZIP SHA-256 is 1b9a66b6f41df42cf1b274efd6b589ef3c46a1e0236a240976a6313e1f80c4d8.
Its root/archive and measured limits are in
.refactorkit/runs/ide-java-entrypoint-wire-r001/handoff.json. The measured matrix is
Linux x86-64/SDK21/release8/Maven main/test, including secondary/nested varargs
and same-named classes in different source sets. Source and extracted candidate
pass the same native wire and legacy query checks. Direct comparison with sealed
M2, using actual embedded Java and no global Java on PATH, passes after read-only
sealing. The peak-size byte boundary is not yet a separately executed matrix.
The original final inherited/Java25/compact-preview/provider/SDK/ownership and
0.8.0 gates remain actionable; keep the default IDE runtime unchanged.

### Selected SDK and effective target qualification

Authority: [ARC42 SDK/target increment](arc42/appendix-requirements.adoc#java-entry-point-sdk-target-2026-11-13).
The application now projects the target from complete effective compiler options
(`release`, otherwise `target`), retaining provider attributes as fallback. It
does not infer the target from the source level or launcher SDK. Ambiguous or
redacted option facts refuse. The native Story qualifies SDK21/release8,
SDK21/source-target8, SDK25/release25, SDK25/source-target25 and SDK25/release8,
plus the typed refusal for incomplete compiler options. These are classic
declared-main fixtures; SDK25 here does not qualify extended Java25 launch forms.

The same six cases pass on source and a new archive-extracted candidate. The full
pipeline passes 32 behavioral cases/128 steps across the stated native and
in-process routes, Java325 (5 existing skips) and daemon64 (no skips). The isolated
sealed ZIP SHA-256 is 408781a00caf6f259c2f9ff3a4dae3c68ad19b4c4ce7aad1223c6324978d1a06
(93,326,606 bytes/222 files). Exact paths, gates and source/XML/payload hashes are
under .refactorkit/runs/ide-java-entrypoint-sdk-target-r001/. Its version remains
0.7.0/API0.2; previous candidates and the default IDE installation are unchanged.

An exploratory disposable-fixture compiler comparison also exposes a remaining
gap: SDK21 `-source 8 -target 8` accepts List.of(), whereas this candidate refuses
entry-point analysis for that source. `--release 8` correctly rejects that API.
This is actionable platform-policy work, not full source/target API qualification;
the six simple classic cases above must not be generalized to that claim.

The consumer reports native GUI Maven/Gradle success after Build and resync
(2 scenarios/24 steps). Inspected artifacts say release-no-bundle and
packagedApplication=false. A read-only probe of a different retained Maven copy
reproduces analysisUnavailable with unresolved GeneratedGreeting imports/uses;
its generated-source directory is absent. The deleted original failing workspace
is not independently attributed. Discovery must not run annotation processors to
repair this prerequisite. Evidence: .refactorkit/runs/ide-java-entrypoint-ap-investigation-r001/.

Run of the type enclosing the cursor remains actionable under the same REQ/M2:
source.range currently identifies the main name, not provider-owned enclosing-type
selection. The consumer must not infer launch ownership from file names or ranges
alone. Inherited/Java25/compact-preview, broader provider/ownership, peak-byte and
original 0.8.0 requirements remain open.

### Provider-owned enclosing-type selection

Authority: [ARC42 cursor increment](arc42/appendix-requirements.adoc#java-entry-point-enclosing-type-2026-11-13).
`intelligence.query`/`entryPoints` accepts optional `position: {line, character}`
only with `path`. Coordinates are nonnegative int32 UTF-16 values in captured
source; unknown fields, nonintegers and position without path are invalid params.
An out-of-file position or split surrogate pair is `java.entryPoints.positionUnavailable`.
The same explicit provider/module/source-set, SDK, snapshot/context/schema and
launchProfile are required. A path owned by another source set still refuses.

The JDT ACL captures immutable native type binary names and whole declaration
ranges, including types without main. The application selects the uniquely
innermost type containing the position in a half-open range. Missing/recovered
bindings, ambiguous/overlapping ownership or absent enclosing type refuse;
`java.entryPoints.enclosingTypeUnavailable` identifies absent/nonunique selection.
The type inventory is bounded to 16384 entries and selection is linear with
cancellation checks. Neither file names nor a nearby main define launch ownership.

For a ready positioned query, optional `selection` contains
`{mode: "enclosing-type", path, position, binaryName, range}`. The binary name and
range come from native type binding/declaration facts; range uses start/end
line/character. Candidates belong only to that selected owner. An inner type with
no declared classic main returns zero candidates with its positive selection;
it never falls back to the outer or sibling main. `complete=false` still exposes
unqualified inherited/Java25/compact forms. Candidate `source.range` remains the
main-name range. Unpositioned replies omit selection and retain their contract.
The capability flag is `javaEntryPointsEnclosingType: true`.

Native RED6/GREEN6/24 covers primary/secondary/nested method bodies, a dormant
inner type without outer-main fallback, outside-type refusal and wrong-source-set
refusal. The primary-body position follows a supplementary character, exercising
UTF-16 coordinates. Four existing steps and runner are reused, with no dependency.
The final source/candidate pipeline passes 44 behavioral cases/176 steps, fresh
Java325/5 existing skips and daemon64/no skips. Direct embedded-Java probes also
verify primary/dormant selection before and after read-only sealing.

New isolated ZIP SHA-256: 42175de77634c9647ea012c5ce84009fdb83b74e87fea94534badc2d1aecdbe2
(93,335,132 bytes/222 files), still0.7.0/API0.2. Exact paths and evidence are in
.refactorkit/runs/ide-java-entrypoint-cursor-r001/. Prior candidates/default IDE
installation are unchanged. Consumer acceptance for this new candidate is pending;
the IDE reports source-file Run success on earlier408781a0, not cursor acceptance.

Remaining actionable work includes wider declared-plain-JDK qualification
(see the subsequent provider increment; the conventional provider stays partial),
source/target SDK-API baseline, broader provider/type/position/resource matrices,
and all final inherited/Java25/compact and C02-C17 gates. The previously missing
response-schema branch is addressed by subsequent documentation maintenance:
[response JSON schema](api-0.2-intelligence-query-schema.json) now includes a closed
ready entryPoints branch and optional selection, without changing existing query
branches or runtime. [ARC42 schema record](arc42/appendix-requirements.adoc#java-entry-point-response-schema-reconciliation-2026-11-13)
owns the bounded structural check and its validation limits.

### Explicit plain-Java project model

Authority: [ARC42 declared provider](arc42/appendix-requirements.adoc#java-declared-project-provider-2026-11-13).
Latest inventory qualification: [literal package roots](arc42/appendix-requirements.adoc#java-declared-project-literal-package-roots-2026-11-13).
`java-declared-v1` is an opt-in provider for Java projects with no Maven/Gradle
model. Create `.refactorkit/java-project.json` explicitly; discovery never creates
or repairs it. Absence retains the partial conventional provider and its typed
missing-compiler refusal. The closed [declaration schema](java-declared-project-v1-schema.json)
documents the input shape; ARC42 owns qualification and runtime bounds.

```json
{
  "schemaVersion": "java-declared-project-v1",
  "sdkHome": "/absolute/path/to/selected-sdk",
  "modules": [{
    "id": "app",
    "root": ".",
    "sourceSets": [{
      "id": "main",
      "kind": "main",
      "sourceRoots": ["."],
      "generatedSourceRoots": [],
      "outputDirectories": [".ide/java/classes"],
      "compileClasspath": [],
      "runtimeClasspath": [".ide/java/classes"],
      "release": 17,
      "proc": "none"
    }]
  }]
}
```

Every field shown is required. Modules/source sets have unique explicit IDs;
kind is main, test, integration-test or custom. Module/source/generated/output
paths are workspace-relative and normalized, within the declared module root;
module/source dot roots become an empty workspace-relative root on the wire.
Generated roots must also occur in sourceRoots. Output ownership is unambiguous.
Explicit roots are scanned literally, with no conventional directory-name
exclusions: a physical package folder named build remains Java source input.
Compile/runtime classpaths are independent ordered declarations of normalized
relative or absolute paths, not augmented by current-directory or Maven defaults.
Only declared future outputs may be absent; other dependencies must materialize.

The analysis closure starts with the selected roots, followed by owners of output
paths explicitly present on compileClasspath. An unrelated source set is not
implicitly visible. The consumer owns compilation/execution and must honor the
attested SDK, release/proc:none, selected roots, classpath and outputs. No default
output is assumed. Compile/run changes require resync and new snapshot/context
tokens. The existing project.context and entryPoints APIs expose the actual
provider/module/source-set; the consumer must not derive a main name locally.

Missing/invalid declarations, unsafe roots, unavailable classpaths and descriptor
conflicts are typed outcomes. Maven/Gradle descriptors cannot be replaced by this
plain-Java declaration. SDK mismatch and stale data retain their existing typed
refusals. The provider reads owned source/configuration and bounded no-follow
classpath inputs; it executes no build/plugin/processor/application code.
Java26/27 remains metadata only for this provider under v6/v7; the provider-specific
metadata matrix and semantic source support are not inferred from an installed JDK.

Final source and sealed artifact qualify SDK21/release17 flat/default-package and
main/test, plus SDK25/release25 custom roots with actual JAR/directory dependencies
and explicit-output source visibility, physically inside package directories named
build. The full pipeline passes56 cases/224 steps,
Java325/5 existing skips, daemon64/no skips and fresh C490/no skips; the shared
workspace-root prerequisite separately passes core131/no skips. An old/new direct
artifact comparison shows the old conventional refusal and new native Main/cursor,
using embedded Java without global Java on PATH and leaving all inputs unchanged.

Candidate ZIP SHA-256: 118024a3ade62685447ec4d680c84dc9addac4d4e08a2ec256fae17307ad034d
(93367323 bytes/222 files), still0.7.0/API0.2. Exact root/archive/proofs:
.refactorkit/runs/ide-java-plain-project-r003/handoff.json. Candidate85b8ca42 was
superseded before handoff;9cc0 checked cells remain historical, with its physical
build-directory gap independently reproduced and corrected by1180. Prior payloads
and default installation are unchanged. New-r003 consumer GUI acceptance is pending.
Wider module/test/generated/
SDK/diagnostics/hostile/resource cells, source-target API semantics, inherited/
Java25/compact and original C02-C17/release gates remain actionable.

The unchanged declaration schema and seven archived native ready replies pass the existing
Ajv6 validator on their common Draft7/2020 keyword subset, with ten negative input
controls and exact equality of the decoder's closed key sets. This uses a Draft7
validation copy, not a full Draft2020-12 meta-validator or new runtime test.
No tool dependency is installed. [ARC42 documentation upkeep](arc42/appendix-requirements.adoc#java-declared-project-documentation-2026-11-13)
records that boundary and the remaining validation capability gap.
Latest documentation evidence: .refactorkit/runs/ide-java-declared-root-package-doc-r001/;
the schema/decoder key sets and runtime payload are unchanged.


### Java25 native editor compiler diagnostics

Authority: this section describes the API0.2 wire contract; [ARC42 owns integrated
qualification](arc42/appendix-requirements.adoc#java25-native-editor-compiler-diagnostics-2026-11-15).
`intelligence.query` keeps `kind: "diagnostics"`, the explicit acquired `javaContext`
and complete `immutable-editor-overlay` source frame. There is no new selector or
capability. The existing schemaVersion1 response remains closed. `complete: true`
means all compiler errors for the captured provider snapshot, excluding lint
warnings and build/processor/application execution.

Selected SDK25/source25/target25 non-JPMS source sets use the SDK public compiler
parse/analyze API, with source sets isolated by provider ownership and captured
source visibility. For these results `providerId` is
`java-sdk-compiler-diagnostics-v1` and `backend` is
`jdk25-javac-compiler-errors-v1`. Other existing cells retain
`java-jdt-bindings-v1` and their JDT backend; a frame requiring both producers uses
`java-sdk-and-jdt-compiler-diagnostics-v1` and the two backend names joined by `+`.
Native errors retain `java.javac.compiler.err.*` codes, original messages and exact
UTF16 source ranges; JDT codes remain unchanged. Context/schema/platform identities,
provider snapshot, overlay and document-version hashes retain their meaning.
There is no invented SDK-qualification field: consumers check the acquired
source-set compiler facts and platform identities rather than infer a level from
the provider name or installed runtime.

Valid compact files named `record`, `var` or `yield` must not become restricted
explicit-type errors. Real unresolved names, illegal explicit types and invalid
implicit filenames remain errors. Incomplete/unlocated/oversized compiler output
is refused rather than reported as a clean complete frame. Native acquisition
failure, timeout and unqualified compiler arguments have typed
`java.diagnostics.*` refusals; stale context/draft and queued JSON-RPC cancellation
retain their existing contracts. The recorded acceptance covers Maven SDK25
source/target25, main/test and independent modules, stale drafts/context and queued
cancellation. Gradle/declared/native mixed-backend/JPMS/other SDK and source levels,
in-flight resource/timeout tests, standalone saved-source routes and fresh
packaged consumer verification remain separately actionable. Existing sealed
packages are unchanged.


### Native SDK26 editor diagnostics and legacy API-profile boundaries

The existing `REQ-JAVA-PROJECT-CONTEXT-001` now qualifies selected SDK26,
source26/target26 non-JPMS Maven editor diagnostics through `project.context.v7`.
Both `--release26` and explicit `-source26 -target26` select that SDK's public
compiler API, with backend `jdk26-javac-compiler-errors-v1`; SDK25 retains its
existing backend. The worker parses/analyzes captured drafts, disables processors
and generation, verifies input currency and returns exact UTF-16 error ranges.
No request field, endpoint, public schema or default JDT dependency changes.

Native API26 value admission is independent from source grammar and transport
schema. Context v2-v5 still refuse native API26 with
`java.platform.compilerApiUnqualified`; v6/v7 retain their existing API26 values.
API27 with older grammar remains separately qualified. Legacy source26 still
refuses before publication instead of being clamped to25. Preview26 diagnostics
retain `java.context.compilerOptionsIncomplete`; general source27 queries retain
`java.context.sourceAnalysisUnqualified`, including on the private patched JDT
pair. Gradle/JPMS and wider native scope combinations need separate qualification.

Six native diagnostics cases and six context-schema cases pass separate Stories.
The latter validates actual refusal/available frames against all six unchanged
Draft2020-12 schemas with existing Ajv8.20.0 and modern-keyword controls. The
intermediate candidate a8d1dc5f is withheld because its API26 legacy boundary
predates this correction. A corrected packaged handoff remains separate from
source evidence and from the preserved Java25 artifact01659.
[ARC42 authority](arc42/appendix-requirements.adoc#java26-context-api-schema-boundary-2026-11-18).


The corrected private SDK26 archive04170b14 has an explicit review-only handoff:
18 extracted native BDD cases,12 direct diagnostics cases and42 full-schema
intelligence frames pass. An earlier additional stale-draft probe produced an
unexpected fail-closed acquisition refusal; repeated attempts do not reproduce
it and do not establish its cause. The candidate is not adopted and the finding
is not marked fixed. Consumer evaluation must read the complete limits and
original trace in
`.refactorkit/runs/ide-java26-native-diagnostics-candidate-20261118-r001/handoff-review.json`.
[ARC42 observation authority](arc42/appendix-requirements.adoc#java26-review-candidate-availability-2026-11-18).
