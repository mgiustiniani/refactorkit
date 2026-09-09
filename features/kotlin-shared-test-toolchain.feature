# Authority: test-infrastructure behavior only; no new refactoring or release qualification.
# See docs/testing.md and docs/arc42/08-crosscutting-concepts.adoc.
@operational-requirement
Feature: Borrow the pinned Kotlin test compiler without copying its artifacts
  Ordinary fixtures consume the existing Gradle-resolved compiler and runtime JARs
  as read-only inputs. Their cleanup owns only their temporary workspace, not those JARs.

  @REQ-KOTLIN-SHARED-TEST-TOOLCHAIN-001
  Scenario: An ordinary JVM fixture borrows compiler artifacts and preserves them during cleanup
    Given a Kotlin JVM file imports the used top-level callable "fixture.api.greeting" and the unused type "fixture.api.Unused" in one contiguous import block
    When organize-imports previews that Kotlin JVM file
    Then the fixture borrows its compiler artifacts directly from the configured test classpath
    And no compiler error is introduced by the preview
    And fixture cleanup leaves the borrowed compiler artifacts unchanged
