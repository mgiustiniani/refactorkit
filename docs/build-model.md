# Build Model SPI

Status: first P2B implementation slice; internal API, not yet a stable `1.0`
contract.

ADR 0010 requires ecosystem discovery to produce a language-neutral model before
additional deep JVM adapters duplicate Java/Maven assumptions. The initial core
contract lives in `org.refactorkit.core.BuildModels.kt`.

## Contract

```text
BuildModelProvider
  -> BuildModel
       providerId
       status: AVAILABLE | PARTIAL | OFFLINE_MISSING | UNAVAILABLE | EXECUTION_REFUSED
       BuildModule[]
         BuildSourceSet[]
           kind: MAIN | TEST | INTEGRATION_TEST | CUSTOM
           sourceRoots
           generatedSourceRoots
           outputDirectories
           classpathEntries
           scoped moduleDependencies
       BuildModelDiagnostic[]
```

`OFFLINE_MISSING` distinguishes an otherwise understood model whose required
artifacts are absent under offline policy from a structurally `UNAVAILABLE`
model. `EXECUTION_REFUSED` is reserved for providers that cannot produce their
requested model without denied build-code execution; the Gradle declarative
provider instead returns `PARTIAL` because it safely produces bounded heuristics.

`BuildModelDiscoveryPolicy` independently controls network, build-code execution,
and credential access. Defaults deny all three. `BuildModelRequest.selections`
adds bounded provider-scoped active/inactive profile IDs; Maven accepts only
safe profile identifiers and records the effective selection in hash-bound model
attributes. Providers discover metadata only;
they never mutate workspace files or bypass `PatchEngine`.

Source/generated/output paths are normalized workspace-relative metadata and
reject absolute/traversal paths. External classpath entries may remain absolute
because local dependency artifacts live outside the workspace. Module dependency
edges must resolve to a module in the same model.

## Snapshot and compatibility

`ProjectSnapshot.buildModels` is hash-bound. `PatchEngine` retains the exact model
while validating pre/post source images, and existing POM/BOM/artifact evidence
continues to detect on-disk build-input drift under the workspace lock.

API `0.2` keeps the existing `Module` fields for compatibility. The Java adapter
now has explicit `BuildModelProvider` implementations and provider identities:

- `maven-effective-v1`: embedded effective model, plugin/lifecycle execution
  denied, credentials denied, network denied unless anonymous opt-in; bounded
  Kotlin Maven roots and JVM target/toolchain declarations are metadata only;
- `gradle-declarative-v1`: bounded literal main/test/integration/custom Java and
  Kotlin source sets, generated roots, Java/Kotlin outputs, JVM targets, Java
  levels, and project edges; Gradle
  settings/scripts/tasks/plugins and Tooling API execution remain denied even if
  the generic request allows explicit execution;
- `java-conventional-v1`: conventional Java/Kotlin JVM source/output layout
  without an effective ecosystem model;
- `kotlin-jvm-projection-v1`: non-executable Kotlin-only source ownership view
  over those JVM models, bound to explicit compiler toolchain provenance.

The scanner projects proven compatibility `Module` facts through these providers.
Core exact and longest-prefix ownership queries preserve provider, module,
source-set, generated-root, and status provenance. JDT parser/overlay
environments, `moveSourceRoot`, external Java import targeting, formatter
selection, and package-path ownership now consume those queries, with a
compatibility fallback only for snapshots that do not carry build models.

## Integration summary

Daemon `project.summary` exposes bounded metadata only: provider/status, policy,
typed diagnostic codes, modules, source sets, roots, outputs, and module edges.
It intentionally omits external classpath paths and diagnostic messages to avoid
leaking local repository layout or secrets. Capability discovery advertises
`buildModelSummary`, `sourceSets`, and `credentialRedaction`.

MCP `project_summary` exposes the same high-level provider/status/source-set
information without classpath contents.

## Authoritative Java/Maven diagnostics boundary

Status: active, product-critical. Full-reactor modeling and post-model module
filtering have a first packaged row; no complete epic claim. See
[`arc42/08-crosscutting-concepts.adoc`](arc42/08-crosscutting-concepts.adoc),
[`requirements/java-maven-authoritative-diagnostics.md`](requirements/java-maven-authoritative-diagnostics.md),
and ADR 0012.

For authoritative Java diagnostics, a `BuildModelRequest` starts at the
caller-declared full Maven reactor root. The provider constructs the active
reactor and independent main/test environments before a module selector filters
results. A child path is not a license to discover ancestors or siblings outside
the declared workspace. Each source set must carry exact roots, only safely
declared and materialized generated sources, Maven-scoped reactor/external
visibility, Java release/platform identity, provider evidence, and typed
availability. Missing test evidence does not make main unavailable.

The Build Model remains non-executable metadata. Maven, wrappers, lifecycle
goals, plugins, annotation processors, credential helpers/settings credentials,
and project code do not run implicitly. Exact Java platform signatures and JDT
provider identity remain separate attested inputs to diagnostic authority rather
than being inferred from the host JDK or reduced packaged runtime. Named
JPMS/module-path reactors remain typed unsupported until their dedicated model
slice is complete.

## Remaining P2B work

- retire compatibility `Module` projection after API-versioned migration of
  external library consumers;
- implement credential-safe private repository support only if required and only
  under the new-provider boundary in ADR 0011; explicit Maven profiles, active
  effective/custom roots, classifiers, `test-jar`, variant mediation,
  `systemPath`, and checksum-verified anonymous Central opt-in are implemented;
- decide Gradle executable-model policy and implement integration/custom sets;
- add provider capability/contract snapshots and pagination/limits;
- continue validating the abstraction through compiler-backed Kotlin analysis
  and mixed Java/Kotlin identity before freezing it.
