# Authoritative release-aware Java/Maven diagnostics requirements

Status: active, product-critical, release-blocking J1 prerequisite. The first
release-8/current-release platform, full-reactor module-filter and concise missing-
platform rows have local and packaged evidence; the complete epic is not qualified.

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
edge; the runtime projection is independently hash-bound. The first mediation
row traverses breadth-first so nearest paths win and declaration order breaks
equal-depth ties. Effective root dependency management is applied before
selection; optional transitive dependencies and explicit exclusions are omitted;
direct `provided` artifacts remain compile-visible but do not export children.
Compile projection follows compile edges only, while runtime/test projections may
follow runtime edges. Bounded local version ranges inspect at most 1,024 candidate
directories and select the highest matching version. Non-`jar`/`test-jar`
artifact types and Maven relocation metadata produce typed source-set
unavailability rather than guessed classpath entries. Missing required artifacts
or ambiguous scope/ownership makes that source set unavailable. Discovery executes
no wrapper, lifecycle, plugin, annotation processor, credential helper or
project code and performs no network access unless separately authorized.

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

## RPK-JAVA-DIAG-006 — Secure reproducibility

All discovery is bounded, offline by default, plugin-free and credential-free.
Provider failure, evidence drift, malformed/truncated evidence or aggregate
limit exhaustion fails closed without partial semantic publication.

## RPK-JAVA-DIAG-007 — Qualification matrix

Permanent fixtures cover at least main/test separation, reactor dependency and
impact closure, external artifacts, generated-but-materialized sources, missing
inputs, releases 8 through 25 and JPMS refusal. Promotion requires library,
packaged CLI/daemon/MCP, all four native platforms, exact rollback and
independent artifact acceptance. Named JPMS/module-path reactors remain typed
unsupported until separately qualified.
