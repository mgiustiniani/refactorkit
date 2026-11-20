# language: en
@functional-requirement @partial
Feature: A captured CMake apply refuses new unmodeled build inputs
  A preview-bound single-TU compilation database is not proof of closure if
  another CMake manifest or compilation database appears before approval.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-LATE-CONFIG-001
  Scenario Outline: An approved move with a late <context> is refused before WAL
    Given an isolated literal CMake executable with a "<context>" compilation context
    When the source move is previewed and explicitly approved with its captured context
    Then managed apply is "refused" without executing the recorded build command
    And applied source and manifest bytes roll back exactly or refusal leaves no transaction

    Examples:
      | context      |
      | nested       |
      | alternate-db |
