# language: en
@implemented-and-validated
Ability: Move one complete non-generated Java source root with rename-only edits and deterministic typed refusals
  As a RefactorKit caller on a Java workspace
  I need the Java moveSourceRoot preview to relocate one complete non-generated source root
    as a rename-only edit set while preserving package identity and to refuse unsupported
    moves with a deterministic typed refusal
  So that safe root relocations are previewable and auditable and unsafe ones fail closed

  This executable backlog covers row C-ROOT of the approved finite J1 Java catalogue
  (REQ-JAVA-J1-CATALOGUE-APPROVED-001): the Java operation moveSourceRoot must accept one
  complete non-generated source root, produce a rename-only FileEdit.Rename set, keep package
  declarations, imports, fully qualified type names, and source bytes unchanged, and carry
  risk LOW or MEDIUM by owner, confidence 1.0, requiresUserApproval true, and evidence
  STRUCTURAL, with the affected source and destination paths reported. The refusal contract
  is a deterministic REFUSED patch plan carrying a stable typed refusal code, an empty
  WorkspaceEdit and an empty affected-file set, no approval and no managed-write eligibility,
  and no pending actionable plan. Refusal codes are asserted as Examples table data below,
  not as narrative prose.

  Characterization RED-deferral (truthful, anti-fake): production already implements the
  planner JavaMoveSourceRootPlanner in the Java adapter, so this feature begins GREEN and
  asserts the real behavior rather than inventing it. The RED-deferral is pre-approved in the
  catalogue because no Cucumber runner, glue, or independent requirements-quality review
  exists for this row yet; the feature-level status is @not-implemented until a runner and
  glue validate the scenarios and an independent reviewer passes. Only real production codes
  are asserted; no invented granular codes are introduced.

  # Scenario 1: successful complete non-generated root move as rename-only edits
  @REQ-JAVA-MOVE-SOURCE-ROOT-CHAR-001 @functional-requirement
  Scenario: A complete non-generated Java source root move is previewed as rename-only edits with unchanged package identity
    Given a Java workspace whose build model owns one recognized non-generated main source root
    And the source root contains Java compilation units whose package declarations match the root path
    When the caller requests a moveSourceRoot preview from that source root to another recognized non-generated root of the same source-set kind
    Then the adapter returns a PREVIEW patch plan for the operation moveSourceRoot
    And the plan carries a rename-only WorkspaceEdit whose entries are all FileEdit.Rename
    And the plan declares package declarations, imports, fully qualified type names, and source bytes unchanged
    And the plan has confidence 1.0, requires user approval, and evidence STRUCTURAL
    And the plan risk is LOW when source and destination share one module and provider, or MEDIUM otherwise
    And the plan lists every affected source path and destination path

  # Scenario 2: deterministic typed refusal codes as assertion data
  @REQ-JAVA-MOVE-SOURCE-ROOT-CHAR-001 @functional-requirement
  Scenario Outline: A moveSourceRoot request is refused deterministically with its real typed code
    Given a Java workspace with a recognized non-generated main source root
    When the caller requests a moveSourceRoot preview under the condition <condition>
    Then the adapter returns a REFUSED patch plan carrying the typed refusal code <code>
    And the refusal grants no approval and no managed-write eligibility
    And the refusal leaves an empty WorkspaceEdit, an empty affected-file set, and no pending actionable plan

    Examples:
      | code | condition |
      | sourceRoot.missing | the source is not a safe workspace-relative recognized source root |
      | sourceRoot.destinationUnrecognized | the destination is not owned by exactly one recognized or prospective source root of the same source-set kind |
      | sourceRoot.overlap | the source and destination roots overlap |
      | sourceRoot.generated | the source or destination root is generated and read-only |
      | sourceRoot.symlinkEscape | a root path traverses a symbolic link |
      | sourceRoot.packageMismatch | a compilation unit package declaration does not match its root-relative path |
      | sourceRoot.duplicateType | the relocation would retain a duplicate fully qualified type name |
      | sourceRoot.destinationCollision | a destination path collides with an existing or folded path |
      | sourceRoot.diagnosticsRegression | the relocation introduces a new compiler diagnostic |
      | buildModel.unavailable | post-image diagnostics cannot be produced |
      | classpath.unavailable | a regression diagnostic reports an unavailable classpath |
