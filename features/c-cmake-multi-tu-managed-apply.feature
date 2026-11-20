# language: en
@functional-requirement @partial
Feature: Managed apply of a CMake source move in a multi-TU project
  A project with multiple translation units and a compilation database with
  multiple entries is admitted as long as the moved source is a leaf in the
  include graph, no other file includes it, and exactly one database entry
  matches the moved file. The other TUs are unaffected by the move.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08/C16); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-MULTI-TU-APPLY-001#a-multi-tu-cmake-project-with-condition-is-disposition
  Scenario Outline: A multi-TU CMake project with <condition> is <disposition>
    Given an isolated multi-TU CMake project with "<condition>"
    When a leaf source is previewed and explicitly approved for managed apply
    Then managed apply is "<disposition>" with correct staged diagnostics
    And applied or refused state is consistent with WAL and rollback

    Examples:
      | condition                  | disposition |
      | leaf-move                  | applied     |
      | other-file-includes-moved  | refused     |
      | moved-file-has-include     | refused     |
