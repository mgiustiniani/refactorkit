# language: en
# Authority: bounded C03 warm semantic configuration; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Refuse stale C semantic authority after configuration changes
  A running semantic process uses captured compilation input. Changing, deleting
  or adding a competing database requires a fresh capture. Unavailable reference
  evidence must never become a zero-reference result or a rename proposal.

  @REQ-C-WARM-CONFIG-001 @c-warm-configuration-invalidation
  Scenario Outline: A warm C query cannot outlive its captured compilation database
    Given a warm C semantic session captured "<database>" configuration
    When "<change>" precedes a warm "<query>" query
    Then the warm query disposition is "<disposition>" for its configuration authority
    And warm configuration checks preserve project bytes and reap owned processes

    Examples:
      | database | change                 | query      | disposition |
      | root     | captured define changes | definition | refused     |
      | build    | captured define changes | references | unavailable |
      | root     | captured file disappears | rename    | refused     |
      | root     | competing file appears | definition | refused     |
      | root     | no input changes       | definition | found       |
      | root     | disabled config changes | definition | found      |


  @REQ-C-COMPILE-UNIT-MODEL-001#owned-sysroot-changes-revoke-warm-semantic-authority
  Scenario Outline: Owned sysroot changes revoke warm semantic authority
    Given a warm C semantic session captured "owned sysroot" configuration
    When "<change>" precedes a warm "<query>" query
    Then the warm query disposition is "<disposition>" for its configuration authority
    And warm configuration checks preserve project bytes and reap owned processes

    Examples:
      | change                       | query      | disposition |
      | no input changes             | definition | found       |
      | owned sysroot header changes | definition | refused     |
      | owned sysroot header appears | references | unavailable |
      | owned sysroot header removed | rename     | refused     |
      | owned sysroot directory added| open       | not opened  |
      | owned sysroot link inserted  | definition | refused     |


  @REQ-C-COMPILE-UNIT-MODEL-001#secondary-owned-sysroot-changes-revoke-warm-semantic-authority
  Scenario Outline: Secondary owned sysroot changes revoke warm semantic authority
    Given a warm C semantic session captured "two owned sysroots" configuration
    When "<change>" precedes a warm "<query>" query
    Then the warm query disposition is "<disposition>" for its configuration authority
    And warm configuration checks preserve project bytes and reap owned processes

    Examples:
      | change                            | query      | disposition |
      | no input changes                  | definition | found       |
      | secondary sysroot header changes  | definition | refused     |
      | secondary sysroot header appears  | references | unavailable |
      | secondary sysroot header removed  | rename     | refused     |
      | secondary sysroot directory added | open       | not opened  |
      | secondary sysroot link inserted   | definition | refused     |


  @REQ-C-COMPILE-UNIT-MODEL-001#external-read-only-sdk-changes-revoke-warm-semantic-authority
  Scenario Outline: External read-only SDK changes revoke warm semantic authority
    Given a warm C semantic session captured "external read-only SDK" configuration
    When "<change>" precedes a warm "<query>" query
    Then the warm query disposition is "<disposition>" for its configuration authority
    And warm configuration checks preserve project bytes and reap owned processes

    Examples:
      | change                     | query      | disposition |
      | no input changes           | definition | found       |
      | SDK header changes         | definition | refused     |
      | SDK header appears         | references | unavailable |
      | SDK header removed         | rename     | refused     |
      | SDK directory added        | open       | not opened  |
      | SDK link inserted          | definition | refused     |


  @REQ-C-COMPILE-UNIT-MODEL-001#discovery-retained-sdk-changes-revoke-warm-semantic-authority
  Scenario Outline: Discovery retained SDK changes revoke warm semantic authority
    Given a warm C semantic session captured "discovered external SDK" configuration
    When "<change>" precedes a warm "<query>" query
    Then the warm query disposition is "<disposition>" for its configuration authority
    And warm configuration checks preserve project bytes and reap owned processes

    Examples:
      | change              | query      | disposition |
      | no input changes    | definition | found       |
      | SDK header changes  | definition | refused     |
      | SDK header appears  | references | unavailable |
      | SDK header removed  | rename     | refused     |
      | SDK directory added | open       | not opened  |
      | SDK link inserted   | definition | refused     |
