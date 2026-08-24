# language: en
@functional-requirement @implemented-and-validated
Business Need: Apply a snapshot-bound Kotlin import layout to retained directives without touching their bytes
  RefactorKit must reorder retained Kotlin import directives and change only directive order and configured
  blank-line separators, preserving each retained directive byte for byte and preserving LF or CRLF line
  endings. The default official layout is one String-natural-order-sorted group within the ASCII-bounded
  import grammar; Unicode code-point ordering is unobservable for valid import directives. A project may override
  grouping with one bounded .editorconfig ij_kotlin_imports_layout value captured as no-follow
  ClasspathEvidence, using one catch-all star token, an optional alias group, and unique package-prefix
  tokens in a declared group order, with groups separated by one empty line and the nearest captured
  ancestor configuration winning.
  An exact kotlin.code.style=official in gradle.properties is recognized as an explicit official-style
  declaration, and every captured style file is re-fingerprinted before use and must still match the preview
  snapshot. Malformed, duplicated, escaping, stale, oversized, symlinked, or unsupported Kotlin layout
  configuration refuses before planning. Preview is read-only and performs no workspace or transaction
  write; managed apply uses the operation-owned diagnostics gate, PatchEngine, and WAL, and rollback
  restores every original byte.
  Declared status: @implemented-and-validated. The production planner KotlinOrganizeImportsPlanner
  pre-existed the baseline and is unchanged; the feature passes 19 of 19 GREEN with a real K2 toolchain
  fixture, PatchEngine, WAL, managed apply, and rollback. Per user-approved approved-change-014 the
  mandatory tests-only RED gate is deferred and recorded as ARCHITECTURAL-PROCESS technical debt R-052,
  and the baseline stale or symlinked refusal-code criterion is superseded to kotlin.classpathEvidenceChanged,
  matching actual production behavior. Independent requirements-quality-reviewer PASS is not yet obtained;
  packaged CLI, daemon, or MCP apply and rollback, and four-platform qualification are not claimed here and
  requalify at K5 band close.
  The refusal codes asserted in the scenarios are the actual production codes observed in the planning
  source, kotlin.organizeImportsStyleUnsupported, kotlin.classpathEvidenceChanged, and
  kotlin.organizeImportsNoChange, not invented granular codes. Changed or symlinked DECLARATION_FILE style
  evidence is rejected by KotlinCompilerDiagnostics.validateClasspathEvidence as kotlin.classpathEvidenceChanged
  before the planner's projectStyle boundary, so kotlin.organizeImportsStyleStale is unreachable in practice.
  The source of truth is intended to match executable reality (anti-fake).

  # REQ-KOTLIN-IMPORT-STYLE-001 — default official layout
  @REQ-KOTLIN-IMPORT-STYLE-001 @functional-requirement
  Scenario: The default official layout sorts retained directives into one String-natural-order-sorted group
    Given one saved authoritative non-generated Kotlin JVM file with retained imports in one contiguous import block
    And the snapshot carries complete error-free compiler evidence and the retained import directives
    And no captured project style file overrides the layout
    When organize-imports previews that Kotlin JVM file
    Then the retained directives are reordered into one String-natural-order-sorted group within the ASCII-bounded import grammar
    And Unicode code-point ordering is unobservable for valid import directives
    And each retained directive is preserved byte for byte
    And only directive order and configured blank-line separators change
    And the preview is read-only and does not mutate the snapshot or the filesystem

  # REQ-KOTLIN-IMPORT-STYLE-001 — line-ending preservation
  @REQ-KOTLIN-IMPORT-STYLE-001 @functional-requirement
  Scenario Outline: LF and CRLF line endings are preserved through the default layout
    Given one saved authoritative non-generated Kotlin JVM file with retained imports in one contiguous import block using "<line ending>" line endings
    And the snapshot carries complete error-free compiler evidence
    When organize-imports previews that Kotlin JVM file
    Then the retained directives are reordered into one String-natural-order-sorted group within the ASCII-bounded import grammar
    And the "<line ending>" line endings are preserved byte for byte

    Examples:
      | line ending |
      | LF          |
      | CRLF        |

  # REQ-KOTLIN-IMPORT-STYLE-001 — .editorconfig override
  @REQ-KOTLIN-IMPORT-STYLE-001 @functional-requirement
  Scenario: A bounded .editorconfig layout override groups retained directives by tokens
    Given one saved authoritative non-generated Kotlin JVM file with retained imports in one contiguous import block
    And a captured .editorconfig declares ij_kotlin_imports_layout = kotlin.**,java.**,*
    And the style file is captured as no-follow ClasspathEvidence and matches the preview snapshot
    When organize-imports previews that Kotlin JVM file
    Then the retained directives are grouped by the tokens kotlin.**, java.**, and * in declared order
    And each group is separated by one empty line
    And each retained directive is preserved byte for byte
    And the preview warns that the import layout is bound to the .editorconfig path
    And the preview is read-only and does not mutate the snapshot or the filesystem

  # REQ-KOTLIN-IMPORT-STYLE-001 — alias group and package prefixes
  @REQ-KOTLIN-IMPORT-STYLE-001 @functional-requirement
  Scenario: An optional alias group and unique package-prefix tokens order the retained groups
    Given one saved authoritative non-generated Kotlin JVM file with retained imports in one contiguous import block
    And a captured .editorconfig declares ij_kotlin_imports_layout = ^,fixture.api.**,fixture.util.**,*
    And the retained imports include an aliased import and imports under the fixture.api and fixture.util package prefixes
    When organize-imports previews that Kotlin JVM file
    Then the retained directives are grouped by the alias group, the fixture.api.** prefix, the fixture.util.** prefix, and the catch-all in declared order
    And each group is separated by one empty line
    And the aliased import is placed in the alias group
    And imports matching a package prefix are placed in the matching prefix group
    And imports matching no prefix are placed in the catch-all group
    And each retained directive is preserved byte for byte

  # REQ-KOTLIN-IMPORT-STYLE-001 — nearest captured ancestor wins
  @REQ-KOTLIN-IMPORT-STYLE-001 @functional-requirement
  Scenario: The nearest captured ancestor layout configuration wins
    Given one saved authoritative non-generated Kotlin JVM file under a nested source path
    And a captured .editorconfig at a nearer ancestor declares ij_kotlin_imports_layout = fixture.api.**,*
    And a captured .editorconfig at a farther ancestor declares ij_kotlin_imports_layout = java.**,*
    When organize-imports previews that Kotlin JVM file
    Then the nearest captured ancestor layout is selected and the farther ancestor layout is ignored
    And the retained directives are grouped by the nearest tokens in declared order
    And each retained directive is preserved byte for byte

  # REQ-KOTLIN-IMPORT-STYLE-001 — gradle.properties official recognition
  @REQ-KOTLIN-IMPORT-STYLE-001 @functional-requirement
  Scenario: An exact kotlin.code.style=official in gradle.properties is recognized
    Given one saved authoritative non-generated Kotlin JVM file with retained imports in one contiguous import block
    And a captured gradle.properties declares exactly kotlin.code.style=official
    And the style file is re-fingerprinted before use and matches the preview snapshot
    When organize-imports previews that Kotlin JVM file
    Then the explicit official-style declaration is recognized
    And the retained directives are reordered into one String-natural-order-sorted group within the ASCII-bounded import grammar
    And each retained directive is preserved byte for byte
    And the preview is read-only and does not mutate the snapshot or the filesystem

  # REQ-KOTLIN-IMPORT-STYLE-001 — preview read-only and no-op
  @REQ-KOTLIN-IMPORT-STYLE-001 @functional-requirement
  Scenario: Preview is read-only and an already-organized import block refuses as a no-op
    Given one saved authoritative non-generated Kotlin JVM file whose retained directives are already in the default official order
    And the snapshot carries complete error-free compiler evidence
    When organize-imports previews that Kotlin JVM file
    Then RefactorKit refuses the operation and explains why, reporting the typed code "kotlin.organizeImportsNoChange"
    And the preview performs no workspace or transaction write
    And no file, plan, lock, or transaction record changes

  # REQ-KOTLIN-IMPORT-STYLE-001 — managed apply and rollback
  @REQ-KOTLIN-IMPORT-STYLE-001 @functional-requirement
  Scenario: Managed apply uses the operation-owned diagnostics gate and rollback restores every original byte
    Given an approved successful preview of a snapshot-bound import layout
    And the managed-apply diagnostics gate for organize-imports is the operation-owned layout gate
    When the preview is applied under explicit authorization
    Then apply uses the patch engine and writes a transaction rollback record
    And the committed post-image contains the reordered retained directives with unchanged bytes
    When that transaction is rolled back
    Then every file byte, path, and snapshot hash equals the pre-apply image
    And rollback restores every original byte

  # REQ-KOTLIN-IMPORT-STYLE-001 — malformed, duplicated, escaping, oversized, and unsupported refusals
  @REQ-KOTLIN-IMPORT-STYLE-001 @functional-requirement
  Scenario Outline: RefactorKit refuses a malformed, duplicated, escaping, oversized, or unsupported Kotlin layout before planning
    Given one saved authoritative non-generated Kotlin JVM file with retained imports in one contiguous import block
    And the captured Kotlin layout configuration is "<layout condition>"
    When organize-imports previews that Kotlin JVM file
    Then RefactorKit refuses the operation before planning and explains why, reporting the typed code "kotlin.organizeImportsStyleUnsupported"
    And the refusal changes no file, plan, lock, or transaction record

    Examples:
      | layout condition                                                        |
      | an empty token making the declared token count invalid                  |
      | ij_kotlin_imports_layout declared more than once                        |
      | a repeated token that breaks the unique-token and one-star rule         |
      | a token that escapes the supported grammar such as an escaped star      |
      | a style evidence file larger than 64 KiB                                |
      | a style evidence file containing a NUL byte                             |
      | an unsupported token outside the supported grammar                      |
      | a gradle.properties value other than the exact official value           |

  # REQ-KOTLIN-IMPORT-STYLE-001 — stale and symlinked refusals
  @REQ-KOTLIN-IMPORT-STYLE-001 @functional-requirement
  Scenario Outline: RefactorKit refuses a stale or symlinked Kotlin layout evidence before planning
    Given one saved authoritative non-generated Kotlin JVM file with retained imports in one contiguous import block
    And the captured Kotlin layout evidence is "<evidence condition>"
    When organize-imports previews that Kotlin JVM file
    Then RefactorKit refuses the operation before planning and explains why, reporting the typed code "<refusal code>"
    And the refusal changes no file, plan, lock, or transaction record

    Examples:
      | evidence condition                                            | refusal code                    |
      | stale because the style fingerprint changed after the snapshot | kotlin.classpathEvidenceChanged |
      | a symbolic link instead of a no-follow regular file            | kotlin.classpathEvidenceChanged |
