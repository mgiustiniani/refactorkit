@REQ-C-CLANG-RUNTIME-DISCOVERY-001
Feature: Native input authority during C toolchain discovery
  Tool probes require a matching current runtime inventory for actual host inputs.
  A new capture cannot renew authority while discovery is already in progress.

  @REQ-C-CLANG-RUNTIME-DISCOVERY-001#toolchain-discovery-retains-current-native-inputs-before-probes-or-refuses-unavailable-authority
  Scenario Outline: Toolchain discovery retains current native inputs before probes or refuses unavailable authority
    Given C toolchain runtime discovery has "<condition>"
    When the configured C tools are discovered with retained runtime inputs
    Then C runtime toolchain discovery is "<outcome>" after "<probes>" version probes
    And runtime discovery preserves source and closes native probe processes

    Examples:
      | condition            | outcome | probes |
      | native reference     | current | 3      |
      | non-ELF executable   | refused | 0      |
      | library changes      | refused | 1      |
      | executable changes   | refused | 1      |
      | alternate cache      | refused | 0      |
      | mismatched inventory | refused | 0      |
