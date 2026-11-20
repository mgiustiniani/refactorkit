@functional-requirement @REQ-JAVA-ROLLBACK-BASELINE-001
Feature: SDK rollback restores the original diagnostic baseline
  Authority: this Story; architecture: docs/arc42/appendix-requirements.adoc.
  @REQ-JAVA-ROLLBACK-BASELINE-001#protected-rollback-restores-exact-original-errors-without-accepting-authority-drift
  Scenario Outline: Protected rollback restores exact original errors without accepting authority drift
    Given an SDK "<sdk>" rollback project with original "<diagnostic>" errors
    When its retained rename is applied and restored after "<event>"
    Then original-baseline rollback reports "<outcome>"
    And rollback preserves the exact source and diagnostic images admitted by the journal
    Examples:
      | sdk | diagnostic                    | event     | outcome                   |
      | 21  | duplicate secondary declaration | none    | rolledBack                |
      | 25  | duplicate secondary declaration | none    | rolledBack                |
      | 21  | duplicate secondary declaration | other context | java.context.contextStale |
      | 21  | duplicate secondary declaration | SDK drift | java.context.contextStale |
