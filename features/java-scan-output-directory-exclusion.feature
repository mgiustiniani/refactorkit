# language: en
# Authority: bounded scanner behavior; integrated status belongs to docs/arc42/appendix-requirements.adoc.
@functional-requirement @partial
Feature: Exclude build output project copies from Java workspace discovery
  Build output directories may contain complete copies of other projects.
  Those copies must not become workspace modules, source files, or build evidence.
  Materialized generated sources belonging to the real module remain visible.

  @REQ-JAVA-SCAN-OUTPUT-001 @java-scan-output-directory-exclusion
  Scenario Outline: Build output copies do not become workspace modules
    Given a Java workspace with a real module and a copied project under "<output directory>"
    When Java discovery scans the workspace before and after the copied project changes
    Then discovery retains only the real module and its materialized generated sources
    And changes inside the build output copy leave the workspace snapshot and build evidence unchanged

    Examples:
      | output directory |
      | build            |
      | target           |
      | module-a/build   |
      | module-a/target  |
