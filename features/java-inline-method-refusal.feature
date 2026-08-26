# language: en
@implemented-and-validated
Ability: Refuse an unsupported inline-method Java refactoring request deterministically
  As a RefactorKit caller on a Java workspace
  I need the Java adapter to refuse an inlineMethod request with a deterministic typed
    refusal instead of silently guessing an edit
  So that unsupported Java refactorings fail closed with an explicit and auditable refusal

  This executable backlog covers row N-INLINE-METHOD of the approved finite J1 Java catalogue
  (REQ-JAVA-J1-CATALOGUE-APPROVED-001, baseline 8aa49c3): the Java adapter operation
  inlineMethod must return a deterministic REFUSED patch plan carrying the stable typed
  refusal code java.inlineMethod.unsupported. The catalogue refusal contract is: an empty
  WorkspaceEdit and an empty affected-file set, no approval and no managed-write eligibility,
  no pending actionable plan, no lock, WAL, or transaction record, and a deterministic
  operation name and refusal code. The refusal codes are asserted as step data below, not as
  narrative prose.

  Genuine RED finding (anti-fake): before the slice, production had no inline-method planner.
  JavaLanguageAdapter.applyRefactoring contained an inlineVariable branch but no inlineMethod
  branch, so an inlineMethod request fell through to the generic notImplemented fallback,
  which returned PatchStatus.REFUSED with the summary and warning text "Unknown operation:
  inlineMethod", an empty WorkspaceEdit, an empty affected-file set, requiresUserApproval=false,
  confidence=0.0, and riskLevel=HIGH, while the typed refusal-code field refusalCode stayed null.
  The RED assertion proved the gap: the typed refusal code java.inlineMethod.unsupported was
  NOT emitted by production. The Java adapter now emits the typed refusal code
  java.inlineMethod.unsupported with an empty edit and no managed-write authority, so this
  feature is promoted to @implemented-and-validated against fresh Cucumber GREEN evidence
  (3 scenarios, 18 steps). No implementation status is overclaimed; the independent
  requirements-quality-review PASS is pending.

  # Scenario 1: deterministic typed refusal for inlineMethod
  @REQ-JAVA-INLINE-METHOD-REFUSAL-001 @functional-requirement
  Scenario: An inlineMethod Java refactoring request is refused deterministically with java.inlineMethod.unsupported
    Given the Java adapter receives an inlineMethod refactoring request on a Java workspace
    When the Java adapter previews the refactoring request
    Then the adapter returns a REFUSED patch plan carrying the typed refusal code "java.inlineMethod.unsupported"
    And the refusal operation name is deterministically "inlineMethod"
    And the refusal is deterministic for the same request and snapshot

  # Scenario 2: no edit, no affected file, no approval, no managed-write eligibility
  @REQ-JAVA-INLINE-METHOD-REFUSAL-001 @functional-requirement
  Scenario: The inlineMethod refusal carries no edit, affected file, approval, or managed-write eligibility
    Given the Java adapter returns a REFUSED patch plan for an inlineMethod request
    When the caller inspects the refusal plan
    Then the refusal carries an empty WorkspaceEdit
    And the refusal carries an empty affected-file set
    And the refusal grants no approval
    And the refusal grants no managed-write eligibility

  # Scenario 3: no pending actionable plan, no lock, WAL, or transaction record
  @REQ-JAVA-INLINE-METHOD-REFUSAL-001 @functional-requirement
  Scenario: The inlineMethod refusal leaves no pending actionable plan and no lock, WAL, or transaction record
    Given the Java adapter returns a REFUSED patch plan for an inlineMethod request
    When the caller checks for persistent side effects
    Then the refusal leaves no pending actionable plan
    And the refusal records no lock
    And the refusal records no WAL
    And the refusal records no transaction entry
    And the refusal mutates no file on disk
