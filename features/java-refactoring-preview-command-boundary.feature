# language: en
@non-functional-requirement @implemented-and-validated
Feature: Preserve Java refactoring preview planning through one typed command boundary
  Java preview callers need one canonical downstream planner path without sharing protocol policy.
  The boundary covers only five plan-only Java commands and ends when the exact planner result escapes.
  Managed apply remains a separate authority and lifecycle.

  @REQ-JAVA-PREVIEW-COMMAND-001
  Scenario Outline: Complete typed "<command>" values preserve one existing planner invocation
    Given one planning call supplies the exact immutable snapshot "S-current" and current Java adapter "A-current"
    And one immutable complete typed "<command>" command contains exactly <complete values>
    And the exact existing planner call <planner call> will return the distinct immutable PatchPlan "<plan>"
    When the command is previewed through the Java refactoring preview command boundary
    Then exactly that existing planner is selected and invoked exactly once
    And no other Java planner is invoked
    And the planner receives the same "S-current" snapshot instance and every command value exactly as supplied
    And every adapter-backed planner is constructed with the same "A-current" Java adapter instance
    And no replacement Java adapter is created for an adapter-independent planner
    And the boundary does not alias, default, parse, convert, normalize, sort, deduplicate, or semantically reinterpret an argument
    And the returned value is the same "<plan>" PatchPlan instance without wrapping, copying, or regenerating its identity
    And its operation, status, snapshot hash, summary, evidence, warnings, diagnostic order, affected files, workspace edit, authority data, confidence, risk, and refusal code remain unchanged

    Examples:
      | command         | complete values                                                                                   | planner call                                                                                                                    | plan               |
      | RenameClass     | symbolId "com.acme.billing.Invoice" and newName "Statement"                                    | JavaRenameClassPlanner(A-current).preview(S-current, "com.acme.billing.Invoice", "Statement")                               | P-rename-class     |
      | RenameMember    | symbolId "com.acme.billing.Invoice#total()" and newName "grandTotal"                            | JavaRenameMemberPlanner(A-current).preview(S-current, "com.acme.billing.Invoice#total()", "grandTotal")                     | P-rename-member    |
      | MoveSourceRoot  | from path "modules/legacy/src/main/java" and to path "modules/billing/src/main/java"            | JavaMoveSourceRootPlanner(A-current).preview(S-current, from path, to path)                                                      | P-move-source-root |
      | OrganizeImports | the single Java file path "src/main/java/com/acme/billing/Invoice.java"                          | JavaOrganizeImportsPlanner().previewSingleFile(S-current, the single exact file path)                                           | P-organize-imports |
      | SafeDelete      | symbolId "com.acme.legacy.ObsoleteTax" and the complete boolean force value "true"              | JavaSafeDeletePlanner(A-current).preview(S-current, "com.acme.legacy.ObsoleteTax", true)                                      | P-safe-delete      |

  @REQ-JAVA-PREVIEW-COMMAND-002
  Scenario: The generic Java adapter remains a compatibility decoder into the canonical path
    Given complete generic RefactoringRequest values map to these complete typed commands:
      | operation       | generic request values                                                            | typed command values                                                       |
      | renameClass     | symbolId and arguments.newName                                                     | RenameClass with the decoded symbolId value and newName                     |
      | renameMember    | symbolId and arguments.newName                                                     | RenameMember with the decoded symbolId value and newName                    |
      | moveSourceRoot  | arguments.from and arguments.to                                                    | MoveSourceRoot with the two decoded path values                             |
      | organizeImports | arguments.file                                                                     | OrganizeImports with exactly one decoded Java file path                     |
      | safeDelete      | symbolId and arguments.force, with the existing false default when force is absent | SafeDelete with the decoded symbolId value and a complete boolean force     |
    When JavaLanguageAdapter.applyRefactoring receives each complete generic request
    Then its existing generic string, path, and boolean decoding occurs before the typed boundary
    And it dispatches the resulting complete command through the same canonical mapping from "REQ-JAVA-PREVIEW-COMMAND-001"
    And it has no parallel direct-planner path for any of the five operations
    And it returns the exact PatchPlan returned by that canonical downstream invocation
    When the generic adapter instead receives an admitted operation with a missing required value or an unknown operation
    Then its existing operation-specific missing-request and unknown-operation behavior remains generic Java adapter behavior
    And any resulting generic refused PatchPlan retains its existing operation, summary, warnings, snapshot binding, and refusal semantics
    And no incomplete or unknown generic request is reclassified as a daemon or MCP protocol failure by the typed boundary
    And no typed command is constructed until the generic decoder has produced every required typed value

  @REQ-JAVA-PREVIEW-COMMAND-003
  Scenario: Daemon and MCP keep distinct surface policy around the same typed dispatch
    Given daemon JSON-RPC and MCP each receive every admitted Java operation "renameClass, renameMember, moveSourceRoot, organizeImports, safeDelete"
    And before constructing a command each surface retains this existing policy:
      | surface         | raw work before the boundary                                                                                                                       | refused-plan policy                                      | pending-plan admission          | rendering after planning       |
      | daemon JSON-RPC | parse raw fields and primitive arguments, apply existing aliases, fallbacks, defaults and conversions, then reject missing or unknown input in its existing order | return its PLAN_REFUSED protocol failure and admit nothing | retain its non-refused plans     | structured daemon plan JSON    |
      | MCP             | parse raw tool arguments and primitive arguments, apply existing aliases, fallbacks, defaults and conversions, then reject missing or unknown input in its existing order | return its existing refusal tool result and admit nothing  | retain only PREVIEW-status plans | existing MCP tool content text |
    And two consecutive validated calls have exact current pairs "S-1, A-1" and "S-2, A-2"
    When each surface constructs and previews its complete typed command
    Then each call supplies its own exact current snapshot and Java adapter pair to the fieldless boundary
    And no startup, previous-call, replacement, or other surface's Java adapter is used
    And no missing or unknown raw request constructs a typed command or invokes a Java planner
    And after the exact PatchPlan returns each surface keeps its listed refusal, admission, and rendering policy
    And daemon and MCP converge only on typed Java planner dispatch, not on protocol parsing, error wording, admission, or projection

  @REQ-JAVA-PREVIEW-COMMAND-004
  Scenario: The boundary remains stateless, plan-only, and closed to the five-command slice
    Given one isolated admitted planner invocation returns the exact PatchPlan "P-returned"
    And another isolated admitted planner invocation throws the exact exception object "E-planner" with distinct type, code, message, and cause
    And workspace bytes, process activity, pending plans, diagnostics calls, locks, journals, transactions, and lifecycle calls are recorded
    When both commands are previewed through the fieldless boundary
    Then the first invocation returns the same "P-returned" instance unchanged
    And the same "E-planner" object escapes the second invocation with its type, code, message, and cause unchanged
    And after each call the boundary retains no snapshot, adapter, command, plan, exception, cache, lease, session, or other state
    And the command catalogue contains exactly:
      | admitted plan-only Java command |
      | RenameClass                     |
      | RenameMember                    |
      | MoveSourceRoot                  |
      | OrganizeImports for one file    |
      | SafeDelete                      |
    And it has no command variant, route, or adoption ownership for:
      | excluded scope                                                                                 |
      | moveClass Plan, Guidance, or LexicalReview outcomes                                            |
      | Kotlin routes                                                                                  |
      | TypeScript or JavaScript routes                                                                |
      | external semantic or mixed-language routes                                                     |
      | experimental, extract-method, change-signature, or Maven-special routes                        |
      | CLI or LSP adoption                                                                            |
      | multi-file organize imports                                                                    |
    And it performs no protocol parsing or rendering and no pending-plan admission, lookup, mutation, removal, or clearing
    And it performs no filesystem write and independently executes no diagnostics
    And it invokes no PatchEngine, workspace lock, write-ahead log, transaction, recovery, managed apply, automatic rollback, or explicit rollback
    And it owns no snapshot refresh, adapter or session lifecycle, watcher, index, process, broad operation catalogue, or persistence mechanism
    And preview dispatch ends at PatchPlan return or unchanged planner exception without producing an approval, apply identity, or combined preview-and-apply request
