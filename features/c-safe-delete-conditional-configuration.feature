# language: en
@functional-requirement
Feature: C safe delete refuses a single conditional-compilation view
  A semantic reference query under one active preprocessor branch cannot prove
  the complete reference set for a source file with conditional branches.
  Authority: docs/releases/v0.8.0-plan.md (C13); integrated status: docs/arc42/appendix-requirements.adoc.

  @implemented-and-validated @REQ-C-SAFE-DELETE-CONFIG-001#safe-delete-refuses-a-symbol-in-a-conditional-compilation-context
  Scenario Outline: Safe delete refuses a symbol in a conditional compilation context
    Given an isolated C source with a "<directive>" branch containing an alternate reference
    When safe delete previews an apparently unused private C function
    Then safe delete refuses because the conditional compilation context is incomplete
    And the source bytes and transaction journal remain unchanged after the refused delete

    Examples:
      | directive   |
      | if          |
      | ifdef       |
      | digraph-if  |
      | trigraph-if |
      | trigraph-spliced-if |
