# language: en
# Authority: bounded C10 library preview; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Preview a compiler-bound C parameter reorder
  A complete original-index mapping changes a file-static function's parameter
  declarations and direct-call arguments together. A preview preserves the
  existing body, unrelated names and saved files, and requires explicit approval.
  Unproven consumers, observable arguments and changed authority refuse.

  @REQ-C-PARAMETER-REORDER-PREVIEW-001 @c-parameter-reordering-preview
  Scenario Outline: C parameter reordering previews a complete compiler family or refuses without edits
    Given a captured C parameter reorder has "<case>"
    When its complete original-index mapping is previewed
    Then the parameter reorder preview has disposition "<disposition>"
    And the parameter reorder preview preserves saved bytes and releases private compiler material

    Examples:
      | case                       | disposition                  |
      | C17 complete family        | complete preview             |
      | C11 Unicode CRLF family    | complete preview             |
      | observable argument        | observable argument refused  |
      | function address taken     | indirect consumers refused   |
      | duplicate original index   | invalid mapping refused      |
      | changed captured database  | stale authority refused      |
