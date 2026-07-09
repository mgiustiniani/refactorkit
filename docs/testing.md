# Testing strategy

See AGENTS.md §18 for the authoritative testing rules.

## Unit tests

Each module contains its own unit tests. Run with:

```bash
./gradlew test
```

## Golden file tests

Golden tests live in `testdata/golden/`. Each subdirectory is one test case:

```
testdata/golden/
  <case-name>/
    before/          ← source tree before the operation
    after/           ← expected source tree after apply (omit for REFUSED cases)
    request.json     ← { "operation", "symbol"?, "arguments"? }
    expected-plan.json ← { "status"?, "operation"?, "summary"?, "minAffectedFiles"? }
```

### Supported operations in request.json

| `operation`       | Required fields in `arguments`      |
|-------------------|-------------------------------------|
| `renameClass`     | `newName`                           |
| `moveClass`       | `to` (target package)               |
| `organizeImports` | `file` (relative path)              |
| `safeDelete`      | `force` (optional boolean)          |

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

### Current cases

| Case name                       | Operation          | Status   |
|---------------------------------|--------------------|----------|
| `rename-class-user-manager`     | renameClass        | PREVIEW  |
| `rename-class-with-references`  | renameClass (2 files) | PREVIEW |
| `move-class-simple`             | moveClass          | PREVIEW  |
| `organize-imports-simple`       | organizeImports    | PREVIEW  |
| `safe-delete-refused`           | safeDelete         | REFUSED  |

## Agent simulation tests

`AgentSimulationTest` in `refactorkit-testkit` simulates full AI-agent workflows:

| Scenario                                            | Module          |
|-----------------------------------------------------|-----------------|
| Rename class + rollback                             | java            |
| Move class to new package + rollback                | java            |
| Safe delete refused (references exist)              | java            |
| Organize imports (deduplication)                    | java            |
| Import external class with unknown license          | web-importer    |
| Import external class with naming conflict          | web-importer    |

Each scenario follows the standard workflow from AGENTS.md §14:
scan → find symbol → preview → inspect → apply → verify → rollback → verify restored.

## Integration test notes

- `JavaOrganizeImportsPlanner` sorts and deduplicates imports but does **not** remove
  unused imports (no type analysis available at Level 1).
- `local-rename` tests use `CommentLiteralFilter` (heuristic); native binding bypasses it.
- Golden tests copy `before/` into a temp dir; temp dirs are cleaned up after each run.
- `GoldenTestRunner` ignores `.refactorkit/` transaction-log directories when comparing
  actual output against `after/`.
