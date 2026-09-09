@typescript-advanced-surfaces @not-implemented
Feature: Public TypeScript operations retain the owning semantic session
  # Authority: docs/requirements/v0.7.x-completion-contract.md, T5.
  # Architecture: docs/arc42/08-crosscutting-concepts.adoc.
  # The same authored Stories can use in-process dispatch or an explicitly supplied
  # packaged runtime. Evidence must bind the execution mode and archive/tree hashes.
  # A source-only execution never becomes packaged qualification or independent review.

  @REQ-TS-ADVANCED-CLI-HELP-001 @functional-requirement
  Scenario: CLI help exposes the advanced preview and catalogue entry points
    Given an isolated compiler-backed project migration workspace for "CLI"
    When CLI help is requested without opening a semantic session
    Then help names the advanced TypeScript command arguments without changing the workspace

  @REQ-TS-MIGRATION-SURFACES-001 @functional-requirement
  Scenario Outline: A public surface previews and applies the bounded project migration with managed rollback
    Given an isolated compiler-backed project migration workspace for "<surface>"
    When the project migration is requested through that public surface
    Then the public migration preview is compiler-proven and leaves the workspace unchanged
    And explicit public apply records one transaction and public rollback restores every original file

    Examples:
      | surface |
      | CLI     |
      | daemon  |
      | MCP     |

  @REQ-TS-RECIPE-SURFACES-001 @functional-requirement
  Scenario Outline: A public recipe retains its sole child operation and owning semantic apply gate
    Given an isolated compiler-backed project migration workspace for "<surface>"
    When the shipped TypeScript relocation recipe is previewed through that public surface
    Then the public migration preview is compiler-proven and leaves the workspace unchanged
    And explicit public apply records one transaction and public rollback restores every original file

    Examples:
      | surface |
      | CLI     |
      | daemon  |
      | MCP     |

  @REQ-TS-REFUSAL-SURFACES-001 @functional-requirement
  Scenario Outline: Public advanced refusals preserve typed codes without crossing language or session boundaries
    Given an isolated compiler-backed project migration workspace for "<surface>"
    When the public TypeScript operation "<operation>" is requested with "<authority>"
    Then the public refusal reports "<code>" without a transaction or workspace changes

    Examples:
      | surface | operation                 | authority       | code                                      |
      | CLI     | changeSignature           | owning          | typescript.changeSignatureUnsupported     |
      | CLI     | inlineFunction            | owning          | typescript.inlineFunctionUnsupported      |
      | daemon  | extractMethod             | owning          | typescript.extractActionRequired          |
      | daemon  | projectReferenceMigration | a foreign lease | typescript.refactoringAuthorityStale       |
      | MCP     | moveSymbol                | owning          | typescript.moveActionRequired             |
      | MCP     | recipe                    | a stale hash    | recipe.contextMismatch                    |

  @REQ-TS-LSP-OWNERSHIP-001 @functional-requirement
  Scenario: LSP 0.2 does not acquire managed TypeScript authority from the advanced catalogue
    Given an isolated compiler-backed project migration workspace for "LSP"
    When LSP ownership and a TypeScript project migration command are inspected
    Then LSP advertises client ownership and refuses the unmanaged command without workspace writes

  @REQ-TS-RECIPE-SESSION-001 @functional-requirement
  Scenario Outline: A new semantic session cannot adopt an old recipe plan even when it previews the same edit
    Given an isolated compiler-backed project migration workspace for "<surface>"
    When the shipped TypeScript relocation recipe is previewed through that public surface
    Then the public migration preview is compiler-proven and leaves the workspace unchanged
    When a replacement semantic session previews the same recipe child
    Then the old child refuses before WAL while the new child can apply and roll back

    Examples:
      | surface |
      | daemon  |
      | MCP     |

  @REQ-TS-ACTION-SURFACES-001 @functional-requirement
  Scenario Outline: Public compiler actions retain exact selections callers and observable arithmetic
    Given an isolated compiler-backed project migration workspace for "<surface>"
    When the exact TypeScript compiler action "<operation>" is previewed through that public surface
    Then the public migration preview is compiler-proven and leaves the workspace unchanged
    And explicit public apply records one transaction and public rollback restores every original file

    Examples:
      | surface | operation       |
      | CLI     | extractFunction |
      | daemon  | extractConstant |
      | MCP     | inlineVariable  |
      | daemon  | moveDeclaration |
    # The positive declaration move is within one configured compiler program.
    # Cross-project callers require complete returned edits and are tested as a refusal below.

  @REQ-TS-COMPOSITE-MOVE-REFUSAL-001 @functional-requirement
  Scenario: Incomplete cross-project declaration edits refuse rather than dropping the incoming caller
    Given an isolated compiler-backed project migration workspace for "MCP"
    When a compiler declaration move omits the separate project caller
    Then the public refusal reports "typescript.diagnosticsNotClean" without a transaction or workspace changes

  @REQ-TS-ORGANIZE-SURFACES-001 @functional-requirement
  Scenario Outline: Public organize-import modes preserve their distinct compiler contracts
    Given an isolated compiler-backed project migration workspace for "<surface>"
    When the TypeScript organize-import mode "<mode>" is previewed through that public surface
    Then the public migration preview is compiler-proven and leaves the workspace unchanged
    And explicit public apply records one transaction and public rollback restores every original file

    Examples:
      | surface | mode           |
      | CLI     | All            |
      | daemon  | SortAndCombine |
      | MCP     | RemoveUnused   |

  @REQ-TS-CATALOGUE-SURFACES-001 @functional-requirement
  Scenario Outline: The public catalogue names exact bounded TypeScript families without legacy promises
    Given an isolated compiler-backed project migration workspace for "<surface>"
    When the TypeScript catalogue is requested through that public surface
    Then the public catalogue contains eight exact families and creates no plan or transaction

    Examples:
      | surface |
      | CLI     |
      | daemon  |
      | MCP     |
