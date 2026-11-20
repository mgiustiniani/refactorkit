# language: en
@functional-requirement @partial
Feature: Managed CMake moves refuse prepared source path-macro identifiers
  Clean configured-Clang syntax does not prove moved-source path independence.
  LF/CRLF/CR splices can reconstruct source identifiers absent from raw matching.
  Reuse managed newline/splice preparation and CTokenizer to detect exact source
  identifiers __FILE__, __FILE_NAME__, __BASE_FILE__ and __builtin_FILE in the
  moved translation unit. Unused actual source operator definitions are not exempt.
  Do not add a second lexer, macro interpreter or source-byte normalization.
  Preserve existing conservative raw-text checks; this is not new admission of
  unspliced raw identifiers in comments/strings. Spliced ordinary comment/string
  trivia and lookalike identifiers remain admitted.
  Unsupported moved-source inputs fail during managed capture: retain inspectable
  structural PREVIEW but no lease or verified/evaluated diagnostics claim. Apply
  refuses diagnostics.unavailable before WAL despite disabled caller diagnostics.
  Separate input-bound moved-source policy/digest bind preview and retained apply;
  older/missing/downgraded/tampered bindings require fresh preview. Core and prior
  policy identities/digests/error precedence/historical receipts stay unchanged.
  Four exercised source cases observe actual path string expansions changing after
  main.c moves to moved/entry.c; unused __builtin_FILE definition is containment
  only, not an exercised expansion/branch/runtime change. Baseline/staged overlay
  roots differ; observations bind suffix/basename and responsible workspace TU,
  not workspace-valued macro strings or runtime equivalence.
  Only configured hash-checked Clang syntax-only checks execute, never CMake code
  or captured commands. Positive control applies with explicit approval and rolls
  back exact source/manifest/database bytes and snapshot.
  Captured -D aliases, unchanged helper sources, generated/header-mediated/other
  expansion, path-sensitive directives/include normalization and complete encoding/
  preprocessing/consumer/source-equivalence proof remain separate/unqualified.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08/C15); integrated narrative:
  docs/arc42/appendix-requirements.adoc. Requirement remains partial.

  @REQ-C-CMAKE-PATH-MACRO-001
  Scenario Outline: A CMake project with <state> moved path macros has <disposition> authority
    Given an isolated CMake moved-path-macro fixture in state "<state>"
    When that moved-path-macro fixture previews its main translation unit move
    Then its managed moved-path-macro disposition is "<disposition>"
    And its retained moved-path-macro policy permits exact rollback or no WAL

    Examples:
      | state                       | disposition         |
      | ordinary-spliced-path-trivia | applicable          |
      | lf-file-macro               | unavailable-context |
      | crlf-base-file-macro         | unavailable-context |
      | cr-file-name-macro           | unavailable-context |
      | chained-file-macro           | unavailable-context |
      | unused-builtin-file-macro    | unavailable-context |
