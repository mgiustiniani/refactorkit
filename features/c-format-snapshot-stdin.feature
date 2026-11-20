# language: en
@functional-requirement @partial
Feature: Format acquired C source through standard input
  A C formatting preview consumes the exact source text selected from its immutable
  project snapshot. The workspace path is only an assumed filename for formatter
  context; clang-format must not reopen the live source or require a scratch file.
  Style capture, staged validation and managed apply/rollback remain separate work.
  Authority: docs/releases/v0.8.0-plan.md (C07); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-FORMAT-SNAPSHOT-INPUT-001#the-formatter-receives-snapshot-text-on-both-formatting-passes
  Scenario: The formatter receives snapshot text on both formatting passes
    Given an isolated C formatting workspace with a captured source snapshot
    When the managed C format preview is requested
    Then clang-format receives the acquired source and idempotence output through standard input
    And clang-format uses the source path only as assumed filename without creating scratch files
