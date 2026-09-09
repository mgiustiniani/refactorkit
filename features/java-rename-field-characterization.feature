# language: en
@partial
Ability: Rename an owner-bound Java field with exact JDT binding evidence, fall back to a lexical rename when JDT evidence is unclean, and refuse invalid, generated, and conflicting requests with deterministic real messages
  As a RefactorKit caller on a Java workspace
  I need the Java renameMember preview to rename an owner-bound field using exact JDT declaration and reference ranges or a documented lexical fallback and to refuse unsupported requests with a deterministic real message
  So that owner-bound field renames are previewable and auditable and unsafe or invalid ones fail closed

  This executable backlog covers row C-RENAME-FIELD of the approved finite J1 Java catalogue
  (REQ-JAVA-J1-CATALOGUE-APPROVED-001): the Java operation renameMember must accept an
  owner-bound field selector, one that names a field of a non-generated owner type, and
  produce a PREVIEW patch plan for the operation renameMember. The supported shape is a
  field whose JDT semantic analysis is clean and whose selector resolves to exactly one JDT
  field candidate with a JDT binding key. That successful plan carries confidence 0.95,
  requiresUserApproval true, evidence JDT_BINDING, risk LOW, an affected-file set listing
  the field declaration file and every binding-matched referencing file, and a WorkspaceEdit
  whose edits are generated from exact JDT field declaration and reference ranges. The plan
  always warns that the JDT binding selected the exact field and that edits were generated
  from JDT declaration and reference ranges, and it warns that reflection, serialization
  names, framework strings, and annotation-processor output are NOT updated and require
  manual review.

  When JDT binding evidence is unavailable or not clean, the same renameMember operation
  falls back to the documented lexical rename path. That fallback carries confidence 0.88,
  requiresUserApproval true, evidence LEXICAL_FALLBACK, risk LOW for a single member or
  MEDIUM when more than one same-named member is detected, and an affected-file set derived
  from JavaLexer occurrences of the old member name in scope of the owner type. The fallback
  warns that reflection, Spring event or listener names, Jackson property names, and
  annotation-processor output are NOT updated and require manual review, and it adds a
  warning that all overloads will be renamed when more than one same-named member is
  detected.

  This feature is scoped to the owner-bound field path implemented by previewJdtFieldRename
  and the general lexical renameMember fallback for fields. The signed method and override
  family path implemented by previewSignedJdtRename is a SEPARATE catalogue row
  C-RENAME-METHOD and is NOT covered here. This feature stays focused on FIELD rename only.

  Characterization RED-deferral (truthful, anti-fake): production already implements the
  planner JavaRenameMemberPlanner in the Java adapter, so this feature begins GREEN and
  asserts the real behavior rather than inventing it. The planner emits refusals as real
  MESSAGE strings through a refused snapshot plan, not as typed refusal codes; there is no
  refusalCode field. Every refusal message asserted below is the exact message string the
  planner emits, and no invented renameMember.xxx typed code is introduced. The RED-deferral
  is pre-approved in the J1 catalogue because no Cucumber runner or glue existed for this
  row yet. The existing runner and glue exercise the real planner. Exact authored ranges
  and complete post-image oracles are being strengthened after review; the feature remains
  at status @partial pending independent acceptance. If execution reveals a real
  mismatch, the row is reclassified as GENUINE RED per baseline section 2 and production is
  corrected. Each refusal is a REFUSED patch plan carrying its real message in the summary
  and warnings, an empty WorkspaceEdit and an empty affected-file set, confidence 0.0, risk
  HIGH, and no approval and no managed-write eligibility.

  # Scenario 1: successful JDT owner-bound field rename preview as exact JDT ranges
  @REQ-JAVA-RENAME-FIELD-CHAR-001 @functional-requirement
  Scenario: A clean owner-bound field rename is previewed as exact JDT field source-range edits with LOW risk and confidence 0.95
    Given a Java workspace snapshot whose owner type declares exactly one field matching the requested member name
    And the JDT binding analysis is clean with no parse or classpath warnings
    And the field selector resolves to exactly one JDT field candidate with a JDT binding key
    When the caller requests a renameMember preview for that owner-bound field with a valid new field name
    Then the adapter returns a PREVIEW patch plan for the operation renameMember
    And the plan carries confidence 0.95
    And the plan requires user approval
    And the plan evidence is JDT_BINDING
    And the plan risk is LOW
    And the plan lists the field declaration file and every binding-matched referencing file in its affected-file set
    And the plan carries a WorkspaceEdit whose edits are generated from exact JDT field declaration and reference ranges
    And the plan warns that the JDT binding selected the exact field and that edits were generated from JDT declaration and reference ranges
    And the plan warns that reflection, serialization names, framework strings, and annotation-processor output are NOT updated and require manual review
    And the plan summary names the field, the owner type, and the number of affected files

  # Scenario 2: lexical fallback for an owner-bound field when JDT evidence is unclean
  @REQ-JAVA-RENAME-FIELD-CHAR-001 @functional-requirement
  Scenario: An owner-bound field rename falls back to a lexical rename when JDT binding evidence is unavailable or not clean
    Given a Java workspace snapshot whose owner type declares exactly one field matching the requested member name
    And the JDT binding analysis is unavailable or not clean for that field
    When the caller requests a renameMember preview for that owner-bound field with a valid new field name
    Then the adapter returns a PREVIEW patch plan for the operation renameMember
    And the plan carries confidence 0.88
    And the plan requires user approval
    And the plan evidence is LEXICAL_FALLBACK
    And the plan risk is LOW
    And the plan affected-file set is derived from JavaLexer occurrences of the old member name in scope of the owner type
    And the plan warns that reflection, Spring event or listener names, Jackson property names, and annotation-processor output are NOT updated and require manual review
    And the plan summary names the field kind, the owner type, and the number of affected files

  # Scenario 3: multiple same-named members elevate the lexical fallback risk to MEDIUM
  @REQ-JAVA-RENAME-FIELD-CHAR-001 @functional-requirement
  Scenario: An owner-bound member rename with multiple same-named members is previewed with MEDIUM risk, confidence 0.88, and an overload warning
    Given a Java workspace snapshot whose owner type declares more than one member matching the requested member name
    And the JDT binding analysis is unavailable or not clean for that member
    When the caller requests a renameMember preview for that member with a valid new name
    Then the adapter returns a PREVIEW patch plan for the operation renameMember
    And the plan carries confidence 0.88
    And the plan requires user approval
    And the plan evidence is LEXICAL_FALLBACK
    And the plan risk is MEDIUM
    And the plan warns that multiple overloads of the old member name were detected and that all overloads will be renamed
    And the plan warns that reflection, Spring event or listener names, Jackson property names, and annotation-processor output are NOT updated and require manual review

  # Scenario 4: refusal contract for every renameMember refusal
  @REQ-JAVA-RENAME-FIELD-CHAR-001 @functional-requirement
  Scenario: A renameMember refusal is a REFUSED plan with no edit, no affected file, no approval, confidence 0.0 and risk HIGH
    Given the Java adapter returns a REFUSED patch plan for a renameMember preview
    When the caller inspects the refusal plan
    Then the refusal operation name is deterministically renameMember
    And the refusal carries an empty WorkspaceEdit
    And the refusal carries an empty affected-file set
    And the refusal grants no approval and no managed-write eligibility
    And the refusal carries confidence 0.0 and risk HIGH
    And the refusal carries its real reason message in both the summary and the warnings
    And the refusal carries no typed refusal code because refusals are message-based

  # Scenario 5: finite real refusal message list as assertion data
  @REQ-JAVA-RENAME-FIELD-CHAR-001 @functional-requirement
  Scenario Outline: A renameMember request is refused deterministically with its real refusal message "<message>"
    Given a Java workspace snapshot for a renameMember preview under the condition <condition>
    When the caller requests a renameMember preview
    Then the adapter returns a REFUSED patch plan for the operation renameMember
    And the refusal summary and warning carry the message "<message>"
    And the refusal carries an empty WorkspaceEdit and an empty affected-file set
    And the refusal grants no approval and carries confidence 0.0 with risk HIGH

    Examples:
      | message | condition |
      | Symbol must be in the form <FQN>#<member> — got: com.example.UserService | the caller supplies a symbol with no hash separator between the fully qualified owner and the member |
      | Constructor rename follows class rename. Use renameClass instead. | the member selector is the constructor pseudo name init |
      | Invalid Java identifier: 1rename | the caller supplies a new member name that is not a valid Java identifier |
      | Old and new names are the same: amount | the caller supplies a new member name identical to the old member name |
      | Owner type not found: com.example.Missing | the owner fully qualified name is absent from the symbol index or is not a recognized type |
      | Generated source cannot be rewritten: build/generated/sources/annotationProcessor/java/main/Generated.java (path is inside a generated-source or build-output location) | the owner declaration path is inside a generated-source or build-output location |
      | Generated source cannot be rewritten: Generated.java (source declares a generated-code annotation) | the owner declaration source declares a generated-code annotation |
      | Generated source cannot be rewritten: Generated.java (source header identifies generated code) | the owner declaration source header identifies generated code |
      | Member 'amount' not found in com.example.UserService | no member with the old member name is declared in the owner type |
      | Field target already exists: com.example.UserService#total | the owner type already declares a field with the new member name |
