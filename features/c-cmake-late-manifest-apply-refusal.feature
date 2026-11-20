# language: en
@functional-requirement @partial
Feature: A CMake manifest added after preview cannot bypass managed diagnostics
  An uncaptured nested CMakeLists.txt may appear after preview without changing
  the source snapshot. Managed apply must refuse before WAL rather than treat
  that unmodeled build configuration as absent or as empty diagnostics.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-LATE-MANIFEST-001
  Scenario: A nested manifest appearing after preview blocks approved source move
    Given an isolated C source in a directory with no CMake manifest
    When a nested CMake manifest is created after a source move preview and before approved apply
    Then managed apply refuses because CMake translation unit context is unavailable
    And the original source and late CMake manifest remain unchanged with no transaction journal
