# language: en
# Authority: bounded compiler resource context; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Compile C facts only with captured current Clang resources
  Discovery retains the complete resource snapshot.
  Compiler analysis pins that directory and rejects missing or changed resources.

  @REQ-C-COMPILER-RESOURCES-001 @c-compiler-resource-context
  @REQ-C-COMPILER-RESOURCES-001#compiler-facts-use-their-retained-resource-context-or-refuse-changed-and-missing-inputs
  Scenario Outline: Compiler facts use their retained resource context or refuse changed and missing inputs
    Given a native C compiler resource context has "<condition>"
    When function facts are requested with the retained resource context
    Then compiler resource authority reports "<outcome>"
    And resource context validation preserves source SDK and process ownership

    Examples:
      | condition                    | outcome                     |
      | intact captured resources    | pinned compiler facts       |
      | captured header bytes change | compiler context refused    |
      | empty resource file added    | compiler context refused    |
      | captured header removed      | compiler context refused    |
      | resource link inserted       | compiler context refused    |
      | resource capture missing     | compiler context refused    |
