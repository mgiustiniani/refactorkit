# language: en
@functional-requirement @partial
Feature: Captured single-TU CMake previews show compiler diagnostics before approval
  The admitted C11/C17 move must evaluate its original and staged source with
  the same captured, hash-bound compilation context before returning a preview.
  A compiler error refuses the preview; an unavailable or unmodeled context is
  never reported as clean. The approved plan binds the captured database and
  compiler identities through an operation-authority lease.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-PREVIEW-DIAGNOSTICS-001
  Scenario Outline: A <source> CMake move has <disposition> before approval
    Given an isolated CMake single-TU project with a "<source>" C source
    When the library previews the C source move with an exact compilation database
    Then the preview is "<disposition>" with explicit staged compiler evidence
    And the preview leaves original source manifest and database bytes unchanged

    Examples:
      | source | disposition |
      | clean  | preview     |
      | broken | refused     |
