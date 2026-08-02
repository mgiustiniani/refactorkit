# language: en
Business Need: Rename one proven direct-child Maven module without widening write authority
  As a maintainer of an offline multi-module Java workspace
  I need module location and Maven artifact identity to remain independently explicit
  So that a bounded module rename can be previewed, applied once, and reversed without collateral POM changes

  This first executable backlog row uses only the direct JVM library boundary and one permanent fixture shape.
  The Java adapter owns immutable Maven planning evidence; the existing PatchEngine and transaction journal retain all mutation and rollback authority.

  @REQ-JAVA-MAVEN-MODULE-RENAME-001 @functional-requirement @implemented-and-validated
  Scenario: An explicit direct-child directory and artifact rename commits once and rolls back exactly
    Given the declared permanent fixture is "testdata/acceptance/java-maven-move-class-authority-20-modules"
    And its root POM is the sole aggregator with exactly 20 unique direct literal module entries, each naming a non-aggregator JAR child with one POM
    And "catalog-model" is one exact direct child, "catalog-domain" is absent without following links, and "catalog-model" contains exactly these two regular files:
      | relative file                                                    |
      | pom.xml                                                          |
      | src/main/java/com/acme/catalog/legacy/Product.java              |
    And the harness creates one pristine workspace and one fresh refusal-probe workspace per probe as no-follow disposable byte copies of the permanent fixture
    And copying refuses every symbolic link, preserves every relative regular-file byte and directory path kind, and never writes through or back to the permanent fixture
    And before library use the harness records manifest "M0", the expected source and auxiliary path inventories, and the absence of ".refactorkit"
    And every scanner, planner, diagnostic, apply, journal, and rollback call is confined to its declared disposable root and explicitly fixture-local repository
    And Maven and wrapper execution, lifecycle goals, plugins, annotation processors, generators, user or global settings, mirrors, proxies, servers, credentials, credential helpers, and network requests are denied and instrumented
    When a direct caller independently invokes the real public JavaProjectScanner scan and JavaRenameMavenModulePlanner preview for each fresh refusal probe:
      | probe                              | isolated raw-POM condition                                                                                                                           | planner arguments                                                                                                             | required refusal evidence                         |
      | inferred artifact coordinate       | every probe raw POM byte equals the permanent fixture                                                                                                  | oldModuleDir="catalog-model", newModuleDir="catalog-domain", and a blank newArtifactId that asks the planner to infer it     | nonblank caller-explicit coordinate intent is absent |
      | property-managed dependency origin | the catalog-pricing dependency artifact text is `${catalog.model.artifact}` and that property effectively resolves to `catalog-model`                 | oldModuleDir="catalog-model", newModuleDir="catalog-domain", newArtifactId="catalog-domain"                               | property-managed edited origin                    |
      | ambiguous dependency origin        | catalog-pricing has two direct literal dependency artifact origins whose effective identities both resolve to the exact catalog-model reactor child   | oldModuleDir="catalog-model", newModuleDir="catalog-domain", newArtifactId="catalog-domain"                               | duplicate effective-to-raw origin mapping         |
    Then every probe returns PatchStatus "REFUSED" before publishing a writable preview and identifies its listed blocker without guessing an origin or coordinate
    And every refusal has an empty WorkspaceEdit, no affected path, no authority lease, no apply identity, and no user-approval capability
    And omission is distinct from the inferred-coordinate probe: an omitted newArtifactId means preserve artifact "catalog-model" and never authorizes a child or dependency coordinate edit
    And every refusal leaves its pre-call probe image exact and creates no workspace lock, ".refactorkit" path, write-ahead journal, or managed transaction
    And the pristine workspace and permanent fixture still equal "M0" byte for byte and path-kind for path-kind
    When JavaProjectScanner with network dependency resolution disabled scans only the declared pristine workspace root
    Then snapshot "S0" contains exactly one applicable authoritative offline Maven effective reactor with status "AVAILABLE"
    And reactor "R0" proves the sole root aggregator and all 20 exact direct non-aggregator children without implicit ancestor, sibling, profile, or outward discovery
    And the only editable origins for the positive request are these exact zero-based element-text ranges from hash-bound raw POMs:
      | role                       | raw POM                  | raw POM SHA-256                                                   | element-text range | literal       | effective proof                                                                                         | exact origin count |
      | child project artifact     | catalog-model/pom.xml    | d954a47ec44e429cbc94a4e0d5e5fc65fc006ab3335a2b5a26e45d2e7f03088e | 9:14-9:27         | catalog-model | own project coordinate com.acme.refactorkit.fixture:catalog-model:1.0.0 with JAR packaging              | 1                  |
      | root direct module entry   | pom.xml                  | f30c9b9cfd394aa431f390a4fb2ff752e37d92f984b8519476f55157c403ac4c | 74:12-74:25       | catalog-model | the selected root module declaration resolves directly to workspace child directory catalog-model       | 1                  |
      | direct dependency artifact | catalog-pricing/pom.xml  | 88c4ffdf826165d119fe278bdc8946d75bbd76b2784c0e476cbf4f2ae06f9ecc | 13:18-13:31       | catalog-model | catalog-pricing directly compile-depends on com.acme.refactorkit.fixture:catalog-model:1.0.0             | 1                  |
    And the child POM parent artifact "java-maven-move-class-authority-reactor", transitive consumers, profiles, dependency management, and lexical same-name tags are not editable origin evidence
    And the direct caller records the reactor's Maven diagnostics and JavaLanguageAdapter authoritative JDT diagnostics as the exact canonical baseline "D0"
    When the caller invokes JavaRenameMavenModulePlanner.preview twice over the same immutable "S0", raw-POM evidence, reactor "R0", diagnostic providers, and exact arguments oldModuleDir="catalog-model", newModuleDir="catalog-domain", newArtifactId="catalog-domain"
    Then both results are PatchStatus "PREVIEW" for operation "java.renameMavenModule", require separate explicit user approval, and carry immutable snapshot-bound origin, destination-absence, staged-reactor, and diagnostic evidence
    And caller-held and exposed collection mutation attempts cannot alter transient StageFacts, GateExpectation, ReactorView, ModuleView, or SourceSetView evidence
    And every real JavaRenameMavenModulePlanner call in this scenario obtains POM structure and exact element-text ranges only through a maintained non-executing XML parser with DTD processing and external general and parameter entity resolution disabled
    And no such planner call uses a hand-written XML tokenizer/parser or serializes a POM; parser-reported ranges authorize only the hash-bound raw element-text replacements listed below
    And each normalized non-overlapping WorkspaceEdit has exactly these five ordered FileEdit entries and no others:
      | order | FileEdit | path                                                               | exact source range | exact new text  | new path                                                            |
      | 1     | Modify   | catalog-model/pom.xml                                              | 9:14-9:27         | catalog-domain  |                                                                     |
      | 2     | Modify   | pom.xml                                                            | 74:12-74:25       | catalog-domain  |                                                                     |
      | 3     | Modify   | catalog-pricing/pom.xml                                            | 13:18-13:31       | catalog-domain  |                                                                     |
      | 4     | Rename   | catalog-model/pom.xml                                              |                   |                 | catalog-domain/pom.xml                                              |
      | 5     | Rename   | catalog-model/src/main/java/com/acme/catalog/legacy/Product.java  |                   |                 | catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java  |
    And the normalized affected-path set contains exactly the three modification paths, both rename sources, and both rename destinations, with the repeated child POM source counted once
    And the exact staged changed-or-moved file image is:
      | pre-image path                                                       | post-image path                                                       | pre-image bytes and SHA-256                                            | post-image bytes and SHA-256                                           |
      | pom.xml                                                              | pom.xml                                                               | 2717; f30c9b9cfd394aa431f390a4fb2ff752e37d92f984b8519476f55157c403ac4c | 2718; 1c0fe9b9ca5383628f346877afff52fd6afe00d1223ef391e736c5a3b5ceb23f |
      | catalog-model/pom.xml                                                | catalog-domain/pom.xml                                                | 397; d954a47ec44e429cbc94a4e0d5e5fc65fc006ab3335a2b5a26e45d2e7f03088e  | 398; e9a34b04e5d408a9f2f6444a30ed118d8dd2d88bf880e67ff297f296092ca17b  |
      | catalog-pricing/pom.xml                                              | catalog-pricing/pom.xml                                               | 3337; 88c4ffdf826165d119fe278bdc8946d75bbd76b2784c0e476cbf4f2ae06f9ecc | 3338; 10831298857e61c7f054200c046a277a29467f5d0cddc34f17c800c776fe307f |
      | catalog-model/src/main/java/com/acme/catalog/legacy/Product.java    | catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java    | 94; 7bb9043767dbc5b61b34670812eed2c948eb6f0aca065a2cb76e28183573f663    | 94; 7bb9043767dbc5b61b34670812eed2c948eb6f0aca065a2cb76e28183573f663    |
    And only the three authorized element-text ranges differ; every other XML and source byte, including the parent artifact, namespace syntax, whitespace, line endings, comments, processing instructions, unknown elements, and `${project.version}` text, is preserved
    And the staged path-kind image preserves the pre-existing now-empty "catalog-model" directory hierarchy, creates only the corresponding "catalog-domain" hierarchy for the two moved regular files, and changes no other non-engine path kind
    And the ordered edit produces one complete staged reactor "R1" with 20 direct children, root "catalog-domain" at coordinate com.acme.refactorkit.fixture:catalog-domain:1.0.0 with JAR packaging, one root module entry "catalog-domain" in the original list position, and the catalog-pricing dependency resolved to that exact coordinate
    And staged source and auxiliary inventories are complete, every other reactor module, coordinate, dependency, source set, and child-relative path equals "R0", and transitive consumers receive no POM edit
    And authoritative staged Maven and JDT diagnostics equal "D0" exactly, with no new, changed, unavailable, or suppressed diagnostic
    And both previews have equal normalized edits, affected paths, summaries, warnings, risk and approval facts, origin and reactor evidence, and before and staged diagnostics when their generated PlanId values are excluded
    But a generated PlanId is opaque correlation data and is neither compared nor accepted as semantic determinism evidence
    And both scans and previews are read-only: the pristine workspace still equals "M0", "catalog-domain" and ".refactorkit" remain absent, and the permanent fixture is unchanged
    And no denied process, plugin, settings, credential, helper, or network authority was requested during scan, refusal, staging, diagnostics, or preview
    When the caller selects one preview and invokes the existing public PatchEngine.apply exactly once with snapshot "S0", ApplyAuthorization.explicit for surface "direct-library" and actor "acceptance-caller", and the staged-reactor diagnostics gate
    Then ApplyResult.Applied returns one transaction "T1" only after under-lock snapshot, destination, raw-origin, effective-model, staged-image, and diagnostic revalidation succeeds
    And the committed non-engine workspace is exactly the staged file and path-kind image and reactor "R1", with no POM or regular file remaining under "catalog-model" and no path difference beyond that staged image
    And a fresh JavaProjectScanner scan and authoritative Maven and JDT diagnostic run attest the exact committed reactor "R1", post-image snapshot identity "S1", and diagnostic multiset "D0"
    And a fresh TransactionLog.listRecordsReadOnly journal inspection returns exactly one schema-v8 record with:
      | journal field       | exact value                                                                                   |
      | transaction         | T1, with one opaque transaction ID bound to the selected preview                              |
      | operation           | java.renameMavenModule                                                                        |
      | state               | APPLIED                                                                                       |
      | forward edit        | the exact normalized five-entry WorkspaceEdit above                                            |
      | approval            | EXPLICIT_APPLY, direct-library, acceptance-caller                                               |
      | snapshot identities | pre-image S0 and post-image S1                                                                 |
      | images              | exact complete pre-images, post-images, permissions, and destination-directory creation facts  |
      | ordered history     | PREPARED, APPLYING, APPLIED                                                                    |
    And the permanent fixture remains byte-identical, path-kind-identical, and free of ".refactorkit"
    When the caller invokes the existing public PatchEngine.rollback for transaction "T1" in RollbackMode.NORMAL exactly once
    Then rollback succeeds for the same transaction identity without force, recovery, compensation, or a second transaction
    And fresh read-only journal inspection still returns exactly one record, now "ROLLED_BACK", with ordered history "PREPARED, APPLYING, APPLIED, ROLLING_BACK, ROLLED_BACK"
    And every pristine-workspace non-engine byte, path kind, source and auxiliary inventory, reactor identity, snapshot identity, and authoritative diagnostic equals "M0", "R0", "S0", and "D0" respectively
    And "catalog-model" again contains its exact two original files, the complete created "catalog-domain" hierarchy is absent, and the permanent fixture remains exact
    And the only expected engine residue is the workspace lock file plus the one advanced transaction journal record; no staging, temporary, backup, quarantine, recovery-required, second-journal, or other unexpected residue exists
    But this bounded row makes no support or qualification claim for:
      | explicitly excluded scope                                                                 |
      | creating an additional Maven module or POM                                                 |
      | nested modules or a module not declared directly by the selected root aggregator           |
      | positive editing of profile-declared, property-managed, inherited, duplicate, or ambiguous origins |
      | groupId, version, packaging, type, classifier, parent-coordinate, or other coordinate changes |
      | JPMS descriptors or module-path reactors                                                   |
      | CLI, daemon, LSP, MCP, or other protocol surfaces                                          |
      | recipe execution or recipe composition                                                     |
      | packaged-runtime, native-host, operating-system, architecture, or cross-platform qualification |
      | disposable real-project acceptance or general Maven project support                        |
      | completion of the broad Java module rename or move roadmap item                             |
