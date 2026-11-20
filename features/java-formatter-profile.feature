# Architecture narrative: docs/arc42/appendix-requirements.adoc#JavaFormatterProfile
# Authority: selected Eclipse formatter profile identity and managed saved-source apply.
@functional-requirement
Feature: Bind the selected Eclipse formatter profile to saved Java formatting
  RefactorKit consumes the exact profile selected by the IDE and keeps its identity
  separate from the project snapshot used to generate a format preview.

  @REQ-JAVA-EDITOR-PREVIEW-001#explicit-eclipse-profile-binds-saved-format-preview-and-managed-apply
  Scenario: Explicit Eclipse profile binds saved format preview and managed apply
    Given a Java workspace and an Eclipse profile selected in the IDE
    When the saved Java formatter previews using the selected Eclipse profile
    Then the saved preview exposes the exact profile identity separately from the project snapshot
    And managed apply refuses missing or changed profile approval and accepts the matching hash

  @REQ-JAVA-EDITOR-PREVIEW-001#explicit-eclipse-profile-binds-dirty-editor-preview-and-confirmation
  Scenario: Explicit Eclipse profile binds dirty editor preview and confirmation
    Given a dirty Java editor workspace and a selected Eclipse profile
    When the editor previews Java formatting with that profile
    Then the dirty preview exposes the exact profile identity and binds it into the editor plan hash
    And editor prepare and confirm require the current matching profile hash
