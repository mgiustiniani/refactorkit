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

  @REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-002 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario: CLI refuses an under-lock required-POM change and otherwise journals and reverses the authoritative rename
    Given the actual source-built RefactorKit CLI has the unchanged case workspace and one independent refusal-probe copy, both at exact "S0" with an empty transaction journal
    And only child-process starts and RefactorKit-attributable socket reads or writes are independently observed here
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

  @REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-003 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario Outline: <surface> retains and applies the exact authoritative plan before refreshing successful state
    Given an actual source-built <surface> session opens the case workspace through "<open entry>"
    And this row invokes the daemon and MCP sessions in process; stdio transport, child-process activity, and socket activity are outside this row's qualification
    And its saved "S0" preserves the scanner's exact source and auxiliary partition, including every raw POM byte required by the canonical lease
    And its pending-plan store, saved snapshot, refresh observations, index where present, and semantic-session lifecycle are recorded
    And before preview the harness retains REQ-JAVA-MAVEN-MODULE-RENAME-001's literal five-entry fixture oracle for this qualified catalog-model->catalog-domain row without reading a PatchPlan or journal record: Modify catalog-model/pom.xml artifactId, Modify root pom.xml module entry, Modify catalog-pricing/pom.xml dependency artifactId, Rename catalog-model/pom.xml to catalog-domain/pom.xml, and Rename catalog-model/src/main/java/com/acme/catalog/legacy/Product.java to the catalog-domain path
    When the session invokes "<preview entry>" with the qualified request and no source-symbol placeholder
    Then the real JavaRenameMavenModulePlanner is invoked once and the public result returns an actionable plan ID for exact operation "java.renameMavenModule"
    And "<route evidence>" is observed at the public boundary, so a source file or operation-name occurrence alone is never accepted as route evidence
    And lookup by that plan ID returns the same immutable PatchPlan and authority lease supplied by the planner without JSON, text, diff, or source-presence reconstruction
    And the retained plan's normalized WorkspaceEdit equals that predeclared five-entry oracle entry-for-entry
    And the retained lease and saved snapshot together preserve the exact raw-POM hashes, ranges, required-file evidence, and auxiliary-POM bytes used to qualify "S0" and "C1"
    And preview performs no managed write, apply-gate construction, apply-gate provider invocation, journal creation, or state refresh beyond its canonical read-only planning evaluations
    Given an independent <surface> refusal-probe session on a separate workspace copy retains its own plan ID returned by an independent canonical preview, and its controlled staged evaluator changes one auxiliary POM byte relative to "C1"
    When that session invokes "<apply entry>" with only the returned plan ID
    Then the existing <surface> refusal projection preserves the authoritative tracked-content violation
    And no PREPARED record or planned target edit exists and the refusal-probe workspace differs from "S0" only in the single auxiliary POM byte mutated by the controlled staged-evaluator change, with no rename applied
    And no saved snapshot, "<refresh state>", index, or semantic session is refreshed or closed by the refused apply; the refusal creates no target edit, WAL entry, or transaction, and for the daemon the dirty workspace is reconciled in a pre-dispatch integration phase before "<apply entry>", so the subsequent refusal performs no outcome-driven or success refresh, while the MCP surface has no equivalent pre-dispatch reconciliation and its generic refusal leaves the saved snapshot and any semantic session unchanged
    And pending-plan retention or removal follows the surface's existing refusal lifecycle rather than masquerading as a refresh
    When the unchanged primary session invokes "<apply entry>" with the plan ID returned by its own canonical preview
    Then PatchEngine receives the exact retained plan and a fresh exact "S0" scan with the complete auxiliary-POM inventory
    And the same lazy operation-owned gate evaluates "S0", "C1", and committed "S1" without invoking generic "java-jdt"
    And no session refresh occurs before ApplyResult.Applied; the daemon pre-dispatch reconciliation of the dirty workspace is not a success refresh and performs no outcome-driven refresh, and only ApplyResult.Applied refreshes "<refresh state>" to exact "S1" and returns one transaction ID
    And the transaction journal contains exactly one schema-v8 APPLIED record for the canonical edit and surface approval
    And that record's deserialized forwardEdit equals the retained normalized plan and that predeclared five-entry oracle entry-for-entry by exact field equality, not raw-JSON-text comparison and not a universal five-entry rule
    When the session invokes "<rollback entry>" for that transaction in normal mode
    Then exact rollback advances the same record to ROLLED_BACK, restores all non-engine bytes and path kinds to "S0", and creates no second transaction
    And only successful rollback refreshes "<refresh state>" back to exact "S0" with authoritative "D0"

    Examples:
      | surface         | open entry   | preview entry                                                                                                                      | route evidence                                                                              | apply entry         | rollback entry       | refresh state                       |
      | daemon JSON-RPC | project.open | refactor.preview operation=renameMavenModule languageId=java with oldModuleDir, newModuleDir, and newArtifactId arguments    | actual refactor.preview invocation and structured PREVIEW response                            | refactor.apply      | patch.rollback       | saved snapshot and workspace index  |
      | MCP tools       | project_scan | preview_refactoring operation=renameMavenModule languageId=java with oldModuleDir, newModuleDir, and newArtifactId arguments | tools/list advertisement plus actual preview_refactoring result without a symbol placeholder | apply_refactoring   | rollback_refactoring | saved snapshot                      |

  @REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-004 @non-functional-requirement @implemented-and-validated
  Scenario: LSP keeps a versioned module-rename proposal client-managed and non-rollbackable
    Given one API "0.2" LSP session and one independent legacy session are exercised in process only through public LspSession.dispatch; the first negotiates versioned documentChanges over the case workspace and the second does not over its own fresh "S0" copy
    And both sessions have exact "S0", all fixture documents closed, no pending plan, and an empty RefactorKit transaction journal, and before either request the harness independently constructs this ordered five-entry protocol oracle from literal fixture paths and ranges without reading a planner result, response, pending-plan store, or journal:
      | order | protocol entry        | literal existing path                                                | exact zero-based range | exact new text | literal destination path                                             | version field |
      | 1     | text document edit    | catalog-model/pom.xml                                                | 9:14-9:27              | catalog-domain |                                                                      | JSON null     |
      | 2     | text document edit    | pom.xml                                                              | 74:12-74:25            | catalog-domain |                                                                      | JSON null     |
      | 3     | text document edit    | catalog-pricing/pom.xml                                              | 13:18-13:31            | catalog-domain |                                                                      | JSON null     |
      | 4     | rename file operation | catalog-model/pom.xml                                                | absent                 | absent         | catalog-domain/pom.xml                                               | absent        |
      | 5     | rename file operation | catalog-model/src/main/java/com/acme/catalog/legacy/Product.java    | absent                 | absent         | catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java   | absent        |
    When the version-capable client invokes workspace/executeCommand "refactorkit.renameMavenModule" with the qualified request
    Then the real planner returns the canonical plan and the response contains these exact ownership facts:
      | response fact                         | exact value                                  |
      | operation                             | java.renameMavenModule                        |
      | status                                | PREVIEW                                       |
      | refactorkitEditOwnership              | client-managed                                |
      | refactorkitRollbackAvailable          | false                                         |
      | refactorkitDocumentVersionsChecked    | true                                          |
      | edit shape                            | documentChanges                               |
    And the response's ordered documentChanges equals that independent oracle entry-for-entry after resolving each literal path under the disposable root to its file URI; all three text-document versions are JSON null because all fixture documents are closed, and neither rename operation fabricates a document version
    And refactorkitPlanId correlates a retained canonical plan for the distinct REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-005 managed command without requiring private retained-object identity, approval, transaction identity, or managed-write authority in this row
    And proposal creation invokes no operation apply gate, PatchEngine apply, workspace lock, WAL, transaction, state refresh, or rollback
    When a harness-side editor/client simulation applies only those five returned documentChanges in order without invoking "refactorkit.applyPlan", PatchEngine, a transaction-journal helper, or any LSP lifecycle notification
    Then the editor owns those writes and RefactorKit still has no journal record, transaction ID, recovery claim, or rollback capability for them
    And semantic, versioned, diff, or client-application evidence from the proposal is never counted as managed-apply validation
    When the legacy client requests the same structural proposal through public LspSession.dispatch without versioned documentChanges support
    Then LSP refuses with DOCUMENT_VERSION_MISMATCH before returning any edit or retaining any plan, whether or not canonical read-only planning evaluations have already occurred, and changes no workspace byte or journal state

  @REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-005 @functional-requirement @non-functional-requirement @implemented-and-validated
  Scenario: Only LSP applyPlan manages the retained exact plan through preflight, authority, and rollback
    Given three version-capable API "0.2" LSP sessions use three pairwise-distinct normalized disposable roots named primary, dirty-document, and affected-open, each an exact fresh "S0" copy, without asking an editor to apply a proposal
    And before any session initialization the harness independently declares this literal ordered WorkspaceEdit from immutable fixture paths and zero-based ranges without reading a planner result, LSP response, pending-plan store, or journal record:
      | order | FileEdit | literal path                                                         | exact source range | exact new text | literal new path                                                     |
      | 1     | Modify   | catalog-model/pom.xml                                                | 9:14-9:27         | catalog-domain |                                                                      |
      | 2     | Modify   | pom.xml                                                              | 74:12-74:25       | catalog-domain |                                                                      |
      | 3     | Modify   | catalog-pricing/pom.xml                                              | 13:18-13:31       | catalog-domain |                                                                      |
      | 4     | Rename   | catalog-model/pom.xml                                                | absent            | absent         | catalog-domain/pom.xml                                               |
      | 5     | Rename   | catalog-model/src/main/java/com/acme/catalog/legacy/Product.java    | absent            | absent         | catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java   |
    And using only the immutable fixture bytes and that literal edit, the harness precomputes and retains these independent oracles before any LSP request:
      | oracle | exact independently retained meaning                                                                                                                                        |
      | S0     | the complete no-follow baseline file bytes, path kinds, source and auxiliary inventories, authoritative scanner snapshot hash, reactor facts, and every raw POM byte             |
      | C1     | the normalized WorkspaceEditSimulator candidate snapshot hash and complete staged bytes and path kinds produced from S0 by only the literal five-entry edit                       |
      | S1     | the authoritative JavaProjectScanner snapshot hash, complete committed bytes and path kinds, reactor facts, source and auxiliary inventories of the independently formed post-image |
      | D0     | the exact canonical authoritative Maven and JDT diagnostic multiset independently retained for S0 and required unchanged for C1 and S1                                           |
    And the independent schema-version-8 image oracle fixes this exact ordered path, presence, byte-length, and SHA-256 matrix before apply:
      | order | normalized path                                                       | pre-image exact content                                      | post-image exact content                                     |
      | 1     | catalog-model/pom.xml                                                | fixture bytes; 397; d954a47ec44e429cbc94a4e0d5e5fc65fc006ab3335a2b5a26e45d2e7f03088e | absent; null content and contentSha256                        |
      | 2     | pom.xml                                                              | fixture bytes; 2717; f30c9b9cfd394aa431f390a4fb2ff752e37d92f984b8519476f55157c403ac4c | literal-edit bytes; 2718; 1c0fe9b9ca5383628f346877afff52fd6afe00d1223ef391e736c5a3b5ceb23f |
      | 3     | catalog-pricing/pom.xml                                              | fixture bytes; 3337; 88c4ffdf826165d119fe278bdc8946d75bbd76b2784c0e476cbf4f2ae06f9ecc | literal-edit bytes; 3338; 10831298857e61c7f054200c046a277a29467f5d0cddc34f17c800c776fe307f |
      | 4     | catalog-domain/pom.xml                                               | absent; null content and contentSha256                       | moved literal-edit bytes; 398; e9a34b04e5d408a9f2f6444a30ed118d8dd2d88bf880e67ff297f296092ca17b |
      | 5     | catalog-model/src/main/java/com/acme/catalog/legacy/Product.java    | fixture bytes; 94; 7bb9043767dbc5b61b34670812eed2c948eb6f0aca065a2cb76e28183573f663 | absent; null content and contentSha256                        |
      | 6     | catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java   | absent; null content and contentSha256                       | moved fixture bytes; 94; 7bb9043767dbc5b61b34670812eed2c948eb6f0aca065a2cb76e28183573f663 |
    And every image in that oracle independently fixes all schema-version-8 FileImage fields as follows rather than accepting values copied from the journal under test:
      | FileImage field       | exact independent expectation                                                                                                                                                            |
      | path                  | the ordered normalized path above                                                                                                                                                         |
      | content               | the exact independently retained fixture or literal-edit UTF-8 content, or null exactly where absent                                                                                     |
      | contentSha256         | the listed independently computed SHA-256, or null exactly where content is null                                                                                                          |
      | posixPermissions      | the exact no-follow set for an existing pre-image when supported, the exact source-derived set for a present post-image, or null exactly when absent or unsupported                       |
      | lastModifiedMillis    | the exact no-follow pre-image millisecond timestamp for an existing file, null for an absent pre-image, and null for every post-image                                                      |
      | ownerName             | the exact no-follow pre-image owner for an existing file, null for an absent pre-image, and null for every post-image                                                                      |
      | groupName             | the exact no-follow pre-image group when supported, null when absent or unsupported, and null for every post-image                                                                         |
      | userDefinedAttributes | the exact independently captured sorted xattr name/base64-value map when supported, the exact source-retained map for a present post-image, or null exactly when absent or unsupported    |
      | aclEntries            | the exact independently captured ordered ACL type, principal, sorted permissions, and sorted flags when supported, the exact source-retained entries for a present post-image, or null when absent or unsupported |
    And the independent directory oracle is this exact ordered list and is retained through rollback:
      | order | normalized created directory path                         |
      | 1     | catalog-domain                                             |
      | 2     | catalog-domain/src                                         |
      | 3     | catalog-domain/src/main                                    |
      | 4     | catalog-domain/src/main/java                               |
      | 5     | catalog-domain/src/main/java/com                           |
      | 6     | catalog-domain/src/main/java/com/acme                      |
      | 7     | catalog-domain/src/main/java/com/acme/catalog              |
      | 8     | catalog-domain/src/main/java/com/acme/catalog/legacy       |
    And the independent journal oracle fixes integer literal schemaVersion 8, API version "0.2", operation "java.renameMavenModule", exact correlation to the primary opaque plan ID once returned, approval kind EXPLICIT_APPLY, approval surface "lsp-managed-command", approval actor "caller", the literal forwardEdit, the exact image and directory oracles, preSnapshotHash "S0", postSnapshotHash "S1", null failure, APPLIED history "PREPARED, APPLYING, APPLIED", and later ROLLED_BACK history "PREPARED, APPLYING, APPLIED, ROLLING_BACK, ROLLED_BACK"
    And generated plan IDs, the sole transaction ID, and time values are constrained only as opaque correlation tokens or valid monotonic recorded times; every other asserted oracle value is fixed before interaction and is never obtained from the PatchPlan, response, record, current-version constant, or simulator result under test
    And the dirty-document session tracks exact saved "S0" content for both unaffected "reporting-unrelated/src/main/java/com/acme/reporting/ProductReport.java" and affected "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"
    And the affected-open session tracks exact saved "S0" content for affected "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java" while the primary session has no open document
    And retained-object identity, pending-plan lookup and removal, private saved-snapshot identity, and internal preflight, scan, selector, PatchEngine, and refresh ordering or counts that lack public protocol evidence are observed only by fail-closed test-only reflection or instrumentation whose missing or unconsumed observation fails the case; no production observer seam, callback, counter, hook, or visibility widening is permitted
    When all three sessions invoke public LspSession.dispatch for workspace/executeCommand "refactorkit.renameMavenModule" with the qualified request and independently retain the canonical proposal
    Then the three responses expose three nonblank pairwise-distinct opaque refactorkitPlanId values that are retained only as exact correlation tokens without parsing, reconstruction, or format assumptions
    And lookup by each returned ID yields that session's same immutable canonical PatchPlan and authority lease, and every retained normalized WorkspaceEdit equals the predeclared literal five-entry oracle entry-for-entry
    And none of the three returned client-managed documentChanges is applied, so preview performs no managed write, operation-gate construction or provider invocation, PatchEngine apply, workspace lock, WAL, transaction, pending-plan removal, or state refresh
    When an external disk-only change without an LSP lifecycle notification replaces only the final LF byte of the dirty-document root's saved ProductReport file with one ASCII space, leaving the exact tracked "S0" buffer divergent and the affected Product buffer open
    And the dirty-document session invokes public LspSession.dispatch for workspace/executeCommand "refactorkit.applyPlan" with its exact opaque plan ID
    Then LSP returns DOCUMENT_VERSION_MISMATCH identifying the divergent ProductReport before evaluating or reporting the affected-open Product rule
    And after only plan-ID parsing and lookup, the dirty-document preflight refuses before any fresh scanner call, ManagedApplyDiagnosticsGateSelector.select call, operation-gate construction, PatchEngine construction or apply, workspace lock, WAL, target edit, pending-plan removal, or state refresh
    When the dirty-document session repeats "refactorkit.applyPlan" with the same exact opaque plan ID
    Then the same ordered DOCUMENT_VERSION_MISMATCH refusal proves that exact plan remains retained, and the repeated dispatch again performs none of the forbidden post-preflight actions
    When the affected-open session invokes "refactorkit.applyPlan" with its exact opaque plan ID
    Then LSP returns DOCUMENT_VERSION_MISMATCH identifying the affected Product as open even though its tracked content is clean
    And after only plan-ID parsing and lookup, the affected-open preflight refuses before any fresh scanner call, ManagedApplyDiagnosticsGateSelector.select call, operation-gate construction, PatchEngine construction or apply, workspace lock, WAL, target edit, pending-plan removal, or state refresh
    When the affected-open session repeats "refactorkit.applyPlan" with the same exact opaque plan ID
    Then the same affected-open DOCUMENT_VERSION_MISMATCH refusal proves that exact plan remains retained, and the repeated dispatch again performs none of the forbidden post-preflight actions
    And the dirty-document root differs from "S0" only by its declared final-byte disk change, the affected-open and primary roots remain exact "S0", all three journals remain empty, and neither a retained plan ID nor any client-managed proposal is approval, validation, or managed-apply evidence
    When the clean and closed primary session invokes public LspSession.dispatch for workspace/executeCommand "refactorkit.applyPlan" with its own retained opaque plan ID
    Then successful document preflight precedes exactly one fresh JavaProjectScanner scan of the primary root whose result is exact "S0" with every auxiliary POM byte
    And LSP calls the normal five-argument ManagedApplyDiagnosticsGateSelector.select entry exactly once with the same retained PatchPlan, language ID "java", the Java and Kotlin adapters current for that session, and the normal external-gate resolver
    And selection returns the lazy operation-owned gate "java-rename-maven-module-staged-reactor-v1" bound to the same exact retained PatchPlan and authority lease, without selecting or evaluating generic "java-jdt"
    And PatchEngine receives that same exact retained PatchPlan, lease-bearing gate, fresh "S0" scan, and ApplyAuthorization.explicit with surface "lsp-managed-command" and actor "caller", which the journal must record with approval kind EXPLICIT_APPLY
    And the operation-owned gate evaluates exact baseline "S0", simulator candidate "C1", and committed authoritative "S1" in order with the exact unchanged diagnostic multiset "D0", while every generic "java-jdt" provider invocation count remains zero for this apply
    And the committed non-engine workspace equals the independent "S1" byte, path-kind, inventory, reactor, and diagnostic oracles before journal inspection
    And fresh read-only journal inspection finds exactly one record whose integer literal schemaVersion is 8 and whose operation, primary plan correlation, approval, exact deserialized forwardEdit, ordered preImages and postImages, createdDirectories, snapshot hashes, null failure, APPLIED state, and ordered history equal the independent journal oracle field by field
    And that record's postSnapshotHash equals authoritative "S1" and is not equal to simulator candidate "C1"
    And the record reaches APPLIED before LSP returns its same opaque transaction ID, and only after ApplyResult.Applied does LSP remove the primary plan, refresh its saved state to exact "S1" and "D0", and make normal RefactorKit rollback available
    When the clean and closed primary session invokes public LspSession.dispatch for workspace/executeCommand "refactorkit.rollback" with that transaction ID and force absent
    Then ManagedRollbackOutcome.RolledBack occurs for that exact transaction before LSP refreshes or returns success
    And fresh read-only journal inspection still finds exactly one record for the same transaction and plan, now in ROLLED_BACK with ordered history "PREPARED, APPLYING, APPLIED, ROLLING_BACK, ROLLED_BACK", no second transaction, and every forwardEdit, pre-image, post-image, createdDirectories, approval, and snapshot field unchanged from the independent oracle
    And every non-engine byte, path kind, source and auxiliary inventory, reactor fact, snapshot identity, and authoritative diagnostic returns to exact "S0" and "D0", including absence of the complete declared created-directory hierarchy
    And only successful rollback refreshes the primary saved state to exact "S0" and "D0", while the separately qualified SURFACE-004 documentChanges remain client-managed, non-transactional, non-rollbackable by RefactorKit, unapplied in this case, and unqualified as managed-apply evidence
