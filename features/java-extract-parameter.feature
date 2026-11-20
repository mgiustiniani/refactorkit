# language: en
# Requirement source: docs/integration-contracts.md, P1 "Add everyday extraction/encapsulation operations".
# Architecture authority: docs/arc42/appendix-requirements.adoc#java-extractparameter-bounded-operation.
@functional-requirement @partial
Feature: Extract a bound Java return expression into a parameter
  This first cell supports one primitive-int addition expression, one private static method,
  and one bound same-file caller; other call graphs and expression shapes remain unavailable.

  @REQ-JAVA-EXTRACT-PARAMETER-001#one-bound-return-expression-is-promoted-to-a-parameter-through-managed-java-surfaces
  Scenario: One bound return expression is promoted to a parameter through managed Java surfaces
    Given an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context
    When I preview apply and roll back extractParameter for a bound integer return expression through daemon and MCP
    Then extractParameter is advertised as stable managed Java mutation and stages the derived call-site argument
    And unsupported selections refuse before managed writes while both transactions restore exact source bytes

  @partial @REQ-JAVA-EXTRACT-PARAMETER-001#lsp-previews-the-bound-parameter-extraction-from-the-current-dirty-buffer
  Scenario: LSP previews the bound parameter extraction from the current dirty buffer
    Given a Java Maven LSP fixture with a dirty buffer before a direct return expression
    When the LSP previews extractParameter "delta" for expression "base + 1" in that buffer
    Then the client receives a versioned client-managed parameter proposal without workspace writes
