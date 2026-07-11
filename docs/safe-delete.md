# Safe Delete

Status: implementation-informed for `v0.3.0-SNAPSHOT`. Safe delete is
preview-first and only removes Java type source files through a `PatchPlan`.

## Command

```bash
refactorkit safe-delete --symbol com.example.LegacyType [--force] [--apply] <root>
```

Supported symbols are Java classes, interfaces, enums, records, and annotation types discovered in
the scanned project snapshot.

## Success conditions

A safe-delete preview is considered reliable only when:

- the target type symbol is found in the scanned Java symbol index;
- the declaration source file is present in the snapshot;
- no Java source references are found in scanned files, unless `--force` is used;
- clean JDT analysis uses exact type-binding references; parse/classpath warnings
  cause an explicit lexical fallback warning instead of claiming semantic certainty;
- the affected file list contains the declaration file that would be deleted;
- warnings have been reviewed by a human or calling agent.

The generated patch deletes the declaration source file only. It does not edit
build files, resource files, generated sources, downstream projects, or external
configuration. Exact JDT evidence distinguishes same-simple-name types in
different packages and reports binding-matched source locations. When JDT evidence
is unavailable or not clean, the preview states that lexical fallback was used.

## Refusal conditions

The planner returns a refused plan when:

- the symbol is not found or is not a deleteable Java type;
- the declaration file cannot be found in the current snapshot;
- Java source references are found and `--force` is not set.

A refused plan is a safety result. Agents and clients must not fall back to
global text deletion or manual filesystem removal. Show the references, ask for a
new plan after code changes, or require an explicit human decision.

## Warnings and manual-review areas

Safe delete is intentionally conservative, but beta users must still review these
areas before applying:

- **Build files:** `pom.xml`, `build.gradle`, Gradle convention plugins, Maven
  plugins, annotation-processor configuration, `mainClass`, shading/relocation,
  test include/exclude patterns, and generated-source wiring are not scanned for
  class references.
- **Generated code:** generated sources, JPA metamodels, MapStruct/micronaut/
  Dagger/Spring generated artifacts, and checked-in generated files may still
  refer to the deleted type or may be regenerated from it.
- **Reflection and service loading:** `Class.forName`, `MethodHandles`, SPI files
  such as `META-INF/services/*`, serialization allow-lists, native-image config,
  scripting, and string-based class names are not proven safe.
- **Framework configuration:** Spring XML/YAML/properties, component scans,
  `@Qualifier` strings, JPA persistence-unit config, JPQL strings, Jackson type
  names, and repository conventions can reference types outside normal Java
  imports. Framework annotations in the declaration file escalate the preview to
  high risk.
- **Tests and fixtures:** Java test source files in the snapshot may be scanned,
  but test resources, golden files, snapshots, build-tool test configuration,
  Mockito/string-based references, and external integration tests are not
  exhaustively checked.
- **Unknown downstream consumers:** deleting public API from a library can break
  projects that are not in the current workspace, published artifacts, scripts,
  documentation examples, and binary compatibility expectations.

## Forced mode risk

`--force` allows a preview even when Java references are already known. This is
high risk and the current warning states that the known references were ignored
and the build will break. Use forced mode only when a human has decided that all
remaining references are intentional dead code or will be handled in a separate
reviewed change.

## Rollback expectations

If applied through RefactorKit, deletion is recorded in a transaction and can be
rolled back with:

```bash
refactorkit patch rollback <transaction-id> --root <root>
```

Rollback restores workspace files tracked by the transaction. It does not repair
external systems, downstream projects, build caches, generated artifacts, or
manual changes made after apply. Always run diagnostics and relevant tests after
apply and again after rollback.
