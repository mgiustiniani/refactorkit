# Acceptance authority: this feature owns managed C LSP command behavior.
# Integrated qualification: docs/arc42/appendix-requirements.adoc#c16-header-field-managed-lsp-2026-11-13
Feature: Managed C refactoring through LSP executeCommand

  @functional-requirement @partial
  @REQ-C16-LSP-MANAGED-001#c-header-field-rename-retains-complete-managed-lsp-authority
  Scenario Outline: C header field rename retains complete managed LSP authority
    Given a managed C header-field LSP workspace captures "<standard>" and "<condition>"
    When the LSP client executes the selected C symbol-rename commands
    Then the managed C symbol-rename LSP outcome is "<outcome>"
    And the managed C symbol-rename LSP inputs and transaction history are preserved

    Examples:
      | standard | condition           | outcome                    |
      | c11      | matching            | applied and rolled back    |
      | c17      | matching            | applied and rolled back    |
      | c17      | target collision    | collision refused          |
      | c17      | database changed    | changed input refused      |
      | c17      | source changed      | changed input refused      |
      | c17      | incomplete consumer | incomplete consumer refused |

  @functional-requirement @partial
  @REQ-C16-LSP-MANAGED-001#c-variable-rename-retains-managed-lsp-authority
  Scenario Outline: C variable rename retains managed LSP authority
    Given a managed C variable-rename LSP workspace captures "<standard>" "<binding>" and "<condition>"
    When the LSP client executes the selected C symbol-rename commands
    Then the managed C symbol-rename LSP outcome is "<outcome>"
    And the managed C symbol-rename LSP inputs and transaction history are preserved

    Examples:
      | standard | binding         | condition        | outcome                 |
      | c11      | local           | matching         | applied and rolled back |
      | c17      | parameter       | matching         | applied and rolled back |
      | c17      | internal object | matching         | applied and rolled back |
      | c17      | local           | database changed | changed input refused   |
      | c17      | parameter       | target collision | collision refused       |
      | c17      | internal object | database changed | changed input refused   |

  @functional-requirement @partial
  @REQ-C16-LSP-MANAGED-001#c-symbol-rename-retains-managed-lsp-authority
  Scenario Outline: C symbol rename retains managed LSP authority
    Given a managed C symbol-rename LSP workspace captures "<standard>" and "<condition>"
    When the LSP client executes the selected C symbol-rename commands
    Then the managed C symbol-rename LSP outcome is "<outcome>"
    And the managed C symbol-rename LSP inputs and transaction history are preserved

    Examples:
      | standard | condition        | outcome                 |
      | c11      | matching         | applied and rolled back |
      | c17      | matching         | applied and rolled back |
      | c17      | target collision | collision refused       |
      | c17      | database changed | changed input refused   |

  @REQ-C16-LSP-MANAGED-001#c-parameter-rename-completes-its-managed-lsp-transaction-or-returns-a-compiler-refusal
  Scenario Outline: C parameter rename completes its managed LSP transaction or returns a compiler refusal
    Given an isolated C LSP workspace with an explicitly captured Clang toolchain
    When the LSP client handles C parameter rename case "<case>"
    Then the LSP returns the C outcome "<outcome>"
    And the original project files and transaction state match "<outcome>"

  Examples:
    | case                      | outcome                      |
    | compiler-bound family     | applied and rolled back      |
    | unknown parameter         | compiler refusal             |

  @functional-requirement @partial @REQ-C16-LSP-MANAGED-001#native-lsp-rename-refuses-c-without-creating-a-managed-transaction
  Scenario: Native LSP rename refuses C without creating a managed transaction
    Given an isolated C LSP workspace with an explicitly captured Clang toolchain
    When the native LSP client requests C rename for the open document
    Then the LSP returns the C outcome "editor-managed refusal"
    And the original project files and transaction state match "editor-managed refusal"

  @REQ-C16-LSP-MANAGED-002#c-scalar-extraction-completes-its-managed-lsp-transaction-or-returns-a-compiler-refusal
  Scenario Outline: C scalar extraction completes its managed LSP transaction or returns a compiler refusal
    Given an isolated C LSP workspace with an explicitly captured Clang toolchain
    When the LSP client handles C scalar extraction case "<case>"
    Then the LSP returns the C outcome "<outcome>"
    And the original project files and transaction state match "<outcome>"

    Examples:
      | case                              | outcome                      |
      | compiler-bound scalar expression | applied and rolled back      |
      | temporary collides with parameter | compiler refusal             |

  @functional-requirement @partial
  @REQ-C16-LSP-INLINE-001#c-function-inline-retains-managed-lsp-authority
  Scenario Outline: C function inline retains managed LSP authority
    Given a managed C function-inline LSP workspace captures "<standard>" and "<condition>"
    When the LSP client executes the managed C function-inline commands
    Then the managed C function-inline LSP outcome is "<outcome>"
    And the managed C function-inline LSP inputs and compiler material are restored

    Examples:
      | standard | condition        | outcome                 |
      | c11      | pure local reads | applied and rolled back |
      | c17      | pure local reads | applied and rolled back |
      | c17      | argument effect  | effects refused         |
      | c17      | database changed | changed input refused   |

  @REQ-C16-CMAKE-INCLUDE-001#include-aware-moves-retain-managed-lsp-lifecycle
  Scenario Outline: Include-aware moves retain managed LSP lifecycle
    Given a managed include-aware CMake LSP workspace captures "<standard>" "<operation>" and "<condition>"
    When the client executes the managed include-aware CMake LSP lifecycle
    Then the managed include-aware CMake LSP outcome is "<outcome>"
    And the managed include-aware CMake LSP project and journal retain their expected images

    Examples:
      | standard | operation | condition        | outcome                 |
      | c11      | component | matching         | applied and rolled back |
      | c17      | component | matching         | applied and rolled back |
      | c17      | source    | matching         | applied and rolled back |
      | c17      | header    | matching         | applied and rolled back |
      | c17      | header    | database changed | changed input refused   |
      | c17      | component | missing flag     | unavailable refused     |


  @functional-requirement @partial
  @REQ-C-COMPILE-UNIT-MODEL-001#lsp-managed-sdk-inputs-retain-preview-apply-and-refusal-authority
  Scenario Outline: LSP managed SDK inputs retain preview Apply and refusal authority
    Given a managed C header-field LSP workspace captures "<standard>" and "<condition>"
    When the LSP client executes the selected C symbol-rename commands
    Then the managed C symbol-rename LSP outcome is "<outcome>"
    And the managed C symbol-rename LSP inputs and transaction history are preserved

    Examples:
      | standard | condition           | outcome                 |
      | c11      | SDK matching        | applied and rolled back |
      | c17      | SDK matching        | applied and rolled back |
      | c11      | SDK header changes  | changed input refused   |
      | c17      | SDK header appears  | changed input refused   |
      | c11      | SDK missing license | invalid SDK refused     |
      | c17      | SDK invalid array   | invalid SDK refused     |


  @functional-requirement @partial
  @REQ-C16-C-SIGNATURE-LSP-001#managed-lsp-applies-c-parameter-insertion-and-reordering-with-rollback
  Scenario Outline: Managed LSP applies C parameter insertion and reordering with rollback
    Given a managed LSP C signature fixture uses "<standard>" and "<operation>"
    When the LSP client issues the C signature lifecycle with "<input>"
    Then the managed C signature response is "<outcome>"
    And the managed C signature workspace has its original content and expected transaction state

    Examples:
      | standard | operation         | input          | outcome                         |
      | c11      | addParameter      | valid          | applied and rolled back         |
      | c17      | addParameter      | valid          | applied and rolled back         |
      | c11      | reorderParameters | valid          | applied and rolled back         |
      | c17      | reorderParameters | valid          | applied and rolled back         |
      | c17      | addParameter      | invalid scalar | c.signatureAdd.invalid_position |
      | c17      | reorderParameters | invalid scalar | c.signatureReorder.invalid_mapping |


  @functional-requirement @partial
  @REQ-C16-FORMAT-LSP-001#managed-c-formatting-through-lsp-preserves-the-requested-range-and-captured-style
  Scenario Outline: Managed C formatting through LSP preserves the requested range and captured style
    Given the LSP C formatting workspace captures "<standard>" and LLVM style
    When the client requests C formatting with "<selection>" and "<style>"
    Then the managed C formatting result is "<outcome>"
    And the C formatting workspace retains its expected source and transaction history

    Examples:
      | standard | selection | style   | outcome                  |
      | c11      | whole     | current | applied and rolled back  |
      | c17      | whole     | current | applied and rolled back  |
      | c11      | range     | current | applied and rolled back  |
      | c17      | range     | current | applied and rolled back  |
      | c17      | invalid   | current | invalid range refused    |
      | c17      | whole     | changed | changed style refused    |

  @REQ-C16-CMAKE-INCLUDE-001#managed-lsp-relocation-retains-explicit-forwarding-headers-and-api-mappings
  Scenario Outline: Managed LSP relocation retains explicit forwarding headers and API mappings
    Given a managed include-aware CMake LSP workspace captures "<standard>" "component" and "<condition>"
    When the client executes the managed include-aware CMake LSP lifecycle
    Then the managed include-aware CMake LSP outcome is "<outcome>"
    And the managed include-aware CMake LSP project and journal retain their expected images

    Examples:
      | standard | condition                 | outcome                 |
      | c11      | forwarding and mapping    | applied and rolled back |
      | c17      | forwarding and mapping    | applied and rolled back |
      | c17      | forwarding database changed | changed input refused |
      | c11      | forwarding header changed | changed input refused   |
      | c17      | forwarding missing header | forwarding refused      |

  @REQ-C-CMAKE-ALL-TU-DIAGNOSTICS-001#managed-lsp-returns-current-and-staged-cmake-diagnostics
  Scenario Outline: Managed LSP returns current and staged CMake diagnostics
    Given a literal CMake diagnostics workspace with "<state>" compiler inputs
    When the LSP client previews the captured CMake source move
    Then managed LSP has "<evidence>" diagnostics for the current and staged files
    And the CMake diagnostics preview preserves saved files and creates no journal

    Examples:
      | state             | evidence           |
      | compiler warnings | complete           |
      | missing TU entry  | unavailable        |
      | many warnings     | bounded projection |
