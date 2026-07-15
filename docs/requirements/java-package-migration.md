# Java Package Migration Requirements

Status: requirement-first acceptance contract for `0.7.0-SNAPSHOT`.

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
