# language: en
@functional-requirement @REQ-LSP-INITIALIZE-WORKSPACE-001
Feature: Resolve the workspace during LSP initialization
  The LSP 3.17 initialize request can carry a null root URI.
  An explicit file root takes precedence over workspace folders.
  The ARC42 requirements appendix records integrated acceptance.

  @REQ-LSP-INITIALIZE-WORKSPACE-001#lsp-initialization-selects-a-file-workspace-or-starts-without-a-root
  Scenario Outline: LSP initialization selects a file workspace or starts without a root
    Given an LSP initialization fixture requests "<root>" root URI and "<folders>" workspace folders
    When the client initializes that LSP session and asks for the saved document symbols
    Then the LSP initialization exposes "<selected>" and preserves both saved sources

    Examples:
      | root    | folders | selected |
      | null    | absent  | neither  |
      | null    | present | folder   |
      | file    | present | explicit |
      | omitted | present | folder   |
