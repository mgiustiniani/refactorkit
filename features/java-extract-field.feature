# Requirement source: docs/integration-contracts.md, P1 "Add everyday extraction/encapsulation operations".
# Architecture authorities: docs/arc42/appendix-requirements.adoc#java-extract-field-bounded-operation and #java-encapsulate-field-bounded-operation.
@functional-requirement @partial
Feature: Java field transformations
  Extract-field and encapsulate-field are separate, bounded requirements. Each
  accepts only exact JDT-bound Java authority and refuses unsupported shapes.

  @REQ-JAVA-EXTRACT-FIELD-001#a-bound-integer-expression-becomes-a-private-final-instance-field-or-refuses
  Scenario Outline: A bound integer expression becomes a private final instance field or refuses
    Given an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context
    When I exercise extractField for expression shape "<expression>" through daemon and MCP
    Then extractField results are "<outcome>" and managed rollback restores exact source
    And extractField is stable and instance-field previews use private final semantics

    Examples:
      | expression       | outcome |
      | compile-time-int | applied |
      | parameter-int    | refused |

  @REQ-JAVA-EXTRACT-FIELD-001#lsp-previews-field-extraction-against-the-current-dirty-buffer
  Scenario: LSP previews field extraction against the current dirty buffer
    Given a Java Maven LSP fixture with a dirty buffer before a direct return expression
    When the LSP previews extractField "answer" for expression "6 * 7 + 0" in that buffer
    Then the client receives one versioned extractField edit for the current buffer without writing workspace files

  @REQ-JAVA-ENCAPSULATE-FIELD-001#a-public-final-integer-field-is-encapsulated-and-bound-workspace-reads-remain-valid
  Scenario: A public final integer field is encapsulated and bound workspace reads remain valid
    Given an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context
    When I install a final-field client fixture and exercise encapsulateField through daemon and MCP
    Then the field becomes private and bound reads use its public getter in both surfaces
    And mutable, static, and unresolved field shapes refuse without workspace edits or transactions

  @REQ-JAVA-ENCAPSULATE-FIELD-001#resolved-reference-and-array-types-are-encapsulated-with-managed-rollback
  Scenario: Resolved reference and array types are encapsulated with managed rollback
    Given an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context
    When I exercise additional resolved field types for encapsulateField through managed Apply
    Then String, parameterized reference, and array fields preserve getter bindings and rollback exactly, while annotations and post-name dimensions refuse without edits

  @REQ-JAVA-ENCAPSULATE-FIELD-001#lsp-previews-field-encapsulation-for-current-dirty-java-documents
  Scenario: LSP previews field encapsulation for current dirty Java documents
    Given a Java Maven LSP fixture with dirty owner and reader buffers
    When the LSP previews encapsulateField at the selected public final field
    Then the client receives versioned encapsulateField edits for both dirty documents without writing workspace files
