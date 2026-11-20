# language: en
# Authority: C17 evidence binding; ARC42 owns whole-release acceptance.
@operational-requirement @partial
Feature: Bind the v0.8 Linux asset to its actual source and captured native evidence
  Source is captured before the clean build. Finalization binds fresh passing
  reports, retained native C evidence, the pinned Clang inputs, embedded runtime,
  archive checksum and SPDX document. Changed or incomplete authority refuses
  without emitting a qualification manifest. Binding is not publication approval;
  metadata fixtures do not qualify an executable or the full C02-C17 catalogue.

  @REQ-C-RELEASE-EVIDENCE-001
  @REQ-C-RELEASE-EVIDENCE-001#v0-8-release-binding-validates-source-native-reports-and-artifact-identities
  Scenario Outline: V0.8 release binding validates source native reports and artifact identities
    Given a v0.8 release evidence binding fixture has "<case>"
    When the release binding captures source then finalizes the native evidence
    Then the v0.8 release binding reports "<outcome>"
    And release binding preserves inputs and writes only the requested complete receipt

    Examples:
      | case                      | outcome                 |
      | intact captured evidence  | bound evidence          |
      | source changes after start| SOURCE_CHANGED          |
      | toolchain changes         | TOOLCHAIN_CHANGED       |
      | stale native reports      | REPORTS_STALE           |
      | a native test fails       | TEST_GATE_FAILED        |
      | asset checksum mismatch   | ASSET_CHECKSUM_MISMATCH  |

  @REQ-C-RELEASE-EVIDENCE-001
  @REQ-C-RELEASE-EVIDENCE-001#v0-8-release-binding-refuses-incomplete-or-untrusted-evidence
  Scenario Outline: V0.8 release binding refuses incomplete or untrusted evidence
    Given a v0.8 release evidence binding fixture has "<case>"
    When the release binding captures source then finalizes the native evidence
    Then the v0.8 release binding reports "<outcome>"
    And release binding preserves inputs and writes only the requested complete receipt

    Examples:
      | case                         | outcome                           |
      | native evidence disappears   | NATIVE_EVIDENCE_MISSING            |
      | SPDX is incomplete           | SPDX_INVALID                      |
      | fixture profile changes      | FIXTURE_PROFILE_CHANGED           |
      | a C scenario is missing      | NATIVE_SCENARIO_COVERAGE_MISSING    |
      | output already exists        | OUTPUT_EXISTS                     |
      | archive contains traversal   | ASSET_LAYOUT_INVALID              |

  @REQ-C-RELEASE-EVIDENCE-001
  @REQ-C-RELEASE-EVIDENCE-001#release-binding-admits-complete-cucumber-reports-within-the-native-report-budget
  Scenario Outline: Release binding admits complete Cucumber reports within the native report budget
    Given a v0.8 release evidence binding fixture has "<case>"
    When the release binding captures source then finalizes the native evidence
    Then the v0.8 release binding reports "<outcome>"
    And release binding preserves inputs and writes only the requested complete receipt

    Examples:
      | case                                           | outcome          |
      | Cucumber report at the native report byte limit | bound evidence   |
      | Cucumber report above the native report byte limit | INPUT_SIZE_LIMIT |

  @REQ-C-RELEASE-EVIDENCE-001
  @REQ-C-RELEASE-EVIDENCE-001#spdx-schema-and-distribution-file-inventory-are-verified-before-release-binding
  Scenario Outline: SPDX schema and distribution file inventory are verified before release binding
    Given a release SPDX inventory fixture has "<case>"
    When the release tooling assembles SPDX inventory and verifies the captured archive
    Then the SPDX inventory qualification reports "<outcome>"
    And SPDX qualification preserves every input and emits no refused validation receipt

    Examples:
      | case                         | outcome                              |
      | complete upstream metadata   | verified schema and file inventory   |
      | required creation is missing | SPDX_SCHEMA_INVALID                  |
      | an archive file is omitted   | SPDX_INVENTORY_INCOMPLETE             |
      | a file checksum differs      | SPDX_FILE_DIGEST_MISMATCH             |
      | a relationship dangles       | SPDX_REFERENCE_UNAVAILABLE           |
      | an element identity repeats  | SPDX_ID_DUPLICATE                    |

  @REQ-C-RELEASE-EVIDENCE-001
  @REQ-C-RELEASE-EVIDENCE-001#release-binding-refuses-unqualified-spdx-receipts-and-rewritten-component-metadata
  Scenario Outline: Release binding refuses unqualified SPDX receipts and rewritten component metadata
    Given a v0.8 release evidence binding fixture has "<case>"
    When the release binding captures source then finalizes the native evidence
    Then the v0.8 release binding reports "<outcome>"
    And release binding preserves inputs and writes only the requested complete receipt

    Examples:
      | case                                  | outcome                        |
      | SPDX qualification receipt disappears | SPDX_QUALIFICATION_MISSING     |
      | SPDX qualification receipt is stale   | SPDX_QUALIFICATION_STALE       |
      | SPDX schema is forged after validation| SPDX_SCHEMA_INVALID            |
      | SPDX inventory is forged              | SPDX_INVENTORY_INCOMPLETE      |
      | SPDX receipt supplies an executable   | SPDX_QUALIFICATION_MISMATCH     |
      | a captured component is rewritten     | SPDX_COMPONENT_METADATA_CHANGED|

  @REQ-C17-NATIVE-GOLDEN-001#c-golden-tests-require-native-compiler-authority-and-preserve-java-control
  Scenario Outline: C golden tests require native compiler authority and preserve Java control
    Given a native golden qualification request has "<case>"
    When the public golden command runs with its explicit fixture selectors
    Then the native golden command reports "<outcome>"
    And golden qualification preserves source fixtures and selected compiler inputs

    Examples:
      | case                     | outcome                               |
      | C11 prefix               | native C pass                         |
      | C17 prefix               | native C pass                         |
      | missing toolchain        | c.golden.toolchainUnavailable          |
      | missing compiler context | c.golden.compilationContextUnavailable |
      | invalid C compiler input | c.renamePrefix.compilerDiagnostics    |
      | Java control             | Java pass                             |

  @REQ-C17-NATIVE-GOLDEN-001#repository-c11-prefix-golden-proves-native-apply-and-rollback
  Scenario: Repository C11 prefix golden proves native apply and rollback
    Given a native golden qualification request has "repository C11 prefix"
    When the public golden command runs with its explicit fixture selectors
    Then the native golden command reports "native C pass"
    And golden qualification preserves source fixtures and selected compiler inputs

  @REQ-C17-NATIVE-GOLDEN-001#repository-c17-prefix-golden-proves-native-apply-and-rollback
  Scenario: Repository C17 prefix golden proves native apply and rollback
    Given a native golden qualification request has "repository C17 prefix"
    When the public golden command runs with its explicit fixture selectors
    Then the native golden command reports "native C pass"
    And golden qualification preserves source fixtures and selected compiler inputs

  @REQ-C17-NATIVE-GOLDEN-001#historical-c-scalar-and-include-goldens-retain-positive-native-acceptance
  Scenario Outline: Historical C scalar and include goldens retain positive native acceptance
    Given a native golden qualification request has "<case>"
    When the public golden command runs with its explicit fixture selectors
    Then the native golden command reports "native C pass"
    And golden qualification preserves source fixtures and selected compiler inputs

    Examples:
      | case                        |
      | repository extract expression |
      | repository inline function    |
      | repository organize includes  |

  @REQ-C17-NATIVE-GOLDEN-001#historical-component-golden-preserves-native-cmake-relocation-and-rollback
  Scenario: Historical component golden preserves native CMake relocation and rollback
    Given a native golden qualification request has "repository relocate component"
    When the public golden command runs with its explicit fixture selectors
    Then the native golden command reports "native C pass"
    And golden qualification preserves source fixtures and selected compiler inputs

  @REQ-C17-NATIVE-GOLDEN-001#repository-c-symbol-golden-preserves-semantic-references-and-exact-rollback
  Scenario: Repository C symbol golden preserves semantic references and exact rollback
    Given a native golden qualification request has "repository symbol rename"
    When the public golden command runs with its explicit fixture selectors
    Then the native golden command reports "native C pass"
    And golden qualification preserves source fixtures and selected compiler inputs

  @REQ-C17-NATIVE-GOLDEN-001#repository-c-formatting-golden-proves-exact-native-formatting-and-rollback
  Scenario: Repository C formatting golden proves exact native formatting and rollback
    Given a native golden qualification request has "repository formatting"
    When the public golden command runs with its explicit fixture selectors
    Then the native golden command reports "native C pass"
    And golden qualification preserves source fixtures and selected compiler inputs

  @REQ-C17-NATIVE-GOLDEN-001#repository-c-parameter-rename-golden-preserves-its-declaration-family
  Scenario: Repository C parameter rename golden preserves its declaration family
    Given a native golden qualification request has "repository parameter rename"
    When the public golden command runs with its explicit fixture selectors
    Then the native golden command reports "native C pass"
    And golden qualification preserves source fixtures and selected compiler inputs

  @REQ-C17-NATIVE-GOLDEN-001#repository-c-parameter-insertion-golden-supplies-an-explicit-caller-expression
  Scenario: Repository C parameter insertion golden supplies an explicit caller expression
    Given a native golden qualification request has "repository parameter insertion"
    When the public golden command runs with its explicit fixture selectors
    Then the native golden command reports "native C pass"
    And golden qualification preserves source fixtures and selected compiler inputs

  @REQ-C17-NATIVE-GOLDEN-001#repository-c-parameter-reorder-golden-preserves-declaration-and-caller-mappings
  Scenario: Repository C parameter reorder golden preserves declaration and caller mappings
    Given a native golden qualification request has "repository parameter reorder"
    When the public golden command runs with its explicit fixture selectors
    Then the native golden command reports "native C pass"
    And golden qualification preserves source fixtures and selected compiler inputs

  @REQ-C17-NATIVE-GOLDEN-001#repository-c-inline-local-golden-replaces-one-compiler-bound-literal-read
  Scenario: Repository C inline local golden replaces one compiler bound literal read
    Given a native golden qualification request has "repository inline local"
    When the public golden command runs with its explicit fixture selectors
    Then the native golden command reports "native C pass"
    And golden qualification preserves source fixtures and selected compiler inputs

  @REQ-C17-NATIVE-GOLDEN-001#repository-c-safe-delete-golden-removes-an-unused-file-static-function
  Scenario: Repository C safe delete golden removes an unused file static function
    Given a native golden qualification request has "repository safe delete"
    When the public golden command runs with its explicit fixture selectors
    Then the native golden command reports "native C pass"
    And golden qualification preserves source fixtures and selected compiler inputs

  @REQ-C17-NATIVE-GOLDEN-001#repository-c-statement-extraction-golden-preserves-helper-inputs-and-output
  Scenario: Repository C statement extraction golden preserves helper inputs and output
    Given a native golden qualification request has "repository statement extraction"
    When the public golden command runs with its explicit fixture selectors
    Then the native golden command reports "native C pass"
    And golden qualification preserves source fixtures and selected compiler inputs

  @REQ-C17-PINNED-SBOM-PRODUCER-001#release-sbom-generation-binds-the-pinned-producer-to-the-captured-asset
  Scenario Outline: Release SBOM generation binds the pinned producer to the captured asset
    Given a v0.8 release evidence binding fixture has "<case>"
    When the release binding captures source then finalizes the native evidence
    Then the v0.8 release binding reports "<outcome>"
    And release binding preserves inputs and writes only the requested complete receipt

    Examples:
      | case                                          | outcome                              |
      | pinned producer and captured payload          | bound evidence                       |
      | producer executable changes after capture     | SBOM_PRODUCER_CHANGED                |
      | producer execution receipt disappears         | SBOM_PRODUCER_RECEIPT_MISSING         |
      | producer execution receipt is stale           | SBOM_PRODUCER_RECEIPT_STALE           |
      | producer receipt rewrites raw SBOM identity    | SBOM_PRODUCER_RECEIPT_MISMATCH        |
      | producer selection comes only from a receipt   | SBOM_PRODUCER_SELECTOR_REQUIRED       |


  @REQ-C17-RUNTIME-COMPONENTS-001#runtime-component-manifest-records-resolved-identities-and-refuses-unsafe-inputs
  Scenario Outline: Runtime component manifest records resolved identities and refuses unsafe inputs
    Given a source build runtime component manifest request has "<case>"
    When Gradle resolves and records the selected distribution components
    Then the runtime component manifest reports "<outcome>"
    And component observation preserves source and binds every packaged JAR byte

    Examples:
      | case                                   | outcome                         |
      | actual current resolved runtime        | verified component identities   |
      | repeated capture in a fresh destination | byte-identical component record |
      | untyped file runtime dependency        | COMPONENT_IDENTITY_UNAVAILABLE  |
      | colliding artifact destination names   | COMPONENT_PATH_COLLISION        |
      | symbolic output destination            | COMPONENT_OUTPUT_UNAVAILABLE    |
      | preexisting output destination         | COMPONENT_OUTPUT_EXISTS         |


  @REQ-C17-SPDX-COMPONENT-IDENTITY-001#spdx-reconciles-resolved-runtime-coordinates-with-the-archive
  Scenario Outline: SPDX reconciles resolved runtime coordinates with the archive
    Given a release SPDX coordinate fixture has "<case>"
    When SPDX qualification reconciles the supplied runtime resolution
    Then the SPDX coordinate qualification reports "<outcome>"
    And the coordinate fixture preserves its archive and original metadata

    Examples:
      | case                           | outcome                              |
      | resolved runtime coordinates   | verified runtime coordinates         |
      | component missing from manifest| SPDX_RUNTIME_COMPONENTS_INCOMPLETE   |
      | component checksum mismatch    | SPDX_RUNTIME_COMPONENT_DIGEST_MISMATCH |
      | ambiguous upstream package     | SPDX_RUNTIME_COMPONENT_AMBIGUOUS    |
      | normalized metadata changed    | SPDX_COMPONENT_METADATA_CHANGED     |
      | malformed coordinates          | SPDX_RUNTIME_COMPONENTS_INVALID      |


  @REQ-C17-SPDX-SOURCE-BINDING-001#release-binding-replays-the-source-owned-gradle-component-manifest
  Scenario Outline: Release binding replays the source-owned Gradle component manifest
    Given a v0.8 release evidence binding fixture has "<case>"
    When the release binding captures source then finalizes the native evidence
    Then the v0.8 release binding reports "<outcome>"
    And release binding preserves inputs and writes only the requested complete receipt

    Examples:
      | case                             | outcome                          |
      | resolved component release input | bound evidence                   |
      | resolved component forged GAV    | RUNTIME_COMPONENTS_REPLAY_MISMATCH|
      | resolved component absent input  | RUNTIME_COMPONENTS_REQUIRED      |
      | resolved component stale input   | RUNTIME_COMPONENTS_STALE         |
      | resolved component source drift  | SOURCE_CHANGED                   |
      | resolved component archive drift | SPDX_RUNTIME_COMPONENT_DIGEST_MISMATCH |


  @REQ-C17-DOWNLOADED-RELEASE-001#verifies-downloaded-linux-assets-before-executing-the-runtime
  Scenario Outline: Verifies downloaded Linux assets before executing the runtime
    Given downloaded release evidence has "<condition>"
    When the independent Linux asset verifier checks the downloaded set
    Then the downloaded release result is "<outcome>"
    And runtime execution and the review receipt match that outcome

    Examples:
      | condition                              | outcome                           |
      | matched source and signed asset set     | verified asset set                |
      | wrong archive checksum                  | DOWNLOAD_CHECKSUM_MISMATCH        |
      | release evidence binds another revision | DOWNLOAD_SOURCE_MISMATCH          |
      | provenance verification refused         | DOWNLOAD_ATTESTATION_REFUSED      |
      | different signed SPDX predicate         | DOWNLOAD_SPDX_ATTESTATION_MISMATCH|
      | runtime smoke refused                   | DOWNLOAD_RUNTIME_EXECUTION_FAILED |

  @REQ-C17-MANAGED-CRASH-001#packaged-c-relocation-recovers-an-interrupted-apply
  Scenario Outline: Packaged C relocation recovers an interrupted apply
    Given the packaged C relocation recovery fixture uses "<case>"
    When the packaged daemon is interrupted after replacing a C component image
    Then the published recovery command reports "<outcome>" and restores the original snapshot

    Examples:
      | case         | outcome                            |
      | c17          | exact recovery                     |
      | c11-conflict | refusal preserves the foreign file |
