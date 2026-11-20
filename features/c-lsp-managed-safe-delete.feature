# language: en
# Authority: C13/C16 LSP-managed safe-delete boundary; ARC42 owns integrated status.
@functional-requirement @implemented-and-validated
Feature: Managed C safe delete through LSP executeCommand
  The LSP retains a compiler-proven safe-delete plan for explicit managed Apply;
  rollback restores the exact source image, while semantic references refuse.

  @REQ-C16-LSP-SAFE-DELETE-001#c-safe-delete-completes-a-managed-lsp-transaction-or-returns-a-compiler-refusal
  Scenario Outline: C safe delete completes a managed LSP transaction or returns a compiler refusal
    Given an isolated C LSP workspace with an explicitly captured Clang toolchain
    When the LSP client handles C safe-delete case "<case>"
    Then the LSP returns the C outcome "<outcome>"
    And the original project files and transaction state match "<outcome>"

    Examples:
      | case                                 | outcome                 |
      | isolated unused private function    | applied and rolled back |
      | unused file-static object           | applied and rolled back |
      | referenced private function         | compiler refusal        |
      | public function without external-consumer evidence | compiler refusal |
