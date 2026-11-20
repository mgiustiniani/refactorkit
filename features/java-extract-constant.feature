# language: en
# Requirement source: docs/integration-contracts.md, P1 "Add everyday extraction/encapsulation operations".
# Architecture authority: docs/arc42/appendix-requirements.adoc.
@functional-requirement @partial @REQ-JAVA-EXTRACT-CONSTANT-001
Feature: Extract a bound Java compile-time constant
  The managed row accepts one complete primitive-int compile-time constant expression
  inside a method of a top-level ordinary class. Its separate LSP row is preview-only
  and uses the same bounded planner against the current dirty Java buffer.

  @REQ-JAVA-EXTRACT-CONSTANT-001#extract-a-compile-time-integer-expression-through-managed-java-surfaces
  Scenario: Extract a compile-time integer expression through managed Java surfaces
    Given an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context
    When I preview apply and roll back extractConstant for a bound integer constant expression through daemon and MCP
    Then extractConstant is advertised as stable managed Java mutation and stages a bound static final constant
    And nonconstant expressions refuse before managed writes while both transactions restore exact source bytes

  @REQ-JAVA-EXTRACT-CONSTANT-001#lsp-previews-extraction-against-the-current-dirty-buffer
  Scenario: LSP previews extraction against the current dirty buffer
    Given a Java Maven LSP fixture with a dirty buffer before a direct return expression
    When the LSP previews extractConstant "answer" for expression "6 * 7 + 0" in that buffer
    Then the client receives one versioned extractConstant edit for the current buffer without writing workspace files
