# language: en
# Acceptance: standard C LSP reads; ARC42 owns integrated architecture and status.
@functional-requirement @partial
Feature: Standard LSP C queries use native compiler binding authority
  Read-only editor requests use an explicitly configured toolchain and saved
  captured C inputs. Unavailable native analysis is never a Java fallback or an
  empty successful result. Mutations retain their separate managed lifecycle.

  @REQ-C04-LSP-QUERY-001#standard-lsp-c-queries-retain-native-binding-authority
  Scenario Outline: Standard LSP C queries retain native binding authority
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding  |
      | c11      | function |
      | c17      | function |
      | c11      | typedef  |
      | c17      | typedef  |
      | c11      | field    |
      | c17      | field    |

  @REQ-C04-LSP-QUERY-001#standard-c-lsp-queries-distinguish-absent-symbols-from-unavailable-analysis
  Scenario Outline: Standard C LSP queries distinguish absent symbols from unavailable analysis
    Given a standard C LSP boundary fixture captures "<standard>" and "<condition>"
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP query disposition is "<outcome>"
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | condition         | outcome                      |
      | c11      | missing toolchain | c.editor.toolchainRequired   |
      | c17      | dirty document    | c.editor.unsavedUnsupported  |
      | c17      | missing TU        | c.search.incompleteTUs       |
      | c11      | macro declaration | c.search.sourceUnsupported   |
      | c17      | invalid position  | invalid params               |
      | c11      | number cursor     | native symbol absent         |

  @REQ-C04-LSP-QUERY-001#source-type-cursors-retain-native-translation-unit-identity
  Scenario Outline: Source type cursors retain native translation unit identity
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding        |
      | c11      | source struct  |
      | c17      | source struct  |
      | c11      | source union   |
      | c17      | source enum    |
      | c11      | source typedef |
      | c17      | source typedef |

  @REQ-C04-LSP-QUERY-001#source-member-cursors-retain-record-and-translation-unit-identity
  Scenario Outline: Source member cursors retain record and translation unit identity
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding             |
      | c11      | source struct field |
      | c17      | source struct field |
      | c11      | source union field  |
      | c17      | source union field  |
      | c11      | peer struct field   |
      | c17      | peer union field    |


  @REQ-C04-LSP-QUERY-001#source-union-c17-and-enum-c11-cursors-retain-exact-tu-locations
  Scenario Outline: Source union C17 and enum C11 cursors retain exact TU locations
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding      |
      | c17      | source union |
      | c11      | source enum  |


  @REQ-C04-LSP-QUERY-001#forward-and-incomplete-record-cursors-preserve-native-definition-availability
  Scenario Outline: Forward and incomplete record cursors preserve native definition availability
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding                  |
      | c11      | source-forward-struct    |
      | c17      | source-forward-union     |
      | c11      | header-forward-struct    |
      | c17      | source-incomplete-struct |
      | c11      | header-incomplete-union  |
      | c17      | header-incomplete-struct |


  @REQ-C04-LSP-QUERY-001#remaining-record-cursors-share-native-family-authority
  Scenario Outline: Remaining record cursors share native family authority
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding |
      | c17 | header-forward-struct |
      | c11 | header-forward-union |
      | c17 | header-forward-union |
      | c17 | source-forward-struct |
      | c11 | source-forward-union |
      | c11 | header-incomplete-struct |


  @REQ-C04-LSP-QUERY-001#remaining-incomplete-record-cursors-keep-known-references
  Scenario Outline: Remaining incomplete record cursors keep known references
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding |
      | c17 | header-incomplete-union |
      | c11 | source-incomplete-struct |
      | c11 | source-incomplete-union |
      | c17 | source-incomplete-union |


  @REQ-C04-LSP-QUERY-001#direct-header-record-cursors-retain-all-captured-native-contexts
  Scenario Outline: Direct header record cursors retain all captured native contexts
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding |
      | c11 | cursor-header-forward-struct |
      | c17 | cursor-header-forward-union |
      | c11 | cursor-header-incomplete-struct |
      | c17 | cursor-header-incomplete-union |
      | c17 | cursor-header-forward-struct |
      | c11 | cursor-header-forward-union |


  @REQ-C04-LSP-QUERY-001#direct-header-incomplete-record-cross-cells-retain-known-references
  Scenario Outline: Direct header incomplete record cross cells retain known references
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding |
      | c17 | cursor-header-incomplete-struct |
      | c11 | cursor-header-incomplete-union |


  @REQ-C04-LSP-QUERY-001#block-scoped-record-cursors-retain-native-local-family
  Scenario Outline: Block scoped record cursors retain native local family
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding                    |
      | c11      | scope-block-struct         |
      | c17      | scope-block-union          |
      | c17      | scope-shadow-struct        |
      | c11      | scope-shadow-union         |
      | c11      | scope-forward-block-struct |
      | c17      | scope-forward-block-union  |


  @REQ-C04-LSP-QUERY-001#remaining-block-scoped-record-cursors-retain-native-local-family
  Scenario Outline: Remaining block scoped record cursors retain native local family
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding                    |
      | c17      | scope-block-struct         |
      | c11      | scope-block-union          |
      | c11      | scope-shadow-struct        |
      | c17      | scope-shadow-union         |
      | c17      | scope-forward-block-struct |
      | c11      | scope-forward-block-union  |


  @REQ-C04-LSP-QUERY-001#local-enum-use-cursors-retain-native-scope-identity
  Scenario Outline: Local enum use cursors retain native scope identity
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding           |
      | c11      | scope-enum-block  |
      | c17      | scope-enum-block  |
      | c11      | scope-enum-shadow |
      | c17      | scope-enum-shadow |
      | c11      | scope-enum-nested |
      | c17      | scope-enum-nested |


  @REQ-C04-LSP-QUERY-001#local-scalar-typedef-use-cursors-retain-native-scope-identity
  Scenario Outline: Local scalar typedef use cursors retain native scope identity
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding              |
      | c11      | scope-typedef-block  |
      | c17      | scope-typedef-block  |
      | c11      | scope-typedef-shadow |
      | c17      | scope-typedef-shadow |
      | c11      | scope-typedef-nested |
      | c17      | scope-typedef-nested |


  @REQ-C04-LSP-QUERY-001#pointer-chain-and-repeated-alias-cursors-retain-native-families
  Scenario Outline: Pointer chain and repeated alias cursors retain native families
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding                      |
      | c11      | scope-typedef-pointer-shadow |
      | c17      | scope-typedef-pointer-shadow |
      | c11      | scope-typedef-chain-shadow   |
      | c17      | scope-typedef-chain-shadow   |
      | c11      | scope-typedef-repeated-alias |
      | c17      | scope-typedef-repeated-alias |


  @REQ-C04-LSP-QUERY-001#native-prototype-cursors-retain-scope-and-definition-availability
  Scenario Outline: Native prototype cursors retain scope and definition availability
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding          |
      | c11      | prototype-first  |
      | c17      | prototype-first  |
      | c11      | prototype-second |
      | c17      | prototype-second |
      | c11      | prototype-other  |
      | c17      | prototype-other  |


  @REQ-C04-LSP-QUERY-001#prototype-homonyms-preserve-source-and-peer-global-cursors
  Scenario Outline: Prototype homonyms preserve source and peer global cursors
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding              |
      | c11      | prototype-global     |
      | c17      | prototype-global     |
      | c11      | prototype-global-use |
      | c17      | prototype-global-use |
      | c11      | prototype-peer       |
      | c17      | prototype-peer       |


  @REQ-C04-LSP-QUERY-001#header-prototype-cursors-retain-original-translation-unit-proof
  Scenario Outline: Header prototype cursors retain original translation unit proof
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding                 |
      | c11      | header-prototype-first  |
      | c17      | header-prototype-first  |
      | c11      | header-prototype-second |
      | c17      | header-prototype-second |
      | c11      | header-prototype-other  |
      | c17      | header-prototype-other  |


  @REQ-C04-LSP-QUERY-001#header-prototype-context-disagreement-refuses-public-queries
  Scenario Outline: Header prototype context disagreement refuses public queries
    Given a standard C LSP boundary fixture captures "<standard>" and "header prototype context disagreement"
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP query disposition is "c.search.prototypeBindingUnavailable"
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard |
      | c11      |
      | c17      |


  @REQ-C04-LSP-QUERY-001#function-parameter-body-cursors-preserve-native-tag-family
  Scenario Outline: Function parameter body cursors preserve native tag family
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding                  |
      | c11      | function-parameter-first |
      | c17      | function-parameter-first |
      | c11      | function-parameter-local |
      | c17      | function-parameter-local |
      | c11      | function-parameter-cast  |
      | c17      | function-parameter-cast  |


  @REQ-C04-LSP-QUERY-001#ordinary-variable-cursors-preserve-native-scope-and-linkage
  Scenario Outline: Ordinary variable cursors preserve native scope and linkage
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding         |
      | c11      | ordinary-local  |
      | c17      | ordinary-local  |
      | c11      | ordinary-global |
      | c17      | ordinary-global |
      | c11      | ordinary-static |
      | c17      | ordinary-static |


  @REQ-C04-LSP-QUERY-001#enumerator-and-parameter-cursors-retain-native-identity-and-protocol-kinds
  Scenario Outline: Enumerator and parameter cursors retain native identity and protocol kinds
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding             |
      | c11      | ordinary-enumerator |
      | c17      | ordinary-enumerator |
      | c11      | ordinary-parameter  |
      | c17      | ordinary-parameter  |


  @REQ-C04-LSP-QUERY-001#ordinary-value-shadow-cursors-preserve-typedef-and-variable-identity
  Scenario Outline: Ordinary value shadow cursors preserve typedef and variable identity
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding                                         |
      | c11      | scope-typedef-ordinary-shadow-alias-before        |
      | c17      | scope-typedef-ordinary-shadow-alias-before        |
      | c11      | scope-typedef-ordinary-shadow-value-declaration   |
      | c17      | scope-typedef-ordinary-shadow-value-declaration   |
      | c11      | scope-typedef-ordinary-shadow-value-use           |
      | c17      | scope-typedef-ordinary-shadow-value-use           |


  @REQ-C16-LSP-RUNTIME-001#packaged-lsp-launcher-serves-native-c-queries
  Scenario Outline: Packaged LSP launcher serves native C queries
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding           |
      | c11      | packaged:function |
      | c17      | packaged:function |
      | c11      | packaged:typedef  |
      | c17      | packaged:typedef  |
      | c11      | packaged:field    |
      | c17      | packaged:field    |


  @REQ-C16-LSP-RUNTIME-001#packaged-lsp-distinguishes-absent-symbols-and-unavailable-c-analysis
  Scenario Outline: Packaged LSP distinguishes absent symbols and unavailable C analysis
    Given a standard C LSP boundary fixture captures "<standard>" and "<condition>"
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP query disposition is "<outcome>"
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | condition                  | outcome                     |
      | c11      | packaged:missing toolchain | c.editor.toolchainRequired   |
      | c17      | packaged:dirty document    | c.editor.unsavedUnsupported  |
      | c17      | packaged:missing TU        | c.search.incompleteTUs       |
      | c11      | packaged:macro declaration | c.search.sourceUnsupported   |
      | c17      | packaged:invalid position  | invalid params              |
      | c11      | packaged:number cursor     | native symbol absent        |


  @REQ-C04-LSP-QUERY-001#workspace-cursors-resolve-readonly-sdk-locations-and-references
  Scenario Outline: Workspace cursors resolve readonly SDK locations and references
    Given a standard C LSP query fixture captures "<standard>" with a "<binding>" selection
    When the editor requests C workspace symbols document symbols definition and references
    Then the standard C LSP locations match the captured native binding family
    And C LSP queries preserve all saved inputs and leave no transaction or compiler process

    Examples:
      | standard | binding      |
      | c11      | sdk:typedef  |
      | c17      | sdk:typedef  |
      | c11      | sdk:field    |
      | c17      | sdk:field    |
      | c11      | sdk:function |
      | c17      | sdk:function |
