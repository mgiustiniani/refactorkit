# language: en
# Authority: native compiler SDK inputs; ARC42 owns integrated release acceptance.
@functional-requirement @partial
Feature: Compiler consumers retain explicit read-only SDK inputs
  SDK inputs captured by discovery authorize compiler reads only while current.
  Current and staged syntax use SDK types without granting SDK write authority.

  @REQ-C-COMPILE-UNIT-MODEL-001#compiler-consumers-retain-discovered-sdk-input-authority
  Scenario Outline: Compiler consumers retain discovered SDK input authority
    Given a native C compiler SDK fixture uses "<condition>" in "<standard>" mode
    When source facts and current plus staged syntax consume that SDK
    Then compiler SDK authority reports "<outcome>" with exact native context
    And compiler SDK checks preserve saved inputs and close private processes

    Examples:
      | condition             | standard | outcome             |
      | intact SDK            | c11      | available results   |
      | intact SDK            | c17      | available results   |
      | SDK header changes    | c11      | unavailable results |
      | SDK header appears    | c17      | unavailable results |
      | SDK identity differs  | c11      | unavailable results |
      | SDK header removed    | c17      | unavailable results |
