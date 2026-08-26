# language: en
@implemented-and-validated
Ability: Refuse an unsupported push-down member Java refactoring request deterministically
  As a RefactorKit caller on a Java workspace
  I need the Java adapter to refuse a pushDownMember request with a deterministic typed
    refusal instead of silently guessing an edit
  So that unsupported Java refactorings fail closed with an explicit and auditable refusal

  This executable backlog covers row N-PUSH-DOWN of the approved finite J1 Java catalogue
  (REQ-JAVA-J1-CATALOGUE-APPROVED-001, baseline 6791b88): the Java adapter operation
  pushDownMember must return a deterministic REFUSED patch plan carrying the stable typed
  refusal code java.hierarchy.pushDown.unsupported. The catalogue normalizes hierarchy
  moves to pull-up and push-down member moves only, so this row is the push-down half of
  that bounded pair. The catalogue refusal contract is: an empty WorkspaceEdit and an
  empty affected-file set, no approval and no managed-write eligibility, no pending
  actionable plan, no lock, WAL, or transaction record, and a deterministic operation
  name and refusal code. The refusal codes are asserted as step data below, not as
  narrative prose.

  Genuine RED finding (anti-fake): before this slice, production has no push-down-member
  planner. JavaLanguageAdapter.applyRefactoring contains inlineVariable, inlineMethod,
  and pullUpMember typed-refusal branches but no pushDownMember branch, so a
  pushDownMember request falls through to the generic notImplemented fallback, which
  returns PatchStatus.REFUSED with the summary and warning text "Unknown operation:
  pushDownMember", an empty WorkspaceEdit, an empty affected-file set,
  requiresUserApproval=false, confidence=0.0, and riskLevel=HIGH, while the typed
  refusal-code field refusalCode stays null. The RED assertion proved the gap:
  the typed refusal code java.hierarchy.pushDown.unsupported was NOT emitted by production
  before the slice. Production then added the pushDownMember typed-refusal branch, and this
  feature is promoted to feature-level @implemented-and-validated with pinned semantic
  evidence 0c9adecc: 3 scenarios and 18 steps GREEN through the Cucumber runner. The
  refusal carries an empty WorkspaceEdit and an empty affected-file set, grants no approval
  and no managed-write eligibility, and records no pending actionable plan, lock, WAL, or
  transaction entry. The scenarios below assert that refusal contract as executable
  backlog.

  # Scenario 1: deterministic typed refusal for pushDownMember
  @REQ-JAVA-HIERARCHY-PUSH-DOWN-REFUSAL-001 @functional-requirement
  Scenario: A pushDownMember Java refactoring request is refused deterministically with java.hierarchy.pushDown.unsupported
    Given the Java adapter receives a pushDownMember refactoring request on a Java workspace
    When the Java adapter previews the refactoring request
    Then the adapter returns a REFUSED patch plan carrying the typed refusal code "java.hierarchy.pushDown.unsupported"
    And the refusal operation name is deterministically "pushDownMember"
    And the refusal is deterministic for the same request and snapshot

  # Scenario 2: no edit, no affected file, no approval, no managed-write eligibility
  @REQ-JAVA-HIERARCHY-PUSH-DOWN-REFUSAL-001 @functional-requirement
  Scenario: The pushDownMember refusal carries no edit, affected file, approval, or managed-write eligibility
    Given the Java adapter returns a REFUSED patch plan for a pushDownMember request
    When the caller inspects the refusal plan
    Then the refusal carries an empty WorkspaceEdit
    And the refusal carries an empty affected-file set
    And the refusal grants no approval
    And the refusal grants no managed-write eligibility

  # Scenario 3: no pending actionable plan, no lock, WAL, or transaction record
  @REQ-JAVA-HIERARCHY-PUSH-DOWN-REFUSAL-001 @functional-requirement
  Scenario: The pushDownMember refusal leaves no pending actionable plan and no lock, WAL, or transaction record
    Given the Java adapter returns a REFUSED patch plan for a pushDownMember request
    When the caller checks for persistent side effects
    Then the refusal leaves no pending actionable plan
    And the refusal records no lock
    And the refusal records no WAL
    And the refusal records no transaction entry
    And the refusal mutates no file on disk
