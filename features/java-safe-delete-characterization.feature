# language: en
@implemented-and-validated
Ability: Delete a JDT-proven unused Java type with exact binding evidence, fall back to lexical evidence when JDT is unclean, force-delete with references, and refuse unsafe, invalid, and generated requests with deterministic real messages
  As a RefactorKit caller on a Java workspace
  I need the Java safeDelete preview to delete a JDT-proven unused type as a single declaration-file deletion and to refuse unsafe, invalid, and generated requests with a deterministic real message
  So that unused type deletions are previewable and auditable and unsafe or invalid ones fail closed

  This executable backlog covers row C-DELETE of the approved finite J1 Java catalogue
  (REQ-JAVA-J1-CATALOGUE-APPROVED-001): the Java operation safeDelete must accept a
  JDT-proven unused type and produce a PREVIEW patch plan for the operation safeDelete.
  The supported shape is a Java class, interface, enum, record, or annotation whose
  declaration file is non-generated and whose JDT binding is clean. The successful plan
  carries confidence 1.0, requiresUserApproval true, evidence JDT_BINDING, risk LOW by
  default, an affected-file set listing only the declaration file, and a WorkspaceEdit with
  exactly one FileEdit.Delete for that declaration file. The plan always reports the adapter
  diagnostics computed after the declaration file is removed from the snapshot, reports that
  captured project text inputs are searched for literal target names while dynamic references
  remain unresolved, and warns about framework annotation findings when present.

  Characterization RED-deferral (truthful, anti-fake): production already implements the
  planner JavaSafeDeletePlanner in the Java adapter, so this feature begins GREEN and
  asserts the real behavior rather than inventing it. The RED-deferral is pre-approved in
  the J1 catalogue. At intake, the row had no Cucumber runner, glue, or independent
  requirements-quality review. The current runner/glue now validate the scenarios,
  and r005 records bounded C-DELETE source acceptance; the existing feature tag is not
  final J1, native, or release acceptance. If an execution reveals a real mismatch,
  the row is reclassified as GENUINE RED per baseline
  section 2 and production is corrected. The planner emits refusals as real MESSAGE strings
  through a refused snapshot plan, not as typed refusal codes; there is no refusalCode
  field. Every refusal message asserted below is the exact message string the planner
  emits, and no invented safeDelete.xxx typed code is introduced. The declaration-file
  branch of the planner is unreachable because the symbol index is built from the same
  snapshot files that supply the declaration, so it is not asserted here. Warnings are
  asserted as real warning text, not as narrative prose.

  # Scenario 1: successful JDT-proven unused type delete preview
  @REQ-JAVA-SAFE-DELETE-CHAR-001 @functional-requirement
  Scenario: A JDT-proven unused type is previewed for deletion as one FileEdit.Delete with LOW risk and confidence 1.0
    Given a Java workspace snapshot that contains a recognized non-generated Java type declaration file
    And the JDT binding analysis is clean with no parse or classpath warnings
    And the type binding resolves to exactly one JDT deleteable type candidate with a binding key
    And the type has no references anywhere in the project
    When the caller requests a safeDelete preview for that fully qualified type name without force
    Then the adapter returns a PREVIEW patch plan for the operation safeDelete
    And the plan carries confidence 1.0
    And the plan requires user approval
    And the plan evidence is JDT_BINDING
    And the plan risk is LOW
    And the plan affected-file set lists only the declaration file
    And the plan carries a WorkspaceEdit with exactly one FileEdit.Delete for the declaration file
    And the plan reports the adapter diagnostics computed after the declaration file is removed from the snapshot
    And the plan warns that Java source references were evaluated using exact JDT type-binding evidence
    And the plan warns that captured workspace text inputs are scanned for literal target names only
    And the plan summary names the fully qualified type and the declaration file path

  # Scenario 2: forced delete with references
  @REQ-JAVA-SAFE-DELETE-CHAR-001 @functional-requirement
  Scenario: A safeDelete preview with force allowed despite references is previewed with confidence 0.3, HIGH risk, and a forced-delete warning
    Given a Java workspace snapshot that contains a recognized non-generated Java type declaration file
    And the JDT binding analysis is clean and resolves the type binding
    And the type has at least one reference in the project
    When the caller requests a safeDelete preview for that fully qualified type name with force allowed
    Then the adapter returns a PREVIEW patch plan for the operation safeDelete
    And the plan carries confidence 0.3
    And the plan requires user approval
    And the plan risk is HIGH
    And the plan carries a WorkspaceEdit with exactly one FileEdit.Delete for the declaration file
    And the plan warns that the delete is forced and that the found references were ignored and the build will break
    And the plan evidence is JDT_BINDING when the JDT binding is clean, or LEXICAL_FALLBACK otherwise

  # Scenario 3: lexical fallback when JDT evidence is unclean
  @REQ-JAVA-SAFE-DELETE-CHAR-001 @functional-requirement
  Scenario: A safeDelete preview falls back to lexical evidence when JDT binding evidence is unavailable or not clean
    Given a Java workspace snapshot that contains a recognized non-generated Java type declaration file
    And the JDT binding analysis reports an error warning or no usable binding for the type
    And the type has no references anywhere in the project
    When the caller requests a safeDelete preview for that fully qualified type name without force
    Then the adapter returns a PREVIEW patch plan for the operation safeDelete
    And the plan carries confidence 1.0
    And the plan requires user approval
    And the plan evidence is LEXICAL_FALLBACK
    And the plan risk is LOW
    And the plan affected-file set lists only the declaration file
    And the plan carries a WorkspaceEdit with exactly one FileEdit.Delete for the declaration file
    And the plan warns that JDT type-binding evidence was unavailable or not clean and that Java source references use lexical fallback and need careful review
    And the plan warns that captured workspace text inputs are scanned for literal target names only

  # Scenario 4: framework annotation findings elevate risk to HIGH
  @REQ-JAVA-SAFE-DELETE-CHAR-001 @functional-requirement
  Scenario Outline: A safeDelete declaration carrying a <framework> annotation is previewed with HIGH risk and framework warnings
    Given a Java workspace snapshot whose delete declaration file carries a <framework> annotation
    And the type has no references anywhere in the project
    When the caller requests a safeDelete preview for that fully qualified type name without force
    Then the adapter returns a PREVIEW patch plan for the operation safeDelete
    And the plan risk is HIGH
    And the plan carries a warning that names the <framework> framework and the detected annotation
    And the plan warns about the framework annotation locations

    Examples:
      | framework | annotation |
      | Spring    | @Component |
      | JPA       | @Entity    |
      | Jackson   | @JsonProperty |

  # Scenario 5: refusal contract for every safeDelete refusal
  @REQ-JAVA-SAFE-DELETE-CHAR-001 @functional-requirement
  Scenario: A safeDelete refusal is a REFUSED plan with no edit, no affected file, no approval, confidence 0.0 and risk HIGH
    Given the Java adapter returns a REFUSED patch plan for a safeDelete preview
    When the caller inspects the refusal plan
    Then the refusal operation name is deterministically safeDelete
    And the refusal carries an empty WorkspaceEdit
    And the refusal carries an empty affected-file set
    And the refusal grants no approval and no managed-write eligibility
    And the refusal carries confidence 0.0 and risk HIGH
    And the refusal carries its real reason message in both the summary and the warnings
    And the refusal carries no typed refusal code because refusals are message-based

  # Scenario 6: refusal when references exist and force is not allowed
  @REQ-JAVA-SAFE-DELETE-CHAR-001 @functional-requirement
  Scenario: A safeDelete refusal for a referenced type without force lists the references and the force instruction
    Given a Java workspace snapshot that contains a recognized non-generated Java type declaration file
    And the type has one or more references in the project
    When the caller requests a safeDelete preview for that fully qualified type name without force
    Then the adapter returns a REFUSED patch plan for the operation safeDelete
    And the refusal carries an empty WorkspaceEdit and an empty affected-file set
    And the refusal grants no approval and carries confidence 0.0 with risk HIGH
    And the refusal summary and warning carry a message that begins with Cannot delete and the fully qualified type name and that reports the reference count and the evidence kind
    And the refusal message lists each reference as a path and line, up to the first twenty references
    And the refusal message carries the phrase Use --force to delete anyway (dangerous).

  # Scenario 7: reference-list truncation adds the more suffix
  @REQ-JAVA-SAFE-DELETE-CHAR-001 @functional-requirement
  Scenario: A safeDelete refusal with more than twenty references truncates the list and appends the more suffix
    Given a Java workspace snapshot that contains a recognized non-generated Java type declaration file
    And the type has more than twenty references in the project
    When the caller requests a safeDelete preview for that fully qualified type name without force
    Then the adapter returns a REFUSED patch plan for the operation safeDelete
    And the refusal message lists the first twenty references
    And the refusal message appends a line that reports the additional references beyond the first twenty
    And the refusal message carries the phrase Use --force to delete anyway (dangerous).

  # Scenario 8: finite real refusal message list as assertion data
  @REQ-JAVA-SAFE-DELETE-CHAR-001 @functional-requirement
  Scenario Outline: A safeDelete request is refused deterministically with its real refusal message "<message>"
    Given a Java workspace snapshot for a safeDelete preview under the condition <condition>
    When the caller requests a safeDelete preview
    Then the adapter returns a REFUSED patch plan for the operation safeDelete
    And the refusal summary and warning carry the message "<message>"
    And the refusal carries an empty WorkspaceEdit and an empty affected-file set
    And the refusal grants no approval and carries confidence 0.0 with risk HIGH

    Examples:
      | message | condition |
      | Symbol not found: com.example.Missing | the fully qualified type name is absent from the symbol index or is not a deleteable type |
      | Generated source cannot be deleted: build/generated/sources/annotationProcessor/java/main/Generated.java (path is inside a generated-source or build-output location) | the declaration path is inside a generated-source or build-output location |
      | Generated source cannot be deleted: Generated.java (source declares a generated-code annotation) | the declaration source declares a generated-code annotation |
      | Generated source cannot be deleted: Generated.java (source header identifies generated code) | the declaration source header identifies generated code |

  @REQ-JAVA-SAFE-DELETE-WORKSPACE-TEXT-001#captured-project-text-reference-refuses-safedelete @functional-requirement
  Scenario Outline: Captured project text reference refuses safeDelete
    Given a Java workspace snapshot that contains a recognized non-generated Java type declaration file
    And a captured workspace text input of type "<surface>" references the selected type at "<path>"
    When the caller requests a safeDelete preview for that fully qualified type name without force
    Then the adapter returns a REFUSED patch plan for the operation safeDelete
    And the refusal carries an empty WorkspaceEdit and an empty affected-file set
    And the refusal grants no approval and carries confidence 0.0 with risk HIGH
    And the refusal summary and warning carry a message that begins with Cannot delete and the fully qualified type name and that reports the reference count and the evidence kind
    And the refusal message lists each reference as a path and line, up to the first twenty references

    Examples:
      | surface | path |
      | gradle  | build.gradle |
      | xml     | src/main/resources/hibernate.cfg.xml |
      | xml     | src/main/resources/pom.xml |
      | yaml    | src/main/resources/application.yml |
      | sql     | src/main/resources/db/queries/legacy.sql |
      | service | src/main/resources/META-INF/services/com.example.Legacy |

  @REQ-JAVA-SAFE-DELETE-WORKSPACE-TEXT-001#maven-pom-only-literal-reference-produces-a-review-only-safedelete-preview @functional-requirement
  Scenario: Maven POM-only literal reference produces a review-only safeDelete preview
    Given a Java workspace snapshot that contains a recognized non-generated Java type declaration file
    And the JDT binding analysis is clean with no parse or classpath warnings
    And a captured workspace text input of type "maven" references the selected type at "pom.xml"
    When the caller requests a safeDelete preview for that fully qualified type name without force
    Then the adapter returns a PREVIEW patch plan for the operation safeDelete
    And the plan carries confidence 0.3
    And the plan requires user approval
    And the plan evidence is LEXICAL_FALLBACK
    And the plan risk is HIGH
    And the plan affected-file set lists only the declaration file
    And the plan carries a WorkspaceEdit with exactly one FileEdit.Delete for the declaration file
    And the review-only proposal identifies "pom.xml" and PatchEngine refuses Apply with "evidence.insufficient" before mutation
    When the caller previews and applies safeDelete through the daemon and MCP
    Then managed Apply is refused with code "java.safeDelete.referenceClosureIncomplete"
    And workspace files and transaction metadata remain unchanged
    And daemon and MCP expose only snapshot-bound workspace-text analysis without Apply authority

  @REQ-JAVA-SAFE-DELETE-WORKSPACE-TEXT-001#incomplete-text-reference-scan-refuses-even-forced-safedelete @functional-requirement
  Scenario Outline: Incomplete text-reference scan refuses even forced safeDelete
    Given a Java workspace snapshot that contains a recognized non-generated Java type declaration file
    And workspace text-reference scan evidence is <coverage>
    When the caller requests a safeDelete preview for that fully qualified type name with force allowed
    Then the adapter returns a REFUSED patch plan for the operation safeDelete
    And the refusal summary and warning carry the message "Safe delete requires complete workspace text-reference scan evidence."
    And the refusal carries an empty WorkspaceEdit and an empty affected-file set
    And the refusal carries no typed refusal code because refusals are message-based

    Examples:
      | coverage |
      | over-budget |
      | missing |

  @REQ-JAVA-SAFE-DELETE-WORKSPACE-TEXT-001#symlinked-workspace-text-input-makes-forced-safedelete-refuse @functional-requirement
  Scenario: Symlinked workspace text input makes forced safeDelete refuse
    Given a Java workspace snapshot that contains a recognized non-generated Java type declaration file
    And workspace text-reference coverage is hidden by a symbolic link
    When the caller requests a safeDelete preview for that fully qualified type name with force allowed
    Then the adapter returns a REFUSED patch plan for the operation safeDelete
    And the refusal summary and warning carry the message "Safe delete requires complete workspace text-reference scan evidence."
    And the refusal carries an empty WorkspaceEdit and an empty affected-file set
    And the refusal carries no typed refusal code because refusals are message-based

  @REQ-JAVA-SAFE-DELETE-WORKSPACE-TEXT-001#managed-safedelete-refuses-incomplete-reference-closure @functional-requirement
  Scenario: Managed safeDelete refuses incomplete reference closure
    Given a Java workspace snapshot that contains a recognized non-generated Java type declaration file
    And the project contains an unresolved dynamic class-loading consumer with no literal target name
    When the caller previews and applies safeDelete through the daemon and MCP
    Then managed Apply is refused with code "java.safeDelete.referenceClosureIncomplete"
    And workspace files and transaction metadata remain unchanged
    And daemon and MCP expose only snapshot-bound workspace-text analysis without Apply authority


  @REQ-JAVA-SAFE-DELETE-WORKSPACE-TEXT-001#aggregator-root-pom-only-literal-reference-produces-a-review-only-safedelete-preview @functional-requirement
  Scenario: Maven aggregator root POM-only literal reference produces a review-only safeDelete preview
    Given a clean Maven reactor with an unused Java type referenced only from its root aggregator POM
    And the JDT binding analysis is clean with no parse or classpath warnings
    When the caller requests a safeDelete preview for that fully qualified type name without force
    Then the adapter returns a PREVIEW patch plan for the operation safeDelete
    And the plan carries confidence 0.3
    And the plan requires user approval
    And the plan evidence is LEXICAL_FALLBACK
    And the plan risk is HIGH
    And the plan affected-file set lists only the declaration file
    And the plan carries a WorkspaceEdit with exactly one FileEdit.Delete for the declaration file
    And the review-only proposal identifies "pom.xml" and PatchEngine refuses Apply with "evidence.insufficient" before mutation

  @REQ-JAVA-SAFE-DELETE-CHAR-001#managed-safedelete-requires-complete-maven-reactor-closure-and-rejects-stale-apply @functional-requirement
  Scenario: Managed safeDelete requires complete Maven reactor closure and rejects stale Apply
    Given an available two-module Maven reactor has a package-private Java type with no source or text consumers
    When the IDE requests safeDelete for fixtures.orbit.Orphan with an SDK-bound daemon context
    Then RefactorKit reports complete snapshot-bound Maven closure and managed Apply authority only for this preview
    And a stale Apply is refused, then exact Apply and rollback restore the full reactor image

  @REQ-JAVA-SAFE-DELETE-WORKSPACE-TEXT-001#binary-resource-keeps-managed-safedelete-disabled @functional-requirement
  Scenario: Binary resource keeps managed safeDelete disabled
    Given a two-module Maven reactor contains a package-private Java type and an opaque binary resource
    When the IDE previews safeDelete for fixtures.orbit.Orphan with an SDK-bound daemon context
    Then RefactorKit reports the binary resource as outside semantic closure and grants no Apply authority
    And managed Apply is refused without changing the reactor or transaction log

  @REQ-JAVA-SAFE-DELETE-WORKSPACE-TEXT-001#unmodeled-workspace-input-keeps-managed-safedelete-disabled @functional-requirement
  Scenario: Unmodeled workspace input keeps managed safeDelete disabled
    Given a two-module Maven reactor contains a package-private Java type and an unmodeled input
    When the IDE previews safeDelete for fixtures.orbit.Orphan with an SDK-bound daemon context
    Then RefactorKit reports an unmodeled workspace input and grants no Apply authority
    And managed Apply is refused without changing the reactor or transaction log

  @REQ-JAVA-SAFE-DELETE-CHAR-001#new-inputs-invalidate-managed-maven-closure-under-lock @functional-requirement
  Scenario Outline: New inputs invalidate managed Maven closure under lock
    Given an available two-module Maven reactor has a package-private Java type with no source or text consumers
    When an input appears "<phase>" while applying the previewed Maven closure
    Then the inventory guard refuses with "<diagnostic>" before writing the reactor or its journal

    Examples:
      | phase                            | diagnostic                             |
      | before lease validation          | snapshot.workspaceInventoryChanged     |
      | during diagnostics               | snapshot.workspaceInventoryChanged     |
      | as a link before lease validation | snapshot.workspaceInventoryUnavailable |
