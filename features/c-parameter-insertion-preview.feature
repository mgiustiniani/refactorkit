# language: en
# Authority: bounded C10 library insertion preview; ARC42 owns integrated status.
@functional-requirement @partial
Feature: Preview explicit C parameter insertion in its captured compiler context
  One named parameter and a source-span-bound expression for every direct call
  form a single preview. Compiler facts must prove scope and argument identity,
  preserve existing evaluation, and refuse expressions escaping their argument.

  @REQ-C-PARAMETER-INSERTION-PREVIEW-001 @c-parameter-insertion-preview
  Scenario Outline: C parameter insertion previews exact declarations and callers or refuses unsafe evidence
    Given a captured C parameter insertion has "<case>"
    When its named parameter and explicit caller expressions are previewed
    Then the C parameter insertion preview has disposition "<disposition>"
    And C parameter insertion preview preserves saved bytes and releases compiler material

    Examples:
      | case                       | disposition                |
      | C17 integer family         | complete insertion preview |
      | C11 explicit void family   | complete insertion preview |
      | global binding captured    | name capture refused       |
      | function address taken     | indirect consumers refused |
      | observable added argument  | observable effect refused  |
      | escaped caller expression  | escaped expression refused |
