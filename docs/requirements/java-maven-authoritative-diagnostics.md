# Authoritative release-aware Java/Maven diagnostics requirements

Status: active, product-critical, release-blocking J1 prerequisite. Exact Java
8-through-25 and complete Maven variant/scope rows have local packaged evidence;
their four-platform promotion and the complete epic remain unqualified.

## Purpose

Java mutation authority must be derived from the exact Maven source-set,
classpath and Java platform environment. Maven build success is supplementary
acceptance evidence and never repairs incomplete RefactorKit evidence.

## RPK-JAVA-DIAG-001 — Release-aware Java platform authority

For every effective `--release` from Java 8 through Java 25, authoritative
analysis requires explicitly selected, immutable Java platform signatures for
that release. The provider records the requested release, signature-content hash,
platform provider/version and license provenance, and one deterministic identity.
When a qualified JDK supplies the evidence, its canonical platform home and JDK
release metadata hash are also bound. Bounded double reads refuse missing,
malformed, drifting or release-incomplete inputs. The host JVM and packaged jlink
runtime are not implicit project-platform evidence.

A source-only/no-release configuration must select an explicitly qualified JDK
platform and bind that identity into the source-set environment. Otherwise the
source set is typed unavailable; a `<source>` level must never be mislabeled as
`--release` API authority.

Configuring or attesting a JDK `lib/ct.sym` alone does not grant JDT diagnostic
or mutation authority. Release authority requires the complete, separately
accepted JDT environment integration and qualification defined by this epic.
For historical modular releases, ASTParser's public environment validates the
attested JRT system library while the pinned JDT resolver consumes only ECJ's
JEP 247 view of that same hash-attested `ct.sym`; project source and dependency
classpath entries are not replaced. A reduced packaged runtime must include the
ZIP filesystem provider required to read external or bundled `ct.sym` archives.

## RPK-JAVA-DIAG-002 — Full-reactor per-source-set model

A declared Maven reactor root is both workspace and effective-model authority.
Module targeting filters results after full-reactor construction. Main and test
retain separate roots, generated roots, classpaths, dependency visibility,
compiler settings and availability.

## RPK-JAVA-DIAG-003 — Maven visibility

Each source-set environment includes the complete offline effective reactor
closure and exact available external artifacts. The first accepted scope-
derivation row keeps a module's runtime-only artifacts out of its main compile
classpath while exporting them to downstream test compilation through a reactor
edge; the runtime projection is independently hash-bound. The full Maven scope
row derives transitive visibility from both the direct and child scopes: direct
`provided` and `test` artifacts do not export to downstream projects, but their
compile/runtime children remain visible to the declaring project's corresponding
compile/test environment as derived `provided`/`test` dependencies. `system`
artifacts are owner-local and non-transitive. The first mediation row traverses
breadth-first so nearest paths win and declaration order breaks equal-depth ties.
Effective root dependency management is applied before selection and matches the
complete Maven `{groupId, artifactId, type, classifier}` identity; managed
version and scope apply only to that exact identity, and management for one
classifier or `test-jar` must never select a sibling variant. Optional
transitive dependencies and explicit exclusions are omitted. Compile projection
follows compile edges only, while runtime/test projections may follow runtime
edges. Bounded local version ranges inspect at most 1,024 candidate directories
and select the highest matching version. Non-`jar`/`test-jar` artifact types and
Maven relocation metadata produce typed source-set unavailability rather than
guessed classpath entries. Missing required artifacts or ambiguous scope/ownership
makes that source set unavailable. Discovery executes no wrapper, lifecycle,
plugin, annotation processor, credential helper or project code and performs no
network access unless separately authorized.

## RPK-JAVA-DIAG-004 — Availability and cascade suppression

Unavailable roots are typed and source-set scoped. The first accepted row tracks
main/test missing artifacts independently: a missing test-only artifact emits one
test source-set root, suppresses only derivative test binding rows, and preserves
genuine main errors. Main-classpath unavailability propagates through transitive
reactor compile closures; runtime-export unavailability propagates only into
downstream test environments that require it. Independent source sets remain
available. Derivative unresolved-symbol cascades may be suppressed only
when their complete causal input is unavailable;
genuine source diagnostics from complete environments remain visible.

## RPK-JAVA-DIAG-005 — Change-specific authority

Baseline, exact staged post-image, post-apply and rollback diagnostics bind the
same snapshot/overlay mode, source-set and dependent-impact closure, build and
classpath evidence, Java platform identity, provider identity and configuration.
The first accepted impact row changes one upstream Maven main source set and
requires JDT errors from both a transitive downstream main source set and a
downstream test source set; the gate refuses before WAL and preserves exact bytes.
Normal mode rejects introduced errors; strict mode requires zero staged errors.
Diagnostic delta identity includes severity, provider code, evidence, category,
location precision, normalized source path, exact start/end range and message;
code/message equality alone cannot hide a moved or replaced compiler error. The
managed apply gate diagnoses baseline and exact staged snapshots before WAL,
then rehydrates committed bytes and requires the same staged error identities.
A post-apply mismatch or provider failure triggers immediate journaled rollback;
the restored bytes are diagnosed again and must reproduce baseline identities.
Any unavailable affected environment blocks semantic mutation even when the same
unavailability existed at baseline.

For an edit that can change hash-bound modules, classpath evidence, or build
models, _exact staged post-image_ means the implemented bounded authoritative
snapshot-diagnostics evaluation result `S1` and its diagnostics, not a
`WorkspaceEditSimulator` file candidate that retains `S0` semantic metadata.
Core deep-detaches the complete returned snapshot/build/diagnostic collection
graph, preserves candidate root, source/auxiliary partition, tracked
paths/language IDs/bytes, extension scope, and ignore policy, and allows only
modules, classpath evidence, and build models to be rehydrated. Before WAL it
normalizes and enforces module/build-root containment and independently
recomputes the no-follow path/kind fingerprint of explicitly allowed external
regular-file classpath evidence. Baseline equals `S0`/`D0`, provider completion
is followed by under-lock workspace/lease revalidation, schema-v8 records exact
`S1.hash`, committed evaluation reproduces `S1` plus staged diagnostics, and a
mismatch advances the same record through automatic rollback to an evaluated
`S0`/`D0` baseline. Existing diagnostics-only gate semantics and hashes remain
exact, and no new durable store or schema is introduced.

REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-001 through `004` are
`@implemented-and-validated` within this language-neutral bounded application-port
contract. Independent OpenJDK 21.0.11 review returned `PASS_FOR_PROMOTION`
(manifest SHA-256
`6633a8de15a320024f76311575d99f7c6d3bec877bb07ba6f05177efcc5d58d1`;
summary SHA-256
`76a2d8d508fbec9786abfb30bc25a0953f41cf6c163a0508e25433a6530d3b02`).
The focused runner passed 16/16 scenarios and 128/128 steps (log SHA-256
`bba1414c3f21f05a7487364af135631574a4eb0dca1668a2285f7436e205fcd2`;
Cucumber JSON SHA-256
`c56c28cec512a57c01e1e9aced0ea6f903b88339e661ffdc80f106044b3eb2a6`).
This promotion is not full `RPK-JAVA-DIAG-001..007`, release-wide, packaged,
native, cross-platform, protocol-surface, or general Java/Maven qualification.

## RPK-JAVA-DIAG-006 — Secure reproducibility

All discovery is bounded, offline by default, plugin-free and credential-free.
Provider failure, evidence drift, malformed/truncated evidence or aggregate
limit exhaustion fails closed without partial semantic publication.

## RPK-JAVA-DIAG-007 — Qualification matrix

Permanent fixtures cover at least main/test separation, reactor dependency and
impact closure, external artifacts, generated-but-materialized sources, missing
inputs, releases 8 through 25 and JPMS refusal. The release matrix uses one
explicit immutable JDK 25 input, proves every effective Maven `--release` from 8
through 25 through packaged CLI, daemon and MCP, and retains an adjacent API
boundary proving Java 8 rejects newer APIs. Promotion requires library, packaged
CLI/daemon/MCP, all four native platforms, exact rollback and independent
artifact acceptance. Named JPMS/module-path reactors remain typed unsupported
until separately qualified.
