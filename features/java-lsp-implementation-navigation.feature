# language: en
@functional-requirement @java @lsp @read-only
Feature: Resolve implementation locations through the Java Language Server Protocol
  The LSP implementation request uses RefactorKit's compiler-bound workspace
  navigation and returns ordinary source locations without editing the workspace.

  @REQ-JAVA-SEMANTIC-NAVIGATION-001#lsp-implementation-requests-resolve-dirty-source-types-and-method-overrides
  Scenario: LSP implementation requests resolve dirty source types and method overrides
    Given a Java LSP session has a saved handler interface and an abstract base
    When an unsaved concrete handler is opened and type and method implementations are requested
    Then implementation responses identify the dirty concrete type and method locations
    And the LSP advertises Java implementation lookup and saved workspace files remain unchanged
