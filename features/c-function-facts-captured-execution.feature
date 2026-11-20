# language: en
# Authority: bounded C03/C04/C10 captured execution; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Read C function facts from an admitted captured compilation
  Function-family facts must describe captured source and configuration.
  A changed configuration or unsupported compiler input must not become
  usable facts, and the compiler must run only over private source material.

  @REQ-C-FUNCTION-EXECUTION-001 @c-function-facts-captured-execution
  Scenario Outline: Captured C function facts preserve compiler identity and input authority
    Given a captured C function project contains "<case>"
    When its configured compiler analyzes the captured function family
    Then compiler function evidence has disposition "<disposition>"
    And captured function analysis preserves workspace bytes and reaps private processes

    Examples:
      | case                         | disposition          |
      | prototype and pure calls     | complete direct      |
      | address of the function      | address taken        |
      | postincrement argument       | observable argument  |
      | database changed after scan  | stale input refused  |
      | compiler plugin flag         | configuration refused |
      | included function declaration | source refused      |
