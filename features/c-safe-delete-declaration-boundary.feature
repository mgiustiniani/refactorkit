# language: en
@functional-requirement
Feature: C safe delete keeps neighboring declarations
  A closed reference result permits only a proven, source-owned declaration range.
  When a textual declaration range cannot be isolated, the operation must refuse;
  a separated private definition can receive a preview without removing its caller.
  Consumer/configuration closure remains C13 qualification work.
  Authority: docs/releases/v0.8.0-plan.md (C13); integrated status: docs/arc42/appendix-requirements.adoc.

  @implemented-and-validated @REQ-C-DELETE-NEIGHBOUR-001#an-unused-form-next-to-another-declaration-cannot-be-deleted-by-line-range
  Scenario Outline: An unused <form> next to another declaration cannot be deleted by line range
    Given an isolated C safe-delete workspace and a semantic peer with "complete empty" references
    And an unused "<form>" and a distinct declaration share a C source line
    When safe delete previews the private C symbol on that line
    Then safe delete refuses to remove the neighboring declaration
    And the source bytes and transaction journal remain unchanged after the refused delete

    Examples:
      | form     |
      | function |
      | variable |

  @implemented-and-validated @REQ-C-DELETE-NEIGHBOUR-001#safe-delete-previews-one-isolated-private-function-and-preserves-the-entry-point
  Scenario: Safe delete previews one isolated private function and preserves the entry point
    Given an isolated C safe-delete workspace and a semantic peer with "declaration only" references
    When safe delete previews an apparently unused private C function
    Then safe delete previews only the unused private definition and preserves the entry point without workspace writes

  @implemented-and-validated @REQ-C-DELETE-NEIGHBOUR-001#safe-delete-refuses-a-private-function-with-an-address-taking-consumer
  Scenario: Safe delete refuses a private function with an address-taking consumer
    Given an isolated C safe-delete workspace and a semantic peer with "address-taking use" references
    When safe delete previews an apparently unused private C function
    Then safe delete refuses the callback target because its address is used
    And the source bytes and transaction journal remain unchanged after the refused delete

  @implemented-and-validated @REQ-C-DELETE-NEIGHBOUR-001#safe-delete-refuses-a-public-function-without-external-consumer-evidence
  Scenario: Safe delete refuses a public function without external-consumer evidence
    Given an isolated C safe-delete workspace with an unavailable semantic runtime
    When safe delete previews a public C function
    Then safe delete refuses the public function because external consumers are unavailable
    And the source bytes and transaction journal remain unchanged after the refused delete
