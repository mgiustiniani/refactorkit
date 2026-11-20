@functional-requirement @REQ-JAVA-CURSOR-SELECTION-001
Feature: Exact SDK Java cursor mutation selector
  Authority: this Story; architecture: docs/arc42/appendix-requirements.adoc.
  @REQ-JAVA-CURSOR-SELECTION-001#saved-java-cursors-select-exact-jdt-mutation-targets-or-refuse-replayed-authority
  Scenario Outline: Saved Java cursors select exact JDT mutation targets or refuse replayed authority
    Given saved Java cursor fixtures with an explicit SDK and overloaded method calls
    When the "<target>" cursor requests a mutation selector before "<event>"
    Then the cursor mutation reports "<outcome>" using exact SDK binding identity
    And selector refusal or retained apply preserves the expected source authority
    Examples:
      | target | event           | outcome                    |
      | class  | exact preview   | applied                    |
      | method | exact preview   | applied                    |
      | field  | exact preview   | applied                    |
      | space  | exact preview   | java.selection.notBound    |
      | method | tampered selector | java.selection.selectorStale |
      | class  | source changed  | java.context.snapshotStale |


  @REQ-JAVA-CURSOR-SELECTION-001#constructor-declaration-cursors-authorize-qualified-signature-mutations
  Scenario Outline: Constructor declaration cursors authorize qualified signature mutations
    Given saved Java constructor cursor fixtures with an explicit SDK and overloaded calls
    When the "constructor" cursor requests a mutation selector before "<event>"
    Then the cursor mutation reports "<outcome>" using exact SDK binding identity
    And selector refusal or retained apply preserves the expected source authority
    Examples:
      | event              | outcome                              |
      | rename parameter   | applied                              |
      | add parameter      | applied                              |
      | reorder parameters | applied                              |
      | remove parameter   | applied                              |
      | change type        | applied                              |
      | incompatible rename | java.selection.operationIncompatible |
