# language: en
@not-implemented
Ability: Relocate a TypeScript source file to a new file path with compiler-proven import and export updates through the exact getEditsForFileRename authority
  As a RefactorKit caller on a TypeScript workspace
  I need the TypeScript source-file relocation preview to rename one source file to a new
    file path and to apply compiler-proven import and export updates across the project
    through the exact getEditsForFileRename authority
  So that source-file relocations are previewable and auditable and unsafe or invalid ones
    fail closed

  This executable backlog covers row T5-R1 of the approved v0.7.0-plan TypeScript section
  (REQ-TS-SOURCE-FILE-RELOCATION-001): the TypeScript operation sourceFileRelocation must
  accept one recognized non-generated TypeScript source file, send getEditsForFileRename
  with the exact old and new file paths to the pinned typescript 5.9.3 compiler server, and
  apply the returned FileRenameEdit as a PREVIEW patch plan. The successful plan renames the
  source file and applies compiler-proven import and export updates across the project, so
  the affected-file set lists the renamed source file and every file whose import or export
  declarations the compiler updated. The plan carries confidence 1.0, requiresUserApproval
  true, risk LOW, and evidence COMPILER_PROVEN, with exactly one FileEdit.Rename for the
  source file and one FileEdit.Modify per import or export updated file.

  RED-contract (truthful, anti-fake): this feature asserts the TARGET behavior the
  TypeScript adapter must deliver, not current production internals. The current
  TypeScriptMoveSymbolPlanner uses an invented getRefactorEdits LSP method shape, which is
  prototype or negative evidence and does not implement the real getEditsForFileRename
  path. The feature-level status is @not-implemented until a Cucumber runner and glue
  validate the scenarios against a real pinned typescript 5.9.3 compiler server and an
  independent requirements-quality review passes. The real protocol contract is asserted at
  a high level: the adapter sends getEditsForFileRename with the exact old and new file
  paths and applies the returned FileRenameEdit. No invented LSP shapes are asserted, and
  getRefactorEdits is never used. Refusals are deterministic REFUSED patch plans carrying
  real message text in the summary and warnings; there is no typed refusal code field.

  # Scenario 1: successful source-file relocation preview through the exact getEditsForFileRename authority
  @REQ-TS-SOURCE-FILE-RELOCATION-001 @functional-requirement
  Scenario: A TypeScript source-file relocation is previewed with the exact getEditsForFileRename authority and a FileRenameEdit rename
    Given a TypeScript project snapshot that contains a recognized non-generated source file with an old file path
    And the TypeScript semantic adapter is running with the pinned typescript 5.9.3 compiler server
    And the compiler server is clean and available with no unreadable or stale project evidence
    When the caller requests a source-file relocation preview for that source file from its old file path to a new file path
    Then the adapter returns a PREVIEW patch plan for the operation sourceFileRelocation
    And the adapter sends getEditsForFileRename with the exact old and new file paths to the pinned typescript 5.9.3 compiler server
    And the plan applies the returned FileRenameEdit as the source-file rename
    And the plan has confidence 1.0
    And the plan requires user approval
    And the plan risk is LOW
    And the plan evidence is COMPILER_PROVEN
    And the plan carries a WorkspaceEdit with exactly one FileEdit.Rename for the source file
    And the plan summary names the old and new file paths

  # Scenario 2: compiler-proven import and export updates across the project
  @REQ-TS-SOURCE-FILE-RELOCATION-001 @functional-requirement
  Scenario: A TypeScript source-file relocation rewrites every compiler-proven import and export declaration across the project
    Given a TypeScript project snapshot whose source file is imported or exported by other files in the project
    And the compiler server returns FileRenameEdit updates that rewrite those import and export declarations
    When the caller requests a source-file relocation preview for that source file
    Then the adapter returns a PREVIEW patch plan for the operation sourceFileRelocation
    And the plan rewrites every import and export declaration across the project that the compiler proved must change
    And the plan affected-file set lists the renamed source file and each import or export updated file
    And the plan carries one FileEdit.Modify per import or export updated file
    And each FileEdit.Modify carries exactly the compiler-returned text edits
    And the plan does not modify files whose import or export declarations the compiler proved unchanged

  # Scenario 3: deterministic refusals as assertion data
  @REQ-TS-SOURCE-FILE-RELOCATION-001 @functional-requirement
  Scenario Outline: A source-file relocation request is refused deterministically with its real message "<message>"
    Given a TypeScript workspace for a source-file relocation preview under the condition <condition>
    When the caller requests a source-file relocation preview
    Then the adapter returns a REFUSED patch plan for the operation sourceFileRelocation
    And the refusal carries an empty WorkspaceEdit and an empty affected-file set
    And the refusal grants no approval and no managed-write eligibility
    And the refusal carries confidence 0.0 and risk HIGH
    And the refusal summary and warning carry the message "<message>"

    Examples:
      | message | condition |
      | Source file not found | the old file path is absent from the project snapshot |
      | TypeScript semantic adapter is not running | the semantic adapter has no active compiler-server session |
      | Relocation target collides with an existing file | the new file path already exists in the project snapshot |
      | TypeScript compiler server is unavailable or its evidence is not clean | the compiler server cannot be reached or its project evidence is stale or unclean |
