# language: en
@functional-requirement
Feature: C CLI safe-delete preview boundary
  The C CLI exposes the semantic safe-delete planner and preserves the project
  when it returns either a preview or a refusal.
  Authority: docs/releases/v0.8.0-plan.md (C13/C16); integrated status: docs/arc42/appendix-requirements.adoc.

  @implemented-and-validated @REQ-C-SAFE-DELETE-CLI-001#c-cli-reports-only-evidence-backed-safe-delete-previews
  Scenario Outline: C CLI reports only evidence-backed safe-delete previews
    Given a C CLI safe-delete workspace with "<case>"
    When the C safe-delete CLI command is previewed
    Then the C safe-delete CLI reports the "<outcome>" outcome
    And the C CLI safe-delete leaves its workspace and transaction journal unchanged

    Examples:
      | case                            | outcome  |
      | isolated unused private function | preview  |
      | referenced private function      | refusal  |

  @implemented-and-validated @REQ-C-SAFE-DELETE-CLI-001#c-cli-applies-and-rolls-back-one-compiler-proven-safe-delete
  Scenario: C CLI applies and rolls back one compiler-proven safe delete
    Given a C CLI safe-delete workspace with "isolated unused private function"
    When the C safe-delete CLI command is applied
    Then the C safe-delete CLI reports an applied compiler-proven deletion transaction
    And rolling back the C safe-delete transaction restores the workspace exactly

  @implemented-and-validated @REQ-C-SAFE-DELETE-CLI-001#c-cli-applies-and-rolls-back-one-compiler-proven-unused-file-static-object
  Scenario: C CLI applies and rolls back one compiler-proven unused file-static object
    Given a C CLI safe-delete workspace with "unused file-static object"
    When the C safe-delete CLI command is applied
    Then the C safe-delete CLI reports an applied compiler-proven deletion transaction
    And rolling back the C safe-delete transaction restores the workspace exactly
