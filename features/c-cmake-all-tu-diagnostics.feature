# language: en
@functional-requirement @partial
Feature: A bounded CMake move evaluates every captured translation unit before approval
  In the admitted literal C11/C17 cell, configured hash-bound Clang checks every
  snapshot translation unit with its own sanitized flags in baseline and staged
  source-only overlays. Only the moved source path is rebased. Compiler errors
  in unchanged translation units also refuse the preview, with no applicable
  edits or authority lease. Warnings retain their workspace source attribution.
  The preview lease records project-TU diagnostics scope and TU count; managed
  apply uses the same all-TU gate and preserves WAL and byte-exact rollback.
  To bound sequential compiler work, projects above eight TUs are unavailable
  for managed apply, not reported as clean. Captured commands are never run.
  This extends the moved-TU-only evidence boundary of the earlier command-model
  Story in c-cmake-multi-tu-compile-context.feature; that historical slice alone
  does not establish project diagnostics. It does not qualify target ownership,
  complete header/consumer closure, linking, ABI or independent release acceptance.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-ALL-TU-DIAGNOSTICS-001
  Scenario Outline: A captured CMake project with <state> has <disposition> diagnostics
    Given an isolated bounded CMake project in diagnostics state "<state>"
    When the captured CMake project is previewed for moving its main translation unit
    Then the project-wide CMake diagnostics disposition is "<disposition>"
    And the project-wide diagnostics outcome permits byte-exact rollback or no WAL

    Examples:
      | state                    | disposition      |
      | clean-distinct-contexts  | applicable       |
      | helper-error             | refused-baseline |
      | helper-define-error      | refused-baseline |
      | third-tu-error           | refused-baseline |
      | staged-helper-dependency | refused-staged   |
      | project-limit            | unavailable      |
