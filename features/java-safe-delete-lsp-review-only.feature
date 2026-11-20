# language: en
@implemented-and-validated
Ability: Keep a review-only Java safeDelete proposal out of client-managed LSP edits
  As an LSP client on a Java workspace
  I need RefactorKit to refuse a safeDelete proposal whose reference evidence is incomplete
  So that clients cannot apply an unqualified edit outside RefactorKit's managed safety gate

  @REQ-JAVA-SAFE-DELETE-WORKSPACE-TEXT-001#lsp-safedelete-refuses-a-review-only-maven-pom-proposal-without-returning-an-edit
  @safe-delete-lsp @functional-requirement
  Scenario: LSP safeDelete refuses a review-only Maven POM proposal without returning an edit
    Given a Maven project with a Java type referenced only by its root POM
    When an LSP client requests safeDelete for that Java type
    Then RefactorKit refuses the client-managed edit with error code "PLAN_REFUSED"
    And the workspace remains unchanged and no client workspace edit is returned
