@functional-requirement @REQ-JAVA-EXTRACT-VARIABLE-001 @implemented-and-validated
Feature: Extract a bound Java return subexpression into a local variable
  Requirement source: docs/integration-contracts.md, P1 "Add everyday extraction/encapsulation operations".
  Authority: this Story; architecture: docs/arc42/appendix-requirements.adoc.
  A selected, fully bound expression inside a direct method return may be extracted only when moving its evaluation before the return preserves evaluation order and control flow. The planner uses an explicit denotable type and refuses effectful prefixes, conditional evaluation, poly expressions, unresolved bindings, and other unsupported shapes.

  @REQ-JAVA-EXTRACT-VARIABLE-001#a-bound-return-subexpression-extracts-without-changing-evaluation-order
  Scenario: A bound return subexpression extracts without changing evaluation order
    Given a Maven Java extraction fixture with a JDT-bound integer return and explicit SDK context
    When I preview apply and roll back extractVariable for a bound return subexpression with safety refusals through daemon and MCP
    Then the managed extractVariable plan inserts a typed local before return and refuses hazardous selections while rollback restores exact source

  @REQ-JAVA-EXTRACT-VARIABLE-001#lsp-previews-extraction-against-the-current-dirty-buffer
  Scenario: LSP previews extraction against the current dirty buffer
    Given a Java Maven LSP fixture with a dirty buffer before a direct return expression
    When the LSP previews extractVariable "answer" for expression "6 * 7" in that buffer
    Then the client receives one versioned extractVariable edit for the current buffer without writing workspace files
