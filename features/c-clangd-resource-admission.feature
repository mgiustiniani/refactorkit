# language: en
# Authority: bounded clangd startup resource admission; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Admit C semantic startup only with complete current resources
  Missing, unavailable, mismatched or changed captured resource inputs cannot launch a server.

  @REQ-C-CLANGD-RESOURCE-ADMISSION-001 @c-clangd-resource-admission @REQ-C-CLANGD-RESOURCE-ADMISSION-001#c-semantic-startup-admits-its-captured-resource-context-or-refuses-before-native-launch
  Scenario Outline: C semantic startup admits its captured resource context or refuses before native launch
    Given a configured C client retains "<condition>" startup resource evidence
    When the configured C client attempts its resource-bound initialize handshake
    Then semantic startup resource disposition is "<disposition>"
    And warm configuration checks preserve project bytes and reap owned processes

    Examples:
      | condition              | disposition              |
      | intact capture         | pinned resource admitted |
      | capture missing        | refused before launch    |
      | capture unavailable    | refused before launch    |
      | declared root differs  | refused before launch    |
      | captured bytes changed | refused before launch    |
      | captured root removed  | refused before launch    |
