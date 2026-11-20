# language: en
# Authority: bounded Maven discovery; integrated status belongs to docs/arc42/appendix-requirements.adoc.
@functional-requirement @partial
Feature: Preserve explicitly declared Maven modules with build output names
  An effective Maven reactor declaration owns its module even when the path
  contains build or target. An inactive profile grants no such authority.
  Undeclared project copies inside build output remain excluded.

  @REQ-JAVA-SCAN-DECLARED-001 @java-scan-declared-output-module
  Scenario Outline: A declared output-named Maven module retains only its declared authority
    Given a Maven reactor declares "<module>" through "<declaration>"
    When Java discovery captures the module before and after its descriptor changes
    Then module membership and descriptor evidence are "<authority>"
    And undeclared build copies never gain discovery authority

    Examples:
      | module        | declaration      | authority |
      | build         | direct           | admitted  |
      | target        | direct           | admitted  |
      | parent/build  | nested           | admitted  |
      | parent/target | nested           | admitted  |
      | build         | selected profile | admitted  |
      | target        | inactive profile | excluded  |
