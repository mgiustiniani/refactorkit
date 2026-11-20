# language: en
# Authority: REQ-C16-SAFE-DELETE-MANAGED-001 and the C13/C16 boundary in ARC42.
@functional-requirement
Feature: Managed C safe delete through daemon and MCP
  Both managed transports expose the same compiler-proven safe-delete preview,
  require explicit Apply, and restore the exact workspace on rollback.

  @implemented-and-validated @REQ-C16-SAFE-DELETE-MANAGED-001#c-safe-delete-applies-through-daemon-and-mcp-with-exact-rollback
  Scenario Outline: C safe-delete applies through daemon and MCP with exact rollback
    Given an isolated C safe-delete workspace for "<surface>" with explicit Clang tools
    When the "<surface>" client requests a safe-delete preview
    Then the "<surface>" reports a compiler-proven safe-delete preview without writing
    And explicit managed Apply and rollback restore the original C workspace

    Examples:
      | surface |
      | daemon  |
      | MCP     |
