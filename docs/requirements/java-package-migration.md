# Java Package Migration Requirements

Status: requirement-first acceptance contract for `0.7.0-SNAPSHOT`.

Integrated architecture/status authority is the [ARC42 requirements appendix](../arc42/appendix-requirements.adoc); this file owns Java package-migration behavior and its bounded acceptance.

## Scope

`movePackage` migrates Java compilation units whose declared package exactly
matches a source package. It does not rename Maven module directories,
`artifactId` values, POM references, Dockerfiles, CI workflows, scripts,
documentation, or arbitrary configuration strings. Those remain separate future
refactoring capabilities.

## Requirements

### RPK-PKG-001 — Packaged YAML runtime

The jlink runtime used by the installed distribution shall include every JDK
module required to load and execute SnakeYAML recipes, including `java.desktop`
for `java.beans`. Distribution acceptance shall invoke a YAML recipe through the
packaged launcher, not the Gradle development classpath.

### RPK-PKG-002 — Fail-closed move-class fallback

A move-class planner without clean JDT binding evidence shall edit only files
with proven lexical references. It shall never add an import merely because a
file shares the old package. If safe simple-name ownership cannot be proven, it
shall refuse with code `java.moveClass.lexicalScopeUnsafe`. Unrelated files shall
remain byte-identical, and no output may join `package ...;import ...`.

### RPK-PKG-003 — Exact package compilation-unit migration

`movePackage` shall move every non-generated Java compilation unit whose declared
package exactly equals `from`, across main and test source roots. This includes
public and package-private top-level types and `package-info.java`. Subpackage
declarations shall remain unchanged unless a future explicit recursive option is
introduced. Imports and fully qualified references from subpackages and other
reactor modules shall be updated. Exact Java source-path literals of the form
`src/{main|test}/java/<package>/<moved-unit>.java` inside non-generated Java
sources shall also be updated because they name a file in the migration set.
An exact quoted package-name literal equal to `from` shall be updated for Java
architecture-contract consumers; literals containing subpackages or arbitrary
text remain out of scope and unchanged.

The summary shall report moved type declarations by kind and package-info units
separately, rather than reporting only a class count.

### RPK-PKG-004 — Generated-source exclusion

Generated sources, Maven `target/` outputs, Gradle build outputs, and generated
source roots may be analyzed but shall never enter package-migration or
unrestricted recipe organize-import plans. Recipe import organization shall be
restricted to touched, staged, non-generated Java compilation units.

### RPK-PKG-005 — Conflict and package validation

Malformed source/target package names and any target path/FQN conflict shall
refuse before producing writable edits. No file may be overwritten implicitly.

### RPK-PKG-006 — Stable recipe reduction and preview

Sequential staged operations shall reduce to a recipe-wide workspace edit whose
text ranges are valid against the original snapshot. Preview rendering shall be
deterministic and shall not apply staged-snapshot ranges to original file bytes.

### RPK-PKG-007 — Baseline-aware diagnostics

`runDiagnostics` shall compare the initial snapshot with the staged snapshot and
report deterministic counts for baseline, staged, introduced, resolved, and
unchanged errors. Diagnostic identity is code/category/path/range/message after
workspace-relative normalization.

Default policy: refuse only when introduced errors are non-zero. Unchanged
baseline errors do not refuse. `strict: true` additionally requires zero staged
errors. Diagnostic delta shall be retained in the step result and recipe summary.

### RPK-PKG-008 — One transaction and exact rollback

Apply shall commit the complete recipe-wide plan through exactly one managed
`PatchEngine` transaction. Rollback shall restore every fixture file byte-for-byte
and remove all created paths.

### RPK-PKG-009 — Maven reactor source/classpath visibility

Offline Maven discovery shall expose sibling-module production source roots and
resolved reactor output/classpath evidence to dependent main/test JDT analysis
without executing project plugins, lifecycle goals, annotation processors, or
network access.

### Existing `movePackage` provider/IDE integration gap

Status: `actionable`; priority P1 under the recorded IDE-first project priority. This is an
integration/API qualification gap, not missing planner behavior and not a new
package-migration requirement. `RecipeEngine` already implements the Java
`movePackage` step and the focused requirement tests cover its behavior. However,
`refactorkit recipe run` remains experimental, there is no stable Java CLI/daemon
entry point that advertises this step, and the current daemon `operation=recipe`
managed preview does not accept Java recipes. The IDE therefore has no qualified
provider route for an IDE-reviewed package move.

Consumer-reported IDE gap — 2026-10-21: current IDE contracts, adapters and UI
contain no stable `movePackage` route, so a reviewable package-move preview and
Apply are unavailable from a Java workspace. The planner is reachable only as an
internal `RecipeEngine` step through the experimental recipe path; the daemon
`operation=recipe` preview rejects Java recipes. This confirms the existing
provider/IDE integration gap and does not establish a stable IDE route. Both this
daemon limitation and the finite J1 catalogue's exclusion of recipe-private
`movePackage` remain in force. The consumer report itself did not authorize a
public API or IDE action; the separate user-authorized follow-up below records
the subsequent scope direction.

User-authorized API/J1 follow-up — 2026-10-21: close this same provider/IDE gap
under the existing package-migration requirement and Java API/J1 gate; do not open
a duplicate requirement. The authorized scope is a direct typed Java provider route
for the existing bounded `movePackage` behavior, rather than public execution of
arbitrary Java recipes. This addendum records work authorization and supersedes the
baseline's recipe-private `movePackage` public-surface non-claim only for this
bounded route. The historical finite J1 baseline remains unchanged. The authorization
itself did not promote capability stability or managed-write authority; those
required the acceptance gate below.

Historical interim guard (superseded 2026-10-22): until the direct route passed,
`movePackage` remained absent from stable Java capability discovery and IDE actions.
The current `operation=recipe` rejection of Java recipes was an expected limitation,
not a substitute for direct-route preview/apply acceptance.

Provider/API qualification — 2026-10-22: the bounded direct Java route now passes
the existing package-migration gate without adding a duplicate requirement. The
daemon accepts `refactor.preview` with `operation=movePackage`, `languageId=java`,
and typed `fromPackage`/`toPackage` arguments under an explicit Java SDK context.
The Java language capability advertises `stable`, `managed-stable`, and
`structural-parse`; `javaContextRefactoring.operations` contains the same operation.
The requirement-bound Story verifies read-only preview with staged diagnostics,
exact stale-snapshot refusal, one PatchEngine transaction, exact-package main/test
units and `package-info.java`, package references and path literals, generated-source
exclusion, exact rollback, and continued rejection of generic Java recipe execution.
The single-scenario local JDK 21 Cucumber result and analyzer evidence are recorded
in [the provider acceptance receipt](evidence/java-package-migration-provider-r001.json).

This closes the RefactorKit provider/API portion of the P1 item. The IDE action is
still `actionable`: the IDE must add the package-move entry and review flow and run
its consumer/native qualification before advertising it. The historical finite J1
catalogue remains unchanged, and arbitrary Java recipe execution remains excluded.

The finite J1 catalogue remains the historical baseline and is not rewritten.
This addendum authorizes only the bounded direct provider route described above;
generic recipe composition remains excluded. The provider has qualified locally;
the IDE surface still requires independent consumer qualification.

### RPK-PKG-003 text boundaries

The scenario
`REQ-JAVA-PACKAGE-MIGRATION-003#move-package-preserves-unrelated-java-source-text`
exercises an SDK-bound Maven package move through the daemon on JDK 21. Package
declarations, imports and qualified Java references are selected from parsed
source ranges. The literal exceptions are whole ordinary string tokens that
exactly match the old package or the source path of a moved Java unit.

The regression checks that a URL containing the old qualified name, exact
class-name strings, prose containing a source path, comments, text blocks, and
CRLF line endings survive Preview and Apply. Rollback uses the original
`javaContext` and the committed snapshot and restores every input byte and path.
An embedded comment that interrupts the affected package name yields the typed
refusal `java.movePackage.commentedPackageName`.
[ARC42 acceptance](../arc42/appendix-requirements.adoc#java-move-package-source-text-2026-11-21).

## Focused fixture

Tests shall use a self-contained multi-module Maven reactor representing:

- an application-port module;
- an infrastructure-adapter module containing mutually dependent public and
  package-private exact-package units plus `package-info.java`;
- a bootstrap consumer module;
- exact-package and subpackage test consumers;
- a generated source under `target/generated-sources`.

The fixture shall verify preview, diagnostics delta, one apply transaction,
build/source correctness, and byte-exact rollback independently of the Magrathea
checkout.

## External acceptance

The real Magrathea checkout is read-only evidence. Acceptance runs from commit
`6532f9b654c8b833a991eddc5712361ac64dcf85` in a disposable copy, previews before
apply, verifies the preview does not mutate files, applies only in that copy,
runs Maven/layering/hygiene/diff checks, then rolls back and compares the complete
pre-apply tree. Existing changes in the real working tree must never be reset,
modified, staged, or committed by this work.
