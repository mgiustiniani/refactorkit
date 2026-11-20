# language: en
@functional-requirement @partial
Feature: C extract-expression cannot change arithmetic type or escape local scope
  Assignment-target type is not the selected expression type. A temporary also
  must be inserted inside the enclosing function, not above its declaration.
  These bounded cases do not establish general C11/C17 AST/effect equivalence.
  Authority: docs/releases/v0.8.0-plan.md (C11); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-EXTRACT-TYPE-PLACEMENT-001
  @REQ-C-EXTRACT-TYPE-PLACEMENT-001#extracting-the-counterexample-expression-preserves-its-semantics-or-refuses
  Scenario Outline: Extracting the <counterexample> expression preserves its semantics or refuses
    Given an isolated C extract-expression source with "<counterexample>"
    When the selected C expression is previewed as a temporary
    Then the <counterexample> extract preview is "<disposition>" without an out-of-scope temporary
    And the extract source bytes and transaction journal remain unchanged

    Examples:
      | counterexample | disposition       |
      | conversion     | refused           |
      | local-scope    | in-scope-preview  |
