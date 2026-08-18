# language: en
@not-implemented
Business Need: Rename one compiler-catalogued Kotlin/JVM value-parameter across its exact override family with K2 and JDT proof
  As a maintainer of a Kotlin JVM workspace
  I need RefactorKit to extend changeSignature with one bounded whole-family parameter-rename shape
  So that a compiler-proven Kotlin value-parameter is renamed atomically, preserving every Java caller binding and rollback

  This executable backlog covers REQ-KOTLIN-CHANGE-SIGNATURE-001, REQ-KOTLIN-CHANGE-SIGNATURE-002, and
  REQ-KOTLIN-CHANGE-SIGNATURE-003. It does not qualify adding, removing, reordering, or retyping parameters;
  migrating reflection or serialization strings; changing JVM descriptors; editing external binaries;
  compiler plugins; generated sources; scripts; Android, Multiplatform, `expect`/`actual`, context receivers,
  or ambiguous Java/Kotlin hierarchy edges. Positional Kotlin and Java call sites deliberately retain unchanged
  bytes; only the exact parameter declaration tokens in the family, the FIR-resolved body references to those
  parameter symbols, and the FIR argument-to-parameter-mapped Kotlin named-argument labels are edited.

  Declared status: @not-implemented. Production planners (KotlinChangeSignaturePlanner and the K2+JDT
  KotlinJvmChangeSignaturePlanner) exist on main and were not weakened. The slice's Cucumber runner/glue
  exists (KotlinJvmChangeSignatureParameterRenameCucumberTest and KotlinJvmChangeSignatureParameterRenameSteps
  in modules/refactorkit-jvm) and passes Linux source-built GREEN against that existing production: 33
  expanded cases / 171 steps, recorded separately as promotion evidence at
  docs/requirements/evidence/v0.7.0-k5-change-signature-green-ad2c953dbb52c75de55699f9e5522e5318a7ebdf420988fc5a2a674f9ce1d8fe.json.
  No scenario is tagged @implemented-and-validated and no implementation status is overclaimed (anti-fake).
  Per user-approved approved-change-010 (docs/requirements/kotlin-jvm-change-signature-parameter-rename-approved-change-010.md)
  the mandatory tests-only RED gate for REQ-KOTLIN-CHANGE-SIGNATURE-001..003 is deferred and recorded as
  ARCHITECTURAL/PROCESS technical debt (R-051, chapter 11), requalified at K5 band close. The 12 non-inducible
  defensive-gate refusal criteria are superseded and reframed per approved-change-011
  (docs/requirements/kotlin-jvm-change-signature-parameter-rename-approved-change-011.md) to assert the actual
  production behavior (SEMANTIC_PREVIEW success, read-only) with a defensive-gate note and are NOT removed.
  Packaged CLI/daemon/MCP apply and rollback and four-platform qualification remain deferred/not claimed.
  Promotion to @implemented-and-validated awaits only the independent requirements-quality-reviewer PASS.

  Refusal codes in the scenarios are the actual production codes observed in the KotlinChangeSignaturePlanner
  and KotlinJvmChangeSignaturePlanner sources, not invented granular codes. The source of truth is intended
  to match executable reality (anti-fake).

  Approved change 011 (docs/requirements/kotlin-jvm-change-signature-parameter-rename-approved-change-011.md)
  supersedes 12 defensive-gate refusal criteria of REQ-KOTLIN-CHANGE-SIGNATURE-001..003 that are
  NON-INDUCIBLE from a clean compiler fixture. Those 12 are reframed below to assert the actual production
  behavior (SEMANTIC_PREVIEW success, read-only, no refusal) with a defensive-gate note; they are NOT
  removed. The 21 inducible cases retain their refusal codes unchanged.

  # REQ-KOTLIN-CHANGE-SIGNATURE-001 — exact target and family
  @REQ-KOTLIN-CHANGE-SIGNATURE-001 @functional-requirement
  Scenario: A compiler-catalogued function value-parameter renames across its exact override family
    Given the selected declaration is one compiler-catalogued Kotlin function "fixture.billing.calculateTotal" whose K2-to-JVM owner, JVM name, and descriptor are exact
    And the selected value-parameter "subtotal" is identified by that callable identity plus zero-based ordinal 0
    And the snapshot carries complete error-free K2 evidence and one stable override-family identity from FIR override checking and resolved source class-supertypes
    And every source declaration in that override family has exactly one parameter at ordinal 0
    And the caller explicitly accepts unknown external-consumer risk because a family member is non-private
    When changeSignature.renameParameter previews renaming parameter "subtotal" to "netAmount"
    Then the result is a SEMANTIC_PREVIEW that renames the exact parameter declaration token at ordinal 0 in every family member atomically
    And every FIR-resolved body reference to those parameter symbols is renamed
    And every FIR argument-to-parameter-mapped Kotlin named-argument label "subtotal" is renamed
    And positional Kotlin call sites keep unchanged bytes
    And default argument expressions are preserved byte for byte
    And overload calls remain bound to the same JVM callable identity
    And the preview is read-only and does not mutate the snapshot

  # REQ-KOTLIN-CHANGE-SIGNATURE-001 — unrelated same-name and same-descriptor methods are not family members
  @REQ-KOTLIN-CHANGE-SIGNATURE-001 @functional-requirement
  Scenario Outline: A same-name overload or unrelated same-descriptor method is not a member of the target family
    Given the selected declaration is a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" whose parameter "subtotal" is at ordinal 0
    And the compiler catalogue also contains one "<unrelated member>" of the target callable identity
    And the caller explicitly accepts unknown external-consumer risk
    When changeSignature.renameParameter previews renaming parameter "subtotal" to "netAmount"
    Then the preview renames only the exact target-family declaration tokens and leaves the "<unrelated member>" parameter token and its uses unchanged

    Examples:
      | unrelated member                                                            |
      | same-name overload with a different descriptor                               |
      | unrelated same-descriptor method on another owner                            |

  # REQ-KOTLIN-CHANGE-SIGNATURE-001 — family incompleteness refuses
  @REQ-KOTLIN-CHANGE-SIGNATURE-001 @functional-requirement
  Scenario Outline: An incomplete, ambiguous, generated, plugin-dependent, or external-only override family refuses
    Given the selected declaration is a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" whose parameter "subtotal" is at ordinal 0
    And the override family is "<family condition>"
    When changeSignature.renameParameter previews renaming parameter "subtotal" to "netAmount"
    Then the selection is refused with stable typed code "<refusal code>" and no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation

    Examples:
      | family condition                                                | refusal code                                   |
      | incomplete with fewer family functions than family parameters   | kotlin.changeSignatureFamilyIncomplete        |
      | ambiguous with a function and parameter family that disagree    | kotlin.changeSignatureFamilyIncomplete        |
      | crossing an external or unavailable declaration boundary        | kotlin.changeSignatureExternalHierarchyUnsupported |
      | a hierarchy member with fewer than two family functions         | kotlin.changeSignatureExternalHierarchyUnsupported |
      | lacking one exact parameter declaration at the selected ordinal | kotlin.changeSignatureFamilyIncomplete        |

  # REQ-KOTLIN-CHANGE-SIGNATURE-002 — exact edits and refusals
  @REQ-KOTLIN-CHANGE-SIGNATURE-002 @functional-requirement
  Scenario: A non-private family parameter rename requires explicit external-consumer-risk acceptance
    Given the selected declaration is a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" whose parameter "subtotal" is at ordinal 0
    And the exact override family is complete and in-workspace
    And the family is externally visible because one member is non-private
    When changeSignature.renameParameter previews renaming parameter "subtotal" to "netAmount" without accepting external-consumer risk
    Then the selection is refused with stable typed code "kotlin.changeSignatureExternalConsumerApprovalRequired" and no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation
    When the caller explicitly accepts unknown external-consumer risk
    Then changeSignature.renameParameter previews a SEMANTIC_PREVIEW with a HIGH risk level and a warning that unknown external named-argument consumers were explicitly accepted

  # REQ-KOTLIN-CHANGE-SIGNATURE-002 — new-name conflict and unsafe identifiers refuse
  @REQ-KOTLIN-CHANGE-SIGNATURE-002 @functional-requirement
  Scenario Outline: An unsafe new parameter name or a binding-capturing new-name token refuses
    Given the selected declaration is a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" whose parameter "subtotal" is at ordinal 0
    And the requested new name is "<new name>" with the "<condition>" precondition
    When changeSignature.renameParameter previews renaming parameter "subtotal" to the requested name
    Then the selection is refused with stable typed code "<refusal code>" and no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation

    Examples:
      | new name      | condition                                                      | refusal code                                     |
      | "when"        | a Kotlin keyword                                               | kotlin.changeSignatureParameterNameInvalid       |
      | "1bad"        | not a safe identifier                                          | kotlin.changeSignatureParameterNameInvalid       |
      | "subtotal"    | the same as the old name                                       | kotlin.changeSignatureNoChange                   |
      | "netAmount"   | conflicting with another parameter in the exact family         | kotlin.changeSignatureParameterConflict          |
      | "netAmount"   | already occurring in an affected source so it could capture a binding | kotlin.changeSignatureParameterConflict    |

  # REQ-KOTLIN-CHANGE-SIGNATURE-002 — token-range refusals (inducible)
  @REQ-KOTLIN-CHANGE-SIGNATURE-002 @functional-requirement
  Scenario Outline: A missing or unsupported catalogued target, parameter, or token-range refuses
    Given the selected declaration is a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" whose parameter "subtotal" is at ordinal 0
    And the parameter declaration and use evidence is "<evidence condition>"
    When changeSignature.renameParameter previews renaming parameter "subtotal" to "netAmount"
    Then the selection is refused with stable typed code "<refusal code>" and no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation

    Examples:
      | evidence condition                                             | refusal code                                    |
      | missing from the compiler catalogue                            | kotlin.changeSignatureTargetMissing            |
      | a non-function target or blank descriptor or blank family      | kotlin.changeSignatureTargetUnsupported        |
      | no unique catalogued parameter named "subtotal"                | kotlin.changeSignatureParameterMissing         |
      | a missing, generated, duplicate, or mismatched token           | kotlin.changeSignatureRangeInvalid             |

  # REQ-KOTLIN-CHANGE-SIGNATURE-002 — token-identity guards (defensive)
  @REQ-KOTLIN-CHANGE-SIGNATURE-002 @functional-requirement
  Scenario Outline: Defensive token-evidence guards are not inducible from a clean compiler fixture
    defensive-gate, not inducible from a clean compiler fixture
    Given the selected declaration is a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" whose parameter "subtotal" is at ordinal 0
    And the "<evidence guard>" defensive gate is retained by the production planner
    When changeSignature.renameParameter previews renaming parameter "subtotal" to "netAmount" on a clean compiler-proven fixture
    Then the preview succeeds as a SEMANTIC_PREVIEW with no refusal code
    And the preview is read-only and does not mutate the snapshot or the filesystem

    Examples:
      | evidence guard                                 |
      | kotlin.changeSignatureIdentityMissing          |
      | kotlin.changeSignatureParameterIdentityInvalid |
      | kotlin.changeSignatureBaselineIncomplete       |

  # REQ-KOTLIN-CHANGE-SIGNATURE-002 — staged regression and post-image identity guards (defensive)
  @REQ-KOTLIN-CHANGE-SIGNATURE-002 @functional-requirement
  Scenario Outline: Defensive staged-preview guards are not inducible from a clean compiler fixture
    defensive-gate, not inducible from a clean compiler fixture
    Given an otherwise valid compiler-catalogued function "fixture.billing.calculateTotal" with parameter "subtotal" at ordinal 0
    And the "<staged guard>" defensive gate is retained by the production planner
    When changeSignature.renameParameter previews renaming parameter "subtotal" to "netAmount" on a clean compiler-proven fixture
    Then the preview succeeds as a SEMANTIC_PREVIEW with no refusal code
    And the preview is read-only and does not mutate the snapshot or the filesystem

    Examples:
      | staged guard                                                |
      | kotlin.changeSignatureDiagnosticsRegression                  |
      | kotlin.changeSignatureBindingChanged                         |
      | kotlin.changeSignaturePreviewInvalid                         |
      | kotlin.changeSignaturePostImageIdentityMissing               |

  # REQ-KOTLIN-CHANGE-SIGNATURE-003 — mixed K2 + JDT staged proof
  @REQ-KOTLIN-CHANGE-SIGNATURE-003 @functional-requirement
  Scenario: A Kotlin-only edit set still proves exact Java caller bindings against staged Kotlin output
    Given the selected declaration is a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" whose parameter "subtotal" is at ordinal 0
    And the pending plan's final edit set contains only Kotlin files
    And the exact operation is "changeSignature.renameParameter"
    And a positional Java caller binds to the unchanged owner, name, and descriptor "fixture.billing.calculateTotal"
    When changeSignature.renameParameter previews renaming parameter "subtotal" to "netAmount"
    Then the staged overlay compiles without new K2 errors
    And the same function and parameter JVM identities and exact usage counts are retained
    And every non-target K2 binding is retained
    And all Java sources compile with JDT against the staged Kotlin output
    And every exact Java caller binding to the unchanged owner, name, and descriptor is preserved
    And the preview records baseline and staged K2 plus JDT diagnostics
    And a warning states that the positional Java binding(s) retain the unchanged JVM descriptor

  # REQ-KOTLIN-CHANGE-SIGNATURE-003 — mixed baseline refusal (inducible)
  @REQ-KOTLIN-CHANGE-SIGNATURE-003 @functional-requirement
  Scenario Outline: A mixed K2+JDT baseline that is incomplete refuses fail-closed
    Given the selected declaration is a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" whose parameter "subtotal" is at ordinal 0
    And the mixed staged proof would "<mixed condition>"
    When changeSignature.renameParameter previews renaming parameter "subtotal" to "netAmount"
    Then the selection is refused with stable typed code "<refusal code>" and no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation

    Examples:
      | mixed condition                                              | refusal code                                   |
      | require clean K2 and JDT baseline evidence that is incomplete | kotlin.changeSignatureMixedBaselineIncomplete |

  # REQ-KOTLIN-CHANGE-SIGNATURE-003 — mixed staged regression guards (defensive)
  @REQ-KOTLIN-CHANGE-SIGNATURE-003 @functional-requirement
  Scenario Outline: Defensive mixed K2+JDT staged guards are not inducible from a clean compiler fixture
    defensive-gate, not inducible from a clean compiler fixture
    Given the selected declaration is a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" whose parameter "subtotal" is at ordinal 0
    And the "<mixed guard>" defensive gate is retained by the production planner
    When changeSignature.renameParameter previews renaming parameter "subtotal" to "netAmount" on a clean compiler-proven fixture
    Then the preview succeeds as a SEMANTIC_PREVIEW with no refusal code
    And the preview is read-only and does not mutate the snapshot or the filesystem

    Examples:
      | mixed guard                                                |
      | kotlin.changeSignatureMixedDiagnosticsRegression            |
      | kotlin.changeSignatureJavaBindingChanged                    |
      | kotlin.changeSignatureBinaryEvidenceUnavailable             |
      | kotlin.changeSignatureUsageEvidenceUnavailable              |
      | kotlin.changeSignaturePreviewInvalid                        |

  # REQ-KOTLIN-CHANGE-SIGNATURE-003 — apply and rollback
  @REQ-KOTLIN-CHANGE-SIGNATURE-003 @functional-requirement
  Scenario: Apply uses PatchEngine with an operation-owned lazy K2+JDT gate, WAL, and exact rollback
    Given an approved SEMANTIC_PREVIEW renames parameter "subtotal" to "netAmount" across the exact family
    And the managed-apply diagnostics gate for "changeSignature.renameParameter" is the lazy "kotlin-k2-java-jdt-change-signature" gate
    When the preview is applied under explicit authorization
    Then apply uses PatchEngine and writes a transaction rollback record
    And the committed post-image contains the renamed parameter tokens and named-argument labels
    And positional Kotlin and Java callers retain unchanged bytes
    When that transaction is rolled back
    Then every file byte, path, and snapshot hash equals the pre-apply image
    And rollback restores every original byte
