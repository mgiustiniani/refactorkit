@functional-requirement @RPK-PKG-003 @REQ-JAVA-PACKAGE-MIGRATION-003
Feature: Direct Java package migration provider
  The bounded package migration is exposed as a typed Java operation, not as execution of an arbitrary recipe.
  Requirement source: docs/requirements/java-package-migration.md, RPK-PKG-003 and its user-authorized provider/API follow-up.

  Scenario: Direct Java package migration previews, applies once, refuses stale plans, and rolls back exactly
    Given a two-module Maven package-migration workspace with package-info, tests, external consumers, and generated sources
    When I exercise typed movePackage preview, stale-plan refusal, fresh apply, and rollback through the daemon
    Then the direct provider returns a read-only preview with staged diagnostics and moves only exact-package non-generated units
    And operation discovery agrees with the stable Java movePackage route and does not expose arbitrary recipe execution

  @REQ-JAVA-PACKAGE-MIGRATION-003#move-package-preserves-unrelated-java-source-text
  Scenario: Move package preserves unrelated Java source text
    Given a Maven workspace has package references and unrelated occurrences of the same text
    When the daemon previews and applies its package migration
    Then the Java package move changes the owned code and only exact package or source-path literals
    And the package migration rollback restores all original paths and bytes
