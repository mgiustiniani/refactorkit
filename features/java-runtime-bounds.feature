@functional-requirement @REQ-JAVA-RUNTIME-BOUNDS-001
Feature: Java semantic adapter runtime bounds
  Requirement source: docs/integration-contracts.md, IDE Java refactoring backlog, P0 "Bound Java adapter runtime".
  Authority: the [ARC42 requirements appendix](../docs/arc42/appendix-requirements.adoc) owns integrated status; each scenario qualifies one transport boundary.
  Java runtime discovery must report only enforceable source, response, and cancellation limits.

  Scenario: Java semantic runtime publishes and enforces bounded cancellable work
    Given a Java analysis cache with a four-byte input budget and a five-byte source snapshot
    When I request Java analysis and encode an oversized daemon response
    Then Java runtime discovery distinguishes cancellable JDT reads from non-cancellable operations
    And cancellation during JDT AST parsing aborts analysis without caching partial results

  Scenario: Oversized LSP response frames are replaced without truncation
    Given an LSP Java workspace has a document-symbol response larger than its output limit
    When the JSON-RPC client requests that document's symbols over stdio
    Then the LSP advertises its response limit and returns a bounded JSON-RPC error with the original request id
    And a subsequent shutdown response remains valid and the server exits cleanly

  Scenario: Oversized LSP diagnostics notifications are replaced with a bounded warning
    Given an LSP Java workspace produces diagnostics larger than its notification output limit
    When the client initializes the LSP over stdio
    Then the oversized diagnostics notification is replaced by a bounded warning
    And the initialize response remains valid and the server exits cleanly

  Scenario: Oversized MCP resource responses are replaced without truncation
    Given an MCP workspace contains a scanned Java file larger than the response output limit
    When the MCP client reads that file resource over stdio
    Then MCP advertises its response limit and returns a bounded JSON-RPC error preserving the request id
    And a following ping response remains valid and the server exits cleanly

  Scenario: Oversized CLI output is discarded atomically
    Given a CLI search fixture produces output larger than the published process output limit
    When the source-built CLI searches that fixture
    Then the CLI discards all command output and returns a bounded "cli.outputLimitExceeded" diagnostic
    And CLI help publishes the same byte limit and a normal command still returns its complete output
