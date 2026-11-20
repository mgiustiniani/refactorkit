# language: en
# Authority: native compiler currency only; ARC42 owns integrated C02 acceptance.
@functional-requirement @partial
Feature: Retain C native runtime inputs through compiler evaluation
  Compiler results require the same current runtime captured for the selected tools.
  Missing authority or changed inputs cannot become usable AST facts or diagnostics.

  @REQ-C-COMPILER-RUNTIME-CURRENCY-001#compiler-consumers-require-matching-current-native-runtime-inputs
  Scenario Outline: Compiler consumers require matching current native runtime inputs
    Given a native compiler consumer runtime has "<condition>"
    When AST facts and syntax diagnostics are requested from that runtime
    Then compiler runtime consumers report "<outcome>"
    And compiler runtime evaluation preserves source and releases private processes

    Examples:
      | condition                | outcome             |
      | current native runtime   | available results   |
      | runtime capture missing  | unavailable results |
      | runtime identity changed | unavailable results |
      | executable selector changed | unavailable results |
      | captured library changed | unavailable results |
      | executable became canary | unavailable results |

  @REQ-C-CLANGD-RUNTIME-CURRENCY-001#clangd-requires-current-native-runtime-through-launch-and-semantic-query
  Scenario Outline: clangd requires current native runtime through launch and semantic query
    Given a native clangd consumer runtime has "<condition>"
    When clangd resolves a bound C function definition from that runtime
    Then clangd native runtime admission is "<outcome>"
    And compiler runtime evaluation preserves source and releases private processes

    Examples:
      | condition                | outcome          |
      | current native runtime   | definition found |
      | runtime capture missing  | runtime refused  |
      | runtime identity changed | runtime refused  |
      | executable selector changed | runtime refused |
      | captured library changed | runtime revoked  |
      | executable became canary | runtime refused  |
