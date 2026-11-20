# language: en
# Authority: bounded warm clangd resource context; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Keep warm C semantics bound to captured Clang resources
  A native server pins the complete retained resource tree.
  Changed resources revoke definition, references, rename and document opening.

  @REQ-C-CLANGD-RESOURCES-001 @c-clangd-resource-context
  Scenario Outline: Warm C semantics use retained resources or close after complete resource revocation
    Given a warm C client retained a complete owned resource tree
    When "<change>" precedes a warm "<query>" query
    Then the warm query disposition is "<disposition>" for its configuration authority
    And warm configuration checks preserve project bytes and reap owned processes

    Examples:
      | change                       | query               | disposition      |
      | no input changes             | resource definition | resource found   |
      | no input changes             | references          | references found |
      | captured header bytes change | definition          | refused          |
      | empty resource file added    | references          | unavailable      |
      | captured header removed      | rename              | refused          |
      | resource link inserted       | open                | not opened       |
