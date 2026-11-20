@functional-requirement @REQ-JAVA-JOURNAL-COMPATIBILITY-001
Feature: Additive SDK journal format
  Authority: this Story; architecture: docs/arc42/appendix-requirements.adoc.
  @REQ-JAVA-JOURNAL-COMPATIBILITY-001#ordinary-transactions-retain-v8-while-sdk-transactions-persist-v9-authority
  Scenario Outline: Ordinary transactions retain v8 while SDK transactions persist v9 authority
    Given a Java format-compatibility workspace using "<authority>" mutation authority
    When a retained Java rename is applied and its journal is reopened
    Then the reopened transaction has schema "<version>" and "<evidence>" authority attributes
    And format-compatible rollback restores the original snapshot
    Examples:
      | authority | version | evidence |
      | ordinary  | 8       | absent   |
      | SDK       | 9       | present  |
