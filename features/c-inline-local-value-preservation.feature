# language: en
@functional-requirement @partial
Feature: C inline-local refuses unproven value, type and observable effects
  A syntactically valid inline preview is not evidence of equivalent C11/C17 behavior.
  One direct int literal local can be inlined when compiler bindings prove its
  unique read and the exact staged translation unit remains valid. Other shapes
  still fail closed. This Story does not qualify all of C12.
  Authority: docs/releases/v0.8.0-plan.md (C12); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-INLINE-LOCAL-EFFECT-001#unproven-value-type-and-effects-refused
  Scenario Outline: Inline-local refuses the <counterexample> counterexample
    Given an isolated C inline-local source with "<counterexample>"
    When the C inline-local preview is requested for x
    Then inline-local is refused without any edit because preservation is unproven
    And the inline-local source bytes and transaction journal remain unchanged

    Examples:
      | counterexample |
      | alias          |
      | dereference    |
      | conversion     |
      | volatile       |

  @REQ-C-INLINE-LOCAL-EFFECT-001#inline-a-compiler-bound-int-literal-and-rollback
  Scenario: Inline one compiler-bound local literal and roll it back
    Given an isolated C inline-local source with "compiler literal"
    When the C inline-local preview is requested for x
    Then the compiler-proven inline-local preview applies and rolls back with exact source restoration
