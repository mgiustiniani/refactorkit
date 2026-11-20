# language: en
# Authority: bounded native runtime path capture; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Retain native runtime path aliases without executing artifacts
  An explicit native path retains its requested path, every observed alias and exact resolved artifact.
  Alias retargeting cannot renew an earlier capture, even when the new target has equal bytes.

  @REQ-C-NATIVE-PATH-001 @c-native-runtime-paths
  Scenario Outline: Explicit C runtime paths retain their aliases or refuse unavailable authority
    Given a selected C runtime path has "<condition>" aliases
    When the library captures and rechecks that explicit runtime path
    Then native runtime path disposition is "<disposition>"
    And native artifact inspection preserves source and executes no artifact

    Examples:
      | condition       | disposition              |
      | direct target   | captured and current     |
      | directory alias | captured and current     |
      | leaf alias      | captured and current     |
      | retargeted alias| changed alias refused    |
      | cyclic alias    | unavailable path refused |
      | script alias    | unavailable path refused |
