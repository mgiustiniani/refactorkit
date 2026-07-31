# language: en
@non-functional-requirement @implemented-and-validated
Feature: Retain pending plans in a bounded session store
  The extraction constrains resource use, isolation, and compatibility without adding a protocol capability.
  Each integration surface keeps authority and lifecycle decisions while sharing only retention mechanics.

  @REQ-PENDING-PLAN-STORE-001
  Scenario: Exact session payloads follow a true 128-entry access-order bound
    Given sessions "A" and "B" each own a separate language-neutral pending-plan store
    And each store has capacity 128 with true access-order LRU behavior
    And session "A" has retained the exact opaque payloads for plans "plan-001" through "plan-128" in that order
    When session "A" successfully looks up "plan-001"
    And session "A" inserts "plan-129" with its exact opaque payload
    Then session "A" contains exactly 128 pending-plan payloads
    And "plan-001" remains available with the exact payload that was inserted
    And "plan-002", the previously second-oldest plan, is evicted
    And session "B" cannot look up any payload retained by session "A"
    And an absent or evicted plan ID produces that surface's existing missing-plan response and requires a new preview
    When the store evicts an entry, removes an ID, or clears a session
    Then it changes only its in-memory references
    And it creates no workspace change, filesystem write, workspace lock, write-ahead-log record, managed transaction, or rollback evidence

  @REQ-PENDING-PLAN-STORE-002
  Scenario: Integration surfaces preserve their existing lifecycle policies
    Given the shared store exposes only insert, successful access-order lookup, remove, and clear
    When each surface replaces its private pending-plan map with a store owned by that session
    Then these existing surface policies remain outside the store:
      | surface | admission preserved | removal and clear behavior preserved |
      | daemon  | non-refused managed previews; importer previews only when apply-eligible | discard and refusal remove the selected ID; successful apply, successful rollback, project open, and close clear all IDs |
      | LSP     | non-refused plans only after edit and document-version checks | successful apply removes the selected ID; general refusal and successful rollback do not intrinsically clear; initialize and document open, change, save, or close clear all IDs; no discard surface |
      | MCP     | refactoring results only when PREVIEW; importer results remain unconditionally registered | successful apply and stale-Kotlin refusal remove the selected ID; general refusal and successful rollback otherwise retain IDs; project scan and close clear all IDs; no discard surface |
    And daemon JSON-RPC, LSP, and MCP keep their existing success and error responses
    And admission, snapshot, apply, refusal, rollback, project or document, and close lifecycle policy stays at the existing surface call sites
    But the extraction does not invent a uniform lifecycle rule
