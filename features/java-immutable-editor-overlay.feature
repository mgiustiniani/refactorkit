@functional-requirement @REQ-JAVA-EDITOR-OVERLAY-001
Feature: SDK-bound immutable Java editor queries
  Authority: this Story; architecture: docs/arc42/appendix-requirements.adoc.
  @REQ-JAVA-EDITOR-OVERLAY-001#java-editor-queries-analyze-closed-sdk-overlays-or-refuse-stale-document-versions
  Scenario Outline: Java editor queries analyze closed SDK overlays or refuse stale document versions
    Given saved Java sources and an explicit SDK context with two unsaved editor documents
    When Java "<query>" reads the immutable overlay after "<event>"
    Then the Java overlay query reports "<outcome>" with exact document authority
    And Java overlay analysis leaves saved sources and transaction state unchanged
    Examples:
      | query           | event               | outcome                   |
      | hover           | current             | ready                     |
      | definition      | current             | ready                     |
      | references      | current             | ready                     |
      | documentSymbols | current             | ready                     |
      | hover           | older version       | java.overlay.versionStale |
      | hover           | same version changed| java.overlay.versionStale |
