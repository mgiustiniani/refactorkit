# Safe Delete

Status: implementation-informed for `v0.3.0`, with a narrow snapshot-bound
Maven reactor route for daemon managed Apply. Other Java `safeDelete` plans remain
preview-only. General closure and release qualification remain unqualified. The central authority is the [ARC42 requirement
`REQ-JAVA-SAFE-DELETE-CHAR-001`](arc42/appendix-requirements.adoc). Safe delete
is preview-first and removes only Java type source files through a `PatchPlan`.

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
- no Java source references exist and captured text has no literal type-name
  match, unless `--force` is used; the Maven-POM-only review exception below
  remains preview-only;
- the scanner has complete bounded-text coverage; missing or incomplete coverage
  refuses the preview even when `--force` is used;
- clean JDT analysis uses exact type-binding references; parse/classpath warnings
  cause an explicit lexical fallback warning instead of claiming semantic certainty;
- the affected file list contains the declaration file that would be deleted;
- warnings have been reviewed by a human or calling agent.

When clean JDT analysis finds no Java references and the only captured-text
matches are in `pom.xml` files owned by an available, source-backed Maven Java
module or recorded as effective-model parent/aggregator inputs for one,
RefactorKit returns a HIGH-risk,
confidence-`0.3` review-only proposal with `LEXICAL_FALLBACK` evidence and the
POM paths and lines. Its warning states that complete project-wide analysis is
not advertised. An unrelated or unmodeled reactor POM, a file merely named
`pom.xml` under resources, or references in
Gradle scripts and other captured text, remain refused when `--force` is
absent. An unclean or unavailable JDT analysis does not qualify for the
POM-only exception.

The generated patch deletes the declaration source file only. It does not edit
build files, resource files, generated sources, downstream projects, or external
configuration. Exact JDT evidence distinguishes same-simple-name types in
different packages and reports binding-matched source locations. A separate
literal-name scan checks captured build and resource text; it is deliberately
conservative and may flag unrelated same-name tokens. When JDT evidence is
unavailable or not clean, the preview states that lexical fallback was used.

## Managed Apply boundary

The daemon grants managed Apply only for a JDT-clean, unused, package-private
top-level ordinary class in an available `maven-effective-v1` reactor when all
Java sources have unique, non-generated source-set ownership; Maven source-root,
compiler-option, artifact, and annotation-processor facts are complete; annotation
processing is explicitly disabled; the bounded text scan is complete; no
unmodeled workspace inputs or binary resources are present; and no target-name
Java string literals, recognized dynamic lookup APIs, or framework annotations
are present. The plan carries a Maven closure
authority lease bound to the snapshot, target, workspace edit, and SDK project
context. Apply revalidates that authority against the current snapshot and refuses
stale inputs before mutation. The plan remains HIGH risk and requires explicit
approval because external and downstream consumers are outside the workspace.

Every plan outside that exact shape remains preview-only and managed Apply
refuses with `PLAN_VALIDATION_FAILED` /
`java.safeDelete.referenceClosureIncomplete`. Missing or partial build facts,
unresolved JDT bindings, generated sources, processor configuration that is not
explicitly disabled, binary resources, dynamic lookup APIs, Java string-literal
consumers, non-Java sources, unmodeled inputs, source/text references, and
Maven-POM-only review proposals do not receive authority. `--force` does not
bypass these gates. A binary resource is
counted in snapshot metadata and prevents the project from being called closed.

The static daemon capability reports
`managedApplyAuthority: conditional-snapshot-bound`; the preview's
`referenceAnalysis` reports `referenceClosure: maven-reactor-complete`,
`completeProject: true`, and `managedApplyAuthority: snapshot-bound` only for a
qualified SDK-bound preview. The static `completeProject` value remains false
because qualification is project- and plan-specific.

Gradle remains fail-closed: the declarative provider is `PARTIAL`, and the
synchronized `gradle-effective-v1` model is not yet composed into the scanner
snapshot used by safe delete. The MCP gate still refuses all Java safe-delete
plans; this qualification is daemon-only. The CLI's separate explicit `--apply`
and packaged/native release qualification are also outside this slice.

The POM-only proposal is also rejected by `PatchEngine` with
`evidence.insufficient` before workspace locking or transaction creation. The
LSP `safeDelete` command refuses lexical-fallback plans with `PLAN_REFUSED`
instead of returning a client-applicable workspace edit.

## Refusal conditions

The planner returns a refused plan when:

- the symbol is not found or is not a deleteable Java type;
- the declaration file cannot be found in the current snapshot;
- Java references or captured-text references outside the Maven-POM-only review
  case are found and `--force` is not set;
- complete bounded text-scan evidence is absent, over budget, or otherwise
  incomplete, regardless of `--force`.

A refused plan is a safety result. Agents and clients must not fall back to
global text deletion or manual filesystem removal. Show the references, ask for a
new plan after code changes, or require an explicit human decision.

## Warnings and manual-review areas

Safe delete is intentionally conservative, but beta users must still review these
areas before applying:

- **Captured text scope:** Maven POMs, captured Gradle Kotlin DSL scripts,
  recognized text extensions, and any regular file under a directory named
  `resources` are scanned for literal simple type-name tokens. This includes
  Groovy Gradle scripts, XML, YAML, properties, service files and common query
  text. The scan does not parse build languages or resolve framework semantics.
  Convention plugins outside the captured scope, Maven plugin execution,
  annotation-processor configuration, shading/relocation, test patterns and
  generated-source wiring can still refer to the type indirectly.
- **Generated code:** generated sources, JPA metamodels, MapStruct/micronaut/
  Dagger/Spring generated artifacts, and checked-in generated files may still
  refer to the deleted type or may be regenerated from it.
- **Dynamic references:** `Class.forName`, `MethodHandles`, computed or escaped
  strings, serialization allow-lists, native-image config and runtime-generated
  names are not resolved. Captured SPI/service files are checked only for a
  literal type-name token.
- **Framework configuration:** captured XML/YAML/properties and common query
  files are checked only for literal simple-name tokens. Component scans,
  `@Qualifier` meaning, JPQL/HQL semantics, Jackson type-name rules and repository
  conventions are not interpreted. Framework annotations in the declaration
  file escalate the preview to high risk.
- **Tests and fixtures:** captured resources and recognized text extensions are
  checked. Binary resources are counted but not interpreted and block the
  managed-Apply closure claim. Unrecognized text formats outside resource
  directories, ignored build output, Mockito/runtime strings and external
  integration tests are not exhaustively checked. Any non-ignored workspace
  symlink makes coverage incomplete; the scanner does not follow links.
- **Unknown downstream consumers:** deleting public API from a library can break
  projects that are not in the current workspace, published artifacts, scripts,
  documentation examples, and binary compatibility expectations.

## Forced mode risk

`--force` allows a preview even when Java references are already known. This is
high risk and the current warning states that the known references were ignored
and the build will break. Forced mode does not bypass missing or incomplete text
coverage. Use it only when a human has decided that all remaining references are
intentional dead code or will be handled in a separate reviewed change.

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

## Qualification boundary for the existing requirement

This section records the qualification boundary of
`REQ-JAVA-SAFE-DELETE-CHAR-001`. The existing characterization qualifies bounded
Java type deletion behavior, not general safe deletion. One new exact Maven-reactor
story adds snapshot-bound daemon Apply/rollback; a separate binary-resource story
proves that opaque resources remain preview-only. These stories do not qualify
Gradle, MCP managed Apply, CLI `--apply`, cross-workspace consumers, or
packaged/native releases.

The planner's deleteable symbol set is Java classes, interfaces, enums, records,
and annotation types. It does not implement member deletion. Methods (including
overload-specific selection), fields, constructors, enum constants, and inherited
or overridden members are outside the current supported operation. Any future
member support must state its exact declaration kinds and prove constructor,
overload, inheritance, override, and implementation-family closure; the current
type-delete evidence cannot be reused as that proof.

Reference evidence combines Java source references from exact JDT bindings (or
lexical fallback) with literal simple-name matches in captured workspace text.
The latter includes Maven POMs, captured Gradle Kotlin DSL, recognized text
extensions, and regular files under `resources`; it has bounded inputs and marks
coverage incomplete on read failure, invalid UTF-8, size/count limits, changing
files, or any non-ignored symlink. It does not parse build or framework semantics,
resolve dynamic/escaped names, inspect binary assets, or cover unrecognized text
formats outside resource directories. The planner does not establish that the
snapshot contains every downstream consumer. The managed Maven slice proves local
Java-source and captured-text closure only under its stricter eligibility checks;
it does not qualify Gradle or downstream consumer closure.

For unresolved, reflective, generated, or out-of-snapshot reference risk, a
warning by itself is not evidence that Apply is safe. The POM-only case is
explicitly marked incomplete and remains blocked by `PatchEngine`, daemon, MCP,
and LSP Apply routes. The bounded Maven case is the only daemon-managed positive
route; all other Java safe-delete shapes still lack qualified closure. The CLI's
separate explicit `--apply` route remains unqualified.

The bounded workspace-text refusal cells are recorded under
[`REQ-JAVA-SAFE-DELETE-WORKSPACE-TEXT-001`](requirements/java-safe-delete-workspace-text-references.md)
and link back to the central ARC42 requirement. They do not close the broader
boundary: Maven/Gradle cross-module closure, semantic plugin/configuration
analysis, stale-context refusal through managed apply, and exact apply/rollback
restoration remain unqualified. Intentionally unsupported families must continue
to refuse or remain review-only. Implementation responsibility remains with the
Java adapter and `JavaSafeDeletePlanner`; broader requirement priority remains
unassigned by triage.
