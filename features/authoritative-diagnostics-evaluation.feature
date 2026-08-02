# language: en
@functional-requirement @implemented-and-validated
Feature: Evaluate authoritative snapshots and diagnostics for model-changing edits
  A model-changing edit needs one post-image identity whose semantic evidence and diagnostics describe the same exact candidate.
  The evaluation is language-neutral, transient, and subordinate to the existing PatchEngine write authority.
  Eventual Cucumber glue uses the real public PatchEngine, DiagnosticsGate, and TransactionLog boundaries in bounded temporary workspaces with immutable synthetic ProjectSnapshots and build models; no direct JUnit behavior path substitutes for these scenarios.

  @REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-001
  Scenario: An authoritative post-image is journaled and committed with one exact identity
    Given a bounded temporary workspace has exact immutable baseline ProjectSnapshot "S0", diagnostic multiset "D0", synthetic build evidence, and no transaction record
    And an explicitly approved model-changing PatchPlan is bound to "S0" and normalizes without writing to exact staged file candidate "C1"
    And the language-neutral provider returns one immutable ProjectSnapshot and diagnostic multiset evaluated from that same supplied candidate
    And "S0" and each otherwise exact provider result include one explicitly declared external regular-file classpathEvidence whose fingerprint matches an engine-controlled no-follow recomputation from its current bytes for its path and kind
    And each provider result is constructed from caller-owned mutable collections with a distinct sentinel at every captured semantic boundary:
      | caller-owned collection boundary                                                                                                                                                       |
      | ProjectSnapshot files, auxiliaryFiles, sourceExtensions, and ignoredDirectories                                                                                                        |
      | ProjectSnapshot modules                                                                                                                                                                 |
      | each Module's sourceRoots, mainSourceRoots, testSourceRoots, generatedSourceRoots, and generatedTestSourceRoots collections                                                             |
      | each Module's classpathEntries, mainClasspathEntries, mainRuntimeClasspathEntries, and testClasspathEntries collections                                                                 |
      | each Module's dependencies, mainDependencies, testDependencies, mainOutputDirectories, testOutputDirectories, and languageSettings collections                                          |
      | ProjectSnapshot classpathEvidence and buildModels                                                                                                                                       |
      | each BuildModel's modules, diagnostics, and attributes collections                                                                                                                      |
      | each BuildModule's sourceSets and attributes collections                                                                                                                                |
      | each BuildSourceSet's sourceRoots, generatedSourceRoots, outputDirectories, classpathEntries, runtimeClasspathEntries, moduleDependencies, and attributes collections                    |
      | the diagnostics input list and every caller-owned fields map supplied to DiagnosticDetails                                                                                              |
    And immediately after constructing each AuthoritativeDiagnosticsEvaluation, the provider mutates every supplied collection and attempts the same sentinel changes through all outer and nested collections exposed by the returned value
    When the caller invokes the real public PatchEngine.apply with authoritative evaluation selected through the additive DiagnosticsGate boundary
    Then the authoritative evaluation trace is exactly:
      | order | phase     | exact input                         | required result                              |
      | 1     | baseline  | S0                                  | ProjectSnapshot S0 and diagnostics D0        |
      | 2     | staged    | C1                                  | ProjectSnapshot S1 and diagnostics D1        |
      | 3     | committed | committed candidate derived from S1 | snapshot hash S1.hash and diagnostics D1     |
    And after every caller and exposed-view mutation attempt, each returned evaluation still exposes its exact construction-time ProjectSnapshot value and complete diagnostic multiset
    And no attempted mutation through an exposed outer or nested collection changes any subsequently observed evaluation value or produces a partial change
    And each evaluation's reported snapshot hash and a fresh canonical hash recomputed from its complete post-attempt exposed snapshot state both equal its construction-time hash
    And the matching external classpathEvidence is accepted on fingerprint authenticity and is not refused solely because its path is outside the workspace
    And "S1" preserves "C1" workspace root, source and auxiliary partition, tracked paths, language IDs, contents, sourceExtensions, and ignoredDirectories exactly, while only modules, classpathEvidence, and buildModels may be rehydrated
    And after staged evaluation returns, PatchEngine revalidates the live workspace and operation-authority lease as exact "S0" under the same lock before WAL
    And the rendered transaction images equal the tracked image of both "C1" and "S1"
    And the sole TransactionLog record first becomes PREPARED at schema version 8 with preSnapshotHash "S0.hash" and postSnapshotHash "S1.hash"
    And PatchEngine returns Applied only after committed evaluation reproduces exact "S1.hash" and the complete "D1" multiset

  @REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-002
  Scenario Outline: Provider authority violation "<case>" is refused before WAL
    Given an approved model-changing plan over exact live "S0" stages exact candidate "C1" and TransactionLog.listRecordsReadOnly is empty
    And the authoritative provider "<provider behavior>"
    When the caller submits the plan through the real public PatchEngine.apply and additive DiagnosticsGate boundary
    Then PatchEngine reports "<violated boundary>" as an authority violation before a PREPARED WAL record and before any managed plan edit
    And TransactionLog.listRecordsReadOnly remains empty, so no write transaction exists
    And no planned edit is applied; a provider-induced live mutation remains external drift rather than a managed transaction
    And neither the provider result nor its diagnostics can approve or authorize a write

    Examples:
      | case                                      | provider behavior                                                                                                                                         | violated boundary                                        |
      | workspace root                            | returns a snapshot rooted at a different bounded workspace                                                                                                | normalized workspace root                                |
      | source and auxiliary split                | repartitions one tracked file between source and auxiliary inventories                                                                                    | source and auxiliary partition                           |
      | tracked path                              | returns one tracked file under a different normalized path                                                                                                | tracked path inventory                                   |
      | language ID                               | returns one tracked file with a different language ID                                                                                                     | tracked language identity                                |
      | tracked content                           | returns one tracked file with different content                                                                                                           | tracked content                                          |
      | source extensions                         | returns a different sourceExtensions set                                                                                                                  | source extension scope                                   |
      | ignored directories                       | returns a different ignoredDirectories set                                                                                                                | ignored-directory policy                                 |
      | outside Module root                       | sets one Module.root outside C1.workspace.root after normalized workspace resolution                                                                       | normalized workspace containment of Module.root          |
      | outside Module source root                | sets one Module.sourceRoots entry outside C1.workspace.root after normalized workspace resolution                                                          | normalized workspace containment of Module.sourceRoots   |
      | outside BuildModule root                  | sets one BuildModule.root outside C1.workspace.root after normalized workspace resolution                                                                  | normalized workspace containment of BuildModule.root     |
      | fabricated external classpath fingerprint | claims a fingerprint for explicit external regular-file classpathEvidence that differs from the engine-recomputed current no-follow byte fingerprint for its path and kind | no-follow fingerprint authenticity of classpathEvidence  |
      | live workspace mutation                   | changes a live tracked file while evaluating and otherwise returns an exact candidate                                                                       | post-provider live S0 revalidation                       |

  @REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-003
  Scenario Outline: Post-apply "<mismatch>" advances the same record through automatic rollback
    Given authoritative baseline and staged evaluation have accepted exact "S0" with "D0" and exact "S1" with "D1"
    And committed evaluation will return "<post-apply result>" while restored evaluation will reproduce exact "S0.hash" and "D0"
    When the caller invokes the real public PatchEngine.apply for the approved model-changing plan
    Then "<mismatch>" is detected after the sole record reached APPLIED and is reported as post-apply failure rather than pre-WAL refusal
    And that same schema-v8 record advances through "PREPARED, APPLYING, APPLIED, ROLLING_BACK, ROLLED_BACK"
    And automatic rollback restores every tracked byte and created-directory fact to the exact "S0" image
    And the restored workspace is reevaluated as exact "S0.hash" with the complete "D0" multiset before the refusal returns
    And TransactionLog.listRecordsReadOnly contains exactly that one transaction and no second transaction

    Examples:
      | mismatch                    | post-apply result                                      |
      | semantic snapshot mismatch  | a hash different from S1.hash with diagnostics D1     |
      | diagnostic multiset mismatch | exact S1.hash with diagnostics different from D1      |

  @REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-004
  Scenario: Existing diagnostics-only gates retain additive compatibility
    Given two otherwise identical diagnostics-only plans cannot change hash-bound modules, classpathEvidence, or buildModels and start from the same immutable "S0"
    And the caller constructs existing DiagnosticsGate.enabled and DiagnosticsGate.disabled values without selecting authoritative evaluation
    When each plan is applied through the real public PatchEngine.apply and inspected through TransactionLog
    Then their exact compatibility traces are:
      | gate     | provider invocation count and order | exact provider inputs                                                                 | journal postSnapshotHash                  |
      | enabled  | 3: baseline, staged, committed      | S0; simulator snapshot for C1 retaining S0 semantic metadata; committed rehydration of that snapshot | existing simulator-derived staged hash    |
      | disabled | 0                                   | none                                                                                  | existing simulator-derived staged hash    |
    And constructing either gate invokes no provider; enabled invokes its provider lazily only in the listed apply order and disabled never invokes one
    And every enabled provider input retains the exact diagnostics-only ProjectSnapshot identity and hash used by the existing gate
    And enabled retains existing diagnostic identity, error-multiset regression, approved-regression, unavailability, post-apply, and automatic-rollback semantics
    And disabled retains its existing apply, result, hash, and rollback semantics without diagnostic evaluation
    And existing records remain schema version 8 with unchanged preSnapshotHash, postSnapshotHash, image, checksum, idempotency, and exact-result meanings
    But the additive authoritative path introduces no schema bump, historical rewrite, new persistence, serialized evaluator state, scanner registry, or generic orchestration service
    And core gains no language-specific type, while a provider gains no edit, approval, lock, WAL, transaction, apply, or rollback authority
