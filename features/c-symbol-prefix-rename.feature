# Requirement source: docs/releases/v0.8.0-plan.md#C09 renameCSymbolPrefix.
# Architecture authority: docs/arc42/appendix-requirements.adoc#c-symbol-prefix-rename.
@functional-requirement @non-functional-requirement @partial
Feature: Explicit C API symbol-family prefix rename
  A caller-selected mapping must use compiler binding identity for each symbol,
  update its complete captured declaration and consumer family, and preserve
  unrelated names that merely share the old textual prefix.

  @REQ-C-SYMBOL-PREFIX-RENAME-001#renames-a-cross-file-api-family-and-preserves-same-prefix-decoys
  Scenario: Renames a cross-file API family and preserves same-prefix decoys
    Given a C workspace contains a header declaration, definition, caller and lexical decoys
    When renamePrefix maps API symbol "calculate" to "compute" across library, daemon, and MCP
    Then all surfaces return "preview" with exact binding-matched edits or a typed refusal
    And preview leaves every project byte unchanged with no transaction

  @REQ-C-SYMBOL-PREFIX-MANAGED-001#multiple-api-mappings-complete-cli-daemon-and-mcp-transactions
  Scenario Outline: Multiple API mappings complete CLI daemon and MCP transactions
    Given the C multi-symbol surface "<surface>" captures "<standard>" API families
    When that surface requests both C API mappings and managed Apply
    Then both C API families share one approved transaction and exact post-images
    And rollback through that surface restores all C inputs and releases compiler material

    Examples:
      | surface | standard |
      | CLI     | c11      |
      | CLI     | c17      |
      | daemon  | c11      |
      | daemon  | c17      |
      | MCP     | c11      |
      | MCP     | c17      |

  @REQ-C-SYMBOL-PREFIX-MANAGED-001#unqualified-mixed-language-consumers-refuse-before-c-api-mutation
  Scenario Outline: Unqualified mixed language consumers refuse before C API mutation
    Given the C multi-symbol surface "<surface>" captures an unqualified "<consumer>" consumer
    When that surface requests both C API mappings and managed Apply
    Then the mixed C consumer is refused with "c.renamePrefix.unsupportedConsumer" before any transaction
    And all multi-symbol source inputs and compiler material remain unchanged

    Examples:
      | surface | consumer    |
      | CLI     | cpp         |
      | daemon  | cpp         |
      | MCP     | cpp         |
      | CLI     | objective-c |
      | daemon  | objective-c |
      | MCP     | objective-c |

  @REQ-C-SYMBOL-PREFIX-LIBRARY-001#direct-library-prefix-previews-retain-managed-authority-and-refuse-unqualified-inputs
  Scenario Outline: Direct library prefix previews retain managed authority and refuse unqualified inputs
    Given the C library prefix preview has "<condition>" inputs under "<standard>"
    When the direct library requests both mapped C API families
    Then the direct library prefix result is "<outcome>" with retained compiler authority
    And the direct library leaves inputs intact or restores its exact approved transaction

    Examples:
      | condition             | standard | outcome                                    |
      | complete              | c11      | managed preview                            |
      | complete              | c17      | managed preview                            |
      | cpp consumer          | c17      | c.renamePrefix.unsupportedConsumer          |
      | objective-c consumer  | c17      | c.renamePrefix.unsupportedConsumer          |
      | duplicate destination | c17      | c.renamePrefix.nonInvertibleMapping         |
      | absent database       | c11      | c.renamePrefix.compilationContextUnavailable |

  @REQ-C-SYMBOL-PREFIX-LIBRARY-001#retained-prefix-authority-refuses-late-consumers-and-recovers-committed-source
  Scenario Outline: Retained prefix authority refuses late consumers and recovers committed source
    Given a retained C API preview acquires "<authority>" authority before a "<consumer>" consumer appears
    When that retained authority attempts the C API transaction
    Then the retained C API authority reports "<outcome>" with exact transaction state
    And the original C sources and the concurrent foreign consumer are preserved

    Examples:
      | authority     | consumer    | outcome                       |
      | facade        | cpp         | refused before WAL            |
      | facade        | objective-c | refused before WAL            |
      | retained gate | cpp         | refused before WAL            |
      | retained gate | objective-c | refused before WAL            |
      | post-commit   | cpp         | automatic rollback and refusal |
      | post-commit   | objective-c | automatic rollback and refusal |
