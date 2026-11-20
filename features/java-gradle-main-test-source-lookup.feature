@functional-requirement @REQ-JAVA-GRADLE-LOOKUP-001
Feature: Effective Gradle main and test source lookup
  Authority: this Story; architecture: docs/arc42/appendix-requirements.adoc.
  @REQ-JAVA-GRADLE-LOOKUP-001#gradle-test-filenames-do-not-hide-visible-main-declarations
  Scenario Outline: Gradle test filenames do not hide visible main declarations
    Given an effective Gradle SDK "<sdk>" workspace with a test filename matching a main class
    When its test cursor selects the main class for a retained rename
    Then exact Gradle source lookup permits semantic preview apply and protected restoration
    And main and test bytes return to their original versions without changing visibility
    Examples:
      | sdk |
      | 21  |
      | 25  |
