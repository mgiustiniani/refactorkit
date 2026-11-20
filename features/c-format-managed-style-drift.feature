# Bounded C07 stale-style refusal; this Story grants no write authority.
# Integrated architecture authority: docs/arc42/appendix-requirements.adoc
# Requirement and slice boundary: docs/requirements/c-formatting-source.md

@REQ-C-FORMAT-SOURCE-001 @functional-requirement @partial
Feature: Managed C formatting refuses when captured style drifts

  @REQ-C-FORMAT-SOURCE-001#changed-captured-c-style-refuses-managed-apply-after-preview
  Scenario: Changed captured C style refuses managed Apply after preview
    Given a scanned C formatting workspace with "captured C17 compilation diagnostics" style evidence
    When the captured C formatter style changes after a preview before managed Apply
    Then the managed C format Apply refuses without changing source or creating a journal
