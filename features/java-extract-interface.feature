@functional-requirement
Feature: Bounded Java extract interface
  Requirement: REQ-JAVA-EXTRACT-INTERFACE-001, the IDE backlog for type extraction.
  ARC42 owns integrated status and release qualification: docs/arc42/appendix-requirements.adoc#java-extract-interface-bounded-operation.
  This first cell accepts one exact JDT-bound public instance method with primitive-int parameters and return type from a top-level class with no existing hierarchy; other signatures refuse.

  @implemented-and-validated @REQ-JAVA-EXTRACT-INTERFACE-001#extract-a-primitive-method-into-an-interface-and-rollback
  Scenario: Extract a primitive method into an interface and rollback
    Given an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context
    When I preview extractInterface for Calculator.calculate using its exact saved JDT type selection
    Then extractInterface is advertised as stable and the preview creates CalculatorOperations and makes Calculator implement the interface with clean diagnostics
    And a reference-typed method selection is refused before the workspace changes
    And applying and rolling back extractInterface restores the exact workspace and preserves selected type identity

  @partial @REQ-JAVA-EXTRACT-INTERFACE-001#lsp-previews-interface-extraction-from-the-current-dirty-buffer
  Scenario: LSP previews interface extraction from the current dirty buffer
    Given a Java Maven LSP fixture with a dirty buffer before a direct return expression
    When the LSP previews extractInterface for Calculator.calculate at the Calculator declaration
    Then the client receives a versioned create and implementation edit for the current dirty buffer without workspace writes
