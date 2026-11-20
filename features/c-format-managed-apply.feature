# Bounded C07/C16 acceptance: one C17 translation unit, no preprocessor inputs.
# Integrated architecture authority: docs/arc42/appendix-requirements.adoc
# Slice limits and evidence: docs/requirements/c-formatting-source.md

@REQ-C-FORMAT-SOURCE-001 @functional-requirement @partial
Feature: Managed C formatting Apply uses captured compiler diagnostics

  @REQ-C-FORMAT-SOURCE-001#captured-c-compiler-diagnostics-authorize-format-apply-and-exact-rollback
  Scenario: Captured C compiler diagnostics authorize format apply and exact rollback
    Given a scanned C formatting workspace with "captured C17 compilation diagnostics" style evidence
    When the managed C format preview is applied with captured C diagnostics
    Then formatting apply and rollback restore exact source bytes while preserving project inputs
