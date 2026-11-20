# language: en
# Authority: C10 parameter-rename CLI preview and managed library transaction; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Rename a compiler-bound C parameter from the CLI
  The requested function name and compiler-owned parameter declaration select one
  source-owned static function family. An unrelated same-name parameter cannot
  redirect or make the request ambiguous; incomplete external consumers refuse.

  @REQ-C-PARAMETER-RENAME-CLI-001 @c-parameter-rename-cli
  Scenario Outline: C parameter rename CLI selects its compiler-bound family or refuses incomplete evidence
    Given a captured C parameter rename CLI invocation has "<case>"
    When the C parameter rename CLI command is previewed
    Then the C parameter rename CLI reports "<outcome>"

    Examples:
      | case                                                                   | outcome                 |
      | same parameter name in another function with Unicode CRLF prefix      | selected family preview |
      | external function consumers                                             | family refused          |

  @REQ-C-PARAMETER-RENAME-APPLY-001#c-parameter-rename-applies-only-an-approved-unchanged-compiler-bound-preview-and-rolls-back-exactly
  Scenario Outline: C parameter rename applies only an approved unchanged compiler-bound preview and rolls back exactly
    Given a compiler-bound C parameter rename awaits "<case>"
    When the managed C parameter rename is applied
    Then the managed C parameter rename has outcome "<outcome>"
    And the rename preserves source and compiler inputs after its outcome

    Examples:
      | case                           | outcome                   |
      | approved disabled gate         | applied and rolled back   |
      | approval missing               | approval refused          |
      | compilation database changed   | changed authority refused |
      | preview edits changed          | changed edit refused      |
      | preview lease removed          | missing authority refused |
      | native runtime identity changed| runtime identity refused  |

  @REQ-C-PARAMETER-RENAME-APPLY-001
  @REQ-C-PARAMETER-RENAME-APPLY-001#scalar-parameter-renames-preserve-types-and-managed-native-behavior
  Scenario Outline: Scalar parameter renames preserve types and managed native behavior
    Given a C scalar parameter family uses "<type>" in "<dialect>"
    When its scalar parameter rename is previewed applied and rolled back
    Then the scalar family preserves its types positional calls and native result
    And the scalar rename preserves exact inputs and records one guarded transaction

    Examples:
      | type   | dialect |
      | long   | c11     |
      | long   | c17     |
      | double | c11     |
      | double | c17     |
