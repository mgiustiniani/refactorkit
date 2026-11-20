# language: en
@functional-requirement @partial
Feature: Managed apply relocates one captured multi-translation-unit C component
  The component's exact source set and one private literal static target move as
  one preview, transaction and rollback. This positive cell has no include roots
  or path-sensitive source inputs; it does not qualify full C15.
  Authority: docs/releases/v0.8.0-plan.md (C15); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-CMAKE-COMPONENT-BINDING-001#relocate-a-complete-multi-tu-component-through-managed-apply-and-exact-rollback
  Scenario: Relocate a complete multi-TU component through managed apply and exact rollback
    Given an isolated multi-TU CMake project with "component-relocation"
    When the captured C component is previewed and approved for managed apply
    Then managed apply is "applied" with correct staged diagnostics
    And applied or refused state is consistent with WAL and rollback

  @REQ-C-CMAKE-COMPONENT-BINDING-001#include-aware-moves-retain-own-ordered-context-and-header-authority
  Scenario Outline: Include-aware moves retain own ordered context and header authority
    Given a captured "<standard>" CMake "<operation>" move has "<visibility>" include roots and "<condition>" inputs
    When the include-aware CMake lifecycle requests preview and approved managed Apply
    Then the include-aware CMake lifecycle reports "<outcome>" with exact source header and target images
    And the include-aware CMake inputs and transaction history retain their expected state

    Examples:
      | standard | operation | visibility | condition        | outcome                 |
      | c11      | component | PRIVATE    | matching         | applied and rolled back |
      | c17      | component | PUBLIC     | matching         | applied and rolled back |
      | c17      | source    | PUBLIC     | matching         | applied and rolled back |
      | c17      | header    | PUBLIC     | matching         | applied and rolled back |
      | c17      | component | PUBLIC     | missing flag     | refused                 |
      | c17      | header    | PUBLIC     | database changed | refused                 |

  @REQ-C-CMAKE-COMPONENT-BINDING-001#include-aware-relocation-retains-context-integrity-under-boundary-inputs
  Scenario Outline: Include-aware relocation retains context integrity under boundary inputs
    Given a captured "<standard>" CMake "<operation>" move has "<visibility>" include roots and "<condition>" inputs
    When the include-aware CMake lifecycle requests preview and approved managed Apply
    Then the include-aware CMake lifecycle reports "<outcome>" with exact source header and target images
    And the include-aware CMake inputs and transaction history retain their expected state

    Examples:
      | standard | operation | visibility | condition        | outcome                 |
      | c17      | header    | PUBLIC     | source changed   | refused                 |
      | c17      | component | PUBLIC     | forged lease     | refused                 |
      | c17      | header    | PUBLIC     | shadow header    | refused                 |
      | c17      | component | PUBLIC     | missing TU       | refused                 |
      | c17      | header    | PUBLIC     | unsafe flag      | refused                 |
      | c17      | header    | PUBLIC     | pretty database  | applied and rolled back |

  @REQ-C16-CMAKE-INCLUDE-001#include-aware-moves-retain-managed-cli-daemon-and-mcp-lifecycle
  Scenario Outline: Include-aware moves retain managed CLI daemon and MCP lifecycle
    Given the include-aware CMake surface "<surface>" captures "<standard>" "<operation>" inputs with "<condition>"
    When that surface requests the include-aware CMake preview apply and rollback lifecycle
    Then the include-aware CMake surface reports "<outcome>"
    And the include-aware CMake surface preserves expected project images and transaction history

    Examples:
      | surface | standard | operation | condition        | outcome                 |
      | CLI     | c17      | component | matching         | applied and rolled back |
      | daemon  | c11      | component | matching         | applied and rolled back |
      | MCP     | c17      | header    | matching         | applied and rolled back |
      | daemon  | c17      | header    | database changed | changed input refused   |
      | MCP     | c17      | component | database changed | changed input refused   |
      | CLI     | c17      | header    | missing flag     | unavailable refused     |


  @REQ-C-CMAKE-COMPONENT-BINDING-001#explicit-component-api-mappings-share-the-owned-relocation-transaction
  Scenario Outline: Explicit component API mappings share the owned relocation transaction
    Given a captured "<standard>" CMake "component" move has "PRIVATE" include roots and "<condition>" inputs
    When the include-aware CMake lifecycle requests preview and approved managed Apply
    Then the include-aware CMake lifecycle reports "<outcome>" with exact source header and target images
    And the include-aware CMake inputs and transaction history retain their expected state

    Examples:
      | standard | condition               | outcome                 |
      | c11      | explicit API mapping    | applied and rolled back |
      | c17      | explicit API mapping    | applied and rolled back |
      | c17      | missing API declaration | refused                 |
      | c17      | cyclic API mapping      | refused                 |
      | c17      | API database changed    | refused                 |

  @REQ-C-CMAKE-COMPONENT-BINDING-001#explicit-forwarding-headers-preserve-the-owned-old-include-path
  Scenario Outline: Explicit forwarding headers preserve the owned old include path
    Given a captured "<standard>" CMake "component" move has "<visibility>" include roots and "<condition>" inputs
    When the include-aware CMake lifecycle requests preview and approved managed Apply
    Then the include-aware CMake lifecycle reports "<outcome>" with exact source header and target images
    And the include-aware CMake inputs and transaction history retain their expected state

    Examples:
      | standard | visibility | condition                      | outcome                 |
      | c11      | PUBLIC     | requested forwarding header    | applied and rolled back |
      | c17      | PRIVATE    | requested forwarding header    | applied and rolled back |
      | c17      | PUBLIC     | forwarding header with mapping | applied and rolled back |
      | c11      | PUBLIC     | unknown forwarding header      | refused                 |
      | c17      | PRIVATE    | source as forwarding header    | refused                 |
      | c17      | PUBLIC     | forwarding database changed    | refused                 |
