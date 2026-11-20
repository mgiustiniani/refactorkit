# language: en
# Authority: bounded C10 CLI contract; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Insert a C integer parameter with an explicit caller expression from the CLI
  The add-parameter mode supplies the new name, ordinal and caller expression.
  Preview is read-only. Explicit apply retains native authority; rollback
  restores exact bytes. Missing arguments, effects and mixed modes refuse.

  @REQ-C-PARAMETER-INSERTION-CLI-001 @c-parameter-insertion-cli
  Scenario Outline: C parameter insertion CLI delegates explicit arguments preview apply rollback and refusals
    Given a captured C parameter insertion CLI invocation has "<case>"
    When the parameter insertion CLI command is executed
    Then the parameter insertion CLI reports "<outcome>"
    And the parameter insertion CLI preserves saved configuration and releases compiler material

    Examples:
      | case                      | outcome                  |
      | C17 preview               | preview without writes   |
      | C11 Unicode CRLF preview  | preview without writes   |
      | approved apply            | applied and rolled back  |
      | missing caller expression | missing argument refused |
      | observable argument       | effects refused          |
      | mixed insertion reorder   | mixed options refused    |
