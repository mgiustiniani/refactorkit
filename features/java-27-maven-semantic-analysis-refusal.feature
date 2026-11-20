# language: en
# Authority: this feature specifies bounded Maven and Gradle refusal cells; ARC42 remains the integrated status authority.
# Sub-requirement of REQ-JAVA-PROJECT-CONTEXT-001, scoped to Java 27 semantic-analysis refusal.
# See docs/arc42/appendix-requirements.adoc, “Java 27 stable JDT gate recheck”.
@functional-requirement @partial @REQ-JAVA-27-SEMANTIC-ANALYSIS-001
Feature: Refuse unqualified Java 27 semantic requests
  Provider-qualified Maven and Gradle v7 metadata contexts do not qualify Java 27 source semantics.
  Definition lookup and class rename requests against the verified Java 27 fixtures return
  java.context.sourceAnalysisUnqualified before creating a preview plan or managed transaction,
  while preserving source and project inputs.

  @REQ-JAVA-27-SEMANTIC-ANALYSIS-001#maven-java-27-definition-and-class-rename-refuse-unqualified-source-analysis
  Scenario Outline: Maven Java 27 definition and class rename refuse unqualified source analysis
    Given a Maven Java 27 project and the verified JDK 27 platform
    When the provider-qualified Maven v7 metadata context handles a Java "<operation>" request
    Then the Java "<operation>" request is refused with "java.context.sourceAnalysisUnqualified"
    And the Maven Java 27 source and project inputs remain unchanged with no preview plan or managed transaction

    Examples:
      | operation   |
      | definition  |
      | class rename |

  @REQ-JAVA-27-SEMANTIC-ANALYSIS-001#gradle-java-27-definition-and-class-rename-refuse-unqualified-source-analysis
  Scenario Outline: Gradle Java 27 definition and class rename refuse unqualified source analysis
    Given a Gradle Java 27 project and the verified JDK 27 platform
    When the provider-qualified Gradle v7 metadata context handles a Java "<operation>" request
    Then the Gradle Java "<operation>" request is refused with "java.context.sourceAnalysisUnqualified"
    And the Gradle Java 27 source and project inputs remain unchanged with no preview plan or managed transaction

    Examples:
      | operation   |
      | definition  |
      | class rename |
