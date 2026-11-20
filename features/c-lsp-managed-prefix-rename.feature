# Requirement source: docs/releases/v0.8.0-plan.md#C09 renameCSymbolPrefix.
# Architecture authority: docs/arc42/appendix-requirements.adoc#c-symbol-prefix-rename.
@functional-requirement @partial
Feature: Managed LSP C API symbol-prefix rename
  C LSP prefix rename keeps the compiler-bound family under a managed
  preview/apply/rollback transaction and refuses unresolved or unsafe inputs.

  @REQ-C-SYMBOL-PREFIX-LSP-001#c-api-prefix-rename-completes-its-managed-lsp-transaction-or-returns-a-compiler-refusal
  Scenario Outline: C API prefix rename completes its managed LSP transaction or returns a compiler refusal
    Given an isolated C LSP workspace with an explicitly captured Clang toolchain
    When the LSP client handles C prefix rename case "<case>"
    Then the LSP returns the C outcome "<outcome>"
    And the original project files and transaction state match "<outcome>"

    Examples:
      | case                       | outcome                  |
      | compiler-bound API family  | applied and rolled back  |
      | unresolved API symbol      | compiler refusal         |
      | symlinked include escape  | compiler refusal         |

  @REQ-C-SYMBOL-PREFIX-LSP-001#multiple-api-mappings-retain-one-managed-transaction-and-refuse-collisions
  Scenario Outline: Multiple API mappings retain one managed transaction and refuse collisions
    Given an isolated C LSP workspace with an explicitly captured Clang toolchain
    When the LSP client handles C prefix rename case "<case>"
    Then the LSP returns the C outcome "<outcome>"
    And the original project files and transaction state match "<outcome>"

    Examples:
      | case                               | outcome                 |
      | matrix: two C11 API symbols         | applied and rolled back |
      | matrix: two C17 API symbols         | applied and rolled back |
      | matrix: duplicate destination       | compiler refusal        |
      | matrix: overlapping source target  | compiler refusal        |
      | matrix: occupied destination       | compiler refusal        |
      | matrix: unavailable second symbol  | compiler refusal        |
