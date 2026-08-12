# language: en
@packaged-runtime @native-qualification
Business Need: Qualify the bounded Maven module rename through every packaged public process
  As a maintainer qualifying a self-contained RefactorKit candidate
  I need the existing explicit Maven module rename to cross the CLI, daemon, and MCP package boundaries
  So that native evidence proves the same authoritative five edits, transaction, diagnostics, and exact rollback without widening product behavior

  The three examples below are the single executable source for this requirement.
  Linux x86-64, Windows x86-64, macOS x86-64, and macOS arm64 each rerun those same three cases at one immutable candidate revision; four host executions do not turn three expanded Gherkin cases into twelve cases or duplicate this feature.

  @REQ-JAVA-MAVEN-MODULE-RENAME-PACKAGED-001 @functional-requirement @non-functional-requirement @absent
  Scenario Outline: <surface> renames the proven module through the extracted runtime and restores its own workspace
    Given immutable baseline "docs/requirements/req-java-maven-module-rename-packaged-001-baseline.md" has SHA-256 "07ceef18167976c3e87d9738a9e677371ca91cbd49a421b3f645918def5a374d"
    And approved change "docs/requirements/req-java-maven-module-rename-packaged-001-approved-change-001.md" has SHA-256 "738a08b0622e42658c94d874a70b989bb29eb366a9575b9b96c46473154908ce" and replaces the external-reactor and omitted-artifact clauses
    And the native runner identifies exactly one current host from this closed matrix while retaining its exact runner, job, run, and attempt identity:
      | operating system | architecture |
      | Linux           | x86-64       |
      | Windows         | x86-64       |
      | macOS           | x86-64       |
      | macOS           | arm64        |
    And all four host runners bind the same full repository commit and tree plus clean/version state, while each runner binds its own platform candidate archive name and SHA-256 to that common revision before any case starts
    And the candidate archive is extracted once into an isolated read-only subject image whose complete no-follow tree hash is recorded before and after the case
    And the runner resolves "<launcher>" only as `bin/<launcher>` on POSIX or `bin/<launcher>.bat` on Windows inside that extracted image and will start no other subject executable
    And the runner records the selected launcher entry type, archive mode, and SHA-256 without repairing its permissions; POSIX will invoke that path without a shell or interpreter, Windows will use the corresponding public batch launcher, and the embedded `runtime/bin/java` or `runtime/bin/java.exe` is a regular executable archive entry
    And the subject environment removes inherited `JAVA_HOME`, `JDK_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, classpath, Maven, Gradle, credential, proxy, and user-settings authority
    And isolated home and temporary directories plus a minimal poison `PATH` put a failing decoy `java` ahead of every external tool, while launcher and process evidence proves only the archive's hash-bound embedded Java executes
    And no Gradle application classpath, source-built or in-process session, developer installation, globally installed Java, or fallback launcher is available to the subject
    And startup, each request, complete untruncated stdout and stderr, memory where enforceable, total runtime, descendants, output files, and no-follow cleanup are bounded fail-closed, so a timeout, truncated stream, live descendant, cleanup failure, or missing report fails the case
    And process and network tripwires deny Maven or Gradle wrappers and lifecycles, plugins, annotation processors, generators, project scripts, user or global settings, mirrors, proxies, servers, credentials, credential helpers, and every network request during discovery, preview, staged evaluation, apply, diagnostics, and rollback
    And the only permanent project is "testdata/acceptance/java-maven-move-class-authority-20-modules", with one root aggregator, exactly 20 unique active direct non-aggregating JAR children, and absent direct child "catalog-domain"
    And this <surface> receives one fresh pairwise-distinct no-follow disposable byte copy that refuses every symbolic link, preserves every regular-file byte and directory path kind, and cannot write through or back to the permanent project, extracted image, or another case
    And before the subject starts, the harness independently retains these immutable identities without reading a preview, response, or journal record:
      | identity | exact meaning |
      | M0       | the permanent fixture's complete no-follow relative path, path-kind, byte-length, permission, and SHA-256 manifest |
      | S0       | this copy's exact non-engine baseline bytes, path kinds, source and auxiliary inventories, 20-child reactor facts, and authoritative snapshot identity |
      | D0       | the exact clean canonical authoritative Maven and JDT diagnostic multiset for S0 |
      | C1       | the complete staged byte, path-kind, inventory, and reactor image produced from S0 by only the independently declared five-entry edit |
      | S1       | the authoritative committed snapshot identity and complete non-engine post-image required to equal C1 after a fresh scan |
    And the affected fixture bytes and their independently computed post-images are exactly:
      | pre-image path                                                      | post-image path                                                      | pre-image bytes and SHA-256                                             | post-image bytes and SHA-256                                            |
      | pom.xml                                                             | pom.xml                                                              | 2717; f30c9b9cfd394aa431f390a4fb2ff752e37d92f984b8519476f55157c403ac4c | 2718; 1c0fe9b9ca5383628f346877afff52fd6afe00d1223ef391e736c5a3b5ceb23f |
      | catalog-model/pom.xml                                               | catalog-domain/pom.xml                                               | 397; d954a47ec44e429cbc94a4e0d5e5fc65fc006ab3335a2b5a26e45d2e7f03088e  | 398; e9a34b04e5d408a9f2f6444a30ed118d8dd2d88bf880e67ff297f296092ca17b  |
      | catalog-pricing/pom.xml                                             | catalog-pricing/pom.xml                                              | 3337; 88c4ffdf826165d119fe278bdc8946d75bbd76b2784c0e476cbf4f2ae06f9ecc | 3338; 10831298857e61c7f054200c046a277a29467f5d0cddc34f17c800c776fe307f |
      | catalog-model/src/main/java/com/acme/catalog/legacy/Product.java   | catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java   | 94; 7bb9043767dbc5b61b34670812eed2c948eb6f0aca065a2cb76e28183573f663    | 94; 7bb9043767dbc5b61b34670812eed2c948eb6f0aca065a2cb76e28183573f663    |
    And every request is explicitly oldModuleDir="catalog-model", newModuleDir="catalog-domain", and newArtifactId="catalog-domain"
    And the harness is restricted to <boundary> and these exact public interactions in order:
      | phase       | interaction               |
      | establish   | <establish interaction>   |
      | preview     | <preview interaction>     |
      | apply       | <apply interaction>       |
      | diagnostics | <diagnostics interaction> |
      | rollback    | <rollback interaction>    |
    When the harness performs <establish interaction> and then <preview interaction> with "<launcher>" only from the extracted image through <boundary>
    Then the selected launcher has started from the extracted image through its public POSIX or Windows form and, on POSIX, direct execution proves its recorded archive mode contains execute bits; across the three examples this executes and qualifies all three shipped launchers
    And the preview succeeds read-only with status "PREVIEW", requires explicit approval, and exposes canonical operation "java.renameMavenModule"
    And the daemon preview uses only the existing input operation "renameMavenModule" before returning canonical "java.renameMavenModule", while MCP supplies canonical "java.renameMavenModule" and no new alias, method, field, protocol shape, or selector semantics is introduced
    And <plan lifecycle>
    And the preview's normalized WorkspaceEdit equals exactly these five ordered non-overlapping FileEdit entries, using zero-based end-exclusive ranges, and contains no other edit:
      | order | FileEdit | path                                                              | exact source range | exact new text | new path                                                           |
      | 1     | Modify   | catalog-model/pom.xml                                             | 9:14-9:27         | catalog-domain |                                                                    |
      | 2     | Modify   | pom.xml                                                           | 74:12-74:25       | catalog-domain |                                                                    |
      | 3     | Modify   | catalog-pricing/pom.xml                                           | 13:18-13:31       | catalog-domain |                                                                    |
      | 4     | Rename   | catalog-model/pom.xml                                             | absent            | absent         | catalog-domain/pom.xml                                             |
      | 5     | Rename   | catalog-model/src/main/java/com/acme/catalog/legacy/Product.java | absent            | absent         | catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java |
    And its normalized affected-path set is exactly `pom.xml`, `catalog-model/pom.xml`, `catalog-pricing/pom.xml`, both Product source paths, and `catalog-domain/pom.xml`, with the repeated child-POM source counted once
    And only the three listed element-text ranges change bytes; every parent coordinate, groupId, version, packaging, profile, dependency-management entry, transitive consumer, XML construct, whitespace, line ending, comment, processing instruction, unknown element, and Java source byte remains exact
    And C1 retains exactly 20 direct children, replaces the root module in its original list position with `catalog-domain`, resolves `com.acme.refactorkit.fixture:catalog-domain:1.0.0` as a JAR, and makes `catalog-pricing` depend directly on that exact coordinate
    And C1 creates only this ordered destination-directory hierarchy while preserving the now-empty pre-existing `catalog-model` hierarchy:
      | order | normalized created directory path                   |
      | 1     | catalog-domain                                      |
      | 2     | catalog-domain/src                                  |
      | 3     | catalog-domain/src/main                             |
      | 4     | catalog-domain/src/main/java                        |
      | 5     | catalog-domain/src/main/java/com                    |
      | 6     | catalog-domain/src/main/java/com/acme               |
      | 7     | catalog-domain/src/main/java/com/acme/catalog       |
      | 8     | catalog-domain/src/main/java/com/acme/catalog/legacy |
    And preview leaves the disposable copy exact S0 with no workspace lock, `.refactorkit` path, WAL record, transaction, target directory, or mutation of M0 or the extracted image
    When the harness performs <apply interaction> according to <apply lifecycle> with only the surface's generated correlation token where its existing lifecycle provides one
    Then fresh no-follow filesystem observation before journal inspection proves that the committed non-engine workspace is exact S1 and differs from S0 only by the declared post-image and path-kind oracle
    And the applied plan equals the independent five-entry oracle, and only the existing operation-owned gate "java-rename-maven-module-staged-reactor-v1" authorizes PatchEngine after evaluating exact S0 and C1 before PREPARED, then authoritative S1 with unchanged D0 after APPLIED and before public success, without generic `java-jdt` substitution
    And PatchEngine remains the sole lock, managed-write, diagnostics-gate, WAL, recovery, and rollback authority; the process harness and protocol responses manufacture no approval or write authority
    And the harness performs <diagnostics interaction> and receives a clean fresh packaged-surface result whose authoritative Maven and JDT diagnostic multiset is exactly D0 for S1
    And read-only journal inspection finds exactly one record and no second journal, with these exact facts:
      | field               | exact value                                                        |
      | schemaVersion       | integer literal 8                                                   |
      | operation           | java.renameMavenModule                                              |
      | state               | APPLIED                                                             |
      | approval            | EXPLICIT_APPLY, <approval surface>, caller                          |
      | forwardEdit         | the exact normalized five-entry WorkspaceEdit above                |
      | snapshot identities | pre-image S0 and authoritative post-image S1                        |
      | images              | the independently retained complete pre-images, post-images, permissions, and created-directory facts |
      | ordered history     | PREPARED, APPLYING, APPLIED                                         |
    When the harness performs <rollback interaction> to request normal rollback of that sole transaction according to <rollback lifecycle>
    Then rollback succeeds without force, recovery, compensation, or a second transaction and advances the same schema-v8 record to "ROLLED_BACK"
    And its ordered history is exactly "PREPARED, APPLYING, APPLIED, ROLLING_BACK, ROLLED_BACK" while its operation, approval, forward edit, images, created directories, S0, and S1 facts remain unchanged
    And fresh packaged diagnostics and no-follow observation prove exact restoration of every non-engine byte, path kind, permission, source and auxiliary inventory, reactor fact, snapshot identity S0, and diagnostic D0
    And `catalog-model` again contains its two exact original files, the complete `catalog-domain` hierarchy is absent, and only the workspace lock plus the one advanced journal record remain as expected engine residue
    And M0, every other disposable copy, the candidate archive, and the extracted subject image remain byte-identical and path-kind-identical to their pre-case identities
    And no denied process, plugin, script, setting, credential helper, external Java, or network authority was requested, and all supervised processes, streams, descendants, temporary outputs, and copies satisfy their bounded cleanup contract
    And the always-emitted operation-specific native evidence binds these exact identity classes and their SHA-256 values where byte-addressable:
      | identity class | required bound subjects |
      | revision       | repository identity, full commit, tree, clean state, and reported RefactorKit version |
      | native runner  | operating system, architecture, runner, job, run, attempt, and immutable candidate revision |
      | package        | archive name and SHA-256, extracted-tree hash, launcher path, mode and hash, and embedded-Java path, version and hash |
      | requirement    | feature hash, requirement ID, Scenario Outline, expanded <surface> example, exact request, and five-edit oracle hash |
      | fixture        | permanent M0, disposable-copy identity, S0, D0, C1, S1, and proof that all three surface roots are pairwise distinct |
      | transaction    | sole transaction ID, schema-v8 operation, approval surface, forward-edit hash, snapshot identities, and APPLIED then ROLLED_BACK history |
      | reports        | Cucumber JSON, JUnit XML, complete stdout, stderr and execution log, manifest, archive checksum, and failure-diagnostic hashes |
    And the native job publishes the Cucumber JSON, JUnit XML, complete logs, manifest, archive checksum, and failure diagnostics under an always-run condition, and any absent or unreconciled artifact fails that host row
    But this scenario does not qualify or introduce any of these excluded scopes:
      | explicitly excluded scope |
      | an external or new sample, a second real repository, Magrathea, or any project other than the existing permanent 20-module fixture |
      | an omitted newArtifactId, directory-only intent, inferred coordinate, groupId or version migration, arbitrary dependency-origin handling, JPMS, package movement, or new planner semantics |
      | a command-catalogue schema, operation-result schema, new daemon or MCP alias, new protocol field, or changed public lifecycle |
      | a recipe, LSP or external editor path, source-built or in-process package evidence, or duplicate runner-specific feature text |
      | Maven or Gradle lifecycle execution, plugins, annotation processors, project scripts, settings credentials, credential helpers, network access, or an external Java runtime |
      | a concurrency, crash-restart, corruption, refusal, forced-rollback, recovery, or compensation matrix beyond this exact normal rollback path |
      | a new aggregate, entity, repository, projection, domain event, event store, outbox, report repository, result store, second journal, or write authority outside PatchEngine |
      | general Maven populations, broad module-rename support, aggregate numerical coverage, I1 publication or downloaded-asset evidence, signing, notarization, installer UX, or release-wide support |
    And no J1, support-matrix, package, native-host, or release row is promoted until the three cases pass independently on all four hosts and their four immutable native reports reconcile at one candidate revision

    Examples:
      | surface         | launcher           | boundary                                  | establish interaction                                                                 | preview interaction                                                                                                                                                                                | apply interaction                                                                                                                                                                                          | diagnostics interaction | rollback interaction                                      | approval surface | plan lifecycle                                                                                                             | apply lifecycle                                                                                   | rollback lifecycle                                              |
      | packaged CLI    | refactorkit         | public argv                               | no session open; every command receives the same normalized `--root {copy}`           | `java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain --new-artifact-id catalog-domain --root {copy}`                                                                  | `java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain --new-artifact-id catalog-domain --root {copy} --apply`                                                                  | `diagnostics {copy}`    | `patch rollback {transactionId} --root {copy}`            | cli              | preview and apply use distinct launcher processes; apply replans, and no preview PlanId crosses a process boundary         | the apply process replans the same explicit request and returns its own transaction ID            | a fresh CLI process uses only the returned transaction ID        |
      | packaged daemon | refactorkit-daemon  | newline-delimited JSON-RPC 2.0 over stdio | one process receives `project.open root={copy}`                                        | `refactor.preview operation=renameMavenModule languageId=java arguments={oldModuleDir=catalog-model,newModuleDir=catalog-domain,newArtifactId=catalog-domain}`                                       | `refactor.apply planId={same-session-planId}`                                                                                                                                                               | `diagnostics`           | `patch.rollback transactionId={same-session-transactionId}` | daemon-json-rpc  | one daemon process retains the returned canonical plan from preview through apply; only successful apply and rollback refresh session state | the same process applies only its retained returned plan ID and returns one transaction ID         | the same process uses only that transaction ID before shutdown   |
      | packaged MCP    | refactorkit-mcp     | MCP JSON-RPC 2.0 over newline-delimited stdio using `tools/call` | one process completes `initialize`, `notifications/initialized`, and `tools/call project_scan root={copy}` | `tools/call preview_refactoring operation=java.renameMavenModule languageId=java arguments={oldModuleDir=catalog-model,newModuleDir=catalog-domain,newArtifactId=catalog-domain}`                    | `tools/call apply_refactoring planId={same-session-planId}`                                                                                                                                                 | `tools/call diagnostics` | `tools/call rollback_refactoring transactionId={same-session-transactionId}` | mcp-tool         | one initialized MCP process retains the canonical plan from preview through apply and retains correlation only inside that session | the same initialized process applies only its retained returned plan ID and returns one transaction ID | the same initialized process uses only that transaction ID before shutdown |
