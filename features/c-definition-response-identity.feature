# language: en
# Authority: bounded C04 semantic transport responses; ARC42 owns integrated status.
# Protocol authority: https://github.com/microsoft/language-server-protocol/blob/gh-pages/_specifications/lsp/3.18/language/definition.md
@functional-requirement @partial
Feature: Preserve C definition identity across semantic transport responses
  A definition response distinguishes absence from invalid or ambiguous evidence.
  Local file URI identity survives escaped spaces and Unicode. A protocol peer
  cannot turn malformed evidence into a guessed location or a missing symbol.

  @REQ-C-DEFINITION-RESPONSE-001#c-definition-lookup-preserves-response-identity-and-evidence-disposition @c-definition-response-identity
  Scenario Outline: C definition lookup preserves response identity and evidence disposition
    Given a C semantic protocol peer returns "<response>" definition evidence
    When the C client requests that definition through its framed protocol
    Then the definition response disposition is "<disposition>" with its exact file identity
    And definition protocol exchanges preserve project bytes and reap owned processes

    Examples:
      | response                | disposition |
      | null location           | absent      |
      | empty location array    | absent      |
      | escaped file URI        | found       |
      | nonnumeric range        | invalid     |
      | multiple locations      | ambiguous   |
      | nonfile URI             | invalid     |
