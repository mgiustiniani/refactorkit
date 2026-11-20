# language: en
# Authority: bounded C02 library artifact capture; ARC42 owns integrated acceptance.
@functional-requirement @partial
Feature: Capture native C toolchain artifact dependencies without executing objects
  Native file bytes and dependency declarations belong to one explicit artifact.
  Capture is read-only and its current check observes all bytes again.
  Changed and indirect files cannot retain the captured authority.

  @REQ-C-ELF-ARTIFACT-001 @c-elf-artifact-input-capture
  @REQ-C-ELF-ARTIFACT-001#configured-c-elf-artifacts-retain-exact-dynamic-inputs-or-refuse-changed-and-indirect-files
  Scenario Outline: Configured C ELF artifacts retain exact dynamic inputs or refuse changed and indirect files
    Given a configured C ELF artifact capture has "<condition>"
    When the library captures and rechecks that native artifact
    Then native artifact capture reports "<outcome>"
    And native artifact inspection preserves source and executes no artifact

    Examples:
      | condition                 | outcome                         |
      | configured clang driver   | captured and current            |
      | configured clangd server  | captured and current            |
      | configured formatter      | captured and current            |
      | configured LLVM library   | captured and current            |
      | captured artifact changed | changed bytes refused           |
      | symbolic link to script   | indirect artifact refused       |
