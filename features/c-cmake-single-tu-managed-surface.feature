# language: en
@functional-requirement @partial
Feature: Preview-bound CMake apply reaches the managed daemon and MCP surfaces
  Each transport must retain the exact captured compilation gate with the
  previewed plan; only RefactorKit's PatchEngine may write and roll back files.
  Authority: docs/releases/v0.8.0-plan.md (C16); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-MANAGED-SURFACE-001
  Scenario Outline: A <surface> source move applies and rolls back the CMake binding
    Given an isolated single-TU CMake workspace opened through "<surface>"
    When the C source move is previewed approved and rolled back through that surface
    Then the surface reports managed apply and byte-exact rollback of C and CMake files
    And no project build command or unowned file has been executed or written

    Examples:
      | surface |
      | daemon  |
      | MCP     |

  @REQ-C-CMAKE-MANAGED-SURFACE-001#managed-c-preview-identifies-the-c-provider
  Scenario Outline: Managed C preview identifies the C provider
    Given an isolated single-TU CMake workspace opened through "<surface>"
    When the caller requests a read-only managed C source move preview
    Then the preview identifies the C provider for the captured plan
    And no project build command or unowned file has been executed or written

    Examples:
      | surface |
      | daemon  |
      | CLI     |

  @REQ-C-CMAKE-MANAGED-SURFACE-001#daemon-advertises-its-managed-c-preview-method
  Scenario: Daemon advertises its managed C preview method
    Given an isolated single-TU CMake workspace opened through "daemon"
    When the caller requests a read-only managed C source move preview
    Then the daemon advertises the executed C preview as a read-only project method
    And no project build command or unowned file has been executed or written

  @REQ-C-CMAKE-MANAGED-SURFACE-001#cli-refuses-conflicting-preview-and-apply-modes
  Scenario Outline: CLI refuses conflicting preview and apply modes
    Given an isolated single-TU CMake workspace opened through "CLI"
    When the caller requests the C move with conflicting flags "<first>" and "<second>"
    Then the CLI refuses the conflicting C modes before changing the workspace
    And no project build command or unowned file has been executed or written

    Examples:
      | first     | second    |
      | --preview | --apply   |
      | --apply   | --preview |
