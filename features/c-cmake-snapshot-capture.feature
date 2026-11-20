# language: en
@functional-requirement @partial
Feature: CMake manifests are snapshot-bound evidence, never executable discovery
  A C workspace's root CMakeLists.txt is an auxiliary input, not a C source.
  Its contents must affect the snapshot hash without executing build code.
  This root-file capture is not proof of a complete CMake project model.
  Authority: docs/releases/v0.8.0-plan.md (C03/C08/C15); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-CAPTURE-001
  Scenario: Editing the root CMake manifest invalidates a C project snapshot without executing it
    Given an isolated C source and a CMake manifest with an executable marker command
    When the C project scanner captures the manifest and rescans after its bytes change
    Then the manifest is auxiliary rather than C source and the snapshot hash changes
    And no captured CMake command or user source is executed or modified
