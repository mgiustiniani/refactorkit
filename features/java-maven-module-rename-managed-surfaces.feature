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
  Scenario: CLI refuses an under-lock required-POM change and otherwise journals and reverses the authoritative rename
    Given the actual source-built RefactorKit CLI has the unchanged case workspace and one independent refusal-probe copy, both at exact "S0" with an empty transaction journal
    And the exact command is `refactorkit java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain --new-artifact-id catalog-domain --root <case-workspace-absolute-path> --apply`, where the value substituted for `<case-workspace-absolute-path>` is the normalized absolute root of the disposable copy used by that invocation and all other operation arguments and flags remain exact
    And REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-001's executable production-selector qualification dynamically counts provider and authoritative-factory invocations through the normal five-argument `ManagedApplyDiagnosticsGateSelector.select` entry, proving zero generic "java-jdt" provider invocations and exactly one lazy operation-owned authoritative-provider construction at first evaluation for exact operation "java.renameMavenModule"
    And the refusal probe alone may use reflection solely to invoke one private test constructor or private test factory whose sole input is `PatchFaultInjector`, retained as a private final construction-time dependency; the seam, including every synthetic bridge, is absent from public JVM bytecode and Kotlin and Java APIs, while production `main` and the unchanged default public constructor always use the no-fault construction path
    And that construction seam exposes no mutable `PatchEngine`, patch-engine factory, injector property, setter, post-construction or global injection hook, or race; it can only construct the refusal CLI and cannot substitute a diagnostics provider, diagnostics gate, planner-returned plan, CLI authorization, or the no-fault production path
    When the exact command is invoked against the refusal-probe copy through that reflectively constructed refusal CLI after substituting only `<case-workspace-absolute-path>` with that copy's normalized absolute root, and the existing `BEFORE_AUTHORITY_LEASE_VALIDATION` fault point replaces the final LF byte of required "catalog-pricing/pom.xml" with ASCII space after workspace-lock acquisition
    Then the CLI exits non-zero, every hash placeholder in the exact stderr below is substituted with its independently retained or recomputed 64-character lowercase hexadecimal value from the lease, "S0", and the mutated refusal copy rather than a value parsed back from stderr, and each `<SP>` marker is decoded as one ASCII space
    And stderr consists of exactly these ordered lines followed by one line terminator and no other stderr text:
      | order | exact decoded line |
      | 1     | Apply refused: |
      | 2     | <SP><SP>ERROR [authorityLease.evidenceDrift]: Operation-authority lease evidence drift: kind=MAVEN_REACTOR_RAW_POM path=catalog-pricing/pom.xml expectedContentSha256=<expected-content-sha256> observedContentSha256=<observed-content-sha256> expectedRequiredFileEvidenceSha256=<expected-required-file-evidence-sha256> observedRequiredFileEvidenceSha256=<observed-required-file-evidence-sha256> (authorityLayer=EVIDENCE_FRESHNESS, changedSourceManaged=true, evidenceKind=MAVEN_REACTOR_RAW_POM, expectedCandidateInventorySha256=<expected-candidate-inventory-sha256>, expectedContentSha256=<expected-content-sha256>, expectedRequiredFileEvidenceSha256=<expected-required-file-evidence-sha256>, observedContentSha256=<observed-content-sha256>, observedRequiredFileEvidenceSha256=<observed-required-file-evidence-sha256>, observedSnapshotSha256=<observed-snapshot-sha256>, path=catalog-pricing/pom.xml, previewSnapshotSha256=<preview-snapshot-sha256>) |
    And line 2 is the first and sole diagnostic, its code and message are exact, and its complete detail set is rendered once in ascending key order as shown
    And an executable probe through that same production diagnostic-line renderer maps severity ERROR, message "coded refusal", code "probe.code", and empty details to exact decoded line `<SP><SP>ERROR [probe.code]: coded refusal`, so every present code renders independently of whether details are empty
    And refusal occurs before any PREPARED journal state, write-ahead-log record, or managed entry of the canonical five-entry target edit
    And the refusal probe preserves the externally changed final POM byte; every other non-engine byte and every path kind remains exact "S0", no equality of the whole copy to "S0" is asserted, and possible workspace-lock residue is not a PREPARED record or transaction
    When the exact command is invoked against the unchanged case workspace through the production CLI path after substituting only `<case-workspace-absolute-path>` with that copy's normalized absolute root and without the seam or fault
    Then `--apply` supplies explicit CLI approval for the exact planner-returned "java.renameMavenModule" plan
    And that real CLI apply invokes the normal five-argument `ManagedApplyDiagnosticsGateSelector.select` production entry exactly once with the exact plan, language ID "java", the adapters current for the CLI invocation, and its normal external-gate resolver instead of a CLI-local operation `when` branch or any duplicated routing decision
    And the selected real operation-owned gate "java-rename-maven-module-staged-reactor-v1" uses that exact plan and lease to evaluate authoritative "S0" and staged "C1" before PREPARED, then committed "S1" with "D0" after APPLIED and before CLI success
    And combined executable evidence uses this real CLI invocation to qualify selector adoption and committed "S1", and REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-001's dynamic invocation counts to prove zero generic "java-jdt" provider invocations and exact one-time lazy authoritative-provider construction; source or bytecode occurrence and absence of a gate ID from stdout or stderr are not accepted as substitutes
    And the sole journal record has integer literal `schemaVersion` 8, never an expected value obtained from `CURRENT_SCHEMA_VERSION` or another production current-version constant, and first becomes PREPARED only after approval, live-snapshot, lease, destination, raw-POM, effective-reactor, and staged-authority validation
    And the CLI succeeds with one transaction ID after that same record reaches APPLIED with the exact five-entry forward edit, complete "S0" and "S1" images, and approval surface "cli"
    And before any journal image or directory field is used as an oracle, independent no-follow filesystem observations capture exact "S0" before apply and committed "S1" before journal inspection and project both through the schema-version-8 `FileImage` field contract
    And the ordered pre-images and post-images in the record equal that independent oracle field by field for every canonical affected path:
      | exposed FileImage field  | exact independent expectation |
      | path                     | the canonical normalized affected-path order, including each absent rename source or destination image |
      | content                  | the exact independently captured UTF-8 content, or null exactly where that path is absent in the corresponding image |
      | contentSha256            | an independently computed SHA-256 of that content, or null exactly when content is null |
      | posixPermissions         | the exact no-follow permission set for an existing image when POSIX attributes are supported, the exact source-derived post-image set required by the contract, or null exactly when absent or unsupported |
      | lastModifiedMillis       | the exact independently captured pre-image millisecond timestamp for an existing file, null for an absent pre-image, and null for every post-image as specified by the schema-version-8 contract |
      | ownerName                | the exact independently captured pre-image owner for an existing file, null for an absent pre-image, and null for every post-image as specified by the schema-version-8 contract |
      | groupName                | the exact independently captured pre-image group when POSIX ownership is supported, null when absent or unsupported, and null for every post-image as specified by the schema-version-8 contract |
      | userDefinedAttributes    | the exact independently captured sorted xattr name/base64-value map when that view is supported, with the exact source-retained post-image map, or null exactly when absent or unsupported |
      | aclEntries               | the exact independently captured ordered ACL type, principal, sorted permissions, and sorted flags when that view is supported, with the exact source-retained post-image entries, or null exactly when absent or unsupported |
    And the record's `createdDirectories` equals this exact independently declared ordered list rather than a value copied from that record:
      | order | normalized directory path |
      | 1     | catalog-domain |
      | 2     | catalog-domain/src |
      | 3     | catalog-domain/src/main |
      | 4     | catalog-domain/src/main/java |
      | 5     | catalog-domain/src/main/java/com |
      | 6     | catalog-domain/src/main/java/com/acme |
      | 7     | catalog-domain/src/main/java/com/acme/catalog |
      | 8     | catalog-domain/src/main/java/com/acme/catalog/legacy |
    And the independent image oracle and literal directory list are retained for the rollback assertion and are never obtained from either the APPLIED record under test or a later copy of the same record
    And the committed non-engine workspace is exact "S1" and no second record or target edit exists
    When `refactorkit patch rollback <transaction-id> --root <case-workspace-absolute-path>` is invoked without force after substituting `<transaction-id>` with that transaction ID and `<case-workspace-absolute-path>` with the same normalized absolute disposable-copy root used by the primary invocation
    Then normal rollback succeeds and the same record with integer literal `schemaVersion` 8 reaches ROLLED_BACK without a second transaction and still equals the independent pre-image, post-image, and exact `createdDirectories` oracles
    And every non-engine byte, path kind, reactor fact, snapshot identity, auxiliary-POM fact, and diagnostic equals the original "S0" and "D0"
    And in the primary copy only the workspace lock and the one advanced record with integer literal `schemaVersion` 8 remain as expected engine residue

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
