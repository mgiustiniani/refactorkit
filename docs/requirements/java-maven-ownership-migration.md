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
FQCNs, imports and public binary identity. Materialized files are also checked by
`JavaGeneratedSourcePolicy`; generated headers/annotations refuse even when the
containing root was not declared generated. Spring, Jakarta/JPA and Jackson
annotations across the complete moved root plus exact quoted moved FQCNs in other
tracked files elevate the plan to high risk with deterministic locations/warnings.

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

Property evidence runs 128 deterministic lexical variants across LF/CRLF,
whitespace, namespace prefixes, comments, CDATA, processing instructions and
quoted `>` attributes. Every accepted variant emits one artifact text edit and
reproduces every other byte exactly; malformed and property-backed inputs return
typed refusal without edits.

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

Packaged acceptance previews the same fixture through all three transports,
checks the POM diff plus Java rename and proves the exact workspace hash and
metadata state are unchanged. Daemon and MCP module tests additionally apply and
roll back the combined plan. Linux x86-64, Windows x86-64, macOS x86-64 and macOS
arm64 pass in run `29903077059`; the independently explicit module-directory and
coordinate-migration rows remain open.

## REQ-JAVA-MAVEN-MODULE-RENAME-001 — Bounded direct-child module rename

Status: implemented-and-validated within one bounded direct-library fixture row.
`REQ-JAVA-MAVEN-MODULE-RENAME-001` contributes one repository-unique functional
scenario/case tagged `@implemented-and-validated`. Independent OpenJDK 21.0.11
review returned `PASS_FOR_PROMOTION` (evidence manifest SHA-256
`6633a8de15a320024f76311575d99f7c6d3bec877bb07ba6f05177efcc5d58d1`;
summary SHA-256
`76a2d8d508fbec9786abfb30bc25a0953f41cf6c163a0508e25433a6530d3b02`).
The focused module-rename runner passed 1/1 scenario and 50/50 steps (log
SHA-256 `b882c9393147fb52e6106329e15b521b3ab19274eb4866a25f8737e5b27fc479`;
Cucumber JSON SHA-256
`d2ff5c1c87b533ad4c760274b6fd191b68f0113e71cf2b268af7831c48e65958`).
This requirement status does not promote a support-matrix row or qualify broad
module rename/move, protocol surfaces, recipes, packaged/native/cross-platform
execution, disposable real projects, general Maven projects, or a release.

### First direct-library row

The implemented first row plans `java.renameMavenModule` directly through the
Java library against one immutable snapshot of the caller-declared,
authoritative, complete offline Maven reactor. It uses a no-follow disposable byte copy of the permanent
fixture with one aggregator and 20 exact direct child modules. The permanent
fixture is never mutated. The request is exactly:

- old module directory `catalog-model`;
- new module directory `catalog-domain`; and
- caller-explicit `newArtifactId=catalog-domain`.

Directory ownership and Maven coordinate identity are independent. A directory
rename never infers an `artifactId` change. If `newArtifactId` is omitted, the
child artifact remains `catalog-model`, dependency coordinates remain unchanged,
and only the independently authorized directory and parent-module path changes
may be planned. The first row supplies `newArtifactId` explicitly and therefore
authorizes only the `catalog-model` to `catalog-domain` artifact component
change.

### Exact editable origins

The planner must prove all editable origins from the same immutable effective
reactor and raw POM evidence before selecting any edit:

1. the child POM's own direct top-level `<project>/<artifactId>` literal is
   exactly `catalog-model`; the `<parent>/<artifactId>` is not the child
   coordinate and must remain unchanged;
2. the root aggregator contains exactly one direct, literal
   `<project>/<modules>/<module>` origin for `catalog-model`;
3. every dependency artifact text selected for change is a direct literal
   `<dependency>/<artifactId>` origin that the effective model proves resolves
   to the exact old child coordinate. In the permanent fixture this is the one
   `catalog-pricing/pom.xml` dependency origin; transitive consumers are not
   edited.

Only three exact zero-based element-text ranges change:

- `catalog-model/pom.xml` `9:14-9:27`, the child project artifact;
- root `pom.xml` `74:12-74:25`, the direct module entry; and
- `catalog-pricing/pom.xml` `13:18-13:31`, the proven direct dependency artifact.

Every other POM byte, including comments, whitespace, line endings, namespace
syntax, processing instructions, unknown elements, the dependency's
`${project.version}` text, and unrelated coordinates/configuration, remains
byte-identical. Maintained Woodstox 7.1.1 StAX2 location information over the
same hash-bound raw bytes is the sole structure/range authority. DTD processing,
external general and parameter entities, and external DTD access are denied. The
planner neither serializes an effective model back to XML nor falls back to an
operation-owned hand-written tokenizer.

Nested modules, profile-origin declarations, property/interpolation-backed
*edited values*, inherited origins, duplicate or otherwise ambiguous origins,
and any origin not uniquely mapped from effective evidence refuse without an
edit. A property elsewhere in the same dependency does not authorize editing it
and does not replace the requirement for a literal artifact origin.

### Ordered staging and deterministic preview

One normalized, non-overlapping `WorkspaceEdit` contains all POM changes and all
file moves. The `FileEdit.Modify` for `catalog-model/pom.xml` is ordered before
that POM and the other child files are renamed to the corresponding
`catalog-domain/**` paths; the remaining POM modifications and file-renames also
have deterministic normalized ordering. The child inventory moves without
changing relative paths or file bytes other than the authorized POM range.

The planner simulates that exact ordered edit, rebuilds one complete staged
effective reactor from the staged descriptor/source bytes, and runs authoritative
before and staged Maven/JDT diagnostics over the required impact closure. It
uses no Maven or wrapper execution, lifecycle, plugin, annotation processor,
settings file, mirror, proxy, server, credential, credential helper, or network
request. A missing/incomplete staged reactor, unavailable diagnostic environment,
cycle, origin drift, or introduced/changed error refuses before plan publication.
Repeated planning from the same request, snapshot, raw/effective-model evidence,
and provider inputs returns identical normalized edits, affected paths, evidence,
warnings, and diagnostics; the existing opaque `PlanId` is not semantic
determinism.

### Authoritative post-image evaluation dependency

A build-descriptor edit changes hash-bound module and Build Model evidence.
`WorkspaceEditSimulator` can produce the exact tracked file candidate `C1`, but a
copy retaining `S0.modules`, `S0.classpathEvidence`, and `S0.buildModels` is not
the authoritative post-image. The row uses the implemented bounded language-neutral core application port,
which returns one transient deeply detached immutable value: one authoritative
`ProjectSnapshot` plus diagnostics for the exact candidate.

Core must preserve the candidate's normalized workspace root, exact
source/auxiliary partition, tracked paths/language IDs/bytes, source extensions,
and ignored directories. Only modules, classpath evidence, and build models may
be rehydrated, with existing workspace-containment and external-classpath policy.
Baseline evaluation must reproduce `S0` and `D0`; staged evaluation must produce
exact `S1` and `D1`. After provider completion, `PatchEngine` repeats workspace
and operation-lease revalidation under its lock before WAL, proves rendered file
images equal `C1`/`S1`, and writes schema-v8 `PREPARED` with
`postSnapshotHash=S1.hash`.

Post-apply evaluation must reproduce exact `S1.hash` and the complete `D1`
multiset. Mismatch or unavailability automatically rolls back the same
transaction, after which restored evaluation must reproduce `S0.hash` and `D0`.
The current `DiagnosticsGate.enabled`/`disabled` behavior and existing hashes
remain exact for diagnostics-only operations. The evaluation is invocation-local
and non-durable; schema-v8 remains the sole WAL/idempotency/exact-result store,
with no schema bump, new store, God service, or core Java/Maven/JDT dependency.
REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-001 through `004` independently passed
16/16 scenarios and 128/128 steps (log SHA-256
`bba1414c3f21f05a7487364af135631574a4eb0dca1668a2285f7436e205fcd2`;
Cucumber JSON SHA-256
`c56c28cec512a57c01e1e9aced0ea6f903b88339e661ffdc80f106044b3eb2a6`).

### Apply and exact rollback

Preview performs no mutation. Apply is a separate caller-explicit invocation of
`PatchEngine.apply` with explicit authorization and the operation's implemented authoritative
staged-reactor evaluation mode. `PatchEngine` owns the exact S0/C1/S1 sequence,
post-provider under-lock revalidation, diagnostic regression decision, and
schema-v8 journal identity before mutation. One successful apply creates exactly
one existing write-ahead-journal transaction for the complete workspace edit.
Normal explicit rollback advances that same transaction rather than creating a
second one and restores the exact non-engine bytes and path kinds; a fresh
caller-owned authoritative scan/evaluation must attest the restored
source/auxiliary inventories, original reactor identity, `S0` snapshot hash, and
`D0` diagnostic baseline. Automatic rollback after post-apply evaluation failure
performs the restored `S0`/`D0` evaluation inside the apply attempt.

The planner is a stateless Java/Maven domain/application planning service in the
existing Java Language Adapter. Its command, raw POM documents, effective-model
projection, origin/range evidence, staged model, diagnostics, and returned
`PatchPlan` are immutable values. It introduces no aggregate, entity, repository,
cache, service lifecycle, or durable store. The existing transaction journal
remains the WAL and exact apply/rollback record; `PatchEngine` remains the only
managed mutation authority.

### Explicitly excluded from the first row

The first row does not cover nested modules; profile, property-managed, inherited,
or ambiguous edited origins; module creation; `groupId`, `version`, `packaging`,
`type`, or `classifier` changes; JPMS/module-path reactors; CLI, daemon, LSP, or
MCP surfaces; recipes; packaged-runtime, native-host, or platform qualification;
disposable real-project acceptance; or general Maven projects. It does not close
the broad J1 module rename/move roadmap checkbox.

The earlier prototype defects remain historical negative evidence rather than a
description of the promoted row. The accepted implementation requires exactly
five ordered `FileEdit` entries: the three parser-authorized modifications above,
then rename `catalog-model/pom.xml` to `catalog-domain/pom.xml`, then rename
`catalog-model/src/main/java/com/acme/catalog/legacy/Product.java` to the same
relative path below `catalog-domain`. Apply journals exact `S0`/`S1` identities in
one schema-v8 record; normal rollback advances that same record and restores the
original reactor, bytes, paths, snapshot, and diagnostic baseline.

Woodstox 7.1.1 is scoped to `refactorkit-java` compile/runtime only and is absent
from the core runtime; its reviewed JAR SHA-256 is
`02b9d022e9d47704ff8a7a859a0dbfd3b2882a8311eb7ff1e180f760ccda2712`.
Parser probes passed exact offsets and DTD/external-DTD/general-entity/parameter-
entity denial. Static evidence is bounded and non-zero overall: `StageFacts`,
`GateExpectation`, `ReactorView`, `ModuleView`, and `SourceSetView` have zero raw
and zero real EI/EI2 findings; the one `AuthoritativeDiagnosticsEvaluation` EI
warning is non-real over a defensively copied unmodifiable list and deep mutation
probes passed. PMD was `NO-SOURCE` for Kotlin, not a cleanliness claim, and the
wider configured SpotBugs baseline remains non-blocking.

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

## Qualification status and next sequence

1. The language-neutral authoritative snapshot-and-diagnostics evaluation is
   implemented-and-validated for its exact S0/C1/S1, deep-detachment,
   containment/fingerprint, same-record automatic-rollback, restored-baseline,
   and diagnostics-only compatibility cases.
2. The exact direct-library module-rename row is implemented-and-validated for
   immutable descriptor staging, parser-authorized origin proof, complete offline
   staged reactor, exact diagnostics, explicit apply, one transaction, and normal
   rollback.
3. Retain typed refusal for property-managed/ambiguous origins, unsafe or missing
   structure, unavailable providers, and evaluator shell or identity violations.
4. Add broader library fixtures, then expose and qualify CLI, daemon and MCP
   packaged behavior on all four native platforms only through separate rows.
5. Nested modules, create-module, additional coordinate dimensions, JPMS,
   disposable real projects, and general Maven populations remain future rows;
   the broad module rename/move roadmap checkbox remains open.
