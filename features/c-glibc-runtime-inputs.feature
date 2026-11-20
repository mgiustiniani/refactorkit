@REQ-C-GLIBC-INPUTS-001
Feature: Captured C glibc runtime data inputs
  Cache bytes and preload absence belong to the selected runtime inputs.
  Native-data inspection must preserve source and refuse unavailable authority.

  @REQ-C-GLIBC-INPUTS-001#c-runtime-cache-and-preload-inputs-retain-current-captures-or-refuse-unavailable-authority
  Scenario Outline: C runtime cache and preload inputs retain current captures or refuse unavailable authority
    Given C glibc runtime inputs have "<condition>"
    When the C runtime cache and preload inputs are captured and checked
    Then the C glibc runtime input outcome is "<outcome>"
    And glibc runtime inspection preserves source and executes no selected tool

    Examples:
      | condition       | outcome     |
      | host inputs     | current     |
      | owned copy      | current     |
      | changed cache   | not-current |
      | indirect cache  | refused     |
      | missing cache   | refused     |
      | preload appears | not-current |
