# language: en
# Authority: bounded C05 captured staging; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Evaluate C function preview content in its captured compiler context
  A preview changes source content while saved input authority stays fixed.
  Its compiler facts must use the captured dialect and define, without saving
  candidate bytes or admitting different paths or compilation configuration.

  @REQ-C-FUNCTION-STAGE-001 @c-function-facts-preview-evaluation
  Scenario Outline: C function preview evaluation keeps saved authority and staged content distinct
    Given a captured function preview contains "<case>"
    When its staged source is evaluated with the captured compiler context
    Then staged function facts have disposition "<disposition>"
    And staged function evaluation leaves saved bytes intact and owns no private process

    Examples:
      | case                          | disposition             |
      | reordered C17 source          | staged facts available  |
      | reordered C11 source          | staged facts available  |
      | invalid candidate syntax      | compiler unavailable    |
      | candidate database changed    | boundary refused        |
      | saved database changed        | stale authority refused |
      | candidate adds another source | boundary refused        |
