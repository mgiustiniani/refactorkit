@ci-c-search
Feature: Compiler-backed C declaration search on managed query surfaces
  Search keeps captured compiler context and C lookup spaces. Unavailable
  analysis is an error, never a successful empty result.

  @REQ-C04-QUERY-001#compiler-search-admits-strict-iso-diagnostics-in-captured-context
  Scenario Outline: Compiler search admits strict ISO diagnostics in captured context
    Given the "daemon" C search fixture captures "<standard>" and "strict ISO diagnostics"
    When that surface searches the captured C declarations
    Then the C symbol search response is "compiler declarations" with its declared evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | standard |
      | c11      |
      | c17      |

  @REQ-C04-QUERY-001#daemon-and-mcp-search-captured-c-declarations-with-compiler-authority
  Scenario Outline: Daemon and MCP search captured C declarations with compiler authority
    Given the "<surface>" C search fixture captures "<standard>" and "<condition>"
    When that surface searches the captured C declarations
    Then the C symbol search response is "<outcome>" with its declared evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | condition        | outcome              |
      | daemon  | c11      | captured         | compiler declarations|
      | MCP     | c17      | captured         | compiler declarations|
      | MCP     | c17      | database changed | c.search.snapshotChanged |
      | daemon  | c17      | missing TU       | c.search.incompleteTUs |
      | daemon  | c17      | macro declaration| c.search.sourceUnsupported |
      | MCP     | c17      | wrong hash       | c.search.snapshotStale |

  @REQ-C04-QUERY-001#daemon-and-mcp-resolve-captured-c-definitions-and-references
  Scenario Outline: Daemon and MCP resolve captured C definitions and references
    Given the "<surface>" C search fixture captures "<standard>" and "<condition>"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "<outcome>" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | condition        | binding   | outcome                       |
      | daemon  | c11      | captured         | function  | native definition and references |
      | MCP     | c17      | captured         | typedef   | native definition and references |
      | daemon  | c17      | captured         | field     | native definition and references |
      | MCP     | c17      | captured         | parameter | native definition and references |
      | MCP     | c17      | navigation drift | function  | c.navigation.snapshotChanged  |
      | daemon  | c17      | unknown symbol   | function  | c.navigation.symbolNotFound   |

  @REQ-C04-QUERY-001#cli-resolves-captured-c-definitions-and-references
  Scenario Outline: CLI resolves captured C definitions and references
    Given the "CLI" C search fixture captures "<standard>" and "captured"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "native definition and references" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | standard | binding  |
      | c11      | function |
      | c17      | function |
      | c11      | typedef  |
      | c17      | typedef  |
      | c11      | field    |
      | c17      | field    |

  @REQ-C04-QUERY-001#cli-search-preserves-native-evidence-and-unavailable-analysis
  Scenario Outline: CLI search preserves native evidence and unavailable analysis
    Given the "CLI" C search fixture captures "<standard>" and "<condition>"
    When that surface searches the captured C declarations
    Then the C symbol search response is "<outcome>" with its declared evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | standard | condition         | outcome                    |
      | c11      | captured          | compiler declarations      |
      | c17      | captured          | compiler declarations      |
      | c11      | missing TU        | c.search.incompleteTUs     |
      | c17      | macro declaration | c.search.sourceUnsupported |
      | c17      | wrong hash        | c.search.snapshotStale     |
      | c11      | database changed  | c.search.snapshotStale     |

  @REQ-C04-QUERY-001#source-type-navigation-preserves-translation-unit-identity
  Scenario Outline: Source type navigation preserves translation unit identity
    Given the "<surface>" C search fixture captures "<standard>" and "source type family"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "native definition and references" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | binding |
      | daemon  | c11      | struct  |
      | CLI     | c17      | struct  |
      | MCP     | c11      | union   |
      | daemon  | c17      | enum    |
      | CLI     | c11      | typedef |
      | MCP     | c17      | typedef |

  @REQ-C04-QUERY-001#source-member-navigation-preserves-record-and-translation-unit-identity
  Scenario Outline: Source member navigation preserves record and translation unit identity
    Given the "<surface>" C search fixture captures "<standard>" and "source member family"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "native definition and references" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | binding             |
      | daemon  | c11      | source struct field |
      | CLI     | c17      | source struct field |
      | MCP     | c11      | source union field  |
      | daemon  | c17      | source union field  |
      | CLI     | c11      | peer struct field   |
      | MCP     | c17      | peer union field    |

  @REQ-C04-QUERY-001#source-union-c17-and-enum-c11-navigation-preserves-tu-identity
  Scenario Outline: Source union C17 and enum C11 navigation preserves TU identity
    Given the "<surface>" C search fixture captures "<standard>" and "source type family"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "native definition and references" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | binding |
      | daemon  | c17      | union   |
      | CLI     | c17      | union   |
      | MCP     | c17      | union   |
      | daemon  | c11      | enum    |
      | CLI     | c11      | enum    |
      | MCP     | c11      | enum    |


  @REQ-C04-QUERY-001#forward-record-navigation-connects-declarations-to-complete-definitions
  Scenario Outline: Forward record navigation connects declarations to complete definitions
    Given the "<surface>" C search fixture captures "<standard>" and "<condition>"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "forward record definition and references" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | condition             | binding |
      | daemon  | c11      | source-forward-struct | struct  |
      | CLI     | c17      | source-forward-union  | union   |
      | MCP     | c11      | header-forward-struct | struct  |
      | daemon  | c17      | header-forward-union  | union   |
      | CLI     | c11      | source-forward-union  | union   |
      | MCP     | c17      | header-forward-struct | struct  |


  @REQ-C04-QUERY-001#incomplete-record-queries-retain-references-without-inventing-definitions
  Scenario Outline: Incomplete record queries retain references without inventing definitions
    Given the "<surface>" C search fixture captures "<standard>" and "<condition>"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "incomplete record references without definition" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | condition                | binding |
      | daemon  | c11      | source-incomplete-struct | struct  |
      | CLI     | c17      | source-incomplete-union  | union   |
      | MCP     | c11      | header-incomplete-struct | struct  |
      | daemon  | c17      | header-incomplete-union  | union   |
      | CLI     | c17      | source-incomplete-struct | struct  |
      | MCP     | c17      | header-incomplete-struct | struct  |


  @REQ-C04-QUERY-001#record-query-cross-cells-preserve-native-definition-availability
  Scenario Outline: Record query cross cells preserve native definition availability
    Given the "<surface>" C search fixture captures "<standard>" and "<condition>"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "<outcome>" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | condition               | binding | outcome                                      |
      | CLI     | c17      | source-forward-struct    | struct  | forward record definition and references     |
      | MCP     | c11      | header-forward-union     | union   | forward record definition and references     |
      | daemon  | c11      | source-incomplete-union  | union   | incomplete record references without definition |
      | CLI     | c11      | header-incomplete-union  | union   | incomplete record references without definition |


  @REQ-C04-QUERY-001#scoped-record-public-queries-retain-native-block-identity
  Scenario Outline: Scoped record public queries retain native block identity
    Given the "<surface>" C search fixture captures "<standard>" and "<condition>"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "scoped record definition and references" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | condition | binding |
      | daemon | c11 | scope-block | scoped struct |
      | CLI | c17 | scope-block | scoped union |
      | MCP | c17 | scope-shadow | scoped struct |
      | daemon | c11 | scope-shadow | scoped union |
      | CLI | c11 | scope-forward-block | scoped struct |
      | MCP | c17 | scope-forward-block | scoped union |


  @REQ-C04-QUERY-001#scoped-record-cross-cells-preserve-native-block-identity
  Scenario Outline: Scoped record cross cells preserve native block identity
    Given the "<surface>" C search fixture captures "<standard>" and "<condition>"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "scoped record definition and references" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | condition           | binding       |
      | CLI     | c17      | scope-block         | scoped struct |
      | MCP     | c11      | scope-block         | scoped union  |
      | daemon  | c11      | scope-shadow        | scoped struct |
      | CLI     | c17      | scope-shadow        | scoped union  |
      | MCP     | c17      | scope-forward-block | scoped struct |
      | daemon  | c11      | scope-forward-block | scoped union  |


  @REQ-C04-QUERY-001#local-enum-queries-retain-native-block-and-tu-identity
  Scenario Outline: Local enum queries retain native block and TU identity
    Given the "<surface>" C search fixture captures "<standard>" and "<condition>"
    When that surface navigates the captured C "scoped enum" binding
    Then the C navigation response matches "scoped enum definition and references" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | condition         |
      | daemon  | c11      | scope-enum-block  |
      | CLI     | c17      | scope-enum-block  |
      | MCP     | c17      | scope-enum-shadow |
      | daemon  | c11      | scope-enum-shadow |
      | CLI     | c11      | scope-enum-nested |
      | MCP     | c17      | scope-enum-nested |


  @REQ-C04-QUERY-001#local-typedef-queries-retain-native-block-and-tu-identity
  Scenario Outline: Local typedef queries retain native block and TU identity
    Given the "<surface>" C search fixture captures "<standard>" and "<condition>"
    When that surface navigates the captured C "scoped typedef" binding
    Then the C navigation response matches "scoped typedef definition and references" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | condition            |
      | daemon  | c11      | scope-typedef-block  |
      | CLI     | c17      | scope-typedef-block  |
      | MCP     | c17      | scope-typedef-shadow |
      | daemon  | c11      | scope-typedef-shadow |
      | CLI     | c11      | scope-typedef-nested |
      | MCP     | c17      | scope-typedef-nested |


  @REQ-C04-QUERY-001#pointer-chain-and-repeated-alias-queries-retain-native-families
  Scenario Outline: Pointer chain and repeated alias queries retain native families
    Given the "<surface>" C search fixture captures "<standard>" and "<condition>"
    When that surface navigates the captured C "scoped typedef" binding
    Then the C navigation response matches "scoped typedef definition and references" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | condition                    |
      | daemon  | c11      | scope-typedef-pointer-shadow |
      | CLI     | c17      | scope-typedef-pointer-shadow |
      | MCP     | c11      | scope-typedef-chain-shadow   |
      | daemon  | c17      | scope-typedef-chain-shadow   |
      | CLI     | c11      | scope-typedef-repeated-alias |
      | MCP     | c17      | scope-typedef-repeated-alias |


  @REQ-C04-QUERY-001#native-prototype-tag-queries-retain-scopes-and-definition-availability
  Scenario Outline: Native prototype tag queries retain scopes and definition availability
    Given the "<surface>" C search fixture captures "<standard>" and "prototype tags"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "prototype tag family" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | binding              |
      | CLI     | c11      | prototype first tag  |
      | daemon  | c17      | prototype first tag  |
      | MCP     | c11      | prototype other tag  |
      | CLI     | c17      | prototype other tag  |
      | daemon  | c11      | prototype global tag |
      | MCP     | c17      | prototype global tag |


  @REQ-C04-QUERY-001#native-prototype-query-capacity-preserves-the-limit-and-refuses-excess
  Scenario Outline: Native prototype query capacity preserves the limit and refuses excess
    Given the "daemon" C search fixture captures "<standard>" and "<profile>"
    When that surface searches the captured C declarations
    Then the C symbol search response is "<outcome>" with its declared evidence
    And the C symbol search preserves all project inputs and has no transaction
    Examples:
      | standard | profile                     | outcome                              |
      | c11      | prototype composed at-cap   | compiler composed limit declarations |
      | c17      | prototype composed at-cap   | compiler composed limit declarations |
      | c11      | prototype composed over-cap | c.search.prototypeBindingUnavailable |
      | c17      | prototype composed over-cap | c.search.prototypeBindingUnavailable |


  @REQ-C04-QUERY-001#public-header-prototype-families-retain-original-tu-identity
  Scenario Outline: Public header prototype families retain original TU identity
    Given the "<surface>" C search fixture captures "<standard>" and "header prototype tags"
    When that surface navigates the captured C "prototype first tag" binding
    Then the C navigation response matches "prototype tag family" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard |
      | CLI     | c11      |
      | daemon  | c11      |
      | MCP     | c11      |
      | CLI     | c17      |
      | daemon  | c17      |
      | MCP     | c17      |


  @REQ-C04-QUERY-001#public-function-parameter-tags-retain-complete-body-references
  Scenario Outline: Public function parameter tags retain complete body references
    Given the "<surface>" C search fixture captures "<standard>" and "function parameter tags"
    When that surface navigates the captured C "prototype first tag" binding
    Then the C navigation response matches "prototype tag family" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard |
      | CLI     | c11      |
      | CLI     | c17      |
      | daemon  | c11      |
      | daemon  | c17      |
      | MCP     | c11      |
      | MCP     | c17      |


  @REQ-C04-QUERY-001#ordinary-variable-queries-preserve-scope-linkage-and-header-declarations
  Scenario Outline: Ordinary variable queries preserve scope linkage and header declarations
    Given the "<surface>" C search fixture captures "<standard>" and "ordinary values"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "ordinary value family" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | binding      |
      | CLI     | c11      | local value  |
      | daemon  | c17      | local value  |
      | daemon  | c11      | global value |
      | MCP     | c17      | global value |
      | MCP     | c11      | static value |
      | CLI     | c17      | static value |


  @REQ-C04-QUERY-001#enumerator-and-parameter-queries-preserve-native-ordinary-identity
  Scenario Outline: Enumerator and parameter queries preserve native ordinary identity
    Given the "<surface>" C search fixture captures "<standard>" and "ordinary values"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "ordinary value family" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | binding         |
      | CLI     | c11      | enum value      |
      | daemon  | c17      | enum value      |
      | MCP     | c11      | parameter value |
      | CLI     | c17      | parameter value |


  @REQ-C04-QUERY-001#ordinary-shadow-ids-preserve-alias-and-value-families
  Scenario Outline: Ordinary shadow IDs preserve alias and value families
    Given the "<surface>" C search fixture captures "<standard>" and "scope-typedef-ordinary-shadow"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "ordinary shadow family" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | binding           |
      | CLI     | c11      | shadow alias      |
      | daemon  | c17      | shadow alias      |
      | daemon  | c11      | shadow value      |
      | MCP     | c17      | shadow value      |
      | MCP     | c11      | shadow peer alias |
      | CLI     | c17      | shadow peer alias |


  @REQ-C04-RUNTIME-QUERY-001#packaged-c-query-routes-preserve-native-family-responses
  Scenario Outline: Packaged C query routes preserve native family responses
    Given the "<surface>" C search fixture captures "<standard>" and "captured"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "native definition and references" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface         | standard | binding   |
      | packaged CLI    | c11      | function  |
      | packaged CLI    | c17      | field     |
      | packaged daemon | c11      | typedef   |
      | packaged daemon | c17      | parameter |
      | packaged MCP    | c11      | function  |
      | packaged MCP    | c17      | typedef   |


  @REQ-C04-RUNTIME-QUERY-001#packaged-c-queries-distinguish-unavailable-and-unknown-bindings
  Scenario Outline: Packaged C queries distinguish unavailable and unknown bindings
    Given the "<surface>" C search fixture captures "<standard>" and "<condition>"
    When that surface navigates the captured C "function" binding
    Then the C navigation response matches "<outcome>" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface         | standard | condition        | outcome                      |
      | packaged daemon | c11      | missing TU       | c.search.incompleteTUs        |
      | packaged MCP    | c17      | missing TU       | c.search.incompleteTUs        |
      | packaged daemon | c17      | unknown symbol   | c.navigation.symbolNotFound  |
      | packaged MCP    | c11      | unknown symbol   | c.navigation.symbolNotFound  |
      | packaged daemon | c11      | navigation drift after refresh | c.navigation.snapshotStale   |
      | packaged MCP    | c17      | navigation drift | c.navigation.snapshotChanged |


  @REQ-C04-QUERY-001#sdk-declarations-retain-readonly-locations-and-captured-query-identity
  Scenario Outline: SDK declarations retain readonly locations and captured query identity
    Given the "<surface>" C search fixture captures "<standard>" and "<condition>"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "<outcome>" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | surface | standard | condition         | binding | outcome                         |
      | daemon  | c11      | SDK captured      | typedef | native SDK definition and references |
      | MCP     | c17      | SDK captured      | typedef | native SDK definition and references |
      | CLI     | c17      | SDK captured      | typedef | native SDK definition and references |
      | daemon  | c17      | SDK captured      | field   | native SDK definition and references |
      | daemon  | c17      | SDK stale id      | typedef | c.navigation.symbolNotFound      |
      | MCP     | c11      | SDK missing input | typedef | SDK inputs unavailable           |


  @REQ-C16-MCP-REFUSAL-001#sdk-errors-retain-typed-data-through-mcp-stdio
  Scenario Outline: SDK errors retain typed data through MCP stdio
    Given a C "<standard>" MCP workspace captures malformed SDK input "<input>"
    When the MCP stdio client calls "<tool>" with that SDK input
    Then the MCP tool error retains INVALID_PARAMS and SDK field "<field>"
    And the MCP SDK refusal preserves inputs and leaves a usable cleanly closed server

    Examples:
      | standard | input              | tool                | field                   |
      | c11      | null               | symbol_search       | readOnlySdks            |
      | c17      | object             | symbol_definition   | readOnlySdks            |
      | c11      | nine roots         | symbol_references   | readOnlySdks            |
      | c17      | non-object entry   | preview_refactoring | readOnlySdks[0]         |
      | c11      | missing license    | symbol_search       | readOnlySdks[0]         |
      | c17      | non-string license | preview_refactoring | readOnlySdks[0].license |


  @REQ-C16-MCP-QUERY-001#source-mcp-sdk-queries-retain-native-locations-and-refusals
  Scenario Outline: Source MCP SDK queries retain native locations and refusals
    Given the "source MCP" C search fixture captures "<standard>" and "<condition>"
    When that surface navigates the captured C "<binding>" binding
    Then the C navigation response matches "<outcome>" with native source evidence
    And the C symbol search preserves all project inputs and has no transaction

    Examples:
      | standard | condition         | binding | outcome                              |
      | c11      | SDK captured      | typedef | native SDK definition and references |
      | c17      | SDK captured      | typedef | native SDK definition and references |
      | c11      | SDK captured      | field   | native SDK definition and references |
      | c17      | SDK captured      | field   | native SDK definition and references |
      | c17      | SDK stale id      | typedef | c.navigation.symbolNotFound          |
      | c11      | SDK missing input | typedef | SDK inputs unavailable               |
