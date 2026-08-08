# language: en
Ability: Discover the exact public Java CLI command catalogue without entering a workspace lifecycle
  Scripts, IDEs, and local agents need one truthful machine-readable inventory of the Java command routes they may select.
  The catalogue is a transient CLI distribution-surface document, distinct from language capabilities, semantic operation results, approval, and write authority.
  This qualification is limited to source-built RefactorKit 0.7.0-SNAPSHOT on local Linux, JDK 21, and the current CPU architecture.

  @REQ-JAVA-CLI-CATALOG-001 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario: Source-built discovery reports the closed deterministic Java command catalogue and nothing else
    Given the source-built RefactorKit 0.7.0-SNAPSHOT CLI entrypoint runs locally on Linux with JDK 21 and the current CPU architecture
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
    When the source-built public parser invokes "refactorkit commands --json" twice with separately captured stdout and stderr bytes
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
    And top-level help exposes "refactorkit commands --json" and exactly these truthful requirement-owned Java usage lines:
      | route                          | exact usage line                                                                                                                                                                                                                                                                                                                                                                                                                                            |
      | java create-module             | refactorkit java create-module --module-name <name> --parent-pom <pom> [--root <path>] [--apply]                                                                                                                                                                                                                                                                                                                                                             |
      | java move-across-maven-modules | refactorkit java move-across-maven-modules --from <root> --to <root> [--dependency-pom <pom> --source-group-id <id> --source-artifact-id <id> --source-version <v> --destination-group-id <id> --destination-artifact-id <id> --destination-version <v>] [--root <path>] [--apply]                                                                                                                       |
      | java rename-module             | refactorkit java rename-module --old-module-dir <dir> --new-module-dir <dir> [--new-artifact-id <id>] [--root <path>] [--apply]                                                                                                                                                                                                                                                                                                                               |
    And help, exact catalogue membership, and actual public-parser reachability agree for those three routes
    And help does not claim dependency rewrite coordinates are unconditionally required for "java move-across-maven-modules"
    And every existing human-oriented parser and command behavior remains compatible, with no requirement-owned change outside the additive truthful top-level help lines
    And "refactorkit capabilities" remains byte-for-byte compatible with its pinned pre-slice output and valid only as the existing language-capability schema
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
