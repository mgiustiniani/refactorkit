# language: en
# Authority: bounded C10 CLI contract; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Request a compiler-bound C parameter reorder from the CLI
  The explicit reorder-parameters mode uses a complete original-index mapping.
  Preview leaves saved bytes intact. Apply delegates the same compiler-bound
  protocol lifecycle, and rollback restores the source. Malformed mappings and
  mixed rename/reorder options cannot become writes.

  @REQ-C-PARAMETER-REORDER-CLI-001 @c-parameter-reordering-cli
  Scenario Outline: C parameter reorder CLI delegates preview apply rollback and argument refusals
    Given a captured C parameter reorder CLI invocation has "<case>"
    When the parameter reorder CLI command is executed
    Then the parameter reorder CLI reports "<outcome>"
    And the parameter reorder CLI preserves saved configuration and disposes private compiler material

    Examples:
      | case                        | outcome                   |
      | C17 preview                 | preview without writes    |
      | C11 Unicode CRLF preview    | preview without writes    |
      | approved apply              | applied and rolled back   |
      | duplicate original index    | mapping refused           |
      | nonnumeric original index   | mapping refused           |
      | mixed rename reorder inputs | ambiguous options refused |

  @REQ-C-PARAMETER-REORDER-CLI-001
  @REQ-C-PARAMETER-REORDER-CLI-001#old-style-c-function-definitions-refuse-before-preview
  Scenario Outline: Old-style C function definitions refuse before preview
    Given a captured C parameter reorder CLI invocation has "<case>"
    When the parameter reorder CLI command is executed
    Then the parameter reorder CLI reports "old-style family refused"
    And the parameter reorder CLI preserves saved configuration and disposes private compiler material

    Examples:
      | case                     |
      | C11 old-style definition |
      | C17 old-style definition |

  @REQ-C-PARAMETER-REORDER-CLI-001
  @REQ-C-PARAMETER-REORDER-CLI-001#variadic-c-functions-refuse-before-a-reorder-preview
  Scenario Outline: Variadic C functions refuse before a reorder preview
    Given a captured C parameter reorder CLI invocation has "<case>"
    When the parameter reorder CLI command is executed
    Then the parameter reorder CLI reports "variadic family refused"
    And the parameter reorder CLI preserves saved configuration and disposes private compiler material

    Examples:
      | case                 |
      | C11 variadic family  |
      | C17 variadic family  |
