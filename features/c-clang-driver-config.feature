# language: en
# Authority: C02 captured compiler inputs; ARC42 owns integrated acceptance.
@functional-requirement @partial @REQ-C-CLANG-DRIVER-CONFIG-001
Feature: Implicit Clang configuration cannot replace captured compiler inputs
  An untracked default configuration beside an unchanged compiler is not part
  of the caller's captured translation-unit flags. Syntax diagnostics retain
  the captured source, compiler identity and declared C17 configuration.

  @REQ-C-CLANG-DRIVER-CONFIG-001#implicit-clang-config-cannot-change-captured-syntax-diagnostics
  Scenario Outline: Implicit Clang config cannot change captured syntax diagnostics
    Given a captured C compiler context has "<initial>" initial diagnostics
    When a benign default configuration is added beside the captured compiler
    And RefactorKit evaluates those same captured compiler inputs again
    Then the original "<initial>" diagnostics and compiler input identities are preserved without writes

    Examples:
      | initial |
      | clean   |
      | error   |

  @REQ-C-CLANG-DRIVER-CONFIG-001#implicit-clang-config-cannot-replace-captured-function-facts
  Scenario Outline: Implicit Clang config cannot replace captured function facts
    Given a captured C function family uses "<standard>"
    When a benign default configuration is added beside the captured compiler
    And RefactorKit reads those same captured C function facts again
    Then compiler-bound declarations calls and input identities remain unchanged

    Examples:
      | standard |
      | c11      |
      | c17      |

  @REQ-C-CLANG-DRIVER-CONFIG-001#implicit-clang-config-cannot-replace-captured-toolchain-metadata
  Scenario Outline: Implicit Clang config cannot replace captured toolchain metadata
    Given captured Clang metadata is retained before implicit "<field>" configuration
    When a benign default configuration is added beside the captured compiler
    And RefactorKit rediscovers those same configured compiler tools
    Then the captured compiler versions target and resource identity remain unchanged

    Examples:
      | field     |
      | target    |
      | resources |
