# language: en
# Authority: bounded C14/C16 managed LSP integration; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Managed C include ordering through LSP executeCommand
  An explicitly captured, compiler-checked include-order plan is retained for
  managed Apply, and rollback restores the exact project snapshot.

  @REQ-C16-LSP-ORGANIZE-INCLUDES-001#c-organize-includes-applies-through-managed-lsp-and-rolls-back-exactly
  Scenario: C organize-includes applies through managed LSP and rolls back exactly
    Given an isolated C LSP workspace with an explicitly captured Clang toolchain
    When the LSP client applies C include ordering for "independent prototypes across two captured C17 translation units"
    Then the LSP returns the C outcome "applied and rolled back"
    And the original project files and transaction state match "applied and rolled back"

  @REQ-C16-LSP-ORGANIZE-INCLUDES-001#lsp-refuses-typedef-dependent-header-ordering-with-a-typed-refusal
  Scenario: LSP refuses typedef-dependent header ordering with a typed refusal
    Given an isolated C LSP workspace with an explicitly captured Clang toolchain
    When the LSP client requests C include ordering for "typedef-dependent local headers"
    Then the LSP returns the C outcome "compiler refusal"
    And the original project files and transaction state match "compiler refusal"
