# language: en
@functional-requirement @java @daemon @read-only @implemented-and-validated
Business Need: Unsaved Java declarations remain navigable under explicit SDK authority
  A dirty editor workspace must resolve type and direct-call relationships from
  the closed overlay while preserving the saved snapshot and reporting overlay provenance.

  @REQ-JAVA-SEMANTIC-NAVIGATION-OVERLAY-001#java-type-and-call-navigation-use-the-closed-sdk-bound-overlay
  Scenario: Java type and call navigation use the closed SDK-bound overlay
    Given saved Java sources and an explicit SDK context with two unsaved editor documents
    When I query Java types and calls through the immutable editor overlay
    Then all three navigation queries resolve dirty source relationships with overlay provenance
    And Java overlay analysis leaves saved sources and transaction state unchanged
