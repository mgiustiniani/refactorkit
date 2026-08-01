# language: en
@non-functional-requirement @implemented-and-validated
Feature: Preserve daemon and MCP workspace snapshots through one bounded composer
  Daemon and MCP need one shared composition policy without changing the snapshots their callers observe.
  The core composer receives only language-neutral scanner and evidence-attacher ports supplied for each call.
  Exact language configuration remains adapter and surface wiring; composition owns no lifecycle or write policy.

  @REQ-WORKSPACE-SNAPSHOT-COMPOSER-001
  Scenario: An empty source contribution preserves the exact authoritative base
    Given two composition calls receive these exact authoritative base snapshots and optional final evidence ports:
      | call          | exact base snapshot | original file order         | source extensions | ignored directories | optional final evidence port |
      | without final | B0                  | zeta/Z.java, alpha/A.java   | java              | .git, target        | absent                       |
      | with final    | B1                  | second/Z.java, first/A.java | java              | .git, target        | current-final-1              |
    And each source-inventory port returns zero files even though the fixture workspace contains "tsconfig.json"
    And each surface-supplied TypeScript evidence port and optional final evidence port records exact input identity and invocation count
    When the stateless composer handles both calls
    Then the TypeScript evidence ports are invoked zero times
    And the call without a final port returns the same B0 instance and hash, with its original file order, extensions, and ignored directories unchanged
    And current-final-1 receives the same B1 instance rather than a copy and the call returns its exact output unchanged

  @REQ-WORKSPACE-SNAPSHOT-COMPOSER-002
  Scenario: A non-empty source contribution overlays only its bounded inventory
    Given the authoritative base snapshot has these distinct field values:
      | field                  | exact base value                     |
      | workspace              | workspace-base                      |
      | modules                | modules-base                        |
      | classpath evidence     | classpath-base                       |
      | build models           | maven-base, existing-provider-base  |
      | auxiliary files        | pom.xml, settings.gradle.kts        |
      | source extensions      | java, kt                            |
      | ignored directories    | .git, target                        |
    And its source files are:
      | path                          | content marker | language ID  |
      | zeta/Base.java                | base-zeta      | java         |
      | scripts/legacy/../same.ts     | base-shadowed  | base-fixture |
    And the later source-inventory port is configured with these exact mappings and files:
      | extension | language ID | path                 | content marker |
      | ts        | typescript  | scripts/same.ts      | later-ts       |
      | tsx       | typescript  | alpha/View.tsx       | later-tsx      |
      | js        | javascript  | middle/worker.js     | later-js       |
      | jsx       | javascript  | omega/Panel.jsx      | later-jsx      |
    And that contribution declares ignored directories "node_modules" and "dist"
    When the composer overlays the contribution before an identity evidence attacher
    Then normalized path "scripts/same.ts" retains the later "later-ts" source and discards "base-shadowed"
    And the surviving source values have this exact `path.toString()` order:
      | order | path             | content marker |
      | 1     | alpha/View.tsx   | later-tsx      |
      | 2     | middle/worker.js | later-js       |
      | 3     | omega/Panel.jsx  | later-jsx      |
      | 4     | scripts/same.ts  | later-ts       |
      | 5     | zeta/Base.java   | base-zeta      |
    And source extensions are exactly the union "java, kt, ts, tsx, js, jsx" and ignored directories are exactly the union ".git, target, node_modules, dist"
    And workspace, modules, classpath evidence, pre-existing build models, and auxiliary files retain their exact authoritative base values
    And repeating composition with identical port evidence returns equal canonical content and hash without a clock, UUID, or composer identity

  @REQ-WORKSPACE-SNAPSHOT-COMPOSER-003
  Scenario: Each call attaches conditional evidence once before its optional current final evidence
    Given three non-empty composition calls supply these language-neutral ports for that call only:
      | call   | base scanner | source-inventory scanner | TypeScript-configured conditional attacher | optional Kotlin-configured final attacher |
      | first  | primary-1    | source-1                 | conditional-1                              | final-1                                  |
      | second | primary-2    | source-2                 | conditional-2                              | final-2                                  |
      | third  | primary-3    | source-3                 | conditional-3                              | absent                                   |
    And every port returns a distinct immutable snapshot and records exact input and output identity
    When the same stateless composer handles first, second, and third in order
    Then their exact collaborator traces are:
      | call   | ordered trace                                         |
      | first  | primary-1, source-1, conditional-1, final-1           |
      | second | primary-2, source-2, conditional-2, final-2           |
      | third  | primary-3, source-3, conditional-3                    |
    And each conditional attacher is invoked exactly once with that call's overlaid snapshot
    And final-1 and final-2 are each invoked exactly once with that call's exact conditional output, while the third call returns conditional-3's exact output
    And no scanner or attacher from an earlier call is retained, reused, or invoked by a later call
    And the composer passes through each attacher's provider replacement or typed unavailability result without reinterpretation

  @REQ-WORKSPACE-SNAPSHOT-COMPOSER-004
  Scenario Outline: A collaborator failure escapes unchanged without composition side effects
    Given the per-call "<collaborator>" port throws the exact sentinel object "<failure>" with a distinct type, code, message, and cause
    And every earlier stage succeeds with a non-empty source contribution and every later port records whether it was invoked
    And workspace bytes, paths, caller state, process activity, locks, and ".refactorkit" contents are recorded before composition
    When the composer handles the call
    Then the same "<failure>" object escapes by reference with its type, code, message, and cause unchanged
    And no stage after "<stage>" is invoked
    And the composer performs no workspace write, build execution, process lifecycle action, lock, WAL, transaction, PatchEngine call, recovery, or rollback
    And all recorded workspace, caller, process, lock, and ".refactorkit" state remains unchanged

    Examples:
      | collaborator                                      | stage | failure       |
      | authoritative base scanner                        | 1     | E_primary     |
      | secondary source-inventory scanner                | 2     | E_secondary   |
      | TypeScript-configured conditional evidence attacher | 3   | E_conditional |
      | current Kotlin-configured final evidence attacher | 4     | E_final       |

  @REQ-WORKSPACE-SNAPSHOT-COMPOSER-005
  Scenario: Adoption centralizes composition only for daemon and MCP
    Given the shared composer is a stateless service in core with primary-scan, source-inventory, conditional-evidence, and optional-final-evidence ports supplied per call
    And daemon and MCP retain the exact script-extension configuration in their adapter or surface wiring rather than in core
    When only daemon and MCP replace their duplicate mixed-workspace composition with the shared composer
    Then they invoke it at their existing composition points:
      | surface | existing composition points                                                              |
      | daemon  | project open, saved-workspace refresh, apply baseline, apply post-image, rollback refresh |
      | MCP     | project scan, apply baseline, apply post-image, rollback refresh                          |
    And each surface retains these policies outside composition:
      | policy boundary             | surface-owned decisions                                                                                                  |
      | refresh and saved state     | invocation timing, stored root and snapshot, watcher dirty or overflow state, refresh count, and response selection      |
      | workspace index             | open, reconcile, contribution, generation, and symbol discovery                                                          |
      | pending and authority state | pending plans, lexical-review audit, admission, removal, clearing, and semantic leases                                   |
      | semantic lifecycle          | toolchain or adapter discovery, start, stop, restart, reset, close, and post-apply scan-before-cleanup ordering           |
      | PatchEngine authority       | preview routing, diagnostics gates, authorization, lock, WAL, apply, validation, recovery, and automatic or explicit rollback |
      | protocol policy             | capabilities, JSON or text rendering, error mapping, changed-file projection, and success or refusal responses           |
    And CLI and LSP keep their existing scan and lifecycle behavior and do not adopt this composer in this extraction
    And core imports no concrete Java, Tree-sitter, TypeScript, Kotlin, daemon, or MCP type and gains no generalized scanner registry or new orchestration module
    And composition returns only an immutable snapshot and owns no stored state, index, protocol, persistence, or mutation authority
