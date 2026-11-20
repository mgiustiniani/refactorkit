# language: en
@functional-requirement @partial
Feature: C inline-function cannot change expressions, bindings or conversions
  A single direct call does not establish compiler-backed binding and effect closure.
  Correct the bounded precedence/string cases and refuse captures, conversions or
  increment effects that lack proof. This is not C12 acceptance.
  Authority: docs/releases/v0.8.0-plan.md (C12); integrated status: docs/arc42/appendix-requirements.adoc.

  @REQ-C-INLINE-FUNCTION-EFFECT-001
  @REQ-C-INLINE-FUNCTION-EFFECT-001#inline-function-handles-counterexample-without-silently-changing-meaning
  Scenario Outline: Inline-function handles <counterexample> without silently changing meaning
    Given an isolated C inline-function source with "<counterexample>"
    When the C inline-function preview is requested for its helper
    Then the <counterexample> inline-function outcome is "<disposition>" and no unrelated token is rewritten
    And the inline-function source bytes and transaction journal remain unchanged

    Examples:
      | counterexample | disposition        |
      | precedence     | equivalent-preview |
      | capture        | refused            |
      | conversion     | refused            |
      | string         | equivalent-preview |
      | increment      | refused            |

  @REQ-C-INLINE-FUNCTION-EFFECT-001
  @REQ-C-INLINE-FUNCTION-EFFECT-001#captured-c-functions-inline-with-exact-semantics-approval-and-rollback
  Scenario Outline: Captured C functions inline with exact semantics approval and rollback
    Given a captured "<standard>" C inline-function fixture has "<shape>" semantics
    When the compiler-bound function is inlined through the managed lifecycle
    Then the inlined program matches its independently expected semantic image
    And exact rollback restores the captured C inline-function inputs

    Examples:
      | standard | shape            |
      | c11      | precedence       |
      | c17      | precedence       |
      | c11      | local arguments  |
      | c17      | local arguments  |
      | c17      | prototype family |
      | c17      | string operand   |

  @REQ-C-INLINE-FUNCTION-EFFECT-001
  @REQ-C-INLINE-FUNCTION-EFFECT-001#unsafe-function-inline-contexts-refuse-without-changing-captured-inputs
  Scenario Outline: Unsafe function-inline contexts refuse without changing captured inputs
    Given an isolated C inline-function source with "<counterexample>"
    When the C inline-function preview is requested for its helper
    Then the <counterexample> inline-function outcome is "refused" and no unrelated token is rewritten
    And the inline-function source bytes and transaction journal remain unchanged

    Examples:
      | counterexample     |
      | argument-increment |
      | lifetime           |
      | recursion          |
      | macro              |
      | control-flow       |
      | aliasing           |
