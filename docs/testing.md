# Testing strategy

See AGENTS.md §18 for the authoritative testing rules.

Status: implementation-informed after `v0.2.0-beta` P1/P2 golden tests and
initial P3/P4 safety/contract tests. The current golden suite contains 21 cases
covering shipped patch-producing operations.

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
    expected-plan.json  ← { "status"?, "operation"?, "summary"?, "minAffectedFiles"? }
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
./gradlew :modules:refactorkit-testkit:test
```

### Current 21 golden cases

| Case name | Operation | Status | Coverage focus |
|-----------|-----------|--------|----------------|
| `rename-class-user-manager` | `renameClass` | PREVIEW | Simple class rename. |
| `rename-class-with-references` | `renameClass` | PREVIEW | Cross-file references. |
| `rename-class-invalid-identifier` | `renameClass` | REFUSED | Invalid Java identifier refusal. |
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

## Patch safety and protocol contract tests

P3/P4 beta coverage now includes focused safety and integration-surface contract
checks:

- PatchEngine: stale snapshot refusal, outside-workspace path refusal,
  overlapping-edit rejection, and rollback restoration for modify, create,
  rename, and delete edits.
- Daemon JSON-RPC: `refactor.preview` → `refactor.apply` → `patch.rollback`,
  missing params, unknown plan, and stale plan/snapshot error code contracts.
- MCP: `renameClass` preview/apply/rollback, invalid tool errors without stack
  traces, and refusal of outside-workspace or not-in-snapshot file resources.
- LSP: `executeCommand` refusal contracts for unknown plan IDs and unknown
  commands.

## Remaining beta coverage gaps

The current suite covers the P1/P2 beta additions and initial P3/P4 safety and
contract tests. Remaining expansion should focus on gaps not yet represented by
golden or protocol tests:

- framework strings, generated code, unresolved-symbol, and public API risk paths;
- `organizeImports` documented limitations beyond the clean no-op case;
- `safeDelete` generated-code and external-configuration limits;
- `extractMethod` additional conservative refusal paths;
- `changeSignature.*` refusal coverage for overloads, method references,
  generated code, hierarchy/public API risk, and unsafe defaults;
- `importExternalJavaClass` provenance, multiple public type, and package rewrite
  edge cases;
- recipe/orchestration cases only for operations advertised as shipped;
- broader daemon contracts for project/symbol/diagnostic methods;
- broader LSP contracts for definition, references, prepareRename, rename,
  codeAction, documentSymbol, diagnostics, and successful command workflows;
- broader MCP contracts for external import, context bundles, and remaining
  scoped resources.

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

- `JavaOrganizeImportsPlanner` sorts and deduplicates imports but does **not**
  fully remove unused imports without type analysis.
- `local-rename` tests use `CommentLiteralFilter` heuristics; native binding
  analysis can bypass it later.
- Golden tests copy `before/` into a temp dir; temp dirs are cleaned up after each
  run.
- `GoldenTestRunner` ignores `.refactorkit/` transaction-log directories when
  comparing actual output against `after/`.
