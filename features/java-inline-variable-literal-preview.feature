# language: en
@implemented-and-validated
Ability: Preview binding-safe inlining for a Java local integer literal
  As a Java refactoring caller
  I want a local initialized by an integer literal to be inlined only when its binding is proven
  So that the adapter can offer a small deterministic inline-variable operation without guessing

  This bounded slice supports one declaration fragment that is a direct statement of an ordinary
  method block, initialized by a resolved primitive-int literal, with binding-matched read-only
  references in that method. It refuses writes, unsupported initializers or scopes, comments that
  would be removed with the declaration line, generated source, and unavailable bindings. This is
  not general inline-variable support and does not infer Kotlin or other Java forms.

  @REQ-JAVA-INLINE-VARIABLE-LITERAL-001#inline-all-binding-matched-reads-of-a-local-primitive-int-literal @functional-requirement
  Scenario: Inline all binding-matched reads of a local primitive-int literal
    Given the Java workspace contains a local int initialized by a literal and two reads
    When inlineVariable is previewed for that local in the saved Java file
    Then the preview produces this Java source:
      """
      package fixture.api;
      public class Calculator {
          public int add() {
              return 7 + 7;
          }
      }
      """
    And the Java adapter advertises inlineVariable as stable managed and applies then rolls back the preview
