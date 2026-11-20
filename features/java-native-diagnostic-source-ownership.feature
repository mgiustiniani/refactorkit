@operational-requirement @partial @REQ-JAVA-PROJECT-CONTEXT-001
Feature: Native compiler diagnostic source ownership
  An error explicitly captured for its main source set must not be published
  again when the test compiler implicitly loads that same source dependency.
  Independent errors with equal code and message remain separate, with their
  original explicit-source ranges. Main sources cannot see test-only types.
  Native SDK25 and SDK26 retain their release and source/target boundaries.

  @REQ-JAVA-PROJECT-CONTEXT-001#native-compiler-diagnostics-retain-explicit-source-set-ownership
  Scenario Outline: Native compiler diagnostics retain explicit source set ownership
    Given native Java diagnostic ownership uses SDK <sdk> with "<profile>"
    When main and test compiler groups report against the same immutable editor sources
    Then only explicitly owned compiler errors are published with their original ranges
    And source ownership qualification keeps independent errors and preserves captured inputs

    Examples:
      | sdk | profile         |
      | 25  | release         |
      | 26  | release         |
      | 25  | source-target   |
      | 26  | source-target   |
      | 25  | repeated errors |
      | 26  | repeated errors |
