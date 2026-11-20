# language: en
# Authority: bounded C11/C16 LSP integration; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Managed C statement helper operation through LSP executeCommand
  The LSP returns a retained compiler-proven plan, never a client-applied edit.
  Explicit Apply uses the C diagnostics gate and rollback restores exact source.
  Unsupported helper collisions refuse without a transaction.

  @REQ-C16-LSP-MANAGED-003#c-statement-helper-completes-its-managed-lsp-transaction-or-returns-a-compiler-refusal
  Scenario Outline: C statement helper completes its managed LSP transaction or returns a compiler refusal
    Given an isolated C LSP workspace with an explicitly captured Clang toolchain
    When the LSP client handles C statement helper case "<case>"
    Then the LSP returns the C outcome "<outcome>"
    And the original project files and transaction state match "<outcome>"

    Examples:
      | case                                    | outcome                      |
      | compiler-bound statement range         | applied and rolled back      |
      | helper collides with existing function | compiler refusal             |
