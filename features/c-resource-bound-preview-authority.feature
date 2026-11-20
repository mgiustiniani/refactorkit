# language: en
# Authority: bounded native preview resource identity; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Retain the resource identity that qualified an approved C preview
  A newly captured resource context cannot renew an older preview's authority.
  An unchanged resource context permits approved apply and exact rollback.

  @REQ-C-RESOURCE-PREVIEW-001 @c-resource-bound-preview-authority
  Scenario Outline: Approved C previews bind resources across rediscovery or complete unchanged transactions
    Given an approved resource-bound C preview has "<condition>"
    When it is applied with the currently captured resource toolchain
    Then resource-bound preview application reports "<outcome>"
    And resource preview validation preserves SDK configuration and compiler ownership

    Examples:
      | condition                        | outcome                    |
      | scalar resources rediscovered    | old resource preview refused |
      | helper resources rediscovered    | old resource preview refused |
      | reordered resources rediscovered | old resource preview refused |
      | insertion resources rediscovered | old resource preview refused |
      | unchanged scalar control         | applied and rolled back    |
      | unchanged helper control         | applied and rolled back    |
