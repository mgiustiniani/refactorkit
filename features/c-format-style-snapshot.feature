# language: en
@functional-requirement @partial
Feature: Use snapshot-bound style for C formatting
  clang-format must use the nearest workspace style recorded in the project snapshot.
  A changed, omitted, or indirect live style cannot authorize a preview. A workspace
  with no style file uses the explicit LLVM fallback rather than an ambient file.
  Conflicting style filenames in the nearest directory also refuse before launch.
  InheritParentConfig cannot import style authority from outside the snapshot.
  Full style inheritance, range behavior and managed apply remain separate acceptance.
  Authority: docs/releases/v0.8.0-plan.md (C07); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-FORMAT-STYLE-SNAPSHOT-001#the-c-format-preview-uses-captured-style-or-refuses-unbound-live-style
  Scenario Outline: The C format preview uses captured style or refuses unbound live style
    Given a scanned C formatting workspace with "<style_state>" style evidence
    When the managed C format preview is requested
    Then the C formatter style decision is "<disposition>"
    And the formatter style check preserves workspace inputs and leaves no transaction journal

    Examples:
      | style_state                              | disposition |
      | the captured project style is current   | preview     |
      | the captured project style changed      | refused     |
      | the live style file was omitted          | refused     |
      | the project style is a symbolic link     | refused     |
      | the workspace has no project style file | preview     |

  @REQ-C-FORMAT-STYLE-SNAPSHOT-001#conflicting-nearest-c-style-files-refuse-before-formatter-launch
  Scenario: Conflicting nearest C style files refuse before formatter launch
    Given a scanned C formatting workspace with "both project style filenames are present" style evidence
    When the managed C format preview is requested
    Then the C formatter style decision is "refused"
    And the formatter style check preserves workspace inputs and leaves no transaction journal

  @REQ-C-FORMAT-STYLE-SNAPSHOT-001#inherited-parent-c-style-refuses-before-formatter-launch
  Scenario: Inherited parent C style refuses before formatter launch
    Given a scanned C formatting workspace with "the captured project style inherits parent configuration" style evidence
    When the managed C format preview is requested
    Then the C formatter style decision is "refused"
    And the formatter style check preserves workspace inputs and leaves no transaction journal
