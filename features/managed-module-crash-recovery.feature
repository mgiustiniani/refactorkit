@non-functional-requirement @partial
Feature: Recover an interrupted public module-directory transaction
  # Authority: docs/requirements/v0.7.x-completion-contract.md, I1.
  # Architecture: docs/arc42/08-crosscutting-concepts.adoc.
  # This stress copy does not change the separate J1 five-edit packaged fixture contract.
  # Source-child and checksum-bound packaged executions are distinct evidence subjects.

  @REQ-I1-MODULE-DIRECTORY-CRASH-001
  Scenario: A killed public module-directory apply restores the complete reactor on recovery
    Given an isolated twenty-module reactor has 512 additional ordinary source files in the module to rename
    When the public module-directory apply is killed after a module source file moves
    Then public recovery restores every original reactor path and byte and the interrupted WAL record

  @REQ-I1-MODULE-RECOVERY-CONFLICT-001
  Scenario Outline: Recovery never adopts foreign or corrupted staging paths after a module crash
    Given an isolated twenty-module reactor has 512 additional ordinary source files in the module to rename
    When the public module-directory apply is killed after a module source file moves
    And an external "<kind>" recovery conflict is introduced in the destination directory
    Then public recovery refuses without changing any reactor or foreign file

    Examples:
      | kind                    |
      | unowned stage-like file |
      | changed owned stage     |
      | symbolic owned stage    |
      | same-byte stage replacement |
      | corrupt ownership receipt   |

  @REQ-I1-MODULE-RECOVERY-CONFLICT-001
  Scenario: Legacy WAL ownership cannot be inferred from a new-style staging filename
    Given an isolated twenty-module reactor has 512 additional ordinary source files in the module to rename
    When the public module-directory apply is killed after a module source file moves
    And the checksum-valid WAL uses the legacy unmarked layout beside an empty foreign new-style staging file
    Then public recovery refuses without changing any reactor or foreign file

  @REQ-I1-MODULE-RECOVERY-CONFLICT-001
  Scenario: A marked pre-stage intent cannot claim an empty file it never created
    Given an isolated twenty-module reactor has 512 additional ordinary source files in the module to rename
    When the public module-directory apply is killed after a module source file moves
    And the marked pre-stage WAL is reconstructed with untouched sources and an empty foreign derived staging file
    Then public recovery refuses without changing any reactor or foreign file
