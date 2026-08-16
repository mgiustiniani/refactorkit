# language: en
Business Need: Refuse a standalone companion-object selection as a package/file move
  As a maintainer of a Kotlin JVM workspace
  I need RefactorKit to refuse a compiler-proven companion-object selection as an independent package/file move
  So that a companion object is never silently relocated out of its enclosing top-level declaration

  This executable backlog covers REQ-KOTLIN-MOVE-COMPANION-REFUSAL-001 acceptance criteria
  AC-COMPANION-REFUSAL-001 and AC-COMPANION-REFUSAL-004, with the AC-COMPANION-REFUSAL-001 second
  sentence (a nested non-companion object shall not receive the companion refusal code) and the
  AC-COMPANION-REFUSAL-002 side-effect prohibition (no WorkspaceEdit, affected file, pending managed
  plan, lock, WAL, transaction, or filesystem mutation) asserted inside Scenario 1. The enclosing
  top-level type/file may still move under its independently qualified whole-file contract
  (AC-COMPANION-REFUSAL-003); extracting or independently relocating a companion is a separate future
  operation and is out of scope for this row. Missing approval or invalid/missing semantic identity
  retain their earlier fail-closed precedence (AC-COMPANION-REFUSAL-004); this row governs an
  otherwise exact approved companion selection.

  Declared status: production behavior exists on main
  (KotlinJvmMoveDeclarationPlanner line ~79 refuses target.kind == OBJECT with
  declaration.isCompanion via the stable typed code kotlin.moveCompanionStandaloneUnsupported), and
  all 3 scenarios pass GREEN against that existing production, not against a tests-only RED. The
  compiler-proven companion selection is REFUSED with kotlin.moveCompanionStandaloneUnsupported; a
  nested non-companion object is refused with kotlin.moveDeclarationUnsupported, not the companion
  code; missing approval retains fail-closed precedence and is refused with
  kotlin.moveExternalConsumerApprovalRequired before the companion refusal applies. No production
  code changed in this slice and no genuine executable tests-only RED exists for the refusal.
  Promotion to @implemented-and-validated is granted by the independent requirements-quality-reviewer
  PASS at candidate ea66a4a. The packaged and four-platform qualification remains deferred per
  approved-change-007, and the tests-only RED gate remains deferred as technical debt R-049 to be
  requalified at K5 band close per approved-change-008.
  All three scenarios are tagged @implemented-and-validated; the tests-only RED gate and the packaged
  and four-platform qualification remain deferred and no implementation status is overclaimed.

  Refusal codes in the scenarios are the actual production codes observed by the Cucumber glue, not
  invented granular codes. The source of truth matches executable reality (anti-fake).

  # AC-COMPANION-REFUSAL-001 and AC-COMPANION-REFUSAL-002
  @REQ-KOTLIN-MOVE-COMPANION-REFUSAL-001 @functional-requirement @implemented-and-validated
  Scenario: A compiler-proven companion-object selection refuses with kotlin.moveCompanionStandaloneUnsupported
    Given the caller explicitly accepts unknown external-consumer risk
    And the selected declaration is a compiler-proven companion object with exact K2 PSI evidence explicitly identifying the declaration as a companion object
    When moveDeclaration previews the selection
    Then the selection is refused with stable typed code "kotlin.moveCompanionStandaloneUnsupported"
    And the refusal contains no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation

  # AC-COMPANION-REFUSAL-001 second sentence: a nested non-companion object is not a companion
  @REQ-KOTLIN-MOVE-COMPANION-REFUSAL-001 @functional-requirement @implemented-and-validated
  Scenario: A nested non-companion object does not receive kotlin.moveCompanionStandaloneUnsupported
    Given the selected declaration is a nested object that is not a companion object
    And exact K2 PSI evidence does not identify the declaration as a companion object
    When moveDeclaration previews the selection
    Then the selection does not receive stable typed code "kotlin.moveCompanionStandaloneUnsupported"
    And the selection follows its own object shape handling under the independently qualified whole-file contract

  # AC-COMPANION-REFUSAL-004: fail-closed precedence for missing approval or invalid/missing identity
  @REQ-KOTLIN-MOVE-COMPANION-REFUSAL-001 @functional-requirement @implemented-and-validated
  Scenario: Missing approval or invalid/missing semantic identity retains fail-closed precedence
    Given the selected declaration is an otherwise exact approved companion selection with no explicit approval of unknown external-consumer risk
    When moveDeclaration previews the selection
    Then the selection is refused for missing approval before the companion refusal code applies
    And the refusal contains no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation
