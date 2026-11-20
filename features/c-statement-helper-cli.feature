# language: en
# Authority: bounded C11/C16 CLI lifecycle; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Extract a selected C statement helper from the CLI
  The statement extraction mode selects a function and UTF-8 byte extent and
  names a private helper. Preview is read-only. Explicit apply retains native
  authority and rollback restores source. Missing selection, initializer effects
  and mixed expression/statement options refuse without a transaction.

  @REQ-C-STATEMENT-CLI-001 @c-statement-helper-cli
  Scenario Outline: C statement helper CLI delegates selection preview apply rollback and explicit refusals
    Given a captured C statement helper CLI invocation has "<case>"
    When the statement helper CLI command is executed
    Then the statement helper CLI reports "<outcome>"
    And the statement helper CLI preserves saved configuration and releases compiler material

    Examples:
      | case                         | outcome                  |
      | C17 preview                  | preview without writes   |
      | C11 Unicode CRLF preview     | preview without writes   |
      | approved apply               | applied and rolled back  |
      | selection omitted            | missing argument refused |
      | initializer changes state    | effects refused          |
      | mixed expression and helper  | mixed options refused    |
