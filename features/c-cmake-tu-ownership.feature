# language: en
@functional-requirement @partial
Feature: Managed CMake moves require complete unique literal target ownership
  Every captured C translation unit and its understood compiler entry must have
  exactly one source occurrence and owner in the captured literal root manifest.
  Owners must be literal executables or explicit STATIC libraries; a normalized
  duplicate source occurrence, multiple owners, missing owner or unsupported
  owner type cannot receive managed write authority. PRIVATE target_sources
  records use the same parser and model as primary source declarations.
  Baseline and simulated staged ownership preserve each target identity/type;
  only the moved source path may rebase. A versioned scope and before/staged
  ownership hash bind the preview lease and are required again at managed apply.
  Unsupported ownership remains an inspectable structural preview without lease
  or verified diagnostics, and refuses before WAL even with caller-disabled gates.
  Complete eligible contexts retain explicit approval and byte-exact rollback.
  This is bounded declarative ownership, not evaluated CMake/flag agreement,
  linking, complete header/consumer/preprocessor equivalence or release acceptance.
  Captured compiler commands and CMake project code are never executed.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08); integrated status:
  docs/arc42/appendix-requirements.adoc. Earlier partial receipts remain unchanged.

  @REQ-C-CMAKE-TU-OWNERSHIP-001
  Scenario Outline: A literal CMake project in ownership <state> has <disposition> authority
    Given an isolated literal CMake ownership fixture in state "<state>"
    When that ownership fixture previews the main translation unit move
    Then its literal target ownership disposition is "<disposition>"
    And its retained ownership authority permits exact rollback or no WAL

    Examples:
      | state                    | disposition |
      | same-target              | applicable  |
      | private-distinct-owners  | applicable  |
      | unowned-helper           | unavailable |
      | duplicate-helper         | unavailable |
      | multiple-helper-owners   | unavailable |
      | unsupported-helper-owner | unavailable |
