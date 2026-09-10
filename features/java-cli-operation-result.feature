# language: en
Ability: Obtain a strict source-built Java module-rename preview result
  Scripts, IDEs, and local agents need a deterministic machine-readable preview result without treating correlation data as refactoring authority.
  The CLI distribution surface projects the existing Java Maven module-rename plan into one transient closed result document and leaves the human preview unchanged.
  This qualification is limited to source-built RefactorKit 0.7.0 on local Linux, JDK 21, and the current CPU architecture.
  V070-RELEASE-VERSION-TRANSITION-001 changes only version metadata, not the preview oracle or release-parity exclusions.

  @REQ-JAVA-CLI-RESULT-001 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario: A qualified module rename returns the exact preview envelope and canonical complete-edit identity
    Given the source-built RefactorKit 0.7.0 CLI entrypoint runs locally on Linux with JDK 21 and the current CPU architecture
    And the permanent fixture "testdata/acceptance/java-maven-move-class-authority-20-modules" is an immutable offline reactor with one root aggregator and exactly 20 direct non-aggregator JAR children
    And the harness creates pairwise-distinct machine-result and human-compatibility workspaces as fresh no-follow disposable byte copies of that permanent fixture, refusing every symbolic link and preserving every relative regular-file byte and path kind
    And each copy has "catalog-model" as one exact direct child, has no "catalog-domain" path, and contains the qualified source at "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"
    And every scan, plan, invocation, and observation is confined to its declared disposable root, while the permanent fixture remains read-only
    And the existing installation under "~/.local/share/refactorkit" is guarded by fail-closed invocation, lookup, and no-follow mutation tripwires and is neither selected nor used as acceptance evidence
    And before any CLI invocation the harness independently retains the machine-result workspace's exact no-follow non-engine image and its public-scanner snapshot SHA-256 as "S0", without reading a CLI result, projected plan, or internal PlanId
    And the complete normalized WorkspaceEdit oracle is declared independently from immutable fixture bytes with exactly these entries in application order:
      | order | kind   | canonical path or source path                                          | destination path                                                        | text-edit count | start line | start character | end line | end character | exact replacement UTF-8 bytes                                             |
      | 1     | modify | catalog-model/pom.xml                                                  | null                                                                    | 1               | 9          | 14              | 9        | 27            | 63 61 74 61 6c 6f 67 2d 64 6f 6d 61 69 6e ("catalog-domain", 14 bytes) |
      | 2     | modify | pom.xml                                                                | null                                                                    | 1               | 74         | 12              | 74       | 25            | 63 61 74 61 6c 6f 67 2d 64 6f 6d 61 69 6e ("catalog-domain", 14 bytes) |
      | 3     | modify | catalog-pricing/pom.xml                                                | null                                                                    | 1               | 13         | 18              | 13       | 31            | 63 61 74 61 6c 6f 67 2d 64 6f 6d 61 69 6e ("catalog-domain", 14 bytes) |
      | 4     | move   | catalog-model/pom.xml                                                  | catalog-domain/pom.xml                                                  | 0               | null       | null            | null     | null          | null                                                                       |
      | 5     | move   | catalog-model/src/main/java/com/acme/catalog/legacy/Product.java      | catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java      | 0               | null       | null            | null     | null          | null                                                                       |
    And before interaction the harness independently computes "EXPECTED_PLAN_SHA256" from "S0" and that complete literal edit, without calling result-projection or plan-digest production code, by framing every string as its unsigned 32-bit big-endian UTF-8 byte length followed by its exact UTF-8 bytes
    And that digest input frames, in order, "refactorkit.cli-result/plan-sha256/v1", "java.renameMavenModule", and the independently retained "S0" snapshot SHA-256, then appends byte 0x01 and unsigned 32-bit big-endian edit count 5
    And for each modify in the declared order the digest input appends byte 0x01, its framed canonical path, unsigned 32-bit big-endian text-edit count 1, its four declared zero-based coordinates as unsigned 32-bit big-endian values, and its framed exact 14 replacement bytes, with text edits ordered by start line, start character, end line, and end character
    And for each move in the declared order the digest input appends byte 0x02, its framed canonical source path, and its framed canonical destination path
    And no request ID, internal PlanId, summary, warning, confidence, risk, diagnostic, authority object, timestamp, projection limit, or truncation state contributes to "EXPECTED_PLAN_SHA256"
    And fail-closed observations cover workspace-lock acquisition, ".refactorkit" creation, WAL access, transaction creation, managed file edits, rollback execution or claims, installed-runtime invocation or lookup, and installation mutation
    And the human-mode stdout bytes, stderr bytes, and exit status for the exact qualified request are pinned from source-built base commit "8b361e3a4ac9d83ef2b9b4e797a7a4ea569d42dc" before result-protocol production changes and without invoking the installed runtime
    When the public source-built parser invokes these exact requests, substituting only each normalized disposable root for its named placeholder and never adding "--apply":
      | order | invocation             | exact argv                                                                                                                                                                                                                                                   |
      | 1     | fixed correlation A    | refactorkit java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain --new-artifact-id catalog-domain --root <machine-result-root> --json --request-id result-preview-001                                                           |
      | 2     | fixed correlation B    | refactorkit java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain --new-artifact-id catalog-domain --root <machine-result-root> --json --request-id result-preview-001                                                           |
      | 3     | generated correlation  | refactorkit java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain --new-artifact-id catalog-domain --root <machine-result-root> --json                                                                                           |
      | 4     | human compatibility    | refactorkit java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain --new-artifact-id catalog-domain --root <human-compatibility-root>                                                                                            |
    Then each of the three JSON invocations exits 0 and has empty stderr
    And each JSON stdout is exactly the compact UTF-8 encoding of this single object after substituting its independently expected request ID, "EXPECTED_PLAN_SHA256", and retained "S0" snapshot SHA-256, followed by one LF byte and no other byte:
      """json
      {"schemaVersion":1,"command":"java.renameMavenModule","requestId":"<REQUEST_ID>","outcome":"preview","plan":{"sha256":"<EXPECTED_PLAN_SHA256>","snapshotSha256":"<S0_SNAPSHOT_SHA256>","requiresApproval":true,"changeCount":5,"changes":[{"kind":"modify","path":"catalog-model/pom.xml","previousPath":null},{"kind":"modify","path":"pom.xml","previousPath":null},{"kind":"modify","path":"catalog-pricing/pom.xml","previousPath":null},{"kind":"move","path":"catalog-domain/pom.xml","previousPath":"catalog-model/pom.xml"},{"kind":"move","path":"catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java","previousPath":"catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"}]},"transaction":null,"diagnostics":[],"truncated":false}
      """
    And the top-level object has exactly these fields in producer order, with no "schema", "error", duplicate, or additional field:
      | position | field         | exact JSON type | exact value for this qualified preview                  |
      | 1        | schemaVersion | integer         | 1                                                       |
      | 2        | command       | string          | java.renameMavenModule                                  |
      | 3        | requestId     | string          | the independently expected invocation correlation value |
      | 4        | outcome       | string          | preview                                                 |
      | 5        | plan          | object          | the exact canonical plan projection                     |
      | 6        | transaction   | null            | null                                                    |
      | 7        | diagnostics   | array           | empty                                                   |
      | 8        | truncated     | boolean         | false                                                   |
    And the plan object has exactly these fields in producer order and exact values derived from the independent oracles rather than from an output or internal PlanId:
      | position | field            | exact JSON type | exact value                         |
      | 1        | sha256          | string          | EXPECTED_PLAN_SHA256 as 64 lowercase hexadecimal characters |
      | 2        | snapshotSha256  | string          | the independently retained S0 snapshot SHA-256               |
      | 3        | requiresApproval | boolean        | true                                                         |
      | 4        | changeCount     | integer         | 5                                                            |
      | 5        | changes         | array           | the complete five-change projection below                    |
    And the changes array preserves the complete normalized WorkspaceEdit application order and contains exactly these objects, each with fields "kind", "path", and "previousPath" in that order:
      | order | kind   | path                                                                       | previousPath                                                               |
      | 1     | modify | catalog-model/pom.xml                                                      | null                                                                       |
      | 2     | modify | pom.xml                                                                    | null                                                                       |
      | 3     | modify | catalog-pricing/pom.xml                                                    | null                                                                       |
      | 4     | move   | catalog-domain/pom.xml                                                     | catalog-model/pom.xml                                                      |
      | 5     | move   | catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java         | catalog-model/src/main/java/com/acme/catalog/legacy/Product.java          |
    And every modify uses its canonical modified path with null "previousPath", while every move uses its destination as "path" and its source as "previousPath"
    And both fixed-correlation results preserve "result-preview-001" byte-for-byte, that value matches `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`, and their complete stdout byte sequences are identical
    And the generated result has one newly generated request ID matching `request-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}` while every other expected result value remains derived from the same "S0" and complete edit oracles
    And no caller-supplied or generated request ID is accepted as snapshot authority, plan identity, approval, apply authority, transaction identity, idempotency, authentication, or semantic evidence
    And every JSON stream contains no ANSI sequence, logger output, progress text, human prose, or stack trace
    And the human-compatibility invocation has stdout, stderr, and exit status byte-for-byte equal to its pinned pre-slice source-built baseline
    And all permitted preview activity is read-only scanning and planning of the disposable workspace, so this scenario does not claim that no workspace scan occurs
    And both disposable workspaces retain their exact pre-invocation non-engine bytes and path kinds, ".refactorkit" remains absent, every fail-closed mutation tripwire remains untriggered, no lock, WAL, transaction, managed edit, or rollback operation or claim exists, and the installed runtime was neither invoked nor mutated
    And this scenario makes no implementation, validation, promotion, or support claim for:
      | explicitly excluded scope                                                                                     |
      | REQ-JAVA-CLI-RESULT-002 applied transaction correlation                                                       |
      | REQ-JAVA-CLI-RESULT-003 application refusal projection                                                        |
      | REQ-JAVA-CLI-RESULT-004 usage or expected operational error projection                                        |
      | REQ-JAVA-CLI-RESULT-005 unexpected internal error projection                                                   |
      | REQ-JAVA-CLI-RESULT-006 schema closure, limits, redaction, or truncation behavior                              |
      | invalid request IDs, duplicate arguments, missing values, apply, refusal, or any error outcome                |
      | java create-module, java move-across-maven-modules, other Java commands, or any other language                |
      | installDist parity, installed execution or promotion, package, installer, signed, or native behavior          |
      | Windows, macOS, another CPU architecture, another JDK, or another host                                         |
      | a fresh SURFACE-002 authority, apply, refusal, rollback, crash, recovery, or transaction qualification        |
      | release-grade portability, release promotion, broad support-matrix status, or changes to prior promoted rows |
