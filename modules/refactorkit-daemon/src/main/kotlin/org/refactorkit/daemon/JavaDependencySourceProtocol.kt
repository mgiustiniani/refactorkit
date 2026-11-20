package org.refactorkit.daemon

import kotlinx.serialization.json.*
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.SourcePosition
import org.refactorkit.java.*
import java.nio.file.Path

/** Transport extraction/projection only. Provider selection, hashing and reading live in Java. */
internal fun decodeJavaDependencySource(params: JsonObject?, read: Boolean, optionalAssociation: Boolean = false): JavaDependencySourceRequest = try {
    val p = requireNotNull(params) { "params are required" }
    val fields = setOf("expectedSnapshotHash", "expectedContextHash", "sdkHome", "path", "position") +
        if (read) setOf("expectedAssociationHash") else emptySet()
    require(p.keys == fields || (optionalAssociation && p.keys == fields + "expectedAssociationHash")) { "Dependency source request contains invalid fields" }
    fun string(key: String): String = p[key].let { require(it is JsonPrimitive && it.isString); it.content }
    val position = p["position"] as? JsonObject ?: error("position must be an object")
    require(position.keys == setOf("line", "character")) { "position requires line and character" }
    fun integer(key: String): Int = position[key].let {
        require(it is JsonPrimitive && !it.isString); requireNotNull(it.intOrNull) { "$key must be an integer" }
    }
    JavaDependencySourceRequest(JavaProjectContextRequest(string("expectedSnapshotHash"), Path.of(string("sdkHome")),
        expectedContextHash = string("expectedContextHash")), Path.of(string("path")),
        SourcePosition(integer("line"), integer("character")), if (read || "expectedAssociationHash" in p) string("expectedAssociationHash") else null)
} catch (failure: IllegalArgumentException) {
    throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, failure.message ?: "Invalid dependency source request")
} catch (_: IllegalStateException) {
    throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "position must be an object")
}

internal fun encodeJavaDependencySourceDeclaration(result: JavaBoundReadResult<JavaDependencySourceDeclarationResult>): JsonObject {
    val source: JavaBoundReadResult<JavaDependencySourceResult> = when (result) {
        is JavaBoundReadResult.Refused -> result
        is JavaBoundReadResult.Available -> JavaBoundReadResult.Available(result.context, result.value.source)
    }
    fun declaration(value: JdtJavaSourceDeclaration) = buildJsonObject {
        fun range(value: org.refactorkit.core.SourceRange) = buildJsonObject {
            fun position(value: org.refactorkit.core.SourcePosition) = buildJsonObject { put("line", value.line); put("character", value.character) }
            put("start", position(value.start)); put("end", position(value.end))
        }
        put("declarationRange", range(value.declarationRange)); put("nameRange", range(value.nameRange))
        put("evidence", "JDT_SOURCE_BINDING"); put("bindingKey", value.bindingKey)
    }
    return buildJsonObject {
        encodeJavaDependencySource(source).forEach { (key, value) ->
            if (key != "candidates" || (value as? JsonArray)?.isNotEmpty() == true) put(key, value)
        }
        put("schemaVersion", "java-dependency-source-declaration-v1")
        if (result is JavaBoundReadResult.Available) {
            result.value.declaration?.let { put("declaration", declaration(it)) }
            if (result.value.ambiguousDeclarations.isNotEmpty()) {
                put("declarationCandidates", buildJsonArray { result.value.ambiguousDeclarations.forEach { add(declaration(it)) } })
            }
        }
    }
}

internal fun encodeJavaDependencySource(result: JavaBoundReadResult<JavaDependencySourceResult>): JsonObject = buildJsonObject {
    put("schemaVersion", "java-dependency-source-v1")
    fun reason(status: String, code: String, message: String) {
        put("status", status); put("reason", buildJsonObject { put("code", code); put("message", message) })
    }
    when (result) {
        is JavaBoundReadResult.Refused -> reason("refused", result.code, result.message)
        is JavaBoundReadResult.Available -> {
            put("snapshotHash", result.context.snapshot.hash); put("contextHash", result.context.view.contextHash)
            put("sdkIdentities", buildJsonArray { result.context.view.sdk.forEach { sdk -> add(buildJsonObject {
                put("release", sdk.release); put("identitySha256", sdk.identitySha256); put("sdkHome", sdk.platformHome.toString())
            }) } })
            when (val value = result.value) {
                is JavaDependencySourceResult.Refused -> reason("refused", value.code, value.message)
                is JavaDependencySourceResult.Unavailable -> reason("unavailable", value.code, value.message)
                is JavaDependencySourceResult.Ambiguous -> {
                    reason("ambiguous", value.code, value.message)
                    put("candidates", buildJsonArray { value.candidates.forEach { add(buildJsonObject { put("binaryPath", it.toString()) }) } })
                }
                is JavaDependencySourceResult.Available -> {
                    put("status", "available")
                    put("association", buildJsonObject {
                        val a = value.association
                        put("associationHash", a.associationHash); put("resourceUri", a.resourceUri); put("readOnly", true)
                        put("providerId", a.providerId); put("moduleId", a.moduleId); put("sourceSetId", a.sourceSetId)
                        put("binaryPath", a.binaryPath.toString()); put("binarySha256", a.binarySha256)
                        put("sourceArchivePath", a.sourceArchivePath.toString()); put("sourceArchiveSha256", a.sourceArchiveSha256)
                        put("sourceEntry", a.sourceEntry); put("sourceSha256", a.sourceSha256)
                        put("jvmIdentity", buildJsonObject { put("kind", a.identity.kind.name); put("ownerBinaryName", a.identity.ownerBinaryName)
                            put("memberName", a.identity.memberName); put("descriptor", a.identity.descriptor) })
                        put("provenance", buildJsonObject { put("kind", a.provenanceKind); put("componentId", a.componentId); put("sourceFile", a.sourceFile) })
                    })
                    value.content?.let { put("content", it) }
                }
            }
        }
    }
}

internal data class JavaEditorDependencySourceWire(
    val request: JavaDependencySourceRequest,
    val expectedOverlayHash: String,
    val sourceAuthority: JsonObject,
)

internal fun decodeJavaEditorDependencySource(params: JsonObject?, operation: JavaEditorDependencySourceOperation): JavaEditorDependencySourceWire {
    val p = params ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "params are required")
    val proof = p["expectedOverlayHash"] as? JsonPrimitive
    if (proof == null || !proof.isString || !proof.content.matches(Regex("[a-f0-9]{64}")))
        throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Selected expectedOverlayHash must be SHA-256")
    val source = p["sourceAuthority"] as? JsonObject
        ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "immutable editor sourceAuthority is required")
    val mode = source["kind"] as? JsonPrimitive
    if (mode == null || !mode.isString || mode.content != "immutable-editor-overlay")
        throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "sourceAuthority.kind must be immutable-editor-overlay")
    val request = decodeJavaDependencySource(JsonObject(p.filterKeys { it !in setOf("sourceAuthority", "expectedOverlayHash") }),
        operation == JavaEditorDependencySourceOperation.READ, operation == JavaEditorDependencySourceOperation.DECLARATION)
    return JavaEditorDependencySourceWire(request, proof.content, source)
}

internal fun encodeJavaEditorDependencySource(result: JavaEditorReadResult<JavaDependencySourceDeclarationResult>,
                                               operation: JavaEditorDependencySourceOperation): JsonObject {
    val base = when (result) {
        is JavaEditorReadResult.Refused -> encodeJavaDependencySource(JavaBoundReadResult.Refused(result.code, result.message))
        is JavaEditorReadResult.Available -> if (operation == JavaEditorDependencySourceOperation.DECLARATION)
            encodeJavaDependencySourceDeclaration(JavaBoundReadResult.Available(result.context, result.value))
        else encodeJavaDependencySource(JavaBoundReadResult.Available(result.context, result.value.source))
    }
    return buildJsonObject {
        base.forEach { (key, value) -> put(key, value) }
        put("schemaVersion", if (operation == JavaEditorDependencySourceOperation.DECLARATION)
            "java-editor-dependency-source-declaration-v1" else "java-editor-dependency-source-v1")
        if (result is JavaEditorReadResult.Available) {
            put("providerSnapshotHash", result.overlay.providerSnapshot.hash)
            put("sourceAuthority", buildJsonObject {
                put("kind", "immutable-editor-overlay"); put("baseSnapshotHash", result.overlay.baseSnapshotHash)
                put("overlayHash", result.overlay.overlayHash)
                put("documents", buildJsonArray { result.overlay.documents.forEach { document -> add(buildJsonObject {
                    put("path", document.path.toString().replace('\\', '/')); put("version", document.version)
                    put("contentSha256", requireNotNull(result.overlay.authority(document.path)).contentSha256)
                }) } })
            })
        }
    }
}
