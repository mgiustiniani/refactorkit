# language: en
@functional-requirement @partial
Feature: A bounded CMake source move validates system includes and refuses unsupported include contexts
  Angled system includes remain available to the configured compiler during a
  source move. A quoted include of a workspace header is outside the current
  source-only managed-apply context and must fail closed. This slice does not
  establish behavior for quoted includes resolved outside the workspace.
  Macro/path-sensitive constructs and #include_next/#line/#import remain refused.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-HEADER-INCLUDE-001
  Scenario Outline: A CMake move with <include_type> is <disposition>
    Given an isolated CMake project with a "<include_type>" include in the source
    When the source is previewed and explicitly approved for managed apply
    Then managed apply is "<disposition>" with staged compiler verification
    And the header move WAL state is consistent with byte-exact restoration

    Examples:
      | include_type      | disposition |
      | system-angled     | applied     |
      | workspace-quoted  | refused     |
      | include-next      | refused     |
      | file-macro        | refused     |
