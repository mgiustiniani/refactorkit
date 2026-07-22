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

## RPK-JAVA-OWN-006 — Transaction and protocols

A successful preview contains Java renames and POM `FileEdit.Modify` entries in
one normalized non-overlapping `WorkspaceEdit`. Apply requires explicit approval,
verifies snapshot and classpath/model evidence, runs exact staged diagnostics
before WAL, writes atomically where possible, rehydrates committed bytes, and
retains normal crash recovery and rollback metadata.

The library operation is `java.moveAcrossMavenModules`. CLI, daemon and MCP expose
the same typed request and refusal codes. API `0.2` routes remain compatible;
`moveSourceRoot` behavior and arguments do not change.

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
