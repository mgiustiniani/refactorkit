# language: en
@functional-requirement @partial
Feature: A library caller cannot disable the protected CMake diagnostics gate
  Even if the caller supplies a disabled diagnostics gate to the public facade,
  the preview-bound CMake lease and changed compilation database must cause a
  refusal before any managed transaction or source/manifest write.
  Authority: docs/releases/v0.8.0-plan.md (C05/C08/C16); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-LIBRARY-GATE-001
  Scenario: A stale CMake database is refused despite a caller-supplied disabled gate
    Given an isolated CMake source with a compiler-verified move preview
    When the compilation database changes and library apply receives a disabled caller gate
    Then protected CMake apply refuses the stale database without a transaction
    And the original source and CMake manifest remain byte-exact
