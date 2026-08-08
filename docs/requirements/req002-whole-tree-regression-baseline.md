# REQ002-WHOLE-TREE-REGRESSION-001 — Immutable Baseline Receipt

Stable ID: `REQ002-WHOLE-TREE-REGRESSION-001`
Requirement: `REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-002`
Feature: `features/java-maven-module-rename-managed-surfaces.feature`
Red feature SHA-256: `793e02aa42e0f4d90f4a85382910b1e684bb80e3f8547d9c02080c4d8b6a8a17`
Reviewed HEAD: `db25c9af2d62c8e9e90f93792a6c6593e6e13698` plus current working-tree changes.

## Initial user intent

The bounded verbatim continuation request was:

> procedi

The planner interpreted this as executing the repository-wide JDK 21 `check goldenTest` gate that remained explicitly unverified after SURFACE-004/005 promotion.

## Observed baseline failure

The repository-wide command was:

```text
JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew --no-daemon --rerun-tasks check goldenTest --console=plain
```

It failed after 9 minutes 26 seconds in the CLI Cucumber runner for SURFACE-002. The complete observed log is `/tmp/req005-post-promotion-whole-tree-check.log`, SHA-256 `8076201a19fad44076b810b7555859606a5717ff03cff97037fe392fcff10914`.

A subsequent isolated rerun of `org.refactorkit.cli.mavenmodulerenamesurface002.JavaMavenModuleRenameSurface002CucumberTest` failed identically; log `/tmp/surface002-post-whole-tree-focused.log`, SHA-256 `13497ab251fff223dcb8c490fd96c09c8ef1a09a81aad70dfa732378e9059b04`.

The failure is at `JavaMavenModuleRenameSurface002Steps.startAuthorityMonitorIfNeeded`: `authorityObservationRequested` is false. The glue sets that flag only through the existing step:

```gherkin
And only child-process starts and RefactorKit-attributable socket reads or writes are independently observed here
```

That step was removed from the shared Background to correct SURFACE-003's in-process qualification, but was not restored inside SURFACE-002. Consequently SURFACE-002 no longer executes its declared authority monitor setup.

## Acceptance criteria

- AC-002-REG-01: Restore the existing child-process/socket observation step inside SURFACE-002 only; do not restore it to the shared Background.
- AC-002-REG-02: Preserve SURFACE-003/004/005 in-process and exclusion semantics.
- AC-002-REG-03: Do not modify production code or weaken/delete the existing fail-closed `authorityObservationRequested` assertion.
- AC-002-REG-04: Demote SURFACE-002 from `@implemented-and-validated` while the current acceptance suite is red.
- AC-002-REG-05: The focused SURFACE-002 Cucumber runner passes all current expanded steps under JDK 21.
- AC-002-REG-06: The repository-wide JDK 21 `check goldenTest` gate reaches `BUILD SUCCESSFUL`; non-blocking static-analysis findings remain reported honestly.
- AC-002-REG-07: Official Gherkin inventory, ARC42 status/counts, and all other requirement statuses reconcile after the scenario-specific restoration.
- AC-002-REG-08: Independent requirements-quality review returns `PASS` before SURFACE-002 is re-promoted.

## Exclusions

- No production behavior, planner, PatchEngine, selector, CLI command, LSP, daemon, MCP, transaction, or rollback change is authorized.
- Do not weaken assertions, skip tests, filter out the failing scenario, restore the observation step globally, or relabel the whole-tree failure as success.
- No broader external transport, packaged/native, cross-platform, general-Maven, release, or numerical-coverage claim is introduced.

## Change history

No material requirement change is approved. The bounded correction restores a scenario-specific prerequisite that was unintentionally lost while narrowing a different requirement's shared Background semantics.
