# language: en
@functional-requirement @partial
Feature: Nested CMake targets cannot silently bypass root-only capture
  The root-only manifest reader is not authority for a nested CMake project.
  A source inside a directory with an uncaptured CMakeLists.txt must refuse
  rather than preview a file-only move with an unedited target binding.
  Authority: docs/releases/v0.8.0-plan.md (C03/C08); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-NESTED-REFUSAL-001
  Scenario: A nested target without a root CMake manifest refuses the move
    Given an isolated C project with a nested CMakeLists but no root manifest
    When the selected C source is moved to a different directory in preview
    Then the CMake move is "refused" with exact target binding and no unowned edits
    And the CMake project bytes and transaction journal remain unchanged
