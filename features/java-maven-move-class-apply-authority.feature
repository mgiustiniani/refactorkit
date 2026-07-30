# language: en
Business Need: Authorize managed Java class moves with complete Maven semantic evidence
  As a maintainer of a multi-module Maven workspace
  I need RefactorKit to distinguish binding-clean move authority from review-only evidence
  So that only complete semantic plans can mutate and roll back the workspace

  This executable backlog covers ARC42 RPK-JAVA-MOVE-001 through RPK-JAVA-MOVE-007.
  LEXICAL_FALLBACK is always review-only and can never acquire managed-write authority.

  Background:
    Given the declared workspace root is the permanent fixture "testdata/acceptance/java-maven-move-class-authority-20-modules"
    And the fixture is a plugin-free offline Maven reactor with one root aggregator and 20 active non-aggregator modules
    And its active graph has at least three dependency levels, one materialized local external dependency, and one safely materialized generated Java source root
    And discovery and analysis cannot run Maven lifecycle goals, plugins, annotation processors, credential helpers, or network requests
    And the request moves the writable sole top-level class "com.acme.catalog.legacy.Product" from "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java" to the unused path "catalog-model/src/main/java/com/acme/catalog/api/Product.java" within the same main source set

  # RPK-JAVA-MOVE-001..004 and RPK-JAVA-MOVE-006..007
  @REQ-JAVA-MAVEN-MOVE-AUTH-001 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario: A binding-clean move is committed once and rolled back byte for byte
    Given the full effective reactor graph and complete reverse-observer closure are available and hash-bound
    And every observer has current dependency-bounded source paths, classpaths, source inventories, Java platform signatures, and provider evidence
    And the target declaration and every managed Java edit site resolve to the same exact non-recovered JDT binding
    When the move is previewed twice from identical snapshot and evidence hashes
    Then each result is a "SEMANTIC_PREVIEW" with evidence "JDT_BINDING" and managed-write eligibility "ELIGIBLE"
    And the normalized edits, observer closure, evidence counts, and diagnostics are identical between the previews
    And exact staged diagnostics introduce no errors relative to authoritative before diagnostics
    And no prohibited build or network activity was attempted
    When one approved preview is applied under the workspace lock
    Then its exact staged post-image is committed in one managed transaction
    And authoritative after diagnostics attest the committed snapshot with no introduced errors
    When that transaction is rolled back
    Then every file byte, path, inventory entry, and snapshot hash equals the pre-apply image
    And authoritative rollback diagnostics attest the restored snapshot

  # RPK-JAVA-MOVE-002..003 and RPK-JAVA-MOVE-006..007
  @REQ-JAVA-MAVEN-MOVE-AUTH-002 @functional-requirement @implemented-and-validated
  Scenario: Only binding-proven Java observers are updated
    Given the complete reverse-observer closure contains these binding-proven uses:
      | source set               | dependency relation | Java use forms                              |
      | catalog-pricing:main     | direct              | import, constructor, and declared type     |
      | catalog-storefront:main  | transitive          | instanceof and class literal               |
      | catalog-acceptance:test  | test-only           | Cucumber step-definition import and FQN use |
    And the effective reactor graph proves that "catalog-decoy:main" and "reporting-unrelated:main" cannot observe the target
    And the candidate inventory classifies these lexical matches:
      | path                                                                                          | classification                  |
      | catalog-decoy/src/main/java/com/acme/decoy/Product.java                                      | same-simple-name decoy          |
      | reporting-unrelated/src/main/java/com/acme/reporting/ProductReport.java                       | unrelated source-set candidate  |
      | catalog-acceptance/src/test/resources/features/product-lifecycle.feature                      | non-Java lexical candidate      |
    When the eligible binding-clean move is previewed and applied
    Then every listed Java use is updated exactly once and resolves to "com.acme.catalog.api.Product"
    And no resolvable reference to "com.acme.catalog.legacy.Product" remains in the authority scope
    And no unrelated source set was added to an observer's JDT source path
    And every listed lexical candidate remains byte-for-byte unchanged
    And the preview reports the same-name and unrelated Java candidates as excluded and the non-Java match as a residual review risk

  # RPK-JAVA-MOVE-002 and RPK-JAVA-MOVE-004..007
  @REQ-JAVA-MAVEN-MOVE-AUTH-003 @functional-requirement @non-functional-requirement @absent
  Scenario: Incomplete or stale semantic authority produces non-managed guidance before journaling
    Given each authority defect is evaluated independently for the otherwise supported request:
      | affected source set       | authority defect                                                        | blocker dimension       |
      | catalog-acceptance:test   | the required step-definition source is absent from the source inventory | source-path incomplete  |
      | catalog-pricing:main      | the local external artifact hash is older than the classpath evidence    | classpath stale         |
      | catalog-storefront:main   | a target use resolves only to a recovered binding                        | binding incomplete      |
      | catalog-model:main        | the materialized generated-source inventory is older than its directory | source-path stale       |
    When a move preview is requested for each authority defect
    Then each result is typed "REVIEW_ONLY_GUIDANCE" with a stable blocker code naming the affected module and source set
    And each result separates bound facts from lexical, non-Java, and unknown candidates and discloses omissions or truncation
    And each result recommends authority-restoration actions and an ordered human and VCS-owned verification checklist
    But no result is a "PatchPlan" or exposes an applyable plan ID, managed transaction, or RefactorKit rollback claim
    When a caller attempts to submit any guidance result for managed apply
    Then RefactorKit refuses with the typed non-managed blocker before creating a write-ahead log or ".refactorkit/transactions"
    And the workspace bytes, paths, inventory, and snapshot hash remain unchanged

  # RPK-JAVA-MOVE-004..005 and RPK-JAVA-MOVE-007
  @REQ-JAVA-MAVEN-MOVE-AUTH-004 @non-functional-requirement @absent
  Scenario: No force flag, recipe, or integration surface promotes lexical fallback
    Given a move-class candidate is marked "LEXICAL_FALLBACK", review-only, and managed-write ineligible
    When promotion is attempted independently through each entry path:
      | entry path      | attempted promotion                                                                                                                |
      | force           | approval, warning acknowledgement, or force metadata is attached before core apply                                                |
      | recipe          | a moveClass step is composed into a recipe that is requested for apply                                                            |
      | CLI             | refactorkit move-class --symbol com.acme.catalog.legacy.Product --to-package com.acme.catalog.api --apply                          |
      | daemon JSON-RPC | refactor.apply with planId plan-lexical                                                                                              |
      | managed LSP     | workspace/executeCommand with refactorkit.applyPlan and plan-lexical                                                                |
      | MCP             | apply_refactoring with planId plan-lexical                                                                                          |
    Then every path preserves "LEXICAL_FALLBACK" as review-only and managed-write ineligible
    And no path returns a semantic preview, applyable plan ID, client-managed workspace edit, or transaction ID
    And every managed apply attempt is refused with "evidence.insufficient" before write-ahead-log creation
    And no workspace byte is mutated and no transaction is recorded
    And every surface reports the operation ID, authority status, evidence kind, evidence hash, eligibility, blocker code, and residual guidance metadata

  @REQ-JAVA-MAVEN-MOVE-AUTH-005 @non-functional-requirement @implemented-and-validated
  Scenario: Refused lexical-fallback CLI apply leaves no managed-write residue
    Given no ".refactorkit" directory exists
    And every source byte, path, source-inventory entry, and workspace snapshot hash is recorded
    When `refactorkit scan .` is invoked
    And `refactorkit move-class --symbol com.acme.catalog.legacy.Product --to-package com.acme.catalog.api --preview` is invoked
    Then the scan and preview are read-only
    And the preview reports "LEXICAL_FALLBACK" and managed-write eligibility "INELIGIBLE"
    And no ".refactorkit" directory exists
    When `refactorkit move-class --symbol com.acme.catalog.legacy.Product --to-package com.acme.catalog.api --apply` is invoked
    Then RefactorKit refuses with "evidence.insufficient" before write-ahead-log creation
    And no ".refactorkit" directory exists, including no zero-byte ".refactorkit/workspace.lock"
    And no managed transaction exists
    And every source byte, path, source-inventory entry, and workspace snapshot hash equals the recorded pre-command state

  # RPK-JAVA-MOVE-002..004 and RPK-JAVA-MOVE-006..007
  @REQ-JAVA-MAVEN-MOVE-AUTH-006 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario: Unrelated JDT warnings do not demote a clean move authority closure
    Given the complete effective reactor proves that "catalog-model:main" owns the target and has this dependency-bounded reverse-observer closure:
      | source set               | dependency relation |
      | catalog-pricing:main     | direct              |
      | catalog-storefront:main  | transitive          |
      | catalog-acceptance:test  | test-only           |
    And the target owner and every listed observer are binding-clean for the move
    And "reporting-unrelated:main" is outside that closure and has unrelated JDT type-resolution warnings
    When the move is previewed
    Then the result remains a "SEMANTIC_PREVIEW" with evidence "JDT_BINDING"
    And exactly the three listed binding-proven observers are selected
    And the preview reports "reporting-unrelated:main" as excluded from the dependency-bounded reverse-observer closure
    And its unrelated warnings do not demote the move's semantic authority
    But a JDT warning in the target owner or any selected observer source set makes managed-write eligibility "INELIGIBLE"

  # RPK-JAVA-MOVE-002..004 and RPK-JAVA-MOVE-006..007
  @REQ-JAVA-MAVEN-MOVE-AUTH-007 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario: Candidate-total authority applies and reverses a move with an offline leaf artifact
    Given target-scoped operation authority reports this complete structural row:
      | reactorStructureStatus | observerClosureStatus | externalClasspathStatus | offline-missing entry                                         | affected source set   |
      | COMPLETE               | COMPLETE              | OFFLINE_MISSING         | com.acme.fixture.external:catalog-price-contract:1.0.0        | catalog-pricing:main  |
    And the missing entry is an enumerated leaf external artifact with hash-bound expected identity and negative-presence evidence
    And every POM, parent, BOM, mediation, variant, reactor edge, ownership, source root, materialized generated source, Java platform, and provider input needed by the closure is complete and hash-bound
    And the target declaration has one exact non-recovered binding at its expected path and range
    And the bounded lexical Java scan inventories every old-FQN and matching simple-name candidate and classifies every exact range once:
      | candidate record          | Java forms                                           | classification |
      | catalog-pricing:main      | import, declared type, and constructor expression    | BOUND_TARGET   |
      | catalog-storefront:main   | import, instanceof type, and class literal           | BOUND_TARGET   |
      | catalog-acceptance:test   | import, declared type, and fully qualified type      | BOUND_TARGET   |
      | catalog-decoy:main        | same-simple-name type declaration                    | BOUND_OTHER    |
    And no Java lexical candidate is "UNRESOLVED", including no target-relevant unresolved candidate
    And the lexical scan is completeness-and-veto evidence only and never selects an edit
    And every Java edit is selected solely by an exact "BOUND_TARGET" binding equal to the selected declaration binding
    And every unrelated retained baseline diagnostic has exact unchanged before/staged identity and positive non-concealment proof, including:
      | affected source set   | missing external type                       | exact before/staged identity                                                                                     | positive non-concealment reason                                                                                                                            |
      | catalog-pricing:main  | com.acme.fixture.external.PriceAuthority    | provider/configuration, problem code, category, severity, normalized path, mapped range, and message are equal  | the diagnostic and missing input intersect no target candidate, edit, or lookup prerequisite and cannot introduce, shadow, ambiguate, or conceal Product  |
    When the move is previewed twice from identical snapshot, candidate-inventory, and negative-presence evidence hashes
    Then each result is a "SEMANTIC_PREVIEW" with evidence "JDT_BINDING" and managed-write eligibility "ELIGIBLE"
    And each result continues to report global external classpath status "OFFLINE_MISSING"
    And the target identity, observer closure, candidate records, normalized edits, retained diagnostic multiset, and staged diagnostic delta are identical between previews
    And exact staged analysis preserves every "BOUND_OTHER" classification and introduces no new or changed errors
    And "LEXICAL_FALLBACK" remains review-only and managed-write ineligible
    When one approved preview is applied under the workspace lock
    Then its exact staged post-image is committed in exactly one managed transaction
    And authoritative after diagnostics attest the committed snapshot without concealing the retained baseline diagnostic
    When that transaction is rolled back
    Then every workspace byte, path, source-inventory entry, and snapshot hash is exactly equal to the pre-apply image
    And authoritative rollback diagnostics attest the byte-exact restored snapshot

  # RPK-JAVA-MOVE-002..004 and RPK-JAVA-MOVE-006..007
  @REQ-JAVA-MAVEN-MOVE-AUTH-008 @non-functional-requirement @absent
  Scenario Outline: Target-relevant uncertainty and evidence drift fail closed without mutation
    Given each authority defect is evaluated independently against the otherwise eligible target-scoped move
    And the bounded lexical Java scan is completeness-and-veto evidence only and never selects an edit
    When "<authority defect>" affects the "<authority layer>" before managed apply
    And the resulting workspace bytes, paths, inventory, and evidence hashes are recorded before authority evaluation
    Then the result is typed "<result type>" with managed-write eligibility "INELIGIBLE" and a stable blocker naming the affected authority layer
    And no result exposes a managed edit, applyable plan ID, or "JDT_BINDING" evidence for a scanner-only lexical range
    And "LEXICAL_FALLBACK" remains review-only and cannot be promoted
    When managed apply is attempted for that result
    Then RefactorKit refuses before workspace-lock acquisition and before write-ahead-log creation
    And no ".refactorkit" directory, lock file, write-ahead log, or managed transaction is created
    And every workspace byte, path, inventory entry, snapshot hash, and evidence hash equals the state recorded before authority evaluation

    Examples:
      | authority layer       | authority defect                                                                                                                                          | result type             |
      | candidate totality    | any target-relevant candidate has an absent, recovered, problem, ambiguous, or truncated binding and is therefore UNRESOLVED                            | REVIEW_ONLY_GUIDANCE    |
      | candidate totality    | an unresolved on-demand import, enclosing type, or supertype can change target-name lookup                                                               | REVIEW_ONLY_GUIDANCE    |
      | closure consistency   | an explicit old-FQN candidate appears outside the computed reverse-observer closure                                                                       | REVIEW_ONLY_GUIDANCE    |
      | diagnostic identity   | a retained diagnostic changes provider/configuration, code, category, severity, message, normalized path, or mapped range, or the same message moves     | REVIEW_ONLY_GUIDANCE    |
      | structural closure    | a required POM, parent, BOM, mediation, variant, reactor edge, ownership, source root, generated source, platform, provider, or inventory input is lost  | REFUSED                 |
      | evidence freshness    | a hash-bound input drifts or an enumerated offline artifact appears, disappears, is replaced, or changes metadata after preview                          | REFUSED                 |

  # RPK-JAVA-MOVE-002..004 and RPK-JAVA-MOVE-006..007
  @REQ-JAVA-MAVEN-MOVE-AUTH-009 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario: Explicit transitive test scope preserves target-closure move authority
    Given every POM, parent, BOM, mediation input, and transitive edge in the fixture's full effective reactor graph is complete, current, and hash-bound
    And a compile-visible fixture dependency has a fixture-owned POM that explicitly declares these field-shaped transitive test children while dependency management repeats each same groupId, artifactId, type, and classifier:
      | groupId                     | artifactId             | type | classifier | dependency-POM scope | matching managed scope       |
      | ch.qos.reload4j             | reload4j               | jar  | empty      | test                 | omitted, defaulting to compile |
      | org.apache.logging.log4j    | log4j-api-test         | jar  | empty      | test                 | compile                      |
      | org.apache.logging.log4j    | log4j-core-test        | jar  | empty      | test                 | omitted, defaulting to compile |
      | org.apache.logging.log4j    | log4j-core             | jar  | empty      | test                 | compile                      |
      | org.projectlombok           | lombok                 | jar  | empty      | test                 | omitted, defaulting to compile |
      | org.springframework         | spring-context-support | jar  | empty      | test                 | compile                      |
    And every listed POM, coordinate, version, repository entry, and source set belongs to the permanent fixture
    And the complete graph proves the target's before-and-staged authority closure and every main and test source-set analysis environment in that closure is "AVAILABLE"
    And ordinary binary absence is confined to structurally excluded modules outside that closure, so global external classpath status is "OFFLINE_MISSING"
    And one exact non-recovered target declaration and the full-reactor Java candidate inventory classify every target-capable range exactly once as "BOUND_TARGET" or "BOUND_OTHER", with zero "UNRESOLVED" candidates
    And apart from the declaration, every simple-name "BOUND_TARGET" use has exactly one resolved non-static single-type import of the target FQN and every other target use is the exact target FQN
    And no relevant type or static wildcard, unresolved static import, enclosing type, or supertype ambiguity can change target-name lookup
    When effective Maven traversal derives the consumer's main and test analysis classpaths and the move is previewed
    Then each listed child's explicit "test" scope is preserved instead of the matching default or "compile" managed scope
    And every listed child is excluded from the consumer's compile and test analysis classpaths
    And no listed child is reported as a missing consumer or target-closure binary
    And reactor structure status remains "COMPLETE" while global external classpath status remains "OFFLINE_MISSING" solely for the structurally excluded outside-closure modules
    And managed eligibility requires no per-binary coordinate/path, provided-type, or retained-diagnostic inventory for missing binaries outside the closure after structural and candidate exclusion is proved
    And the result is a "SEMANTIC_PREVIEW" with evidence "JDT_BINDING" and managed-write eligibility "ELIGIBLE"
    And exact staged analysis preserves candidate-total JDT authority and target-name lookup protection without a new or changed error
    And "LEXICAL_FALLBACK" remains review-only and managed-write ineligible
    When the approved preview is applied under the workspace lock
    Then its exact staged post-image is committed in exactly one managed transaction
    And authoritative after diagnostics attest the committed snapshot
    When that transaction is rolled back
    Then every workspace byte, path, source-inventory entry, and snapshot hash is exactly equal to the pre-apply image
    And authoritative rollback diagnostics attest the restored snapshot

  # RPK-JAVA-MOVE-002, RPK-JAVA-MOVE-004, and RPK-JAVA-MOVE-006..007
  @REQ-JAVA-MAVEN-MOVE-AUTH-010 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario: Descriptor-pruned children need no artifacts until another path selects them
    Given the fixture's inactive profile "req-java-maven-move-auth-010" is enabled in an isolated copy without changing its root aggregator or 20 active non-aggregator modules
    And the profile selects one fixture-owned external parent through a compile-visible target-closure dependency path
    And the selected parent's effective POM, parents, BOMs, dependency-management, mediation, repository, and declaration inputs are present, current, and hash-bound
    And the selected parent POM explicitly declares these field-shaped external children:
      | groupId                  | artifactId             | version                   | type     | classifier | declared scope | optional | inherited path exclusion                              | exact downstream prune                     |
      | ch.qos.reload4j          | reload4j               | 1.0.0-refactorkit-fixture | jar      | empty      | test           | false    | none                                                    | transitive test scope                      |
      | org.apache.logging.log4j | log4j-api-test         | 1.0.0-refactorkit-fixture | test-jar | tests      | test           | false    | none                                                    | transitive test scope                      |
      | org.apache.logging.log4j | log4j-core-test        | 1.0.0-refactorkit-fixture | test-jar | tests      | provided       | false    | none                                                    | transitive provided scope                  |
      | org.apache.logging.log4j | log4j-core             | 1.0.0-refactorkit-fixture | jar      | empty      | provided       | false    | none                                                    | transitive provided scope                  |
      | org.projectlombok        | lombok                 | 1.0.0-refactorkit-fixture | jar      | empty      | compile        | true     | none                                                    | transitive optional declaration            |
      | org.springframework      | spring-context-support | 1.0.0-refactorkit-fixture | jar      | empty      | compile        | false    | org.springframework:spring-context-support on this path | exact inherited group/artifact exclusion   |
    And every listed version is fixed and non-snapshot and every type and classifier has an unambiguous supported "jar" or "test-jar" variant mapping
    And type and classifier participate only in dependency-management and variant identity and never authorize pruning
    And every listed child's repository POM and normalized JAR path is deliberately absent
    And all other full-reactor structure, target-closure, candidate-total JDT, target-name lookup, platform, provider, diagnostic, and freshness evidence required by the move is complete and hash-bound
    And every before and staged target-closure main and test analysis environment is "AVAILABLE"
    When descriptor-closed Maven traversal builds every active main and test projection and the move is previewed twice from identical evidence hashes
    Then every outgoing child declaration and its selector inputs are hash-bound with the declaring POM, consumer module, source set, projection, dependency path, normalized variant, exact "PRUNED" outcome, and one exact scope, optional, or exclusion reason
    And every listed edge is pruned before child version or range resolution, repository or path selection, POM or effective-model lookup, JAR lookup, or any network request
    And no listed child contributes a selected node, subtree, compile or test analysis-classpath entry, missing descriptor, missing binary, or graph failure on that path
    And reactor structure status remains "COMPLETE" and every target-closure environment remains "AVAILABLE"
    And each result is a "SEMANTIC_PREVIEW" with evidence "JDT_BINDING" and managed-write eligibility "ELIGIBLE"
    And the selected and pruned edge records, target closure, candidate records, normalized edits, and diagnostics are identical between previews
    And exact staged analysis preserves candidate-total JDT authority and target-name lookup protection without a new or changed error
    And no Maven lifecycle, plugin, annotation processor, credential helper, snapshot repository, or network activity is used
    And "LEXICAL_FALLBACK" remains review-only and managed-write ineligible
    When one approved preview is applied under the workspace lock
    Then its exact staged post-image is committed in exactly one managed transaction
    And authoritative after diagnostics attest the committed snapshot
    When that transaction is rolled back
    Then every workspace byte, path, source-inventory entry, and snapshot hash is exactly equal to the pre-apply image
    And authoritative rollback diagnostics attest the restored snapshot
    When the restored fixture is evaluated with any listed coordinate also selected through an authority-relevant alternate path at effective "compile" or "runtime" scope without an optional or exact-exclusion prune
    Then that alternate edge is classified "SELECTED" and the child's complete effective POM, parent, BOM, management, mediation, and relocation closure becomes required before binary availability can be classified
    And the absent child POM produces a typed "REFUSED" result with managed-write eligibility "INELIGIBLE" and a stable structural blocker naming the coordinate, dependency path, projection, and missing descriptor
    And refusal occurs before workspace-lock acquisition and before write-ahead-log creation
    And no applyable plan ID, managed edit, lock file, write-ahead log, managed transaction, or workspace mutation is created

  # RPK-JAVA-MOVE-002..004 and RPK-JAVA-MOVE-006..007
  @REQ-JAVA-MAVEN-MOVE-AUTH-011 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario: RefactorKit-owned ordinary JAR absence preserves target-scoped move authority
    Given the fixture's inactive profile "req-java-maven-move-auth-011" is enabled in an isolated copy without changing its root aggregator or 20 active non-aggregator modules
    And "catalog-pricing:main", which belongs to the before-and-staged operation authority closure, selects exactly one fixture-owned non-reactor dependency from the explicitly supplied local-only repository "fixture-repository":
      | groupId                 | artifactId              | version | type | classifier | extension | effective scope |
      | com.acme.fixture.missing | ordinary-missing-leaf   | 1.0.0   | jar  | empty      | jar       | compile         |
    And the selected version is fixed and non-snapshot, the dependency has no relocation, and this bounded row admits no classified or "test-jar" variant
    And the repository's provider, Maven-2 layout, normalized canonical root, and no-settings local-only policy form one hash-bound repository identity
    And the deterministic repository POM path "com/acme/fixture/missing/ordinary-missing-leaf/1.0.0/ordinary-missing-leaf-1.0.0.pom" is a present regular file
    And the selected effective POM and every required parent, imported BOM, dependency-management, and mediation input are present, complete, current, and hash-bound
    And descriptor-closed traversal classifies every outgoing declaration and proves that the selected graph subtree has no selected child and consists only of this leaf in every affected projection
    And the normalized repository JAR path "com/acme/fixture/missing/ordinary-missing-leaf/1.0.0/ordinary-missing-leaf-1.0.0.jar" is absent in the before and staged images under no-follow filesystem observations
    And no JAR-adjacent ".refactorkit-evidence" sidecar, expected JAR content hash, or provided-type inventory exists or is supplied
    And all other full-reactor structure, target closure, source inventory, Java platform, provider, diagnostic, and freshness evidence required by the move is complete and hash-bound
    And one exact non-recovered target declaration and the full-reactor Java candidate inventory classify every before-and-staged target-capable range exactly once as "BOUND_TARGET" or "BOUND_OTHER", with zero "UNRESOLVED" candidates
    And apart from the declaration, every simple-name "BOUND_TARGET" use has exactly one resolved non-static single-type import of the applicable target FQN and every other target use is the exact applicable target FQN
    And no relevant type or static import-on-demand, target-named or unresolved static import, same-package lookup, inherited lookup, or enclosing-type lookup can change the selected target name
    And no recovered, problem, or ambiguous binding and no candidate, diagnostic, or edit overlap is present
    When the move is previewed twice from identical snapshot, repository, descriptor, candidate, and negative-presence evidence hashes
    Then each result is a "SEMANTIC_PREVIEW" with evidence "JDT_BINDING" and managed-write eligibility "ELIGIBLE"
    And each result continues to report global external classpath status "OFFLINE_MISSING"
    And each preview contains exactly one RefactorKit-enumerated selected-missing-binary record with this ordinary identity and path evidence:
      | evidence field          | expected evidence                                                                                                      |
      | selected coordinate     | com.acme.fixture.missing:ordinary-missing-leaf                                                                         |
      | selected version        | 1.0.0                                                                                                                  |
      | selected variant        | type jar, empty classifier, extension jar                                                                              |
      | repository identity     | provider, Maven-2 layout, normalized canonical fixture-repository root, and local-only policy                          |
      | deterministic POM path  | com/acme/fixture/missing/ordinary-missing-leaf/1.0.0/ordinary-missing-leaf-1.0.0.pom                                   |
      | deterministic JAR path  | com/acme/fixture/missing/ordinary-missing-leaf/1.0.0/ordinary-missing-leaf-1.0.0.jar                                   |
    And that record enumerates every selecting dependency path and projection and the exact affected operation-authority-closure source sets
    And that record contains and hash-binds the selected-POM content hash, every effective-input content hash, the graph-leaf proof, and the before-and-staged no-follow "ABSENT" observations with their evidence-fact hashes
    And that record has no expected JAR content hash and no provided-type inventory instead of inventing either
    And candidate-total exact JDT bindings and target-name lookup protection positively prove that the absent unrelated binary cannot conceal the selected target or any target edit
    And before-and-staged diagnostics contain zero unresolved-type diagnostics attributable to unknown provided types from the absent JAR
    And exact staged analysis preserves every "BOUND_OTHER" classification and introduces no new or changed error
    And the selected-missing record, target closure, candidate records, normalized edits, and diagnostics are identical between previews
    And no Maven lifecycle, plugin, settings file, mirror, proxy, server, credential, credential helper, or network request is executed or consulted
    And no caller-attested classpath or lexical evidence supplies authority, and "LEXICAL_FALLBACK" remains review-only and managed-write ineligible
    When one approved preview is applied under the workspace lock
    Then the lease revalidates the canonical repository identity, POM and effective-input hashes, leaf proof, and matching under-lock no-follow "ABSENT" JAR observation before write-ahead-log creation
    And its exact staged post-image is committed in exactly one managed transaction
    And authoritative after diagnostics attest the committed snapshot with zero unresolved-type diagnostics attributable to the absent JAR
    When that transaction is rolled back
    Then every workspace byte, path, source-inventory entry, and snapshot hash is exactly equal to the pre-apply image
    And authoritative rollback diagnostics attest the byte-exact restored snapshot
    When each evidence drift is introduced independently for a fresh eligible preview after its workspace lock is acquired and before write-ahead-log creation:
      | evidence drift                                                                                                                           |
      | the absent JAR path or one of its path components appears or is replaced by a file, directory, or symbolic link under a no-follow check |
      | the selected POM or any effective-input file is replaced, even at the same relative path                                                |
      | the canonical repository identity or normalized deterministic POM or JAR path differs from the preview                                 |
    Then each apply is "REFUSED" with managed-write eligibility "INELIGIBLE" and a stable evidence-drift blocker naming the coordinate, path, and changed evidence
    And each refusal occurs while the workspace lock is held but before write-ahead-log creation
    And no write-ahead log, managed edit, managed transaction, or RefactorKit workspace mutation is created

  # RPK-JAVA-MOVE-002, RPK-JAVA-MOVE-004, and RPK-JAVA-MOVE-006..007
  @REQ-JAVA-MAVEN-MOVE-AUTH-012 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario Outline: Missing or drifted selected descriptors refuse ordinary missing-leaf authority
    Given a fresh isolated fixture starts from every eligible precondition of "REQ-JAVA-MAVEN-MOVE-AUTH-011" for the fixed selected ordinary JAR leaf "com.acme.fixture.missing:ordinary-missing-leaf:1.0.0"
    And the absent JAR remains a binary-availability fact that can be considered only after complete selected descriptor closure
    And where an example requires a parent, BOM, management, mediation, or relocation input, a bounded fixture variant adds exactly one fixture-owned required input while preserving the selected coordinate, ordinary "jar" variant, direct dependency path, and "COMPILE", "RUNTIME", and "TEST" projections
    And every selected descriptor input other than the one named by the example is present, well-formed, current, and hash-bound
    When "<descriptor mutation>" is introduced independently in the "<descriptor layer>" before authority evaluation
    And the resulting workspace bytes, paths, inventories, snapshot hash, descriptor facts, evidence records, and evidence hashes are recorded
    Then descriptor-closed Maven traversal returns typed structural "REFUSED" guidance with managed-write eligibility "INELIGIBLE"
    And the stable blocker names the selected coordinate, its direct dependency path from "catalog-pricing:main", every affected source-set projection, and the "<descriptor layer>" as "<descriptor condition>"
    And no absent, malformed, incomplete, or drifted selected POM or effective-model input is reclassified as an admissible missing binary or a proven graph leaf
    And no clean target lookup, likely leaf shape, approval, caller-supplied classpath, or caller attestation compensates for the descriptor failure
    And refusal occurs before semantic edit selection, applyable plan ID issuance, workspace-lock acquisition, and write-ahead-log creation
    And no Maven lifecycle, plugin, settings file, mirror, proxy, server, credential, credential helper, or network request is executed or consulted
    And no caller classpath contributes authority, and "LEXICAL_FALLBACK" remains review-only and managed-write ineligible
    And no ".refactorkit" directory, lock file, write-ahead log, managed transaction, or RefactorKit workspace mutation is created
    And every workspace byte, path, inventory entry, snapshot hash, descriptor fact, evidence record, and evidence hash equals the state recorded before authority evaluation

    Examples:
      | descriptor layer                            | descriptor mutation                                                                                      | descriptor condition |
      | selected leaf POM                           | remove the deterministic selected leaf POM regular file                                                  | MISSING              |
      | selected leaf POM model                     | replace the selected leaf POM with malformed XML that cannot produce a complete raw or effective model   | MALFORMED            |
      | required parent POM                         | remove the one fixture-owned parent POM referenced by the selected leaf                                  | MISSING              |
      | required imported BOM                       | change the one fixture-owned imported BOM after its content hash is bound                                | DRIFTED              |
      | dependency-management/mediation declaration | change the required declaration that supplies the mediated selected version after hash binding           | DRIFTED              |
      | relocation/model parse evidence             | remove parsed-model proof that the selected leaf has no relocation while descriptor bytes remain present | MISSING              |
