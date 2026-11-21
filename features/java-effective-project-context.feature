# language: en
# Authority: proposed project-context acceptance; ARC42 owns integrated status.
@functional-requirement @partial @REQ-JAVA-PROJECT-CONTEXT-001
Feature: Expose snapshot-bound Java effective project context to integration consumers
  Public provenance identifies module, source set, source root and effective classpath scope.
  Same source-relative paths and secondary top-level types never justify a guessed source association.
  JDI, build/run and editor markers remain consumer/JDK responsibilities.

  Entry-point discovery under this same requirement has a qualified classic source
  and native NDJSON increment: exact provider/module/source-set, native binary
  names and snapshot/context/schema/SDK authority. Frame/ID limits and queued
  cancellation are qualified on source and an isolated runtime candidate.
  Capability advertises classic-declared-v1 only; inherited/Java25/compact-preview
  and wider provider/SDK/ownership matrices remain mandatory final scope.
  complete=false exposes unqualified forms. Discovery never compiles,
  loads user classes or executes main.

  Explicit plain-Java declarations own literal source roots and ordered classpaths.
  Analysis uses selected roots plus only output owners explicitly admitted by the
  compile classpath. Package directories named build inside a declared source root
  remain Java input; conventional directory-name exclusions must not hide them.

  Planned extension under this requirement: the public `project.context` response projects
  each effective Maven source set directly from provider-declared records, with snapshot-bound
  module identity and kind (including CUSTOM and
  INTEGRATION_TEST), source/generated roots, output directories, ordered compile/runtime
  classpaths and provenance. Effective compiler options, processor paths/dependencies/options
  with source-set ownership, and Maven Failsafe execution/configuration/report metadata are
  exposed only when derived safely from the effective reactor/profile model; unsupported or
  ambiguous plugin/profile shapes return typed refusals. The wire binds plugin identity/version,
  execution id/goals/phase, profile provenance and explicitly configured selector/report values
  to provider/module/source-set identity. It does not claim goals ran or reports exist. For v7
  test-JVM facts, plugin defaults are projected only from a local plugin descriptor whose
  coordinates, version, selected goal, and JAR SHA-256 are verified and snapshot-bound, with a
  literal version inherited from one workspace-contained immediate-parent pluginManagement
  entry. An empty effective argLine is complete only when its descriptor expression is the user
  property, no effective POM argLine property or explicit override exists, and no active JaCoCo
  prepare-agent or prepare-agent-integration execution contributes to that property. An explicit
  nonliteral module plugin version is never attributed to parent pluginManagement, even when its
  interpolation happens to produce the same version; unsupported inheritance, profile layers,
  missing descriptors, and known contributors remain partial. Any
  Failsafe DTO uses a declared versioned schema; the closed
  v2 schema must not silently grow fields. JPMS context represents module paths separately from
  classpaths and binds parsed module-info identity, requires/exports and descriptor hash to the
  snapshot. If a descriptor contains opens/uses/provides not represented on the wire, JPMS must
  be partial/unavailable and required JPMS facts refuse; they may not be silently omitted under
  complete availability. Stale or ambiguous module graphs refuse, without asserting that every
  JPMS project fails.
  Acceptance fixtures include a real multi-module Maven Failsafe custom-root case under both
  active and inactive profile states, compared with the supported effective Maven outputs and
  classpaths. Assert module-scoped INTEGRATION_TEST identity, source/generated roots, outputs,
  root provenance, ordered compile/runtime classpaths, and typed refusal for stale POM/profile
  evidence or ambiguous module/root ownership. Assert exact Failsafe wire values and typed
  absent/unavailable/refusal cases for unresolved expressions, unsupported effective-model
  shapes and ambiguous profile/execution ownership. Never infer Failsafe ownership from path or
  naming convention; the IDE must not reconstruct or infer Maven source-set semantics.
  Provider identity is part of exact selection: providerId, moduleId and sourceSetId together
  identify a source set, and selectedSource echoes all three. Qualify legacy v1 pair-only
  selection only when (moduleId, sourceSetId) resolves to exactly one provider row; a collision
  must return typed sourceAssociationAmbiguous and no selectedSource. A Maven/Gradle collision
  fixture uses equal moduleId and sourceSetId values, proves provider-qualified requests select
  the intended row, and validates each response against its declared v1/v2 (or explicitly
  versioned successor) schema. No consumer may bind the first matching row.
  The IDE owns task/goal, test-selector and report mapping and fails closed
  when exact module/source-set context is unavailable or refused. Assert module-path names,
  fingerprints, descriptor identity, open/requires/qualified-exports facts and content hash
  against real Maven and Gradle named-module fixtures; prove stale descriptor/artifact refusal.
  A complete, snapshot-bound module path and its owning module descriptor are supplied to JDT as
  separate semantic inputs. Association is available only when module identity, requires/exports,
  source-root ownership and module-path contents are complete and unambiguous; exported readable
  dependency types bind, while inaccessible packages remain unresolved. Incomplete directives,
  stale inputs and ambiguous ownership continue to refuse with typed context errors.
  Explicit Maven compiler annotationProcessorPath entries that resolve to a unique workspace
  reactor JAR may project its materialized output directory only when it is inside the workspace
  and the processor runtime dependency closure is complete. Duplicate coordinates, absent output,
  unsupported artifact variants, or incomplete dependency closure keep the annotation-processor
  fact ambiguous, unavailable or partial. Materialized generated Java roots remain source-set-owned
  and snapshot-bound. RefactorKit does not execute Maven goals or processors; test execution, JDI
  and coverage remain IDE-owned.

  Add Gradle compiler-option coverage under this same requirement: after explicit Gradle sync,
  the actual public `project.context` source-set fact must preserve effective `JavaCompile`
  options, including `--enable-preview`, its source-set/task origin, option order and complete
  availability. Compare an enabled fixture with a source set where the option is absent; do not
  infer preview state from `release`, `source` or `target`. If argument providers or other
  configuration cannot be captured completely, mark the fact partial/unavailable and refuse a
  request requiring `compiler-options`. Qualified positive source analysis remains Java 8-26.
  Maven and Gradle Java 26 context and analysis have provider-specific positive acceptance.
  Java 27 has separate provider-specific v6 acceptance for compiler/platform metadata, while
  source selection and semantic analysis remain explicitly unqualified until the released JDT DOM
  and a matching JDK 27 platform are separately qualified. The Java 27 v6 metadata contract does
  not qualify source-language support. This same requirement covers GA Java 26 and 27 (GA as of
  2026-10-26; see the Java SE specifications and
  release list). Acceptance separately covers Maven and Gradle source sets and binds the declared `--release`/source/
  target values to the selected JDK/compiler identity, ordered compiler options, and exact
  platform API baseline used by JDT. For `--release`, bind the selected JDK's matching `ct.sym`
  entry and hash; for source/target-only modes, bind the actual compiler system modules/API
  inputs without inferring an API restriction. Preserve the JDK release metadata and platform
  module identities/hashes consumed by JDT. Distinguish the compiler runtime JDK from the project's
  source/API release; a JDK 27 compiler does not prove a Java 27 target, and a Java 27 target
  does not identify the JDK used for analysis. Positive analysis requires a parser/JDT level
  and platform classes/API metadata that match the requested source level; stale, missing, or
  conflicting facts must return a typed refusal before publishing misleading context. Do not
  infer support from JDT LS acceptance, generated build outputs, or JDK path/version alone.
  Future GA feature releases require an explicit versioned support-matrix update and their own
  metadata acceptance. Java 27 positive source-analysis support remains `actionable`. Keep Maven
  and Gradle metadata, qualified analysis, and unsupported-level cases distinct in their
  provider-specific Story slices.

  @REQ-JAVA-PROJECT-CONTEXT-001#public-gradle-context-reports-ordered-java-compiler-arguments-or-refuses-incomplete-provider-facts
  Scenario Outline: Public Gradle context reports ordered Java compiler arguments or refuses incomplete provider facts
    Given a Gradle Java project with compiler arguments "<configuration>"
    # The project.sync request omits gradleUserHome; use GRADLE_USER_HOME when present, then user.home/.gradle.
    When Gradle is synchronized without a request user home and public Java context requires compiler options
    Then the public Gradle context reports compiler arguments "<expected>" with availability "<availability>" and outcome "<outcome>"
    And the Gradle context preserves original Java and build input bytes

    Examples:
      | configuration   | expected                                | availability | outcome                                  |
      | preview-enabled | -Xlint:deprecation,--enable-preview     | complete     | available                                |
      | preview-absent  | none                                    | complete     | available                                |
      | argument-provider | -Xlint:deprecation                     | partial      | java.context.compilerOptionsIncomplete |
      | secret-argument   | redacted                               | complete     | available                                |

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-21-preview-option-survives-a-real-classes-build-and-context-evaluation
  Scenario: Gradle Java 21 preview option survives a real classes build and context evaluation
    Given a Gradle Java project with compiler arguments "preview-enabled-java21"
    And the Gradle classes task succeeds with the Java 21 preview fixture
    When Gradle is synchronized and public Java context requires compiler options
    Then the public Gradle context reports compiler arguments "-Xlint:deprecation,--enable-preview" with availability "complete" and outcome "available"
    And the Gradle context preserves original Java and build input bytes

  @REQ-JAVA-PROJECT-CONTEXT-001#public-gradle-context-refuses-custom-task-actions
  Scenario: Public Gradle context refuses custom task actions
    Given a Gradle Java project with compiler arguments "custom-task-action"
    When Gradle is synchronized and public Java context requires compiler options
    Then the public Gradle context reports compiler arguments "none" with availability "partial" and outcome "java.context.compilerOptionsIncomplete"
    And the Gradle context preserves original Java and build input bytes

  Scenario Outline: Public Java project context distinguishes source provenance or reports missing scope and stale evidence
    Given a Java context fixture with same-path main and test sources and "<variant>"
    When project context is requested for "<type>" in "<scope>" with "<snapshot>" evidence
    Then its public provenance reports "<outcome>" with "<source>" owned by "<owner>"
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | variant             | type              | scope         | snapshot | outcome           | source                                     | owner          |
      | ordinary roots      | pkg.Runner        | selected main | current  | unique provenance | src/main/java/pkg/Runner.java               | app:main       |
      | ordinary roots      | pkg.Peer          | selected main | current  | unique provenance | src/main/java/pkg/Runner.java               | app:main       |
      | ordinary roots      | pkg.Unrelated     | selected test | current  | unique provenance | src/test/java/pkg/Runner.java               | app:test       |
      | materialized root   | pkg.GeneratedPeer | selected main | current  | unique provenance | target/generated-sources/rk/pkg/Runner.java | app:main       |
      | ordinary roots      | unspecified       | unselected    | current  | ambiguous context | none                                       | none           |
      | changed build input | pkg.Peer          | selected main | previous | stale context     | none                                       | none           |

  @REQ-JAVA-PROJECT-CONTEXT-001#source-association-selects-provider-qualified-scope-or-refuses-a-legacy-collision
  Scenario Outline: Source association selects provider-qualified scope or refuses a legacy collision
    Given an explicitly synchronized Maven and Gradle fixture where both providers expose module ":app" source set "main" for "pkg/Runner.java"
    When project context is requested using "<selection>" provider selection
    Then schema "<schema>" reports "<outcome>" with refusal "<refusal>" and selected source provider "<provider>" for module ":app", source set "main" and path "pkg/Runner.java"
    And the query preserves synchronized project inputs without running a Maven lifecycle

    Examples:
      | selection | schema                 | outcome   | refusal                                  | provider           |
      | maven     | java-project-context-v3| available | none                                     | maven-effective-v1 |
      | gradle    | java-project-context-v3| available | none                                     | gradle-effective-v1|
      | legacy    | java-project-context-v2| refused   | java.context.sourceAssociationAmbiguous | none               |

  @REQ-JAVA-PROJECT-CONTEXT-001#effective-maven-outputs-isolate-scoped-classpaths-from-stale-conventional-builds
  Scenario Outline: Effective Maven outputs isolate scoped classpaths from stale conventional builds
    Given a Maven reactor with "<configuration>" effective output directories
    When its "<scope>" context is acquired without running Maven
    Then effective outputs report "<outcome>" and preserve reactor compile runtime scope
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | configuration | scope | outcome   |
      | ordinary      | main  | available |
      | custom        | main  | available |
      | custom        | test  | available |
      | inherited     | main  | available |
      | profile       | test  | available |
      | escaping      | main  | refused   |

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-failsafe-source-sets-are-profile-and-module-bound-with-typed-refusals
  Scenario Outline: Maven Failsafe source sets are profile and module bound with typed refusals
    Given a multi-module Maven reactor with Failsafe profile "<profile>" and ownership "<ownership>"
    When project context is acquired for "<selection>" with "<snapshot>" evidence
    Then Failsafe source-set context reports "<outcome>" with refusal "<refusal>" and module "<module>"
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | profile  | ownership     | selection         | snapshot | outcome  | refusal                                      | module |
      | active   | unique        | app symbol         | current  | available| none                                         | app    |
      | inactive | unique        | project            | current  | available| none                                         | none   |
      | active   | unique        | project            | previous | refused  | java.context.snapshotStale                  | none   |
      | active   | duplicate root| ambiguous evidence | current  | refused  | java.context.sourceRootProvenanceIncomplete | none   |

  @REQ-JAVA-PROJECT-CONTEXT-001#checked-in-maven-failsafe-reactor-context-follows-profile-activation
  Scenario Outline: Checked in Maven Failsafe reactor context follows profile activation
    Given the checked in two module Maven reactor uses Failsafe profile "<profile>"
    When project context is acquired for "<selection>" with "current" evidence
    Then Failsafe source-set context reports "available" with refusal "none" and module "<module>"
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | profile  | selection | module |
      | active   | app symbol| app    |
      | inactive | project   | none   |

  @REQ-JAVA-PROJECT-CONTEXT-001#dependency-source-navigation-is-context-bound-read-only-and-honest
  Scenario Outline: Dependency source navigation is context bound read only and honest
    Given a Java binary dependency with "<attachment>" source attachment evidence
    When its bound dependency source is looked up and read with "<change>" evidence
    Then dependency source navigation reports "<outcome>" with exact classfile provenance
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | attachment | change        | outcome     |
      | attached   | unchanged     | available   |
      | missing    | unchanged     | unavailable |
      | duplicate  | unchanged     | ambiguous   |
      | attached   | source change | stale       |
      | attached   | binary change | refused     |
      | secondary  | unchanged     | available   |

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-source-variants-are-exported-only-by-explicit-offline-sync
  Scenario Outline: Gradle source variants are exported only by explicit offline sync
    Given a Gradle binary dependency with "<attachment>" source variant metadata
    When its bound dependency source is looked up and read with "<change>" evidence
    Then dependency source navigation reports "<outcome>" with exact classfile provenance
    And dependency source queries preserve synchronized project bytes

    Examples:
      | attachment | change        | outcome     |
      | attached   | unchanged     | available   |
      | missing    | unchanged     | unavailable |
      | duplicate  | unchanged     | ambiguous   |
      | attached   | source change | stale       |
      | attached   | binary change | refused     |
      | secondary  | unchanged     | available   |

  @REQ-JAVA-PROJECT-CONTEXT-001#dependency-declaration-ranges-require-exact-attested-jvm-identity
  Scenario Outline: Dependency declaration ranges require exact attested JVM identity
    Given attested dependency sources with a "<declaration>" JVM binding
    When the exact dependency declaration is requested with current source association authority
    Then semantic source navigation reports "<outcome>" without guessed declaration positions
    And dependency source queries preserve synchronized project bytes

    Examples:
      | declaration      | outcome     |
      | overload         | available   |
      | generic erasure  | available   |
      | secondary nested | available   |
      | constructor      | available   |
      | record           | available   |
      | mismatch         | refused     |

  @REQ-JAVA-PROJECT-CONTEXT-001#exact-source-navigation-refuses-ambiguous-or-incompatible-declarations
  Scenario Outline: Exact source navigation refuses ambiguous or incompatible declarations
    Given attested source declaration evidence contains "<evidence>"
    When the exact dependency declaration is requested with current source association authority
    Then exact source declaration evidence reports "<outcome>" without selecting an ambiguous range
    And dependency source queries preserve synchronized project bytes

    Examples:
      | evidence               | outcome     |
      | valid                  | available   |
      | duplicate methods      | ambiguous   |
      | duplicate fields       | ambiguous   |
      | missing explicit member| refused     |
      | static mismatch        | refused     |
      | implicit record member | unavailable |

  @REQ-JAVA-PROJECT-CONTEXT-001#editor-dependency-source-navigation-retains-the-exact-closed-overlay
  Scenario Outline: Editor dependency source navigation retains the exact closed overlay
    Given Java editor drafts redirect a saved binary call through another document
    When dependency source navigation consumes the selected overlay with "<change>" authority
    Then editor dependency navigation reports "<outcome>" with the original saved bytes intact
    And dependency source queries preserve synchronized project bytes

    Examples:
      | change                 | outcome             |
      | current                | second binary       |
      | other document changed | overlay stale       |
      | version only changed   | association stale   |
      | same version changed   | version stale       |
      | document omitted       | overlay stale       |
      | archive changed        | association stale   |

  @REQ-JAVA-PROJECT-CONTEXT-001#selected-sdk-platform-declarations-retain-exact-current-release-source-authority
  Scenario Outline: Selected SDK platform declarations retain exact current release source authority
    Given a Java platform cursor with "<selection>" source authority
    When the selected SDK platform is queried for a guarded declaration
    Then platform source navigation reports "<outcome>" without saving project files
    And dependency source queries preserve synchronized project bytes

    Examples:
      | selection           | outcome            |
      | current method      | exact String method|
      | current type        | exact String type  |
      | current constructor | exact constructor  |
      | current field       | exact Integer field|
      | missing SDK sources | sources unavailable|
      | historical release  | release unavailable|

  @REQ-JAVA-PROJECT-CONTEXT-001#sdk-source-editor-reads-retain-the-selected-cross-document-platform-binding
  Scenario Outline: SDK source editor reads retain the selected cross document platform binding
    Given Java editor drafts redirect the selected SDK call to StringBuffer
    When a platform source read consumes the selected frame with "<change>" authority
    Then platform editor navigation reports "<outcome>" with both saved sources intact
    And dependency source queries preserve synchronized project bytes

    Examples:
      | change                 | outcome             |
      | current                | StringBuffer source |
      | other document changed | overlay stale       |
      | version only changed   | association stale   |
      | same version changed   | version stale       |
      | document omitted       | overlay stale       |
      | saved fallback         | association stale   |

  @REQ-JAVA-PROJECT-CONTEXT-001#platform-source-reads-refuse-sdk-archive-drift-and-inconsistent-zip-evidence
  Scenario Outline: Platform source reads refuse SDK archive drift and inconsistent ZIP evidence
    Given an owned SDK platform source association with "<evidence>" evidence
    When the SDK source bytes or platform metadata change after association
    Then platform source integrity reports "<outcome>" without a guessed declaration
    And dependency source queries preserve synchronized project bytes

    Examples:
      | evidence                  | outcome          |
      | current                   | available        |
      | changed archive           | association stale|
      | removed archive           | association stale|
      | changed SDK metadata      | context stale    |
      | central checksum mismatch | archive unsafe   |
      | central size mismatch     | archive unsafe   |

  @REQ-JAVA-PROJECT-CONTEXT-001#platform-source-metadata-never-associates-an-unrelated-or-unqualified-source-owner
  Scenario Outline: Platform source metadata never associates an unrelated or unqualified source owner
    Given SDK source metadata contains "<source>" owning source evidence
    When fresh platform metadata is requested without an older association guard
    Then SDK metadata association reports "<outcome>" without unrelated source content
    And dependency source queries preserve synchronized project bytes

    Examples:
      | source          | outcome               |
      | current         | available             |
      | wrong package   | identity unavailable  |
      | wrong owner     | identity unavailable  |
      | invalid grammar | identity unavailable  |
      | missing entry   | identity unavailable  |
      | oversized source| source budget exceeded|

  @REQ-JAVA-PROJECT-CONTEXT-001#platform-declaration-type-kinds-follow-jdt-semantics-and-classfile-evidence
  Scenario Outline: Platform declaration type kinds follow JDT semantics and classfile evidence
    Given a current SDK cursor selects a "<kind>" platform declaration
    When exact SDK type kind and classfile identity are required
    Then SDK declaration kind "<kind>" has an exact guarded source range
    And dependency source queries preserve synchronized project bytes

    Examples:
      | kind                    |
      | interface               |
      | nested interface        |
      | annotation              |
      | enum                    |
      | class                   |
      | interface static method |

  @REQ-JAVA-PROJECT-CONTEXT-001#configured-maven-build-helper-add-source-roots-are-advertised-with-exact-owner-or-refused
  Scenario Outline: Configured Maven Build Helper add-source roots are advertised with exact owner or refused
    Given a Maven module declares Build Helper add-source ownership "<ownership>" for a non-generated external root
    And the public Maven project context advertises sibling modules "app" and "unrelated"
    And the Build Helper root is owned only by Maven module "unrelated"
    When provider-qualified project context is requested with "<snapshot>" root evidence
    Then v3 context reports "<outcome>" for the Build Helper root "<root>" with refusal "<refusal>"
    And context acquisition preserves project bytes and executes no build or debugger

  Examples:
      | ownership | snapshot | outcome  | root                       | refusal                                      |
      | unique    | current  | available | unrelated/src/extra/java    | none                                         |
      | unique    | stale    | refused  | none                       | java.context.snapshotStale                  |
      | duplicate | current | refused  | none                       | java.context.sourceRootProvenanceIncomplete |

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-custom-test-source-sets-resolve-types-from-same-module-custom-main-roots
  Scenario: Maven custom test source sets resolve types from same-module custom main roots
    Given a Maven project declares one Build Helper main root and one Build Helper test root
    When Java diagnostics are requested for the Maven custom test source
    Then the custom test source resolves a type from the same-module custom main root
    And context evaluation leaves workspace inputs unchanged without running Maven

  @REQ-JAVA-PROJECT-CONTEXT-001#duplicate-java-type-diagnostics-respect-effective-maven-module-ownership
  Scenario Outline: Duplicate Java type diagnostics respect effective Maven module ownership
    Given a Maven reactor has same Java type declarations in "<ownership>"
    When Java project diagnostics are requested for those declarations
    Then duplicate Java type diagnostics report "<outcome>" for effective module ownership
    And project diagnostics preserve inputs without running Maven

    Examples:
      | ownership                       | outcome          |
      | independent modules             | no duplicate     |
      | one module across source sets   | duplicate symbol |

  @REQ-JAVA-PROJECT-CONTEXT-001#unsupported-and-relocated-maven-artifacts-fail-as-an-unavailable-classpath
  Scenario: Unsupported and relocated Maven artifacts fail as an unavailable classpath
    Given a Maven project declares one relocated jar and one unsupported zip dependency
    When the Java project scanner derives its project model and diagnostics
    Then scanning returns a typed unavailable classpath diagnostic for both declarations
    And no relocated artifact is retained in classpath evidence and project inputs stay unchanged

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-release-27-is-refused-without-lower-source-fallback
  Scenario: Maven release 27 is refused without lower source fallback
    Given a Maven Java project declares release "27" and lower source "21"
    When project context is requested using the declared Maven release
    Then project context refuses with "java.context.sourceLevelUnsupported" without publishing source level "21"
    And context acquisition preserves project bytes and executes no build or debugger

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-java-26-context-binds-source-to-selected-jdk-platform
  Scenario: Maven Java 26 context binds source to selected JDK platform
    Given a Maven Java 26 project and the verified JDK 26 platform
    When public Maven context selects the Java 26 source
    Then Java 26 context binds source and attests exact release options and platform identity
    And context acquisition preserves project bytes and executes no build or debugger

  @implemented-and-validated @REQ-JAVA-PROJECT-CONTEXT-001#maven-java-27-metadata-is-available-with-unqualified-source-analysis
  Scenario: Maven Java 27 metadata is available with unqualified source analysis
    Given a Maven Java 27 project and the verified JDK 27 platform
    When provider-qualified Java context v6 is requested for Maven metadata
    Then the Java 27 Maven context reports metadata with unqualified source analysis
    And Java 27 source selection is refused with "java.context.sourceAnalysisUnqualified"

  @implemented-and-validated @REQ-JAVA-PROJECT-CONTEXT-001#maven-java-27-v7-metadata-remains-available-with-unqualified-source-analysis
  Scenario: Maven Java 27 v7 metadata remains available with unqualified source analysis
    Given a Maven Java 27 project and the verified JDK 27 platform
    When provider-qualified Java context v7 is requested for Maven metadata
    Then the Java 27 Maven v7 context reports metadata with unqualified source analysis
    And Maven Java 27 v7 source selection is refused with "java.context.sourceAnalysisUnqualified"

  @implemented-and-validated @REQ-JAVA-PROJECT-CONTEXT-001#maven-java-27-reactor-metadata-remains-available-with-unqualified-source-analysis
  Scenario: Maven Java 27 reactor metadata remains available with unqualified source analysis
    Given a multi-module Maven Java 27 project and the verified JDK 27 platform
    When provider-qualified Java context v7 is requested for Maven metadata
    Then the Java 27 Maven v7 context reports metadata with unqualified source analysis
    And Maven Java 27 v7 source selection is refused with "java.context.sourceAnalysisUnqualified"

  @implemented-and-validated @REQ-JAVA-PROJECT-CONTEXT-001#maven-java-27-v7-source-owner-is-available-with-unqualified-semantic-analysis
  Scenario: Maven Java 27 v7 source owner is available with unqualified semantic analysis
    Given a Maven Java 27 project and the verified JDK 27 platform
    When provider-qualified Java context v7 selects Maven source owner "fixture/Release27Application.java"
    Then the v7 Java context reports owner "fixture/Release27Application.java" without a qualified JDT binding
    And Maven Java 27 v7 source selection is refused with "java.context.sourceAnalysisUnqualified"

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-java-27-v7-symbol-analysis-uses-the-qualified-jdt-platform-or-refuses
  Scenario: Maven Java 27 v7 symbol analysis uses the qualified JDT platform or refuses
    Given a Maven Java 27 project and the verified JDK 27 platform
    When provider-qualified Java context v7 selects Maven source "fixture/Release27Application.java" and symbol "fixture.Release27Application"
    Then Java 27 Maven v7 source analysis matches the configured JDT qualification and preserves project bytes without running Maven

  @implemented-and-validated @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-27-metadata-is-available-with-unqualified-source-analysis
  Scenario: Gradle Java 27 metadata is available with unqualified source analysis
    Given a Gradle Java 27 project and the verified JDK 27 platform
    When provider-qualified Java context v6 is requested for Gradle metadata
    Then the Java 27 Gradle context reports metadata with unqualified source analysis
    And Gradle Java 27 source selection is refused with "java.context.sourceAnalysisUnqualified"

  @implemented-and-validated @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-27-v7-metadata-remains-available-with-unqualified-source-analysis
  Scenario: Gradle Java 27 v7 metadata remains available with unqualified source analysis
    Given a Gradle Java 27 project and the verified JDK 27 platform
    When provider-qualified Java context v7 is requested for Gradle metadata
    Then the Java 27 Gradle context reports metadata with unqualified source analysis
    And Gradle Java 27 source selection is refused with "java.context.sourceAnalysisUnqualified"

  @implemented-and-validated @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-27-v7-source-owner-is-available-with-unqualified-semantic-analysis
  Scenario: Gradle Java 27 v7 source owner is available with unqualified semantic analysis
    Given a Gradle Java 27 project and the verified JDK 27 platform
    When provider-qualified Java context v7 selects Gradle source owner "fixture/Release27Application.java"
    Then the v7 Gradle Java context reports owner "fixture/Release27Application.java" without a qualified JDT binding
    And Gradle Java 27 source selection is refused with "java.context.sourceAnalysisUnqualified"

  @implemented-and-validated @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-27-definition-query-returns-the-typed-source-analysis-refusal
  Scenario: Gradle Java 27 definition query returns the typed source-analysis refusal
    Given a Gradle Java 27 project and the verified JDK 27 platform
    When provider-qualified Java context v7 selects Gradle source owner "fixture/Release27Application.java"
    And a saved-snapshot Java definition query runs with the selected Gradle Java 27 context
    Then the v7 Gradle Java context reports owner "fixture/Release27Application.java" without a qualified JDT binding
    And the Java definition query refuses with "java.context.sourceAnalysisUnqualified"

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-26-context-binds-source-to-selected-jdk-platform
  Scenario: Gradle Java 26 context binds source to selected JDK platform
    Given a Gradle Java 26 project and the verified JDK 26 platform
    When an explicit offline Gradle sync selects the Java 26 source context
    Then Gradle Java 26 context binds source and attests exact release options and platform identity
    And the Gradle context preserves original Java and build input bytes

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-release-27-is-refused-without-lower-source-fallback
  Scenario: Gradle release 27 is refused without lower source fallback
    Given a Gradle Java project declares release "27" and lower source "21"
    When explicit Gradle sync requests the declared Java release
    Then Gradle sync refuses with "gradle.sync.sourceLevelUnsupported" without publishing source level "21"
    And refused Gradle sync preserves original Java and build input bytes

  @REQ-JAVA-PROJECT-CONTEXT-001#source-only-maven-authoritative-analysis-requires-explicit-platform-selection
  Scenario: Source-only Maven authoritative analysis requires explicit platform selection
    Given a Maven Java project declares only source level "8" for authoritative analysis
    When JDT authoritative analysis is requested without a selected platform
    Then the analysis refuses with "java.platform.explicitJdkRequired" before host platform fallback
    And context acquisition preserves project bytes and executes no build or debugger

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-source-target-context-distinguishes-compiler-facts-from-sdk-selection
  Scenario Outline: Maven source-target context distinguishes compiler facts from SDK selection
    Given a Maven source-target 17 project with "<compiler>" compiler binding
    When public Maven project context is requested with "<sdk>" SDK selection
    Then the Maven compiler authority response is "<status>" with refusal "<refusal>"
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | compiler                | sdk      | status    | refusal                                |
      | implicit                | selected | refused   | java.context.compilerOptionsIncomplete |
      | implicit                | omitted  | refused   | java.context.sdkRequired               |
      | pinned                  | selected | available | none                                   |
      | Maven 3.9.11 runtime    | selected | available | none                                   |
      | Maven 3.9.16 runtime    | selected | available | none                                   |
      | Maven runtime stale    | selected | refused   | java.context.snapshotStale             |

  @REQ-JAVA-PROJECT-CONTEXT-001#project-context-resolves-provider-qualified-source-set-kind-or-refuses-ambiguity
  Scenario Outline: Project context resolves provider-qualified source-set kind or refuses ambiguity
    Given a multi-module Maven reactor with Failsafe profile "<profile>" and ownership "<ownership>"
    When project context is requested by provider-qualified source-set kind "integration-test"
    Then Failsafe kind selection reports "<outcome>" with refusal "<refusal>" and selected module "<module>"
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | profile  | ownership      | outcome   | refusal                                | module |
      | active   | unique         | available | none                                   | app    |
      | active   | duplicate root | refused   | java.context.sourceSetKindAmbiguous   | none   |
      | inactive | unique         | refused   | java.context.sourceSetUnavailable     | none   |

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-failsafe-execution-selector-and-report-metadata-use-a-versioned-context
  Scenario Outline: Maven Failsafe execution selector and report metadata use a versioned context
    Given a multi-module Maven reactor with Failsafe profile "<profile>" and ownership "<ownership>"
    When Failsafe metadata is requested for provider "maven-effective-v1" module "app" using source-set kind "<kind>"
    Then versioned project context reports Failsafe metadata "<availability>" with execution "<execution>" and refusal "<refusal>"
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | profile  | ownership           | kind             | availability | execution | refusal                                                |
      | active   | configured          | integration-test | complete     | run-tests | none                                                   |
      | active   | duplicate Failsafe  | custom           | ambiguous    | none      | java.context.mavenFailsafeExecutionAmbiguous          |
      | active   | unresolved           | integration-test | partial      | run-tests | java.context.mavenFailsafeConfigurationIncomplete     |
      | inactive | unique              | test             | not-declared | none      | none                                                   |

  @implemented-and-validated @REQ-JAVA-PROJECT-CONTEXT-001#maven-test-jvm-settings-are-source-set-bound-and-fail-closed
  Scenario Outline: Maven test-JVM settings are source-set bound and fail closed
    Given a Maven test-JVM fixture "<shape>"
    When provider-qualified Java context v7 is requested for module "app" source-set "<sourceSet>"
    Then test-JVM context for source-set "<sourceSet>" reports "<availability>" mode "<mode>" refusal "<refusal>" and argLine "<argLine>"
    And Maven execution metadata identifies plugin "<plugin>" execution "<execution>" goals "<goals>" phase "<phase>" version "<pluginVersion>" and Failsafe metadata "<failsafeAvailability>" selector "<selector>" reports "<reports>"
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | shape                         | sourceSet        | availability | mode        | refusal                                               | argLine                | plugin   | execution       | goals                   | phase             | pluginVersion | failsafeAvailability | selector        | reports                                                                       |
      | direct surefire               | test             | complete     | forked      | none                                                  | @{argLine} -Xmx1g      | surefire | unit-tests      | test                    | test              | 3.5.2         | not-declared         | none            | none                                                                          |
      | direct failsafe               | integration-test | complete     | in-process  | none                                                  | @{argLine} -Xmx2g      | failsafe | integration-tests| integration-test,verify | integration-test  | 3.5.2         | complete             | none            | none                                                                          |
      | unresolved argLine            | test             | partial      | unknown     | java.context.mavenTestJvmConfigurationIncomplete     | none                   | surefire | unit-tests      | test                    | test              | 3.5.2         | not-declared         | none            | none                                                                          |
      | duplicate runs                | test             | ambiguous    | ambiguous   | java.context.mavenTestJvmExecutionAmbiguous          | none                   | none     | none            | none                    | none              | none          | not-declared         | none            | none                                                                          |
      | implicit surefire             | test             | partial      | unknown     | java.context.mavenTestJvmConfigurationIncomplete     | none                   | surefire | default-test    | test                    | test              | none          | not-declared         | none            | none                                                                          |
      | failsafe JVM settings absent  | integration-test | partial      | unknown     | java.context.mavenTestJvmConfigurationIncomplete     | none                   | failsafe | integration-tests| integration-test,verify | integration-test  | 3.5.2         | complete             | **/*IT.java     | reportsDirectory=target/failsafe-reports,reportFormat=plain                    |

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-failsafe-context-keeps-app-and-library-source-sets-and-declarations-separate
  Scenario: Maven Failsafe context keeps app and library source sets and declarations separate
    Given a Maven reactor with distinct app and library Failsafe test scopes
    When provider context v3 and v4 are requested for each integration-test module
    Then each module retains only its own integration roots, outputs, classpath and Failsafe declarations
    And context acquisition preserves project bytes and executes no build or debugger

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-artifact-coordinates-are-public-per-source-set-and-required-context-refuses-missing-artifacts
  Scenario Outline: Maven artifact coordinates are public per source set and required context refuses missing artifacts
    Given a Java context fixture with same-path main and test sources and "<variant>"
    When public Maven project context requires artifact coordinates
    Then Maven source-set artifact coordinates report "<outcome>" with refusal "<refusal>"
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | variant              | outcome   | refusal                            |
      | complete artifacts    | available | none                               |
      | missing test artifact | refused   | java.context.classpathUnavailable |

  @REQ-JAVA-PROJECT-CONTEXT-001#jpms-context-refuses-descriptors-with-unprojected-directives
  Scenario: JPMS context refuses descriptors with unprojected directives
    Given a Maven Java project declares a module descriptor with "opens, uses, and provides"
    When public Java context is requested with and without required JPMS facts
    Then context marks omitted JPMS directives partial and refuses "java.context.jpmsDescriptorIncomplete"
    And context acquisition preserves project bytes and executes no build or debugger

  @REQ-JAVA-PROJECT-CONTEXT-001#stale-module-descriptor-refuses-jpms-context
  Scenario: Stale module descriptor refuses JPMS context
    Given a Maven Java project declares a module descriptor with "opens, uses, and provides"
    When public JPMS context is requested after its module descriptor changed
    Then public JPMS context refuses stale snapshot "java.context.snapshotStale"
    And context acquisition preserves project bytes and executes no build or debugger

  @REQ-JAVA-PROJECT-CONTEXT-001#stale-jpms-module-path-artifact-refuses-project-context
  Scenario: Stale JPMS module-path artifact refuses project context
    Given a Maven JPMS project declares one module-path artifact
    When the JPMS module-path artifact changes after project open
    Then public JPMS context refuses stale snapshot "java.context.snapshotStale"
    And context acquisition preserves project bytes and executes no build or debugger

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-jpms-context-publishes-module-identities-and-descriptor-facts
  Scenario: Maven JPMS context publishes module identities and descriptor facts
    Given a Maven Java project with a complete descriptor and a modular module-path JAR
    When public Java context is requested with and without required JPMS facts
    Then public JPMS context reports complete module metadata and exact fingerprints
    And context acquisition preserves project bytes and executes no build or debugger

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-jpms-v5-publishes-standard-module-directives
  Scenario: Maven JPMS v5 publishes standard module directives
    Given a Maven JPMS project declares opens uses and provides in a complete descriptor
    When provider-qualified Java project context v5 requires JPMS facts
    Then JPMS context v5 publishes exact opens uses and provides directives
    And context acquisition preserves project bytes and executes no build or debugger

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-jpms-v5-semantic-analysis-binds-uses-and-provides
  Scenario: Maven JPMS v5 semantic analysis binds uses and provides
    Given a Maven JPMS project declares opens uses and provides in a complete descriptor
    When provider-qualified Java project context v5 analyzes symbol "fixture.api.Api"
    Then JPMS v5 semantic analysis returns a bound source with the expected directive projection
    And context acquisition preserves project bytes and executes no build or debugger

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-jpms-default-module-path-includes-resolved-modular-dependencies
  Scenario: Maven JPMS default module path includes resolved modular dependencies
    Given a Maven JPMS project depends on a local named module and uses compiler plugin module-path defaults
    When provider-qualified Maven context v5 requires JPMS facts
    Then the resolved dependency appears on the snapshot-bound module path
    And context acquisition preserves project bytes and executes no build or debugger

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-jpms-semantic-context-respects-module-exports-and-reads
  Scenario: Maven JPMS semantic context respects module exports and reads
    Given a Maven Java project with a complete descriptor and a modular module-path JAR
    When public Maven context selects JPMS source "fixture.api.Api"
    Then Maven JDT analysis resolves exported module types and rejects the hidden package
    And context acquisition preserves project bytes and executes no build or debugger

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-jpms-context-publishes-module-identities-and-descriptor-facts
  Scenario: Gradle JPMS context publishes module identities and descriptor facts
    Given a Gradle Java project with a complete JPMS descriptor and a real module-path JAR
    When Gradle is explicitly synchronized and public context requires JPMS
    Then public Gradle JPMS metadata is complete with exact module identity, fingerprints, and unchanged inputs
    And the Gradle context preserves original Java and build input bytes

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-8-11-1-jpms-inference-exports-declared-modular-dependency
  Scenario: Gradle 8.11.1 JPMS inference exports a declared modular dependency
    Given a Gradle 8.11.1 Java project with inferred module path and a declared modular dependency
    When Gradle is explicitly synchronized and public context observes JPMS inference
    Then Gradle JPMS inference publishes the declared modular dependency on the module path
    And the Gradle context preserves original Java and build input bytes

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-8-11-1-jpms-inference-opt-out-refuses-missing-required-module
  Scenario: Gradle 8.11.1 JPMS inference opt-out refuses a missing required module
    Given a Gradle 8.11.1 Java project with module path inference disabled and a declared modular dependency
    When Gradle is explicitly synchronized and public context requires JPMS
    Then Gradle JPMS inference disabled refuses the missing required module as "java.context.jpmsRequiredModuleUnavailable"
    And the Gradle context preserves original Java and build input bytes

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-8-11-1-jpms-aggregate-inference-keeps-source-set-ownership
  Scenario: Gradle 8.11.1 aggregate JPMS context keeps inferred module facts on their source set
    Given a Gradle 8.11.1 Java project with inferred module path and a declared modular dependency
    When Gradle is explicitly synchronized and public aggregate context observes JPMS inference
    Then public aggregate Gradle context analyzes the inferred JPMS main source set without test-set leakage
    And the Gradle context preserves original Java and build input bytes

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-8-11-1-jpms-aggregate-opt-out-refuses-required-module
  Scenario: Gradle 8.11.1 aggregate JPMS context refuses an opted-out required module
    Given a Gradle 8.11.1 Java project with module path inference disabled and a declared modular dependency
    When Gradle is explicitly synchronized and public aggregate context requires JPMS
    Then Gradle JPMS inference disabled refuses the missing required module as "java.context.jpmsRequiredModuleUnavailable"
    And the Gradle context preserves original Java and build input bytes

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-8-11-1-jpms-context-uses-public-build-tree-identity
  Scenario: Gradle 8.11.1 JPMS context exports through the public build-tree identity API
    Given a Gradle 8.11.1 Java project with a complete JPMS descriptor and a real module-path JAR
    When Gradle is explicitly synchronized and public context requires JPMS
    Then public Gradle JPMS metadata is complete with exact module identity, fingerprints, and unchanged inputs
    And the Gradle context preserves original Java and build input bytes

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-jpms-semantic-context-respects-module-exports-and-reads
  Scenario: Gradle JPMS semantic context respects module exports and reads
    Given a Gradle Java project with a complete JPMS descriptor and a real module-path JAR
    When Gradle is explicitly synchronized and public context selects JPMS source "fixture.api.Api"
    Then JDT JPMS analysis resolves exported module types and rejects the hidden package
    And the Gradle context preserves original Java and build input bytes

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-stale-jpms-module-path-artifact-refuses-project-context
  Scenario: Gradle stale JPMS module-path artifact refuses project context
    Given a Gradle Java project with a complete JPMS descriptor and a real module-path JAR
    When Gradle is explicitly synchronized before the JPMS module-path artifact changes
    Then public Gradle JPMS context refuses stale snapshot "java.context.snapshotStale"
    And the Gradle context preserves original Java and build input bytes

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-annotation-processor-context-exposes-configured-path
  Scenario: Gradle annotation processor context exposes configured path
    Given a Gradle Java project with a real declared annotation processor JAR
    When Gradle is explicitly synchronized and main annotation processor context is required
    Then public Gradle annotation processor metadata reports the configured path and provider
    And the Gradle context preserves original Java and build input bytes

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-reactor-annotation-processor-context-projects-workspace-outputs-and-generated-sources
  Scenario: Maven reactor annotation processor context projects workspace outputs and generated sources
    Given a Maven reactor declares a local annotation processor module and materialized generated source
    When provider-qualified Java context v7 is requested for module "app" source-set "main"
    Then Maven v7 context reports the reactor processor output and generated source root
    And context acquisition preserves project bytes and executes no build or debugger

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-reactor-annotation-processor-context-refreshes-after-workspace-output-materializes
  Scenario: Maven reactor annotation processor context refreshes after workspace output materializes
    Given a Maven reactor declares a local annotation processor module without materialized output
    When provider-qualified Java context v7 is requested for module "app" source-set "main"
    Then Maven v7 context reports the unavailable reactor processor output and no generated source root
    And context acquisition preserves project bytes and executes no build or debugger
    When the Maven processor output and generated source are materialized and context is reopened
    When provider-qualified Java context v7 is requested for module "app" source-set "main"
    Then Maven v7 context reports the reactor processor output and generated source root
    And context acquisition preserves project bytes and executes no build or debugger

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-add-module-refuses-unsafe-or-existing-targets
  Scenario: Maven Add Module refuses unsafe or existing targets
    Given a Maven Add Module fixture with declared parent coordinates and an occupied child
    When daemon Add Module preview is attempted for each unsafe or occupied module name
    Then every Add Module request is refused before a plan is retained and all workspace bytes stay unchanged

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-test-jvm-plugin-defaults-require-a-versioned-origin-and-a-clean-argline-layer
  Scenario Outline: Maven test-JVM plugin defaults require a versioned origin and a clean argLine layer
    Given a Maven test-JVM fixture "<shape>"
    When provider-qualified Java context v7 is requested for module "app" source-set "<sourceSet>"
    Then test-JVM context for source-set "<sourceSet>" reports "<availability>" mode "<mode>" refusal "<refusal>" and argLine "<argLine>"
    And Maven execution metadata identifies plugin "<plugin>" execution "<execution>" goals "<goals>" phase "<phase>" version "<pluginVersion>" and Failsafe metadata "<failsafeAvailability>" selector "<selector>" reports "<reports>"
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | shape                                  | sourceSet        | availability | mode    | refusal                                           | argLine     | plugin   | execution          | goals                   | phase             | pluginVersion | failsafeAvailability | selector         | reports                                                                                |
      | inherited default Surefire lifecycle   | test             | complete     | forked  | none                                              | empty       | surefire | default-test       | test                    | test              | 3.5.4         | not-declared         | none             | none                                                                                   |
      | parent-managed Failsafe execution      | integration-test | complete     | forked  | none                                              | empty       | failsafe | failsafe-app-tests | integration-test,verify | integration-test  | 3.5.4         | complete             | **/*AppIT.java    | reportsDirectory=target/app-failsafe-reports,reportFormat=plain                        |
      | inherited Surefire version unavailable | test             | partial      | unknown | java.context.mavenTestJvmConfigurationIncomplete | none        | surefire | default-test       | test                    | test              | none          | not-declared         | none             | none                                                                                   |
      | JaCoCo prepare-agent contributes argLine| test             | partial      | forked  | java.context.mavenTestJvmConfigurationIncomplete | unavailable | surefire | default-test       | test                    | test              | 3.5.4         | not-declared         | none             | none                                                                                   |
      | Failsafe declaration without descriptor | integration-test | partial      | unknown | java.context.mavenTestJvmConfigurationIncomplete | none        | failsafe | integration-tests  | integration-test,verify | integration-test  | 3.5.2         | complete             | **/*IT.java      | reportsDirectory=target/failsafe-reports,reportFormat=plain                            |
      | Surefire version property matches parent management | test             | partial      | unknown | java.context.mavenTestJvmConfigurationIncomplete | none        | surefire | default-test       | test                    | test              | none          | not-declared         | none             | none                                                                                   |
  @REQ-JAVA-PROJECT-CONTEXT-001#maven-test-jvm-settings-inherit-from-one-literal-workspace-parent
  Scenario Outline: Maven test-JVM settings inherit from one literal workspace parent
    Given a Maven test-JVM fixture "<shape>"
    When provider-qualified Java context v7 is requested for module "app" source-set "<sourceSet>"
    Then test-JVM context for source-set "<sourceSet>" reports "<availability>" mode "<mode>" refusal "<refusal>" and argLine "<argLine>"
    And Maven execution metadata identifies plugin "<plugin>" execution "<execution>" goals "<goals>" phase "<phase>" version "<pluginVersion>" and Failsafe metadata "<failsafeAvailability>" selector "<selector>" reports "<reports>"
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | shape                                  | sourceSet        | availability | mode   | refusal | argLine                 | plugin   | execution          | goals                   | phase            | pluginVersion | failsafeAvailability | selector       | reports                                                                                |
      | one-level parent Surefire JVM settings | test             | complete     | forked | none    | @{argLine} -Xmx2g       | surefire | default-test       | test                    | test             | 3.5.4         | not-declared         | none           | none                                                                                   |
      | one-level parent Failsafe JVM settings  | integration-test | complete     | forked | none    | @{argLine} -Xmx3g       | failsafe | failsafe-app-tests | integration-test,verify | integration-test | 3.5.4         | complete              | **/*AppIT.java | reportsDirectory=target/app-failsafe-reports,reportFormat=plain                        |

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-java-26-v7-navigation-consumes-the-verified-sdk
  Scenario Outline: Maven Java 26 v7 navigation consumes the verified SDK
    Given a saved Maven Java 26 v7 navigation workspace with the verified SDK
    When the IDE requests Java 26 v7 "<query>" with the acquired context
    Then the Java 26 v7 navigation result retains exact source bindings and SDK authority
    And the Java 26 v7 navigation leaves source build and SDK inputs unchanged

    Examples:
      | query      |
      | definition |
      | references |

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-java-26-diagnostics-use-the-selected-platform
  Scenario Outline: Maven Java 26 diagnostics use the selected platform
    Given a saved Maven Java 26 v7 diagnostics workspace with "<condition>"
    When the IDE requests diagnostics using the selected Java 26 SDK
    Then the Java 26 diagnostics match the captured platform API boundary
    And the Java 26 v7 navigation leaves source build and SDK inputs unchanged

    Examples:
      | condition       |
      | valid source    |
      | removed SDK API |

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-java-26-v7-class-rename-retains-managed-authority
  Scenario Outline: Maven Java 26 v7 class rename retains managed authority
    Given a saved Maven Java 26 v7 navigation workspace with the verified SDK
    When the IDE requests a managed Java 26 v7 class rename with "<authority>"
    Then the Java 26 v7 class rename reports "<outcome>" with exact transaction images
    And the Java 26 v7 managed rename preserves input identities and the expected journal

    Examples:
      | authority       | outcome                        |
      | matching        | applied and rolled back        |
      | missing context | java.context.authorityRequired |
      | other SDK       | java.context.contextStale      |
      | source changed  | java.context.snapshotStale     |
      | target exists   | target collision refused       |

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-26-v7-navigation-consumes-the-verified-sdk
  Scenario Outline: Gradle Java 26 v7 navigation consumes the verified SDK
    Given a synchronized Gradle Java 26 v7 navigation workspace with the verified SDK
    When the IDE requests Java 26 v7 "<query>" with the acquired context
    Then the Java 26 v7 navigation result retains exact source bindings and SDK authority
    And the Java 26 v7 navigation leaves source build and SDK inputs unchanged

    Examples:
      | query      |
      | definition |
      | references |

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-26-v7-class-rename-retains-managed-authority
  Scenario Outline: Gradle Java 26 v7 class rename retains managed authority
    Given a synchronized Gradle Java 26 v7 navigation workspace with the verified SDK
    When the IDE requests a managed Java 26 v7 class rename with "<authority>"
    Then the Java 26 v7 class rename reports "<outcome>" with exact transaction images
    And the Java 26 v7 managed rename preserves input identities and the expected journal

    Examples:
      | authority       | outcome                        |
      | matching        | applied and rolled back        |
      | missing context | java.context.authorityRequired |
      | other SDK       | java.context.sdkMismatch       |
      | source changed  | java.context.snapshotStale     |
      | target exists   | target collision refused       |

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-java-26-diagnostics-use-the-selected-platform
  Scenario Outline: Gradle Java 26 diagnostics use the selected platform
    Given a synchronized Gradle Java 26 v7 diagnostics workspace with "<condition>"
    When the IDE requests diagnostics using the selected Java 26 SDK
    Then the Java 26 diagnostics match the captured platform API boundary
    And the Java 26 v7 navigation leaves source build and SDK inputs unchanged

    Examples:
      | condition       |
      | valid source    |
      | removed SDK API |

  @REQ-JAVA-PROJECT-CONTEXT-001#provider-owned-entry-point-discovery-pins-scope-sdk-and-snapshot
  Scenario Outline: Provider-owned entry point discovery pins scope SDK and snapshot
    Given a provider-owned Java entry point fixture requests "<condition>" with its selected SDK
    When the daemon discovers Java entry points for the exact module and source set
    Then Java entry point discovery reports "<outcome>" with its native launch and coverage facts
    And Java entry point discovery preserves all inputs without building or executing application code

    Examples:
      | condition           | outcome                    |
      | classic scope       | declared classic candidates|
      | nested varargs path | one native nested candidate|
      | limit one           | honest truncation          |
      | stale context       | java.context.contextStale  |
      | cancelled           | intelligence.cancelled     |
      | Java25 profile      | java.entryPoints.profileUnqualified |

  @REQ-JAVA-PROJECT-CONTEXT-001#provider-owned-entry-point-query-retains-native-wire-bounds-and-cancellation
  Scenario Outline: Provider-owned entry point query retains native wire bounds and cancellation
    Given a provider-owned Java entry point fixture requests "<condition>" with its selected SDK
    When the daemon discovers Java entry points for the exact module and source set
    Then Java entry point discovery reports "<outcome>" with its native launch and coverage facts
    And Java entry point discovery preserves all inputs without building or executing application code

    Examples:
      | condition                | outcome                              |
      | wire classic scope       | declared classic candidates          |
      | wire RPC ID boundary     | bounded RPC ID response               |
      | wire oversized RPC ID    | invalid RPC ID refused                |
      | wire queued cancellation | cancelled without registration leak  |
      | wire invalid limit       | invalid limit refused                 |
      | wire Java25 profile      | java.entryPoints.profileUnqualified  |

  @REQ-JAVA-PROJECT-CONTEXT-001#provider-owned-entry-point-discovery-uses-selected-sdk-and-effective-target
  Scenario Outline: Provider-owned entry point discovery uses selected SDK and effective target
    Given a provider-owned Java entry point fixture requests "<condition>" with its selected SDK
    When the daemon discovers Java entry points for the exact module and source set
    Then Java entry point discovery reports "<outcome>" with its native launch and coverage facts
    And Java entry point discovery preserves all inputs without building or executing application code

    Examples:
      | condition                       | outcome                              |
      | wire SDK21 release8             | declared classic candidates          |
      | wire SDK21 source-target8       | declared classic candidates          |
      | wire SDK25 release25            | declared classic candidates          |
      | wire SDK25 source-target25      | declared classic candidates          |
      | wire SDK25 release8             | declared classic candidates          |
      | wire incomplete compiler facts | java.context.compilerOptionsIncomplete |

  @REQ-JAVA-PROJECT-CONTEXT-001#provider-owned-entry-point-discovery-selects-the-native-enclosing-type
  Scenario Outline: Provider-owned entry point discovery selects the native enclosing type
    Given a provider-owned Java entry point fixture requests "<condition>" with its selected SDK
    When the daemon discovers Java entry points for the exact module and source set
    Then Java entry point discovery reports "<outcome>" with its native launch and coverage facts
    And Java entry point discovery preserves all inputs without building or executing application code

    Examples:
      | condition                     | outcome                                  |
      | wire cursor primary body      | selected primary main                    |
      | wire cursor secondary body    | selected secondary main                  |
      | wire cursor nested body       | selected nested main                     |
      | wire cursor dormant inner type| selected type has no declared main       |
      | wire cursor outside type      | java.entryPoints.enclosingTypeUnavailable |
      | wire cursor wrong source set  | java.entryPoints.sourceUnavailable        |

  @REQ-JAVA-PROJECT-CONTEXT-001#source-navigation-selects-the-owning-compiler-api-profile
  Scenario Outline: Source navigation selects the owning compiler API profile
    Given a native Java source navigation fixture uses "<condition>"
    When the native daemon resolves source metadata declaration and content under the acquired schema
    Then source navigation reports "<outcome>" with exact binary source and API authority
    And source navigation preserves all captured project SDK and dependency inputs

    Examples:
      | condition                 | outcome                          |
      | SDK21 platform source8    | exact platform type              |
      | SDK25 platform source8    | exact modern platform method     |
      | SDK21 platform release8   | historical source unavailable    |
      | SDK21 mixed API profiles  | isolated native and release APIs |
      | SDK21 dependency source8  | exact dependency method          |
      | SDK25 dependency source8  | exact dependency method          |

  @REQ-JAVA-PROJECT-CONTEXT-001#java-compiler-diagnostics-close-native-cross-source-type-families
  Scenario Outline: Java compiler diagnostics close native cross source type families
    Given a native Java type collision fixture uses "<condition>"
    When the native saved and editor diagnostic routes evaluate the captured Java type declarations
    Then Java type diagnostics report "<outcome>" without merging compilation scopes
    And Java type collision checks preserve all captured source build and SDK inputs

    Examples:
      | condition                     | outcome                      |
      | source plus saved declaration | two exact type errors        |
      | two draft declarations        | two exact type errors        |
      | main and test binary names    | separate compilation scopes  |
      | independent module names      | separate compilation scopes  |
      | distinct enclosing binaries   | separate compilation scopes  |
      | flat Java and JavaScript      | two exact type errors        |

  @REQ-JAVA-PROJECT-CONTEXT-001#java-editor-compiler-diagnostics-retain-selected-sdk-and-closed-frame
  Scenario Outline: Java editor compiler diagnostics retain selected SDK and closed frame
    Given a native Java editor compiler diagnostics fixture uses "<condition>"
    When the daemon diagnoses the immutable Java drafts with their acquired SDK context
    Then Java editor diagnostics report "<outcome>" with exact frame and compiler authority
    And Java editor diagnostics preserve captured project and SDK bytes without execution

    Examples:
      | condition                | outcome                         |
      | SDK25 release8 drafts    | restricted draft API error      |
      | SDK21 source8 drafts     | clean closed drafts             |
      | SDK25 source8 drafts     | clean closed drafts             |
      | obsolete draft version   | java.overlay.versionStale       |
      | substituted SDK context  | java.context.contextStale       |
      | queued cancellation      | intelligence.cancelled          |

  @REQ-JAVA-PROJECT-CONTEXT-001#standalone-java-diagnostics-selects-explicit-sdk-apis-without-runtime-inference
  Scenario Outline: Standalone Java diagnostics selects explicit SDK APIs without runtime inference
    Given a Java compiler API diagnostics fixture uses "<condition>"
    When the native daemon and CLI evaluate Java diagnostics with the selected SDK
    Then both Java diagnostics routes report "<outcome>" for the compiler API boundary
    And Java diagnostics preserve source build and report inputs without execution

    Examples:
      | condition             | outcome                         |
      | SDK21 source-target8  | clean compiler diagnostics      |
      | SDK25 source-target8  | clean compiler diagnostics      |
      | SDK21 release8        | restricted API error            |
      | SDK21 mixed modes     | isolated module API error       |
      | embedded SDK fallback | explicit project SDK required   |
      | custom system         | unqualified API environment     |

  @REQ-JAVA-PROJECT-CONTEXT-001#java-context-isolates-language-providers-without-hiding-workspace-inputs
  Scenario Outline: Java context isolates language providers without hiding workspace inputs
    Given a provider-owned Java entry point fixture requests "<condition>" with its selected SDK
    When the daemon discovers Java entry points for the exact module and source set
    Then Java entry point discovery reports "<outcome>" with its native launch and coverage facts
    And Java entry point discovery preserves all inputs without building or executing application code

    Examples:
      | condition                             | outcome                                  |
      | wire mixed inventory TestNG report    | isolated Java context and candidates     |
      | wire mixed inventory real JavaScript  | isolated Java context and candidates     |
      | wire mixed inventory flat TypeScript  | isolated Java context and candidates     |
      | wire mixed inventory broken tsconfig  | isolated Java context and candidates     |
      | wire mixed inventory invalid Java     | java.context.declaredProjectInvalid      |
      | wire mixed inventory stale report     | java.context.snapshotStale               |

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-source-target-context-attests-native-sdk-apis-separately-from-release
  Scenario Outline: Maven source target context attests native SDK APIs separately from release
    Given a provider-owned Java entry point fixture requests "<condition>" with its selected SDK
    When the daemon discovers Java entry points for the exact module and source set
    Then Java entry point discovery reports "<outcome>" with its native launch and coverage facts
    And Java entry point discovery preserves all inputs without building or executing application code

    Examples:
      | condition                       | outcome                                |
      | wire API SDK21 source-target8    | native SDK API candidates              |
      | wire API SDK25 source-target8    | native SDK API candidates              |
      | wire API SDK21 release8          | java.entryPoints.analysisUnavailable   |
      | wire API SDK21 mixed modes       | isolated SDK API candidates            |
      | wire API missing explicit SDK    | java.context.sdkRequired               |
      | wire API custom system          | java.platform.compilerApiUnqualified   |

  @REQ-JAVA-PROJECT-CONTEXT-001#declared-plain-java-context-owns-roots-classpath-compiler-and-sdk
  Scenario Outline: Declared plain Java context owns roots classpath compiler and SDK
    Given a provider-owned Java entry point fixture requests "<condition>" with its selected SDK
    When the daemon discovers Java entry points for the exact module and source set
    Then Java entry point discovery reports "<outcome>" with its native launch and coverage facts
    And Java entry point discovery preserves all inputs without building or executing application code

    Examples:
      | condition                        | outcome                               |
      | wire plain flat release17        | declared classic candidates           |
      | wire plain conventional release17| declared classic candidates           |
      | wire plain custom classpath25    | declared classic candidates           |
      | wire plain missing compiler facts| java.context.compilerOptionsUnavailable|
      | wire plain stale manifest        | java.context.snapshotStale             |
      | wire plain unsafe source root    | java.context.declaredProjectInvalid    |

  # Authority: docs/arc42/appendix-requirements.adoc#gradle-evaluated-source-root-provenance-2026-11-14.
  @REQ-JAVA-PROJECT-CONTEXT-001#evaluated-gradle-source-roots-retain-provenance-for-editor-diagnostics
  Scenario Outline: Evaluated Gradle source roots retain provenance for editor diagnostics
    Given an evaluated Gradle Java editor fixture selects "<sdk>" with "<profile>" source roots
    When compiler diagnostics are requested for its exact Gradle editor frame
    Then evaluated Gradle roots and diagnostics report "<outcome>" with native owner provenance
    And the Gradle editor query preserves captured inputs without compiling or evaluating the build again

    Examples:
      | sdk   | profile                 | outcome                      |
      | SDK21 | source8                 | captured draft errors        |
      | SDK25 | source8                 | captured draft errors        |
      | SDK21 | release8                | restricted draft API errors  |
      | SDK25 | release8                | restricted draft API errors  |
      | SDK21 | custom and generated    | captured draft errors        |
      | SDK21 | changed build receipt   | intelligence.indexStale       |

  # Authority: complete Maven input evidence under the existing requirement; ARC42 integrates status.
  @REQ-JAVA-PROJECT-CONTEXT-001#maven-context-publishes-complete-aggregator-and-parent-input-evidence
  Scenario Outline: Maven context publishes complete aggregator and parent input evidence
    Given an offline Maven editor context fixture uses "<shape>"
    When the native daemon captures its Maven context before and after an authoritative POM edit
    Then all provider-captured Maven POMs have unique content-bound public build evidence
    And changing a captured Maven POM invalidates its snapshot and context without running project code

    Examples:
      | shape                     |
      | inherited reactor parent  |
      | aggregation-only reactor  |
      | nested aggregator         |
      | workspace relative parent |
      | repository parent         |
      | imported BOM              |

  @REQ-JAVA-PROJECT-CONTEXT-001#sdk27-native-api-context-keeps-older-source-grammar-and-release-authority-distinct
  Scenario Outline: SDK27 native API context keeps older source grammar and release authority distinct
    Given a native SDK27 Java compiler context fixture uses "<mode>"
    When its saved sources and closed editor drafts are diagnosed with the acquired context
    Then SDK27 compiler authority reports "<outcome>" without promoting source27 grammar
    And SDK27 context queries preserve project and SDK bytes and retain stale authority guards

    Examples:
      | mode             | outcome                                |
      | source target8   | native SDK27 compiler diagnostics       |
      | source target17  | native SDK27 compiler diagnostics       |
      | source target25  | native SDK27 compiler diagnostics       |
      | release17        | historical release17 API errors         |
      | custom system    | java.platform.compilerApiUnqualified    |
      | source target27  | java.context.sourceAnalysisUnqualified  |

  @REQ-JAVA-PROJECT-CONTEXT-001#sdk27-api-authority-retains-evaluated-gradle-and-declared-release-ownership
  Scenario Outline: SDK27 API authority retains evaluated Gradle and declared release ownership
    Given a native SDK27 Java compiler context fixture uses "<mode>"
    When its saved sources and closed editor drafts are diagnosed with the acquired context
    Then SDK27 compiler authority reports "<outcome>" without promoting source27 grammar
    And SDK27 context queries preserve project and SDK bytes and retain stale authority guards

    Examples:
      | mode                   | outcome                                |
      | Gradle source target8  | native SDK27 compiler diagnostics       |
      | Gradle source target17 | native SDK27 compiler diagnostics       |
      | Gradle source target25 | native SDK27 compiler diagnostics       |
      | Gradle release17       | historical release17 API errors         |
      | Gradle source target27 | java.context.sourceAnalysisUnqualified  |
      | declared release17     | historical release17 API errors         |

  @REQ-JAVA-PROJECT-CONTEXT-001#native-sdk27-api-profiles-satisfy-versioned-context-schemas-without-source27-admission
  Scenario Outline: Native SDK27 API profiles satisfy versioned context schemas without source27 admission
    Given a native SDK27 context selects public protocol "<version>"
    When the native daemon projects that versioned SDK27 context
    Then the API27 profile satisfies its unchanged Draft 2020-12 context schema and retained bounds
    And SDK27 context queries preserve project and SDK bytes and retain stale authority guards

    Examples:
      | version |
      | v2      |
      | v3      |
      | v4      |
      | v5      |
      | v6      |
      | v7      |

  @REQ-JAVA-PROJECT-CONTEXT-001#legacy-context-source26-is-refused-without-clamping-sdk27-or-versioned-metadata
  Scenario Outline: Legacy context source26 is refused without clamping SDK27 or versioned metadata
    Given a Maven source26 context selects public protocol "<version>"
    When the native daemon projects that versioned SDK27 context
    Then legacy source26 is refused while versioned metadata retains the declared source level
    And SDK27 context queries preserve project and SDK bytes and retain stale authority guards

    Examples:
      | version |
      | v2      |
      | v3      |
      | v4      |
      | v5      |
      | v6      |
      | v7      |

  @REQ-JAVA-PROJECT-CONTEXT-001#native-java25-launch-facts-retain-compiler-owners-methods-and-construction
  Scenario Outline: Native Java25 launch facts retain compiler owners methods and construction
    Given an attested Java25 launch-facts fixture uses "<shape>"
    When the selected SDK compiler analysis captures detached launch facts
    Then native launch facts retain their exact compiler owners methods and construction
    And launch-facts acquisition preserves inputs and existing classic facts

    Examples:
      | shape       |
      | compact     |
      | keyword     |
      | ignorable   |
      | instance    |
      | inherited   |
      | constructor |

  @REQ-JAVA-PROJECT-CONTEXT-001#native-java25-hierarchy-facts-preserve-member-selection-and-declared-shapes
  Scenario Outline: Native Java25 hierarchy facts preserve member selection and declared shapes
    Given an attested Java25 launch-facts fixture uses "<shape>"
    When the selected SDK compiler analysis captures detached launch facts
    Then native hierarchy facts preserve effective main ownership and declared parameter shapes
    And launch-facts acquisition preserves inputs and existing classic facts

    Examples:
      | shape             |
      | overridden        |
      | hidden            |
      | default-interface |
      | private-ancestor  |
      | abstract          |
      | generic           |

  @REQ-JAVA-PROJECT-CONTEXT-001#native-launch-acquisition-refuses-invalid-inputs-without-partial-inventories
  Scenario Outline: Native launch acquisition refuses invalid inputs without partial inventories
    Given an attested Java25 launch-facts fixture uses "<shape>"
    When the selected SDK compiler analysis captures detached launch facts
    Then native launch acquisition returns the exact refusal without a partial inventory
    And launch-facts acquisition preserves inputs and existing classic facts

    Examples:
      | shape             |
      | cancelled         |
      | sdk-mismatch      |
      | stale-sdk         |
      | source-error      |
      | source-release    |
      | missing-classpath |

  @REQ-JAVA-PROJECT-CONTEXT-001#native-compiler-selection-matches-the-selected-java25-launcher
  Scenario Outline: Native compiler selection matches the selected Java25 launcher
    Given an attested Java25 launch-facts fixture uses "<shape>"
    When the selected SDK compiler analysis captures detached launch facts
    Then native selections match an independent Java25 launch control
    And launch-facts acquisition preserves inputs and existing classic facts

    Examples:
      | shape       |
      | compact     |
      | keyword     |
      | ignorable   |
      | instance    |
      | inherited   |
      | constructor |

  @REQ-JAVA-PROJECT-CONTEXT-001#native-construction-and-precedence-selection-agrees-with-java25-launching
  Scenario Outline: Native construction and precedence selection agrees with Java25 launching
    Given an attested Java25 launch-facts fixture uses "<shape>"
    When the selected SDK compiler analysis captures detached launch facts
    Then native selections match an independent Java25 launch control
    And launch-facts acquisition preserves inputs and existing classic facts

    Examples:
      | shape               |
      | private-constructor |
      | abstract-launch     |
      | inner               |
      | record-launch       |
      | static-member       |
      | precedence          |

  @REQ-JAVA-PROJECT-CONTEXT-001#native-main-declarations-retain-source-ranges-and-attested-binary-origins
  Scenario Outline: Native main declarations retain source ranges and attested binary origins
    Given an attested Java25 launch-facts fixture uses "<shape>"
    When the selected SDK compiler analysis captures detached launch facts
    Then native main declarations retain exact source ranges or attested binary origins
    And launch-facts acquisition preserves inputs and existing classic facts

    Examples:
      | shape               |
      | ignorable           |
      | inherited           |
      | default-interface   |
      | classpath-directory |
      | classpath-jar       |
      | platform-origin     |

  @REQ-JAVA-PROJECT-CONTEXT-001#native-entrypoint-context-preserves-provider-closure-and-snapshot-boundaries
  Scenario Outline: Native entrypoint context preserves provider closure and snapshot boundaries
    Given a provider-owned Java25 native entrypoint context uses "<shape>"
    When the native entrypoint application acquires the selected provider scope
    Then the native context preserves exact compile closure or its typed refusal
    And provider scope acquisition preserves source SDK and process state

    Examples:
      | shape          |
      | main           |
      | test           |
      | reactor        |
      | mixed-source   |
      | missing-set    |
      | stale-snapshot |

  @REQ-JAVA-PROJECT-CONTEXT-001#versioned-native-entrypoint-responses-preserve-classic-contract-and-exact-launch-facts
  Scenario Outline: Versioned native entrypoint responses preserve classic contract and exact launch facts
    Given a versioned Java25 entrypoint protocol fixture uses "<shape>"
    When the native versioned entrypoint protocol is queried
    Then the response has exact launch facts and satisfies the closed v2 schema
    And versioned queries preserve classic contract inputs and refuse stale authority

    Examples:
      | shape             |
      | compact           |
      | instance          |
      | inherited         |
      | inherited-classic |
      | none              |
      | constructor       |

  @REQ-JAVA-PROJECT-CONTEXT-001#versioned-native-cursor-selection-retains-innermost-owners-and-utf16-guards
  Scenario Outline: Versioned native cursor selection retains innermost owners and UTF16 guards
    Given a versioned Java25 entrypoint protocol fixture uses "<shape>"
    When the native versioned entrypoint protocol is queried
    Then the response has exact launch facts and satisfies the closed v2 schema
    And versioned queries preserve classic contract inputs and refuse stale authority

    Examples:
      | shape            |
      | inherited-cursor |
      | nested-cursor    |
      | outer-cursor     |
      | no-main-cursor   |
      | bad-cursor       |
      | surrogate-cursor |

  @REQ-JAVA-PROJECT-CONTEXT-001#versioned-native-discovery-enumerates-launchable-types-and-explicit-construction-exclusions
  Scenario Outline: Versioned native discovery enumerates launchable types and explicit construction exclusions
    Given a versioned Java25 entrypoint protocol fixture uses "<shape>"
    When the native versioned entrypoint protocol is queried
    Then the response has exact launch facts and satisfies the closed v2 schema
    And versioned queries preserve classic contract inputs and refuse stale authority

    Examples:
      | shape              |
      | global-inherited   |
      | global-abstract    |
      | global-constructor |
      | file-multiple      |
      | global-static      |
      | global-none        |

  @REQ-JAVA-PROJECT-CONTEXT-001#native-entrypoint-capabilities-separate-versioned-profiles-from-legacy-flags
  Scenario: Native entrypoint capabilities separate versioned profiles from legacy flags
    Given a Java entrypoint capability consumer reads the source daemon catalogue
    When the daemon reports its versioned Java entrypoint capabilities
    Then v2 capabilities distinguish opt-in native profiles from retained classic flags
    And entrypoint capability discovery preserves daemon workspace and request state

  @REQ-JAVA-PROJECT-CONTEXT-001#inherited-profile-keeps-classic-candidates-in-a-modern-java25-source-scope
  Scenario Outline: Inherited profile keeps classic candidates in a modern Java25 source scope
    Given a versioned Java25 entrypoint protocol fixture uses "<shape>"
    When the native versioned entrypoint protocol is queried
    Then the response has exact launch facts and satisfies the closed v2 schema
    And versioned queries preserve classic contract inputs and refuse stale authority

    Examples:
      | shape                          |
      | inherited-mixed-instance       |
      | inherited-mixed-static-array   |
      | inherited-mixed-static-noarg   |
      | inherited-compact-instance     |
      | inherited-compact-static-array |
      | inherited-compact-static-noarg |

  @REQ-JAVA-PROJECT-CONTEXT-001#java25-editor-diagnostics-use-the-selected-compiler-for-implicit-type-names
  Scenario Outline: Java25 editor diagnostics use the selected compiler for implicit type names
    Given a native Java editor compiler diagnostics fixture uses "<condition>"
    When the daemon diagnoses the immutable Java drafts with their acquired SDK context
    Then Java editor diagnostics report "<outcome>" with exact frame and compiler authority
    And Java editor diagnostics preserve captured project and SDK bytes without execution

    Examples:
      | condition                            | outcome                      |
      | SDK25 compact record clean           | clean native diagnostics     |
      | SDK25 compact var clean              | clean native diagnostics     |
      | SDK25 compact yield clean            | clean native diagnostics     |
      | SDK25 compact record missing value   | exact native missing value   |
      | SDK25 explicit record illegal        | exact native illegal name    |
      | SDK25 compact underscore illegal     | exact native illegal name    |

  @REQ-JAVA-PROJECT-CONTEXT-001#java25-editor-diagnostics-retain-native-scope-and-query-authority
  Scenario Outline: Java25 editor diagnostics retain native scope and query authority
    Given a native Java editor compiler diagnostics fixture uses "<condition>"
    When the daemon diagnoses the immutable Java drafts with their acquired SDK context
    Then Java editor diagnostics report "<outcome>" with exact frame and compiler authority
    And Java editor diagnostics preserve captured project and SDK bytes without execution

    Examples:
      | condition                         | outcome                      |
      | SDK25 native source-target25       | clean native diagnostics     |
      | SDK25 native main-test isolation   | clean native diagnostics     |
      | SDK25 native independent modules   | clean native diagnostics     |
      | SDK25 native obsolete draft version| java.overlay.versionStale    |
      | SDK25 native stale context         | java.context.contextStale    |
      | SDK25 native queued cancellation   | intelligence.cancelled       |

  @REQ-JAVA-PROJECT-CONTEXT-001#java26-editor-diagnostics-use-the-selected-compiler-and-platform
  Scenario Outline: Java26 editor diagnostics use the selected compiler and platform
    Given a native Java editor compiler diagnostics fixture uses "<condition>"
    When the daemon diagnoses the immutable Java drafts with their acquired SDK context
    Then Java editor diagnostics report "<outcome>" with exact frame and compiler authority
    And Java editor diagnostics preserve captured project and SDK bytes without execution

    Examples:
      | condition                          | outcome                         |
      | SDK26 compact record clean         | clean native diagnostics        |
      | SDK26 native source-target26       | clean native diagnostics        |
      | SDK26 compact record missing value | exact native missing value      |
      | SDK26 explicit record illegal      | exact native illegal name       |
      | SDK26 native removed platform API  | exact native platform API error |
      | SDK25 native source-target25       | clean native diagnostics        |

  @REQ-JAVA-PROJECT-CONTEXT-001#native-sdk26-api-authority-retains-versioned-schema-boundaries
  Scenario Outline: Native SDK26 API authority retains versioned schema boundaries
    Given a native SDK26 context selects public protocol "<version>"
    When the native daemon projects that versioned SDK26 context
    Then the native API26 context reports "<outcome>" within its unchanged Draft 2020-12 schema
    And SDK26 context queries preserve project and SDK bytes and retain stale authority guards

    Examples:
      | version | outcome            |
      | v2      | legacy API refused |
      | v3      | legacy API refused |
      | v4      | legacy API refused |
      | v5      | legacy API refused |
      | v6      | available          |
      | v7      | available          |

  @REQ-JAVA-PROJECT-CONTEXT-001#selected-jdk-8-attests-the-legacy-platform-for-maven-and-gradle
  Scenario Outline: Selected JDK 8 attests the legacy platform for Maven and Gradle
    Given a Java platform fixture uses "<configuration>"
    When the source project acquires and uses its explicitly selected Java platform
    Then the Java platform proves "<outcome>" and keeps the saved project unchanged

    Examples:
      | configuration       | outcome                      |
      | Maven release 8     | native Java 8 binding        |
      | Maven source target | native Java 8 binding        |
      | Gradle source target | native Java 8 binding        |
      | Maven newer API     | Java 8 API diagnostic        |
      | Gradle newer API    | Java 8 API diagnostic        |
      | Maven release 11    | selected JDK too old         |

  @REQ-JAVA-PROJECT-CONTEXT-001#gradle-processor-options-retain-the-selected-java-api-platform
  Scenario Outline: Gradle processor options retain the selected Java API platform
    Given a Java platform fixture uses "<configuration>"
    When the source project acquires and uses its explicitly selected Java platform
    Then the Gradle compiler option is "<outcome>" without changing saved inputs

    Examples:
      | configuration                 | outcome                 |
      | Gradle processor value         | qualified processor option |
      | Gradle processor valueless     | qualified processor option |
      | Gradle processor malformed     | unqualified option      |
      | Gradle processor boot option   | unqualified option      |
      | Gradle processor unknown       | unqualified option      |
      | Gradle processor redacted      | redacted option refused |

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-test-jvm-build-model-retains-per-execution-selection
  Scenario Outline: Maven test JVM build model retains per execution selection
    Given a Maven test-JVM fixture "<shape>"
    When the Maven build model is captured from declared test settings
    Then Maven build model JVM selection for "<sourceSet>" reports "<kind>" and "<availability>"
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | shape                            | sourceSet        | kind                | availability |
      | Surefire explicit JVM            | test             | explicit-executable | complete     |
      | Surefire JVM wins over toolchain | test             | explicit-executable | complete     |
      | Failsafe JVM wins over toolchain | integration-test | explicit-executable | complete     |
      | inherited build JVM             | test             | build-jvm           | complete     |
      | test toolchain selection         | test             | toolchain           | unavailable  |
      | unresolved test JVM              | test             | unresolved          | unavailable  |

  @REQ-JAVA-PROJECT-CONTEXT-001#maven-test-executions-expose-the-selected-jvm-with-bounded-provenance
  Scenario Outline: Maven test executions expose the selected JVM with bounded provenance
    Given a Maven test-JVM fixture "<shape>"
    When provider-qualified Java context v7 is requested for module "app" source-set "<sourceSet>"
    Then opt-in Maven test JVM selection for "<sourceSet>" reports "<kind>" and "<availability>"
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | shape                        | sourceSet        | kind                | availability |
      | Surefire explicit JVM        | test             | explicit-executable | complete     |
      | Surefire JVM wins over toolchain | test         | explicit-executable | complete     |
      | Failsafe JVM wins over toolchain | integration-test | explicit-executable | complete |
      | inherited build JVM          | test             | build-jvm           | complete     |
      | test toolchain selection     | test             | toolchain           | unavailable  |
      | unresolved test JVM          | test             | unresolved          | unavailable  |

  @REQ-JAVA-PROJECT-CONTEXT-001#active-failsafe-profile-binds-test-jvm-facts-to-its-execution
  Scenario Outline: Active Failsafe profile binds test JVM facts to its execution
    Given a Maven Failsafe JVM profile fixture "<shape>"
    When provider-qualified Java context v7 is requested for module "app" source-set "<sourceSet>"
    Then Maven profile Failsafe JVM facts report "<outcome>"
    And context acquisition preserves project bytes and executes no build or debugger

    Examples:
      | shape                       | sourceSet        | outcome                |
      | explicit profile JVM        | integration-test | explicit executable    |
      | profile descriptor defaults | integration-test | build JVM default      |
      | inactive profile            | test             | absent integration set |
      | competing direct plugin     | integration-test | partial configuration  |
      | unresolved profile argLine  | integration-test | partial configuration  |

  @REQ-JAVA-PROJECT-CONTEXT-001#offline-gradle-plugin-resolution-reports-bounded-process-evidence
  Scenario Outline: Offline Gradle plugin resolution reports bounded process evidence
    Given an explicit Gradle export failure fixture "<fixture>"
    When the failing exporter is synchronized through the public project API
    Then the sync refusal retains "gradle.sync.exportFailed" and bounded cause "<cause>"
    And the export refusal reveals no raw process secrets and publishes no model

    Examples:
      | fixture                         | cause                                               |
      | real offline plugin resolution  | gradle.sync.cause.offlinePluginResolutionFailed     |
      | plugin not found without source | gradle.sync.cause.unknown                           |
      | unrelated plugin repository     | gradle.sync.cause.unknown                           |
