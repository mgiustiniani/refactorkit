@functional-requirement @REQ-JAVA-RETAINED-DIFF-001
Feature: Retained Java plan diff
  Authority: this Story; architecture: docs/arc42/appendix-requirements.adoc.
  @REQ-JAVA-RETAINED-DIFF-001#java-preview-renders-the-retained-plan-and-declares-bounded-truncation
  Scenario Outline: Java preview renders the retained plan and declares bounded truncation
    Given a saved Java diff fixture with "<extent>" changes and explicit SDK authority
    When an SDK-bound "<operation>" preview renders its retained plan
    Then the textual diff is "<completeness>" and represents that plan's edits
    And preview preserves source bytes and retained apply uses the same edits
    Examples:
      | extent | operation       | completeness |
      | small  | organizeImports | complete     |
      | small  | renameClass     | complete     |
      | small  | formatFile      | complete     |
      | large  | renameClass     | truncated    |
