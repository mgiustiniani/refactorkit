# Requirement source: docs/releases/v0.8.0-plan.md#C06 semantic symbol rename.
# Architecture authority: docs/arc42/appendix-requirements.adoc#c-semantic-rename-exact-selection
@functional-requirement @partial @ci-c-rename
Feature: Semantic C rename selects an exact declaration in a multifile family
  A textual name that occurs in several workspace files is ambiguous without a
  source location. An exact selected declaration lets clangd bind the family.

  @REQ-C-SEMANTIC-RENAME-001#managed-header-field-rename-retains-cli-daemon-and-mcp-authority
  Scenario Outline: Managed header field rename retains CLI daemon and MCP authority
    Given the managed C header-field surface "<surface>" captures "<standard>" inputs with "<condition>"
    When that surface requests the selected C header field rename lifecycle
    Then the managed C symbol-rename surface reports "<result>"
    And the C symbol-rename surface preserves captured inputs and transaction history

    Examples:
      | surface | standard | condition        | result                  |
      | daemon  | c11      | matching         | applied and rolled back |
      | MCP     | c17      | matching         | applied and rolled back |
      | CLI     | c17      | matching         | applied and rolled back |
      | daemon  | c17      | database changed | changed input refused   |
      | MCP     | c17      | database changed | changed input refused   |
      | CLI     | c17      | target collision | collision refused       |

  @REQ-C-SEMANTIC-RENAME-001#header-field-rename-retains-the-complete-managed-consumer-family
  Scenario Outline: Header field rename retains the complete managed consumer family
    Given a captured "<standard>" header field rename has "<condition>" consumer authority
    When the selected header field rename is previewed and managed Apply is requested
    Then the header field managed lifecycle reports "<outcome>" with exact namespace images
    And the header field inputs and transaction history retain their expected state

    Examples:
      | standard | condition           | outcome                 |
      | c11      | matching            | applied and rolled back |
      | c17      | matching            | applied and rolled back |
      | c17      | incomplete consumer | refused                 |
      | c17      | database changed    | refused                 |
      | c17      | forged lease        | refused                 |
      | c17      | macro declaration   | refused                 |

  @REQ-C-SEMANTIC-RENAME-001#managed-variable-rename-retains-authority-through-cli-daemon-and-mcp
  Scenario Outline: Managed variable rename retains authority through CLI daemon and MCP
    Given the managed C variable-rename surface "<surface>" captures "<standard>" "<binding>" inputs with "<condition>"
    When that surface requests the selected C variable rename lifecycle
    Then the managed C symbol-rename surface reports "<result>"
    And the C symbol-rename surface preserves captured inputs and transaction history

    Examples:
      | surface | standard | binding         | condition        | result                  |
      | daemon  | c11      | local           | matching         | applied and rolled back |
      | MCP     | c17      | parameter       | matching         | applied and rolled back |
      | CLI     | c17      | internal object | matching         | applied and rolled back |
      | daemon  | c17      | local           | database changed | changed input refused   |
      | MCP     | c17      | parameter       | database changed | changed input refused   |
      | CLI     | c17      | internal object | target collision | collision refused       |

  @REQ-C-SEMANTIC-RENAME-001#selected-variable-bindings-retain-managed-rename-authority
  Scenario Outline: Selected variable bindings retain managed rename authority
    Given a captured "<standard>" C "<binding>" variable rename has "<authority>" authority
    When the selected C variable rename is previewed and managed Apply is requested
    Then the managed C variable rename reports "<outcome>" with exact binding images
    And the C variable rename preserves captured inputs and expected transaction state

    Examples:
      | standard | binding         | authority        | outcome                 |
      | c11      | local           | matching         | applied and rolled back |
      | c17      | parameter       | matching         | applied and rolled back |
      | c17      | internal object | matching         | applied and rolled back |
      | c17      | local           | missing approval | refused                 |
      | c17      | parameter       | database changed | refused                 |
      | c17      | internal object | forged lease     | refused                 |

  @REQ-C-SEMANTIC-RENAME-001#single-source-function-rename-retains-managed-authority
  Scenario Outline: Single source function rename retains managed authority
    Given a captured "<standard>" single-source C function rename has "<authority>" authority
    When the selected C function rename is previewed and managed Apply is requested
    Then the managed C function rename reports "<outcome>" with exact binding images
    And the C function rename preserves captured inputs and expected transaction state

    Examples:
      | standard | authority        | outcome                 |
      | c11      | matching         | applied and rolled back |
      | c17      | matching         | applied and rolled back |
      | c17      | missing approval | refused                 |
      | c17      | database changed | refused                 |
      | c17      | forged lease     | refused                 |
      | c17      | source changed   | refused                 |

  @REQ-C-SEMANTIC-RENAME-001#managed-function-rename-retains-authority-through-cli-daemon-and-mcp
  Scenario Outline: Managed function rename retains authority through CLI daemon and MCP
    Given the managed C symbol-rename surface "<surface>" captures "<standard>" inputs with "<condition>"
    When that surface requests the selected C function rename lifecycle
    Then the managed C symbol-rename surface reports "<result>"
    And the C symbol-rename surface preserves captured inputs and transaction history

    Examples:
      | surface | standard | condition        | result                  |
      | daemon  | c11      | matching         | applied and rolled back |
      | MCP     | c17      | matching         | applied and rolled back |
      | CLI     | c17      | matching         | applied and rolled back |
      | daemon  | c17      | database changed | changed input refused   |
      | MCP     | c17      | database changed | changed input refused   |
      | CLI     | c17      | target collision | collision refused       |

  @REQ-C-SEMANTIC-RENAME-001#header-selection-renames-only-the-binding-matched-declaration-family-or-refuses-name-only-selection
  Scenario Outline: Header selection renames only the binding-matched declaration family or refuses name-only selection
    Given a C workspace contains a header declaration, definition, caller and lexical decoys
    When renameSymbol is requested with "<selection>" through library, daemon, and MCP
    Then all surfaces return "<outcome>" with exact binding-matched edits or a typed refusal
    And preview leaves every project byte unchanged with no transaction

    Examples:
      | selection           | outcome |
      | exact-header-token  | preview |
      | name-only           | refused |

  @REQ-C-SEMANTIC-RENAME-001#header-selection-refuses-a-same-translation-unit-target-collision
  Scenario: Header selection refuses a same-translation-unit target collision
    Given a C workspace contains a header declaration, caller, lexical decoys, and a same-translation-unit target function
    When renameSymbol is requested with "exact-header-collision" through library, daemon, and MCP
    Then all surfaces return "refused" with exact binding-matched edits or a typed refusal
    And preview leaves every project byte unchanged with no transaction

  @REQ-C-SEMANTIC-RENAME-001#header-selection-allows-a-target-name-used-only-by-a-c-tag
  Scenario: Header selection allows a target name used only by a C tag
    Given a C workspace contains a header function declaration and a same-spelled struct tag
    When renameSymbol is requested with "exact-header-tag-target" through library, daemon, and MCP
    Then all surfaces return "preview" with exact binding-matched edits or a typed refusal
    And preview leaves every project byte unchanged with no transaction

  @REQ-C-SEMANTIC-RENAME-001#header-selection-renames-a-binding-matched-global-variable-family
  Scenario: Header selection renames a binding-matched global variable family
    Given a C workspace contains a header global variable declaration, definition, uses, and lexical decoys
    When renameSymbol is requested with "exact-global-variable-token" through library, daemon, and MCP
    Then all surfaces return "preview" with exact binding-matched edits or a typed refusal
    And preview leaves every project byte unchanged with no transaction

  @c06-typedef-exact-binding
  @REQ-C-SEMANTIC-RENAME-001#header-selection-renames-a-binding-matched-typedef-family-across-translation-units
  Scenario: Header selection renames a binding-matched typedef family across translation units
    Given a C workspace contains a header typedef declaration, type-signature family, and lexical decoys
    When renameSymbol is requested with "exact-header-typedef-token" through library, daemon, and MCP
    Then all surfaces return "preview" with exact binding-matched edits or a typed refusal
    And preview leaves every project byte unchanged with no transaction

  @c06-enumerator-binding
  @REQ-C-SEMANTIC-RENAME-001#header-selection-renames-a-binding-matched-enumerator-family-across-translation-units
  Scenario: Header selection renames a binding-matched enumerator family across translation units
    Given a C workspace contains a header enum declaration, two translation-unit uses, and lexical decoys
    When renameSymbol is requested with "exact-header-enumerator-token" through library, daemon, and MCP
    Then all surfaces return "preview" with exact binding-matched edits or a typed refusal
    And preview leaves every project byte unchanged with no transaction

  @c06-field-binding
  @REQ-C-SEMANTIC-RENAME-001#header-selection-renames-a-binding-matched-field-family-across-translation-units
  Scenario: Header selection renames a binding-matched field family across translation units
    Given a C workspace contains a header struct-field declaration and member uses in two translation units with lexical decoys
    When renameSymbol is requested with "exact-header-field-token" through library, daemon, and MCP
    Then all surfaces return "preview" with exact binding-matched edits or a typed refusal
    And preview leaves every project byte unchanged with no transaction

  @c06-parameter-binding
  @REQ-C-SEMANTIC-RENAME-001#definition-selection-renames-only-the-binding-matched-c-parameter
  Scenario: Definition selection renames only the binding-matched C parameter
    Given a C source contains a function parameter shadowed by a nested local and lexical decoys
    When renameSymbol is requested with "exact-function-parameter-token" through library, daemon, and MCP
    Then all surfaces return "preview" with exact binding-matched edits or a typed refusal
    And preview leaves every project byte unchanged with no transaction

  @c06-static-object-binding
  @REQ-C-SEMANTIC-RENAME-001#definition-selection-renames-only-the-binding-matched-c-internal-linkage-object
  Scenario: Definition selection renames only the binding-matched C internal-linkage object
    Given a C source contains a static file object shadowed by local and external objects with lexical decoys
    When renameSymbol is requested with "exact-static-file-object-token" through library, daemon, and MCP
    Then all surfaces return "preview" with exact binding-matched edits or a typed refusal
    And preview leaves every project byte unchanged with no transaction

  @REQ-C-SEMANTIC-RENAME-001#local-selection-renames-only-the-binding-matched-shadowed-variable
  Scenario: Local selection renames only the binding-matched shadowed variable
    Given a C source contains a local variable shadowed by another local and a parameter, with lexical decoys
    When renameSymbol is requested with "exact-local-variable-token" through library, daemon, and MCP
    Then all surfaces return "preview" with exact binding-matched edits or a typed refusal
    And preview leaves every project byte unchanged with no transaction

  @c06-macro-origin-refusal
  @REQ-C-SEMANTIC-RENAME-001#selection-of-a-macro-generated-declaration-refuses-before-editing-any-surface
  Scenario: Selection of a macro-generated declaration refuses before editing any surface
    Given a C workspace contains a function declaration generated by a function-like macro and macro-expanded uses
    When renameSymbol is requested with "macro-generated-declaration-token" through library, daemon, and MCP
    Then all surfaces return "refused" with exact binding-matched edits or a typed refusal
    And preview leaves every project byte unchanged with no transaction

  # Authority: docs/arc42/appendix-requirements.adoc#c06-ordinary-header-managed-authority-2026-11-13.
  @REQ-C-SEMANTIC-RENAME-001#ordinary-header-rename-retains-the-complete-managed-declaration-family
  Scenario Outline: Ordinary header rename retains the complete managed declaration family
    Given a captured "<standard>" ordinary header "<family>" rename has "<condition>" authority
    When the ordinary header rename is previewed and managed Apply is requested
    Then the ordinary header lifecycle reports "<outcome>" with exact declaration and shadowing images
    And the ordinary header sources and transaction history retain their expected state

    Examples:
      | standard | family     | condition           | outcome                 |
      | c11      | function   | matching            | applied and rolled back |
      | c17      | global     | matching            | applied and rolled back |
      | c17      | enumerator | matching            | applied and rolled back |
      | c17      | function   | incomplete consumer | refused                 |
      | c17      | global     | database changed    | refused                 |
      | c17      | enumerator | forged lease        | refused                 |

  # Authority: docs/arc42/appendix-requirements.adoc#c06-ordinary-header-lookup-spaces-2026-11-13.
  @REQ-C-SEMANTIC-RENAME-001#ordinary-header-rename-preserves-c-lookup-spaces-during-managed-apply
  Scenario Outline: Ordinary header rename preserves C lookup spaces during managed Apply
    Given a captured "c17" ordinary header "<family>" rename has "<condition>" authority
    When the ordinary header rename is previewed and managed Apply is requested
    Then the ordinary header lifecycle reports "<outcome>" with exact declaration and shadowing images
    And the ordinary header sources and transaction history retain their expected state

    Examples:
      | family     | condition  | outcome                 |
      | function   | collision  | refused                 |
      | global     | collision  | refused                 |
      | enumerator | collision  | refused                 |
      | function   | tag only   | applied and rolled back |

  # Authority: docs/arc42/appendix-requirements.adoc#c06-tag-managed-authority-2026-11-13.
  @REQ-C-SEMANTIC-RENAME-001#shared-header-tag-rename-retains-compiler-type-and-namespace-authority
  Scenario Outline: Shared header tag rename retains compiler type and namespace authority
    Given a captured "<standard>" shared header "<tag>" tag rename has "<condition>" authority
    When the shared header tag rename is previewed and managed Apply is requested
    Then the shared header tag lifecycle reports "<outcome>" with exact type and ordinary namespace images
    And the shared header tag inputs and transaction history retain their expected state

    Examples:
      | standard | tag    | condition          | outcome                 |
      | c11      | struct | matching           | applied and rolled back |
      | c17      | union  | matching           | applied and rolled back |
      | c17      | enum   | matching           | applied and rolled back |
      | c17      | struct | ordinary namespace | applied and rolled back |
      | c17      | enum   | tag collision      | refused                 |
      | c17      | union  | database changed   | refused                 |

  # Authority: docs/arc42/appendix-requirements.adoc#c06-tag-managed-refusals-2026-11-13.
  @REQ-C-SEMANTIC-RENAME-001#shared-header-tag-managed-apply-refuses-unclosed-or-unqualified-authority
  Scenario Outline: Shared header tag managed Apply refuses unclosed or unqualified authority
    Given a captured "c17" shared header "<tag>" tag rename has "<condition>" authority
    When the shared header tag rename is previewed and managed Apply is requested
    Then the shared header tag lifecycle reports "refused" with exact type and ordinary namespace images
    And the shared header tag inputs and transaction history retain their expected state

    Examples:
      | tag    | condition           |
      | struct | incomplete consumer |
      | struct | shadow tag          |
      | struct | macro declaration   |
      | union  | source changed      |
      | enum   | forged lease        |
      | struct | GNU source          |

  # Authority: docs/arc42/appendix-requirements.adoc#c03-tag-hidden-include-refusals-2026-11-13.
  @REQ-C-SEMANTIC-RENAME-001#shared-header-tag-managed-apply-refuses-unacquired-include-spellings
  Scenario Outline: Shared header tag managed Apply refuses unacquired include spellings
    Given a captured "c17" shared header "struct" tag rename has "<condition>" authority
    When the shared header tag rename is previewed and managed Apply is requested
    Then the shared header tag lifecycle reports "refused" with exact type and ordinary namespace images
    And the shared header tag inputs and transaction history retain their expected state

    Examples:
      | condition       |
      | comment include |
      | digraph include |
  @REQ-C-SEMANTIC-RENAME-001#shared-header-typedef-rename-retains-type-and-preserved-namespace-authority
  Scenario Outline: Shared header typedef rename retains type and preserved namespace authority
    Given a captured "<standard>" shared header typedef rename has "<condition>" authority
    When the shared header typedef rename is previewed and managed Apply is requested
    Then the shared header typedef lifecycle reports "<outcome>" with exact type and preserved namespace images
    And the shared header typedef inputs and transaction history retain their expected state

    Examples:
      | standard | condition          | outcome                 |
      | c11      | matching           | applied and rolled back |
      | c17      | matching           | applied and rolled back |
      | c17      | tag destination    | applied and rolled back |
      | c17      | ordinary collision | refused                 |
      | c17      | database changed   | refused                 |
      | c17      | forged lease       | refused                 |

  @REQ-C-SEMANTIC-RENAME-001#shared-header-typedef-managed-apply-refuses-unclosed-or-unqualified-authority
  Scenario Outline: Shared header typedef managed Apply refuses unclosed or unqualified authority
    Given a captured "<standard>" shared header typedef rename has "<condition>" authority
    When the shared header typedef rename is previewed and managed Apply is requested
    Then the shared header typedef lifecycle reports "refused" with exact type and preserved namespace images
    And the shared header typedef inputs and transaction history retain their expected state

    Examples:
      | standard | condition           |
      | c17      | incomplete consumer |
      | c17      | shadow alias        |
      | c17      | macro declaration   |
      | c17      | source changed      |
      | c17      | GNU source          |
      | c17      | opaque designator   |

  # Authority: docs/arc42/appendix-requirements.adoc#c06-source-tag-managed-authority-2026-11-14.
  @REQ-C-SEMANTIC-RENAME-001#source-tag-rename-retains-native-tu-identity-through-managed-apply
  Scenario Outline: Source tag rename retains native TU identity through managed Apply
    Given a captured "<standard>" source "<tag>" tag rename has "matching" authority
    When the source tag rename is previewed and managed Apply is requested
    Then the source tag lifecycle reports "applied and rolled back" with exact type and ordinary namespace images
    And the source tag inputs and transaction history retain their expected state

    Examples:
      | standard | tag    |
      | c11      | struct |
      | c17      | struct |
      | c11      | union  |
      | c17      | union  |
      | c11      | enum   |
      | c17      | enum   |

  # Authority: docs/arc42/appendix-requirements.adoc#c06-source-tag-exposure-managed-2026-11-14.
  @REQ-C-SEMANTIC-RENAME-001#source-tag-mutation-refuses-unqualified-external-type-exposure
  Scenario Outline: Source tag mutation refuses unqualified external type exposure
    Given a captured "<standard>" source "<tag>" tag rename has "<condition>" authority
    When the source tag rename is previewed and managed Apply is requested
    Then the source tag lifecycle reports "exposure refused" with exact type and ordinary namespace images
    And the source tag inputs and transaction history retain their expected state

    Examples:
      | standard | tag    | condition           |
      | c11      | struct | external return     |
      | c17      | union  | external parameter  |
      | c11      | enum   | external object     |
      | c17      | struct | source alias        |
      | c11      | union  | containing record   |
      | c17      | struct | pointer erasure     |


  @REQ-C-SEMANTIC-RENAME-001#source-typedef-rename-retains-native-type-and-tu-authority
  Scenario Outline: Source typedef rename retains native type and TU authority
    Given a captured "<standard>" source typedef rename has "<shape>" shape and "matching" authority
    When the source typedef rename is previewed and managed Apply is requested
    Then the source typedef lifecycle reports "applied and rolled back" with exact type and preserved namespace images
    And the source typedef inputs and transaction history retain their expected state

    Examples:
      | standard | shape   |
      | c11      | scalar  |
      | c17      | scalar  |
      | c11      | pointer |
      | c17      | pointer |
      | c11      | chain   |
      | c17      | chain   |


  @REQ-C-SEMANTIC-RENAME-001#source-typedef-managed-apply-refuses-unqualified-or-stale-authority
  Scenario Outline: Source typedef managed Apply refuses unqualified or stale authority
    Given a captured "<standard>" source typedef rename has "scalar" shape and "<condition>" authority
    When the source typedef rename is previewed and managed Apply is requested
    Then the source typedef lifecycle reports "<outcome>" with exact type and preserved namespace images
    And the source typedef inputs and transaction history retain their expected state

    Examples:
      | standard | condition          | outcome               |
      | c11      | ordinary collision | refused               |
      | c17      | missing TU         | refused               |
      | c11      | shadow alias       | refused               |
      | c17      | macro declaration  | refused               |
      | c11      | database changed   | changed input refused |
      | c17      | forged lease       | forged lease refused  |


  @REQ-C-SEMANTIC-RENAME-001#source-typedef-apply-preserves-a-changed-unrelated-translation-unit
  Scenario: Source typedef Apply preserves a changed unrelated translation unit
    Given a captured "c17" source typedef rename has "scalar" shape and "source changed" authority
    When the source typedef rename is previewed and managed Apply is requested
    Then the source typedef lifecycle reports "changed source refused" with exact type and preserved namespace images
    And the source typedef inputs and transaction history retain their expected state


  @REQ-C-COMPILE-UNIT-MODEL-001#owned-sysroot-retains-managed-header-field-authority
  Scenario Outline: Owned sysroot retains managed header field authority
    Given the managed C header-field surface "<surface>" captures "<standard>" inputs with "<condition>"
    When that surface requests the selected C header field rename lifecycle
    Then the managed C symbol-rename surface reports "<result>"
    And the C symbol-rename surface preserves captured inputs and transaction history

    Examples:
      | surface | standard | condition                | result                  |
      | CLI     | c11      | sysroot matching         | applied and rolled back |
      | CLI     | c17      | sysroot matching         | applied and rolled back |
      | daemon  | c11      | sysroot matching         | applied and rolled back |
      | MCP     | c17      | sysroot matching         | applied and rolled back |
      | daemon  | c17      | sysroot database changed | changed input refused   |
      | MCP     | c11      | sysroot database changed | changed input refused   |


  @REQ-C-COMPILE-UNIT-MODEL-001#managed-workspace-field-rename-retains-read-only-sdk-authority
  Scenario Outline: Managed workspace field rename retains read only SDK authority
    Given a captured "<standard>" header field rename has "<condition>" consumer authority
    When the selected header field rename is previewed and managed Apply is requested
    Then the header field managed lifecycle reports "<outcome>" with exact namespace images
    And the header field inputs and transaction history retain their expected state

    Examples:
      | standard | condition          | outcome                 |
      | c11      | SDK matching       | applied and rolled back |
      | c17      | SDK matching       | applied and rolled back |
      | c11      | SDK header changes | refused                 |
      | c17      | SDK header appears | refused                 |
      | c11      | SDK forged lease   | refused                 |
      | c17      | SDK header removed | refused                 |


  @REQ-C-COMPILE-UNIT-MODEL-001#daemon-and-mcp-retain-explicit-sdk-authority-through-managed-apply
  Scenario Outline: Daemon and MCP retain explicit SDK authority through managed Apply
    Given the managed C header-field surface "<surface>" captures "<standard>" inputs with "<condition>"
    When that surface requests the selected C header field rename lifecycle
    Then the managed C symbol-rename surface reports "<result>"
    And the C symbol-rename surface preserves captured inputs and transaction history

    Examples:
      | surface | standard | condition           | result                  |
      | daemon  | c11      | SDK matching        | applied and rolled back |
      | MCP     | c17      | SDK matching        | applied and rolled back |
      | daemon  | c17      | SDK changed         | changed input refused   |
      | MCP     | c11      | SDK changed         | changed input refused   |
      | daemon  | c11      | SDK missing license | invalid SDK refused     |
      | MCP     | c17      | SDK invalid array   | invalid SDK refused     |


  @REQ-C-COMPILE-UNIT-MODEL-001#cli-sdk-json-preserves-managed-authority-and-bounded-root-lists
  Scenario Outline: CLI SDK JSON preserves managed authority and bounded root lists
    Given the managed C header-field surface "CLI" captures "<standard>" inputs with "<condition>"
    When that surface requests the selected C header field rename lifecycle
    Then the managed C symbol-rename surface reports "<result>"
    And the C symbol-rename surface preserves captured inputs and transaction history

    Examples:
      | standard | condition         | result                  |
      | c11      | SDK matching      | applied and rolled back |
      | c17      | SDK matching      | applied and rolled back |
      | c11      | SDK two roots     | applied and rolled back |
      | c17      | SDK eight roots   | applied and rolled back |
      | c11      | SDK invalid JSON  | invalid SDK refused     |
      | c17      | SDK nine roots    | invalid SDK refused     |
