# REQ-JAVA-MAVEN-MODULE-RENAME-PACKAGED-001 Approved Change Receipt 001

- Change ID: `REQ-JAVA-MAVEN-MODULE-RENAME-PACKAGED-001-APPROVED-CHANGE-001`
- Approved: 2026-08-09
- Initial baseline SHA-256: `07ceef18167976c3e87d9738a9e677371ca91cbd49a421b3f645918def5a374d`
- Baseline commit: `4dda838a62bd329f539336f65f0ceafa54010406`
- Approval evidence: the user rejected unrelated projects and premature sample selection, then instructed the requirement-first workflow to proceed.

## Fixture correction

No external repository, JavadocX project, Magrathea module rename, or new sample project is authorized.

The packaged/native qualification reuses only the existing permanent RefactorKit-owned acceptance project:

```text
testdata/acceptance/java-maven-move-class-authority-20-modules
```

Every case runs on a fresh, pairwise-distinct, no-follow disposable byte copy. The permanent project remains immutable. It is an executable offline Maven reactor with one root aggregator, 20 active direct non-aggregating modules, Java main/test sources, authoritative dependency evidence, and an already independently qualified exact module-rename oracle.

This project is both the permanent fixture and the disposable real-project acceptance subject for the bounded J1 row. No second project is required to prove the same behavior purpose.

## Exact request and oracle

All packaged surfaces use the already qualified explicit request:

```text
oldModuleDir=catalog-model
newModuleDir=catalog-domain
newArtifactId=catalog-domain
```

The independently retained oracle remains exactly:

1. modify `catalog-model/pom.xml` artifact text;
2. modify root `pom.xml` module text;
3. modify `catalog-pricing/pom.xml` direct dependency artifact text;
4. move `catalog-model/pom.xml` to `catalog-domain/pom.xml`;
5. move the qualified Java source to the corresponding `catalog-domain` path.

No directory-only omitted-artifact behavior, groupId/version migration, arbitrary dependency-origin intent, or new planner semantics is added by this requirement.

## Validation matrix

The same Gherkin requirement is exercised through:

- packaged CLI public argv;
- packaged daemon public stdio JSON-RPC;
- packaged MCP public stdio `tools/call`.

The three cases run on:

- Linux x86-64;
- Windows x86-64;
- macOS x86-64;
- macOS arm64.

Each surface receives its own disposable copy and sole transaction. Daemon and MCP retain preview/apply/rollback within one process according to their existing public lifecycle; CLI uses its existing public process behavior. Source-built LSP evidence remains separately qualified and is not duplicated.

## Superseded baseline clauses

Initial-baseline AC-PACKAGED-006 and AC-PACKAGED-007 are replaced by this receipt. No external real-reactor or directory-only request is required. All other baseline criteria and exclusions remain unchanged.

## Release-ledger effect

Successful four-host packaged promotion closes only:

```text
J1 — Qualify module rename/move next using multi-module Maven fixtures and disposable real-project acceptance.
```

The Java catalogue umbrella, broader Maven ownership-migration parent, explicit groupId/coordinate-intent row, I1 publication, recipes, general Maven, and other language rows remain open.
