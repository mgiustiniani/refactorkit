@functional-requirement @partial @REQ-DAEMON-REQUEST-ISOLATION-001
Feature: Daemon request failures preserve session liveness
  The daemon returns an internal error for a non-fatal handler failure and keeps
  its single request worker available for the next request.

  @REQ-DAEMON-REQUEST-ISOLATION-001#non-fatal-handler-errors-receive-a-response-and-do-not-kill-the-daemon-worker
  Scenario: Non-fatal handler errors receive a response and do not kill the daemon worker
    Given a daemon request dispatcher raises an assertion error once
    When the failing request and a valid follow-up are submitted to one daemon scheduler
    Then the failing request receives a JSON-RPC internal error response
    And the follow-up receives a success response from the same scheduler
