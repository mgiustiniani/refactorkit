Feature: Owned and bounded C source capture
  The structural scanner must return a complete owned source inventory or a
  typed unavailable result. A path spelling alone grants no read ownership.

  @REQ-C-SOURCE-CAPTURE-001#structural-capture-refuses-unowned-or-over-budget-source-inputs
  Scenario Outline: Structural capture refuses unowned or over-budget source inputs
    Given the C structural capture fixture has "<condition>" inputs
    When the structural C scanner captures that workspace with its declared limits
    Then structural C capture reports "<outcome>" without a partial snapshot
    And structural C capture preserves the workspace and external canary bytes

    Examples:
      | condition          | outcome                  |
      | regular sources    | captured                 |
      | external file link | SYMBOLIC_LINK            |
      | linked root        | SYMBOLIC_LINK            |
      | oversized source   | FILE_BYTES_EXCEEDED      |
      | total source bytes | TOTAL_BYTES_EXCEEDED     |
      | too many entries   | ENTRIES_EXCEEDED         |

  @REQ-C-SOURCE-CAPTURE-001#structural-capture-retains-explicit-input-and-traversal-boundaries
  Scenario Outline: Structural capture retains explicit input and traversal boundaries
    Given the C structural capture fixture has "<condition>" inputs
    When the structural C scanner captures that workspace with its declared limits
    Then structural C capture reports "<outcome>" without a partial snapshot
    And structural C capture preserves the workspace and external canary bytes

    Examples:
      | condition          | outcome              |
      | directory link     | SYMBOLIC_LINK        |
      | invalid UTF8       | INVALID_UTF8         |

      | too many sources   | SOURCES_EXCEEDED     |
      | deep source tree   | DEPTH_EXCEEDED       |
      | ignored source tree| captured             |
      | exact budgets      | captured             |

  @REQ-C-SOURCE-CAPTURE-001#generic-inventory-uses-owned-and-bounded-c-source-capture
  Scenario Outline: Generic inventory uses owned and bounded C source capture
    Given the C structural capture fixture has "<condition>" inputs
    When the generic inventory captures that C workspace with its declared limits
    Then structural C capture reports "<outcome>" without a partial snapshot
    And structural C capture preserves the workspace and external canary bytes

    Examples:
      | condition          | outcome              |
      | regular sources    | captured             |
      | external file link | SYMBOLIC_LINK        |
      | linked root        | SYMBOLIC_LINK        |
      | oversized source   | FILE_BYTES_EXCEEDED  |
      | total source bytes | TOTAL_BYTES_EXCEEDED |
      | invalid UTF8       | INVALID_UTF8         |

  @REQ-C-SOURCE-CAPTURE-001#managed-project-capture-preserves-source-refusal-authority
  Scenario Outline: Managed project capture preserves source refusal authority
    Given the C structural capture fixture has "<condition>" inputs
    When the "<surface>" surface opens that C workspace through its generic inventory
    Then structural C capture reports "<outcome>" without a partial snapshot
    And structural C capture preserves the workspace and external canary bytes

    Examples:
      | surface | condition                | outcome             |
      | daemon  | managed regular sources  | captured            |
      | MCP     | managed regular sources  | captured            |
      | daemon  | external file link       | SYMBOLIC_LINK       |
      | MCP     | linked root              | SYMBOLIC_LINK       |
      | daemon  | invalid UTF8             | INVALID_UTF8        |
      | MCP     | managed oversized source | FILE_BYTES_EXCEEDED |


  @REQ-C-SOURCE-CAPTURE-001#owned-inventory-precedes-jvm-source-access-on-managed-project-open
  Scenario Outline: Owned inventory precedes JVM source access on managed project open
    Given the C structural capture fixture has "<condition>" inputs
    When the "<surface>" surface opens that C workspace through its generic inventory
    Then structural C capture reports "<outcome>" without a partial snapshot
    And structural C capture preserves the workspace and external canary bytes

    Examples:
      | surface | condition                     | outcome       |
      | daemon  | managed regular sources       | captured      |
      | MCP     | managed regular sources       | captured      |
      | daemon  | unreadable Java source link   | SYMBOLIC_LINK |
      | MCP     | unreadable Java source link   | SYMBOLIC_LINK |
      | daemon  | unreadable Kotlin source link | SYMBOLIC_LINK |
      | MCP     | unreadable Kotlin source link | SYMBOLIC_LINK |


  @REQ-C-SOURCE-CAPTURE-001#generic-capture-bounds-root-ancestry-and-traversal-resources
  Scenario Outline: Generic capture bounds root ancestry and traversal resources
    Given the C structural capture fixture has "<condition>" inputs
    When the generic inventory captures that C workspace with its declared limits
    Then structural C capture reports "<outcome>" without a partial snapshot
    And structural C capture preserves the workspace and external canary bytes

    Examples:
      | condition               | outcome          |
      | excessive root ancestry | DEPTH_EXCEEDED   |
      | deep source tree        | DEPTH_EXCEEDED   |
      | too many sources        | SOURCES_EXCEEDED |
      | too many entries        | ENTRIES_EXCEEDED |
      | ignored source tree     | captured         |
      | exact budgets           | captured         |


  @REQ-C-SOURCE-CAPTURE-001#jvm-source-acquisition-refuses-post-inventory-path-substitution
  Scenario Outline: JVM source acquisition refuses post-inventory path substitution
    Given the JVM source acquisition fixture prepares "<condition>"
    When owned inventory completes and JVM source state changes before base scanning
    Then JVM source acquisition reports "<outcome>" without a partial snapshot
    And JVM source acquisition preserves declared final files and its external canary

    Examples:
      | condition                  | outcome             |
      | stable Java sources        | captured            |
      | modeled generated sources  | captured            |
      | replaced Java file         | SYMBOLIC_LINK       |
      | replaced Kotlin file       | SYMBOLIC_LINK       |
      | replaced source directory  | SYMBOLIC_LINK       |
      | grown Java source          | FILE_BYTES_EXCEEDED |


  @REQ-C-SOURCE-CAPTURE-001#selected-source-roots-retain-confinement-and-shared-budgets
  Scenario Outline: Selected source roots retain confinement and shared budgets
    Given the selected source-root fixture prepares "<condition>"
    When the core reader captures the explicitly selected source roots
    Then selected source-root capture reports "<outcome>" without partial sources
    And selected source-root capture preserves its declared inputs

    Examples:
      | condition               | outcome               |
      | overlapping roots       | captured              |
      | outside workspace       | OWNERSHIP_UNAVAILABLE |
      | symbolic root ancestor  | SYMBOLIC_LINK         |
      | missing modeled root    | captured              |
      | aggregate source bytes  | TOTAL_BYTES_EXCEEDED  |
      | invalid source UTF8     | INVALID_UTF8          |


  @REQ-C-SOURCE-CAPTURE-001#compilation-inputs-use-owned-bounded-byte-exact-capture
  Scenario Outline: Compilation inputs use owned bounded byte-exact capture
    Given the C compilation-input fixture prepares "<condition>"
    When the C database provider and snapshot capture read their inputs
    Then C compilation input reports "<status>" with "<authority>" snapshot authority
    And C compilation input discovery preserves bytes and creates no outputs

    Examples:
      | condition              | status      | authority   |
      | exact eight MiB        | AVAILABLE   | captured    |
      | build database         | AVAILABLE   | captured    |
      | database file link     | UNAVAILABLE | unavailable |
      | database parent link   | UNAVAILABLE | unavailable |
      | invalid UTF8 note      | UNAVAILABLE | unavailable |
      | over eight MiB         | UNAVAILABLE | unavailable |


  @REQ-C-SOURCE-CAPTURE-001#cmake-manifest-retains-owned-input-and-byte-bound-authority
  Scenario Outline: CMake manifest retains owned input and byte-bound authority
    Given the CMake input fixture prepares "<condition>"
    When CMake snapshot capture acquires the root manifest
    Then CMake input retains "<authority>" authority without changing sources
    And CMake input capture preserves its declared files and creates no outputs

    Examples:
      | condition            | authority   |
      | exact 512 KiB        | captured    |
      | ancestor root link   | unavailable |
      | manifest file link   | unavailable |
      | over 512 KiB         | unavailable |
      | invalid UTF8         | unavailable |
      | absent manifest      | unavailable |
