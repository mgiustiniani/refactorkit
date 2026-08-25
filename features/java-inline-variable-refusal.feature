# language: en
@not-implemented
Ability: Refuse an unsupported inline-variable Java refactoring request deterministically
  As a RefactorKit caller on a Java workspace
  I need the Java adapter to refuse an inlineVariable request with a deterministic typed
    refusal instead of silently guessing an edit
  So that unsupported Java refactorings fail closed with an explicit and auditable refusal

  This executable backlog covers row N-INLINE-VAR of the approved finite J1 Java catalogue
  (REQ-JAVA-J1-CATALOGUE-APPROVED-001, baseline 8aa49c3): the Java adapter operation
  inlineVariable must return a deterministic REFUSED patch plan carrying the stable typed
  refusal code java.inlineVariable.unsupported. The catalogue refusal contract is: an empty
  WorkspaceEdit and an empty affected-file set, no approval and no managed-write eligibility,
  no pending actionable plan, no lock, WAL, or transaction record, and a deterministic
  operation name and refusal code. The refusal codes are asserted as step data below, not as
  narrative prose.

  Genuine RED finding (anti-fake): production currently has no inline-variable planner.
  JavaLanguageAdapter.applyRefactoring contains no inlineVariable branch, so the request falls
  through to the generic notImplemented fallback, which returns PatchStatus.REFUSED with the
  summary and warning text "Unknown operation: inlineVariable", an empty WorkspaceEdit, an
  empty affected-file set, requiresUserApproval=false, confidence=0.0, and riskLevel=HIGH,
  while the typed refusal-code field refusalCode stays null. The typed refusal code
  java.inlineVariable.unsupported is NOT emitted by production today. The scenario assertions
  below document the required contract; the Java adapter must emit the typed refusal code for
  this requirement to pass. The feature is declared @not-implemented and is a genuine RED
  slice, and no implementation status is overclaimed.

  # Scenario 1: deterministic typed refusal for inlineVariable
  @REQ-JAVA-INLINE-VARIABLE-REFUSAL-001 @functional-requirement
  Scenario: An inlineVariable Java refactoring request is refused deterministically with java.inlineVariable.unsupported
    Given the Java adapter receives an inlineVariable refactoring request on a Java workspace
    When the Java adapter previews the refactoring request
    Then the adapter returns a REFUSED patch plan carrying the typed refusal code "java.inlineVariable.unsupported"
    And the refusal operation name is deterministically "inlineVariable"
    And the refusal is deterministic for the same request and snapshot

  # Scenario 2: no edit, no affected file, no approval, no managed-write eligibility
  @REQ-JAVA-INLINE-VARIABLE-REFUSAL-001 @functional-requirement
  Scenario: The inlineVariable refusal carries no edit, affected file, approval, or managed-write eligibility
    Given the Java adapter returns a REFUSED patch plan for an inlineVariable request
    When the caller inspects the refusal plan
    Then the refusal carries an empty WorkspaceEdit
    And the refusal carries an empty affected-file set
    And the refusal grants no approval
    And the refusal grants no managed-write eligibility

  # Scenario 3: no pending actionable plan, no lock, WAL, or transaction record
  @REQ-JAVA-INLINE-VARIABLE-REFUSAL-001 @functional-requirement
  Scenario: The inlineVariable refusal leaves no pending actionable plan and no lock, WAL, or transaction record
    Given the Java adapter returns a REFUSED patch plan for an inlineVariable request
    When the caller checks for persistent side effects
    Then the refusal leaves no pending actionable plan
    And the refusal records no lock
    And the refusal records no WAL
    And the refusal records no transaction entry
    And the refusal mutates no file on disk
