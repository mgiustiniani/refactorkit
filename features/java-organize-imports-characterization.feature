# language: en
@implemented-and-validated
Ability: Organize a single-file Java import block with deduplication, same-package removal, JDT-proven unused removal, sorted groups, static imports last, and preserved wildcard and unresolved imports
  As a RefactorKit caller on a Java workspace
  I need the Java organizeImports preview to tidy one file's import block into a sorted,
    deduplicated, same-package-free and JDT-proven-unused-free block while preserving
    wildcard and unresolved imports, and to refuse generated source with a deterministic
    typed diagnostic
  So that single-file import organization is previewable and safe and generated code is
    never rewritten

  This executable backlog covers row C-IMPORT of the approved finite J1 Java catalogue
  (REQ-JAVA-J1-CATALOGUE-APPROVED-001): the Java operation organizeImports must accept one
  or more non-generated Java source files and produce a PREVIEW patch plan for the operation
  organizeImports. The supported single-file shape is a Java source file whose import block
  is rewritten in place: duplicate imports are removed, same-package imports are removed
  because they are never needed, and unused exact imports are removed when clean JDT binding
  evidence proves the imported binding has no non-import use in the file. The rewritten block
  sorts imports into the documented group order java, then javax, then jakarta, then org,
  then com, then everything else, and places static imports last in their own group. Wildcard
  and unresolved imports are preserved because their usage cannot be proven safely. The
  successful plan carries confidence 1.0, requiresUserApproval false, risk LOW, evidence
  JDT_BINDING when the JDT binding evidence is clean or STRUCTURAL when it is not, an
  affected-file set listing the rewritten file, and a WorkspaceEdit with exactly one
  FileEdit.Modify containing a single replace TextEdit for the import block. A file whose
  import block is already organized produces no FileEdit and no affected-file entry: the
  preview returns an empty result.

  Characterization and promotion (truthful, anti-fake): C-IMPORT was characterized as
  GENUINE RED, not RED-deferral. The characterization revealed a real production mismatch:
  the generated-refusal outcome asserted requiresUserApproval false, but the planner
  JavaOrganizeImportsPlanner defaulted requiresUserApproval to true for generated source.
  Per J1 catalogue baseline section 2, a characterization that reveals a real mismatch is
  reclassified as GENUINE RED, so production was corrected (requiresUserApproval=false,
  dd9094ed) while the requirement was retained. After the fix, C-IMPORT GREEN 12 of 12
  (evidence c5b20a5b), a Cucumber runner and glue validate the scenarios, and scenario 4 is
  reconciled. The feature-level status is promoted to @implemented-and-validated. Independent
  requirements-quality review is a separate final gate and its PASS is not yet obtained. Only
  real production behavior is asserted. The only typed refusal code is the diagnostic code
  java.generatedSource emitted with severity ERROR on a REFUSED plan; every other asserted
  outcome is carried by real plan fields, warnings, and messages rather than invented
  granular codes. Warnings are asserted as real warning text, not as narrative prose.

  # Scenario 1: successful single-file organizeImports preview with clean JDT binding evidence
  @REQ-JAVA-ORGANIZE-IMPORTS-CHAR-001 @functional-requirement
  Scenario: A single non-generated file organizeImports is previewed with clean JDT binding evidence and one Modify edit
    Given a Java workspace snapshot that contains one recognized non-generated Java source file with an import block
    And the JDT binding analysis is clean for that file with no error warnings
    And the import block contains duplicate imports, a same-package import, and unused exact imports
    When the caller requests an organizeImports preview for that single file
    Then the adapter returns a PREVIEW patch plan for the operation organizeImports
    And the plan has confidence 1.0
    And the plan does not require user approval
    And the plan risk is LOW
    And the plan evidence is JDT_BINDING
    And the plan lists the source file in its affected-file set
    And the plan carries a WorkspaceEdit with exactly one FileEdit.Modify for that file
    And that FileEdit.Modify carries a single replace TextEdit over the import block range
    And the plan warns that JDT binding evidence checked exact imports and removed the unused import count
    And the plan warns that wildcard and unresolved imports are preserved because their usage cannot be proven safely
    And the plan does not warn that JDT evidence was unclean

  # Scenario 2: unclean JDT binding evidence falls back to structural evidence
  @REQ-JAVA-ORGANIZE-IMPORTS-CHAR-001 @functional-requirement
  Scenario: A single-file organizeImports preview falls back to structural evidence when JDT binding evidence is unclean
    Given a Java workspace snapshot that contains one recognized non-generated Java source file with an import block
    And the JDT binding analysis reports an error warning for that file
    When the caller requests an organizeImports preview for that single file
    Then the adapter returns a PREVIEW patch plan for the operation organizeImports
    And the plan has confidence 1.0
    And the plan does not require user approval
    And the plan risk is LOW
    And the plan evidence is STRUCTURAL
    And the plan lists the source file in its affected-file set
    And the plan carries a WorkspaceEdit with exactly one FileEdit.Modify for that file
    And the plan warns that JDT evidence was unclean and unused imports were preserved while lexical sorting, deduplication, and same-package removal continued
    And the plan warns that wildcard and unresolved imports are preserved because their usage cannot be proven safely

  # Scenario 3: import block sorts into the documented group order
  @REQ-JAVA-ORGANIZE-IMPORTS-CHAR-001 @functional-requirement
  Scenario Outline: The rewritten import block places the import <first> before the import <second>
    Given a single non-generated Java source file whose import block contains the imports <first> and <second>
    And every import in the block is used by the file
    When the caller requests an organizeImports preview for that file
    Then the single FileEdit.Modify rewrites the import block
    And the rewritten block places the import <first> before the import <second>

    Examples:
      | first | second |
      | java.util.List | javax.naming.Context |
      | javax.naming.Context | jakarta.servlet.http.HttpServlet |
      | jakarta.servlet.http.HttpServlet | org.slf4j.Logger |
      | org.slf4j.Logger | com.example.App |
      | com.example.App | net.example.Util |

  # Scenario 4: import block rules for duplicates, same-package, unused, static, wildcard
  # under clean JDT binding. An unresolved import is deliberately absent here because any
  # unresolved import produces a JDT error warning, which makes the binding unclean and
  # forces the STRUCTURAL fallback; unresolved imports are therefore preserved only in the
  # unclean-fallback context asserted by Scenario 2, never alongside clean JDT unused removal.
  @REQ-JAVA-ORGANIZE-IMPORTS-CHAR-001 @functional-requirement
  Scenario: The import block is deduplicated, freed of same-package and JDT-proven unused imports, static imports last, and wildcard imports preserved
    Given a single non-generated Java source file whose import block contains duplicate imports, a same-package import, used exact imports, unused exact imports, and a wildcard import
    And the JDT binding analysis is clean and proves the unused exact imports have no non-import use
    When the caller requests an organizeImports preview for that file
    Then the resulting single FileEdit.Modify rewrites the import block
    And the rewritten block keeps no duplicate import
    And the rewritten block keeps no same-package import
    And the rewritten block keeps the used exact imports
    And the rewritten block drops the unused exact imports proven by JDT binding
    And the rewritten block keeps the wildcard import
    And the rewritten block places static imports last in their own group

  # Scenario 5: already-organized single file returns an empty result
  @REQ-JAVA-ORGANIZE-IMPORTS-CHAR-001 @functional-requirement
  Scenario: A single file whose import block is already organized is previewed as an empty result
    Given a Java workspace snapshot that contains one recognized non-generated Java source file whose import block is already organized
    When the caller requests an organizeImports preview for that single file
    Then the adapter returns a PREVIEW patch plan for the operation organizeImports
    And the plan carries an empty WorkspaceEdit
    And the plan carries an empty affected-file set
    And the plan lists zero rewritten files in its summary
    And the plan does not require user approval

  # Scenario 6: generated source is refused with the typed diagnostic code java.generatedSource
  @REQ-JAVA-ORGANIZE-IMPORTS-CHAR-001 @functional-requirement
  Scenario Outline: An organizeImports request for generated source is refused with the typed diagnostic code java.generatedSource and reason "<reason>"
    Given a Java workspace snapshot that contains a generated Java source file that "<reason>"
    When the caller requests an organizeImports preview for that generated file
    Then the adapter returns a REFUSED patch plan for the operation organizeImports
    And the refusal carries an empty WorkspaceEdit
    And the refusal carries an empty affected-file set
    And the refusal grants no approval and no managed-write eligibility
    And the refusal leaves no pending actionable plan
    And the refusal carries confidence 0.0 and risk HIGH
    And the refusal carries a Diagnostic with severity ERROR and code "java.generatedSource"
    And the refusal summary carries the message "Generated source cannot be rewritten: <path> (<reason>)"

    Examples:
      | reason | path |
      | path is inside a generated-source or build-output location | build/generated/sources/annotationProcessor/java/main/Generated.java |
      | source declares a generated-code annotation | src/main/java/Generated.java |
      | source header identifies generated code | src/main/java/Generated.java |
