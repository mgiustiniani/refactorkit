# language: en
@functional-requirement @partial
Feature: Failed C formatting never authorizes a patch
  C formatting is a managed operation. Output from an unsuccessful formatter is
  not a proposed edit, even if the process emitted some apparently valid text.
  This Story covers the fail-closed process outcome only; exact style and source
  provenance, range preservation and C17 release qualification are separate work.
  Authority: docs/releases/v0.8.0-plan.md (C07); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-FORMAT-FAIL-CLOSED-001
  Scenario Outline: A failed formatter producing <stdout_kind> is refused without an edit
    Given an isolated C formatting workspace and a failing formatter producing "<stdout_kind>"
    When the managed C format preview is requested
    Then the preview is refused for the formatter failure with no edit
    And the C workspace bytes and transaction journal remain unchanged

    Examples:
      | stdout_kind |
      | empty       |
      | partial     |
