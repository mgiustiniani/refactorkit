# REQ-JAVA-MAVEN-MODULE-RENAME-PACKAGED-001 Approved Change Receipt 001

- Change ID: `REQ-JAVA-MAVEN-MODULE-RENAME-PACKAGED-001-APPROVED-CHANGE-001`
- Approved: 2026-08-09
- Initial baseline SHA-256: `07ceef18167976c3e87d9738a9e677371ca91cbd49a421b3f645918def5a374d`
- Baseline commit: `4dda838a62bd329f539336f65f0ceafa54010406`
- Approval evidence: the user rejected use of unrelated JavadocX material for RefactorKit qualification.

## Project-owned real-reactor correction

No JavadocX or other unrelated external repository may be imported or used.

The Linux real-reactor row uses the existing RefactorKit-owned sample:

```text
samples/java-maven-reactor-21
```

This sample is part of the RefactorKit repository, has one Maven aggregator and six direct non-aggregating modules, contains main and test Java sources, and is already maintained as representative Java/Maven release-aware project evidence. Its provenance is the exact RefactorKit candidate commit and tree; no external code, remote repository, license decision, source archive, or dependency bundle is introduced.

## Exact directory-only request

On a fresh no-follow disposable byte copy, invoke:

```text
oldModuleDir=domain
newModuleDir=domain-module
newArtifactId omitted
```

Omission means the argument/member is absent—not blank and not inferred from the destination directory.

Expected semantic intent:

- rename the direct module directory from `domain` to `domain-module`;
- preserve artifact identity `example.reactor:domain:1.0.0`;
- preserve every dependency coordinate referencing artifact `domain`;
- update only the root `<module>` path selected by authoritative Maven origin evidence;
- move the child `pom.xml` and Java source bytes unchanged;
- preserve all unrelated modules, POM bytes, source bytes, package identities, and path kinds.

The exact line/range, byte, snapshot, edit, diagnostic, package, and transaction oracles must be captured from the committed sample revision before RED; this receipt does not guess them.

## Surface and platform decision

- The permanent 20-module explicit-artifact fixture runs through packaged CLI, daemon, and MCP on all four native hosts.
- The project-owned `java-maven-reactor-21` directory-only row runs through packaged CLI, daemon, and MCP on Linux x86-64.
- Daemon qualification uses its existing public alias input and verifies the returned canonical operation; no new daemon protocol alias is authorized.
- No external editor/LSP process row is added.

## Unchanged boundaries

The requirement still closes only the J1 module rename/move qualification row after native evidence. It does not close broader explicit groupId/coordinate intent, ownership migration, general Maven, I1 publication, recipe, catalogue, CLI-result, or other-language rows.
