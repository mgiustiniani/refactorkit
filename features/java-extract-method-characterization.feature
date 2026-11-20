# language: en
@partial
Ability: Preview conservative Java Extract Method and safely extract a bounded JDT statement range
  As a RefactorKit caller on a Java workspace
  I need review-only compatibility previews for simple statements and binding-aware plans for
    contiguous complete statements with resolved inputs, one live-out result, and staged checks
  So that supported extractions remain previewable and auditable and unsupported shapes fail closed

  This executable backlog covers row C-EXTRACT of the approved finite J1 Java catalogue
  (REQ-JAVA-J1-CATALOGUE-APPROVED-001): the compatibility operation retains its existing
  straight-line, complete-line, no-argument private void preview contract; lexical evidence does
  not grant managed Apply. A separate JDT-bound shape extracts a contiguous range of complete
  direct statements, captures resolved enclosing-method parameters and preceding direct local
  reads, returns exactly one local used after the range, propagates resolved checked exceptions,
  and validates staged bindings and Java diagnostics before daemon/MCP Apply and rollback. Both use a Java source file and a 1-based
  inclusive line range. Ambiguous bindings, unsupported live-outs, and escaping control flow fail
  closed.

  Characterization RED-deferral (truthful, anti-fake): production already implements the
  planner JavaExtractMethodPlanner in the Java adapter, so this feature begins GREEN and
  asserts the real behavior rather than inventing it. The planner emits refusals as real
  MESSAGE strings through a refused snapshot plan, not as typed refusal codes; there is no
  refusalCode field. Every refusal message asserted below is the exact message string the
  planner emits, and no invented extractMethod.xxx typed code is introduced. The RED-deferral
  is pre-approved in the J1 catalogue for existing production. The managed binding-aware
  scenario validates multi-statement selection with a conditional branch, two resolved parameter
  inputs plus one preceding direct local input, one live-out result, checked-exception propagation,
  staged bindings and diagnostics, daemon/MCP Apply and rollback, plus non-applicability of lexical
  fallback and refusal of writes to prior locals. The feature remains partial because additional
  local inputs/outputs, escaping control flow, and native IDE Apply are outside this bounded
  qualification.
  Each refusal is a REFUSED patch plan carrying its real message in the summary and
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
      | Selection contains 'return'; return values/exceptions/control flow are not supported by extract-method MVP | the selection contains a return statement |
      | Selection contains 'throw'; return values/exceptions/control flow are not supported by extract-method MVP | the selection contains a throw statement |
      | Selection contains 'break'; return values/exceptions/control flow are not supported by extract-method MVP | the selection contains break control flow |
      | Selection contains 'continue'; return values/exceptions/control flow are not supported by extract-method MVP | the selection contains a continue statement |
      | Selection contains 'yield'; return values/exceptions/control flow are not supported by extract-method MVP | the selection contains a yield statement |
      | Selection appears to contain a type declaration | the selection contains a class, interface, enum, or record declaration |
      | Binding-aware selection must contain complete contiguous statements directly inside one resolved method body | the selection contains a method or control block declaration |
      | Selection has unbalanced braces | the selection has unmatched opening or closing braces |
      | Could not find final class closing brace for method insertion | the file has no final class closing brace |
      | Selected range appears to include the class closing brace | the selected range includes the class closing brace |

  # The former single-live-out refusal now has explicit positive coverage; no original condition is dropped.
  @REQ-JAVA-EXTRACT-METHOD-CHAR-001
  @REQ-JAVA-EXTRACT-METHOD-CHAR-001#a-single-live-out-local-is-extracted-with-an-equivalent-returned-result
  Scenario: A single live-out local is extracted with an equivalent returned result
    Given a Java workspace snapshot for an extractMethod preview under the condition the selection declares a local variable used after the range
    When the caller requests an extractMethod preview
    Then the adapter returns a PREVIEW patch plan for the operation extractMethod
    And the single live-out preview has the exact returned-local image and preserves compiled output

  # Scenario 4: binding-aware multi-statement data flow with a checked exception, qualified on managed surfaces
  @REQ-JAVA-EXTRACT-METHOD-CHAR-001 @REQ-JAVA-EXTRACT-METHOD-CHAR-001#single-output-propagates-a-checked-exception-through-managed-java-surfaces @functional-requirement
  Scenario: Single output propagates a checked exception through managed Java surfaces
    Given a Java workspace snapshot for an extractMethod preview under the condition the selection spans multiple statements, reads method parameters and a preceding direct local, updates one local through a conditional branch and invokes a checked-exception method
    When the caller requests a binding-aware extractMethod preview for the fixture's checked-exception multi-statement selection as "readContents" through daemon and MCP
    Then the adapter returns a PREVIEW patch plan for the operation extractMethod
    And the preview passes the input parameters and returns the selected local from the multi-statement control-flow block with its checked exception
    And the staged before and after Java fixtures compile and preserve the method result
    And extractMethod is advertised as stable managed mutation for binding-backed plans
    And daemon and MCP apply and roll back the plan with exact source restoration while line-based fallback remains non-applicable
    And the managed planner refuses a selected write to a preceding local without edits
