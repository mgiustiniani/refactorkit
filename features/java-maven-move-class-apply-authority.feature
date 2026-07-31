# language: en
Business Need: Authorize managed Java class moves with complete Maven semantic evidence
  As a maintainer of a multi-module Maven workspace
  I need RefactorKit to distinguish binding-clean move authority from review-only evidence
  So that only complete semantic plans can mutate and roll back the workspace

  This executable backlog covers ARC42 RPK-JAVA-MOVE-001 through RPK-JAVA-MOVE-007.
  LEXICAL_FALLBACK is always review-only and can never acquire managed-write authority.
  Declared status: 12 of 12 requirement definitions and 22 of 22 expanded cases carry
  implemented-and-validated status; no requirement definition or expanded case remains absent.

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
  @REQ-JAVA-MAVEN-MOVE-AUTH-003 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario: Safely enumerable preview defects produce immutable non-managed guidance
    Given each preview-time authority defect is evaluated independently for the otherwise supported canonical move request
    And every listed input and its affected scope remains readable, safely contained, and enumerable enough for complete bounded guidance:
      | Maven module              | source set | readable and safely enumerable input                                                                                                                | preview-time authority defect                                                                                         | stable blocker code                                                                       |
      | catalog-acceptance        | test       | catalog-acceptance/src/test/java/com/acme/catalog/acceptance/ProductLifecycleSteps.java                                                             | the required readable step-definition source is omitted from its source inventory                                    | java.maven.moveClass.sourceInventory.missingEntry                                          |
      | catalog-pricing           | main       | fixture-libs/catalog-price-contract-1.0.0.jar and its classpath evidence                                                                             | the expected local-artifact fingerprint differs from the freshly observed fingerprint for the same readable artifact | java.maven.moveClass.classpathFingerprint.mismatch                                         |
      | catalog-storefront        | main       | catalog-storefront/src/main/java/com/acme/catalog/storefront/ProductTile.java and its target-use range                                               | the readable target use resolves only to a recovered binding                                                          | java.maven.moveClass.targetUse.recoveredBinding                                            |
      | catalog-generated-support | main       | catalog-generated-support/target/generated-sources/catalog-metadata and its materialized generated-root inventory                                   | the expected inventory fingerprint differs from the freshly observed fingerprint for the same readable directory     | java.maven.moveClass.materializedGeneratedRootInventory.fingerprintMismatch                |
    And the materialized generated source root is owned by "catalog-generated-support:main"
    And each defect is present in the freshly observed evidence used to construct its preview, and no listed input changes after the request, snapshot, and evidence identities are bound
    And no ".refactorkit" directory exists and the workspace bytes, paths, inventories, and snapshot hash are recorded before each evaluation
    And lost, unreadable, unsafe, or unbounded required structural input and every change after snapshot or evidence binding are refused under "REQ-JAVA-MAVEN-MOVE-AUTH-008" as structural loss or post-preview evidence drift, not represented by this guidance
    When the unchanged move is previewed twice for each authority defect
    Then each pair returns the same immutable "REVIEW_ONLY_GUIDANCE" result
    And each result binds the same canonical request identity, exact workspace snapshot SHA-256, and deterministic canonical evidence SHA-256
    And each result carries the row's stable blocker code and reports the affected Maven module and source set as separate structured fields
    And each result keeps exact non-recovered "BOUND_TARGET" facts, exact non-recovered "BOUND_OTHER" facts, "UNRESOLVED" Java candidates, "JAVA_NON_CODE_RESIDUAL" occurrences, and "NON_JAVA_RESIDUAL" paths in separate typed groups
    And a recovered binding appears only as an "UNRESOLVED" Java candidate
    And every candidate or residual is bound to its snapshot, normalized path, exact range, and content hash without being described as an edit
    And candidate-list completeness has an explicit typed status and every known omission is a typed record with bounded identity
    And its ordered typed restoration actions respectively restore the source inventory, refresh classpath evidence, re-establish exact bindings, or externally restore the generated root, then require a full reactor rescan and a new preview
    And every result presents this fixed human and VCS-owned checklist in order:
      | order | verification                                                     |
      | 1     | create a VCS checkpoint                                          |
      | 2     | inspect every candidate and omission                              |
      | 3     | restore authority or make only confirmed manual changes           |
      | 4     | review Java non-code and non-Java residual risks                  |
      | 5     | run appropriate supplemental builds and tests                     |
      | 6     | inspect the final diff                                             |
      | 7     | use VCS for recovery                                               |
    But no result contains a "PatchPlan", "WorkspaceEdit", managed edit or replacement text, applyable plan ID, pending-plan entry, managed transaction or transaction identity, or RefactorKit rollback capability
    And no result can be converted into or promoted to a semantic plan
    When the same `refactorkit move-class --symbol com.acme.catalog.legacy.Product --to-package com.acme.catalog.api --apply` command is invoked for each unchanged guidance condition
    Then the CLI refuses with "guidance.nonManaged" before workspace-lock acquisition and before write-ahead-log creation
    And no ".refactorkit" directory, lock file, pending-plan record, write-ahead log, or managed transaction is created
    And every workspace byte, path, inventory entry, and snapshot hash equals the state recorded before its evaluation

  # RPK-JAVA-MOVE-004..005 and RPK-JAVA-MOVE-007
  @REQ-JAVA-MAVEN-MOVE-AUTH-004 @non-functional-requirement @implemented-and-validated
  Scenario: No force flag, recipe, or integration surface promotes lexical fallback
    Given each entry path is evaluated independently against an isolated fixture copy with a broad syntax failure in the required observer "catalog-acceptance/src/test/java/com/acme/catalog/acceptance/ProductLifecycleSteps.java"
    And the unchanged move cannot establish complete JDT binding authority and is classified with legacy evidence "LEXICAL_FALLBACK"
    And no ".refactorkit" directory exists and the workspace bytes, paths, source inventory, and snapshot SHA-256 are recorded before each evaluation
    When the move is previewed twice from the same normalized request and snapshot through each entry path:
      | entry path              | preview operation                                                       |
      | Java move-class outcome API | moveClass                                                               |
      | CLI                     | refactorkit move-class --symbol com.acme.catalog.legacy.Product --to-package com.acme.catalog.api --preview |
      | daemon JSON-RPC         | refactor.preview                                                        |
      | managed LSP             | workspace/executeCommand with refactorkit.moveClass                     |
      | MCP                     | preview_refactoring                                                     |
    Then every path projects the same immutable canonical "LEXICAL_FALLBACK_REVIEW" envelope
    And every envelope reports schema version 1, canonicalization "refactorkit.lexicalFallbackReview.canonical.v1", and hash algorithm "SHA-256"
    And every envelope binds the normalized request, exact snapshot, bounded review artifact, and sorted typed evidence facts with "requestSha256", "snapshotSha256", "reviewArtifactSha256", and "evidenceSha256"
    And every envelope has the same deterministic non-capability "operationId" derived from those identities and the fixed disposition, not a "PlanId"
    And every envelope reports authority status "REVIEW_ONLY", evidence kind "LEXICAL_FALLBACK", managed-write eligibility "INELIGIBLE", and blocker code "evidence.insufficient"
    And every envelope reports semantic completeness "NOT_SEMANTICALLY_PROVEN" and the fixed ordered actions "INSPECT_RESIDUALS", "RESTORE_SEMANTIC_EVIDENCE", "FULL_REACTOR_RESCAN", and "REQUEST_NEW_PREVIEW", while its edit-free residual guidance is capped at 200 records and 262144 canonical UTF-8 record bytes, exposes explicit "recordCompleteness" and "truncated" fields, reports deterministic "returnedRiskCategoryCounts", and sets "managedEdit" to false on every record without claiming semantic-reference or managed-edit capability
    And repeat preview with the same canonical inputs returns the same envelope and "operationId"
    But no envelope contains a "PatchPlan", "planId", "refactorkitPlanId", pending-plan handle, "WorkspaceEdit", affected edit, diff, replacement text, actionable apply token, transaction ID, or RefactorKit rollback capability
    And daemon JSON-RPC, managed LSP, and MCP preview create no pending managed plan, while managed LSP returns neither "changes" nor "documentChanges"
    And any retained "operationId" correlation is bounded, edit-free, audit-only, and separate from pending managed plans
    And CLI preview renders the envelope without an actionable apply token
    When approval, warning acknowledgement, confidence, or force metadata is attached without changing the canonical inputs
    Then the envelope, every SHA-256 identity, the "operationId", and the review-only disposition remain unchanged
    And no metadata converts the envelope to a semantic preview or managed plan
    And any direct compatibility-plan apply is refused by the core lexical-evidence gate before workspace-lock acquisition and write-ahead-log creation
    When a recipe reaches the lexical moveClass step in each position:
      | recipe position                                                        |
      | as its first step                                                       |
      | after an earlier valid step has produced only an in-memory staged image |
    Then recipe evaluation stops at that lexical step and returns its "LEXICAL_FALLBACK_REVIEW" directly instead of a recipe aggregate or plan
    And any earlier staged image is discarded, no later step runs, and no pending plan or transaction is created
    When the CLI apply command is invoked for the unchanged move
    Then the CLI exits non-zero with "evidence.insufficient" before "PatchEngine", workspace-lock acquisition, and write-ahead-log creation
    When each still-known "operationId" returned by daemon JSON-RPC, managed LSP, or MCP preview is submitted to that surface's apply entry point
    Then each surface returns the same envelope with typed "evidence.insufficient" before "PatchEngine", workspace-lock acquisition, write-ahead-log creation, editor application, or mutation
    And daemon JSON-RPC and managed LSP return "PLAN_VALIDATION_FAILED" code -32008 with structured data containing the blocker and envelope
    And MCP returns "isError" true with structured data containing the blocker and envelope
    But an arbitrary unknown "planId", including "plan-lexical", remains "INVALID_PARAMS" code -32602 and is never treated as known lexical evidence
    And after every preview or refused apply, every workspace byte, path, inventory entry, and snapshot hash equals the recorded state
    And no ".refactorkit" directory, lock file, write-ahead log, managed transaction, or RefactorKit rollback claim is created
    And "LEXICAL_FALLBACK_REVIEW" remains distinct from REQ-003 "REVIEW_ONLY_GUIDANCE" and never uses its "guidance.nonManaged" contract
    But a fresh full-reactor scan and new preview with complete exact bindings retain the existing "SEMANTIC_PREVIEW", "JDT_BINDING", "ELIGIBLE" managed-plan behavior

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
  @REQ-JAVA-MAVEN-MOVE-AUTH-008 @non-functional-requirement @implemented-and-validated
  Scenario Outline: Each isolated "<case>" authority failure is refused at its evidence phase
    Given a fresh isolated authority case "<case>" starts from every eligible target-scoped precondition of "REQ-JAVA-MAVEN-MOVE-AUTH-007"
    And no ".refactorkit" directory exists before the case-specific mutation or preview
    And every authority input except the isolated "<defect kind>" remains readable, safely contained, completely enumerable, non-truncated, current, and hash-bound
    And the bounded lexical Java scan inventories exact ranges for completeness and veto only and never selects an edit
    When the case performs this isolated mutation and evaluation: "<mutation and evaluation>"
    Then the phase outcome is "<outcome>" with managed-write eligibility "INELIGIBLE"
    And its primary blocker is "<blocker>" with authority layer "<authority layer>"
    And its stable typed data is "<typed data>"
    And its request, snapshot, overlay, candidate, and evidence identities satisfy "<identity contract>"
    And its exact timing and residue boundary is "<timing and residue>"
    And its exact preserved state is "<preserved state>"
    And repeat evaluation in a fresh isolated copy with the same canonical inputs returns the same outcome, primary blocker, authority layer, identities, and ordered typed data
    And no scanner-only lexical range is reported as "JDT_BINDING", selected as "BOUND_TARGET", or represented as an edit
    And no case returns "LEXICAL_FALLBACK_REVIEW", promotes lexical evidence, or converts guidance or refusal into a semantic plan
    And recovered-binding guidance and preview-construction fingerprint mismatch remain "REQ-JAVA-MAVEN-MOVE-AUTH-003", broad lexical fallback remains "REQ-JAVA-MAVEN-MOVE-AUTH-004", selected-path or selected-descriptor failures remain "REQ-JAVA-MAVEN-MOVE-AUTH-010" and "REQ-JAVA-MAVEN-MOVE-AUTH-012", and selected JAR, POM, effective-input, repository, or deterministic-path drift remains "REQ-JAVA-MAVEN-MOVE-AUTH-011"

    @preview-time-contract
    Examples: Preview-time authority evaluation before workspace lock
      | case                                      | defect kind                                             | mutation and evaluation                                                                                                                                                                                                                                                                                                                                                                                                     | outcome                                                      | blocker                                                                    | authority layer       | typed data                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | identity contract                                                                                                                                                                                            | timing and residue                                                                                                                                                                                  | preserved state                                                                                                                                                                                                                                            |
      | inside-closure ambiguous target candidate | ambiguous problem binding with complete candidates      | before preview create catalog-storefront/src/main/java/com/acme/catalog/storefront/decoy/Product.java, retain import com.acme.catalog.legacy.Product, add the competing explicit import com.acme.catalog.storefront.decoy.Product to catalog-storefront/src/main/java/com/acme/catalog/storefront/ProductTile.java, bind the mutated disk state S0, and preview                                                           | immutable REVIEW_ONLY_GUIDANCE                                | java.maven.moveClass.candidate.unresolved                                  | CANDIDATE_TOTALITY    | module=catalog-storefront; sourceSet=main; normalized ProductTile path and content SHA-256; every exact Product candidate range; bindingState=AMBIGUOUS; competingFqns=[com.acme.catalog.legacy.Product,com.acme.catalog.storefront.decoy.Product]; classification=UNRESOLVED; candidateCompleteness=COMPLETE; recovered=false; truncated=false                                                                                                                         | normalized request identity, mutated disk snapshot S0 SHA-256, deterministic canonical guidance evidence SHA-256, and candidate-record identities are bound                                                  | preview returns guidance; CLI apply refuses guidance.nonManaged before PatchEngine, workspace-lock acquisition, WAL, or any .refactorkit creation                                                   | all bytes, paths, inventories, S0, candidate records, and evidence identities equal the post-mutation pre-evaluation state; no PatchPlan, WorkspaceEdit, replacement, pending plan, lease, WAL, transaction, rollback claim, or destination exists              |
      | unresolved target-name lookup prerequisite | unresolved static import-on-demand owner                 | before preview add import static com.acme.fixture.missing.ProductProvider.* to ProductTile.java while retaining the exact non-static import com.acme.catalog.legacy.Product, keep every actual Product candidate exact, bind the mutated disk state S0, and preview                                                                                                                                                                  | immutable REVIEW_ONLY_GUIDANCE                                | java.maven.moveClass.targetLookup.unresolvedPrerequisite                   | TARGET_NAME_LOOKUP    | prerequisiteKind=STATIC_IMPORT_ON_DEMAND; normalized import path, exact range, and content SHA-256; unresolvedOwner=com.acme.fixture.missing.ProductProvider; targetSimpleName=Product; affectedCandidateRangeHash; every actual Product candidate remains exact BOUND_TARGET or BOUND_OTHER; candidateCompleteness=COMPLETE                                                                                                                                        | normalized request identity, mutated disk snapshot S0 SHA-256, deterministic canonical guidance evidence SHA-256, import-fact identity, and affected candidate-range identity are bound                       | preview returns guidance; CLI apply refuses guidance.nonManaged before PatchEngine, workspace-lock acquisition, WAL, or any .refactorkit creation                                                   | all bytes, paths, inventories, S0, exact candidate records, and evidence identities equal the post-mutation pre-evaluation state; no PatchPlan, WorkspaceEdit, replacement, pending plan, lease, WAL, transaction, rollback claim, or destination exists       |
      | old FQN outside observer closure           | explicit old FQN in an outside-closure Java type position | before preview replace the LEGACY_TYPE_NAME string field in reporting-unrelated/src/main/java/com/acme/reporting/ProductReport.java with a field typed com.acme.catalog.legacy.Product while reporting-unrelated:main retains no dependency path to catalog-model, bind the mutated disk state S0, and preview                                                                                                                      | immutable REVIEW_ONLY_GUIDANCE                                | java.maven.moveClass.reverseObserverClosure.explicitOldFqnOutside          | CLOSURE_CONSISTENCY   | normalized path, exact type-use range, content SHA-256, and fqn=com.acme.catalog.legacy.Product; sourceSet=reporting-unrelated:main; closureMembership=OUTSIDE; dependencyPath=NONE; closureEvidenceHash; observedClassification=UNRESOLVED; this closure blocker is primary and java.maven.moveClass.candidate.unresolved is not co-primary                                                                                                                          | normalized request identity, mutated disk snapshot S0 SHA-256, deterministic canonical guidance evidence SHA-256, candidate identity, and reverse-observer-closure evidence identity are bound               | preview returns guidance; CLI apply refuses guidance.nonManaged before PatchEngine, workspace-lock acquisition, WAL, or any .refactorkit creation                                                   | all bytes, paths, inventories, S0, closure and candidate records, and evidence identities equal the post-mutation pre-evaluation state; no PatchPlan, WorkspaceEdit, replacement, pending plan, lease, WAL, transaction, rollback claim, or destination exists |
      | retained diagnostic identity drift         | same-snapshot before-versus-staged diagnostic identity   | before preview add the fixture system dependency to catalog-model, add the destination-package non-annotation PriceAuthority, annotate legacy Product with unqualified @PriceAuthority, remove the fixture JAR, bind one disk state S0, run authoritative before analysis, apply only the staged move overlay S*, and run staged analysis without changing the filesystem                                                                 | immutable REVIEW_ONLY_GUIDANCE                                | java.maven.moveClass.retainedDiagnostic.identityDrift                     | DIAGNOSTIC_IDENTITY   | phase=BEFORE_VS_STAGED; before and staged provider hashes, problem IDs, categories, severities, normalized mapped paths, exact mapped ranges, messages, and diagnostic-multiset hashes; changedFields=[problemId,message]; category, severity, mapped path, and mapped range remain equal; diskDrift=false                                                                                                                                                         | normalized request identity, unchanged disk snapshot S0 SHA-256, staged overlay S* identity, deterministic canonical guidance evidence SHA-256, and before and staged diagnostic-multiset identities are bound | preview returns guidance; CLI apply refuses guidance.nonManaged before PatchEngine, workspace-lock acquisition, WAL, or any .refactorkit creation                                                   | every disk byte, path, inventory, and S0 remains unchanged across before and staged analysis; only S* differs; no PatchPlan, WorkspaceEdit, replacement, pending plan, lease, WAL, transaction, rollback claim, or destination exists                           |
      | missing active reactor module POM          | root-declared active reactor structural loss             | before preview remove catalog-shipping/pom.xml while the readable hash-bound root pom.xml still declares catalog-shipping as an active module, record the exact root declaration and no-follow absence fact, and run full-reactor discovery                                                                                                                                                                                           | PatchStatus.REFUSED with RefactoringEvidence.STRUCTURAL       | java.maven.reactorDescriptor.missing                                      | STRUCTURAL_CLOSURE    | inputKind=ACTIVE_REACTOR_POM; module=catalog-shipping; declaring root-POM content SHA-256 and exact module-declaration range; expectedPath=catalog-shipping/pom.xml; condition=MISSING; noFollowAbsenceFactHash; affectedFiles=[]; edits=[]; authorityLease=none; approval=none; pendingPlan=none; applyableIdentity=none                                                                                                                                              | normalized request identity, recorded post-removal disk snapshot S0 SHA-256, root-POM identity, module-declaration identity, no-follow absence-fact identity, and structural evidence SHA-256 are bound         | full-reactor discovery refuses before semantic edit selection, PatchEngine, workspace-lock acquisition, WAL, or any .refactorkit creation                                                         | catalog-shipping/pom.xml remains externally missing and every other byte, path, inventory, S0, and evidence identity equals the recorded post-removal state; no managed edit, destination, WAL, transaction, or rollback claim exists                         |

    @post-preview-contract
    Examples: Post-preview authority-lease validation under workspace lock
      | case                                      | defect kind                                             | mutation and evaluation                                                                                                                                                                                                                                                                                                                                                                                                     | outcome                                                      | blocker                                                                    | authority layer       | typed data                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | identity contract                                                                                                                                                                                            | timing and residue                                                                                                                                                                                  | preserved state                                                                                                                                                                                                                                            |
      | non-managed candidate inventory drift      | under-lock evidence freshness failure                   | first produce an eligible REQ-JAVA-MAVEN-MOVE-AUTH-007 SEMANTIC_PREVIEW with JDT_BINDING and the non-managed BOUND_OTHER catalog-decoy/src/main/java/com/acme/decoy/Product.java, acquire the workspace lock, then before authority-lease validation and WAL overwrite that file with valid source package com.acme.decoy; final class ProductDecoy {}, and submit the approved preview for apply                              | typed ApplyResult.Refused after the eligible semantic preview | authorityLease.evidenceDrift                                               | EVIDENCE_FRESHNESS    | evidenceKind=CANDIDATE_INVENTORY; normalized decoy path; expected and observed content SHA-256; previewSnapshot=S0; stagedOverlay=S*; observedSnapshot=S1; expected candidate-inventory SHA-256 plus expected and observed required-file-evidence SHA-256; leaseEvidence=E0; expectedClassification=BOUND_OTHER; changedSourceManaged=false                                                                                                                                                                   | normalized request identity, preview S0 SHA-256, staged S* identity, complete expected candidate-inventory SHA-256, expected and observed required-file-evidence SHA-256, lease evidence E0 SHA-256, and observed S1 SHA-256 are bound | apply acquires the workspace lock; authority-lease revalidation refuses while that lock is held and before WAL; the lock file may exist and no absence of .refactorkit is asserted after refusal | the external ProductDecoy bytes and observed S1 are preserved; equality to S0 is not asserted; no WAL, managed transaction, managed target edit, catalog-model/src/main/java/com/acme/catalog/api/Product.java, or other managed mutation is created          |

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
