# language: en
@functional-requirement @java @daemon @read-only
Business Need: Java editor clients need compiler-bound type and call navigation
  Definition and references cover only part of IDE navigation. The daemon must
  expose type definition, type hierarchy and call hierarchy from the same JDT
  bindings and snapshot authority already used by Java semantic queries.
  Results must remain bounded and must not claim compiler evidence for lexical
  or unresolved relationships.
  Isolated source-only snapshots without build-owned roots or classpaths must
  still resolve Java platform types through the running JVM's system library.

  @REQ-JAVA-SEMANTIC-NAVIGATION-001#java-position-queries-return-compiler-bound-types-and-call-sites
  Scenario: Java position queries return compiler-bound types and call sites
    Given a Java daemon workspace contains bound type and method relationships
  When I query type definition, type hierarchy, call hierarchy, and concrete type and method implementations
    Then each query identifies the expected source declaration and call site
    And implementation lookup returns exact concrete workspace types and overriding methods
    And every result carries compiler evidence and the opened snapshot identity

  @REQ-JAVA-SEMANTIC-NAVIGATION-001#source-only-jdt-analysis-resolves-java-platform-types
  Scenario: Source-only JDT analysis resolves Java platform types
    Given a source-only Java snapshot contains a declaration that uses java.lang.String
    When JDT analysis runs without build roots or classpath entries
    Then the source declaration and java.lang.String use have exact compiler bindings
    And analysis reports no binding problems and leaves the source bytes unchanged

  @REQ-JAVA-SEMANTIC-NAVIGATION-001#maven-dependency-call-hierarchy-preserves-overloaded-method-identity
  Scenario: Maven dependency call hierarchy preserves overloaded method identity
    Given a Maven Java daemon project calls overloaded methods from a local dependency JAR
    When I query outgoing call hierarchy for the selected source caller
    Then the call hierarchy returns distinct exact overload identities and retains the project-local callee
    And binary-only dependency targets have no fabricated source location and the result remains explicitly partial

  @REQ-JAVA-SEMANTIC-NAVIGATION-001#gradle-dependency-call-hierarchy-preserves-overloaded-method-identity
  Scenario: Gradle dependency call hierarchy preserves overloaded method identity
    Given a Gradle Java daemon project calls overloaded methods from a local dependency JAR
    When I query outgoing call hierarchy for the selected source caller
    Then the call hierarchy returns distinct exact overload identities and retains the project-local callee
    And binary-only dependency targets have no fabricated source location and the result remains explicitly partial
