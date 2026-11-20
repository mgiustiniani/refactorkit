# language: en
@functional-requirement @partial
Feature: Managed apply moves a shared C header across captured translation units
  A captured source/header snapshot may move one workspace header when its
  literal target_sources owner and every literal C translation-unit consumer
  are known. The plan rewrites those includes and the target binding together;
  Clang checks every captured TU before and after the staged move.
  Authority: docs/requirements/c-cmake-header-managed-apply.md; integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-HEADER-MANAGED-APPLY-001#move-a-shared-header-with-every-captured-tu-owner
  Scenario: Move a shared header with every captured TU owner
    Given an isolated multi-TU CMake project with "shared-header"
    When a captured shared header is previewed and approved for managed apply
    Then managed apply is "applied" with correct staged diagnostics
    And applied or refused state is consistent with WAL and rollback
