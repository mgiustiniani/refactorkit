# language: en
# Authority: bounded native C02 resource acquisition; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Capture every Clang resource file and directory without executing its contents
  Resource validity includes the complete directory shape and binary file bytes.
  Changes after capture cannot retain the previous resource authority.

  @REQ-C-CLANG-RESOURCE-CAPTURE-001 @c-clang-resource-directory-capture
  @REQ-C-CLANG-RESOURCE-CAPTURE-001#complete-clang-resources-remain-current-or-refuse-changed-indirect-and-oversized-inputs
  Scenario Outline: Complete Clang resources remain current or refuse changed indirect and oversized inputs
    Given a configured Clang resource directory has "<condition>"
    When the library captures and rechecks the complete resource directory
    Then Clang resource acquisition reports "<outcome>"
    And resource inspection preserves the configured SDK and executes no content

    Examples:
      | condition                   | outcome                   |
      | intact configured resources | captured and current      |
      | captured file bytes changed | changed resources refused |
      | resource file added         | changed resources refused |
      | resource file removed       | changed resources refused |
      | symbolic link to script     | indirect inputs refused   |
      | entry budget too small      | input limit refused       |
