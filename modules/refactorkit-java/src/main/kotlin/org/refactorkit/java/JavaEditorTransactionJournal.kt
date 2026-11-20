package org.refactorkit.java

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.refactorkit.core.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

internal data class JavaEditorStoredSource(val path: String, val content: String, val languageId: String)
internal data class JavaEditorStoredDocument(val path: String, val version: Long, val content: String)
internal data class JavaEditorStoredReceipt(
    val schemaVersion: String = "java-editor-journal-v1",
    val id: String, val root: String, val phase: String,
    val sdkHome: String, val contextHash: String, val baseSnapshotHash: String,
    val sources: List<JavaEditorStoredSource>, val auxiliary: List<JavaEditorStoredSource>,
    val beforeDocuments: List<JavaEditorStoredDocument>,
    val planId: String, val planHash: String, val selectionHash: String?,
    val operation: String, val summary: String, val confidence: Double, val riskLevel: String, val evidence: String,
    val warnings: List<String>, val edit: WorkspaceEditDto,
    val providerHash: String, val candidateHash: String,
    val diagnosticsBeforeHash: String, val diagnosticsAfterHash: String,
    val confirmedDocuments: List<JavaEditorStoredDocument>?,
    val undoDocuments: List<JavaEditorStoredDocument>?, val undoEdit: WorkspaceEditDto?, val undoPlanHash: String?,
    val refusalCode: String?,
    val formatterProfilePath: String? = null, val formatterProfileName: String? = null, val formatterProfileSha256: String? = null,
)

/** Durable, checksummed application journal, separate from engine-owned disk transactions. */
internal class JavaEditorTransactionJournal(private val root: Path) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val directory = root.resolve(".refactorkit/editor-transactions")

    fun readAll(): List<JavaEditorStoredReceipt> {
        safeParents()
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) fail("java.editor.journalCorrupt", "Editor journal directory is not a directory")
        val paths = Files.list(directory).use { it.filter { path -> path.fileName.toString().endsWith(".json") }.limit(17).sorted().toList() }
        if (paths.size > 16) fail("java.editor.journalLimitExceeded", "Editor journal exceeds its bounded record limit")
        if (paths.sumOf { Files.size(it) } > MAX_BYTES) fail("java.editor.journalLimitExceeded", "Editor journal exceeds its total byte limit")
        return paths.map { path ->
            try {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || Files.size(path) > MAX_BYTES) error("Unsafe journal file")
                val envelope = json.parseToJsonElement(Files.readString(path)).jsonObject
                require(envelope.keys == setOf("receipt","checksum"))
                val receipt = decode(envelope.getValue("receipt").jsonObject)
                require(envelope.getValue("checksum").jsonPrimitive.content == editorPlanSha256(json.encodeToString(envelope.getValue("receipt"))))
                require(receipt.schemaVersion == "java-editor-journal-v1" && receipt.root == root.toString())
                require(UUID.fromString(receipt.id).toString() == receipt.id && path.fileName.toString() == receipt.id+".json")
                validate(receipt)
                receipt
            } catch (failure: JavaContextAuthorityException) { throw failure }
            catch (_: Exception) { fail("java.editor.journalCorrupt", "Editor journal integrity or schema validation failed") }
        }
    }

    fun write(receipt: JavaEditorStoredReceipt, previous: JavaEditorStoredReceipt?) {
        safeParents();Files.createDirectories(directory);safeParents()
        val lockPath=directory.resolve(".lock")
        if(Files.isSymbolicLink(lockPath))fail("java.editor.journalCorrupt","Editor journal lock is a symbolic link")
        FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS).use { channel ->
            val lock=channel.tryLock() ?: fail("java.editor.journalBusy","Another process is updating editor receipts")
            lock.use {
                val current=readAll()
                if(current.singleOrNull { it.id==receipt.id } != previous)fail("java.editor.journalStale","Editor receipt changed in another process")
                if(previous==null && current.any { it.phase in setOf("prepared","undo-prepared","recovery-required") })fail("java.editor.transactionPending","Another unresolved editor receipt blocks preparation")
                writeLocked(receipt)
            }
        }
    }

    private fun writeLocked(receipt: JavaEditorStoredReceipt) {
        try {
            validate(receipt);require(receipt.root == root.toString())
            safeParents()
            Files.createDirectories(directory)
            safeParents()
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            }
            val body = encode(receipt)
            val text = json.encodeToString(buildJsonObject { put("receipt",body);put("checksum",editorPlanSha256(json.encodeToString(body))) })
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) fail("java.editor.journalLimitExceeded", "Editor journal record exceeds its byte limit")
            val destination = directory.resolve(receipt.id+".json")
            val existing = Files.list(directory).use { it.filter { path -> path != destination && path.fileName.toString().endsWith(".json") }.limit(17).toList() }
            if (existing.size >= 16 || existing.sumOf { Files.size(it) } + bytes.size > MAX_BYTES) fail("java.editor.journalLimitExceeded", "Editor journal retention limit would be exceeded")
            if (Files.isSymbolicLink(destination)) fail("java.editor.journalCorrupt", "Editor journal destination is a symbolic link")
            val temporary = Files.createTempFile(directory,".editor-",".tmp")
            try {
                if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"))
                FileChannel.open(temporary,StandardOpenOption.WRITE).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true)
                }
                Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                FileChannel.open(directory,StandardOpenOption.READ).use { it.force(true) }
            } finally { Files.deleteIfExists(temporary) }
        } catch (failure: JavaContextAuthorityException) { throw failure }
        catch (_: Exception) { fail("java.editor.journalUnavailable", "Editor journal could not be persisted atomically") }
    }

    private fun encode(receipt:JavaEditorStoredReceipt):JsonObject = buildJsonObject {
        put("schemaVersion",receipt.schemaVersion);put("id",receipt.id);put("root",receipt.root);put("phase",receipt.phase)
        put("sdkHome",receipt.sdkHome);put("contextHash",receipt.contextHash);put("baseSnapshotHash",receipt.baseSnapshotHash)
        fun sources(values:List<JavaEditorStoredSource>)=buildJsonArray { values.forEach { add(buildJsonObject { put("path",it.path);put("content",it.content);put("languageId",it.languageId) }) } }
        fun documents(values:List<JavaEditorStoredDocument>?)=values?.let { buildJsonArray {it.forEach { add(buildJsonObject {put("path",it.path);put("version",it.version);put("content",it.content)})}} } ?: JsonNull
        put("sources",sources(receipt.sources));put("auxiliary",sources(receipt.auxiliary));put("beforeDocuments",documents(receipt.beforeDocuments))
        put("planId",receipt.planId);put("planHash",receipt.planHash);put("selectionHash",receipt.selectionHash?.let(::JsonPrimitive) ?: JsonNull)
        put("formatterProfilePath",receipt.formatterProfilePath?.let(::JsonPrimitive) ?: JsonNull)
        put("formatterProfileName",receipt.formatterProfileName?.let(::JsonPrimitive) ?: JsonNull)
        put("formatterProfileSha256",receipt.formatterProfileSha256?.let(::JsonPrimitive) ?: JsonNull)
        put("operation",receipt.operation);put("summary",receipt.summary);put("confidence",receipt.confidence);put("riskLevel",receipt.riskLevel);put("evidence",receipt.evidence)
        put("warnings",buildJsonArray { receipt.warnings.forEach {add(it)} });put("edit",json.encodeToJsonElement(WorkspaceEditDto.serializer(),receipt.edit))
        put("providerHash",receipt.providerHash);put("candidateHash",receipt.candidateHash);put("diagnosticsBeforeHash",receipt.diagnosticsBeforeHash);put("diagnosticsAfterHash",receipt.diagnosticsAfterHash)
        put("confirmedDocuments",documents(receipt.confirmedDocuments));put("undoDocuments",documents(receipt.undoDocuments))
        put("undoEdit",receipt.undoEdit?.let {json.encodeToJsonElement(WorkspaceEditDto.serializer(),it)} ?: JsonNull)
        put("undoPlanHash",receipt.undoPlanHash?.let(::JsonPrimitive) ?: JsonNull);put("refusalCode",receipt.refusalCode?.let(::JsonPrimitive) ?: JsonNull)
    }
    private fun decode(value:JsonObject):JavaEditorStoredReceipt {
        fun text(key:String):String { val p=value.getValue(key).jsonPrimitive;require(p.isString);return p.content }
        fun optional(key:String)=if(value[key]==null || value.getValue(key)==JsonNull)null else text(key)
        fun sources(key:String)=value.getValue(key).jsonArray.map { element->val s=element.jsonObject;require(s.keys==setOf("path","content","languageId"));
            fun t(k:String):String { val p=s.getValue(k).jsonPrimitive;require(p.isString);return p.content };JavaEditorStoredSource(t("path"),t("content"),t("languageId")) }
        fun documents(key:String):List<JavaEditorStoredDocument>?=if(value.getValue(key)==JsonNull)null else value.getValue(key).jsonArray.map {element->
            val d=element.jsonObject;require(d.keys==setOf("path","version","content"));val path=d.getValue("path").jsonPrimitive;val content=d.getValue("content").jsonPrimitive;val version=d.getValue("version").jsonPrimitive
            require(path.isString&&content.isString&&!version.isString);JavaEditorStoredDocument(path.content,version.long,content.content) }
        val confidence=value.getValue("confidence").jsonPrimitive;require(!confidence.isString&&confidence.double.isFinite())
        val receipt=JavaEditorStoredReceipt(text("schemaVersion"),text("id"),text("root"),text("phase"),text("sdkHome"),text("contextHash"),text("baseSnapshotHash"),
            sources("sources"),sources("auxiliary"),requireNotNull(documents("beforeDocuments")),text("planId"),text("planHash"),optional("selectionHash"),text("operation"),text("summary"),confidence.double,text("riskLevel"),text("evidence"),
            value.getValue("warnings").jsonArray.map { val p=it.jsonPrimitive;require(p.isString);p.content },json.decodeFromJsonElement(WorkspaceEditDto.serializer(),value.getValue("edit")),
            text("providerHash"),text("candidateHash"),text("diagnosticsBeforeHash"),text("diagnosticsAfterHash"),documents("confirmedDocuments"),documents("undoDocuments"),
            if(value.getValue("undoEdit")==JsonNull)null else json.decodeFromJsonElement(WorkspaceEditDto.serializer(),value.getValue("undoEdit")),optional("undoPlanHash"),optional("refusalCode"),
            formatterProfilePath=optional("formatterProfilePath"),formatterProfileName=optional("formatterProfileName"),formatterProfileSha256=optional("formatterProfileSha256"))
        val currentKeys=encode(receipt).keys
        require(value.keys==currentKeys || value.keys==currentKeys-setOf("formatterProfilePath","formatterProfileName","formatterProfileSha256"))
        return receipt
    }

    private fun safeParents() {
        var current = root
        for (component in listOf(".refactorkit","editor-transactions")) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) fail("java.editor.journalCorrupt", "Editor journal must remain inside the workspace")
        }
    }
    private fun validate(receipt: JavaEditorStoredReceipt) {
        require(UUID.fromString(receipt.id).toString() == receipt.id)
        require(receipt.phase in JavaEditorTransactionPhase.entries.map { it.wire })
        require(receipt.operation in JavaEditorPreviewOperation.entries.map { it.wire })
        require(Path.of(receipt.sdkHome).isAbsolute)
        require(listOf(receipt.contextHash,receipt.baseSnapshotHash,receipt.planHash,receipt.providerHash,receipt.candidateHash,
            receipt.diagnosticsBeforeHash,receipt.diagnosticsAfterHash).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(receipt.selectionHash == null || receipt.selectionHash.matches(Regex("[0-9a-f]{64}")))
        require(receipt.formatterProfileSha256 == null || receipt.formatterProfileSha256.matches(Regex("[0-9a-f]{64}")))
        require((receipt.formatterProfilePath==null)==(receipt.formatterProfileName==null) &&
            (receipt.formatterProfileName==null)==(receipt.formatterProfileSha256==null))
        receipt.formatterProfilePath?.let { safePath(it);require(it.length<=1024) }
        receipt.formatterProfileName?.let { require(it.isNotBlank() && it.length<=256 && it.none(Char::isISOControl)) }
        require(receipt.sources.size in 1..5_000 && receipt.auxiliary.size <= 5_000)
        val all = receipt.sources+receipt.auxiliary
        require(all.map { it.path }.distinct().size == all.size)
        all.forEach { safePath(it.path);require(it.content.toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_SOURCE_FILE_BYTES) }
        require(all.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_BYTES)
        fun documents(values: List<JavaEditorStoredDocument>?) { values?.let {
            require(it.size in 1..ProtocolLimits.MAX_EDITOR_OVERLAY_DOCUMENTS && it.map { d -> d.path }.distinct().size == it.size)
            it.forEach { d -> safePath(d.path);require(d.version in 0..Int.MAX_VALUE.toLong()) }
            require(it.sumOf { d -> d.content.toByteArray(Charsets.UTF_8).size.toLong() } <= ProtocolLimits.MAX_EDITOR_OVERLAY_BYTES)
        } }
        documents(receipt.beforeDocuments);documents(receipt.confirmedDocuments);documents(receipt.undoDocuments)
        fun edit(dto: WorkspaceEditDto?) { dto?.let {
            require(it.edits.size <= 256)
            it.edits.forEach { e -> safePath(e.path);when(e) {
                is FileEditDto.Rename -> safePath(e.newPath)
                is FileEditDto.Modify -> require(e.textEdits.size <= 4_096)
                else -> error("Editor journal contains unsupported resource operations")
            } }
        } }
        edit(receipt.edit);edit(receipt.undoEdit)
        require(receipt.warnings.size <= 500 && receipt.summary.length <= 16_384)
    }
    private fun safePath(value: String) {
        val path = Path.of(value);require(value.isNotBlank() && !path.isAbsolute && path == path.normalize() && !path.startsWith("..") && !Regex("^[A-Za-z]:").containsMatchIn(value))
    }
    private fun fail(code: String,message: String): Nothing = throw JavaContextAuthorityException(code,message)
    companion object { private const val MAX_BYTES = 64L*1024*1024 }
}
