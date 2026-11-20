# language: en
@functional-requirement @partial
Feature: C component relocation carries admitted CMake source and include roots
  Source/header paths, private target sources and private literal include roots
  are rewritten together in one preview; unbound headers and dynamic scripts
  are not treated as proof. No CMake project code is executed.
  This bounded case is not full C15/C03/C05 acceptance.
  Authority: docs/releases/v0.8.0-plan.md (C15); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-COMPONENT-BINDING-001
  Scenario Outline: Relocate <manifest_form> component with one CMake target
    Given an isolated C component with "<manifest_form>" CMake bindings
    When the C component relocation is previewed
    Then the component binding is "<disposition>" with an exact staged CMake manifest
    And the relocation preview does not write files or a transaction

    Examples:
      | manifest_form   | disposition |
      | literal-private | preview     |
      | unbound-header  | refused     |
      | included-script | refused     |

  @REQ-C-CMAKE-COMPONENT-BINDING-001#generated-c-component-tree-relocation-refuses-before-any-edit
  Scenario: Generated C component tree relocation refuses before any edit
    Given an isolated C component with "generated-source" CMake bindings
    When the C component relocation is previewed
    Then the component binding is "refused" with an exact staged CMake manifest
    And the relocation preview does not write files or a transaction

  @REQ-C-CMAKE-COMPONENT-BINDING-001#component-relocation-refuses-an-uncaptured-literal-include
  Scenario: Component relocation refuses an uncaptured literal include
    Given an isolated C component with "uncaptured-include" CMake bindings
    When the C component relocation is previewed
    Then the component binding is "refused" with an exact staged CMake manifest
    And the relocation preview does not write files or a transaction

  @REQ-C-CMAKE-COMPONENT-BINDING-001#public-component-include-root-preview-retains-literal-target-authority
  Scenario Outline: Public component include root preview retains literal target authority
    Given an isolated C component with "<manifest_form>" CMake bindings
    When the C component relocation is previewed
    Then the component binding is "<disposition>" with an exact staged CMake manifest
    And the relocation preview does not write files or a transaction

    Examples:
      | manifest_form       | disposition |
      | literal-public      | preview     |
      | literal-private     | preview     |
      | interface-root      | refused     |
      | missing-visibility  | refused     |
      | public-script       | refused     |
      | public-dynamic-root | refused     |
