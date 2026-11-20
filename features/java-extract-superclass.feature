@functional-requirement
Feature: Bounded Java extract superclass
  Requirement: REQ-JAVA-EXTRACT-SUPERCLASS-001, the IDE backlog for type extraction.
  ARC42 owns integrated status and release qualification: docs/arc42/appendix-requirements.adoc#java-extract-superclass-bounded-operation.
  This first cell moves one exact JDT-bound public int method whose sole return expression uses only int parameters and numeric literals into a same-package superclass; other shapes refuse.

  @implemented-and-validated @REQ-JAVA-EXTRACT-SUPERCLASS-001#extract-a-pure-primitive-method-into-a-superclass-and-rollback
  Scenario: Extract a pure primitive method into a superclass and rollback
    Given an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context
    When I preview extractSuperclass for Calculator.calculate using its exact saved JDT type selection
    Then extractSuperclass is advertised as stable and the preview creates CalculatorBase and makes Calculator extend it with clean diagnostics
    And a reference-typed extractSuperclass method selection is refused before the workspace changes
    And applying and rolling back extractSuperclass restores the exact workspace and preserves selected type identity

  @implemented-and-validated @REQ-JAVA-EXTRACT-SUPERCLASS-001#lsp-previews-superclass-extraction-from-the-current-dirty-buffer
  Scenario: LSP previews superclass extraction from the current dirty buffer
    Given a Java Maven LSP fixture with a dirty buffer before a direct return expression
    When the LSP previews extractSuperclass as "CalculatorBase" for "compute" at the Calculator declaration
    Then the client receives a versioned superclass create and implementation edit without workspace writes
