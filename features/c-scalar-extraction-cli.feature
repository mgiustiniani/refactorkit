# language: en
# Authority: bounded C11/C16 CLI scalar lifecycle; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Compiler-bound scalar extraction through the C CLI
  Scalar extraction selects an eager integer initializer with zero-based UTF-16
  coordinates and preserves its conversion into the declared target type.
  Preview is read-only and explicit apply can be rolled back exactly.

  @REQ-C-SCALAR-CLI-001 @c-scalar-extraction-cli
  Scenario Outline: C scalar CLI retains native selection approval and rollback or refuses invalid inputs
    Given a captured C scalar CLI invocation has "<condition>"
    When the scalar extraction CLI command is executed
    Then the scalar extraction CLI reports "<outcome>"
    And the scalar extraction CLI preserves saved configuration and releases compiler material

    Examples:
      | condition                  | outcome                 |
      | C17 preview                | preview without writes  |
      | C11 Unicode CRLF preview   | preview without writes  |
      | approved apply             | applied and rolled back |
      | selection omitted          | missing range refused   |
      | initializer changes state  | effects refused         |
      | mixed expression and helper | mixed options refused  |
