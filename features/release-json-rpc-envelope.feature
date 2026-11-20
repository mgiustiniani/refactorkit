@operational-requirement @partial @REQ-C-RELEASE-EVIDENCE-001
Feature: Release JSON RPC response envelope evidence
  The existing C16/C17 public API gate requires a response to publish its result
  or its error, never both, with the required protocol and correlation fields.
  A successful null result and a null error correlation id remain explicit.
  Legacy nullable envelopes remain readable; outgoing envelopes are canonical.
  Native parser refusals and unexpected internal failures do not leak the input
  or implementation exception messages.
  This does not qualify batches, request validation, or unsupported protocol cells.

  @REQ-C-RELEASE-EVIDENCE-001#release-json-rpc-producers-publish-one-outcome-and-required-null
  Scenario Outline: Release JSON RPC producers publish one outcome and required null
    Given a JSON RPC envelope producer uses "<producer>"
    When its response envelopes cross the selected serialization boundary
    Then every response publishes one outcome with exact payload and correlation
    And envelope qualification preserves owned inputs and closes its native processes

    Examples:
      | producer            |
      | library compact     |
      | library defaults    |
      | library omit nulls  |
      | legacy decode       |
      | daemon stdio        |
      | MCP stdio           |
