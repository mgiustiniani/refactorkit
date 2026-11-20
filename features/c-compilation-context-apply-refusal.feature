# language: en
@functional-requirement @partial
Feature: C managed apply cannot invent a clean compiler context
  A captured compile command containing -DENABLED=1 is not silently replaced by
  empty flags. Without a retained exact per-TU context, managed apply must
  refuse before the transaction log instead of reporting a clean gate.
  Capturing/classifying database bytes alone never executes a command or grants
  compiler authority; separately qualified operations retain their own context.
  This fail-closed Story does not qualify C03/C05 positive compilation contexts.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-COMPILE-CONTEXT-FAIL-CLOSED-001#generic-c-database-capture-preserves-stale-and-ambiguous-input-evidence
  Scenario Outline: Generic C database capture preserves stale and ambiguous input evidence
    Given a generic C workspace database snapshot has "<condition>" evidence
    When the C adapter captures that existing database input
    Then database capture reports "<outcome>" without refreshing or hiding the original evidence
    And database capture changes no source configuration symlink or execution marker

    Examples:
      | condition      | outcome              |
      | current JSON   | C database captured  |
      | stale JSON     | generic retained     |
      | duplicate JSON | snapshot refused     |
      | missing JSON   | generic retained     |
      | symlink JSON   | generic retained     |
      | already typed  | C database retained  |

  @REQ-C-COMPILE-CONTEXT-FAIL-CLOSED-001#a-captured-conditional-error-configuration-cannot-be-applied-with-default-compiler-flags
  Scenario: A captured conditional-error configuration cannot be applied with default compiler flags
    The facade preview retains the captured ENABLED diagnostics. A structural
    caller proposal without that compiler authority cannot authorize a write.
    Given an isolated C project with a captured ENABLED compilation configuration
    When a managed C include preview is approved for apply
    Then the caller-proposed apply is refused because the captured compiler authority was not retained
    And source bytes remain unchanged with no transaction journal

  @REQ-C-COMPILE-CONTEXT-FAIL-CLOSED-001#source-only-project-refuses-host-default-compiler-context
  Scenario: Source-only project refuses host default compiler context
    Given an isolated source-only C project with sortable includes and no declared compiler context
    When a managed C include preview is approved for apply
    Then the apply is refused because exact compilation context is unavailable
    And source bytes remain unchanged with no transaction journal

  @REQ-C-COMPILE-CONTEXT-FAIL-CLOSED-001#missing-caller-approval-refuses-before-diagnostics
  Scenario: Missing caller approval refuses before diagnostics
    Given an isolated source-only C project with sortable includes and no declared compiler context
    When the C include preview is applied without caller approval
    Then the apply refuses for missing approval before diagnostics are evaluated
    And source bytes remain unchanged with no transaction journal
