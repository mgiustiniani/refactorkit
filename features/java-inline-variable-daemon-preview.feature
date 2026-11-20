# language: en
@implemented-and-validated
Ability: Dispatch bounded Java inline operations through managed daemon preview
  As a Java IDE client
  I want advertised inline operations to reach their compiler-backed planners
  So that capability-gated preview and rollback work through the production daemon

  @REQ-JAVA-INLINE-VARIABLE-LITERAL-001#daemon-inline-variable-preview-apply-and-rollback @functional-requirement
  Scenario: Inline a bound local integer literal through daemon preview
    Given a clean Maven fixture with a local literal and a static final integer constant
    When the daemon previews inlineVariable for the selected local name "base"
    Then inlineVariable is advertised, applied, and rolled back through the daemon

  @REQ-JAVA-INLINE-CONSTANT-001#daemon-inline-constant-preview-apply-and-rollback @functional-requirement
  Scenario: Inline a bound static final integer literal through daemon preview
    Given a clean Maven fixture with a local literal and a static final integer constant
    When the daemon previews inlineConstant for the selected constant name "OFFSET"
    Then inlineConstant is advertised, applied, and rolled back through the daemon
