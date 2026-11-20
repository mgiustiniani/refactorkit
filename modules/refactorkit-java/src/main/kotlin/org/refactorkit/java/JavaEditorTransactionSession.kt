package org.refactorkit.java

import org.refactorkit.core.*
import java.nio.file.Path
import java.util.Collections
import java.util.UUID

enum class JavaEditorTransactionPhase(val wire: String) {
    PREPARED("prepared"), APPLIED("applied"), UNDO_PREPARED("undo-prepared"), UNDONE("undone"), ABORTED("aborted"), RECOVERY_REQUIRED("recovery-required")
}

data class JavaEditorTransaction(
    val id: String,
    val preview: JavaEditorMutationPreview,
    val phase: JavaEditorTransactionPhase,
    val confirmedDocuments: List<ImmutableEditorOverlayDocument>? = null,
    val undo: JavaEditorMutationPreview? = null,
    val refusalCode: String? = null,
)

data class JavaEditorTransactionDocumentSummary(
    val originalPath: Path,
    val finalPath: Path,
    val originalSavedContentSha256: String,
)

data class JavaEditorTransactionSummary(
    val id: String, val planId: String, val planHash: String, val phase: String,
    val baseSnapshotHash: String, val contextHash: String, val sdkHome: String,
    val documents: List<JavaEditorTransactionDocumentSummary>,
)

data class JavaEditorTransactionResult(val transaction: JavaEditorTransaction, val proposal: JavaEditorMutationPreview? = null,
                                     val observedOverlay: ImmutableEditorOverlay? = null)

/** Application broker. The editor owns buffer/resource writes; confirmation proves the closed result. */
class JavaEditorTransactionSession(private val plans: JavaEditorMutationSession, private val overlays: JavaEditorOverlaySession) {
    private val records = LinkedHashMap<String, JavaEditorTransaction>()
    private val stored = LinkedHashMap<String, JavaEditorStoredReceipt>()
    private var journal: JavaEditorTransactionJournal? = null
    fun clear() { records.clear();stored.clear();journal=null }
    fun open(root: Path) {
        clear()
        val selected = JavaEditorTransactionJournal(root)
        val receipts = selected.readAll()
        receipts.forEach { stored[it.id]=it }
        journal=selected
    }
    fun transactions(): List<JavaEditorTransactionSummary> = stored.values.map { receipt ->
        val sources = receipt.sources.associateBy { it.path }
        val edit = receipt.edit.toDomain()
        val documents = receipt.beforeDocuments.sortedBy { it.path }.map { document ->
            val saved = sources[document.path]
                ?: fail("java.editor.journalCorrupt", "Original captured document has no saved source image")
            val original = Path.of(document.path)
            JavaEditorTransactionDocumentSummary(original, finalPath(original, edit), editorPlanSha256(saved.content))
        }
        JavaEditorTransactionSummary(receipt.id, receipt.planId, receipt.planHash, receipt.phase,
            receipt.baseSnapshotHash, receipt.contextHash, receipt.sdkHome, Collections.unmodifiableList(documents))
    }


    fun prepare(id: PlanId, approvedHash: String, request: JavaProjectContextRequest, scan: () -> ProjectSnapshot,
                overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay,
                formatterProfileSha256: String? = null): JavaEditorTransactionResult {
        if (journal == null) fail("java.editor.journalUnavailable", "Editor transactions require an opened durable workspace")
        val preview = plans.lookup(id)
        approveFormatterProfile(preview, formatterProfileSha256)
        if(preview.plan.workspaceEdit.edits.size>256)fail("java.editor.transactionLimitExceeded","Editor transaction edit count exceeds its bounded limit")
        approve(preview, approvedHash)
        if (stored.values.any { it.phase in setOf("prepared","undo-prepared","recovery-required") }) fail("java.editor.transactionPending", "Resolve the current editor transaction before preparing another")
        if (records.values.sumOf { it.preview.retainedUtf8Bytes } + preview.retainedUtf8Bytes > 64L*1024*1024 || records.size >= 16) {
            records.entries.removeIf { it.value.phase == JavaEditorTransactionPhase.UNDONE }
            if (records.values.sumOf { it.preview.retainedUtf8Bytes } + preview.retainedUtf8Bytes > 64L*1024*1024 || records.size >= 16) fail("java.editor.transactionLimitExceeded", "Editor transaction retention exceeds its bounded budget")
        }
        val actual = scan()
        authority(preview, request, actual, scan, after = false)
        val overlay = overlayFactory(actual)
        if (overlay.overlayHash != preview.overlay.overlayHash || overlay.documents != preview.overlay.documents) fail("java.editor.planStale", "Captured editor buffers differ from the approved preview")
        overlays.assertCurrent(overlay)
        if (preview.diff.truncated) fail("java.editor.reviewIncomplete", "Truncated preview cannot authorize editor application")
        requireDiagnostics(preview, overlay.providerSnapshot, preview.plan.diagnosticsBefore)
        requireDiagnostics(preview, preview.candidate, preview.plan.diagnosticsAfterPreview)
        authority(preview, request, scan(), scan, after = false)
        val record = JavaEditorTransaction(UUID.randomUUID().toString(), preview, JavaEditorTransactionPhase.PREPARED)
        persist(record)
        return JavaEditorTransactionResult(record, preview)
    }

    fun confirm(id: String, approvedHash: String, request: JavaProjectContextRequest, scan: () -> ProjectSnapshot,
                overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay,
                formatterProfileSha256: String? = null): JavaEditorTransactionResult {
        val record = requireRecord(id, approvedHash, JavaEditorTransactionPhase.PREPARED, request, scan)
        approveFormatterProfile(record.preview, formatterProfileSha256)
        return observe(record, request, scan, overlayFactory, undo = false)
    }

    fun prepareUndo(id: String, approvedHash: String, request: JavaProjectContextRequest, scan: () -> ProjectSnapshot,
                    overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay): JavaEditorTransactionResult {
        val record = requireRecord(id, approvedHash, JavaEditorTransactionPhase.APPLIED, request, scan)
        val actual = scan()
        authority(record.preview, request, actual, scan, after = true)
        val overlay = overlayFactory(actual)
        if (overlay.documents != record.confirmedDocuments || overlay.providerSnapshot.hash != record.preview.candidate.hash) fail("java.editor.undoStale", "Undo requires the exact confirmed document versions and content")
        overlays.assertCurrent(overlay)
        val inverse = inverse(record.preview, overlay)
        requireDiagnostics(record.preview, inverse.candidate, record.preview.plan.diagnosticsBefore)
        authority(record.preview, request, scan(), scan, after = true)
        val updated = record.copy(phase = JavaEditorTransactionPhase.UNDO_PREPARED, undo = inverse)
        persist(updated)
        return JavaEditorTransactionResult(updated, inverse)
    }

    fun confirmUndo(id: String, approvedHash: String, request: JavaProjectContextRequest, scan: () -> ProjectSnapshot,
                    overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay): JavaEditorTransactionResult {
        val record = requireRecord(id, approvedHash, JavaEditorTransactionPhase.UNDO_PREPARED, request, scan)
        return observe(record, request, scan, overlayFactory, undo = true)
    }

    private fun observe(record: JavaEditorTransaction, request: JavaProjectContextRequest, scan: () -> ProjectSnapshot,
                        overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay, undo: Boolean): JavaEditorTransactionResult {
        return try {
            val preview = record.preview
            val actual = scan()
            authority(preview, request, actual, scan, after = !undo)
            val overlay = overlayFactory(actual)
            val proposal = if (undo) requireNotNull(record.undo) else preview
            val before = if (undo) requireNotNull(record.confirmedDocuments) else preview.overlay.documents
            checkImages(proposal, before, overlay)
            requireDiagnostics(preview, overlay.providerSnapshot, if (undo) preview.plan.diagnosticsBefore else preview.plan.diagnosticsAfterPreview)
            authority(preview, request, scan(), scan, after = !undo)
            overlays.acceptTransaction(before, overlay)
            val updated = record.copy(phase = if (undo) JavaEditorTransactionPhase.UNDONE else JavaEditorTransactionPhase.APPLIED,
                confirmedDocuments = Collections.unmodifiableList(overlay.documents.toList()), refusalCode = null)
            persist(updated)
            JavaEditorTransactionResult(updated, observedOverlay = overlay)
        } catch (failure: JavaContextAuthorityException) {
            val updated = record.copy(phase = JavaEditorTransactionPhase.RECOVERY_REQUIRED, refusalCode = failure.code)
            persist(updated)
            JavaEditorTransactionResult(updated)
        } catch (_: Exception) {
            val updated = record.copy(phase = JavaEditorTransactionPhase.RECOVERY_REQUIRED, refusalCode = "java.editor.confirmationUnavailable")
            persist(updated)
            JavaEditorTransactionResult(updated)
        }
    }

    private fun checkImages(proposal: JavaEditorMutationPreview, before: List<ImmutableEditorOverlayDocument>, overlay: ImmutableEditorOverlay) {
        val expected = before.associate { document -> finalPath(document.path, proposal.plan.workspaceEdit) to document }
        if (overlay.documents.map { it.path }.toSet() != expected.keys || overlay.providerSnapshot.hash != proposal.candidate.hash) fail("java.editor.resultMismatch", "Observed buffers do not match the exact proposed result")
        for (document in overlay.documents) {
            val original = expected.getValue(document.path)
            val content = proposal.candidate.files.single { it.path == document.path }.content
            if (document.content != content || document.version > Int.MAX_VALUE) fail("java.editor.resultMismatch", "Observed result content or version is invalid")
            if (document.path == original.path && (document.version < original.version ||
                    document.content != original.content && document.version <= original.version)) fail("java.editor.versionStale", "Changed documents must advance their original version")
            // A renamed URI has a new version lifecycle, admitted only with exact content and known lineage.
        }
    }

    private fun authority(preview: JavaEditorMutationPreview, request: JavaProjectContextRequest, actual: ProjectSnapshot,
                          scan: () -> ProjectSnapshot, after: Boolean) {
        val sameHome = try { request.sdkHome?.toRealPath() == preview.context.view.sdk.first().platformHome.toRealPath() } catch (_: Exception) { false }
        if (request.expectedContextHash != preview.context.view.contextHash || !sameHome) fail("java.context.contextStale", "Editor transaction requires its original SDK context")
        val diskEdit = WorkspaceEdit(preview.plan.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>())
        val expectedDisk = if (after) WorkspaceEditSimulator.apply(preview.context.snapshot, diskEdit) else preview.context.snapshot
        if (actual.hash != expectedDisk.hash || request.expectedSnapshotHash != actual.hash) fail("java.context.snapshotStale", "Disk inputs differ from the approved editor transaction state")
        fun originalScan(): ProjectSnapshot {
            val current = scan()
            if (current.hash != expectedDisk.hash) fail("java.context.snapshotStale", "Disk authority changed during editor transaction validation")
            return if (!after) current else WorkspaceEditSimulator.apply(current, WorkspaceEdit(diskEdit.edits.reversed().map {
                val rename = it as FileEdit.Rename;FileEdit.Rename(rename.newPath, rename.path)
            }))
        }
        val acquired = JavaBoundContext.acquire(request.copy(expectedSnapshotHash = preview.context.snapshot.hash), ::originalScan)
        if (acquired is JavaBoundContextResolution.Refused) fail(acquired.code, acquired.message)
        preview.context.authority.requireCurrent()
    }

    private fun requireDiagnostics(preview: JavaEditorMutationPreview, candidate: ProjectSnapshot, expected: List<Diagnostic>) {
        val observed = requireNotNull(preview.context.diagnosticsGate().provider).invoke(candidate)
        fun identities(values: List<Diagnostic>) = values.groupingBy { listOf(it.severity, it.code, it.message, it.location, it.evidence, it.category, it.locationPrecision) }.eachCount()
        if (identities(observed) != identities(expected)) fail("java.editor.diagnosticsChanged", "SDK diagnostics differ from the approved closed result")
    }

    private fun inverse(preview: JavaEditorMutationPreview, after: ImmutableEditorOverlay): JavaEditorMutationPreview {
        val modifications = preview.overlay.documents.mapNotNull { original ->
            val path = finalPath(original.path, preview.plan.workspaceEdit)
            val document = requireNotNull(after.document(path))
            if (document.content == original.content) null else FileEdit.Modify(path, listOf(TextEdit(fullRange(document.content), original.content)))
        }
        val renames = preview.plan.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>().reversed().map { FileEdit.Rename(it.newPath, it.path) }
        val edit = WorkspaceEdit(modifications + renames)
        val candidate = WorkspaceEditSimulator.apply(after.providerSnapshot, edit)
        if (candidate.hash != preview.overlay.providerSnapshot.hash) fail("java.editor.inverseUnavailable", "Inverse does not restore exact original dirty buffers")
        val plan = preview.plan.copy(snapshotHash = after.providerSnapshot.hash, workspaceEdit = edit, summary = "Undo editor transaction: ${preview.plan.summary}",
            affectedFiles = edit.edits.map { it.path }.toSet(), diagnosticsBefore = preview.plan.diagnosticsAfterPreview, diagnosticsAfterPreview = preview.plan.diagnosticsBefore, authorityLease = null)
        return preview.copy(overlay = after, plan = plan, candidate = candidate,
            planHash = editorPlanSha256("java-editor-inverse-v1\u0000${preview.planHash}\u0000${after.overlayHash}\u0000${WorkspaceEditIdentity.sha256(edit)}"),
            diff = PatchDiffRenderer.render(after.providerSnapshot, edit))
    }

    fun recover(id: String, approvedHash: String, request: JavaProjectContextRequest, scan: () -> ProjectSnapshot,
                overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay): JavaEditorTransactionResult {
        val record=records[id] ?: hydrate(id,request,scan)
        approve(record.preview,approvedHash)
        if(record.phase in setOf(JavaEditorTransactionPhase.UNDONE,JavaEditorTransactionPhase.ABORTED))fail("java.editor.transactionState","Transaction is already terminal")
        return try {
            val actual=scan()
            val source=requireNotNull(stored[id])
            originalSnapshot(source,actual)
            requireOriginalContext(source,request,scan)
            val overlay=overlayFactory(actual)
            overlays.assertCurrent(overlay)
            val before=record.preview.overlay.documents
            val current=overlay.documents.associateBy { it.path }
            val sourceNames=record.preview.context.snapshot.files.map { it.path }.toSet()
            val mapping=before.associate { old ->
                val final=finalPath(old.path,record.preview.plan.workspaceEdit)
                val present=listOf(old.path,final).distinct().filter { it in sourceNames || actual.files.any { f->f.path==it } }
                    .filter { it in current && actual.files.any { f->f.path==it } }
                if(present.size!=1)fail("java.editor.recoveryUnknown","Recovery cannot identify one exact current URI per original document")
                old.path to present.single()
            }
            if(mapping.values.toSet()!=current.keys)fail("java.editor.recoveryUnknown","Recovery requires the complete original document lineage")
            for(old in before){
                val path=mapping.getValue(old.path);val now=current.getValue(path)
                val final=finalPath(old.path,record.preview.plan.workspaceEdit)
                val expected=record.preview.candidate.files.single { it.path==final }.content
                val saved=record.preview.context.snapshot.files.single { it.path==old.path }.content
                if(now.content !in setOf(old.content,expected,saved))fail("java.editor.recoveryUnknown","Unknown external content cannot be overwritten by recovery")
            }
            val originalsRestored=actual.hash==record.preview.context.snapshot.hash && before.all { old->
                mapping.getValue(old.path)==old.path && current.getValue(old.path).content==old.content
            }
            if(originalsRestored){
                requireDiagnostics(record.preview,overlay.providerSnapshot,record.preview.plan.diagnosticsBefore)
                val updated=record.copy(phase=JavaEditorTransactionPhase.ABORTED,confirmedDocuments=overlay.documents,refusalCode=null)
                requireOriginalContext(source,request,scan);persist(updated);overlays.acceptTransaction(before,overlay)
                JavaEditorTransactionResult(updated,observedOverlay=overlay)
            } else {
                val modifications=before.mapNotNull { old->
                    val path=mapping.getValue(old.path);val now=current.getValue(path)
                    if(now.content==old.content)null else FileEdit.Modify(path,listOf(TextEdit(fullRange(now.content),old.content)))
                }
                val renames=before.mapNotNull { old-> val path=mapping.getValue(old.path);if(path==old.path)null else FileEdit.Rename(path,old.path) }
                val edit=WorkspaceEdit(modifications+renames)
                val candidate=WorkspaceEditSimulator.apply(overlay.providerSnapshot,edit)
                if(candidate.hash!=record.preview.overlay.providerSnapshot.hash)fail("java.editor.recoveryUnknown","Recovery inverse does not restore the exact original closed snapshot")
                requireDiagnostics(record.preview,candidate,record.preview.plan.diagnosticsBefore)
                val plan=record.preview.plan.copy(snapshotHash=overlay.providerSnapshot.hash,workspaceEdit=edit,summary="Recover editor transaction: ${record.preview.plan.summary}",affectedFiles=edit.affectedFiles(),authorityLease=null)
                val inverse=record.preview.copy(overlay=overlay,plan=plan,candidate=candidate,
                    planHash=editorPlanSha256("java-editor-recovery-v1\u0000${record.preview.planHash}\u0000${overlay.overlayHash}\u0000${WorkspaceEditIdentity.sha256(edit)}"),
                    diff=PatchDiffRenderer.render(overlay.providerSnapshot,edit))
                val updated=record.copy(phase=JavaEditorTransactionPhase.UNDO_PREPARED,confirmedDocuments=overlay.documents,undo=inverse,refusalCode=null)
                requireOriginalContext(source,request,scan);persist(updated)
                JavaEditorTransactionResult(updated,inverse)
            }
        }catch(failure:JavaContextAuthorityException){
            val updated=record.copy(phase=JavaEditorTransactionPhase.RECOVERY_REQUIRED,refusalCode=failure.code)
            persist(updated);JavaEditorTransactionResult(updated)
        }
    }

    private fun persist(record:JavaEditorTransaction) {
        val preview=record.preview
        fun source(file:SourceFile)=JavaEditorStoredSource(ProtocolPath.serialize(file.path),file.content,file.languageId)
        fun documents(values:List<ImmutableEditorOverlayDocument>?)=values?.map { JavaEditorStoredDocument(ProtocolPath.serialize(it.path),it.version,it.content) }
        val receipt=JavaEditorStoredReceipt(id=record.id,root=preview.context.snapshot.workspace.root.toString(),phase=record.phase.wire,
            sdkHome=preview.context.view.sdk.first().platformHome.toString(),contextHash=preview.context.view.contextHash,baseSnapshotHash=preview.context.snapshot.hash,
            sources=preview.context.snapshot.files.map(::source),auxiliary=preview.context.snapshot.auxiliaryFiles.map(::source),beforeDocuments=requireNotNull(documents(preview.overlay.documents)),
            planId=preview.plan.id.value,planHash=preview.planHash,selectionHash=preview.selectionHash,
            operation=preview.plan.operation,summary=preview.plan.summary,confidence=preview.plan.confidence,riskLevel=preview.plan.riskLevel.name,evidence=preview.plan.evidence.name,
            warnings=preview.plan.warnings,edit=preview.plan.workspaceEdit.toDto(),providerHash=preview.overlay.providerSnapshot.hash,candidateHash=preview.candidate.hash,
            diagnosticsBeforeHash=diagnosticHash(preview.plan.diagnosticsBefore),diagnosticsAfterHash=diagnosticHash(preview.plan.diagnosticsAfterPreview),
            formatterProfilePath=preview.formatterProfilePath,formatterProfileName=preview.formatterProfileName,
            formatterProfileSha256=preview.formatterProfileSha256,
            confirmedDocuments=documents(record.confirmedDocuments),undoDocuments=documents(record.undo?.overlay?.documents),undoEdit=record.undo?.plan?.workspaceEdit?.toDto(),undoPlanHash=record.undo?.planHash,refusalCode=record.refusalCode)
        requireNotNull(journal).write(receipt,stored[record.id])
        stored[record.id]=receipt;records[record.id]=record
    }

    private fun originalSnapshot(receipt:JavaEditorStoredReceipt,actual:ProjectSnapshot):ProjectSnapshot {
        fun file(source:JavaEditorStoredSource)=SourceFile(Path.of(source.path),source.content,source.languageId)
        val original=actual.copy(files=receipt.sources.map(::file),auxiliaryFiles=receipt.auxiliary.map(::file),semanticAnalysisAuthority=null)
        if(original.hash!=receipt.baseSnapshotHash)fail("java.context.snapshotStale","Build/classpath evidence differs from the original editor receipt")
        val renames=receipt.edit.toDomain().edits.filterIsInstance<FileEdit.Rename>()
        var disk=original
        if(disk.hash==actual.hash)return original
        for(rename in renames){disk=WorkspaceEditSimulator.apply(disk,WorkspaceEdit(listOf(rename)));if(disk.hash==actual.hash)return original}
        fail("java.context.snapshotStale","Saved files do not match a known structural editor transaction state")
    }
    private fun requireOriginalContext(receipt:JavaEditorStoredReceipt,request:JavaProjectContextRequest,scan:()->ProjectSnapshot):JavaBoundContext {
        if(request.expectedContextHash!=receipt.contextHash || request.expectedSnapshotHash!=scan().hash)fail("java.context.contextStale","Recovery requires original SDK/context and current saved snapshot")
        val acquired=JavaBoundContext.acquire(request.copy(expectedSnapshotHash=receipt.baseSnapshotHash),{ originalSnapshot(receipt,scan()) })
        if(acquired is JavaBoundContextResolution.Refused)fail(acquired.code,acquired.message)
        return (acquired as JavaBoundContextResolution.Available).context
    }
    private fun hydrate(id:String,request:JavaProjectContextRequest,scan:()->ProjectSnapshot):JavaEditorTransaction {
        val receipt=stored[id] ?: fail("java.editor.transactionNotFound","Durable editor receipt is unavailable")
        val context=requireOriginalContext(receipt,request,scan)
        fun documents(values:List<JavaEditorStoredDocument>)=values.map { ImmutableEditorOverlayDocument(Path.of(it.path),it.version,it.content) }
        val before=ImmutableEditorOverlay.create(context.snapshot,documents(receipt.beforeDocuments),"java")
        val edit=receipt.edit.toDomain();val candidate=WorkspaceEditSimulator.apply(before.providerSnapshot,edit)
        val identityParts=listOf("java-editor-refactoring-v1",context.view.contextHash,context.snapshot.hash,
            before.providerSnapshot.hash,before.overlayHash,WorkspaceEditIdentity.sha256(edit),candidate.hash,receipt.selectionHash.orEmpty())
        val identity=editorPlanSha256((if(receipt.formatterProfileSha256==null)identityParts else identityParts+receipt.formatterProfileSha256).joinToString("\u0000"))
        if(identity!=receipt.planHash || before.providerSnapshot.hash!=receipt.providerHash || candidate.hash!=receipt.candidateHash)fail("java.editor.journalCorrupt","Retained editor edit identity cannot be reconstructed")
        val gate=requireNotNull(context.diagnosticsGate().provider);val diagnosticsBefore=gate(before.providerSnapshot);val diagnosticsAfter=gate(candidate)
        if(diagnosticHash(diagnosticsBefore)!=receipt.diagnosticsBeforeHash || diagnosticHash(diagnosticsAfter)!=receipt.diagnosticsAfterHash)fail("java.editor.diagnosticsChanged","Recovery SDK diagnostics differ from the approved receipt")
        val plan=PatchPlan(id=PlanId(receipt.planId),operation=receipt.operation,status=PatchStatus.PREVIEW,snapshotHash=before.providerSnapshot.hash,confidence=receipt.confidence,
            summary=receipt.summary,affectedFiles=edit.affectedFiles(),workspaceEdit=edit,warnings=receipt.warnings,riskLevel=RiskLevel.valueOf(receipt.riskLevel),evidence=RefactoringEvidence.valueOf(receipt.evidence),
            diagnosticsBefore=diagnosticsBefore,diagnosticsAfterPreview=diagnosticsAfter)
        val bytes=(context.snapshot.files+before.providerSnapshot.files+candidate.files).sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() }
        val preview=JavaEditorMutationPreview(context,before,plan,candidate,identity,PatchDiffRenderer.render(before.providerSnapshot,edit),bytes,
            receipt.selectionHash,receipt.formatterProfilePath,receipt.formatterProfileName,receipt.formatterProfileSha256)
        val confirmed=receipt.confirmedDocuments?.let(::documents)
        val inverse=if(receipt.undoDocuments==null)null else {
            val actual=scan();val original=originalSnapshot(receipt,actual)
            val undoDocuments=documents(receipt.undoDocuments)
            val renames=receipt.edit.toDomain().edits.filterIsInstance<FileEdit.Rename>()
            var disk=original
            var input:ProjectSnapshot?=if(undoDocuments.all { d->disk.files.any { it.path==d.path } })disk else null
            for(rename in renames){
                disk=WorkspaceEditSimulator.apply(disk,WorkspaceEdit(listOf(rename)))
                if(input==null && undoDocuments.all { d->disk.files.any {it.path==d.path} })input=disk
            }
            val overlay=ImmutableEditorOverlay.create(input ?: fail("java.editor.journalCorrupt","Durable inverse URI lineage is invalid"),undoDocuments,"java")
            val inverseEdit=requireNotNull(receipt.undoEdit).toDomain();val restored=WorkspaceEditSimulator.apply(overlay.providerSnapshot,inverseEdit)
            if(restored.hash!=before.providerSnapshot.hash)fail("java.editor.journalCorrupt","Durable inverse fails exact original-buffer restoration")
            preview.copy(overlay=overlay,plan=plan.copy(snapshotHash=overlay.providerSnapshot.hash,workspaceEdit=inverseEdit,authorityLease=null),candidate=restored,
                planHash=requireNotNull(receipt.undoPlanHash),diff=PatchDiffRenderer.render(overlay.providerSnapshot,inverseEdit))
        }
        val record=JavaEditorTransaction(id,preview,JavaEditorTransactionPhase.entries.single { it.wire==receipt.phase },confirmed,inverse,receipt.refusalCode)
        records[id]=record;return record
    }
    private fun diagnosticHash(values:List<Diagnostic>):String=editorPlanSha256(values.map { d-> listOf(d.severity,d.code,d.message,d.location?.path,
        d.location?.range?.start?.line,d.location?.range?.start?.character,d.location?.range?.end?.line,d.location?.range?.end?.character,d.evidence,d.category,d.locationPrecision).joinToString("\u0000") }.sorted().joinToString("\u0001"))

    private fun fullRange(content: String): SourceRange {
        val line = content.count { it == '\n' };val last = content.lastIndexOf('\n')
        return SourceRange(SourcePosition(0,0), SourcePosition(line, content.length-last-1))
    }
    private fun finalPath(path: Path, edit: WorkspaceEdit): Path = edit.edits.filterIsInstance<FileEdit.Rename>().fold(path) { current, rename -> if (current == rename.path) rename.newPath else current }
    private fun approve(preview: JavaEditorMutationPreview, hash: String) { if (hash != preview.planHash) fail("java.editor.approvalMismatch", "Approval must bind the exact retained plan hash") }
    private fun approveFormatterProfile(preview: JavaEditorMutationPreview, supplied: String?) {
        val expected=preview.formatterProfileSha256
        if(expected==null) {
            if(supplied!=null)fail("java.formatterProfile.identityStale","Editor plan did not select a formatter profile")
            return
        }
        if(supplied==null)fail("java.formatterProfile.approvalMissing","Editor approval must include the selected formatter profile hash")
        if(supplied!=expected)fail("java.formatterProfile.identityStale","Editor formatter profile differs from the approved preview")
    }
    private fun requireRecord(id: String, approvedHash: String, phase: JavaEditorTransactionPhase, request: JavaProjectContextRequest, scan: () -> ProjectSnapshot): JavaEditorTransaction {
        val record = records[id] ?: hydrate(id,request,scan)
        approve(record.preview, approvedHash)
        if (record.phase != phase) fail("java.editor.transactionState", "Editor transaction is not in the required phase")
        return record
    }
    private fun fail(code: String, message: String): Nothing = throw JavaContextAuthorityException(code,message)

}
