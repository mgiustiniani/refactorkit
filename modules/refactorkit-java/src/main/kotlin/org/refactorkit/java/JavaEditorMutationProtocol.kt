package org.refactorkit.java

import kotlinx.serialization.json.*
import org.refactorkit.core.*

/** Explicit protocol ACL: source/editor domain models to versioned WorkspaceEdit. */
object JavaEditorMutationProtocol {
    const val VERSION="java-editor-refactoring-v1"
    fun renderTransaction(result: JavaEditorTransactionResult): JsonObject = buildJsonObject {
        val record = result.transaction
        val preview = record.preview
        put("schemaVersion","java-editor-transaction-v1");put("status",record.phase.wire)
        put("editOwnership","editor");put("transactionId",record.id)
        put("planId",preview.plan.id.value);put("approvedPlanHash",preview.planHash)
        put("baseSnapshotHash",preview.context.snapshot.hash);put("contextHash",preview.context.view.contextHash)
        put("platformIdentities",buildJsonArray { preview.context.view.sdk.forEach { add(it.identitySha256) } })
        put("durableRecoveryAvailable",true)
        record.refusalCode?.let { put("refusalCode",it) }
        result.proposal?.let { proposal ->
            val rendered = render(proposal)
            put("workspaceEdit",rendered.getValue("workspaceEdit"))
            put("sourceAuthority",rendered.getValue("sourceAuthority"))
            put("expectedProviderSnapshotHash",proposal.candidate.hash)
            put("proposalHash",proposal.planHash)
        }
        result.observedOverlay?.let { overlay ->
            put("providerSnapshotHash",overlay.providerSnapshot.hash)
            put("documentAuthorities",buildJsonArray { overlay.documents.forEach { document -> add(buildJsonObject {
                put("path",ProtocolPath.serialize(document.path));put("version",document.version)
                put("contentSha256",requireNotNull(overlay.authority(document.path)).contentSha256)
            }) } })
        }
    }
    fun renderTransactions(transactions: List<JavaEditorTransactionSummary>): JsonObject = buildJsonObject {
        put("schemaVersion","java-editor-transactions-v1")
        put("transactions",buildJsonArray { transactions.forEach { record -> add(buildJsonObject {
            put("transactionId",record.id);put("planId",record.planId);put("approvedPlanHash",record.planHash)
            put("status",record.phase);put("baseSnapshotHash",record.baseSnapshotHash);put("contextHash",record.contextHash);put("sdkHome",record.sdkHome)
            put("revalidationRequired",record.phase !in setOf("undone","aborted"))
            put("documents",buildJsonArray { record.documents.forEach { document -> add(buildJsonObject {
                put("originalPath",ProtocolPath.serialize(document.originalPath))
                put("finalPath",ProtocolPath.serialize(document.finalPath))
                put("originalSavedContentSha256",document.originalSavedContentSha256)
            }) } })
        }) } })
    }
    fun render(preview: JavaEditorMutationPreview, mode:JavaEditorMutationMode=JavaEditorMutationMode.READ_ONLY_PREVIEW): JsonObject = buildJsonObject {
        val base=preview.context.snapshot
        put("schemaVersion",VERSION);put("status","preview");put("editOwnership","editor");put("applyAvailable",mode.applyAvailable)
        if(mode.applyAvailable)put("editorMutationMode",mode.wire)
        put("requiresUserApproval",true);put("riskLevel",preview.plan.riskLevel.name);put("evidence",preview.plan.evidence.name)
        put("planId",preview.plan.id.value);put("planHash",preview.planHash);put("operation",preview.plan.operation)
        put("summary",preview.plan.summary);put("baseSnapshotHash",base.hash);put("contextHash",preview.context.view.contextHash)
        put("contextSchemaVersion",preview.context.contextSchemaVersion)
        put("platformIdentities",buildJsonArray { preview.context.view.sdk.forEach { add(it.identitySha256) } })
        put("providerSnapshotHash",preview.overlay.providerSnapshot.hash);put("expectedProviderSnapshotHash",preview.candidate.hash)
        put("sourceAuthority",buildJsonObject {
            put("kind","immutable-editor-overlay");put("baseSnapshotHash",preview.overlay.baseSnapshotHash);put("overlayHash",preview.overlay.overlayHash)
            put("documents",buildJsonArray { preview.overlay.documents.forEach { d -> add(buildJsonObject {
                put("path",ProtocolPath.serialize(d.path));put("version",d.version)
                put("contentSha256",requireNotNull(preview.overlay.authority(d.path)).contentSha256)
            }) } })
        })
        put("workspaceEdit",buildJsonObject { put("documentChanges",buildJsonArray {
            preview.plan.workspaceEdit.edits.forEach { edit ->
                fun uri(path:java.nio.file.Path)=base.workspace.root.resolve(path).normalize().toUri().toASCIIString()
                when(edit) {
                    is FileEdit.Modify -> add(buildJsonObject {
                        put("textDocument",buildJsonObject { put("uri",uri(edit.path));put("version",requireNotNull(preview.overlay.document(edit.path)).version) })
                        put("edits",buildJsonArray { edit.textEdits.forEach { t -> add(buildJsonObject {
                            put("range",buildJsonObject { put("start",point(t.range.start));put("end",point(t.range.end)) });put("newText",t.newText)
                        }) } })
                    })
                    is FileEdit.Rename -> add(buildJsonObject {
                        put("kind","rename");put("oldUri",uri(edit.path));put("newUri",uri(edit.newPath))
                        put("options",buildJsonObject { put("overwrite",false);put("ignoreIfExists",false) })
                    })
                    else -> error("Editor preview admitted unsupported resource operation")
                }
            }
        }) })
        put("renderedDiff",preview.diff.renderedDiff);put("diffTruncated",preview.diff.truncated)
        put("diffTruncationReasons",buildJsonArray { preview.diff.truncationReasons.forEach { add(it) } })
        put("warnings",buildJsonArray { preview.plan.warnings.forEach { add(it) } })
        val before=diagnostics(preview.plan.diagnosticsBefore);val after=diagnostics(preview.plan.diagnosticsAfterPreview)
        put("diagnosticsBefore",before);put("diagnosticsAfterPreview",after)
        put("diagnosticsTruncated",before.size<preview.plan.diagnosticsBefore.size || after.size<preview.plan.diagnosticsAfterPreview.size ||
            (preview.plan.diagnosticsBefore+preview.plan.diagnosticsAfterPreview).any { it.message.length>ProtocolLimits.MAX_DIAGNOSTIC_MESSAGE_CHARS })
    }
    private fun point(p:SourcePosition)=buildJsonObject { put("line",p.line);put("character",p.character) }
    private fun diagnostics(values:List<Diagnostic>):JsonArray {
        val items=mutableListOf<JsonObject>();var bytes=0
        for(d in values) {
            val item=buildJsonObject {
                put("severity",d.severity.name.lowercase());put("message",d.message.take(ProtocolLimits.MAX_DIAGNOSTIC_MESSAGE_CHARS));d.code?.let { put("code",it) }
                d.location?.let { put("path",ProtocolPath.serialize(it.path));put("range",buildJsonObject { put("start",point(it.range.start));put("end",point(it.range.end)) }) }
            }
            val size=item.toString().toByteArray(Charsets.UTF_8).size
            if(items.size>=ProtocolLimits.MAX_PREVIEW_DIAGNOSTICS || bytes+size>ProtocolLimits.MAX_PREVIEW_DIAGNOSTIC_BYTES) break
            items+=item;bytes+=size
        }
        return JsonArray(items)
    }
}
