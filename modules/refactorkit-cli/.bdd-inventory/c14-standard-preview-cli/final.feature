# language: en
@functional-requirement @partial
Feature: C include organization refuses unproven preprocessing order
  Angle brackets also name application headers through include paths. They are
  not evidence that macros and header dependencies are order-independent.
  This bounded safeguard is not full C14 redundancy/independence qualification.
  Authority: docs/releases/v0.8.0-plan.md (C14); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-INCLUDE-ORDER-001#c-cli-preview-emits-sorted-standard-includes-without-writing-workspace-files
  Scenario: C CLI preview emits sorted standard includes without writing workspace files
    Given an isolated C include workspace with "standard" directives
    When the C organize-includes CLI preview is requested
    Then the standard include preview is "sorted-preview" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @REQ-C-INCLUDE-ORDER-001#c-cli-apply-and-rollback-restores-exact-source-bytes
  Scenario: C CLI apply and rollback restores exact source bytes
    Given an isolated C include workspace with "standard" directives
    When the C organize-includes CLI plan is applied and rolled back
    Then the C CLI reports a successful Apply and exact rollback
    And the original source is restored with its rollback transaction record

  @scenario-c14-standard-preview @REQ-C-INCLUDE-ORDER-001
  @REQ-C-INCLUDE-ORDER-001#organizing-header-case-includes-preserves-only-the-admitted-order-independent-subset
  Scenario Outline: Organizing <header_case> includes preserves only the admitted order-independent subset
    Given an isolated C include workspace with "<header_case>" directives
    When the C organize-includes preview is requested
    Then the <header_case> include preview is "<disposition>" without losing an include
    And the include source bytes and transaction journal remain unchanged

  Examples:
      | header_case | disposition    |
      | custom      | refused        |
      | macro       | refused        |
      | standard    | sorted-preview |
      | standard-with-comments | sorted-preview |

  @scenario-repeated-local-pragma-once-include-is-removed-with-source-lines-preserved @REQ-C-INCLUDE-ORDER-001#repeated-local-pragma-once-include-is-removed-with-source-lines-preserved
  Scenario: Repeated local pragma-once include is removed with source lines preserved
    Given an isolated C include workspace with "duplicate-once" directives
    When the C organize-includes CLI preview is requested
    Then the duplicate-once include preview is "redundant-preview" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @scenario-repeated-local-pragma-once-include-applies-and-rolls-back @REQ-C-INCLUDE-ORDER-001#repeated-local-pragma-once-include-applies-and-rolls-back
  Scenario: Repeated local pragma-once include applies and rolls back
    Given an isolated C include workspace with "duplicate-once" directives
    When the C organize-includes CLI plan is applied and rolled back
    Then the C CLI reports a successful Apply and exact rollback
    And the original source is restored with its rollback transaction record

  @scenario-repeated-local-pragma-once-include-preserves-other-unique-includes @REQ-C-INCLUDE-ORDER-001#repeated-local-pragma-once-include-preserves-other-unique-includes
  Scenario: Repeated local pragma-once include preserves other unique includes
    Given an isolated C include workspace with "duplicate-once-with-other-include" directives
    When the C organize-includes CLI preview is requested
    Then the duplicate-once-with-other-include include preview is "redundant-preview" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @scenario-repeated-local-pragma-once-include-apply-retains-other-unique-includes @REQ-C-INCLUDE-ORDER-001#repeated-local-pragma-once-include-apply-retains-other-unique-includes
  Scenario: Repeated local pragma-once include Apply retains other unique includes
    Given an isolated C include workspace with "duplicate-once-with-other-include" directives
    When the C organize-includes CLI plan is applied and rolled back
    Then the C CLI reports a successful Apply and exact rollback
    And the original source is restored with its rollback transaction record

  @scenario-multiple-repeated-local-pragma-once-include-groups-remain-refused @REQ-C-INCLUDE-ORDER-001#multiple-repeated-local-pragma-once-include-groups-remain-refused
  Scenario: Multiple repeated local pragma-once include groups remain refused
    Given an isolated C include workspace with "multiple-duplicate-once-groups" directives
    When the C organize-includes preview is requested
    Then the multiple-duplicate-once-groups include preview is "refused" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @scenario-two-independent-local-function-prototype-headers-are-reordered-with-original-lines-preserved @REQ-C-INCLUDE-ORDER-001#two-independent-local-function-prototype-headers-are-reordered-with-original-lines-preserved
  Scenario: Two independent local function prototype headers are reordered with original lines preserved
    Given an isolated C include workspace with "independent-prototypes" directives
    When the C organize-includes preview is requested
    Then the independent-prototypes include preview is "sorted-preview" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @scenario-typedef-dependent-local-header-reordering-is-refused @REQ-C-INCLUDE-ORDER-001#typedef-dependent-local-header-reordering-is-refused
  Scenario: Typedef-dependent local header reordering is refused
    Given an isolated C include workspace with "typedef-dependent" directives
    When the C organize-includes preview is requested
    Then the typedef-dependent include preview is "refused" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @scenario-independent-built-in-scalar-prototype-headers-are-reordered-under-c17-diagnostics @REQ-C-INCLUDE-ORDER-001#independent-built-in-scalar-prototype-headers-are-reordered-under-c17-diagnostics
  Scenario: Independent built-in scalar prototype headers are reordered under C17 diagnostics
    Given an isolated C include workspace with "independent-builtin-prototypes" directives
    When the C organize-includes preview is requested
    Then the independent-builtin-prototypes include preview is "sorted-preview" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @scenario-blank-line-separated-includes-are-refused-without-losing-spacing @REQ-C-INCLUDE-ORDER-001#blank-line-separated-includes-are-refused-without-losing-spacing
  Scenario: Blank-line-separated includes are refused without losing spacing
    Given an isolated C include workspace with "standard-with-blank-line" directives
    When the C organize-includes preview is requested
    Then the standard-with-blank-line include preview is "refused" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @scenario-c-organize-includes-preview-checks-all-captured-c17-translation-units @REQ-C-INCLUDE-ORDER-001#c-organize-includes-preview-checks-all-captured-c17-translation-units
  Scenario: C organize-includes preview checks all captured C17 translation units
    Given an isolated C include workspace with "independent-builtin-prototypes-multi-tu" directives
    When the C organize-includes preview is requested
    Then the independent-builtin-prototypes-multi-tu include preview is "sorted-preview" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @scenario-c-organize-includes-preview-checks-three-captured-c17-translation-units @REQ-C-INCLUDE-ORDER-001#c-organize-includes-preview-checks-three-captured-c17-translation-units
  Scenario: C organize-includes preview checks three captured C17 translation units
    Given an isolated C include workspace with "independent-builtin-prototypes-three-tu" directives
    When the C organize-includes preview is requested
    Then the independent-builtin-prototypes-three-tu include preview is "sorted-preview" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @scenario-c-organize-includes-preview-checks-the-eight-translation-unit-bound @REQ-C-INCLUDE-ORDER-001#c-organize-includes-preview-checks-the-eight-translation-unit-bound
  Scenario: C organize-includes preview checks the eight translation-unit bound
    Given an isolated C include workspace with "independent-builtin-prototypes-eight-tu" directives
    When the C organize-includes preview is requested
    Then the independent-builtin-prototypes-eight-tu include preview is "sorted-preview" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @scenario-c-organize-includes-preview-refuses-more-than-eight-captured-c17-translation-units @REQ-C-INCLUDE-ORDER-001#c-organize-includes-preview-refuses-more-than-eight-captured-c17-translation-units
  Scenario: C organize-includes preview refuses more than eight captured C17 translation units
    Given an isolated C include workspace with "independent-builtin-prototypes-nine-tu" directives
    When the C organize-includes preview is requested
    Then the independent-builtin-prototypes-nine-tu include preview is "refused" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @REQ-C-INCLUDE-ORDER-001#non-c-translation-units-are-refused-before-include-reorganization
  Scenario Outline: Non-C translation units are refused before include reorganization
    Given an isolated C include workspace with "<language_id>" directives
    When the C organize-includes preview is requested
    Then the <language_id> include preview is "refused" without losing an include
    And the include source bytes and transaction journal remain unchanged

    Examples:
      | language_id |
      | cpp         |
      | objective-c |
