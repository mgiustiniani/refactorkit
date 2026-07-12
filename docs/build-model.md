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
       status: AVAILABLE | PARTIAL | UNAVAILABLE | EXECUTION_REFUSED
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

API `0.2` keeps the existing `Module` fields for compatibility. The Java scanner
now projects those proven Maven/Gradle/conventional facts into
`java-project-model-v1`; consumers migrate incrementally rather than through a
large breaking rewrite. The current projection is not the final provider split.

## Integration summary

Daemon `project.summary` exposes bounded metadata only: provider/status, policy,
typed diagnostic codes, modules, source sets, roots, outputs, and module edges.
It intentionally omits external classpath paths and diagnostic messages to avoid
leaking local repository layout or secrets. Capability discovery advertises
`buildModelSummary`, `sourceSets`, and `credentialRedaction`.

MCP `project_summary` exposes the same high-level provider/status/source-set
information without classpath contents.

## Remaining P2B work

- split the Java projection into explicit Maven and declarative Gradle providers;
- migrate JDT ownership/classpath queries from compatibility `Module` fields to
  `BuildSourceSet`;
- harden Maven production edge cases and repository/checksum policy;
- decide Gradle executable-model policy and implement integration/custom sets;
- add provider capability/contract snapshots and pagination/limits;
- validate the abstraction with Kotlin or another JVM adapter before freezing it.
