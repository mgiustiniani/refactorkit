# Testing strategy

See AGENTS.md §18 for the authoritative testing rules.

Status: implementation-informed after `v0.2.0-beta` P1/P2 golden tests,
initial P3 safety tests, initial P4 daemon/MCP contract tests, hardened P4 LSP
command-contract coverage, focused P6 external-importer hardening, and P7
packaging/release-verification progress. The current golden suite contains 23
cases covering shipped patch-producing operations.

Every Gradle `Test` worker uses bounded repository-local temporary storage below
`build/test-tmp/<module>` for both `java.io.tmpdir` and `TMPDIR`. A saturated host
`/tmp` therefore cannot corrupt compiler/toolchain acceptance, and disposable
evidence remains outside user workspaces.

## Unit tests

Each module contains its own unit tests. Run with:

```bash
./gradlew test
```

## Golden file tests

Golden tests live in `testdata/golden/`. Each subdirectory is one test case:

```text
testdata/golden/
  <case-name>/
    before/             ← source tree before the operation
    after/              ← expected source tree after apply; omit for REFUSED cases
    request.json        ← { "operation", "symbol"?, "arguments"? }
    expected-plan.json  ← { "status"?, "operation"?, "summary"?, "minAffectedFiles"?, "warningContains"? }
```

### Supported operations in `request.json`

| `operation` | Required or common fields |
|-------------|---------------------------|
| `renameClass` | `symbol`, `arguments.newName` |
| `renameMember` | `symbol` as `<FQN#member>`, `arguments.newName` |
| `moveClass` | `symbol`, `arguments.to` target package |
| `organizeImports` | `arguments.file` relative path |
| `safeDelete` | `symbol`, optional `arguments.force` |
| `extractMethod` | `arguments.file`, `startLine`, `endLine`, `methodName` |
| `changeSignature.renameParameter` | `symbol`, `arguments.oldParameterName`, `newParameterName` |
| `changeSignature.addParameter` | `symbol`, `arguments.parameterType`, `parameterName`, `defaultExpression` |
| `changeSignature.reorderParameters` | `symbol`, `arguments.newOrder` |
| `changeSignature.removeParameter` | `symbol`, `arguments.parameterName` |
| `importExternalJavaClass` | `arguments.code`, `targetPackage`, `sourceKind`, `licensePolicy` |

### Run from CLI

```bash
# Run all cases
refactorkit test-golden

# Run one case
refactorkit test-golden rename-class-user-manager

# Custom directory
refactorkit test-golden --golden-dir path/to/golden
```

### Run from Gradle

```bash
./gradlew goldenTest
# Equivalent focused module task:
./gradlew :modules:refactorkit-testkit:test
```

### Current 23 golden cases

| Case name | Operation | Status | Coverage focus |
|-----------|-----------|--------|----------------|
| `rename-class-user-manager` | `renameClass` | PREVIEW | Simple class rename. |
| `rename-class-with-references` | `renameClass` | PREVIEW | Cross-file references. |
| `rename-class-invalid-identifier` | `renameClass` | REFUSED | Invalid Java identifier refusal. |
| `rename-class-framework-warning` | `renameClass` | PREVIEW | JPA/framework risk warning assertion. |
| `rename-member-method` | `renameMember` | PREVIEW | Method/member rename. |
| `rename-member-constructor-refusal` | `renameMember` | REFUSED | Constructor rename refused; use class rename. |
| `move-class-simple` | `moveClass` | PREVIEW | Package declaration and path move. |
| `move-class-same-package-refusal` | `moveClass` | REFUSED | Same-package move refused. |
| `organize-imports-simple` | `organizeImports` | PREVIEW | Import sorting/deduplication. |
| `organize-imports-already-clean` | `organizeImports` | PREVIEW | Already-clean no-op preview. |
| `safe-delete-refused` | `safeDelete` | REFUSED | Referenced class deletion refused. |
| `safe-delete-forced-with-references` | `safeDelete` | PREVIEW | Forced delete with references remains explicit. |
| `safe-delete-unused-class` | `safeDelete` | PREVIEW | Unreferenced class deletion. |
| `extract-method-success` | `extractMethod` | PREVIEW | Limited no-argument private void extraction. |
| `extract-method-refusal` | `extractMethod` | REFUSED | Parameter/control-flow limitation refusal. |
| `change-signature-rename-parameter` | `changeSignature.renameParameter` | PREVIEW | Parameter rename and call-site update. |
| `change-signature-add-parameter` | `changeSignature.addParameter` | PREVIEW | Add parameter with default expression. |
| `change-signature-reorder-parameters` | `changeSignature.reorderParameters` | PREVIEW | Parameter order update. |
| `change-signature-remove-parameter` | `changeSignature.removeParameter` | PREVIEW | Remove unused parameter. |
| `external-class-import-preview` | `importExternalJavaClass` | PREVIEW | External class preview with package rewrite. |
| `external-class-import-conflict` | `importExternalJavaClass` | REFUSED | Naming conflict refusal. |
| `external-class-import-license-block-unknown` | `importExternalJavaClass` | REFUSED | Unknown-license import blocked. |

## Patch safety, protocol contract, and importer hardening tests

P3/P4/P6 beta coverage now includes focused safety, integration-surface, and
external-importer checks:

- PatchEngine: stale snapshot refusal, outside-workspace path refusal,
  overlapping-edit rejection, and rollback restoration for modify, create,
  rename, and delete edits. Auxiliary workspace-file acceptance keeps one Maven
  POM outside language source scope while proving staged/post-apply diagnostic
  visibility, exact apply and byte-identical rollback.
- Maven ownership migration: one complete-root Java move plus exact literal
  consumer dependency rewrite is previewed, applied and rolled back as one plan;
  dependent JDT diagnostics consume an isolated offline staged-reactor rebuild.
  Tests retain comments/unknown XML byte-for-byte and cover missing intent,
  identity mismatch, property-backed/duplicate origins, source-set mismatch,
  missing destination dependencies, remaining-source references and dependency-
  cycle refusal. A compatibility-stripped snapshot proves shared core Build Model
  ownership remains authoritative without legacy `Module` source-root projections.
  Framework-annotated and exact quoted-FQCN fixtures elevate risk,
  while generated headers refuse before planning. A deterministic 128-case POM
  property row varies line endings, whitespace, namespace prefixes, comments,
  CDATA, processing instructions and quoted `>` attributes and proves every byte
  outside the authorized artifact text is unchanged. CLI preview plus daemon/MCP
  preview-apply-rollback tests exercise
  the operation-specific staged-reactor diagnostics gate. Packaged acceptance
  previews the same capability through all three transports without changing the
  workspace hash or creating transaction metadata. The machine-readable API
  `0.2` inventory classifies Maven ownership as additive/experimental while the
  existing beta daemon method list remains byte-for-byte ordered and tested.
  Packaged assertions normalize Windows separators, and temporary staged-reactor
  deletion retries only bounded transient handle-release failures before failing
  closed.
- Daemon JSON-RPC: `refactor.preview` → `refactor.apply` → `patch.rollback`,
  missing params, unknown plan, and stale plan/snapshot error code contracts.
- MCP: `renameClass` preview/apply/rollback, invalid tool errors without stack
  traces, and refusal of outside-workspace or not-in-snapshot file resources.
- LSP: `executeCommand` preview metadata (`refactorkitPlanId`, operation,
  status, summary, risk level, and warnings), `renameClass` preview → apply →
  rollback, pending-plan apply behavior, transaction-backed rollback response,
  unknown plan and transaction refusals, unknown commands, and referenced
  `safeDelete` `PLAN_REFUSED` behavior.
- External importer: provenance warning details, GPL high-risk warning/risk
  level, one-public-type plus package-private helper preservation,
  multi-public-type splitting with package/import preservation, and non-Java
  Markdown fence stripping.

## Transaction fault injection

Core tests inject journal failures after new-record force, lifecycle temp-file
force, and lifecycle atomic move. They prove restart-visible complete records or
preservation of the previous record plus temporary-file cleanup at each boundary.
Workspace tests inject deterministic failures after staged-file force and after
each committed workspace image. Acceptance covers unchanged content and temporary-
file cleanup on staging disk-full, full compensation after a partial multi-file
commit, durable `RECOVERY_REQUIRED` after compensation failure, and successful
compensation retry by a clean `PatchEngine` restart.

On Windows, OS release of a killed process's file lock can lag successful
`Process.waitFor`; subprocess acceptance therefore retries only
`workspace.locked` for a bounded five-second post-exit interval. Any other
recovery diagnostic fails immediately, so this does not weaken recovery checks.

Journal subprocess tests force-kill real JVMs after new-record force, lifecycle
temp-file force, and lifecycle atomic move. They prove respectively that complete
`PREPARED`, prior-authoritative, and complete new `APPLYING` records survive.
Startup recovery durably removes strictly named non-authoritative lifecycle temps
under the workspace lock. A workspace subprocess test force-kills a real JVM
after the first of two committed images, inspects the durable `APPLYING`/mixed-
image state, and proves exact explicitly authorized restart compensation. Raw journal truncation
is tested at four byte boundaries, and `/dev/shm` provides a conditional distinct-
store WAL apply/rollback test. Actual power-loss remains a separate RC acceptance
gate.

Read-only lifecycle tests separately assert that fresh recovery inspection,
daemon project open, MCP project scan, and LSP initialize create no `.refactorkit`
directory or `workspace.lock`. Packaged TypeScript and Kotlin semantic-read smoke
hashes hidden metadata as well as source files before and after the full read
sequence. Incomplete journal inspection must report recovery-required without
changing source/journal bytes; only explicit `patch.recover` may acquire the writer
lock and compensate.

## Build Model SPI acceptance

`BuildModelsTest` validates provider/module/source-set graph invariants, rejects
unknown module edges and unsafe absolute/traversal source metadata, and proves
that build-model changes alter the project snapshot hash. `BuildModelQueriesTest`
proves exact and longest-prefix ownership, generated-root classification, and
nested-root disambiguation while preserving provenance. Maven reactor acceptance
projects main/test/generated sets through `maven-effective-v1` and proves that a
test-scoped artifact is absent from the main source set but available to test and
generated-test sources. Planner/importer/formatter tests clear compatibility
ownership fields and prove Build Model authority; reactor acceptance does the
same through staged JDT diagnostics overlays. Daemon contract tests verify exact
versioned DTO/schema keys, workspace-relative module paths, canonical statuses,
capability flags, deterministic bounded root truncation, and redaction without
exposing local home/classpath paths.

## Runtime archive trust acceptance

`scripts/verify-runtime-archive.py` validates checksums before extraction, bounded
safe layout, traversal/symlink/case-collision refusal, reproducible timestamps,
Unix executable metadata, required jlink modules, ELF/PE/Mach-O architecture,
and native launcher execution without `JAVA_HOME`. CI runs it on every native
archive; the release publish job runs it again after downloading job artifacts.
`scripts/test-verify-runtime-archive.py` supplies malicious traversal,
case-collision, checksum-mismatch, and architecture fixtures.

## Native packaged Build Model acceptance

`scripts/smoke-packaged-build-model.py` runs in the Linux and native
Windows/macOS runtime matrix with `JAVA_HOME` removed. It copies the committed
Java 21 Maven reactor, verifies packaged scan and zero diagnostics, proves
rename-only preview does not mutate, applies through approval/PatchEngine, checks
transaction/WAL evidence and post-image diagnostics, rolls back to byte-identical
Java hashes, and refuses a case-folded destination collision. The same script is
runnable locally after `packageCliRuntime`.

## Gradle declarative model acceptance

`GradleDeclarativeModelAcceptanceTest` proves main/integration/generated source
sets, literal project edges, Java 21 JDT resolution, and scanner classpath
enrichment without invoking Gradle. A build-script filesystem side effect remains
absent. Separate acceptance proves workspace escape refusal, `EXECUTION_REFUSED`,
path redaction, and exclusion of outside sources. See
[Gradle declarative build model](gradle-build-model.md).

## Java diagnostics acceptance

`MavenReactorAnalysisAcceptanceTest` builds an isolated effective-model reactor
with inherited Java 21, domain/application/infrastructure/acceptance modules,
records, text blocks, switch/record patterns, imported BOM management, a generated
local test artifact, main/test scope isolation, classifier and `test-jar`
variants, explicit `systemPath` scope/evidence, active-profile effective and
build-helper custom main/test roots, inactive-profile exclusion, source-root
workspace/symlink confinement, explicit profile activation and active-by-default
deactivation, cross-module definitions and references, one-root missing-artifact
diagnostics, and BOM/JAR/system-path drift
refusal. Variant acceptance proves that normal and classified artifacts sharing
GA coordinates are not collapsed, managed version/scope matches type/classifier,
and test JARs never enter main classpaths. Scope-table acceptance proves direct
provided/test dependencies retain owner-visible compile/runtime children while
reactor provided edges remain absent from downstream consumers. The combined
packaged row repeats normal/classified/test-jar/system and scope evidence through
CLI, daemon and MCP under an isolated temporary Maven home.
`MavenNetworkResolutionTest` uses an injected transport to prove offline zero
requests, mandatory SHA-256 verification, successful verified publication, and
mismatch cleanup without relying on the network.
`JavaMoveSourceRootPlannerTest` proves rename-only byte identity, package/module
info inclusion, post-image diagnostics, atomic apply, exact rollback, and typed
unsafe/collision/drift refusals. `samples/java-maven-reactor-21` is the committed
structural acceptance fixture.

JDT syntax/type diagnostics are tested for stable codes, severity, exact ranges,
compiler evidence, and categories. Maven, Gradle, and declared multi-module
samples must diagnose cleanly; Spring/JPA samples intentionally expose unresolved
external framework types without executing builds. A managed rename lifecycle is
re-diagnosed after apply and rollback. Staged diagnostics use an isolated exact
source overlay to avoid resolving against stale pre-edit files.

## Managed text encoding

Core acceptance proves that malformed UTF-8 is refused as
`snapshot.scopeUnreadable` before WAL creation and that a UTF-8 BOM survives a
delete/rollback byte-for-byte. Other source encodings are outside the v1 managed-
text contract and are never guessed or silently transcoded.

## Packaged CLI and release verification smoke tests

P7 packaging verification now covers the self-contained runtime artifact:

- CI builds `refactorkit-runtime.zip` and writes
  `refactorkit-runtime.zip.sha256`.
- CI verifies the checksum with `sha256sum -c` before uploading artifacts.
- CI runs `:modules:refactorkit-cli:smokePackagedCli` with `JAVA_HOME` unset.
  The task checks bundled `java.compiler`, runs signed-selector `definition` and
  `references` against a temporary Java fixture, checks overload precision, and
  verifies source hashes are unchanged.
- CI runs the packaged launcher with `JAVA_HOME` unset.
- CI scans Maven, Gradle, Spring, JPA, and multi-module samples using the
  packaged launcher.
- Release tag builds verify the tag-named checksum, unzip the runtime zip, and
  smoke-test the extracted launcher with `JAVA_HOME` unset before publishing.

The packaged runtime smoke tests are release verification checks, not a
substitute for golden, unit, protocol, or rollback tests.

## Remaining beta coverage gaps

The current suite covers the P1/P2 beta additions, initial P3 safety tests,
initial P4 daemon/MCP contract tests, hardened P4 LSP command tests, and focused
P6 importer hardening tests. Remaining expansion should focus on gaps not yet
represented by golden, unit, or protocol tests:

- framework strings, generated code, unresolved-symbol, and public API risk paths;
- `organizeImports` documented limitations beyond the clean no-op case;
- `safeDelete` generated-code and external-configuration limits;
- `extractMethod` additional conservative refusal paths;
- `changeSignature.*` refusal coverage for overloads, method references,
  generated code, hierarchy/public API risk, and unsafe defaults;
- `importExternalJavaClass` CLI/JSON-RPC/MCP output contracts, refusal wording,
  conflict-policy choices, and additional license-policy combinations;
- recipe/orchestration cases only for operations advertised as shipped;
- broader daemon contracts for project/symbol/diagnostic methods;
- broader LSP contracts for definition, references, prepareRename, rename,
  codeAction, documentSymbol, diagnostics, and command edges beyond the covered
  preview/apply/rollback flow;
- broader MCP contracts for external import, context bundles, and remaining
  scoped resources;
- final tag-specific release notes, changelog, and installation/smoke-test
  instructions recording the verified artifact names, checksums, source tag,
  release commit, and verification commands.

## Real TypeScript toolchain qualification

`scripts/smoke-packaged-typescript.py` runs only against the self-contained
RefactorKit package and the lockfile-pinned CI toolchain in
`qualification/typescript-toolchain`. It verifies stable IDs across fresh server
sessions, path-alias project definition/reference reads, source immutability,
exact `typescript-compiler-exact-v1` before/after diagnostics, forced termination
of the owned real language-server process, provenance-preserving bounded daemon
restart, three-file semantic rename through an alias and re-export, explicit apply,
WAL creation and exact rollback. `scripts/smoke-packaged-kill-recovery.py` additionally
builds a 256-file semantic rename, waits for both durable `APPLYING` intent and a
committed source image, kills the packaged daemon, and verifies that a fresh
packaged daemon restores the exact pre-image and records `ROLLED_BACK`. Because
`PatchEngine` stages every post-image before its first commit, the native observer
uses a 300-second absolute bound plus a 120-second inactivity bound that advances
only when the journal lifecycle or staged-temp high-water mark progresses. Unit
checks prove progressing staging extends the inactivity window and stalled staging
still fails with explicit state/count evidence. CI installs packages with
`npm ci --ignore-scripts`; runtime code never
invokes npm. Upstream unversioned LSP diagnostics are not trusted or relabelled.

## Agent simulation tests

`AgentSimulationTest` in `refactorkit-testkit` simulates full AI-agent workflows:

| Scenario | Module |
|----------|--------|
| Rename class + rollback | java |
| Rename member + rollback | java |
| Move class to new package + rollback | java |
| Safe delete refused because references exist | java |
| Organize imports with deduplication | java |
| Import external class with unknown license | web-importer |
| Import external class with naming conflict | web-importer |

Each scenario follows the standard workflow from AGENTS.md §14:
scan → find symbol → preview → inspect → apply → verify → rollback → verify restored.

## Integration test notes

- Field-rename coverage verifies exact JDT declaration/reference edits preserve
  a shadowing local and an unrelated same-name field, and verifies refusal when
  the target owner already declares the requested field name.
- Annotation-element coverage verifies signed zero-parameter JDT identity,
  read-only definition/reference lookup, and exact rename of the declaration and
  named usage while preserving an unrelated same-signature annotation element.
- Annotation-type coverage verifies lexical/JDT symbol discovery and exact
  binding-backed rename of the declaration and selected annotation usages while
  preserving an unrelated same-simple-name annotation type.
- `JavaOrganizeImportsPlanner` sorts and deduplicates imports, removes
  same-package imports, and uses clean JDT declaration-normalized binding evidence
  to remove unused exact imports. Tests preserve used parameterized-type, generic
  static-method, and wildcard imports; unclean files retain lexical-only cleanup.
- `local-rename` tests use `CommentLiteralFilter` heuristics; native binding
  analysis can bypass it later.
- Golden tests copy `before/` into a temp dir; temp dirs are cleaned up after each
  run.
- `GoldenTestRunner` ignores `.refactorkit/` transaction-log directories when
  comparing actual output against `after/`.
