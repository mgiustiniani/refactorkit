# language: en
# Authority: executable path-selection requirement; ARC42 owns integrated status.
@functional-requirement
Feature: Resolve Java organize-imports file selection for integration consumers
  Absolute and workspace-relative selections identify the same saved Java source.
  Invalid selection refuses the entire preview and cannot masquerade as an unchanged file.

  @REQ-JAVA-IMPORT-PATH-001
  Scenario Outline: Organize imports resolves selected paths or refuses the entire request
    Given an unsorted Java import fixture selected with "<selection>"
    When the Java planner and CLI preview the selected import paths
    Then the import selection returns "<outcome>" with diagnostic "<diagnostic>"
    And import path selection preserves all fixture bytes and the saved snapshot

    Examples:
      | selection            | outcome | diagnostic                  |
      | absolute             | changed | none                        |
      | normalized relative  | changed | none                        |
      | duplicate spellings  | changed | none                        |
      | missing              | refused | java.sourceFileNotFound     |
      | outside workspace    | refused | java.pathOutsideWorkspace   |
      | valid and missing    | refused | java.sourceFileNotFound     |
