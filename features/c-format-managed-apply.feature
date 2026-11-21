# C07/C16 managed formatting covers captured compiler diagnostics and owned header order.
# Integrated architecture authority: docs/arc42/appendix-requirements.adoc
# Slice limits and evidence: docs/requirements/c-formatting-source.md

@REQ-C-FORMAT-SOURCE-001 @functional-requirement @partial
Feature: Managed C formatting Apply uses captured compiler diagnostics

  @REQ-C-FORMAT-SOURCE-001#captured-c-compiler-diagnostics-authorize-format-apply-and-exact-rollback
  Scenario: Captured C compiler diagnostics authorize format apply and exact rollback
    Given a scanned C formatting workspace with "captured C17 compilation diagnostics" style evidence
    When the managed C format preview is applied with captured C diagnostics
    Then formatting apply and rollback restore exact source bytes while preserving project inputs

  @REQ-C-FORMAT-SOURCE-001#captured-ordered-local-headers-authorize-format-apply
  Scenario Outline: Captured ordered local headers authorize format apply
    Given a managed C formatting fixture with "<configuration>"
    When its captured C formatting plan is previewed and applied
    Then the ordered-header formatting outcome is "<outcome>" with exact project images

    Examples:
      | configuration             | outcome                 |
      | C11 ordered local headers | applied and rolled back |
      | C17 ordered local headers | applied and rolled back |
      | computed include          | refused before write    |
      | missing local header      | refused before write    |
      | changed captured header   | stale input refused     |
