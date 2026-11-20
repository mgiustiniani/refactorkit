# language: en
@functional-requirement
Feature: Explicit compiler-bound Java field type repair
  Requirement: REQ-JAVA-TYPE-REPAIR-001, a bounded operation from the existing P1 IDE Java backlog.
  Type repair requires an exact JDT field binding, one captured Maven source-set owner, and an explicit requested type.
  It must refuse unsupported field shapes or incomplete source-set authority before writing.

  @REQ-JAVA-TYPE-REPAIR-001#change-a-private-field-type-only-with-exact-binding-and-source-set-authority
  Scenario Outline: Change a private field type only with exact binding and source-set authority
    Given a Maven Java type-repair fixture with "<ownership>" ownership and a "<visibility>" field
    When the IDE requests field "count" type "<newType>" with "<selector>" selector
    Then type repair is "<status>" with refusal "<refusal>" and leaves source untouched before apply
    And an accepted type repair applies, validates, and rolls back the exact workspace

    Examples:
      | ownership | visibility | newType | selector | status  | refusal                            |
      | unique    | private    | long    | exact    | PREVIEW | none                               |
      | unique    | public     | long    | exact    | REFUSED | java.typeRepair.fieldNotPrivate   |
      | ambiguous | private   | long    | exact    | REFUSED | java.diagnostics.sourceSetUnavailable |
      | unique    | private    | short   | exact    | REFUSED | java.typeRepair.typePairUnsupported |
      | unique    | private    | long    | missing  | REFUSED | java.typeRepair.selectionRequired |

  @REQ-JAVA-TYPE-REPAIR-001#lsp-proposes-only-a-versioned-edit-for-a-saved-private-field-type-repair
  Scenario Outline: LSP proposes only a versioned edit for a saved private field type repair
    Given a Maven Java type-repair fixture with "unique" ownership and a "<visibility>" field
    When the LSP requests field "count" type "long" at the exact saved declaration
    Then the LSP type-repair response is "<status>" with refusal "<refusal>" and respects client ownership
    And the LSP type-repair proposal leaves saved files and transaction state unchanged

  Examples:
      | visibility | status  | refusal                          |
      | private    | PREVIEW | none                             |
      | public     | REFUSED | java.typeRepair.fieldNotPrivate |

  @REQ-JAVA-TYPE-REPAIR-001#lsp-proposes-a-versioned-edit-for-a-dirty-private-field-type-repair
  Scenario: LSP proposes a versioned edit for a dirty private field type repair
    Given a Maven Java type-repair fixture with "unique" ownership and a "private" field
    And the client has an unsaved Java edit outside the selected field
    When the LSP requests field "count" type "long" at the exact buffer declaration
    Then the LSP type-repair response is "PREVIEW" with refusal "none" and respects client ownership
    And the LSP type-repair proposal leaves saved files and transaction state unchanged
