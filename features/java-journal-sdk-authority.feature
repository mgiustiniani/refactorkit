@functional-requirement @REQ-JAVA-JOURNAL-AUTHORITY-001
Feature: Persisted SDK transaction authority
  Authority: this Story; architecture: docs/arc42/appendix-requirements.adoc.
  @REQ-JAVA-JOURNAL-AUTHORITY-001#sdk-transaction-authority-survives-daemon-restart-with-journal-integrity
  Scenario Outline: SDK transaction authority survives daemon restart with journal integrity
    Given a Java journal fixture with explicit SDK authority
    When an SDK rename is applied and its journal is "<journal>" after daemon restart
    Then the SDK authority journal is "<outcome>" with original context and platform evidence
    And journal inspection does not change applied source bytes
    Examples:
      | journal  | outcome   |
      | intact   | available |
      | tampered | corrupt   |
