# language: en
@non-functional-requirement @implemented-and-validated
Feature: Preserve managed rollback behavior through one language-neutral executor
  Rollback surfaces need one shared resolution sequence without changing PatchEngine authority.
  CLI, LSP, and MCP select APPLIED_ONLY visibility; daemon selects JOURNAL_RECORD visibility.
  Each caller selects RollbackMode and owns response, session lifecycle, refresh, and diagnostics policy.

  @REQ-MANAGED-ROLLBACK-EXECUTOR-001
  Scenario: One visible applied record is durably rolled back through the real engine
    Given the normalized workspace journal contains one complete "APPLIED" record for raw ID "transaction-123e4567-e89b-42d3-a456-426614174000"
    And the record has distinct fixture values for every complete-record component:
      | complete-record component                                  |
      | schema, implementation, and API versions                   |
      | transaction, including its rollback edit                   |
      | operation and forward edit                                 |
      | pre-images, post-images, and created directories           |
      | pre-snapshot and post-snapshot hashes                      |
      | state, history, updated timestamp, and failure detail      |
    And the caller supplies "APPLIED_ONLY", mode "NORMAL", and an allow guard
    When the language-neutral executor handles the raw ID with TransactionLog and the real PatchEngine for that workspace
    Then the executor-owned sequence is exactly:
      | order | observable action                                                                                                      |
      | 1     | pass the raw ID once to TransactionId.parseOrNull without trimming, case-folding, or canonicalization                    |
      | 2     | call TransactionLog.loadRecord once before any workspace lock and retain its complete result                            |
      | 3     | apply APPLIED_ONLY visibility to that result                                                                           |
      | 4     | invoke the guard once with that exact visible record before any workspace lock                                         |
      | 5     | invoke the real public PatchEngine.rollback with the resolved record transaction and supplied NORMAL mode exactly once |
      | 6     | wrap the exact engine result without another executor-owned journal lookup                                              |
    And PatchEngine alone performs its under-lock reload, recovery, state, image, conflict, force, metadata, and journal-transition rules
    And before the outcome returns, a newly opened journal reads "ROLLED_BACK" and the workspace paths, bytes, metadata, and created-directory state match the recorded pre-apply image
    And the outcome is "RolledBack" with the exact transaction from ApplyResult.Applied and the same complete resolved pre-lock record value
    And the retained resolved record still says "APPLIED" and is described as lookup evidence rather than current or post-rollback state
    And the executor neither reloads nor reconstructs that resolved record for the outcome
    And the executor performs none of these caller-owned policies:
      | caller-owned policy                                                        |
      | select NORMAL or FORCE mode                                                |
      | render a response, error, exit code, tool text, or changed-file projection |
      | refresh a project, snapshot, symbol index, or semantic adapter             |
      | run, publish, or render diagnostics                                        |
      | remove or clear pending plans, audit state, or semantic sessions           |

  @REQ-MANAGED-ROLLBACK-EXECUTOR-002
  Scenario Outline: Short circuits and surface visibility retain distinct outcomes
    Given surface "<surface>" submits raw transaction ID "<raw ID>"
    And its initial journal condition is "<journal condition>" with visibility "<visibility>" and guard behavior "<guard>"
    When the executor handles the request with the caller-supplied "NORMAL" mode
    Then its observable work is exactly "<work>"
    And its exact typed outcome is "<outcome>"
    And its resolved-record and failure evidence is "<evidence>"
    And visible absence, preflight rejection, engine refusal, and journal failure are not reclassified as one another
    And the executor performs no response projection, session lifecycle, refresh, or diagnostics policy

    Examples:
      | surface            | raw ID                                                       | journal condition                                                                  | visibility     | guard                                                                                               | work                                                                                                           | outcome                                                                                       | evidence                                                                             |
      | any rollback surface | transaction-123E4567-E89B-42D3-A456-426614174000             | journal must not be accessed                                                       | caller-selected | must not run                                                                                        | parseOrNull=1; outer loadRecord=0; guard=0; real rollback=0; workspace lock=0                                   | InvalidTransactionId carrying the exact unnormalized raw ID                                  | no parsed ID and no resolved record                                                |
      | CLI                | transaction-123e4567-e89b-42d3-a456-426614174000             | no ID-matching record exists                                                       | APPLIED_ONLY   | must not run                                                                                        | parseOrNull=1; outer loadRecord=1 before lock; guard=0; real rollback=0; workspace lock=0                       | TransactionNotFound with the parsed ID and APPLIED_ONLY visibility                            | no resolved record                                                                 |
      | CLI, LSP, and MCP  | transaction-123e4567-e89b-42d3-a456-426614174000             | one complete ID-matching ROLLED_BACK record exists                                 | APPLIED_ONLY   | must not run                                                                                        | parseOrNull=1; outer loadRecord=1 before lock; visibility hides record; guard=0; real rollback=0; workspace lock=0 | TransactionNotFound with the parsed ID and APPLIED_ONLY visibility                            | hidden non-APPLIED record is not exposed                                           |
      | daemon             | transaction-123e4567-e89b-42d3-a456-426614174000             | one complete ID-matching ROLLED_BACK record exists                                 | JOURNAL_RECORD | allow                                                                                               | parseOrNull=1; outer loadRecord=1 before lock; guard=1 before lock; real rollback=1; workspace lock=1           | Refused with the exact resolved record and exact ordered transaction.notApplied diagnostic    | same complete ROLLED_BACK record; no reload or reconstruction by executor            |
      | LSP                | transaction-123e4567-e89b-42d3-a456-426614174000             | one complete ID-matching APPLIED record exists                                     | APPLIED_ONLY   | both an unrelated document is dirty and an affected path is open; reject dirty-document-first with exact DOCUMENT_VERSION_MISMATCH payload | parseOrNull=1; outer loadRecord=1 before lock; visibility then guard=1; real rollback=0; workspace lock=0        | PreflightRejected with the exact resolved record and unchanged surface rejection payload      | same complete APPLIED record; rejection precedes engine lock                         |
      | daemon             | transaction-123e4567-e89b-42d3-a456-426614174000             | outer loadRecord throws exact TransactionLogException E_lookup                     | JOURNAL_RECORD | must not run                                                                                        | parseOrNull=1; outer loadRecord=1 before lock and throws; guard=0; real rollback=0; workspace lock=0            | JournalLookupFailed with the parsed ID and the same E_lookup                                  | no resolved record; exact code, message, and cause retained                          |
      | daemon             | transaction-123e4567-e89b-42d3-a456-426614174000             | outer loadRecord resolves one complete APPLIED record, then under-lock engine journal access throws exact TransactionLogException E_call | JOURNAL_RECORD | allow                                                                                               | parseOrNull=1; outer loadRecord=1 before lock; guard=1 before lock; real rollback=1; workspace lock=1           | RollbackCallJournalFailed with the exact resolved record and the same E_call                  | same complete APPLIED record; exact code, message, and cause retained               |
