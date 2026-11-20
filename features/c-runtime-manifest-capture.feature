@REQ-C-RUNTIME-CAPTURE-001
Feature: Captured C runtime graph inputs
  Configured native tools require complete selected object inventories.
  A captured graph retains object bytes and aliases and can lose currency.
  Capturing an inventory neither selects loader resolution nor admits execution.

  @REQ-C-RUNTIME-CAPTURE-001#configured-c-runtime-manifests-retain-all-selected-objects-or-refuse-stale-and-incomplete-graphs
  Scenario Outline: Configured C runtime manifests retain all selected objects or refuse stale and incomplete graphs
    Given a configured C runtime graph with "<condition>"
    When the complete selected C runtime is captured and checked
    Then the C runtime manifest outcome is "<outcome>"
    And runtime manifest capture preserves source inputs and executes no configured artifact

    Examples:
      | condition                  | outcome            |
      | compiler                   | captured           |
      | server                     | captured           |
      | formatter                  | captured           |
      | changed-library            | not-current        |
      | retargeted-library         | not-current        |
      | missing-transitive-library | incomplete-refusal |
