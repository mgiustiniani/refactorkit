# language: en
@functional-requirement @partial
Feature: Managed apply of an explicitly captured single-TU CMake library source move
  A literal root CMakeLists.txt add_library(STATIC) target with one captured
  C translation unit and one compile_commands.json entry is admitted for
  managed source move apply with the same preview-bound gate as executables.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08/C16); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-LIBRARY-TU-APPLY-001
  Scenario Outline: An approved CMake library source move with <variant> is <disposition>
    Given an isolated CMake library workspace with a "<variant>" configuration
    When the library source is previewed and explicitly approved for managed apply
    Then managed apply is "<disposition>" and the CMake binding updates atomically
    And the applied source and manifest roll back byte-exact or refusal leaves no transaction

    Examples:
      | variant        | disposition |
      | static-single  | applied     |
      | shared         | refused     |
      | two-sources    | refused     |
