# language: en
# Authority: bounded C03 Build Model SPI projection; ARC42 owns integrated status.
@functional-requirement @partial
Feature: The C Build Model preserves per-translation-unit compiler context
  A captured compile database is data, never permission to execute its compiler
  commands. Each translation unit retains its own file, working directory,
  compiler arguments and declared language configuration. Conflicting aggregate
  configuration remains partial without collapsing the distinct unit facts.

  @REQ-C-COMPILE-UNIT-MODEL-001#build-model-retains-distinct-per-translation-unit-configuration
  Scenario: Build Model retains distinct per-translation-unit configuration
    Given a captured C compilation database has two differently configured translation units
    When the C Build Model provider projects the compilation database
    Then each Build Model compilation unit retains its exact file directory arguments and language configuration
    And the partial aggregate reports the conflict without executing commands or changing project inputs

  @REQ-C-COMPILE-UNIT-MODEL-001#raw-compilation-records-preserve-encoding-and-argument-types
  Scenario Outline: Raw compilation records preserve encoding and argument types
    Given a raw compilation record fixture "<record>"
    When the public C compilation parser receives the exact captured bytes
    Then raw compilation discovery reports "<outcome>" without a coerced or partial record
    And raw compilation parsing preserves all input bytes without executing commands

    Examples:
      | record                           | outcome                      |
      | valid Unicode arguments          | available                    |
      | malformed UTF8 in ignored note   | c.compilationDatabaseInvalid |
      | number in arguments              | c.compilationDatabaseInvalid |
      | object in arguments              | c.compilationDatabaseInvalid |
      | null in arguments                | c.compilationDatabaseInvalid |
      | nonarray arguments with command  | c.compilationDatabaseInvalid |

  @REQ-C-COMPILE-UNIT-MODEL-001#compilation-field-types-do-not-coerce-identity-or-drop-output
  Scenario Outline: Compilation field types do not coerce identity or drop output
    Given a raw compilation record fixture "<record>"
    When the public C compilation parser receives the exact captured bytes
    Then raw compilation discovery reports "<outcome>" without a coerced or partial record
    And raw compilation parsing preserves all input bytes without executing commands

    Examples:
      | record                | outcome                      |
      | valid string fields   | available                    |
      | numeric file          | c.compilationDatabaseInvalid |
      | boolean directory     | c.compilationDatabaseInvalid |
      | boolean command       | c.compilationDatabaseInvalid |
      | numeric output        | c.compilationDatabaseInvalid |
      | object output         | c.compilationDatabaseInvalid |

  @REQ-C-COMPILE-UNIT-MODEL-001#compilation-path-values-preserve-identity-and-refuse-invalid-characters
  Scenario Outline: Compilation path values preserve identity and refuse invalid characters
    Given a raw compilation record fixture "<record>"
    When the public C compilation parser receives the exact captured bytes
    Then raw compilation discovery reports "<outcome>" without a coerced or partial record
    And raw compilation parsing preserves all input bytes without executing commands

    Examples:
      | record                           | outcome                      |
      | space-prefixed source filename   | available                    |
      | space-suffixed include directory | available                    |
      | NUL in source filename           | c.compilationDatabaseInvalid |
      | NUL in working directory         | c.compilationDatabaseInvalid |
      | NUL in include operand           | c.compilationDatabaseInvalid |
      | NUL in sysroot operand           | c.compilationDatabaseInvalid |

  @REQ-C-COMPILE-UNIT-MODEL-001#compilation-flag-forms-preserve-model-and-native-header-context
  Scenario Outline: Compilation flag forms preserve model and native header context
    Given a native C compilation flag fixture "<profile>" in "<standard>" mode
    When the captured flag model drives the reference clangd header definition query
    Then the flag projection and native definition retain the exact configured context
    And flag context acquisition preserves project bytes and closes its semantic process

    Examples:
      | profile              | standard |
      | separate define      | c11      |
      | separate define      | c17      |
      | joined quote include | c11      |
      | joined quote include | c17      |
      | joined system include| c11      |
      | joined system include| c17      |

  @REQ-C-COMPILE-UNIT-MODEL-001#include-option-boundaries-refuse-without-repairing-invalid-context
  Scenario Outline: Include option boundaries refuse without repairing invalid context
    Given a native C compilation flag fixture "<profile>" in "<standard>" mode
    When the captured flag model drives the reference clangd header definition query
    Then the inadmissible include option is refused before semantic process startup
    And flag context acquisition preserves project bytes and closes its semantic process

    Examples:
      | profile                   | standard |
      | quote outside             | c11      |
      | system outside            | c17      |
      | quote symlink             | c17      |
      | system symlink            | c11      |
      | missing quote operand     | c11      |
      | removed include separator | c17      |

  @REQ-C-COMPILE-UNIT-MODEL-001#compiler-option-spellings-do-not-shadow-literal-include-paths
  Scenario Outline: Compiler option spellings do not shadow literal include paths
    Given a native C compilation flag fixture "<profile>" in "<standard>" mode
    When the captured flag model drives the reference clangd header definition query
    Then the compilation option outcome is "<outcome>" with exact model and native context
    And flag context acquisition preserves project bytes and closes its semantic process

    Examples:
      | profile                   | standard | outcome   |
      | long system option        | c11      | refused   |
      | joined long system option | c17      | refused   |
      | literal equal quote       | c11      | available |
      | literal equal system      | c17      | available |
      | literal dash quote        | c17      | available |
      | literal dash system       | c11      | available |

  @REQ-C-COMPILE-UNIT-MODEL-001#scalar-compiler-facts-ignore-option-shaped-operands
  Scenario Outline: Scalar compiler facts ignore option-shaped operands
    Given a native C compilation flag fixture "<profile>" in "<standard>" mode
    When the captured flag model drives the reference clangd header definition query
    Then the flag projection and native definition retain the exact configured context
    And flag context acquisition preserves project bytes and closes its semantic process

    Examples:
      | profile                | standard |
      | target shaped include  | c11      |
      | target shaped include  | c17      |
      | sysroot shaped include | c11      |
      | sysroot shaped include | c17      |
      | standard shaped output | c11      |
      | standard shaped output | c17      |


  @REQ-C-COMPILE-UNIT-MODEL-001#explicit-target-flags-preserve-native-header-context
  Scenario Outline: Explicit target flags preserve native header context
    Given a native C compilation flag fixture "<profile>" in "<standard>" mode
    When the captured flag model drives the reference clangd header definition query
    Then the flag projection and native definition retain the exact configured context
    And flag context acquisition preserves project bytes and closes its semantic process

    Examples:
      | profile                | standard |
      | target short i386      | c11      |
      | target short i386      | c17      |
      | target assigned i386   | c11      |
      | target assigned i386   | c17      |
      | target assigned x86_64 | c11      |
      | target assigned x86_64 | c17      |


  @REQ-C-COMPILE-UNIT-MODEL-001#target-admission-refuses-ambiguous-or-incoherent-context
  Scenario Outline: Target admission refuses ambiguous or incoherent context
    Given a native C compilation flag fixture "<profile>" in "<standard>" mode
    When the captured flag model drives the reference clangd header definition query
    Then the compilation option outcome is "refused" with exact model and native context
    And flag context acquisition preserves project bytes and closes its semantic process

    Examples:
      | profile                     | standard |
      | target duplicate identical  | c11      |
      | target duplicate conflicting| c17      |
      | target unknown triple       | c11      |
      | target invalid long separate| c17      |
      | target invalid short assign | c11      |
      | target conflicting source   | c17      |


  @REQ-C-COMPILE-UNIT-MODEL-001#owned-sysroot-preserves-native-header-search
  Scenario Outline: Owned sysroot preserves native header search
    Given a native C compilation flag fixture "<profile>" in "<standard>" mode
    When the captured flag model drives the reference clangd header definition query
    Then the flag projection and native definition retain the exact configured context
    And flag context acquisition preserves project bytes and closes its semantic process

    Examples:
      | profile                   | standard |
      | sysroot assigned relative | c11      |
      | sysroot assigned relative | c17      |
      | sysroot separate relative | c11      |
      | sysroot separate relative | c17      |
      | sysroot assigned absolute | c11      |
      | sysroot assigned absolute | c17      |


  @REQ-C-COMPILE-UNIT-MODEL-001#sysroot-startup-refuses-unowned-or-incoherent-inputs
  Scenario Outline: Sysroot startup refuses unowned or incoherent inputs
    Given a native C compilation flag fixture "<profile>" in "<standard>" mode
    When the captured flag model drives the reference clangd header definition query
    Then the compilation option outcome is "refused" with exact model and native context
    And flag context acquisition preserves project bytes and closes its semantic process

    Examples:
      | profile               | standard |
      | sysroot duplicate     | c11      |
      | sysroot external root | c17      |
      | sysroot linked header | c11      |
      | sysroot driver layout | c17      |
      | sysroot Linux isysroot| c11      |
      | sysroot conflicting source | c17      |


  @REQ-C-COMPILE-UNIT-MODEL-001#distinct-translation-units-retain-independent-native-targets
  Scenario Outline: Distinct translation units retain independent native targets
    Given a native C compilation flag fixture "<profile>" in "<standard>" mode
    When the captured flag model drives the reference clangd header definition query
    Then the flag projection and native definition retain the exact configured context
    And flag context acquisition preserves project bytes and closes its semantic process

    Examples:
      | profile                 | standard |
      | target per-TU explicit  | c11      |
      | target per-TU explicit  | c17      |
      | target per-TU implicit  | c11      |
      | target per-TU implicit  | c17      |


  @REQ-C-COMPILE-UNIT-MODEL-001#distinct-translation-units-retain-independent-owned-sysroots
  Scenario Outline: Distinct translation units retain independent owned sysroots
    Given a native C compilation flag fixture "<profile>" in "<standard>" mode
    When the captured flag model drives the reference clangd header definition query
    Then the flag projection and native definition retain the exact configured context
    And flag context acquisition preserves project bytes and closes its semantic process

    Examples:
      | profile                 | standard |
      | sysroot per-TU explicit | c11      |
      | sysroot per-TU explicit | c17      |
      | sysroot per-TU implicit | c11      |
      | sysroot per-TU implicit | c17      |


  @REQ-C-COMPILE-UNIT-MODEL-001#explicit-external-sdk-scope-permits-read-only-native-navigation
  Scenario Outline: Explicit external SDK scope permits read-only native navigation
    Given a native C compilation flag fixture "<profile>" in "<standard>" mode
    When the captured flag model drives the reference clangd header definition query
    Then the compilation option outcome is "<outcome>" with exact model and native context
    And flag context acquisition preserves project bytes and closes its semantic process

    Examples:
      | profile                    | standard | outcome       |
      | external SDK navigation    | c11      | read-only SDK |
      | external SDK navigation    | c17      | read-only SDK |
      | external SDK rename        | c11      | read-only SDK |
      | external SDK rename        | c17      | read-only SDK |
      | external SDK invalid scope | c17      | SDK rejected  |
      | external SDK escaped header| c11      | SDK rejected  |


  @REQ-C-COMPILE-UNIT-MODEL-001#toolchain-discovery-binds-configured-read-only-sdk-inputs
  Scenario Outline: Toolchain discovery binds configured read-only SDK inputs
    Given a native C compilation flag fixture "<profile>" in "<standard>" mode
    When the captured flag model drives the reference clangd header definition query
    Then the compilation option outcome is "<outcome>" with exact model and native context
    And flag context acquisition preserves project bytes and closes its semantic process

    Examples:
      | profile                  | standard | outcome               |
      | toolchain SDK navigation | c11      | discovered SDK        |
      | toolchain SDK navigation | c17      | discovered SDK        |
      | toolchain SDK refreshed  | c11      | discovered SDK        |
      | toolchain SDK refreshed  | c17      | discovered SDK        |
      | toolchain SDK forged     | c11      | toolchain SDK refused |
      | toolchain SDK changed    | c17      | toolchain SDK refused |
