# language: en
@functional-requirement @java @cli @read-only @partial
Business Need: Locate annotated Java members without disguising unavailable analysis as absence
  Developers and coding agents need stable source navigation for ordinary annotated Java declarations.
  A declaration does not disappear when an annotation shares its line, contains arguments or spans lines.
  Unsigned lexical navigation is distinct from signed JDT binding evidence; neither permits mutation.
  A packaged runtime must contain both java.compiler and jdk.compiler so projects using com.sun.source
  compiler APIs can be inspected without an external JDK or JAVA_HOME workaround.
  Semantic warnings remain blocking for signed definition lookup, but must be reported as unavailable
  analysis rather than a false assertion that the requested symbol does not exist.
  This bounded CLI contract complements the architecture requirements appendix; it does not change
  managed refactoring authority, accept partial bindings for writes or promise all Java grammar shapes.

  @REQ-JAVA-ANNOTATED-NAVIGATION-001
  Scenario: Packaged navigation preserves annotated declarations and distinguishes unresolved analysis
    Given a packaged Java navigation workspace contains these declarations and a compiler API consumer:
      | class                 | member kind | annotation shape           |
      | InlineResource        | method      | inline marker              |
      | SeparateResource      | method      | preceding marker           |
      | ArrayResource         | method      | qualified array and string |
      | MultilineResource     | method      | multiline nested arguments |
      | AnnotatedField        | field       | inline marker              |
      | AnnotatedConstructor  | constructor | inline marker              |
    When the packaged CLI lists and locates those members before and after an unrelated type-resolution failure
    Then each annotated member is listed once at its exact source location and signed callable definitions work with the embedded compiler APIs
    And absent symbols and unavailable semantic analysis have distinct diagnostics while every read preserves the workspace bytes
