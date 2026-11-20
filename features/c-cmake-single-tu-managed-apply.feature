# language: en
@functional-requirement @partial
Feature: Managed apply of an explicitly captured single-TU CMake source move
  For a single source C-only executable and a literal root CMake binding, a
  snapshot-bound, single captured TU context can be rebased to its moved path
  without running the project's compiler command. All other CMake apply cells
  remain refused until their exact staged build contexts are qualified.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08/C16); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-SINGLE-TU-APPLY-001#an-approved-cmake-source-move-with-context-is-disposition
  Scenario Outline: An approved CMake source move with <context> is <disposition>
    Given an isolated literal CMake executable with a "<context>" compilation context
    When the source move is previewed and explicitly approved with its captured context
    Then managed apply is "<disposition>" without executing the recorded build command
    And applied source and manifest bytes roll back exactly or refusal leaves no transaction

    Examples:
      | context       | disposition |
      | c11           | applied     |
      | c17           | applied     |
      | response-file | refused     |
      | changed-db    | refused     |

  @REQ-C-CMAKE-SINGLE-TU-APPLY-001#native-runtime-identity-mismatch-refuses-before-transaction
  Scenario: Native runtime identity mismatch refuses before transaction
    Given an isolated literal CMake executable with a "runtime-identity" compilation context
    When the source move is previewed and explicitly approved with its captured context
    Then managed apply is "refused" without executing the recorded build command
    And applied source and manifest bytes roll back exactly or refusal leaves no transaction
