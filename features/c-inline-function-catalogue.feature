# language: en
@functional-requirement @partial
Feature: C inline-function MCP catalogue boundary
  Authority: docs/arc42/appendix-requirements.adoc (C12/C16).
  The MCP catalogue describes only the compiler-bound inline cell admitted by
  the C facade. This catalogue check does not qualify every managed surface.

  @REQ-C16-INLINE-CATALOGUE-001
  @REQ-C16-INLINE-CATALOGUE-001#c-mcp-catalogue-describes-the-compiler-bound-inline-cell
  Scenario: C MCP catalogue describes the compiler-bound inline cell
    Given a C MCP refactoring catalogue session
    When the C inline-function catalogue is requested through MCP
    Then the C function-inline entry declares its captured compiler boundary
    And the C function-inline entry declares its inputs without external-linkage claims
