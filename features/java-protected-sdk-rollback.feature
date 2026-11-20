@functional-requirement @REQ-JAVA-SDK-ROLLBACK-001
Feature: Protected Java SDK rollback
  Authority: this Story; architecture: docs/arc42/appendix-requirements.adoc.
  @REQ-JAVA-SDK-ROLLBACK-001#sdk-rollback-restores-the-exact-transaction-or-refuses-authority-drift
  Scenario Outline: SDK rollback restores the exact transaction or refuses authority drift
    Given a committed Java SDK "<sdk>" rename transaction with durable authority
    When protected SDK rollback is requested after "<event>"
    Then SDK rollback reports "<outcome>" with exact snapshot authority
    And restored or refused rollback preserves the expected source and journal images
    Examples:
      | sdk | event           | outcome                       |
      | 21  | daemon restart  | rolledBack                    |
      | 25  | daemon restart  | rolledBack                    |
      | 21  | missing context | java.context.authorityRequired |
      | 21  | other context   | java.context.contextStale      |
      | 21  | SDK drift       | java.context.contextStale      |
      | 21  | build drift     | java.context.snapshotStale     |
