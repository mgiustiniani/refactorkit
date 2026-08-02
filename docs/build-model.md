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

`ProjectSnapshot.buildModels` is hash-bound. For an edit that cannot change
`modules`, `classpathEvidence`, or `buildModels`, retaining those exact fields
while staging new tracked bytes is authoritative and the current
`WorkspaceEditSimulator`/`PatchEngine` hash behavior remains exact. For a
model-changing descriptor edit, however, that simulator result is only the exact
file candidate `C1`; carrying semantic fields forward from `S0` is stale metadata
and must not define the authoritative post-image snapshot or journal hash.
Existing POM/BOM/artifact evidence continues to detect on-disk build-input drift
under the workspace lock.

## Implemented bounded authoritative snapshot diagnostics evaluation

Status: implementation-informed for
`REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-001` through `004` and its bounded
`REQ-JAVA-MAVEN-MODULE-RENAME-001` consumer. All five definitions and their 17
expanded cases are `@implemented-and-validated`. This does not qualify the wider
Java/Maven diagnostics epic, general descriptor-changing operations, a release,
or any protocol, packaged, native, cross-platform, or general-Maven support row.

Core Patch and Transaction Execution exposes an additive language-neutral
application port whose one invocation returns one transient immutable
`AuthoritativeDiagnosticsEvaluation`: one authoritative `ProjectSnapshot` plus
the diagnostics produced for that exact candidate. The value deep-detaches every
caller-owned outer and nested collection reachable through `ProjectSnapshot`,
each `Module`, `BuildModel`, `BuildModule`, and `BuildSourceSet`, the diagnostics
list, and every `DiagnosticDetails.fields` map. Mutation through caller aliases or
exposed views cannot change the construction-time value, and a fresh canonical
hash recomputed from the complete exposed snapshot remains equal to its retained
snapshot hash.

Core preserves the candidate's normalized workspace root, exact
source/auxiliary partition, normalized tracked paths, language IDs and bytes,
`sourceExtensions`, and `ignoredDirectories`. Only compatibility `modules`,
`classpathEvidence`, and `buildModels` may be rehydrated. Before WAL, core—not
the provider—normalizes and enforces workspace containment for module and build
source-set roots. Explicit external regular-file classpath evidence remains
admissible only when core independently recomputes its current no-follow
path/kind fingerprint and it exactly matches the supplied evidence. Arbitrary
roots, symlink substitution, missing or unreadable evidence, and fabricated
fingerprints fail closed; a provider-owned temporary root or different tracked
image is never authority.

The lock-held sequence is exact: baseline evaluation reproduces full `S0` and
diagnostics `D0`; evaluation of simulated file candidate `C1` yields full staged
snapshot `S1` and diagnostics `D1`; after provider completion, core repeats
engine-owned workspace and operation-authority lease revalidation before WAL.
Rendered post-images equal the tracked image in `C1`/`S1`, and the schema-v8
`PREPARED` record uses `S0.hash` and `S1.hash`. Evaluation after commit reproduces
`S1.hash` and the complete `D1` multiset. A hash, diagnostic, or availability
mismatch advances that same record through automatic rollback, after which
restored evaluation must reproduce `S0.hash` and `D0`; no second transaction is
created.

This is additive to the diagnostics-only gate. The exact
`DiagnosticsGate.enabled`/`disabled` call and no-call behavior, provider order,
diagnostic identity, error-multiset regression and preview-approved-regression
semantics, typed failures, and existing simulator-derived hashes remain unchanged
for operations that cannot change hash-bound semantic metadata. The evaluation
is discarded with the apply attempt. Schema-v8 remains the sole
WAL/idempotency/exact-result store: there is no schema bump, new store, event
stream, durable evaluator, God service, or core dependency on Java, Maven, JDT,
or another language adapter.

Independent final review on OpenJDK 21.0.11 returned `PASS_FOR_PROMOTION`
(evidence manifest SHA-256
`6633a8de15a320024f76311575d99f7c6d3bec877bb07ba6f05177efcc5d58d1`;
summary SHA-256
`76a2d8d508fbec9786abfb30bc25a0953f41cf6c163a0508e25433a6530d3b02`).
The authoritative evaluation runner passed 16/16 scenarios and 128/128 steps
(log SHA-256
`bba1414c3f21f05a7487364af135631574a4eb0dca1668a2285f7436e205fcd2`;
JSON SHA-256
`c56c28cec512a57c01e1e9aced0ea6f903b88339e661ffdc80f106044b3eb2a6`).
The bounded module-rename runner passed 1/1 scenario and 50/50 steps. Static
analysis is deliberately not reported as aggregate clean: the five targeted
module-rename evidence types have zero raw/real EI/EI2 findings, the one
`AuthoritativeDiagnosticsEvaluation` EI warning is non-real over a defensively
copied unmodifiable list and deep mutation probes passed, PMD was `NO-SOURCE`
for Kotlin, and the wider configured SpotBugs baseline remains non-blocking.

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

## Mixed-workspace snapshot composition

Daemon and MCP use the language-neutral core `WorkspaceSnapshotComposer` to
preserve one ordered mixed-workspace pipeline without moving concrete provider
policy into core. The authoritative primary snapshot retains workspace, modules,
classpath evidence, build models, and auxiliary files. A non-empty secondary
source inventory may replace normalized-path source entries and contributes only
its files, source-extension declarations, and ignored directories. Conditional
TypeScript build evidence is attached after that overlay; optional current
Kotlin/JVM evidence is attached last because its projection hash binds the
pre-attachment snapshot.

An empty secondary source inventory preserves the exact primary snapshot and does
not attach TypeScript evidence, even when a configuration file is present. The
composer accepts scanners and attachers per invocation, retains none of them,
and propagates their exact failures. It imports no concrete language adapter and
executes no build or project code. Scanner selection, toolchain availability,
refresh/index/session lifecycle, and all mutation authority remain outside this
internal composition contract. Bounded evidence is
`REQ-WORKSPACE-SNAPSHOT-COMPOSER-001..005`; CLI and LSP remain unchanged.

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
availability. Every literal active module declared by the root aggregator must
have a regular POM; a missing descriptor is retained as a typed unavailable
synthetic module with workspace-relative declaration/range/no-follow evidence so
semantic planning refuses before edit selection. Symlink or non-regular path
states are not misreported as ordinary missing descriptors. Missing test evidence
does not make main unavailable. BuildSourceSet
keeps compile and runtime classpaths separate: runtime-only artifacts do not leak
into owner main compilation, but a downstream test source set may consume the
runtime export through an authoritative reactor dependency closure.

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
