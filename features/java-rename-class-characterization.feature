# language: en
@partial
Ability: Rename a Java class, interface, enum, or record type exactly with JDT binding evidence and refuse invalid, generated, and conflicting requests with deterministic real messages
  As a RefactorKit caller on a Java workspace
  I need the Java renameClass preview to rename a renameable type across its declaration, constructors, file, imports, and references using exact JDT source ranges and to refuse unsupported requests with a deterministic real message
  So that exact type renames are previewable and auditable and unsafe or invalid ones fail closed

  This executable backlog covers row C-RENAME-TYPE of the approved finite J1 Java catalogue
  (REQ-JAVA-J1-CATALOGUE-APPROVED-001): the Java operation renameClass must accept a
  renameable type and produce a PREVIEW patch plan for the operation renameClass. The
  supported shape is a Java class, interface, enum, record, or annotation whose declaration
  file is non-generated and whose JDT binding is clean. The successful plan carries
  confidence 0.96, requiresUserApproval true, evidence JDT_BINDING, an affected-file set
  listing the declaration file, every referencing file, and the new file, and a WorkspaceEdit
  that combines the JDT source-range modifications with one FileEdit.Rename that moves the
  declaration file to the new file name. The plan risk is LOW by default, MEDIUM when more
  than ten files are affected, and HIGH when the declaration carries framework annotations.
  The plan always warns that string literals and comments are NOT scanned and that
  reflection and annotation processor output require manual review.

  Characterization RED-deferral (truthful, anti-fake): production already implements the
  planner JavaRenameClassPlanner in the Java adapter, so this feature begins GREEN and
  asserts the real behavior rather than inventing it. The RED-deferral is pre-approved in
  the J1 catalogue because no Cucumber runner, glue, or independent requirements-quality
  review existed for this row yet. The historical GREEN 14 of 14 (evidence 0df18185,
  101 steps) is retained, but does not substitute for independent exact-edit verification.
  The feature remains @partial while authored ranges and complete post-image oracles are
  strengthened and independently reviewed. If execution reveals a real mismatch, the row is reclassified as GENUINE RED
  per baseline
  section 2 and production is corrected. The planner emits refusals as real MESSAGE strings
  through a refused snapshot plan, not as typed refusal codes; there is no refusalCode
  field. Every refusal message asserted below is the exact message string the planner
  emits, and no invented renameClass.xxx typed code is introduced. Warnings are asserted
  as real warning text, not as narrative prose.

  # Scenario 1: successful exact JDT type rename preview
  @REQ-JAVA-RENAME-CLASS-CHAR-001 @functional-requirement
  Scenario: A clean JDT type rename is previewed as JDT source-range edits plus one FileEdit.Rename with LOW risk
    Given a Java workspace snapshot that contains a recognized non-generated Java type declaration file and at least one referencing Java file
    And the JDT binding analysis is clean and resolves the type binding with no error warnings
    And the rename touches fewer than eleven files and the declaration carries no framework annotation
    When the caller requests a renameClass preview from the current fully qualified type name to a valid new simple name
    Then the adapter returns a PREVIEW patch plan for the operation renameClass
    And the plan carries confidence 0.96
    And the plan requires user approval
    And the plan evidence is JDT_BINDING
    And the plan risk is LOW
    And the plan lists the declaration file, every referencing file, and the new file in its affected-file set
    And the plan carries a WorkspaceEdit that combines the JDT source-range modifications and one FileEdit.Rename from the declaration file to the new file name
    And the plan warns that the JDT type binding was selected and that declaration, constructor, and reference edits use exact JDT source ranges
    And the plan warns that string literals and comments are NOT scanned and that reflection and annotation processor output require manual review

  # Scenario 2: framework annotation findings elevate risk to HIGH and add framework warnings
  @REQ-JAVA-RENAME-CLASS-CHAR-001 @functional-requirement
  Scenario Outline: A renameClass declaration carrying a <framework> annotation is previewed with HIGH risk and framework warnings
    Given a Java workspace snapshot whose rename declaration file carries a <framework> annotation
    And the JDT binding analysis is clean for that declaration
    When the caller requests a renameClass preview for that declaration
    Then the adapter returns a PREVIEW patch plan for the operation renameClass
    And the plan risk is HIGH
    And the plan carries a warning that names the <framework> framework and the detected annotation
    And the plan warns about the framework annotation locations

    Examples:
      | framework | annotation |
      | Spring    | @Component |
      | JPA       | @Entity    |
      | Jackson   | @JsonProperty |

  # Scenario 3: a large rename elevates risk to MEDIUM with the large-rename warning
  @REQ-JAVA-RENAME-CLASS-CHAR-001 @functional-requirement
  Scenario: A renameClass preview touching more than ten files is previewed with MEDIUM risk and a large-rename warning
    Given a Java workspace snapshot whose rename declaration file carries no framework annotation
    And the JDT binding analysis is clean for that declaration
    And the rename touches twelve files across the declaration and its referencing files
    When the caller requests a renameClass preview for that declaration
    Then the adapter returns a PREVIEW patch plan for the operation renameClass
    And the plan risk is MEDIUM
    And the plan warns that the rename is large and reports that 12 files are affected

  # Scenario 4: JDT evidence unavailable falls back to a lexical rename
  @REQ-JAVA-RENAME-CLASS-CHAR-001 @functional-requirement
  Scenario: A renameClass preview falls back to a lexical rename when JDT binding evidence is unavailable or unclean
    Given a Java workspace snapshot that contains a recognized non-generated Java type declaration file and at least one referencing Java file
    And the JDT binding analysis reports an error warning or no usable binding for the type
    When the caller requests a renameClass preview from the current fully qualified type name to a valid new simple name
    Then the adapter returns a PREVIEW patch plan for the operation renameClass
    And the plan carries confidence 0.92
    And the plan requires user approval
    And the plan evidence is LEXICAL_FALLBACK
    And the plan warns that JDT type-binding evidence was unavailable or not clean and that the rename uses lexical fallback and needs careful review
    And the plan warns that string literals and comments are NOT scanned and that reflection and annotation processor output require manual review

  # Scenario 5: refusal contract for every renameClass refusal
  @REQ-JAVA-RENAME-CLASS-CHAR-001 @functional-requirement
  Scenario: A renameClass refusal is a REFUSED plan with no edit, no affected file, no approval, confidence 0.0 and risk HIGH
    Given the Java adapter returns a REFUSED patch plan for a renameClass preview
    When the caller inspects the refusal plan
    Then the refusal operation name is deterministically renameClass
    And the refusal carries an empty WorkspaceEdit
    And the refusal carries an empty affected-file set
    And the refusal grants no approval and no managed-write eligibility
    And the refusal carries confidence 0.0 and risk HIGH
    And the refusal carries its real reason message in both the summary and the warnings
    And the refusal carries no typed refusal code because refusals are message-based

  # Scenario 6: finite real refusal message list as assertion data
  @REQ-JAVA-RENAME-CLASS-CHAR-001 @functional-requirement
  Scenario Outline: A renameClass request is refused deterministically with its real refusal message "<message>"
    Given a Java workspace snapshot for a renameClass preview under the condition <condition>
    When the caller requests a renameClass preview
    Then the adapter returns a REFUSED patch plan for the operation renameClass
    And the refusal summary and warning carry the message "<message>"
    And the refusal carries an empty WorkspaceEdit and an empty affected-file set
    And the refusal grants no approval and carries confidence 0.0 with risk HIGH

    Examples:
      | message | condition |
      | Invalid Java identifier: 1rename | the caller supplies a new simple name that is not a valid Java identifier |
      | Old and new type names are the same: UserService | the caller supplies a new simple name identical to the old simple name |
      | Symbol not found or not a renameable type: com.example.Missing | the symbol is absent from the index or is not a renameable type |
      | Generated source cannot be rewritten: build/generated/sources/annotationProcessor/java/main/Generated.java (path is inside a generated-source or build-output location) | the declaration path is inside a generated-source or build-output location |
      | Generated source cannot be rewritten: Generated.java (source declares a generated-code annotation) | the declaration source declares a generated-code annotation |
      | Generated source cannot be rewritten: Generated.java (source header identifies generated code) | the declaration source header identifies generated code |
      | Rename target already exists: com.example.AccountService (src/main/java/com/example/AccountService.java) | the new fully qualified name or the new file path already exists |
