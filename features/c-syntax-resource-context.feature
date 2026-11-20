# language: en
# Authority: bounded syntax diagnostic resource context; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Evaluate C syntax with retained Clang resources
  Syntax diagnostics use the captured resource directory.
  Missing, changed or caller-replaced resources cannot produce available diagnostics.

  @REQ-C-SYNTAX-RESOURCES-001 @c-syntax-resource-context
  @REQ-C-SYNTAX-RESOURCES-001#syntax-diagnostics-pin-retained-resources-or-refuse-changed-missing-and-overridden-inputs
  Scenario Outline: Syntax diagnostics pin retained resources or refuse changed missing and overridden inputs
    Given a native C compiler resource context has "<condition>"
    When syntax diagnostics are requested with the retained resource context
    Then syntax resource authority reports "<outcome>"
    And resource context validation preserves source SDK and process ownership

    Examples:
      | condition                    | outcome                  |
      | intact captured resources    | pinned clean diagnostics |
      | captured header bytes change | compiler context refused |
      | empty resource file added    | compiler context refused |
      | captured header removed      | compiler context refused |
      | resource capture missing     | compiler context refused |
      | caller resource override     | resource override refused |
