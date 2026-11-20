# language: en
@functional-requirement
Feature: C safe delete refuses incomplete reference analysis
  A reference service error or malformed/truncated result cannot prove a symbol unused.
  This Story tests a bounded protocol peer; exact configuration and consumer closure
  still need independent C13 qualification.
  Authority: docs/releases/v0.8.0-plan.md (C13); integrated status: docs/arc42/appendix-requirements.adoc.

  @implemented-and-validated @REQ-C-REFERENCES-FAIL-CLOSED-001#safe-delete-refuses-a-reference-reply-from-the-semantic-peer
  Scenario Outline: Safe delete refuses a <reference_reply> from the semantic peer
    Given an isolated C safe-delete workspace and a semantic peer with "<reference_reply>" references
    When safe delete previews an apparently unused private C function
    Then the reference result is unavailable and safe delete proposes no edit
    And the source bytes and transaction journal remain unchanged after the refused delete

    Examples:
      | reference_reply       |
      | protocol error        |
      | malformed result      |
      | truncated 257 results |
