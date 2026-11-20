@functional-requirement
Feature: Bounded atomic Java batch cleanup
  Requirement: REQ-JAVA-IDE-ENGINE-OPERATIONS-001, a bounded acceptance slice of the existing P1 IDE engine backlog.
  ARC42 owns integrated status and release qualification: docs/arc42/appendix-requirements.adoc.
  A batch may compose only independently supported low-risk Java operations on distinct saved files.
  It is one previewable managed patch and refuses in full when its input is stale, overlapping,
  unsupported, ambiguous, or contains a high-risk operation.

  @REQ-JAVA-IDE-ENGINE-OPERATIONS-001#selected-eclipse-profile-is-attested-for-a-saved-source-cleanup-batch
  Scenario Outline: Selected Eclipse profile is attested for a saved source cleanup batch
    Given two saved Java files and a selected Eclipse profile for cleanup case "<case>"
    When the daemon previews that cleanup batch and receives "<approval>" profile approval
    Then the cleanup batch returns "<outcome>" with profile identity and exact saved images
    And the cleanup batch preserves other inputs and expected transaction history

    Examples:
      | case                | approval | outcome                                  |
      | two formatters      | matching | applied and rolled back                  |
      | format plus imports | matching | applied and rolled back                  |
      | two formatters      | missing  | java.formatterProfile.approvalMissing     |
      | two formatters      | changed  | java.formatterProfile.identityStale       |
      | invalid XML         | matching | java.batchCleanup.constituentRefused      |
      | organizers only     | matching | java.batchCleanup.formatterProfileUnused  |

  @REQ-JAVA-IDE-ENGINE-OPERATIONS-001#preview-and-apply-a-low-risk-java-cleanup-batch-atomically
  Scenario Outline: Preview and apply a low-risk Java cleanup batch atomically
    Given an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context
    When the daemon previews the bounded batchCleanup request for "<case>" using "<snapshotState>"
    Then the batchCleanup result is "<expectedStatus>" with refusal "<expectedRefusalCode>" and no partial changes
    And applying and rolling back the accepted batchCleanup restores the exact workspace

    Examples:
      | case                                  | snapshotState | expectedStatus | expectedRefusalCode                 |
      | two-disjoint-import-organizers        | current       | PREVIEW        | none                                 |
      | duplicate-organizer-target            | current       | REFUSED        | java.batchCleanup.targetsOverlap     |
      | organizer-plus-visibility-widening    | current       | REFUSED        | java.batchCleanup.constituentHighRisk|
      | two-disjoint-import-organizers        | stale         | REFUSED        | java.batchCleanup.snapshotStale      |
      | organizer-plus-safe-delete            | current       | REFUSED        | java.batchCleanup.constituentUnsupported |
