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

  @scenario-multiple-independent-built-in-prototypes-per-header-are-reordered-under-c17-diagnostics @REQ-C-INCLUDE-ORDER-001#multiple-independent-built-in-prototypes-per-header-are-reordered-under-c17-diagnostics
  Scenario: Multiple independent built-in prototypes per header are reordered under C17 diagnostics
    Given an isolated C include workspace with "multiple-independent-builtin-prototypes" directives
    When the C organize-includes preview is requested
    Then the multiple-independent-builtin-prototypes include preview is "sorted-preview" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @scenario-multiple-independent-builtin-prototype-headers-apply-and-roll-back-exactly @REQ-C-INCLUDE-ORDER-001#multiple-independent-built-in-prototype-headers-apply-and-roll-back-exactly
  Scenario: Multiple independent built-in prototype headers Apply and roll back exactly
    Given an isolated C include workspace with "multiple-independent-builtin-prototypes" directives
    When the C organize-includes CLI plan is applied and rolled back
    Then the C CLI reports a successful Apply and exact rollback
    And the original source is restored with its rollback transaction record

  @scenario-local-headers-with-duplicate-prototype-names-remain-refused @REQ-C-INCLUDE-ORDER-001#local-headers-with-duplicate-prototype-names-remain-refused
  Scenario: Local headers with duplicate prototype names remain refused
    Given an isolated C include workspace with "duplicate-prototype-name" directives
    When the C organize-includes preview is requested
    Then the duplicate-prototype-name include preview is "refused" without losing an include
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

  @scenario-c11-captured-context-admits-independent-local-prototype-ordering @REQ-C-INCLUDE-ORDER-001#c11-captured-context-admits-independent-local-prototype-ordering
  Scenario: C11 captured context admits independent local prototype ordering
    Given an isolated C include workspace with "independent-builtin-prototypes-c11" directives
    When the C organize-includes preview is requested
    Then the independent-builtin-prototypes-c11 include preview is "sorted-preview" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @scenario-mixed-c11-and-c17-translation-units-retain-per-unit-include-diagnostics @REQ-C-INCLUDE-ORDER-001#mixed-c11-and-c17-translation-units-retain-per-unit-include-diagnostics
  Scenario: Mixed C11 and C17 translation units retain per-unit include diagnostics
    Given an isolated C include workspace with "independent-builtin-prototypes-mixed-standards" directives
    When the C organize-includes preview is requested
    Then the independent-builtin-prototypes-mixed-standards include preview is "sorted-preview" without losing an include
    And the include source bytes and transaction journal remain unchanged

  @scenario-c11-captured-translation-unit-bound-is-applied-before-preview @REQ-C-INCLUDE-ORDER-001#c11-captured-translation-unit-bound-is-applied-before-preview
  Scenario Outline: C11 captured translation-unit bound is applied before preview
    Given an isolated C include workspace with "<case>" directives
    When the C organize-includes preview is requested
    Then the <case> include preview is "<disposition>" without losing an include
    And the include source bytes and transaction journal remain unchanged

  Examples:
      | case                                             | disposition    |
      | independent-builtin-prototypes-c11-two-tu        | sorted-preview |
      | independent-builtin-prototypes-c11-eight-tu      | sorted-preview |
      | independent-builtin-prototypes-c11-nine-tu       | refused        |

  @scenario-c11-include-ordering-captures-only-distinct-literal-defines @REQ-C-INCLUDE-ORDER-001#c11-include-ordering-captures-only-distinct-literal-defines
  Scenario Outline: C11 include ordering captures only distinct literal defines
    Given an isolated C include workspace with "<case>" directives
    When the C organize-includes preview is requested
    Then the <case> include preview is "<disposition>" without losing an include
    And the include source bytes and transaction journal remain unchanged

    Examples:
      | case                                                                    | disposition    |
      | independent-builtin-prototypes-c11-distinct-literal-defines              | sorted-preview |
      | independent-builtin-prototypes-c11-symbolic-define                       | refused        |
      | independent-builtin-prototypes-c11-duplicate-define                     | refused        |

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
