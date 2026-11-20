# language: en
# Authority: bounded C02 launch admission; ARC42 owns integrated status and the 0.8.0 plan owns the complete target.
@functional-requirement @partial
Feature: Admit C semantic process configuration before launching clangd
  Captured compiler commands are data, not authority to execute project code.
  Semantic queries use a private admitted database and ignore project clangd
  configuration. Unsupported flags refuse before any semantic process starts;
  an initialization timeout terminates the owned process tree.

  @REQ-C-CLANGD-LAUNCH-001#a-c-semantic-query-uses-only-admitted-launch-configuration @c-clangd-launch-configuration
  Scenario Outline: A C semantic query uses only admitted launch configuration
    Given an isolated C project with "<configuration>" launch inputs
    When the configured reference clangd is asked for the C function definition
    Then semantic launch is "<disposition>" with the captured C context
    And the query preserves project inputs and reaps every owned process

    Examples:
      | configuration           | disposition |
      | explicit C17 arguments  | admitted    |
      | hostile project config  | admitted    |
      | plugin arguments        | refused     |
      | response file           | refused     |
      | compiler config file    | refused     |
      | command string          | refused     |

  @REQ-C-CLANGD-LAUNCH-001#a-clangd-initialize-timeout-terminates-its-descendant-process-tree
  Scenario: A clangd initialize timeout terminates its descendant process tree
    Given a C project with a fake clangd that starts a child and waits
    When clangd initialization exceeds its deadline
    Then startup refuses after the timeout and closes the complete process tree
    And the query preserves project inputs and reaps every owned process

  @REQ-C-CLANGD-LAUNCH-001#cancelling-clangd-initialization-closes-its-descendant-process-tree
  Scenario: Cancelling clangd initialization closes its descendant process tree
    Given a C project with a fake clangd that starts a child and waits
    When clangd initialization is cancelled after its child starts
    Then cancellation promptly refuses startup and closes the complete process tree
    And the query preserves project inputs and reaps every owned process

  @REQ-C-CLANGD-LAUNCH-001#clangd-starts-a-fresh-session-after-initialize-cancellation
  Scenario: Clangd starts a fresh session after initialize cancellation
    Given a C project with a fake clangd that starts a child and waits
    When the cancelled clangd client restarts against a responding server
    Then the restart completes initialization with fresh process provenance
    And the query preserves project inputs and reaps every owned process

  @REQ-C-CLANGD-LAUNCH-001#concurrent-closes-during-initialize-reap-one-process-tree
  Scenario: Concurrent closes during initialize reap one process tree
    Given a C project with a fake clangd that starts a child and waits
    When concurrent callers close the client while initialization is pending
    Then startup refuses and every close caller completes cleanly
    And the query preserves project inputs and reaps every owned process

  @REQ-C-CLANGD-LAUNCH-001#closing-during-runtime-admission-prevents-a-late-clangd-launch
  @c-clangd-runtime-admission-close
  Scenario: Closing during runtime admission prevents a late clangd launch
    Given a C project with a fake clangd that starts a child and waits
    When the client closes while native runtime admission is blocked
    Then startup refuses without launching clangd or retaining process provenance
    And the query preserves project inputs and reaps every owned process

  @REQ-C-CLANGD-LAUNCH-001#clangd-rejects-malformed-initialize-response-and-reaps-its-process-tree
  Scenario: Clangd rejects malformed initialize response and reaps its process tree
    Given a C project with fake clangd that starts a child and sends malformed LSP
    When clangd returns malformed initialize framing while its child is alive
    Then startup refuses and closes the malformed server process tree
    And the query preserves project inputs and reaps every owned process

  @REQ-C-CLANGD-LAUNCH-001#clangd-refuses-oversized-initialize-frame-and-reaps-its-process-tree
  Scenario: Clangd refuses oversized initialize frame and reaps its process tree
    Given a C project with fake clangd that starts a child and announces an oversized LSP frame
    When clangd announces an initialize frame over the protocol size limit
    Then startup refuses the oversized frame and closes the complete process tree
    And the query preserves project inputs and reaps every owned process

  @REQ-C-CLANGD-LAUNCH-001#clangd-rejects-a-truncated-initialize-frame-and-reaps-its-process-tree
  Scenario: Clangd rejects a truncated initialize frame and reaps its process tree
    Given a C project with fake clangd that starts a child and announces a truncated LSP frame
    When clangd closes initialize output before sending the declared body length
    Then startup refuses the truncated frame and closes the complete process tree
    And the query preserves project inputs and reaps every owned process

  @REQ-C-CLANGD-LAUNCH-001#c-semantic-process-stdout-overflow-reaps-the-descendant-tree
  Scenario: C semantic process stdout overflow reaps the descendant tree
    Given a bounded C semantic process that starts a child and exceeds its stdout budget
    When the C semantic process writes beyond its managed stdout limit
    Then stdout overflow refuses the process and reaps its complete process tree
    And the query preserves project inputs and reaps every owned process

  @REQ-C-CLANGD-LAUNCH-001#c-semantic-process-stderr-overflow-truncates-diagnostics-and-reaps-the-descendant-tree
  Scenario: C semantic process stderr overflow truncates diagnostics and reaps the descendant tree
    Given a bounded C semantic process that starts a child and exceeds its stderr budget
    When the C semantic process writes beyond its managed stderr limit
    Then stderr overflow truncates diagnostics and reaps the complete process tree
    And the query preserves project inputs and reaps every owned process

  @REQ-C-CLANGD-LAUNCH-001#clangd-input-capture-and-currency-require-owned-database-paths
  Scenario Outline: Clangd input capture and currency require owned database paths
    Given a native clangd database ownership fixture "<input>"
    When the C semantic session observes its configured database ownership boundary
    Then database ownership produces "<outcome>" without unavailable analysis becoming a result
    And database ownership preserves fixture bytes and closes native processes

    Examples:
      | input                       | outcome          |
      | exact 8 MiB C11 database     | definition       |
      | root ancestor link at start | startup refused  |
      | malformed UTF8 ignored note | startup refused  |
      | absent alternate under link | startup refused  |
      | alternate parent linked live| currency refused |
      | root ancestor linked live   | currency refused |

  @REQ-C-CLANGD-LAUNCH-001#managed-c-rename-preserves-the-configuration-refusal
  Scenario Outline: Managed C rename preserves the configuration refusal
    Given an owned C compilation database points to an absent include directory
    When "<surface>" requests the managed C rename preview
    Then the managed preview returns the clangd configuration refusal
    And the refused rename leaves the compilation inputs and journal untouched

    Examples:
      | surface |
      | CLI     |
      | daemon  |
      | LSP     |
      | MCP     |
