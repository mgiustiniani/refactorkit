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
and credential access. Defaults deny all three. Providers discover metadata only;
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
  denied, credentials denied, network denied unless anonymous opt-in;
- `gradle-declarative-v1`: deterministic descriptor heuristics only; Gradle
  settings/scripts/tasks and Tooling API execution remain denied even if the
  generic request allows explicit execution;
- `java-conventional-v1`: conventional source/output layout without an effective
  ecosystem model.

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

## Remaining P2B work

- retire compatibility `Module` projection after API-versioned migration of
  external library consumers;
- complete explicit non-default Maven profile activation and repository/checksum
  policy; active effective source directories and declarative build-helper custom
  roots, classifier, `test-jar`, variant mediation, and `systemPath` coverage are
  implemented;
- decide Gradle executable-model policy and implement integration/custom sets;
- add provider capability/contract snapshots and pagination/limits;
- validate the abstraction with Kotlin or another JVM adapter before freezing it.
