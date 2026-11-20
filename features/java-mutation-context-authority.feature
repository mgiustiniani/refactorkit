@functional-requirement @REQ-JAVA-MUTATION-CONTEXT-001
Feature: Java mutation context authority
  A preview and its managed apply consume the same SDK and build context.
  Authority: this Story; architecture: docs/arc42/appendix-requirements.adoc.

  @REQ-JAVA-MUTATION-CONTEXT-001#java-previews-and-managed-apply-retain-sdk-authority-or-refuse-context-drift
  Scenario Outline: Java previews and managed apply retain SDK authority or refuse context drift
    Given a Java mutation fixture with an acquired explicit SDK context
    When a Java "<operation>" preview is followed by "<action>"
    Then the SDK-bound mutation reports "<outcome>"
    And context refusal leaves no source modification or transaction intent

    Examples:
      | operation       | action                | outcome                      |
      | organizeImports | matching apply        | applied                      |
      | renameClass     | matching apply        | applied                      |
      | organizeImports | other context apply   | java.context.contextStale    |
      | organizeImports | SDK drift apply       | java.context.contextStale    |
      | organizeImports | build drift apply     | java.context.snapshotStale   |
      | organizeImports | missing context apply | java.context.authorityRequired |

  @REQ-JAVA-MUTATION-CONTEXT-001#gradle-moveclass-preview-matches-the-synchronized-context-or-refuses-stale-context-authority
  Scenario Outline: Gradle moveClass preview matches the synchronized context or refuses stale context authority
    Given a synchronized Gradle Java project with a single class move target
    When a Gradle moveClass preview is requested with "<authority>" context authority
    Then the moveClass preview reports "<outcome>" and "<attestation>" synchronized authority

    Examples:
      | authority | outcome | attestation      |
      | current   | preview | exact            |
      | current-v3| preview | java-project-context-v3 |
      | stale     | refused | java.context.contextStale |
