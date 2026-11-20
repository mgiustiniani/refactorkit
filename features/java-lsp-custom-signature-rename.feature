@functional-requirement @java @lsp @partial
Feature: RefactorKit custom LSP parameter rename
  Integrated boundary: docs/integration-contracts.md and
  docs/arc42/appendix-requirements.adoc.
  This `workspace/executeCommand` route is distinct from standard
  `textDocument/rename` and from daemon/MCP managed change-signature authority.

  @REQ-JAVA-LSP-SIGNATURE-RENAME-001#open-dirty-parameter-rename-is-a-versioned-client-managed-lsp-edit
  Scenario: Open dirty parameter rename is a versioned client-managed LSP edit
    Given a Java LSP workspace has a saved method and an open dirty buffer at version 7
    When the client requests method code actions and sends the custom rename command from "left" to "amount"
    Then the client is offered no incomplete signature code action and the custom command returns a version-7 client-managed edit without managed Apply or rollback authority
    And the proposal renames only the bound parameter declaration and reads while saved bytes remain unchanged
