# language: en
@not-implemented
Business Need: Rename a parameter of a Kotlin function across its full override family, safely
  As a maintainer of a Kotlin JVM workspace
  I need RefactorKit to rename one parameter of a Kotlin function across the complete family of overrides that share it
  So that the rename is applied atomically to every matching override, every Kotlin and Java caller stays bound, and the change can be previewed and rolled back

  This executable backlog covers REQ-KOTLIN-CHANGE-SIGNATURE-001, REQ-KOTLIN-CHANGE-SIGNATURE-002, and
  REQ-KOTLIN-CHANGE-SIGNATURE-003. It does not cover adding, removing, reordering, or retyping parameters;
  migrating reflection or serialization strings; changing Java method signatures on disk; editing external
  binaries; compiler plugins; generated sources; scripts; Android or Multiplatform builds; `expect` and
  `actual` declarations; context receivers; or ambiguous Kotlin and Java hierarchy edges. Positional Kotlin
  and Java call sites deliberately keep their bytes unchanged; only the exact parameter declaration tokens
  across the family, the compiler-resolved references to those parameters in the function body, and the Kotlin
  named-argument labels mapped to those parameters are edited.

  Declared status: @not-implemented. The production change-signature planning logic and the combined Kotlin and
  Java change-signature planner already exist on main and were not weakened. The slice's Cucumber runner and
  glue exist and pass a Linux source-built GREEN against that existing production: 33 expanded cases, recorded
  separately as promotion evidence (docs/requirements/evidence/v0.7.0-k5-change-signature-green-ad2c953dbb52c75de55699f9e5522e5318a7ebdf420988fc5a2a674f9ce1d8fe.json).
  No scenario is tagged @implemented-and-validated and no implementation status is overclaimed (anti-fake).

  Per the user-approved change 010 (docs/requirements/kotlin-jvm-change-signature-parameter-rename-approved-change-010.md),
  the mandatory tests-only RED gate for REQ-KOTLIN-CHANGE-SIGNATURE-001..003 is deferred and recorded as
  architecture and process technical debt (R-051, chapter 11), to be requalified at the close of this
  milestone band.

  The 12 non-inducible defensive-gate refusal criteria are superseded and reframed per approved change 011
  (docs/requirements/kotlin-jvm-change-signature-parameter-rename-approved-change-011.md) to assert the actual
  production behavior: a successful read-only preview with a defensive-gate note. They are not removed.
  Packaged CLI, daemon, and MCP apply and rollback, and four-platform qualification, remain deferred and not
  claimed. Promotion to @implemented-and-validated awaits only the independent requirements-quality-reviewer PASS.

  Per the user-approved change 012 (docs/requirements/kotlin-jvm-change-signature-parameter-rename-approved-change-012.md),
  4 non-inducible REQ-001 family-incompleteness refusal criteria are superseded and reframed below to assert the actual
  production behavior: a successful read-only semantic preview (SEMANTIC_PREVIEW), read-only, with an explicit
  "defensive-gate, not inducible from a clean compiler fixture" note. They are not removed. The inducible
  "lacking one exact parameter declaration at the selected ordinal" family-incompleteness case keeps its refusal.

  The refusal codes in the scenarios are the actual production codes observed in the change-signature planning
  sources, not invented granular codes. The source of truth is intended to match executable reality (anti-fake).

  Approved change 011 supersedes 12 defensive-gate refusal criteria of REQ-KOTLIN-CHANGE-SIGNATURE-001..003
  that are non-inducible from a clean compiler fixture. Those 12 are reframed below to assert the actual
  production behavior (a successful read-only preview, no refusal) with a defensive-gate note; they are not
  removed. The 21 inducible cases retain their refusal codes unchanged under change 011; approved change 012
  then supersedes 4 of the REQ-001 family-incompleteness cases among them, so the feature's expanded-case count
  now reconciles to 17 inducible cases plus 16 defensive cases (33 total).

  # REQ-KOTLIN-CHANGE-SIGNATURE-001 — exact target and family
  @REQ-KOTLIN-CHANGE-SIGNATURE-001 @functional-requirement
  Scenario: A maintainer renames a parameter of a Kotlin function across its exact override family
    Given a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" is selected, and its parameter "subtotal" is identified at ordinal 0
    And the snapshot carries complete error-free compiler evidence and one stable override-family identity from compiler override checking across resolved source hierarchies
    And every source declaration in that override family has exactly one parameter at ordinal 0
    And the caller explicitly accepts unknown external-consumer risk because one family member is non-private
    When a maintainer renames the parameter "subtotal" to "netAmount"
    Then RefactorKit returns a successful read-only preview that renames the parameter declaration at ordinal 0 in every family member atomically
    And every compiler-resolved body reference to those parameter symbols is renamed
    And every Kotlin named argument mapped to that parameter with the label "subtotal" is renamed
    And positional Kotlin call sites keep unchanged bytes
    And default argument expressions are preserved byte for byte
    And overload calls remain bound to the same callable identity
    And the preview is read-only and does not mutate the snapshot

  # REQ-KOTLIN-CHANGE-SIGNATURE-001 — unrelated same-name and same-descriptor methods are not family members
  @REQ-KOTLIN-CHANGE-SIGNATURE-001 @functional-requirement
  Scenario Outline: A same-name overload or an unrelated same-signature method is not part of the target family
    Given a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" is selected, and its parameter "subtotal" is at ordinal 0
    And the compiler catalogue also contains one "<unrelated member>" of that function
    And the caller explicitly accepts unknown external-consumer risk
    When a maintainer renames the parameter "subtotal" to "netAmount"
    Then RefactorKit renames only the exact target-family declaration tokens and leaves the "<unrelated member>" parameter token and its uses unchanged

    Examples:
      | unrelated member                                                            |
      | same-name overload with a different descriptor                               |
      | unrelated same-descriptor method on another owner                            |

  # REQ-KOTLIN-CHANGE-SIGNATURE-001 — family-incompleteness gates: inducible refusal and retained defensive guards
  @REQ-KOTLIN-CHANGE-SIGNATURE-001 @functional-requirement
  Scenario Outline: RefactorKit refuses an inducible family-incompleteness gap or previews a retained defensive family-incompleteness gate
    These four family-incompleteness gates are defensive safety checks that are not reproducible from a clean compiler fixture (defensive-gate, not inducible from a clean compiler fixture), so the production behavior is a successful read-only preview with a defensive-gate note. The inducible "lacking one exact parameter declaration at the selected ordinal" case keeps its refusal.
    Given a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" is selected, and its parameter "subtotal" is at ordinal 0
    And the override family is "<family condition>"
    When a maintainer renames the parameter "subtotal" to "netAmount"
    Then RefactorKit <outcome>
    And <assertion>

    Examples:
      | family condition                                                | outcome                                                                                         | assertion                                                         |
      | incomplete with fewer family functions than family parameters   | returns a successful read-only semantic preview (SEMANTIC_PREVIEW) with no refusal                 | the preview is read-only and does not mutate the snapshot or the filesystem |
      | ambiguous with a function and parameter family that disagree    | returns a successful read-only semantic preview (SEMANTIC_PREVIEW) with no refusal                 | the preview is read-only and does not mutate the snapshot or the filesystem |
      | crossing an external or unavailable declaration boundary        | returns a successful read-only semantic preview (SEMANTIC_PREVIEW) with no refusal                 | the preview is read-only and does not mutate the snapshot or the filesystem |
      | a hierarchy member with fewer than two family functions         | returns a successful read-only semantic preview (SEMANTIC_PREVIEW) with no refusal                 | the preview is read-only and does not mutate the snapshot or the filesystem |
      | lacking one exact parameter declaration at the selected ordinal | refuses the operation and explains why, reporting the typed code "kotlin.changeSignatureFamilyIncomplete" | changes no file, plan, lock, or transaction record |

  # REQ-KOTLIN-CHANGE-SIGNATURE-002 — exact edits and refusals
  @REQ-KOTLIN-CHANGE-SIGNATURE-002 @functional-requirement
  Scenario: Renaming a parameter of an externally visible family requires explicit acceptance of external-consumer risk
    Given a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" is selected, and its parameter "subtotal" is at ordinal 0
    And the exact override family is complete and in-workspace
    And the family is externally visible because one member is non-private
    When a maintainer renames the parameter "subtotal" to "netAmount" without accepting external-consumer risk
    Then RefactorKit refuses the operation and explains why, reporting the typed code "kotlin.changeSignatureExternalConsumerApprovalRequired", and changes no file, plan, lock, or transaction record
    When the caller explicitly accepts unknown external-consumer risk
    Then RefactorKit returns a successful read-only preview flagged HIGH risk, with a warning that unknown external named-argument consumers were explicitly accepted

  # REQ-KOTLIN-CHANGE-SIGNATURE-002 — new-name conflict and unsafe identifiers refuse
  @REQ-KOTLIN-CHANGE-SIGNATURE-002 @functional-requirement
  Scenario Outline: RefactorKit refuses an unsafe new parameter name or a name that could capture a binding
    Given a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" is selected, and its parameter "subtotal" is at ordinal 0
    And the requested new name is "<new name>" with the "<condition>" precondition
    When a maintainer renames the parameter "subtotal" to the requested new name
    Then RefactorKit refuses the operation and explains why, reporting the typed code "<refusal code>", and changes no file, plan, lock, or transaction record

    Examples:
      | new name      | condition                                                      | refusal code                                     |
      | "when"        | a Kotlin keyword                                               | kotlin.changeSignatureParameterNameInvalid       |
      | "1bad"        | not a safe identifier                                          | kotlin.changeSignatureParameterNameInvalid       |
      | "subtotal"    | the same as the old name                                       | kotlin.changeSignatureNoChange                   |
      | "netAmount"   | conflicting with another parameter in the exact family         | kotlin.changeSignatureParameterConflict          |
      | "netAmount"   | already occurring in an affected source so it could capture a binding | kotlin.changeSignatureParameterConflict    |

  # REQ-KOTLIN-CHANGE-SIGNATURE-002 — token-range refusals (inducible)
  @REQ-KOTLIN-CHANGE-SIGNATURE-002 @functional-requirement
  Scenario Outline: RefactorKit refuses when the target function, parameter, or token range cannot be identified
    Given a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" is selected, and its parameter "subtotal" is at ordinal 0
    And the parameter declaration and use evidence is "<evidence condition>"
    When a maintainer renames the parameter "subtotal" to "netAmount"
    Then RefactorKit refuses the operation and explains why, reporting the typed code "<refusal code>", and changes no file, plan, lock, or transaction record

    Examples:
      | evidence condition                                             | refusal code                                    |
      | missing from the compiler catalogue                            | kotlin.changeSignatureTargetMissing            |
      | a non-function target or blank descriptor or blank family      | kotlin.changeSignatureTargetUnsupported        |
      | no unique catalogued parameter named "subtotal"                | kotlin.changeSignatureParameterMissing         |
      | a missing, generated, duplicate, or mismatched token           | kotlin.changeSignatureRangeInvalid             |

  # REQ-KOTLIN-CHANGE-SIGNATURE-002 — token-identity guards (defensive)
  @REQ-KOTLIN-CHANGE-SIGNATURE-002 @functional-requirement
  Scenario Outline: Defensive token-identity guards cannot be triggered from a clean compiler fixture
    These guards are defensive safety checks that are not reproducible from a clean compiler fixture, so the production behavior is a successful read-only preview with a defensive-gate note.
    Given a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" is selected, and its parameter "subtotal" is at ordinal 0
    And the "<evidence guard>" defensive gate is retained by the production planner
    When a maintainer renames the parameter "subtotal" to "netAmount" on a clean compiler-proven fixture
    Then RefactorKit returns a successful read-only semantic preview (SEMANTIC_PREVIEW) with no refusal
    And the preview is read-only and does not mutate the snapshot or the filesystem

    Examples:
      | evidence guard                                 |
      | kotlin.changeSignatureIdentityMissing          |
      | kotlin.changeSignatureParameterIdentityInvalid |
      | kotlin.changeSignatureBaselineIncomplete       |

  # REQ-KOTLIN-CHANGE-SIGNATURE-002 — staged regression and post-image identity guards (defensive)
  @REQ-KOTLIN-CHANGE-SIGNATURE-002 @functional-requirement
  Scenario Outline: Defensive staged-preview guards cannot be triggered from a clean compiler fixture
    These guards are defensive safety checks that are not reproducible from a clean compiler fixture, so the production behavior is a successful read-only preview with a defensive-gate note.
    Given an otherwise valid compiler-catalogued function "fixture.billing.calculateTotal" is selected, with parameter "subtotal" at ordinal 0
    And the "<staged guard>" defensive gate is retained by the production planner
    When a maintainer renames the parameter "subtotal" to "netAmount" on a clean compiler-proven fixture
    Then RefactorKit returns a successful read-only semantic preview (SEMANTIC_PREVIEW) with no refusal
    And the preview is read-only and does not mutate the snapshot or the filesystem

    Examples:
      | staged guard                                                |
      | kotlin.changeSignatureDiagnosticsRegression                  |
      | kotlin.changeSignatureBindingChanged                         |
      | kotlin.changeSignaturePreviewInvalid                         |
      | kotlin.changeSignaturePostImageIdentityMissing               |

  # REQ-KOTLIN-CHANGE-SIGNATURE-003 — mixed K2 + JDT staged proof
  @REQ-KOTLIN-CHANGE-SIGNATURE-003 @functional-requirement
  Scenario: A Kotlin-only edit set still proves that every Java caller stays bound after a staged rebuild
    Given a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" is selected, and its parameter "subtotal" is at ordinal 0
    And the pending plan's final edit set contains only Kotlin files
    And the exact operation is a parameter rename
    And a positional Java caller binds to the unchanged owner, name, and descriptor "fixture.billing.calculateTotal"
    When a maintainer renames the parameter "subtotal" to "netAmount"
    Then the staged overlay compiles without new Kotlin compiler errors
    And the same function and parameter identities and exact usage counts are retained
    And every non-target binding is retained
    And all Java sources compile against the staged Kotlin output
    And every Java caller binding to the unchanged owner, name, and descriptor is preserved
    And the preview records baseline and staged Kotlin plus Java compiler diagnostics
    And a warning states that the positional Java binding(s) retain the unchanged JVM descriptor

  # REQ-KOTLIN-CHANGE-SIGNATURE-003 — mixed baseline refusal (inducible)
  @REQ-KOTLIN-CHANGE-SIGNATURE-003 @functional-requirement
  Scenario Outline: RefactorKit refuses when the combined Kotlin and Java baseline evidence is incomplete
    Given a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" is selected, and its parameter "subtotal" is at ordinal 0
    And the combined staged proof would "<mixed condition>"
    When a maintainer renames the parameter "subtotal" to "netAmount"
    Then RefactorKit refuses the operation and explains why, reporting the typed code "<refusal code>", and changes no file, plan, lock, or transaction record

    Examples:
      | mixed condition                                              | refusal code                                   |
      | require clean K2 and JDT baseline evidence that is incomplete | kotlin.changeSignatureMixedBaselineIncomplete |

  # REQ-KOTLIN-CHANGE-SIGNATURE-003 — mixed staged regression guards (defensive)
  @REQ-KOTLIN-CHANGE-SIGNATURE-003 @functional-requirement
  Scenario Outline: Defensive combined Kotlin and Java staged guards cannot be triggered from a clean compiler fixture
    These guards are defensive safety checks that are not reproducible from a clean compiler fixture, so the production behavior is a successful read-only preview with a defensive-gate note.
    Given a compiler-catalogued Kotlin function "fixture.billing.calculateTotal" is selected, and its parameter "subtotal" is at ordinal 0
    And the "<mixed guard>" defensive gate is retained by the production planner
    When a maintainer renames the parameter "subtotal" to "netAmount" on a clean compiler-proven fixture
    Then RefactorKit returns a successful read-only semantic preview (SEMANTIC_PREVIEW) with no refusal
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
  Scenario: Applying the rename uses the patch engine with a lazy compiler gate, a transaction record, and exact rollback
    Given an approved successful preview renames parameter "subtotal" to "netAmount" across the exact family
    And the managed-apply diagnostics gate for a parameter rename is the lazy combined Kotlin and Java change-signature gate
    When the preview is applied under explicit authorization
    Then apply uses the patch engine and writes a transaction rollback record
    And the committed post-image contains the renamed parameter tokens and named-argument labels
    And positional Kotlin and Java callers retain unchanged bytes
    When that transaction is rolled back
    Then every file byte, path, and snapshot hash equals the pre-apply image
    And rollback restores every original byte
