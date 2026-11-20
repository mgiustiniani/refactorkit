# language: en
@functional-requirement @partial
Feature: CMake binding preview is not permission to apply without captured TU context
  Rewriting a literal CMake target does not prove the per-translation-unit
  compiler model. Until staged context is connected, approval fails closed
  before a transaction even when the preview contains exact CMake edits.
  Authority: docs/releases/v0.8.0-plan.md (C03/C05/C08); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-APPLY-CONTEXT-001
  Scenario: An approved CMake source move refuses managed apply without exact TU configuration
    Given an isolated CMake-backed C source with a literal target binding
    When a managed CMake source move preview is approved for apply without captured TU context
    Then managed apply refuses because CMake translation unit context is unavailable
    And the source and CMake manifest remain byte-exact with no transaction journal
