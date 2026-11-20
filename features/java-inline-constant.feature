# language: en
# Requirement source: docs/integration-contracts.md, P1 "Add everyday extraction/encapsulation operations".
# Architecture authority: docs/arc42/appendix-requirements.adoc.
@functional-requirement @partial @REQ-JAVA-INLINE-CONSTANT-001
Feature: Inline a bounded Java compile-time constant
  This first row supports a private static final primitive-int literal with bound reads in its declaring compilation unit.
  It does not claim public API, external consumer, dirty-buffer, or general constant-expression coverage.

  @REQ-JAVA-INLINE-CONSTANT-001#inline-a-private-static-final-integer-literal-through-managed-java-surfaces
  Scenario: Inline a private static final integer literal through managed Java surfaces
    Given an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context
    When I preview apply and roll back inlineConstant for a bound private static final integer literal through daemon and MCP
    Then inlineConstant is advertised as stable managed Java mutation and replaces only its bound literal reads
    And non-literal or mutable fields refuse before managed writes while both surfaces restore exact source bytes
