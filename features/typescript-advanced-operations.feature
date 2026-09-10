@typescript-advanced @not-implemented
Feature: Compiler-bound TypeScript advanced operations retain managed-write authority
  # Execution contract: docs/requirements/v0.7.x-completion-contract.md, T5.
  # Architecture authority: docs/arc42/08-crosscutting-concepts.adoc.
  # Real pinned compiler success and deliberately corrupted negative proposals are distinct evidence.

  @REQ-TS-RELOCATION-AUTHORITY-001 @functional-requirement
  Scenario Outline: Relocation requires exact compiler diagnostics and retains guarded apply authority
    Given a compiler-bound TypeScript relocation workspace with "<condition>"
    When the relocation is previewed through its active semantic adapter
    Then the guarded relocation has outcome "<outcome>" and refusal code "<code>"
    And the relocation preserves approval diagnostics workspace bytes and rollback authority

    Examples:
      | condition                              | outcome      | code                                         |
      | clean source and compiler evidence     | PREVIEW      | none                                         |
      | compiler errors in the input snapshot  | REFUSED      | typescript.diagnosticsNotClean                |
      | changed project evidence after startup | REFUSED      | typescript.modelEvidenceChanged               |
      | unchecked JavaScript source            | REFUSED      | typescript.semanticCompletenessInsufficient   |
      | a deliberately broken edit proposal   | REFUSED      | typescript.diagnosticsNotClean                |
      | disk source differs from the snapshot | PREVIEW_ONLY | none                                         |

  @REQ-TS-RELOCATION-ELIGIBILITY-001 @functional-requirement
  Scenario Outline: Relocation refuses sources targets and compiler edits outside its qualified semantic context
    Given a compiler-bound TypeScript relocation workspace with "<condition>"
    When the relocation is previewed through its active semantic adapter
    Then the guarded relocation has outcome "REFUSED" and refusal code "<code>"
    And the relocation refusal states "<message>" without compiler write authority
    And the relocation preserves approval diagnostics workspace bytes and rollback authority

    Examples:
      | condition | code | message |
      | relocation to text without rootDir | none | Relocation target is not a recognized source in the source language |
      | relocation to text with rootDir | none | Relocation target is not a recognized source in the source language |
      | relocation changes source language | none | Relocation target is not a recognized source in the source language |
      | relocation changes explicit files config | none | Compiler relocation edits exceed source-only authority |
      | relocation target excluded from compiler | typescript.compilerDiagnosticsIncomplete | Required relocation source is outside compiler program: src/ignored/renamed.ts |
      | relocation source excluded from compiler | typescript.compilerDiagnosticsIncomplete | Required relocation source is outside compiler program: src/a.ts |

  @REQ-TS-COMPILER-EVIDENCE-MISSING-001 @functional-requirement
  Scenario: A valid compiler edit without captured exchange evidence grants no apply authority
    Given a compiler-bound TypeScript relocation workspace with "missing compiler exchange evidence"
    When the relocation is previewed through its active semantic adapter
    Then the guarded relocation has outcome "REFUSED" and refusal code "typescript.compilerAuthorityUnavailable"
    And the relocation preserves approval diagnostics workspace bytes and rollback authority

  @REQ-TS-SEMANTIC-RESTART-AUTHORITY-001 @operational-requirement
  Scenario: A real semantic-child crash and same-adapter restart cannot revive retained apply authority
    Given a compiler-bound TypeScript relocation workspace with "clean source and compiler evidence"
    When the relocation is previewed through its active semantic adapter
    Then the guarded relocation has outcome "PREVIEW" and refusal code "none"
    When the owning semantic child crashes and the same adapter restarts with the original snapshot
    Then the retained pre-restart plan and gate are refused with "diagnostics.unavailable" before any transaction
    When the relocation is previewed through its active semantic adapter
    Then the guarded relocation has outcome "PREVIEW" and refusal code "none"
    And the retained pre-restart plan and gate are refused with "diagnostics.unavailable" before any transaction
    And the relocation preserves approval diagnostics workspace bytes and rollback authority

  @REQ-TS-CHANGE-SIGNATURE-REFUSAL-001 @functional-requirement
  Scenario: Change signature refuses without declaration-family and call-site authority
    Given a compiler-bound TypeScript relocation workspace with "signature and inline function targets"
    When the TypeScript operation "changeSignature" is requested for an existing function
    Then the unsupported TypeScript operation refuses with "typescript.changeSignatureUnsupported"
    And the TypeScript operation leaves all files and transaction records unchanged

  @REQ-TS-INLINE-FUNCTION-REFUSAL-001 @functional-requirement
  Scenario Outline: Inline function never delegates to extract or inline variable
    Given a compiler-bound TypeScript relocation workspace with "signature and inline function targets"
    When the TypeScript operation "<operation>" is requested for an existing function
    Then the unsupported TypeScript operation refuses with "typescript.inlineFunctionUnsupported"
    And the TypeScript operation leaves all files and transaction records unchanged

    Examples:
      | operation      |
      | inlineMethod   |
      | inlineFunction |

  @REQ-TS-ORGANIZE-MODES-001 @functional-requirement
  Scenario Outline: Organize imports honors the explicit compiler mode and quote preference
    Given a compiler-bound TypeScript relocation workspace with "imports with used and unused bindings"
    When organize imports mode "<mode>" is previewed with quote preference "<quotes>"
    Then only the compiler-approved import changes for mode "<mode>" and quotes "<quotes>" are proposed
    And the TypeScript operation preserves approval diagnostics and rollback

    Examples:
      | mode           | quotes |
      | All            | double |
      | SortAndCombine | single |
      | RemoveUnused   | single |

  @REQ-TS-EXTRACT-ACTIONS-001 @functional-requirement
  Scenario Outline: Extraction uses an exact returned action and preserves the selected computation
    Given a compiler-bound TypeScript relocation workspace with "an extractable expression"
    When compiler action "<action>" from refactor "Extract Symbol" is previewed as "<operation>"
    Then the compiler action preserves the function results and its exact selection authority
    And the TypeScript operation preserves approval diagnostics and rollback

    Examples:
      | operation       | action           |
      | extractFunction | function_scope_0 |
      | extractConstant | constant_scope_0 |

  @REQ-TS-EXTRACT-LEGACY-REFUSAL-001 @functional-requirement
  Scenario: Legacy extract method cannot invent compiler action authority
    Given a compiler-bound TypeScript relocation workspace with "signature and inline function targets"
    When the TypeScript operation "extractMethod" is requested for an existing function
    Then the unsupported TypeScript operation refuses with "typescript.extractActionRequired"
    And the TypeScript operation leaves all files and transaction records unchanged

  @REQ-TS-INLINE-VARIABLE-ACTION-001 @functional-requirement
  Scenario: Inline variable uses its own returned compiler action
    Given a compiler-bound TypeScript relocation workspace with "an inlineable local variable"
    When compiler action "Inline variable" from refactor "Inline variable" is previewed as "inlineVariable"
    Then the compiler action preserves the function results and its exact selection authority
    And the TypeScript operation preserves approval diagnostics and rollback

  @REQ-TS-MOVE-DECLARATION-ACTION-001 @functional-requirement
  Scenario: Declaration move uses the returned interactive action and preserves callers
    Given a compiler-bound TypeScript relocation workspace with "a declaration with callers and an existing target"
    When compiler action "Move to file" from refactor "Move to file" is previewed as "moveDeclaration"
    Then the compiler action preserves the function results and its exact selection authority
    And the TypeScript operation preserves approval diagnostics and rollback

  @REQ-TS-MOVE-LEGACY-REFUSAL-001 @functional-requirement
  Scenario: Legacy move symbol cannot substitute a symbol spelling for a compiler selection
    Given a compiler-bound TypeScript relocation workspace with "signature and inline function targets"
    When the TypeScript operation "moveSymbol" is requested for an existing function
    Then the unsupported TypeScript operation refuses with "typescript.moveActionRequired"
    And the TypeScript operation leaves all files and transaction records unchanged

  @REQ-TS-RECIPE-AUTHORITY-001 @functional-requirement
  Scenario: A migration recipe retains its sole compiler-owned child plan
    Given a compiler-bound TypeScript relocation workspace with "clean compiler input"
    When a TypeScript migration recipe previews a relocation and non-mutating summaries
    Then the recipe retains the exact child plan rather than relabeling its authority
    And the TypeScript operation preserves approval diagnostics and rollback

  @REQ-TS-RECIPE-REFUSALS-001 @functional-requirement
  Scenario Outline: An incompatible recipe exposes no partially applicable plan
    Given a compiler-bound TypeScript relocation workspace with "clean compiler input"
    When the migration recipe is requested with "<fault>"
    Then the recipe refuses with "<code>" and exposes no child plan
    And the TypeScript operation leaves all files and transaction records unchanged

    Examples:
      | fault                          | code                            |
      | a second mutation              | recipe.singleAuthorityRequired  |
      | a disabled diagnostics gate    | recipe.semanticContextRequired  |
      | a generic diagnostics gate     | recipe.semanticContextRequired  |
      | an external command step       | recipe.childAuthorityRequired   |
      | a stale semantic snapshot      | recipe.childAuthorityRequired   |
      | apply without a retained plan  | recipe.retainedPlanApplyRequired |

  @REQ-TS-COMPOSITE-DIAGNOSTICS-001 @operational-requirement
  Scenario: Composite project diagnostics preserve compiler options without emitting build files
    Given a TypeScript project-reference migration workspace
    When the composite TypeScript projects are checked without emitting output
    Then every composite project has complete clean diagnostics
    And the project-reference workspace and transaction records are unchanged

  @REQ-TS-COMPOSITE-DIAGNOSTICS-002 @operational-requirement
  Scenario Outline: Composite diagnostics retain genuine option and source failures
    Given a TypeScript project-reference migration workspace
    And the reference graph contains "<fault>"
    When the composite TypeScript projects are checked without emitting output
    Then the composite diagnostics report only "<code>" errors
    And the project-reference workspace and transaction records are unchanged

    Examples:
      | fault                              | code   |
      | explicitly disabled incremental    | TS6379 |
      | explicitly disabled source redirect| TS6305 |
      | a type error in a referenced source| TS2322 |
      | an unresolved consumer import      | TS2307 |

  @REQ-TS-SNAPSHOT-PROJECT-MODEL-001 @operational-requirement
  Scenario: A staged project-reference graph is resolved from snapshot bytes rather than the old disk layout
    Given a TypeScript project-reference migration workspace
    When a relocated project and its reference edges are modeled from an immutable snapshot
    Then the staged model owns the relocated sources and the rewritten reference graph
    And the project-reference workspace and transaction records are unchanged

  @REQ-TS-SNAPSHOT-PROJECT-MODEL-002 @operational-requirement
  Scenario Outline: Invalid snapshot reference graphs refuse without borrowing valid disk configuration
    Given a TypeScript project-reference migration workspace
    When the staged reference graph has "<fault>"
    Then the snapshot model refuses with "<code>" and exposes no project graph
    And the project-reference workspace and transaction records are unchanged

    Examples:
      | fault                         | code                              |
      | omitted project configuration | typescript.configReferenceMissing |
      | a project-reference cycle     | typescript.referenceCycle         |
      | an escaping reference         | typescript.referenceInvalid       |
      | malformed project JSONC       | typescript.configSyntax           |

  @REQ-TS-PROJECT-MIGRATION-001 @functional-requirement
  Scenario: A sibling project migration preserves compiler imports and proven configuration references in one transaction
    Given a TypeScript project-reference migration workspace
    When the sibling library project is previewed as a project-reference migration
    Then the migration binds compiler edits and exact JSONC reference origins to the staged project graph
    And the project migration requires approval and restores its original files through rollback

  @REQ-TS-MIGRATION-CONFIG-AUTHORITY-001 @functional-requirement
  Scenario: Unsuitable native configuration suggestions do not replace proven migration origins
    Given a TypeScript project-reference migration workspace
    When the sibling library project is previewed as a project-reference migration
    Then the migration binds compiler edits and exact JSONC reference origins to the staged project graph
    And unsuitable real compiler configuration suggestions are excluded while native source edits are retained
    And the project migration requires approval and restores its original files through rollback

  @REQ-TS-PROJECT-MIGRATION-002 @functional-requirement
  Scenario Outline: An unsafe project migration refuses without partial edits or transactions
    Given a TypeScript project-reference migration workspace
    When the project migration is requested with "<fault>"
    Then the project migration refuses with "<code>" and preserves the workspace

    Examples:
      | fault                              | code                                         |
      | duplicate reference origins        | typescript.projectMigrationOriginInvalid     |
      | an uncaptured project file         | typescript.projectMigrationSnapshotIncomplete|
      | an existing destination            | typescript.projectMigrationTargetExists      |
      | configuration changed after start  | typescript.projectMigrationSnapshotChanged   |
      | path aliases needing other authority| typescript.projectMigrationScopeUnsupported |
      | a corrupted compiler proposal      | typescript.compilerAuthorityUnavailable      |

  @REQ-TS-PROJECT-MIGRATION-CHECKING-001 @functional-requirement
  Scenario: A TypeScript session cannot migrate an unchecked JavaScript source
    Given a TypeScript project-reference migration workspace
    When the project migration is requested with "unchecked JavaScript"
    Then the project migration refuses with "typescript.semanticCompletenessInsufficient" and preserves the workspace

  @REQ-TS-PROJECT-MIGRATION-APPLY-001 @functional-requirement
  Scenario Outline: A retained migration refuses changed apply authority before creating a transaction
    Given a TypeScript project-reference migration workspace
    When the sibling library project is previewed as a project-reference migration
    And the retained project migration is applied after "<change>"
    Then changed migration authority leaves no transaction and preserves the current files

    Examples:
      | change                         |
      | configuration drift            |
      | closing the semantic session   |
      | modifying the retained edit    |
      | selecting another workspace    |

  @REQ-TS-ADVANCED-CATALOGUE-001 @functional-requirement
  Scenario: The TypeScript catalogue exposes exact supported families rather than unsupported legacy aliases
    Given a compiler-bound TypeScript relocation workspace with "clean source and compiler evidence"
    Then the TypeScript catalogue contains only the eight bounded compiler-backed operation families
    And the TypeScript operation leaves all files and transaction records unchanged

  @REQ-TS-CAPTURED-MODEL-INPUTS-001 @functional-requirement
  Scenario: Build model attachment captures configuration origins for a managed migration
    Given a TypeScript project-reference migration workspace
    When the build model attaches configuration inputs to a source-only migration snapshot
    Then the migration binds compiler edits and exact JSONC reference origins to the staged project graph
    And the project migration requires approval and restores its original files through rollback

  @REQ-TS-CHECKED-JAVASCRIPT-RECIPE-001 @functional-requirement
  Scenario: A checked JavaScript recipe retains its JavaScript adapter and exact child authority
    Given a compiler-bound TypeScript relocation workspace with "checked JavaScript source"
    When a TypeScript migration recipe previews a relocation and non-mutating summaries
    Then the recipe retains the exact child plan rather than relabeling its authority
    And the TypeScript operation leaves all files and transaction records unchanged

  @REQ-TS-PROCESS-CLOSE-RACE-001 @non-functional-requirement
  Scenario: A completed child may leave the registry while its owner closes
    Given a real explicit Node executable for bounded lifecycle checking
    When a completed process leaves the registry immediately before iteration
    Then process-manager close completes without a collection exception or an owned live child

  @REQ-TS-NODE-PROBE-CLEANUP-001 @non-functional-requirement
  Scenario: A bounded sequence of short-lived Node probes closes without a collection race
    Given a real explicit Node executable for bounded lifecycle checking
    When sixty four short-lived Node version probes run sequentially
    Then every version probe succeeds without changing the existing attempt and timeout limits

  @REQ-TS-NODE-PROBE-EXIT-001 @operational-requirement
  Scenario: A failed Node version probe preserves its exit status without widening retry bounds
    Given an unsuitable bundled executable for a Node version probe
    When the bounded Node version probe runs that executable
    Then the probe refusal includes exit status 1 with the original attempt and timeout limits

  @REQ-TS-PROJECT-MIGRATION-AUXILIARY-001 @functional-requirement
  Scenario: Auxiliary configuration inputs retain exact model diagnostics and migration authority
    Given a TypeScript project-reference migration workspace
    When the project migration is previewed with auxiliary configuration inputs
    Then the migration binds compiler edits and exact JSONC reference origins to the staged project graph
    And the project migration requires approval and restores its original files through rollback
