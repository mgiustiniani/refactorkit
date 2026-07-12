# Testing strategy

See AGENTS.md §18 for the authoritative testing rules.

Status: implementation-informed after `v0.2.0-beta` P1/P2 golden tests,
initial P3 safety tests, initial P4 daemon/MCP contract tests, hardened P4 LSP
command-contract coverage, focused P6 external-importer hardening, and P7
packaging/release-verification progress. The current golden suite contains 22
cases covering shipped patch-producing operations.

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
  rename, and delete edits.
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
image state, and proves exact clean-restart compensation. Raw journal truncation
is tested at four byte boundaries, and `/dev/shm` provides a conditional distinct-
store WAL apply/rollback test. Actual power-loss remains a separate RC acceptance
gate.

## Build Model SPI acceptance

`BuildModelsTest` validates provider/module/source-set graph invariants, rejects
unknown module edges and unsafe absolute/traversal source metadata, and proves
that build-model changes alter the project snapshot hash. Maven reactor acceptance
projects main/test/generated sets into `java-project-model-v1` and proves that a
test-scoped artifact is absent from the main source set but available to test and
generated-test sources. Daemon contract tests verify redacted source-set summary
and capability flags without exposing the local home/classpath.

## Java diagnostics acceptance

`MavenReactorAnalysisAcceptanceTest` builds an isolated effective-model reactor
with inherited Java 21, domain/application/infrastructure/acceptance modules,
records, text blocks, switch/record patterns, imported BOM management, a generated
local test artifact, main/test scope isolation, cross-module definitions and
references, one-root missing-artifact diagnostics, and BOM/JAR drift refusal.
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
