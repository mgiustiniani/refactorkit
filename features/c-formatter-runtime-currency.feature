# language: en
# Authority: formatter native currency only; ARC42 owns integrated C02/C07 acceptance.
@functional-requirement @partial
Feature: Retain C native runtime inputs during formatting
  Formatting and its idempotence check require the same current selected runtime.
  Unknown or changed native inputs cannot produce usable edits.

  @REQ-C-FORMATTER-RUNTIME-CURRENCY-001#formatter-results-require-matching-current-native-runtime-inputs
  Scenario Outline: Formatter results require matching current native runtime inputs
    Given a native formatter consumer runtime has "<condition>"
    When formatting and idempotence are requested from that runtime
    Then formatter runtime authority reports "<outcome>"
    And formatter runtime evaluation preserves source and releases private processes

    Examples:
      | condition                   | outcome                |
      | current native runtime      | idempotent edits       |
      | runtime capture missing     | runtime refused        |
      | runtime identity changed    | runtime refused        |
      | executable selector changed | runtime refused        |
      | captured library changed    | runtime refused        |
      | executable became canary    | runtime refused        |
