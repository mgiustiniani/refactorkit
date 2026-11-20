# Requirement source: docs/releases/v0.8.0-plan.md#C09-renameCSymbolPrefix.
# Architecture authority: docs/arc42/appendix-requirements.adoc#c-symbol-prefix-rename-cli.
@functional-requirement @partial
Feature: C CLI prefix rename mapping
  The CLI must pass an explicit old-to-new symbol mapping to the existing
  compiler-bound C prefix rename operation.

  @REQ-C-SYMBOL-PREFIX-CLI-001#cli-previews-a-mapped-cross-file-api-family
  Scenario: CLI previews a mapped cross-file API family
    Given a C workspace contains a header declaration, definition, caller and lexical decoys
    When the C CLI previews rename-prefix mapping "calculate" to "compute"
    Then the C CLI returns a preview for exactly the binding-matched API family
    And preview leaves every project byte unchanged with no transaction

  @REQ-C-SYMBOL-PREFIX-CLI-001#cli-previews-multiple-explicit-cross-file-api-mappings
  Scenario: CLI previews multiple explicit cross-file API mappings
    Given a C workspace contains two header API declarations, definitions, callers and lexical decoys
    When the C CLI previews rename-prefix mappings "calculate=compute,load_item=read_item"
    Then the C CLI returns both exact binding-matched API families
    And preview leaves every project byte unchanged with no transaction
