# language: en
@implemented-and-validated
Ability: Rename a signed Java method exactly with JDT binding evidence across its override family and refuse invalid, generated, and unresolvable requests with deterministic real messages
  As a RefactorKit caller on a Java workspace
  I need the Java renameMember preview with a signed member selector to rename the exact method signature and its override family using JDT source ranges and to refuse unsupported requests with a deterministic real message
  So that exact signed method renames are previewable and auditable and unsafe or invalid ones fail closed

  This executable backlog covers row C-RENAME-METHOD of the approved finite J1 Java catalogue
  (REQ-JAVA-J1-CATALOGUE-APPROVED-001): the Java operation renameMember must accept a signed
  method selector, one that contains a left parenthesis, and produce a PREVIEW patch plan for
  the operation renameMember. The supported shape is a method whose owner type is non-generated,
  whose JDT semantic analysis is clean, and whose signed selector resolves to exactly one JDT
  method candidate with a JDT binding key. The successful plan carries confidence 0.93 when the
  method is a single declaration, or 0.91 when it is part of an override family with more than
  one declaration, requiresUserApproval true, evidence JDT_BINDING, and risk LOW for a single
  declaration or MEDIUM for an override family. The plan lists every declaration file and
  referencing file in its affected-file set and carries a WorkspaceEdit whose edits are
  generated from exact JDT declaration and reference ranges. The plan always warns that the JDT
  binding selected the exact member signature and that edits were generated from JDT
  declaration and reference ranges, and it warns that reflection, Spring event or listener
  names, Jackson property names, and annotation processor output are NOT updated and require
  manual review. When overrides are detected, the plan adds a warning that override aware
  propagation selected the family declarations and their binding matched call sites across the
  inheritance hierarchy.

  This feature is scoped to the signed method and override family path implemented by
  previewSignedJdtRename. The general lexical renameMember path, which handles owner bound
  field renames through previewJdtFieldRename and the lexical fallback, is a SEPARATE catalogue
  row C-RENAME-FIELD and is NOT covered here. This feature stays focused on the signed method
  and override family behavior only.

  Characterization RED-deferral (truthful, anti-fake): production already implements the
  planner JavaRenameMemberPlanner in the Java adapter, so this feature begins GREEN and asserts
  the real behavior rather than inventing it. The planner emits refusals as real MESSAGE strings
  through a refused snapshot plan, not as typed refusal codes; there is no refusalCode field.
  Every refusal message asserted below is the exact message string the planner emits, and no
  invented renameMember.xxx typed code is introduced. The RED-deferral is pre-approved in the
  J1 catalogue because no Cucumber runner or glue existed for this row yet. The runner and
  glue are now synced and the scenarios validate GREEN 14/14 (evidence ad7b4eae, 101 steps),
  so the feature-level status is promoted to @implemented-and-validated. Independent
  requirements-quality review PASS is tracked separately by the workflow and is not yet
  obtained. If a first
  execution reveals a real mismatch, the row is reclassified as GENUINE RED per baseline
  section 2 and production is corrected. Each refusal is a REFUSED patch plan carrying its real
  message in the summary and warnings, an empty WorkspaceEdit and an empty affected-file set,
  confidence 0.0, risk HIGH, and no approval and no managed-write eligibility.

  # Scenario 1: successful signed JDT method rename preview as exact JDT ranges
  @REQ-JAVA-RENAME-METHOD-CHAR-001 @functional-requirement
  Scenario: A clean signed method rename is previewed as JDT source-range edits with LOW risk and confidence 0.93
    Given a Java workspace snapshot whose owner type declares exactly one signed method matching the requested selector
    And the JDT binding analysis is clean with no parse or classpath warnings
    And the signed member selector resolves to exactly one JDT method candidate with a JDT binding key
    And the method is not part of an override family with more than one declaration
    When the caller requests a renameMember preview with a signed member selector that contains a left parenthesis and a valid new method name
    Then the adapter returns a PREVIEW patch plan for the operation renameMember
    And the plan carries confidence 0.93
    And the plan requires user approval
    And the plan evidence is JDT_BINDING
    And the plan risk is LOW
    And the plan lists every declaration file and referencing file in its affected-file set
    And the plan carries a WorkspaceEdit whose edits are generated from exact JDT declaration and reference ranges
    And the plan warns that the JDT binding selected the exact member signature and that edits were generated from JDT declaration and reference ranges
    And the plan warns that reflection, Spring event or listener names, Jackson property names, and annotation processor output are NOT updated and require manual review

  # Scenario 2: an override family elevates risk to MEDIUM and adds the override-aware warning
  @REQ-JAVA-RENAME-METHOD-CHAR-001 @functional-requirement
  Scenario: A signed method that is part of an override family is previewed with MEDIUM risk, confidence 0.91, and an override-aware warning
    Given a Java workspace snapshot whose signed method is part of an override family with more than one declaration across the inheritance hierarchy
    And the JDT binding analysis is clean and resolves the override family
    And the signed member selector resolves to exactly one JDT method candidate with a JDT binding key
    When the caller requests a renameMember preview for that signed method selector
    Then the adapter returns a PREVIEW patch plan for the operation renameMember
    And the plan carries confidence 0.91
    And the plan requires user approval
    And the plan evidence is JDT_BINDING
    And the plan risk is MEDIUM
    And the plan warns that override aware propagation selected the family declarations and their binding matched call sites across the inheritance hierarchy
    And the plan summary reports the number of declarations and the number of affected files
    And the plan warns that reflection, Spring event or listener names, Jackson property names, and annotation processor output are NOT updated and require manual review

  # Scenario 3: refusal contract for every renameMember refusal
  @REQ-JAVA-RENAME-METHOD-CHAR-001 @functional-requirement
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

  # Scenario 4: finite real refusal message list as assertion data
  @REQ-JAVA-RENAME-METHOD-CHAR-001 @functional-requirement
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
      | Invalid Java identifier: 1rename | the caller supplies a new method name that is not a valid Java identifier |
      | Old and new names are the same: validate | the caller supplies a new method name identical to the old method name |
      | Owner type not found: com.example.Missing | the owner fully qualified name is absent from the symbol index or is not a recognized type |
      | Generated source cannot be rewritten: build/generated/sources/annotationProcessor/java/main/Generated.java (path is inside a generated-source or build-output location) | the owner declaration path is inside a generated-source or build-output location |
      | Generated source cannot be rewritten: Generated.java (source declares a generated-code annotation) | the owner declaration source declares a generated-code annotation |
      | Generated source cannot be rewritten: Generated.java (source header identifies generated code) | the owner declaration source header identifies generated code |
      | Member 'validate' not found in com.example.UserService | no member with the old method name is declared in the owner type |
      | Signed member rename for com.example.UserService#validate(java.lang.String) requires clean JDT semantic evidence; 1 parse/classpath warning(s) were reported. | the JDT semantic analysis reports one parse or classpath warning |
      | Signed member selector com.example.UserService#validate(java.lang.String) belongs to an override family containing declarations outside the scanned source workspace; source-only propagation is unsafe. | the override family contains declarations outside the scanned source workspace |
