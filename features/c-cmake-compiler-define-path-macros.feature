# language: en
@functional-requirement @partial
Feature: Managed CMake moves contain captured compiler-define path identifiers
  A compiler define can introduce a path operator absent from every source.
  Reuse existing sanitized -D payloads and prepared source-token predicate for
  exact __FILE__, __FILE_NAME__, __BASE_FILE__ and __builtin_FILE identifiers in
  every captured TU context. Joined/separated arguments use the existing sanitizer;
  no second grammar/lexer, macro interpreter, relevance or unused-safety inference.
  Source-path capture/raw guards and historical policies remain unchanged.
  After clean staged compiler and earlier consumer checks, direct path identifiers
  in captured define payloads refuse c.compilationConsumerUnavailable with the
  responsible rebased TU. This is staged REFUSED/no applicable edits/affected files/
  lease/approval/verified claim, not the historical source capture-time preview.
  Refused-plan apply cannot write WAL even with caller-disabled diagnostics.
  Numeric/lookalike ordinary defines stay admitted. Separate required define-path
  policy/input digest bind preview and retained apply; missing/downgraded/tampered
  bindings require fresh preview pre-WAL. Core and previous source/define/paste/
  trigraph/resource/newline/moved-path identities/digests/receipts stay intact.
  Three exercised joined FILE/separated BASE_FILE/chained FILE_NAME cases observe
  compiler-valid string expansion changes after main.c→moved/entry.c. Unused moved
  builtin define and other-owner FILE define are containment only, not exercised
  expansion/runtime/branch changes. Overlay roots differ; observations bind source
  suffix/basename and responsible workspace TU, not workspace-valued macro strings.
  Only fixed configured hash-checked Clang syntax checks execute, never captured
  database commands or CMake code. Approved control applies and rolls back exact
  source/manifest/database bytes and snapshot. No complete flag/CMake/path/directive/
  encoding/generated/header-mediated expansion/preprocessing/consumer/source-
  equivalence, independent acceptance or release qualification is implied.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08/C15); integrated narrative:
  docs/arc42/appendix-requirements.adoc. Requirement remains partial.

  @REQ-C-CMAKE-PATH-DEFINE-001#a-cmake-project-with-state-compiler-path-defines-has-disposition-authority
  Scenario Outline: A CMake project with <state> compiler path defines has <disposition> authority
    Given an isolated CMake compiler-path-define fixture in state "<state>"
    When that compiler-path-define fixture previews its main translation unit move
    Then its managed compiler-path-define disposition is "<disposition>"
    And its retained compiler-path-define policy permits exact rollback or no WAL

    Examples:
      | state                          | disposition    |
      | ordinary-define-path-trivia     | applicable     |
      | joined-file-alias              | refused-staged |
      | separated-base-alias           | refused-staged |
      | chained-file-name-alias        | refused-staged |
      | unused-moved-builtin-define    | refused-staged |
      | unused-other-owner-file-define | refused-staged |
