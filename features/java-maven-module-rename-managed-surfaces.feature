# language: en
@source-built-in-process @packaged-runtime-excluded @native-qualification-excluded
Business Need: Preserve direct-child Maven module-rename authority across managed public surfaces
  As a maintainer using RefactorKit through its public entry points
  I need every managed module-rename apply to retain the operation's exact Maven authority
  So that protocol convenience cannot replace authoritative validation, journaling, or rollback

  This bounded slice reuses the qualified direct-library behavior of REQ-JAVA-MAVEN-MODULE-RENAME-001 rather than repeating its 50-step proof.
  It exercises source-built classes through real in-process CLI, daemon, MCP, and LSP entry points only.
  Recipe composition is a separate authority-preservation slice. Packaged processes, transports, native hosts, operating systems, architectures, cross-platform execution, general Maven projects, and broad module-rename support remain unqualified.

  Background:
    Given each case uses a fresh no-follow disposable byte copy of "testdata/acceptance/java-maven-move-class-authority-20-modules"
    And the permanent fixture remains an immutable offline reactor with one root aggregator and exactly 20 direct non-aggregator JAR children
    And this slice reuses REQ-JAVA-MAVEN-MODULE-RENAME-001's already-qualified in-process denial contract for Maven and wrapper execution, lifecycle goals, plugins, annotation processors, settings and credential access, credential helpers, and network requests
    And only child-process starts and RefactorKit-attributable socket reads or writes are independently observed here
    And the qualified request is oldModuleDir="catalog-model", newModuleDir="catalog-domain", and caller-explicit newArtifactId="catalog-domain"
    And REQ-JAVA-MAVEN-MODULE-RENAME-001 supplies the canonical "java.renameMavenModule" PREVIEW, exact five-edit candidate "C1", immutable authority lease and auxiliary-POM evidence, baseline "S0", authoritative post-image "S1", and diagnostic multiset "D0"

  @REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-001 @non-functional-requirement @implemented-and-validated
  Scenario: Exact module-rename identity takes the production lazy authoritative route before real Java fallbacks
    Given each production route probe invokes the normal five-argument ManagedApplyDiagnosticsGateSelector.select entry used by daemon and MCP with exact language ID "java", the adapters current for this call, and the canonical plan or a copy differing only in operation
    And isolated non-applying production route probes use these exact case-sensitive operation JSON labels:
      | operation JSON label                     | expected route                                           | exact gate ID                                |
      | "java.renameMavenModule"                 | production operation-owned Maven module-rename authority | java-rename-maven-module-staged-reactor-v1   |
      | "java.renamemavenmodule"                 | real production generic Java diagnostics                 | java-jdt                                     |
      | "renameMavenModule"                      | real production generic Java diagnostics                 | java-jdt                                     |
      | "java.moveAcrossMavenModules"            | real production Maven move-ownership diagnostics         | java-maven-ownership                         |
      | "renameClass"                            | real production generic Java diagnostics                 | java-jdt                                     |
      | "\\u0020java.renameMavenModule"         | real production generic Java diagnostics                 | java-jdt                                     |
      | "java.renameMavenModule\\u0020"         | real production generic Java diagnostics                 | java-jdt                                     |
    And the escaped whitespace labels are decoded as JSON strings into these exact raw plan-operation bytes before plan construction:
      | operation JSON label                     | exact raw operation UTF-8 hexadecimal                                                                  |
      | "\\u0020java.renameMavenModule"         | 20 6a 61 76 61 2e 72 65 6e 61 6d 65 4d 61 76 65 6e 4d 6f 64 75 6c 65                               |
      | "java.renameMavenModule\\u0020"         | 6a 61 76 61 2e 72 65 6e 61 6d 65 4d 61 76 65 6e 4d 6f 64 75 6c 65 20                               |
    And a separate language-neutral lazy-gate mechanics probe observes only an inert authoritative-factory count and fixed diagnostics over synthetic immutable snapshots
    And that mechanics probe has no canonical plan, authority lease, Java or Maven provider, case-workspace access, PatchEngine, journal, or reference from a production-selected gate
    When every decoded operation is selected through the normal production entry and the canonical preview is rendered without invoking PatchEngine
    Then every probe returns its listed gate ID while its raw plan operation remains exact, without trimming, case-folding, prefixing, aliasing, or relabelling
    And only exact raw operation "java.renameMavenModule" returns the production lazy authoritative gate "java-rename-maven-module-staged-reactor-v1" bound to the same immutable canonical plan and authority lease supplied to selection
    And selection and preview rendering complete without authoritative evaluation, fallback-provider invocation, Maven or JDT analysis, child-process start, or RefactorKit-attributable socket read or write
    When each selected non-matching Java gate evaluates the unchanged real "S0" snapshot once
    Then exact raw operation "java.moveAcrossMavenModules" invokes the real production JavaMoveAcrossMavenModulesPlanner diagnostics provider
    And every other non-matching raw operation invokes the real production diagnostics provider of the JavaLanguageAdapter current for that selector call
    And no test-supplied fallback-provider function participates in production selection or fallback evaluation
    When the isolated mechanics probe creates its lazy authoritative gate, reads its ID, and evaluates three synthetic candidate snapshots
    Then its inert factory count is zero through gate creation and ID inspection, becomes one at first evaluation, and remains one through all three evaluations
    And all three fixed diagnostic results are returned without an evaluation observer, and the mechanics probe changes no case-workspace or journal state
    When PatchEngine begins exactly one explicitly approved apply of the canonical plan through the production-selected module-rename gate
    Then the real operation-owned gate uses the exact retained plan and lease to evaluate exact "S0" and exact staged candidate "C1" before PREPARED, then evaluates committed "S1" after APPLIED and before ApplyResult.Applied is returned
    And read-only journal inspection finds exactly one schema-v8 APPLIED record whose operation, exact five-entry forward edit, approval, "S0" pre-snapshot, "S1" post-snapshot, and PREPARED, APPLYING, APPLIED history match the canonical plan
    And the real "java-jdt" and "java-maven-ownership" fallback providers perform no invocation for that module-rename apply
    And no caller-supplied operation-gate factory, evaluation observer, fallback-provider function, or mechanics-probe gate participates in the production-selected apply
    When the resulting sole managed transaction is rolled back in NORMAL mode
    Then the disposable copy returns exactly to "S0" and "D0" without changing the permanent fixture
    And the same schema-v8 record reaches ROLLED_BACK with no second transaction
    And no child process or RefactorKit-attributable socket read or write was observed during selection, fallback evaluation, the mechanics probe, apply, or rollback

  @REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-002 @functional-requirement @non-functional-requirement @absent
  Scenario: CLI apply refuses staged provider drift and otherwise journals and reverses the authoritative rename
    Given the actual source-built RefactorKit CLI has the case workspace and one independent refusal-probe copy, both at exact "S0" with an empty transaction journal
    And the exact command is `refactorkit java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain --new-artifact-id catalog-domain --root . --apply`
    And a controlled refusal-probe fault makes the operation-owned staged evaluator preserve "C1" root, paths, partition, and language IDs but change one auxiliary "catalog-pricing/pom.xml" byte relative to "C1"
    When the exact command is invoked against the refusal-probe copy
    Then the CLI exits non-zero through its existing "Apply refused" rendering with the authoritative tracked-content violation
    And refusal occurs after workspace-lock acquisition but before a PREPARED journal record and before any planned POM modification or file rename
    And the refusal-probe non-engine bytes and path kinds remain exact "S0", while lock-file residue is not represented as a transaction
    When the exact command is invoked against the unchanged case workspace without the controlled fault
    Then `--apply` supplies explicit CLI approval for the exact planner-returned "java.renameMavenModule" plan
    And the lazy operation-owned gate accepts authoritative baseline "S0" and staged "C1"/"S1" before WAL, then attests committed "S1" and "D0"
    And the sole schema-v8 journal record first becomes PREPARED only after approval, live-snapshot, lease, destination, raw-POM, effective-reactor, and staged-authority validation
    And the CLI succeeds with one transaction ID after that same record reaches APPLIED with the exact five-entry forward edit, complete "S0" and "S1" images, and approval surface "cli"
    And the committed non-engine workspace is exact "S1" and no second record or target edit exists
    When `refactorkit patch rollback` is invoked for that transaction without force
    Then normal rollback succeeds and the same record reaches ROLLED_BACK without a second transaction
    And every non-engine byte, path kind, reactor fact, snapshot identity, auxiliary-POM fact, and diagnostic equals the original "S0" and "D0"
    And only the workspace lock and the one advanced schema-v8 record remain as expected engine residue

  @REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-003 @functional-requirement @non-functional-requirement @absent
  Scenario Outline: <surface> retains and applies the exact authoritative plan before refreshing successful state
    Given an actual source-built <surface> session opens the case workspace through "<open entry>"
    And its saved "S0" preserves the scanner's exact source and auxiliary partition, including every raw POM byte required by the canonical lease
    And its pending-plan store, saved snapshot, refresh observations, index where present, and semantic-session lifecycle are recorded
    When the session invokes "<preview entry>" with the qualified request and no source-symbol placeholder
    Then the real JavaRenameMavenModulePlanner is invoked once and the public result returns an actionable plan ID for exact operation "java.renameMavenModule"
    And "<route evidence>" is observed at the public boundary, so a source file or operation-name occurrence alone is never accepted as route evidence
    And lookup by that plan ID returns the same immutable PatchPlan and authority lease supplied by the planner without JSON, text, diff, or source-presence reconstruction
    And the retained lease and saved snapshot together preserve the exact raw-POM hashes, ranges, required-file evidence, and auxiliary-POM bytes used to qualify "S0" and "C1"
    And preview performs no managed write, apply-gate construction, apply-gate provider invocation, journal creation, or state refresh beyond its canonical read-only planning evaluations
    Given an independent <surface> refusal-probe session has retained the same canonical preview and its controlled staged evaluator changes one auxiliary POM byte relative to "C1"
    When that session invokes "<apply entry>" with only the returned plan ID
    Then the existing <surface> refusal projection preserves the authoritative tracked-content violation
    And no PREPARED record or planned target edit exists and the refusal-probe workspace remains exact "S0"
    And no saved snapshot, "<refresh state>", index, or semantic session is refreshed or closed by the refused apply
    And pending-plan retention or removal follows the surface's existing refusal lifecycle rather than masquerading as a refresh
    When the unchanged primary session invokes "<apply entry>" with its returned plan ID
    Then PatchEngine receives the exact retained plan and a fresh exact "S0" scan with the complete auxiliary-POM inventory
    And the same lazy operation-owned gate evaluates "S0", "C1", and committed "S1" without invoking generic "java-jdt"
    And no session refresh occurs before ApplyResult.Applied; only success refreshes "<refresh state>" to exact "S1" and returns one transaction ID
    And the transaction journal contains exactly one schema-v8 APPLIED record for the canonical edit and surface approval
    When the session invokes "<rollback entry>" for that transaction in normal mode
    Then exact rollback advances the same record to ROLLED_BACK, restores all non-engine bytes and path kinds to "S0", and creates no second transaction
    And only successful rollback refreshes "<refresh state>" back to exact "S0" with authoritative "D0"

    Examples:
      | surface         | open entry   | preview entry                                                                                                                      | route evidence                                                                              | apply entry         | rollback entry       | refresh state                       |
      | daemon JSON-RPC | project.open | refactor.preview operation="renameMavenModule" languageId="java" with oldModuleDir, newModuleDir, and newArtifactId arguments    | actual refactor.preview invocation and structured PREVIEW response                            | refactor.apply      | patch.rollback       | saved snapshot and workspace index  |
      | MCP tools       | project_scan | preview_refactoring operation="renameMavenModule" languageId="java" with oldModuleDir, newModuleDir, and newArtifactId arguments | tools/list advertisement plus actual preview_refactoring result without a symbol placeholder | apply_refactoring   | rollback_refactoring | saved snapshot                      |

  @REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-004 @non-functional-requirement @absent
  Scenario: LSP keeps a versioned module-rename proposal client-managed and non-rollbackable
    Given one API "0.2" LSP session negotiates versioned documentChanges over the case workspace and an independent legacy session does not over its own fresh "S0" copy
    And both sessions have exact "S0", no dirty document, no pending plan, and an empty RefactorKit transaction journal
    When the version-capable client invokes workspace/executeCommand "refactorkit.renameMavenModule" with the qualified request
    Then the real planner returns the canonical plan and the response contains these exact ownership facts:
      | response fact                         | exact value                                  |
      | operation                             | java.renameMavenModule                        |
      | status                                | PREVIEW                                       |
      | refactorkitEditOwnership              | client-managed                                |
      | refactorkitRollbackAvailable          | false                                         |
      | refactorkitDocumentVersionsChecked    | true                                          |
      | edit shape                            | documentChanges                               |
    And documentChanges represents the canonical three POM modifications followed by the two file renames, with null versions for closed text documents and no fabricated open-document version
    And refactorkitPlanId correlates the exact retained plan for the distinct managed command but does not turn this proposal into approval or a transaction
    And proposal creation invokes no operation gate, PatchEngine apply, workspace lock, WAL, transaction, state refresh, or rollback
    When the editor applies that returned WorkspaceEdit without invoking "refactorkit.applyPlan"
    Then the editor owns those writes and RefactorKit still has no journal record, transaction ID, recovery claim, or rollback capability for them
    And semantic, versioned, diff, or client-application evidence from the proposal is never counted as managed-apply validation
    When the legacy client requests the same structural proposal without versioned documentChanges support
    Then LSP refuses with DOCUMENT_VERSION_MISMATCH before returning an edit or retaining a plan and changes no workspace byte or journal state

  @REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-005 @functional-requirement @non-functional-requirement @absent
  Scenario: Only LSP applyPlan manages the retained exact plan through preflight, authority, and rollback
    Given three version-capable API "0.2" LSP sessions use separate fresh "S0" copies without asking an editor to apply a proposal
    And the dirty-document probe already tracks exact saved content for both unaffected "reporting-unrelated/src/main/java/com/acme/reporting/ProductReport.java" and affected "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"
    And the affected-open probe already tracks exact saved content for affected "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"
    And the primary session has no open document
    When all three sessions invoke workspace/executeCommand "refactorkit.renameMavenModule" and independently retain the canonical proposal
    And an external editor then changes only the dirty probe's saved ProductReport bytes without an LSP lifecycle notification, leaving its tracked open buffer divergent and its plan retained
    And each probe invokes workspace/executeCommand "refactorkit.applyPlan" with its own retained plan ID
    Then LSP rejects both with DOCUMENT_VERSION_MISMATCH in this preflight order:
      | probe          | refusal reason                                                                    |
      | dirty-document | the divergent ProductReport buffer is rejected before the affected open Product  |
      | affected-open  | the affected Product document is open even though it is clean                     |
    And each preflight refusal occurs before a fresh apply scan, operation-gate construction, PatchEngine, workspace lock, WAL, target edit, pending-plan removal, or state refresh
    And neither the retained plan ID nor the earlier client-managed proposal counts as approval, validation, or managed-apply evidence
    When the clean and closed primary session invokes "refactorkit.applyPlan" with its retained plan ID
    Then LSP supplies PatchEngine the same exact PatchPlan and lease plus a fresh "S0" scan retaining every auxiliary POM byte
    And only this command supplies explicit approval surface "lsp-managed-command" and selects the lazy operation-owned authoritative gate
    And the gate evaluates exact "S0", "C1", and committed "S1" with "D0" while generic "java-jdt" remains unused for the apply
    And one schema-v8 record reaches APPLIED for the exact canonical edit before LSP returns its transaction ID
    And only after ApplyResult.Applied does LSP remove the plan, refresh exact "S1", and make normal RefactorKit rollback available for that transaction
    When the clean and closed client invokes "refactorkit.rollback" for that transaction without force
    Then normal rollback advances the same record to ROLLED_BACK, restores every non-engine byte and path kind to exact "S0", and creates no second transaction
    And successful rollback refreshes exact "S0" and "D0", while the client-managed proposal route remains non-rollbackable and unqualified as managed apply
