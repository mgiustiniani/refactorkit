@functional-requirement @REQ-JAVA-QUERY-CONTEXT-001
Feature: Java query context authority
  The SDK selected for project context must also govern daily Java intelligence.
  Authority: this executable requirement; architecture: docs/arc42/appendix-requirements.adoc.

  @REQ-JAVA-QUERY-CONTEXT-001#java-queries-consume-the-selected-sdk-context-or-refuse-stale-authority
  Scenario Outline: Java queries consume the selected SDK context or refuse stale authority
    Given a saved Java query fixture with an explicitly acquired SDK context
    When the IDE requests Java "<query>" with "<authority>" context authority
    Then the Java query reports "<outcome>" using the acquired SDK evidence
    And the Java query preserves all saved source and build bytes

    Examples:
      | query           | authority | outcome                    |
      | hover           | current   | ready                      |
      | definition      | current   | ready                      |
      | references      | current   | ready                      |
      | documentSymbols | current   | ready                      |
      | workspaceSymbols| other SDK | java.context.contextStale  |
      | hover           | build drift| java.context.snapshotStale |

  @REQ-JAVA-QUERY-CONTEXT-001#saved-java-query-accepts-provider-qualified-v5-project-context
  Scenario: Saved Java query accepts provider-qualified v5 project context
    Given a saved Java query fixture with an explicitly acquired v5 SDK context
    When the IDE requests Java "hover" with "current" context authority
    Then the Java query reports "ready" using the acquired SDK evidence
    And the Java query preserves all saved source and build bytes

  @REQ-JAVA-QUERY-CONTEXT-001#java-query-sequence-keeps-its-daemon-session-after-reactor-context-acquisition
  Scenario: Java query sequence keeps its daemon session after reactor context acquisition
    Given a two-module Maven Java reactor has acquired project context over daemon stdio
    When the IDE requests definition and implementations through the same daemon session
    Then both Java queries reply with the acquired context and expected source declarations
    And the Java query preserves all saved source and build bytes

  @REQ-JAVA-QUERY-CONTEXT-001#java-query-sequence-keeps-its-daemon-session-in-packaged-runtime-after-reactor-context-acquisition
  Scenario: Java query sequence keeps its daemon session in packaged runtime after reactor context acquisition
    Given a two-module Maven Java reactor has acquired project context over packaged daemon stdio
    When the IDE requests definition and implementations through the same daemon session
    Then both Java queries reply with the acquired context and expected source declarations
    And the Java query preserves all saved source and build bytes
