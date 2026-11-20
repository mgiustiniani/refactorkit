package org.refactorkit.java

import org.refactorkit.core.*
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

enum class JavaEditorPreviewOperation(val wire: String) {
    RENAME_CLASS("renameClass"), RENAME_MEMBER("renameMember"), FORMAT_FILE("formatFile"), ORGANIZE_IMPORTS("organizeImports");
    companion object {
        fun fromWire(value: String) = entries.singleOrNull { it.wire == value }
            ?: throw JavaContextAuthorityException("java.editor.operationUnsupported", "Operation has no qualified dirty editor preview")
    }
}

enum class JavaEditorMutationMode(val wire: String, val applyAvailable: Boolean) {
    READ_ONLY_PREVIEW("read-only-preview",false), DURABLE_EDITOR_OWNED("durable-editor-owned",true);
    companion object {
        fun fromWire(value:String)=entries.singleOrNull {it.wire==value}
            ?: throw IllegalArgumentException("Unknown editor mutation admission mode")
    }
}

data class JavaEditorMutationPreview(
    val context: JavaBoundContext,
    val overlay: ImmutableEditorOverlay,
    val plan: PatchPlan,
    val candidate: ProjectSnapshot,
    val planHash: String,
    val diff: PatchDiffResult,
    val retainedUtf8Bytes: Long,
    val selectionHash: String? = null,
    val formatterProfilePath: String? = null,
    val formatterProfileName: String? = null,
    val formatterProfileSha256: String? = null,
)

/** Application-owned dirty plans. They are never admitted to PatchEngine's disk apply store. */
class JavaEditorMutationSession(private val overlays: JavaEditorOverlaySession) {
    private val retained = LinkedHashMap<PlanId, JavaEditorMutationPreview>(16, 0.75f, true)
    private var retainedBytes = 0L
    fun clear() { retained.clear();retainedBytes=0 }
    internal fun lookup(id: PlanId): JavaEditorMutationPreview = retained[id]
        ?: throw JavaContextAuthorityException("java.editor.planNotFound", "Editor plan is no longer retained")

    fun preview(contextRequest: JavaProjectContextRequest, operation: JavaEditorPreviewOperation,
                symbol: String?, arguments: Map<String, String>, scan: () -> ProjectSnapshot,
                overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay,
                cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE,
                selectionClaim:JavaEditorMutationSelection?=null): JavaEditorMutationPreview {
        val selected = arguments["file"]?.let { path ->
            val supplied=Path.of(path);if(supplied.isAbsolute) scan().workspace.root.relativize(supplied.normalize()) else supplied.normalize()
        }
        val acquired = JavaBoundContext.acquire(contextRequest, scan, cancellation)
        if(acquired is JavaBoundContextResolution.Refused) throw JavaContextAuthorityException(acquired.code,acquired.message)
        val base=(acquired as JavaBoundContextResolution.Available).context
        val closed=overlayFactory(base.snapshot)
        if(closed.documents.any { it.version>Int.MAX_VALUE }) throw JavaContextAuthorityException("java.editor.versionUnsupported","Editor edits require LSP integer document versions")
        val query=if(selectionClaim==null)JavaEditorQueryRequest(JavaEditorQueryKind.DOCUMENT_SYMBOLS,selected ?: closed.documents.first().path,SourcePosition(0,0),1)
            else JavaEditorQueryRequest(JavaEditorQueryKind.MUTATION_SELECTION,selectionClaim.selection.path,selectionClaim.selection.position,1)
        val checked=overlays.query(contextRequest,query,scan,{ closed },cancellation)
        if(checked is JavaEditorQueryResult.Refused) throw JavaContextAuthorityException(checked.code,checked.message)
        val currentSelection=if(selectionClaim==null)null else {
            val payload=(checked as JavaEditorQueryResult.Available).payload as JavaEditorQueryPayload.MutationSelection
            if(payload.value!=selectionClaim)throw JavaContextAuthorityException("java.selection.selectorStale","Editor selector differs from current closed document authority")
            JavaMutationSelectionService.validate(payload.boundContext,selectionClaim.selection,operation.wire,symbol,arguments)
        }
        val provider=AuthoritativeDiagnosticsEvaluation(closed.providerSnapshot,emptyList()).snapshot
            .copy(semanticAnalysisAuthority=base.authority)
        val formatterProfile = try { JavaFormatterProfile.fromArguments(arguments) }
            catch (failure: Exception) { throw JavaContextAuthorityException("java.formatterProfile.invalid", failure.message ?: "Invalid selected formatter profile") }
        if (formatterProfile != null && operation != JavaEditorPreviewOperation.FORMAT_FILE) {
            throw JavaContextAuthorityException("java.formatterProfile.invalid", "Selected formatter profile is supported only for Java formatFile previews")
        }
        val args=Collections.unmodifiableMap(arguments.toMap() + if(selected==null) emptyMap() else mapOf("file" to ProtocolPath.serialize(selected)))
        val request=RefactoringRequest(operation.wire,symbolId=(currentSelection?.symbolId ?: symbol)?.let(::SymbolId),arguments=args,snapshot=provider)
        // Formatting reads the original module's preferences; its diagnostics already
        // stage closed source content. Binding planners need a full private source tree.
        if(cancellation.isCancellationRequested()) throw JavaContextAuthorityException("intelligence.cancelled","Editor preview was cancelled")
        val raw=try {
            if(operation==JavaEditorPreviewOperation.FORMAT_FILE) JavaLanguageAdapter().applyRefactoring(request)
            else JavaLanguageAdapter().withDiagnosticsOverlay(provider) { staged -> JavaLanguageAdapter().applyRefactoring(request.copy(snapshot=staged)) }
        } catch(failure:JavaContextAuthorityException) { throw failure }
        catch(_:Exception) { throw JavaContextAuthorityException("java.editor.planUnavailable","Closed editor plan could not be generated") }
        if(cancellation.isCancellationRequested()) throw JavaContextAuthorityException("intelligence.cancelled","Editor preview was cancelled")
        if(raw.status==PatchStatus.REFUSED) throw JavaContextAuthorityException(raw.refusalCode ?: "java.editor.planRefused",raw.summary)
        if(raw.evidence==RefactoringEvidence.LEXICAL_FALLBACK) throw JavaContextAuthorityException("java.editor.semanticUnavailable","Dirty preview requires deterministic semantic edits")
        val edit=WorkspaceEditSimulator.normalize(raw.workspaceEdit)
        if(edit.edits.any { it !is FileEdit.Modify && it !is FileEdit.Rename }) throw JavaContextAuthorityException("java.editor.structuralUnsupported","Dirty preview supports text edits and source-file rename")
        val required=edit.edits.map { it.path.normalize() }.toSet()
        if(required.any { closed.document(it)==null }) throw JavaContextAuthorityException("java.editor.documentsMissing","Every affected source must be included in the versioned editor overlay")
        val candidate=WorkspaceEditSimulator.apply(provider,edit)
        val gate=base.diagnosticsGate()
        val before=requireNotNull(gate.provider).invoke(provider)
        val after=requireNotNull(gate.provider).invoke(candidate)
        val plan=raw.copy(snapshotHash=provider.hash,workspaceEdit=edit,diagnosticsBefore=before,diagnosticsAfterPreview=after,
            authorityLease=raw.authorityLease?.let { previous -> OperationAuthorityLease(
                kind=previous.kind,operation=previous.operation,snapshotHash=provider.hash,evidenceHash=previous.evidenceHash,
                evidenceCompleteness=previous.evidenceCompleteness,workspaceEditSha256=WorkspaceEditIdentity.sha256(edit),
                requiredClasspathEvidence=previous.requiredClasspathEvidence,requiredFileEvidence=previous.requiredFileEvidence,
                attributes=previous.attributes)
            })
        base.verifyAfterQuery(scan)?.let { throw JavaContextAuthorityException(it.code,it.message) }
        val identityParts=listOf("java-editor-refactoring-v1",base.view.contextHash,base.snapshot.hash,
            provider.hash,closed.overlayHash,WorkspaceEditIdentity.sha256(edit),candidate.hash,selectionClaim?.selectorHash.orEmpty())
        // Preserve legacy plan identities when no explicit IDE profile was selected.
        val identity=editorPlanSha256((if(formatterProfile==null) identityParts else identityParts+formatterProfile.sha256).joinToString("\u0000"))
        val diff=PatchDiffRenderer.render(provider.copy(files=provider.trackedFiles,auxiliaryFiles=emptyList()),edit)
        val bytes=(base.snapshot.files + provider.files + candidate.files).sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() }
        if(bytes>MAX_RETAINED_BYTES) throw JavaContextAuthorityException("java.editor.planLimitExceeded","Closed editor plan exceeds the bounded retention budget")
        while(retained.isNotEmpty() && (retained.size>=16 || retainedBytes+bytes>MAX_RETAINED_BYTES)) {
            val oldest=retained.entries.iterator();retainedBytes-=oldest.next().value.retainedUtf8Bytes;oldest.remove()
        }
        return JavaEditorMutationPreview(base,closed,plan,candidate,identity,diff,bytes,selectionClaim?.selectorHash,
            formatterProfile?.path,formatterProfile?.profileName,formatterProfile?.sha256).also {
            retained[it.plan.id]=it;retainedBytes+=bytes
        }
    }
    companion object { private const val MAX_RETAINED_BYTES=64L*1024*1024 }
}

internal fun editorPlanSha256(value: String)=MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
