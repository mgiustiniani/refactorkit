# language: en
@functional-requirement @partial
Feature: A multi-TU CMake move requires a complete and fully modelled project compile context
  Before a bounded source move may apply, every project translation unit must
  have exactly one compilation-database entry naming a snapshot translation
  unit, and every project command must be fully modelled data: the configured
  Clang only, exactly one source per command, and only bounded admitted flags.
  A missing or non-snapshot entry, a foreign compiler, a -target flag or a
  multi-source command is unmodelled context and must refuse with no write.
  Compiler diagnostics remain limited to the moved leaf translation unit;
  this slice does not qualify all-TU diagnostics or header/component relocation.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-MULTI-TU-CONTEXT-001
  Scenario Outline: A multi-TU CMake project in compile-context state <state> is <disposition>
    Given an isolated multi-TU CMake project in compile-context state "<state>"
    When the multi-TU compile-context source is previewed and explicitly approved for managed apply
    Then the multi-TU compile-context apply is "<disposition>"
    And the multi-TU compile-context WAL state is consistent

    Examples:
      | state                | disposition |
      | all-understood       | applied     |
      | missing-entry        | refused     |
      | target-flag          | refused     |
      | alternate-compiler   | refused     |
      | multi-source-command | refused     |
      | non-snapshot-entry   | refused     |
