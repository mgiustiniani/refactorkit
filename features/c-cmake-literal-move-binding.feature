# language: en
@functional-requirement @partial
Feature: C source move owns a bounded literal CMake target binding
  Only root CMakeLists.txt commands in the admitted declarative subset may
  supply write authority. Literal target sources and the moved file change in
  one preview plan; scripts, conditions, variables and generator expressions
  are refused without running CMake. This is not full C08/C03 qualification.
  Authority: docs/releases/v0.8.0-plan.md (C08); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-MOVE-BINDING-001
  Scenario Outline: C source move handles <manifest_form> CMake bindings without executing build code
    Given an isolated C project with a "<manifest_form>" root CMake manifest
    When the selected C source is moved to a different directory in preview
    Then the CMake move is "<disposition>" with exact target binding and no unowned edits
    And the CMake project bytes and transaction journal remain unchanged

    Examples:
      | manifest_form   | disposition |
      | add-library     | preview     |
      | target-sources  | preview     |
      | variable        | refused     |
      | generator       | refused     |
      | included-script | refused     |
      | conditional     | refused     |

  @REQ-C-CMAKE-MOVE-BINDING-001#c-source-move-refuses-an-uncaptured-existing-quoted-include
  Scenario: C source move refuses an uncaptured existing quoted include
    Given an isolated C project with a "uncaptured-include" root CMake manifest
    When the selected C source is moved to a different directory in preview
    Then the CMake move is "refused" with exact target binding and no unowned edits
    And the CMake project bytes and transaction journal remain unchanged
