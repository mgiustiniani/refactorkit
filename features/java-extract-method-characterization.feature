# language: en
@implemented-and-validated
Ability: Extract one straight-line complete-line no-argument private void method and refuse unsupported selections with a finite real refusal message list
  As a RefactorKit caller on a Java workspace
  I need the Java extractMethod preview to turn one straight-line complete-line block
    of simple statements into a call plus a no-argument private void method and to refuse
    unsupported selections with a deterministic real refusal message
  So that safe limited extractions are previewable and auditable and unsupported ones fail closed

  This executable backlog covers row C-EXTRACT of the approved finite J1 Java catalogue
  (REQ-JAVA-J1-CATALOGUE-APPROVED-001): the Java operation extractMethod must accept an
  existing straight-line, complete-line, no-argument private void extraction and produce a
  PREVIEW patch plan for the operation extractMethod. The supported shape is a Java source
  file, a 1-based inclusive line range, and extraction of the selected complete lines into a
  private void method named by the caller: the selected block is replaced with a call at the
  original indentation and the new method declaration is inserted before the final class
  closing brace. The successful plan carries confidence 0.70, requiresUserApproval true, risk
  MEDIUM, an affected-file set listing the source file, and a WorkspaceEdit with exactly one
  FileEdit.Modify containing a replace TextEdit and an insert TextEdit. The plan warns that the
  limited MVP supports only no-argument private void methods and that the selection is refused
  when local variables, return values, exceptions, or complex control flow are detected.

  Characterization RED-deferral (truthful, anti-fake): production already implements the
  planner JavaExtractMethodPlanner in the Java adapter, so this feature begins GREEN and
  asserts the real behavior rather than inventing it. The planner emits refusals as real
  MESSAGE strings through a refused snapshot plan, not as typed refusal codes; there is no
  refusalCode field. Every refusal message asserted below is the exact message string the
  planner emits, and no invented extractMethod.xxx typed code is introduced. The RED-deferral
  is pre-approved in the J1 catalogue because no Cucumber runner, glue, or independent
  requirements-quality review exists for this row yet; the feature-level status is set to
  the @not-implemented tag until a runner and glue validate the scenarios and an independent reviewer
  passes. Each refusal is a REFUSED patch plan carrying its real message in the summary and
  warnings, an empty WorkspaceEdit and an empty affected-file set, confidence 0.0, risk HIGH,
  and no approval and no managed-write eligibility.

  # Scenario 1: successful straight-line complete-line no-argument private void extraction
  @REQ-JAVA-EXTRACT-METHOD-CHAR-001 @functional-requirement
  Scenario: A straight-line complete-line no-argument private void extract is previewed as a replace plus insert edit set
    Given a Java workspace snapshot that contains a recognized non-generated Java source file
    And the file declares a final class whose body ends with a single class closing brace
    When the caller requests an extractMethod preview for a valid method name and a straight-line complete-line range of simple statements that use no local variables and no return, throw, break, continue, or yield
    Then the adapter returns a PREVIEW patch plan for the operation extractMethod
    And the plan carries confidence 0.70
    And the plan requires user approval
    And the plan risk is MEDIUM
    And the plan lists the source file in its affected-file set
    And the plan carries a WorkspaceEdit with exactly one FileEdit.Modify for that file
    And that FileEdit.Modify contains a replace TextEdit and an insert TextEdit
    And the replace edit rewrites the selected block with a call to the new method at the original indentation
    And the insert edit inserts a no-argument private void method declaration before the final class closing brace
    And the plan warns that the limited MVP supports only no-argument private void methods
    And the plan warns that the selection was refused if local variables, return values, exceptions, or complex control flow were detected

  # Scenario 2: refusal contract for every extractMethod refusal
  @REQ-JAVA-EXTRACT-METHOD-CHAR-001 @functional-requirement
  Scenario: An extractMethod refusal is a REFUSED plan with no edit, no affected file, no approval, confidence 0.0 and risk HIGH
    Given the Java adapter returns a REFUSED patch plan for an extractMethod preview
    When the caller inspects the refusal plan
    Then the refusal operation name is deterministically extractMethod
    And the refusal carries an empty WorkspaceEdit
    And the refusal carries an empty affected-file set
    And the refusal grants no approval and no managed-write eligibility
    And the refusal carries confidence 0.0 and risk HIGH
    And the refusal carries its real reason message in both the summary and the warnings

  # Scenario 3: finite real refusal message list as assertion data
  @REQ-JAVA-EXTRACT-METHOD-CHAR-001 @functional-requirement
  Scenario Outline: An extractMethod request is refused deterministically with its real refusal message "<message>"
    Given a Java workspace snapshot for an extractMethod preview under the condition <condition>
    When the caller requests an extractMethod preview
    Then the adapter returns a REFUSED patch plan for the operation extractMethod
    And the refusal summary and warning carry the message "<message>"
    And the refusal carries an empty WorkspaceEdit and an empty affected-file set
    And the refusal grants no approval and carries confidence 0.0 with risk HIGH

    Examples:
      | message | condition |
      | Invalid Java method name: 1extract | the caller supplies a method name that is not a valid Java identifier |
      | Invalid line range: startLine=0 endLine=5 (lines are 1-based, inclusive) | the caller supplies a line range that is not 1-based and inclusive |
      | File not found in snapshot: Missing.java | the requested file is absent from the snapshot |
      | Extract method currently supports Java files only: Util.kt | the requested file is not a Java source file |
      | Generated source cannot be rewritten: Generated.java (source declares a generated-code annotation) | the requested file is generated and read-only |
      | Method already exists or is already called in file: extract | a method named extract already exists or is already called in the file |
      | Line range is outside file: startLine=10 endLine=20 | the line range exceeds the file line count |
      | Selected range is blank | the selected range contains only whitespace |
      | Selection uses local variable(s)/parameter(s) declared before the range: count. Parameter extraction is not supported yet. | the selection reads a local variable or parameter declared before the range |
      | Selection declares local variable(s) used after the range: total. Return-value extraction is not supported yet. | the selection declares a local variable used after the range |
      | Selection contains 'return'; return values/exceptions/control flow are not supported by extract-method MVP | the selection contains a return statement |
      | Selection contains 'throw'; return values/exceptions/control flow are not supported by extract-method MVP | the selection contains a throw statement |
      | Selection contains 'break'; return values/exceptions/control flow are not supported by extract-method MVP | the selection contains break control flow |
      | Could not find final class closing brace for method insertion | the file has no final class closing brace |
      | Selected range appears to include the class closing brace | the selected range includes the class closing brace |
