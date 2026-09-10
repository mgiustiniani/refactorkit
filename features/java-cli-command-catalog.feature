# language: en
Ability: Discover the exact public Java CLI command catalogue without entering a workspace lifecycle
  Scripts, IDEs, and local agents need one truthful machine-readable inventory of the Java command routes they may select.
  The catalogue is a transient CLI distribution-surface document, distinct from language capabilities, semantic operation results, approval, and write authority.
  This qualification is limited to the exact source-built version in each scenario on local Linux, JDK 21, and the current CPU architecture.
  The version-only transition is governed by V070-RELEASE-VERSION-TRANSITION-001; it does not qualify package or release parity.

  @REQ-JAVA-CLI-CATALOG-001 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario: Source-built discovery reports the closed deterministic Java command catalogue and nothing else
    Given the source-built RefactorKit 0.7.0 CLI entrypoint runs locally on Linux with JDK 21 and the current CPU architecture
    And the installed RefactorKit executable is guarded by an invocation tripwire and is neither selected nor invoked
    And the v1 command-catalogue contract admits exactly these fields in this order:
      | scope     | position | field             | JSON type     |
      | top level | 1        | schema            | string        |
      | top level | 2        | schemaVersion     | integer       |
      | top level | 3        | commands          | array         |
      | entry     | 1        | name              | string        |
      | entry     | 2        | operation         | string        |
      | entry     | 3        | aliases           | array<string> |
      | entry     | 4        | modes             | array<string> |
      | entry     | 5        | mutationAuthority | string        |
      | entry     | 6        | jsonSupport       | string        |
      | entry     | 7        | stability         | string        |
      | entry     | 8        | requiredArguments | array<string> |
    And workspace opening, scanning, planning, locking, WAL, transaction, editing, and installation mutation boundaries are instrumented fail-closed
    And a public-parser reachability probe resolves route and option grammar through the production parser but stops before command execution
    When the source-built public parser invokes "refactorkit commands --json --schema-version 1" twice with separately captured stdout and stderr bytes
    And the same source-built entrypoint invokes top-level help and "refactorkit capabilities"
    And the reachability probe resolves these exact public token routes:
      | route                          | semantic operation identity        |
      | java create-module             | java.createMavenModule              |
      | java move-across-maven-modules | java.moveAcrossMavenModules         |
      | java rename-module             | java.renameMavenModule              |
    Then both command-catalogue invocations exit successfully
    And each stdout is exactly the UTF-8 encoding of this single JSON object followed by one LF byte:
      """json
      {"schema":"refactorkit.cli-command-catalog/v1","schemaVersion":1,"commands":[{"name":"java create-module","operation":"java.createMavenModule","aliases":[],"modes":["preview","apply"],"mutationAuthority":"refactorkit-managed","jsonSupport":"catalog-only","stability":"experimental","requiredArguments":["--module-name","--parent-pom"]},{"name":"java move-across-maven-modules","operation":"java.moveAcrossMavenModules","aliases":[],"modes":["preview","apply"],"mutationAuthority":"refactorkit-managed","jsonSupport":"catalog-only","stability":"experimental","requiredArguments":["--from","--to"]},{"name":"java rename-module","operation":"java.renameMavenModule","aliases":[],"modes":["preview","apply"],"mutationAuthority":"refactorkit-managed","jsonSupport":"catalog-only","stability":"experimental","requiredArguments":["--old-module-dir","--new-module-dir"]}]}
      """
    And the two complete stdout byte sequences are identical
    And each stderr is empty, with no ANSI sequence, human prose, log, progress text, or stack trace in either stream
    And the closed v1 contract validator accepts that exact object and rejects each object formed by adding the listed unknown field without ignoring or normalizing it:
      | insertion point     | unknown field | JSON value |
      | top level           | unexpected    | true       |
      | first command entry | unexpected    | true       |
    And the commands are in exact lexicographic name order, every alias array is empty, and no route or semantic operation identity is inferred from source-name occurrences
    And top-level help exposes "refactorkit commands --json [--schema-version 1]" and exactly these truthful requirement-owned Java usage lines:
      | route                          | exact usage line                                                                                                                                                                                                                                                                                                                                                                                                                                            |
      | java create-module             | refactorkit java create-module --module-name <name> --parent-pom <pom> [--root <path>] [--apply]                                                                                                                                                                                                                                                                                                                                                             |
      | java move-across-maven-modules | refactorkit java move-across-maven-modules --from <root> --to <root> [--dependency-pom <pom> --source-group-id <id> --source-artifact-id <id> --source-version <v> --destination-group-id <id> --destination-artifact-id <id> --destination-version <v>] [--root <path>] [--apply]                                                                                                                       |
      | java rename-module             | refactorkit java rename-module --old-module-dir <dir> --new-module-dir <dir> [--new-artifact-id <id>] [--root <path>] [--apply]                                                                                                                                                                                                                                                                                                                               |
    And help, exact catalogue membership, and actual public-parser reachability agree for those three routes
    And help does not claim dependency rewrite coordinates are unconditionally required for "java move-across-maven-modules"
    And every existing human-oriented parser and command behavior remains compatible, with no requirement-owned change outside the additive truthful top-level help lines
    And "refactorkit capabilities" matches the exact pinned additive language-capability evolution and remains valid only as the existing language-capability schema
    And the capabilities output contains none of "commands", "operation", "aliases", "modes", "mutationAuthority", "jsonSupport", "stability", or "requiredArguments" as command-catalogue fields
    And no invocation opens or scans a workspace, creates a preview or plan, acquires a workspace lock, creates a WAL or transaction, edits a file, mutates an installation, or invokes the installed executable
    And this scenario does not qualify any of these explicitly excluded future or external surfaces:
      | excluded surface                                                        |
      | refactorkit.cli-result/v1 operation-result envelopes                     |
      | installDist or source-built-to-installed executable parity               |
      | installed-runtime promotion or overwrite                                 |
      | a fresh SURFACE-002 apply, refusal, or rollback rerun                     |
      | non-Java command inventory or other language capability work             |
      | Windows, macOS, another CPU architecture, another JDK, or release parity |

  @REQ-JAVA-CLI-CATALOG-002 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario: Default discovery reports exact catalogue v2 while explicit selection preserves exact v1
    Given the source-built RefactorKit 0.7.0 CLI entrypoint runs locally on Linux with JDK 21 and the current CPU architecture
    And the installed RefactorKit executable is guarded by invocation, lookup, and no-follow mutation tripwires and is neither selected, invoked, nor modified
    And workspace opening, scanning, planning, locking, WAL, transaction, editing, and installation mutation boundaries are instrumented fail-closed
    And separate closed v1 and v2 validators are available without treating either version as the other
    And the exact v1 bytes, closed field and type contract, producer order, command inventory, and semantics are owned unchanged by "REQ-JAVA-CLI-CATALOG-001"
    And the exact successful preview result is owned only by "REQ-JAVA-CLI-RESULT-001" and is not copied into this catalogue scenario
    When the source-built public parser invokes "refactorkit commands --json" twice and "refactorkit commands --json --schema-version 1" twice with each stdout and stderr captured separately
    And the same source-built public parser invokes each invalid selector request:
      | selector condition | exact request                                                                          |
      | malformed          | refactorkit commands --json --schema-version one                                       |
      | duplicate          | refactorkit commands --json --schema-version 1 --schema-version 1                      |
      | missing value      | refactorkit commands --json --schema-version                                           |
      | unsupported        | refactorkit commands --json --schema-version 2                                         |
    And the same source-built entrypoint invokes top-level help and "refactorkit capabilities"
    Then both unversioned invocations exit successfully
    And each unversioned stdout is exactly the UTF-8 encoding of this single compact JSON object followed by one LF byte:
      """json
      {"schema":"refactorkit.cli-command-catalog/v2","schemaVersion":2,"commands":[{"name":"java create-module","operation":"java.createMavenModule","aliases":[],"modes":["preview","apply"],"mutationAuthority":"refactorkit-managed","jsonSupport":"catalog-only","stability":"experimental","requiredArguments":["--module-name","--parent-pom"]},{"name":"java move-across-maven-modules","operation":"java.moveAcrossMavenModules","aliases":[],"modes":["preview","apply"],"mutationAuthority":"refactorkit-managed","jsonSupport":"catalog-only","stability":"experimental","requiredArguments":["--from","--to"]},{"name":"java rename-module","operation":"java.renameMavenModule","aliases":[],"modes":["preview","apply"],"mutationAuthority":"refactorkit-managed","jsonSupport":"preview-only","stability":"experimental","requiredArguments":["--old-module-dir","--new-module-dir"]}]}
      """
    And the two complete unversioned stdout byte sequences are identical
    And each unversioned stderr is empty, with no ANSI sequence, human prose, log, progress text, or stack trace in either stream
    And both explicit-v1 invocations exit successfully and each stdout is exactly the v1 UTF-8 byte oracle owned by "REQ-JAVA-CLI-CATALOG-001", including its one trailing LF byte
    And the two complete explicit-v1 stdout byte sequences are identical and each stderr is empty
    And absence of "--schema-version" selects v2 while one exact "--schema-version 1" selects v1
    And every malformed, duplicate, missing-value, or unsupported selector request is rejected with no successful catalogue bytes and without falling back to v1 or v2
    And this selector-rejection requirement specifies no operation-result envelope, exact error prose, output stream, or exit code
    And the v2 top-level and entry field sets, JSON types, and producer order are exactly the v1 field, type, and order contract owned by "REQ-JAVA-CLI-CATALOG-001", with no added, omitted, or reordered field
    And the commands remain in exact lexicographic name order with the same route-operation pairs, empty aliases, modes, mutation authority, stability, and required arguments as v1
    And only "java rename-module" has "jsonSupport" equal to "preview-only", while "java create-module" and "java move-across-maven-modules" remain "catalog-only"
    And "preview-only" means only the successful source-built preview qualified by "REQ-JAVA-CLI-RESULT-001" and grants no apply, refusal, error, transaction, rollback, approval, or write authority
    And the v2 inventory agrees with the production-parser reachability oracle owned by "REQ-JAVA-CLI-CATALOG-001", without inferring a route or operation from source-name occurrences
    And the separate closed validators accept their exact versioned documents and reject each listed mutation without ignoring or normalizing it:
      | validator | mutation                                                                 |
      | v1        | add unknown top-level field "unexpected" with value true               |
      | v1        | add unknown field "unexpected" with value true to the first entry      |
      | v1        | replace the first entry's "jsonSupport" with unknown value "unknown"  |
      | v2        | add unknown top-level field "unexpected" with value true               |
      | v2        | add unknown field "unexpected" with value true to the first entry      |
      | v2        | replace the rename entry's "jsonSupport" with unknown value "unknown" |
    And neither exact document is accepted by the other version's validator or under the other schema identity
    And top-level help exposes exactly "refactorkit commands --json [--schema-version 1]" as the machine-readable catalogue discovery route and agrees with the public parser
    And "refactorkit capabilities" matches the exact pinned additive language-capability evolution and remains valid only as the existing language-capability schema
    And the capabilities output contains none of "commands", "operation", "aliases", "modes", "mutationAuthority", "jsonSupport", "stability", or "requiredArguments" as command-catalogue fields
    And the explicit-v1 compatibility, default-v2 catalogue, and separately owned successful preview are qualified from the same source-built revision before any one is promoted, and none is promoted alone
    And no catalogue, selector, help, or capabilities invocation opens or scans a workspace, creates a preview or plan, acquires a workspace lock, creates a WAL or transaction, edits a file, mutates an installation, or invokes the installed executable
    And this scenario makes no implementation, validation, promotion, or support claim for:
      | explicitly excluded scope                                                                                  |
      | REQ-JAVA-CLI-RESULT-002 through REQ-JAVA-CLI-RESULT-006                                                    |
      | JSON apply, refusal, usage, operational, internal-error, closure, redaction, or truncation result behavior |
      | JSON results for create-module, move-across-maven-modules, another Java command, or another language      |
      | installDist or source-built-to-installed parity, installed execution, overwrite, or promotion             |
      | a fresh SURFACE-002 apply, refusal, rollback, crash, recovery, or authority qualification                  |
      | non-Java command inventory or other language capability behavior                                           |
      | Windows, macOS, another CPU architecture, another JDK, or another host                                     |
      | package, installer, signed, native, broad release, or release-promotion behavior                           |
