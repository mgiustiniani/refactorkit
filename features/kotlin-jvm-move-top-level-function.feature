# language: en
Business Need: Move one compiler-proven public top-level Kotlin function as moveDeclaration
  As a maintainer of a Kotlin JVM workspace
  I need RefactorKit to extend moveDeclaration by one bounded whole-file top-level-function shape
  So that a compiler-proven public top-level function moves safely with preview, apply, and rollback

  This executable backlog covers REQ-KOTLIN-MOVE-FUNCTION-001 acceptance criteria AC-FUNCTION-001
  through AC-FUNCTION-006. It does not qualify general function relocation, Java callers, aliased
  callable imports, overload families, source-file facade renaming, multifile facades, JvmName
  facades, extension or suspend or default functions, callable references, member extraction, or
  arbitrary declaration splitting. Plugin-dependent/Xplugin shapes are out of scope: the production
  planner has no Xplugin path, so a plugin-dependent top-level function is refused with the stable
  typed code kotlin.moveFunctionShapeUnsupported.

  Declared status: production implementation exists on main, and local Linux GREEN Cucumber evidence
  now exists (29 scenarios / 109 steps), but promotion to @implemented-and-validated is blocked
  pending the tests-only RED and independent review evidence, so every scenario remains tagged @partial
  (anti-fake).

  Refusal codes in the scenarios are the actual production codes observed by the Cucumber glue, not
  invented granular codes. The source of truth matches executable reality (anti-fake).

  # AC-FUNCTION-001
  @REQ-KOTLIN-MOVE-FUNCTION-001 @functional-requirement @partial
  Scenario: A compiler-proven public top-level function moves as moveDeclaration
    Given the selected declaration is one compiler-proven public top-level Kotlin function "fixture.pricing.computeInvoiceTotal" with explicit or implicit PUBLIC visibility
    And the compiler-proven source file is present in the snapshot
    And the snapshot carries a hash attestation and the compiler-proven source file is present
    And the source file contains the selected function and only compiler-proven private top-level helper declarations
    And the destination package "fixture.accounting" has no same-name top-level function family
    And the caller explicitly accepts unknown external-consumer risk
    And an in-workspace consumer has exactly one compiler-proven unaliased explicit import of the source callable FQN "fixture.pricing.computeInvoiceTotal"
    When moveDeclaration previews the selection
    Then the result is a SEMANTIC_PREVIEW with exact new facade callable identity "fixture.accounting.computeInvoiceTotal"
    And the preview edits only the package declaration, the exact consumer import directive, and the source-file path
    And the moved file retains its exact source content beyond the package token
    And declarations carried in the same file resolve to their exact computed post-move identities
    And no source text other than the package and import tokens is rewritten or formatted
    And line endings and every private helper byte remain exact

  # AC-FUNCTION-001 refinement: a trailing package comment is preserved byte for byte
  @REQ-KOTLIN-MOVE-FUNCTION-001 @functional-requirement @partial
  Scenario: A compiler-proven public top-level function package declaration with a trailing comment preserves every other byte
    Given the selected declaration is a compiler-proven public top-level Kotlin function "fixture.pricing.computeInvoiceTotal" whose package declaration carries a trailing comment
    When moveDeclaration previews the selection
    Then the result is a SEMANTIC_PREVIEW that edits only the package token and preserves the trailing comment and every other byte exactly

  # AC-FUNCTION-002
  @REQ-KOTLIN-MOVE-FUNCTION-001 @functional-requirement @partial
  Scenario Outline: An unsupported top-level function shape refuses with a stable typed code
    Given the selected declaration is one "<shape>" top-level Kotlin function
    When moveDeclaration previews the selection
    Then the selection is refused with stable typed code "<refusal code>" and no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation

    Examples:
      | shape                                  | refusal code                           |
      | extension                              | kotlin.moveFunctionShapeUnsupported    |
      | suspend                                | kotlin.moveFunctionShapeUnsupported    |
      | overloaded with a private same-name sibling | kotlin.moveFunctionShapeUnsupported |
      | default-argument                       | kotlin.moveFunctionShapeUnsupported    |
      | literal JvmName facade                 | kotlin.moveDeclarationUnsupported      |
      | local or member                        | kotlin.moveDeclarationUnsupported      |
      | script                                 | kotlin.scriptSemanticsUnsupported      |
      | generated                              | kotlin.moveSourceOwnershipUnavailable  |
      | plugin-dependent                       | kotlin.moveFunctionShapeUnsupported    |
      | delegated                              | kotlin.symbolDelegatedPropertyUnsupported |
      | annotation-evaluated                   | kotlin.moveFunctionShapeUnsupported    |
      | non-public selected                    | kotlin.moveDeclarationUnsupported      |

  # AC-FUNCTION-003
  @REQ-KOTLIN-MOVE-FUNCTION-001 @functional-requirement @partial
  Scenario Outline: A co-located non-private top-level declaration refuses
    Given the source file contains the selected public top-level function and one "<declaration kind>" top-level declaration
    When moveDeclaration previews the selection
    Then the selection is refused with stable typed code "<refusal code>" and no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation

    Examples:
      | declaration kind | refusal code                    |
      | public           | kotlin.moveFileShapeUnsupported |
      | internal         | kotlin.moveFileShapeUnsupported |
      | protected        | kotlin.symbolCompilationFailed  |

  # AC-FUNCTION-004
  @REQ-KOTLIN-MOVE-FUNCTION-001 @functional-requirement @partial
  Scenario Outline: A consumer form other than one unaliased explicit import refuses
    Given an in-workspace consumer uses "<consumer form>" of the source callable FQN "fixture.pricing.computeInvoiceTotal"
    When moveDeclaration previews the selection
    Then the consumer is refused with stable typed code "<refusal code>" and no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation

    Examples:
      | consumer form            | refusal code                              |
      | aliased import           | kotlin.moveFunctionConsumerShapeUnsupported |
      | same-package implicit    | kotlin.moveFunctionConsumerShapeUnsupported |
      | package-star import      | kotlin.moveFunctionConsumerShapeUnsupported |
      | fully-qualified use      | kotlin.moveFunctionConsumerShapeUnsupported |
      | callable-reference       | kotlin.moveFunctionCallableReferenceUnsupported |
      | Java consumer            | kotlin.moveFunctionJavaConsumerUnsupported |
      | generated consumer       | kotlin.moveGeneratedReference             |
      | mixed consumer forms     | kotlin.moveFunctionConsumerShapeUnsupported |
      | unresolved consumer      | kotlin.symbolCompilationFailed            |
      | recovered consumer       | kotlin.symbolCompilationFailed            |
      | truncated consumer       | kotlin.symbolCompilationFailed            |

  # AC-FUNCTION-005
  @REQ-KOTLIN-MOVE-FUNCTION-001 @functional-requirement @partial
  Scenario: Destination same-name top-level function family absence is enforced
    Given the selected declaration is one compiler-proven public top-level Kotlin function "fixture.pricing.computeInvoiceTotal"
    And the caller explicitly accepts unknown external-consumer risk
    And the destination package "fixture.accounting" already contains a same-name top-level function family
    When moveDeclaration previews the selection
    Then the selection is refused with stable typed code "kotlin.moveDestinationOverloadUnsupported" and no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation
    When the destination package "fixture.accounting" has no same-name top-level function family and every other precondition is clean
    Then moveDeclaration previews a SEMANTIC_PREVIEW with exact new facade callable identity "fixture.accounting.computeInvoiceTotal"

  # AC-FUNCTION-006
  @REQ-KOTLIN-MOVE-FUNCTION-001 @functional-requirement @partial
  Scenario: Apply uses PatchEngine with authorization, diagnostics, WAL, attestation, and exact rollback
    Given an approved SEMANTIC_PREVIEW moves "fixture.pricing.computeInvoiceTotal" to "fixture.accounting"
    And the preview edits only the package declaration, the exact consumer import directive, and the source-file path
    When the preview is applied under explicit authorization
    Then apply uses PatchEngine and writes a transaction rollback record
    And the committed post-image is written with the moved package and consumer import
    And line endings and every private helper byte remain exact
    When that transaction is rolled back
    Then every file byte, path, and snapshot hash equals the pre-apply image
    And authoritative rollback diagnostics attest the restored snapshot
    And no source text other than the package and import tokens is rewritten or formatted
