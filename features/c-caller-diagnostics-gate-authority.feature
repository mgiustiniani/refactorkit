# language: en
# Authority: one C managed-Apply boundary cell; ARC42 owns integrated status.
@functional-requirement @partial
Feature: C facade does not trust caller-created diagnostics gates
  A caller-supplied clean diagnostics function is not proof that the selected C
  operation has an exact, snapshot-bound compiler context. The facade must use
  the gate selected for the operation and snapshot and fail closed when that
  evidence is unavailable.
  Authority: docs/releases/v0.8.0-plan.md (C05/C16); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-COMPILE-CONTEXT-FAIL-CLOSED-001#caller-clean-diagnostics-gate-cannot-authorize-source-only-managed-apply
  Scenario: Caller clean diagnostics gate cannot authorize source-only managed apply
    Given an isolated source-only C project with sortable includes and no declared compiler context
    When a managed C include preview is applied with a caller-supplied clean diagnostics gate
    Then the apply is refused because exact compilation context is unavailable
    And the caller-supplied diagnostics gate is not evaluated
    And source bytes remain unchanged with no transaction journal
