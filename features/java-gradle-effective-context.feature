@functional-requirement @REQ-JAVA-GRADLE-CONTEXT-001
Feature: Effective Java Gradle context
  Gradle synchronization is explicit; ordinary context discovery remains read-only.
  Authority: this Story; architecture: docs/arc42/appendix-requirements.adoc.

  Composite builds are covered below under the same requirement. Their qualified semantic
  identities and refusal behavior are provider facts; native IDE mapping from those identities
  to project discovery, run/test selectors, launch/debug and selected-project coverage remains
  a separate consumer acceptance owned by the IDE.

  @REQ-JAVA-GRADLE-CONTEXT-001#explicit-gradle-sync-provides-effective-java-context-or-refuses-execution-and-stale-evidence
  Scenario Outline: Explicit Gradle sync provides effective Java context or refuses execution and stale evidence
    Given a Gradle Java workspace with six modules and distinct dependency scopes
    When Gradle is synchronized with "<policy>" and Java "<selection>" context is requested
    Then effective Gradle context reports "<outcome>" with exact compile and runtime visibility
    And the Gradle context preserves original Java and build input bytes

  Examples:
      | policy        | selection | outcome                            |
      | explicit      | main      | available                          |
      | explicit      | test      | available                          |
      | explicit SDK25| main      | available                          |
      | denied        | main      | gradle.sync.executionNotAuthorized  |
      | explicit      | stale build| java.context.snapshotStale         |
      | explicit      | other SDK | java.context.sdkMismatch           |

  @REQ-JAVA-GRADLE-CONTEXT-001#composite-gradle-context-qualifies-workspace-build-identity-or-refuses-ambiguous-stale-and-outside-roots
  Scenario Outline: Composite Gradle context qualifies workspace build identity or refuses ambiguous, stale and outside roots
    Given a Gradle composite Java workspace with "<layout>" included builds
    When project context is requested with "<selection>" identity and "<freshness>" evidence
    Then the composite context reports "<outcome>" with qualified workspace ownership
    And refused composite context contains no partial model
    And project summary preserves all published Gradle composite identities

  Examples:
      | layout      | selection                 | freshness | outcome                                  |
      | nested      | provider-qualified         | current   | available                                |
      | nested      | legacy duplicate project path | current | java.context.sourceAssociationAmbiguous |
      | nested      | provider-qualified         | stale     | java.context.snapshotStale               |
      | outside     | provider-qualified         | current   | gradle.sync.includedBuildOutsideWorkspace |
      | custom-project-dir | provider-qualified   | current   | available                                |

  @REQ-JAVA-GRADLE-CONTEXT-001#included-gradle-app-selection-retains-build-root-project-path-and-project-directory
  Scenario: Included Gradle app selection retains build root project path and project directory
    Given a Gradle composite Java workspace with "included-app" included builds
    When project context is requested with "provider-qualified" identity and "current" evidence
    Then included Gradle app context preserves its exact build identity and mapped directory
    And project summary preserves all published Gradle composite identities

  @REQ-JAVA-GRADLE-CONTEXT-001#offline-kotlin-dsl-sync-retains-app-to-library-main-context
  Scenario: Offline Kotlin DSL sync retains app-to-library main context
    Given a Gradle Kotlin DSL Java workspace with an app depending on a library
    When the Kotlin DSL workspace is explicitly synchronized offline and app context is requested
    Then project context retains the library main dependency in app compile and runtime context
    And Kotlin DSL and Java project inputs remain byte-identical after sync

  @REQ-JAVA-GRADLE-CONTEXT-001#root-javacompile-options-reach-selected-app-context
  Scenario: Root JavaCompile options reach selected app context
    Given a Gradle Java multi-project where the root configures preview compiler arguments for subprojects
    When the app Java 21 classes task is built and its Gradle source context is requested
    Then the selected app main context reports the ordered preview compiler arguments from its compileJava task
    And the Gradle context preserves original Java and build input bytes

  @REQ-JAVA-GRADLE-CONTEXT-001#kotlin-dsl-settings-project-directories-preserve-provider-owned-source-context
  Scenario: Kotlin DSL settings project directories preserve provider-owned source context
    Given a Gradle Kotlin DSL Java workspace maps app and library to custom project directories
    When the remapped Kotlin DSL workspace is synchronized offline and app main and test contexts are requested
    Then remapped Kotlin DSL contexts preserve project identity source ownership and library classpaths
    And Kotlin DSL and Java project inputs remain byte-identical after sync

  @REQ-JAVA-GRADLE-CONTEXT-001#kotlin-dsl-custom-integration-source-set-selects-exact-module-and-classpath
  Scenario: Kotlin DSL custom integration source set selects exact module and classpath
    Given a Gradle Kotlin DSL Java workspace declares an integrationTest source set
    When the Kotlin DSL workspace is synchronized offline and integrationTest context is requested
    Then project context selects the integrationTest source set with exact library main visibility
    And Kotlin DSL and Java project inputs remain byte-identical after sync

  @REQ-JAVA-GRADLE-CONTEXT-001#aggregate-gradle-context-retains-qualified-workspace-identities-after-sync
  Scenario: Aggregate Gradle context retains qualified workspace identities after explicit sync
    Given a Gradle composite Java workspace with "nested" included builds
    When project context is requested with "aggregate" identity and "current" evidence
    Then aggregate Gradle context exposes qualified workspace identity for every module

  @REQ-JAVA-GRADLE-CONTEXT-001#aggregate-gradle-context-retains-qualified-workspace-identities-across-repeated-sync
  Scenario: Aggregate Gradle context retains qualified workspace identities across repeated sync
    Given a Gradle composite Java workspace with "nested" included builds
    When project context is requested with "aggregate" identity and "current" evidence
    And the selected Gradle module context is refreshed after another explicit sync
    Then aggregate Gradle context exposes qualified workspace identity for every module
    And project summary preserves all published Gradle composite identities

  @REQ-JAVA-GRADLE-CONTEXT-001#failed-gradle-export-reports-bounded-redacted-process-cause
  Scenario Outline: Failed Gradle export reports bounded redacted process cause
    Given an explicit Gradle export failure fixture "<fixture>"
    When the failing exporter is synchronized through the public project API
    Then the sync refusal retains "<code>" and bounded cause "<cause>"
    And the export refusal reveals no raw process secrets and publishes no model

    Examples:
      | fixture                  | code                                | cause                                      |
      | cache read only          | gradle.sync.exportFailed             | gradle.sync.cause.cacheReadOnly             |
      | real cache permission denied | gradle.sync.exportFailed             | gradle.sync.cause.cachePermissionDenied     |
      | real offline dependency  | gradle.sync.exportFailed             | gradle.sync.cause.offlineDependencyUnavailable |
      | real empty cache denied  | gradle.sync.exportFailed             | gradle.sync.cause.unknown                   |
      | unrelated path denied    | gradle.sync.exportFailed             | gradle.sync.cause.filesystemPermissionDenied |
      | fast oversized log       | gradle.sync.outputLimitExceeded      | absent                                     |


  @REQ-JAVA-GRADLE-CONTEXT-001#runtime-classpath-ownership-follows-resolved-project-artifacts-at-a-shared-jar-path
  Scenario Outline: Runtime classpath ownership follows resolved project artifacts at a shared JAR path
    Given a Gradle Java app selects "<dependencies>" from projects with one shared JAR output
    When the shared-output Gradle workspace is explicitly synchronized and aggregate context requested
    Then the shared runtime JAR outcome is "<outcome>"
    And the shared-output Gradle workspace retains its Java and build input bytes

    Examples:
      | dependencies | outcome   |
      | library      | :library  |
      | shadow       | :shadow   |
      | both         | ambiguous |

  @REQ-JAVA-GRADLE-CONTEXT-001#aggregate-gradle-context-exports-the-evaluated-build-tree-project-path
  Scenario Outline: Aggregate Gradle context exports the evaluated build tree project path
    Given a transitive Gradle application composite with custom aliases on "<gradle>"
    When the aliased composite is synchronized and build tree paths are explicitly requested
    Then the exported build tree project path launches the substituted library from the composite root

    Examples:
      | gradle |
      | 8.11.1 |
      | 9.8.1  |

  @REQ-JAVA-GRADLE-CONTEXT-001#gradle-selected-context-retains-build-tree-project-path-across-compilation
  Scenario: Gradle selected context retains build tree project path across compilation
    Given a transitive Gradle application composite with custom aliases on "9.8.1"
    When the aliased composite is synchronized and build tree paths are explicitly requested
    Then selected Gradle v7 and v3 contexts retain the build tree path before and after compilation

  @REQ-JAVA-GRADLE-CONTEXT-001#gradle-composite-resync-retains-parent-owned-artifacts-after-run
  Scenario: Gradle composite resync retains parent-owned artifacts after run
    Given a Gradle nested Java app substitutes a library owned by its parent build
    When the composite is synchronized before and after running both applications
    Then the refreshed composite keeps the evaluated task path and the built parent library owner
