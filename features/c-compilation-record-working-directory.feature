# language: en
# Authority: bounded C03 configuration paths; ARC42 owns integrated status.
# Format contract: https://clang.llvm.org/docs/JSONCompilationDatabase.html
@functional-requirement @partial
Feature: Resolve C compilation inputs from each record's working directory
  A compilation record's relative file and compiler paths belong to its own
  directory. Captured model paths and real semantic lookup must agree without
  executing the recorded compiler command or escaping the workspace.

  @REQ-C-COMPILE-DIRECTORY-001 @c-compilation-record-working-directory
  Scenario Outline: A C compilation record retains its directory-relative input identity
    Given a C compilation record uses "<layout>" paths
    When the captured C model drives a real semantic header lookup
    Then translation unit and header paths are "<disposition>" relative to the record directory
    And captured path queries preserve all project bytes and own no running process

    Examples:
      | layout                    | disposition |
      | workspace directory       | admitted    |
      | nested directory          | admitted    |
      | parent-relative inputs    | admitted    |
      | absolute translation unit | admitted    |
      | escaping translation unit | refused     |
      | escaping directory        | refused     |
