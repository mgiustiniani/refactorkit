# Transactional Java ownership migration across Maven modules

Status: active, product-critical, release-blocking J1 requirement.

## Purpose

`java.moveAcrossMavenModules` moves proven Java ownership between modules of one
authoritative offline Maven reactor and, only when explicitly requested, changes
the exact reactor POM edges needed by that move. Java and POM changes form one
previewed `PatchPlan`, one authorization decision and one `PatchEngine`/WAL
transaction with recovery and byte-exact rollback.

This operation is distinct from `moveSourceRoot`, which never edits POMs, and
from `moveClass`/package migration, which change Java package identity without
changing build ownership.

## RPK-JAVA-OWN-001 — Proven source and destination ownership

The source and destination must each have exactly one available Maven effective-
model owner and the same main/test source-set kind. The operation accepts only a
complete source root, complete package or JDT-proven top-level type shape that is
qualified by its current capability row. Generated roots, overlapping paths,
package/path mismatches, duplicate FQCNs, destination collisions, symlink
traversal, unavailable classpaths and ambiguous ownership fail closed.

The first accepted row moves one complete non-generated Java source root between
two existing reactor modules while preserving source bytes, package declarations,
FQCNs, imports and public binary identity.

## RPK-JAVA-OWN-002 — Explicit POM intent

POM changes are never inferred merely from Java package or path changes. Every
module-directory move, parent `<modules>` edit, coordinate change and dependency
rewrite is an explicit independently previewable argument.

The first existing-module row accepts an ordered list of explicit dependency
rewrite requests. Each request identifies:

- the workspace-relative reactor POM;
- the exact literal source `{groupId, artifactId, version, type, classifier}`;
- the exact literal destination identity;
- whether only that dependency occurrence or every identical occurrence in the
  identified POM is authorized.

The requested identities must equal the effective source/destination module
identities. An omitted field may use only Maven's documented literal default; it
must not become wildcard matching.

## RPK-JAVA-OWN-003 — Byte-preserving POM edits

POM handling is lossless and range-based. Only authorized element text or an
explicitly authorized whole dependency/module element may change. XML comments,
whitespace, namespace prefixes, CDATA, processing instructions, unknown elements,
plugin configuration and unrelated dependency declarations retain exact bytes.

Property/interpolation-backed coordinates, inherited declarations, duplicate
matching dependency blocks, profile-dependent ownership or an effective value
whose originating text cannot be identified uniquely produce typed refusal.
RefactorKit does not serialize an effective Maven model back into a POM.

## RPK-JAVA-OWN-004 — Immutable descriptor staging

Build descriptors participating in a managed edit are immutable snapshot inputs,
not language source files. `ProjectSnapshot`/`PatchEngine` must stage, hash,
simulate and rehydrate those bounded workspace documents alongside source files
without broadening language source scope to arbitrary XML.

Baseline, exact post-image, post-apply and rollback Maven/JDT diagnostics consume
the same staged descriptor and source bytes. A descriptor change after preview,
missing staged descriptor or post-apply identity mismatch refuses or triggers
automatic journaled rollback under the existing diagnostics contract.

Local prerequisite evidence: `ProjectSnapshot.auxiliaryFiles` now keeps bounded
reactor POM bytes disjoint from language `files`/`sourceExtensions` while binding
them into snapshot identity. `WorkspaceEditSimulator` and `PatchEngine` stage,
precondition-check, rehydrate, apply and roll those bytes back with source edits.
The first Maven ownership planner now materializes only immutable tracked source
and auxiliary bytes into an isolated temporary workspace, rebuilds the staged
reactor offline with plugins/network/credentials disabled, and uses that model
for preview plus `planner::diagnostics` apply/post-apply gates. Packaged and native
qualification remains required before this requirement is fully accepted.

## RPK-JAVA-OWN-005 — Staged reactor validity and cycles

The complete staged Maven reactor is rebuilt offline with plugin processing,
network access and credentials disabled. RefactorKit rejects:

- new or existing dependency cycles touched by the requested rewrite;
- unresolved effective models or source-set ownership;
- missing required dependency edges;
- ambiguous mediation/classifier/scope results;
- source-set diagnostics introduced anywhere in the dependent impact closure;
- a rewrite that removes a dependency still required by Java remaining in the
  source module;
- a rewrite that fails to provide dependencies required by moved Java in the
  destination module.

Maven build success may be supplementary acceptance evidence but never replaces
RefactorKit's staged model and JDT authority.

Local first-row evidence moves one complete root, rewrites one exact literal
consumer edge and validates the dependent consumer through JDT. It rejects staged
cycles before plan publication, reports a moved-file regression as
`mavenOwnership.destinationDependencyMissing`, and reports a custom remaining
source-root reference as `mavenOwnership.remainingSourceDependency`. Wider
classifier/scope mediation and native qualification remain open.

## RPK-JAVA-OWN-006 — Transaction and protocols

A successful preview contains Java renames and POM `FileEdit.Modify` entries in
one normalized non-overlapping `WorkspaceEdit`. Apply requires explicit approval,
verifies snapshot and classpath/model evidence, runs exact staged diagnostics
before WAL, writes atomically where possible, rehydrates committed bytes, and
retains normal crash recovery and rollback metadata.

The library operation is `java.moveAcrossMavenModules`; the typed planner and the
flat single-rewrite `RefactoringRequest` route are implemented locally. CLI
`java move-across-maven-modules`, daemon `refactor.preview/apply` and MCP
`preview_refactoring/apply_refactoring` expose the same operation and refusal
codes. Apply selects the staged-reactor diagnostics gate by plan operation. API
`0.2` routes remain compatible; `moveSourceRoot` behavior and arguments do not
change.

Local packaged acceptance previews the same fixture through all three transports,
checks the POM diff plus Java rename and proves the exact workspace hash and
metadata state are unchanged. Daemon and MCP module tests additionally apply and
roll back the combined plan. Four-platform native completion remains open.

## Stable refusal codes

The capability defines at least:

- `mavenOwnership.sourceUnrecognized`
- `mavenOwnership.destinationUnrecognized`
- `mavenOwnership.sourceSetMismatch`
- `mavenOwnership.generated`
- `mavenOwnership.dependencyRewriteRequired`
- `mavenOwnership.dependencyRewriteMismatch`
- `mavenOwnership.propertyManagedCoordinate`
- `mavenOwnership.ambiguousPomOrigin`
- `mavenOwnership.cycle`
- `mavenOwnership.remainingSourceDependency`
- `mavenOwnership.destinationDependencyMissing`
- `mavenOwnership.descriptorUnavailable`
- `mavenOwnership.diagnosticsRegression`

Existing generic snapshot, classpath, authorization, WAL and recovery codes remain
applicable.

## Qualification sequence

1. Add immutable non-source descriptor staging to snapshots, simulation,
   PatchEngine preconditions, post-apply rehydration and rollback tests.
2. Qualify a complete-root move between existing modules with one explicit
   literal dependency rewrite, exact comment/unknown-element preservation,
   dependent-impact diagnostics, apply, recovery and rollback.
3. Add typed refusal for property-managed/ambiguous coordinates, cycles, missing
   destination dependencies and dependencies still needed by source Java.
4. Expose and qualify library, CLI, daemon and MCP packaged behavior on all four
   native platforms.
5. Only then add explicit module-directory move, parent `<modules>` edit and
   group/artifact/version migration rows.
