# language: en
@functional-requirement @partial
Business Need: Preserve compiler-proven callable imports while removing unused type imports in Kotlin organize-imports
  Kotlin organize-imports must keep every compiler-proven top-level callable import that is actually used,
  while dropping unused type imports that share the same contiguous import block.
  A callable import is proven used through K2 compiler evidence and must not be removed merely because it is
  not a type import. Unused type imports are dropped; proven callable imports are preserved and sorted with the
  retained imports, and the preview must not introduce a compiler error.
  Status: @partial pending the independent requirements-quality-reviewer PASS. Linux
  source-built Cucumber GREEN exists but no independent PASS is recorded, so this
  feature is not promoted to @implemented-and-validated.

  @REQ-KOTLIN-JVM-ORGANIZE-IMPORTS-CALLABLE-001
  Scenario: A used top-level callable import is preserved while an unused type import is removed
    Given a Kotlin JVM file imports the used top-level callable "fixture.api.greeting" and the unused type "fixture.api.Unused" in one contiguous import block
    And the callable "fixture.api.greeting" is compiler-proven used in that file
    And the type "fixture.api.Unused" has no reference in that file
    When organize-imports previews that Kotlin JVM file
    Then the import "fixture.api.Unused" is removed from the contiguous import block
    And the import "fixture.api.greeting" is preserved in the contiguous import block
    And the retained imports are sorted
    And no compiler error is introduced by the preview
