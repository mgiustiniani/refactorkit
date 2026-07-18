# Authoritative release-aware Java/Maven diagnostics requirements

Status: active, release-blocking J1 prerequisite.

## Purpose

Java mutation authority must be derived from the exact Maven source-set,
classpath and Java platform environment. Maven build success is supplementary
acceptance evidence and never repairs incomplete RefactorKit evidence.

## RPK-JAVA-DIAG-001 — Release-aware Java platform authority

For every effective `--release` from Java 8 through Java 25, authoritative
analysis requires explicitly configured, immutable Java platform signatures for
that release. The provider records the canonical platform home, JDK release
metadata hash, signature archive hash, requested release and one deterministic
identity. It verifies the evidence with bounded double reads and refuses missing,
malformed, drifting or release-incomplete inputs. The host JVM and packaged jlink
runtime are not implicit project-platform evidence.

The first implementation slice only resolves and attests a configured JDK
`lib/ct.sym`. It does not yet grant JDT diagnostic or mutation authority; that
requires a separately tested JDT environment integration.

## RPK-JAVA-DIAG-002 — Full-reactor per-source-set model

A declared Maven reactor root is both workspace and effective-model authority.
Module targeting filters results after full-reactor construction. Main and test
retain separate roots, generated roots, classpaths, dependency visibility,
compiler settings and availability.

## RPK-JAVA-DIAG-003 — Maven visibility

Each source-set environment includes the complete offline effective reactor
closure and exact available external artifacts. Missing required artifacts or
ambiguous scope/ownership makes that source set unavailable. Discovery executes
no wrapper, lifecycle, plugin, annotation processor, credential helper or
project code and performs no network access unless separately authorized.

## RPK-JAVA-DIAG-004 — Availability and cascade suppression

Unavailable roots are typed and source-set scoped. Derivative unresolved-symbol
cascades may be suppressed only when their complete causal input is unavailable;
genuine source diagnostics from complete environments remain visible.

## RPK-JAVA-DIAG-005 — Change-specific authority

Baseline, exact staged post-image, post-apply and rollback diagnostics bind the
same snapshot/overlay mode, source-set and dependent-impact closure, build and
classpath evidence, Java platform identity, provider identity and configuration.
Normal mode rejects introduced errors; strict mode requires zero staged errors.
Any unavailable affected environment blocks semantic mutation even when the
same unavailability existed at baseline.

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
