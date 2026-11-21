package org.refactorkit.java

import kotlinx.serialization.json.*
import org.refactorkit.core.*
import java.nio.file.Path
import java.security.MessageDigest

data class JavaEditorMutationSelection(val baseSnapshotHash:String,val overlayHash:String,
    val documents:List<SemanticDocumentAuthority>,val selection:JavaMutationSelection,val selectorHash:String) {
    companion object {
        fun create(overlay:ImmutableEditorOverlay,selection:JavaMutationSelection):JavaEditorMutationSelection {
            require(selection.snapshotHash==overlay.providerSnapshot.hash)
            val docs=java.util.Collections.unmodifiableList(overlay.documents.map { requireNotNull(overlay.authority(it.path)) })
            val hash=editorPlanSha256(listOf("java-editor-mutation-selection-v1",overlay.baseSnapshotHash,overlay.overlayHash,selection.selectorHash).joinToString("\u0000"))
            return JavaEditorMutationSelection(overlay.baseSnapshotHash,overlay.overlayHash,docs,selection,hash)
        }
    }
}

/** Explicit ACL keeps the version receipt alongside the exact compiler selector. */
object JavaEditorMutationSelectionWire {
    const val VERSION="java-editor-mutation-selection-v1"
    fun render(value:JavaEditorMutationSelection)=buildJsonObject {
        put("schemaVersion",VERSION);put("baseSnapshotHash",value.baseSnapshotHash);put("overlayHash",value.overlayHash);put("selectorHash",value.selectorHash)
        put("documents",buildJsonArray { value.documents.forEach { d->add(buildJsonObject { put("path",ProtocolPath.serialize(d.path));put("version",requireNotNull(d.version));put("contentSha256",d.contentSha256) }) } })
        put("selection",JavaMutationSelectionWire.render(value.selection))
    }
    fun parse(value:JsonObject):JavaEditorMutationSelection {
        require(value.keys==setOf("schemaVersion","baseSnapshotHash","overlayHash","selectorHash","documents","selection"))
        fun text(key:String):String { val p=value.getValue(key).jsonPrimitive;require(p.isString);return p.content }
        require(text("schemaVersion")==VERSION)
        val docs=value.getValue("documents").jsonArray.map { e->
            val d=e.jsonObject;require(d.keys==setOf("path","version","contentSha256"))
            val path=d.getValue("path").jsonPrimitive;val hash=d.getValue("contentSha256").jsonPrimitive;val version=d.getValue("version").jsonPrimitive
            require(path.isString&&hash.isString&&!version.isString&&version.long>=0)
            SemanticDocumentAuthority(Path.of(path.content),SemanticDocumentMode.IMMUTABLE_EDITOR_OVERLAY,hash.content,version.long)
        }
        require(docs.size in 1..ProtocolLimits.MAX_EDITOR_OVERLAY_DOCUMENTS)
        val result=JavaEditorMutationSelection(text("baseSnapshotHash"),text("overlayHash"),java.util.Collections.unmodifiableList(docs),
            JavaMutationSelectionWire.parse(value.getValue("selection").jsonObject),text("selectorHash"))
        require(listOf(result.baseSnapshotHash,result.overlayHash,result.selectorHash).all { it.matches(Regex("[0-9a-f]{64}")) })
        return result
    }
}

/** Immutable saved-source selection. Its version is independent of daemon/API versions. */
data class JavaMutationSelection(
    val snapshotHash: String, val contextHash: String, val path: Path, val position: SourcePosition,
    val symbolId: String, val symbolKind: Symbol.Kind, val bindingKey: String, val declaration: SourceLocation,
    val contentSha256: String, val provenanceHash: String, val selectorHash: String,
)

/** Application service: exact compiler ranges and visibility, never lexical symbol guessing. */
object JavaMutationSelectionService {
    fun select(context: JavaBoundContext, path: Path, position: SourcePosition): JavaMutationSelection {
        val snapshot = context.snapshot
        val source = snapshot.files.singleOrNull { it.path == path && it.languageId == "java" }
            ?: fail("java.selection.pathMissing", "Selection source is outside the saved Java snapshot")
        val owners = snapshot.owningBuildSourceRoots(path)
        val depth = owners.maxOfOrNull { it.root.nameCount } ?: fail("java.selection.ownerUnavailable", "Selection has no source-set owner")
        val owner = owners.filter { it.root.nameCount == depth }.distinctBy { Triple(it.providerId, it.module.id, it.sourceSet.id) }
            .singleOrNull() ?: fail("java.selection.ambiguous", "Selection source-set ownership is ambiguous")
        val model = snapshot.buildModels.singleOrNull { it.providerId == owner.providerId }
            ?: fail("java.selection.ownerUnavailable", "Selection build authority is ambiguous")
        val environment = JavaBuildSourceSetContextResolver.resolve(model, owner.module, owner.sourceSet)
        val cached = context.cache.get(snapshot)
        val analysis = cached.analysis
        val direct = analysis.symbols.filter { it.path == path && contains(it.sourceRange, position) && clean(it) }
        val candidates = if (direct.isNotEmpty()) direct else {
            val uses = analysis.bindingUses.filter { it.path == path && contains(it.sourceRange, position) &&
                it.evidence == JdtJavaSemanticEvidence.JDT_BINDING && !it.recovered }
                .distinctBy { it.bindingKey to it.jvmIdentity }
            val use = uses.singleOrNull() ?: fail(if (uses.isEmpty()) "java.selection.notBound" else "java.selection.ambiguous", "Cursor has no unique compiler binding")
            analysis.symbols.filter { symbol -> clean(symbol) && environment.sourceRoots.any { symbol.path.startsWith(it) } &&
                (symbol.bindingKey == use.bindingKey || use.jvmIdentity != null && symbol.jvmIdentity == use.jvmIdentity) }
        }
        val declaration = candidates.distinctBy { Triple(it.path, it.sourceRange, it.bindingKey) }.singleOrNull()
            ?: fail(if (candidates.isEmpty()) "java.selection.sourceDeclarationUnavailable" else "java.selection.ambiguous", "Compiler binding has no unique editable source declaration")
        val declarationSource = snapshot.files.singleOrNull { it.path == declaration.path }
            ?: fail("java.selection.sourceDeclarationUnavailable", "Compiler declaration is not a saved project source")
        if (JavaGeneratedSourcePolicy.reason(declarationSource) != null) fail("java.selection.generatedSource", "Generated declarations cannot be mutation targets")
        val result = JavaMutationSelection(snapshot.hash, context.view.contextHash, path, position, declaration.qualifiedName,
            Symbol.Kind.valueOf(declaration.kind.name), requireNotNull(declaration.bindingKey), SourceLocation(declaration.path, declaration.sourceRange),
            sha(source.content), cached.provenanceHash, "")
        return result.copy(selectorHash = sha(listOf(result.snapshotHash, result.contextHash, ProtocolPath.serialize(path),
            position.line, position.character, result.symbolId, result.symbolKind.name, result.bindingKey,
            ProtocolPath.serialize(result.declaration.path), result.declaration.range.start.line, result.declaration.range.start.character,
            result.declaration.range.end.line, result.declaration.range.end.character, result.contentSha256, result.provenanceHash).joinToString("\u0000")))
    }

    fun validate(context: JavaBoundContext, claim: JavaMutationSelection, operation: String,
                 suppliedSymbol: String?, arguments: Map<String, String>): JavaMutationSelection {
        val actual = select(context, claim.path, claim.position)
        if (actual != claim) fail("java.selection.selectorStale", "Mutation selector differs from the exact current SDK/source binding")
        if (suppliedSymbol != null && suppliedSymbol != actual.symbolId) fail("java.selection.symbolMismatch", "Requested symbol differs from the compiler selector")
        val typeKinds = setOf(Symbol.Kind.CLASS, Symbol.Kind.INTERFACE, Symbol.Kind.ENUM, Symbol.Kind.RECORD, Symbol.Kind.ANNOTATION)
        val admitted = when {
            operation == "extractInterface" -> actual.symbolKind == Symbol.Kind.CLASS
            operation == "extractSuperclass" -> actual.symbolKind == Symbol.Kind.CLASS
            operation == "pullUpMember" -> actual.symbolKind == Symbol.Kind.METHOD
            operation == "pushDownMember" -> actual.symbolKind == Symbol.Kind.METHOD
            operation == "moveMember" -> actual.symbolKind == Symbol.Kind.METHOD &&
                arguments["memberKind"] == "method" && !arguments["targetType"].isNullOrBlank() &&
                !arguments["targetBindingKey"].isNullOrBlank() && run {
                    val target = arguments["file"]?.let { runCatching { Path.of(it) }.getOrNull() }
                        ?: fail("java.selection.fileMismatch", "moveMember requires the selected Java declaration path")
                    val root = context.snapshot.workspace.root.toAbsolutePath().normalize()
                    val relative = if (target.isAbsolute) {
                        val normalized = target.toAbsolutePath().normalize()
                        if (!normalized.startsWith(root)) fail("java.selection.fileMismatch", "Selected declaration is outside the Java workspace")
                        root.relativize(normalized)
                    } else target.normalize()
                    if (relative != actual.path) fail("java.selection.fileMismatch", "moveMember file differs from the exact selected declaration")
                    true
                }
            operation in setOf("renameClass", "moveClass") -> actual.symbolKind in typeKinds
            operation == "renameMember" -> actual.symbolKind in setOf(Symbol.Kind.METHOD, Symbol.Kind.FIELD, Symbol.Kind.RECORD_COMPONENT)
            operation == "safeDelete" -> actual.symbolKind in typeKinds || actual.symbolKind in setOf(Symbol.Kind.METHOD, Symbol.Kind.FIELD)
            operation in setOf("makeStatic", "inlineMethod", "changeVisibility", "introduceParameterObject") -> actual.symbolKind == Symbol.Kind.METHOD
            operation in setOf("changeType", "encapsulateField") -> actual.symbolKind == Symbol.Kind.FIELD
            operation.startsWith("changeSignature.") || operation in setOf("renameParameter", "changeParameterType", "addParameter", "reorderParameters", "removeParameter") -> actual.symbolKind in setOf(Symbol.Kind.METHOD, Symbol.Kind.CONSTRUCTOR)
            operation in setOf("formatFile", "organizeImports") -> {
                val target = arguments["file"]?.let(Path::of) ?: fail("java.selection.fileMismatch", "Selected file operation requires a file")
                val relative = if (target.isAbsolute) context.snapshot.workspace.root.relativize(target.normalize()) else target.normalize()
                if (relative != actual.path) fail("java.selection.fileMismatch", "File operation differs from the selected document")
                true
            }
            else -> false
        }
        if (!admitted) fail("java.selection.operationIncompatible", "This operation is incompatible with the exact compiler selection")
        return actual
    }

    fun attest(
        plan: PatchPlan,
        selection: JavaMutationSelection,
        targetBindingKey: String? = null,
    ): PatchPlan {
        val prior = plan.authorityLease ?: fail("java.selection.authorityUnavailable", "Selection requires a retained SDK mutation lease")
        val targetIdentity = if (plan.operation == "moveMember") {
            targetBindingKey?.takeIf(String::isNotBlank)
                ?: fail("java.moveMember.targetBindingRequired", "moveMember requires the exact selected target type binding")
        } else null
        val attestedIdentity = if (targetIdentity == null) selection.selectorHash
        else "${selection.selectorHash}\u0000$targetIdentity"
        val attributes = prior.attributes + mapOf(
            "javaSelectionHash" to selection.selectorHash,
            "javaSelectedSymbolId" to selection.symbolId,
        ) + (targetIdentity?.let { mapOf("javaTargetBindingKey" to it) } ?: emptyMap())
        return plan.copy(authorityLease = OperationAuthorityLease(prior.kind, prior.operation, prior.snapshotHash,
            sha("java-mutation-selection-v1\u0000${prior.evidenceHash}\u0000$attestedIdentity"), prior.evidenceCompleteness,
            prior.workspaceEditSha256, prior.requiredClasspathEvidence, prior.requiredFileEvidence,
            attributes))
    }
    private fun clean(symbol: JdtJavaSemanticSymbol) = symbol.evidence == JdtJavaSemanticEvidence.JDT_BINDING && !symbol.recovered && !symbol.bindingKey.isNullOrBlank()
    private fun contains(range: SourceRange, point: SourcePosition) =
        (point.line > range.start.line || point.line == range.start.line && point.character >= range.start.character) &&
            (point.line < range.end.line || point.line == range.end.line && point.character < range.end.character)
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun fail(code: String, message: String): Nothing = throw JavaContextAuthorityException(code, message)
}

/** Explicit Java protocol ACL; no application rules or authority hashing in transport handlers. */
object JavaMutationSelectionWire {
    const val VERSION = "java-mutation-selection-v1"
    fun render(value: JavaMutationSelection): JsonObject = buildJsonObject {
        put("schemaVersion", VERSION);put("snapshotHash", value.snapshotHash);put("contextHash", value.contextHash)
        put("path", ProtocolPath.serialize(value.path));put("position", point(value.position));put("symbolId", value.symbolId)
        put("symbolKind", value.symbolKind.name.lowercase().replace('_', '-'));put("bindingKey", value.bindingKey)
        put("declaration", buildJsonObject { put("path", ProtocolPath.serialize(value.declaration.path));put("range", buildJsonObject { put("start", point(value.declaration.range.start));put("end", point(value.declaration.range.end)) }) })
        put("contentSha256", value.contentSha256);put("provenanceHash", value.provenanceHash);put("selectorHash", value.selectorHash)
    }
    fun parse(value: JsonObject): JavaMutationSelection {
        require(value.keys == setOf("schemaVersion", "snapshotHash", "contextHash", "path", "position", "symbolId", "symbolKind", "bindingKey", "declaration", "contentSha256", "provenanceHash", "selectorHash")) { "javaSelection fields are invalid" }
        fun text(key: String): String { val item = value[key];require(item is JsonPrimitive && item.isString);return item.content }
        require(text("schemaVersion") == VERSION) { "Unsupported javaSelection schema" }
        fun path(text: String): Path { val result = Path.of(text);require(!result.isAbsolute && result.normalize() == result && !result.startsWith(".."));return result }
        fun readPoint(element: JsonElement): SourcePosition { val v = element.jsonObject;require(v.keys == setOf("line", "character"));val l = v.getValue("line").jsonPrimitive;val c = v.getValue("character").jsonPrimitive;require(!l.isString && !c.isString && l.int >= 0 && c.int >= 0);return SourcePosition(l.int, c.int) }
        val location = value.getValue("declaration").jsonObject;require(location.keys == setOf("path", "range"));val range = location.getValue("range").jsonObject;require(range.keys == setOf("start", "end"))
        val result = JavaMutationSelection(text("snapshotHash"), text("contextHash"), path(text("path")), readPoint(value.getValue("position")),
            text("symbolId"), Symbol.Kind.valueOf(text("symbolKind").uppercase().replace('-', '_')), text("bindingKey"),
            SourceLocation(path(location.getValue("path").jsonPrimitive.content), SourceRange(readPoint(range.getValue("start")), readPoint(range.getValue("end")))),
            text("contentSha256"), text("provenanceHash"), text("selectorHash"))
        require(listOf(result.snapshotHash, result.contextHash, result.contentSha256, result.provenanceHash, result.selectorHash).all { it.matches(Regex("[a-f0-9]{64}")) }) { "Invalid javaSelection identity" }
        return result
    }
    private fun point(value: SourcePosition) = buildJsonObject { put("line", value.line);put("character", value.character) }
}
