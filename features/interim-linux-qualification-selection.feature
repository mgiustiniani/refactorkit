# language: en
# Authority: C17 prospective host selection; ARC42 owns integrated acceptance.
# GitHub job/matrix semantics: https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-jobs
@functional-requirement @partial
Feature: Select only the admitted host during interim qualification
  Interim development and 0.x publication select Linux x86-64. An unsupported
  dispatch cannot start dependent qualification jobs. Native verification and
  historical release guards retain their original checks and evidence.

  @REQ-C-INTERIM-HOST-001 @interim-linux-qualification-selection
  @REQ-C-INTERIM-HOST-001#interim-workflow-selection-preserves-qualification-on-the-admitted-host
  Scenario Outline: Interim workflow selection preserves qualification on the admitted host
    Given an interim qualification request uses "<event>" and "<target>"
    When the repository workflow selects its qualification jobs
    Then the workflow host disposition is "<disposition>"
    And interim selection preserves native verification and historical release guards

    Examples:
      | event             | target       | disposition      |
      | push              | main         | linux only       |
      | pull_request      | develop      | linux only       |
      | workflow_dispatch | linux-x86_64 | linux only       |
      | workflow_dispatch | windows-x86_64 | refused        |
      | release           | v0.8.0       | linux only       |
      | release           | v1.0.0       | stable matrix    |

  @REQ-C-INTERIM-HOST-001
  @REQ-C-INTERIM-HOST-001#native-kotlin-qualification-binds-the-checked-out-package-version
  Scenario Outline: Native Kotlin qualification binds the checked out package version
    Given an interim qualification request uses "push" and "main"
    When the repository workflow selects its qualification jobs
    Then the "<job>" evidence binds the checked out package version
    And interim selection preserves native verification and historical release guards

    Examples:
      | job                              |
      | k1-k2-shared-foundations-native  |
      | k5-move-next-native               |
      | k5-bounded-completion-native     |

  @REQ-C-INTERIM-HOST-001
  @REQ-C-INTERIM-HOST-001#clean-native-builds-prepare-the-cli-installation-tripwire-before-regression
  Scenario Outline: Clean native builds prepare the CLI installation tripwire before regression
    Given an interim qualification request uses "<event>" and "<target>"
    When the repository workflow selects its qualification jobs
    Then the clean "<job>" build prepares the CLI installation tripwire before regression
    And interim selection preserves native verification and historical release guards

    Examples:
      | event   | target | job                    |
      | push    | main   | cross-platform-runtime |
      | release | v0.8.0 | build-runtime          |

  @REQ-C-INTERIM-HOST-001
  @REQ-C-INTERIM-HOST-001#native-maven-move-class-receipts-bind-the-selected-feature-bytes
  Scenario Outline: Native Maven move-class receipts bind the selected feature bytes
    Given an interim qualification request uses "push" and "main"
    When the repository workflow selects its qualification jobs
    Then the native Maven move-class receipt passes the "<control>" control
    And interim selection preserves native verification and historical release guards

    Examples:
      | control                |
      | exact tagged feature    |
      | historical feature      |
      | wrong matrix identity   |
      | changed scenario bytes  |

  @REQ-C-INTERIM-HOST-001#ci-and-the-v0-8-release-test-c-against-the-attested-clang-toolchain
  @ci-exact-clang-toolchain
  Scenario: CI and the v0.8 release test C against the attested Clang toolchain
    Given an interim qualification request uses "push" and "main"
    When the C qualification toolchain is prepared for workflow test tasks
    Then CI verifies the official LLVM 22.1.8 Linux archive and its tool identities
    And every C unit and CLI test task receives that toolchain explicitly
    And default C qualification retains native evidence in distinct task report directories
    And v0.8 release tests use the same qualified toolchain without changing v0.7 gates

  @REQ-C-INTERIM-HOST-001#source-regression-discovers-and-selects-every-admitted-c-story
  Scenario: Source regression discovers and selects every admitted C Story
    Given an interim qualification request uses "push" and "main"
    When source regression inventories the admitted C Stories
    Then each admitted C feature has exactly one source runner
    And the default source filter selects every admitted C scenario

  @REQ-C-INTERIM-HOST-001
  @REQ-C-INTERIM-HOST-001#source-test-tasks-provision-native-fixture-inputs
  Scenario Outline: Source test tasks provision native fixture inputs
    Given source qualification fixture inputs use "<profile>"
    When the actual Gradle CLI and LSP test task models are inspected
    Then both source test tasks retain the selected runtime and evidence ownership
    And source test scheduling preserves runtime prerequisites and the Gradle executable

    Examples:
      | profile         |
      | owned defaults  |
      | caller override |

  @REQ-C-INTERIM-HOST-001
  @REQ-C-INTERIM-HOST-001#native-fixture-bootstrap-exports-verified-inputs-or-refuses-incomplete-authority
  Scenario Outline: Native fixture bootstrap exports verified inputs or refuses incomplete authority
    Given a native fixture bootstrap request has "<case>"
    When the native fixture bootstrap validates its explicitly selected inputs
    Then native fixture bootstrap reports "<outcome>" before qualification starts
    And native fixture bootstrap preserves selected artifacts and writes only approved outputs

    Examples:
      | case                        | outcome                  |
      | complete verified inputs    | complete profile emitted |
      | SDK25 supplied as SDK26     | profile refused          |
      | complete verified LTS inputs | complete profile emitted |
      | SDK8 supplied as SDK11      | profile refused          |

  @REQ-C-INTERIM-HOST-001
  @REQ-C-INTERIM-HOST-001#release-source-inventories-exclude-local-qualification-artifacts-without-deleting-them
  Scenario: Release source inventories exclude local qualification artifacts without deleting them
    Given a release source inventory contains authored files and local qualification artifacts
    When Git inventories that fixture with the repository ignore policy
    Then only authored source and pinned qualification manifests enter the release source inventory
    And release source filtering preserves every local artifact byte-for-byte

  @REQ-C-RELEASE-EVIDENCE-001
  @REQ-C-RELEASE-EVIDENCE-001#v0-8-publication-remains-draft-pending-independent-download-and-review
  Scenario Outline: V0.8 publication remains draft pending independent download and review
    Given an interim qualification request uses "release" and "<tag>"
    When the repository workflow evaluates release publication visibility
    Then the release publication visibility is "<visibility>"
    And interim selection preserves native verification and historical release guards

    Examples:
      | tag     | visibility             |
      | v0.8.0  | draft                  |
      | v0.8.1  | draft                  |
      | v0.8.0-rc.1 | draft              |
      | v0.7.0  | draft                  |
      | v0.9.0  | existing public policy |
      | v1.0.0  | existing public policy |
