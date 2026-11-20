package org.refactorkit.daemon

import kotlinx.serialization.json.*
import org.refactorkit.core.*
import org.refactorkit.java.*

internal data class JavaPlatformSourceWire(val request: JavaPlatformSourceRequest, val sourceAuthority: JsonObject, val editor: Boolean)
internal fun decodeJavaPlatformSource(params: JsonObject?, operation: JavaPlatformSourceOperation): JavaPlatformSourceWire = try {
    val p = requireNotNull(params) { "params are required" }
    fun hash(key: String): String { val v=p[key]; require(v is JsonPrimitive && v.isString && v.content.matches(Regex("[a-f0-9]{64}"))) { "$key must be SHA-256" }; return v.content }
    val platform = hash("expectedPlatformIdentitySha256")
    val source = p["sourceAuthority"] as? JsonObject ?: error("sourceAuthority is required")
    val kind = source["kind"] as? JsonPrimitive; require(kind != null && kind.isString) { "sourceAuthority.kind is required" }
    val editor = when (kind.content) {
        "saved-snapshot" -> { require(source.keys == setOf("kind") && "expectedOverlayHash" !in p) { "saved authority cannot contain editor fields" }; false }
        "immutable-editor-overlay" -> { require(source.keys == setOf("kind", "documents")) { "editor authority requires a closed document frame" }; true }
        else -> error("Unsupported source authority")
    }
    val overlay = if (editor) hash("expectedOverlayHash") else null
    val common = decodeJavaDependencySource(JsonObject(p.filterKeys { it !in setOf("expectedPlatformIdentitySha256", "sourceAuthority", "expectedOverlayHash") }),
        operation == JavaPlatformSourceOperation.READ, optionalAssociation = operation == JavaPlatformSourceOperation.DECLARATION)
    JavaPlatformSourceWire(JavaPlatformSourceRequest(common.context, common.path, common.position, platform, common.expectedAssociationHash, overlay), source, editor)
} catch (e: IllegalArgumentException) { throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, e.message ?: "Invalid platform source request") }
  catch (_: IllegalStateException) { throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Invalid platform source authority") }

internal fun encodeJavaPlatformSource(r: JavaBoundReadResult<JavaPlatformSourceResult>, op: JavaPlatformSourceOperation): JsonObject = when (r) {
    is JavaBoundReadResult.Available -> encodePlatform(r.value, r.context, null, op)
    is JavaBoundReadResult.Refused -> encodePlatform(JavaPlatformSourceResult.Refused(r.code, r.message), null, null, op)
}
internal fun encodeJavaEditorPlatformSource(r: JavaEditorReadResult<JavaPlatformSourceResult>, op: JavaPlatformSourceOperation): JsonObject = when (r) {
    is JavaEditorReadResult.Available -> encodePlatform(r.value, r.context, r.overlay, op)
    is JavaEditorReadResult.Refused -> encodePlatform(JavaPlatformSourceResult.Refused(r.code, r.message), null, null, op)
}
private fun encodePlatform(value: JavaPlatformSourceResult, context: JavaBoundContext?, overlay: ImmutableEditorOverlay?, op: JavaPlatformSourceOperation): JsonObject = buildJsonObject {
    put("schemaVersion", if (op == JavaPlatformSourceOperation.DECLARATION) "java-platform-source-declaration-v1" else "java-platform-source-v1")
    fun reason(status: String, code: String, message: String) { put("status", status); put("reason", buildJsonObject { put("code", code); put("message", message) }) }
    if (context != null) {
        put("snapshotHash", context.snapshot.hash); put("contextHash", context.view.contextHash)
        put("sdkIdentities", buildJsonArray { context.view.sdk.forEach { s -> add(buildJsonObject { put("release", s.release); put("identitySha256", s.identitySha256); put("sdkHome", s.platformHome.toString()) }) } })
        put("sourceAuthority", buildJsonObject {
            put("kind", if (overlay == null) "saved-snapshot" else "immutable-editor-overlay"); put("baseSnapshotHash", context.snapshot.hash)
            if (overlay != null) { put("overlayHash", overlay.overlayHash); put("documents", buildJsonArray { overlay.documents.forEach { d -> add(buildJsonObject {
                put("path", ProtocolPath.serialize(d.path)); put("version", d.version); put("contentSha256", requireNotNull(overlay.authority(d.path)).contentSha256)
            }) } }) }
        })
        if (overlay != null) put("providerSnapshotHash", overlay.providerSnapshot.hash)
    }
    when (value) {
        is JavaPlatformSourceResult.Unavailable -> reason("unavailable", value.code, value.message)
        is JavaPlatformSourceResult.Refused -> reason("refused", value.code, value.message)
        is JavaPlatformSourceResult.Ambiguous -> reason("ambiguous", value.code, value.message)
        is JavaPlatformSourceResult.Available -> {
            put("status", "available"); put("association", buildJsonObject {
                val a=value.association; val p=a.platform
                put("associationHash", a.associationHash); put("resourceUri", a.resourceUri); put("readOnly", true); put("resourceKind", "java-platform-source")
                put("providerId", a.providerId); put("moduleId", a.moduleId); put("sourceSetId", a.sourceSetId)
                put("sdkHome", p.platformHome.toString()); put("effectiveRelease", p.release); put("platformRelease", p.platformRelease)
                put("platformIdentitySha256", p.identitySha256); put("platformModule", a.binary.moduleName)
                put("binaryOrigin", buildJsonObject { put("kind", "CURRENT_JRT_IMAGE"); put("uri", a.binary.originUri)
                    put("classEntry", a.binary.classEntry); put("classSha256", a.classSha256); put("moduleImagePath", requireNotNull(p.currentSystemModules).toString()); put("moduleImageSha256", requireNotNull(p.currentSystemModulesSha256)) })
                put("sourceArchivePath", a.sourceArchivePath.toString()); put("sourceArchiveSha256", a.sourceArchiveSha256)
                put("sourceEntry", a.sourceEntry); put("sourceSha256", a.sourceSha256); put("sourceFile", a.sourceFile)
                put("jvmIdentity", buildJsonObject { put("kind", a.binary.identity.kind.name); put("ownerBinaryName", a.binary.identity.ownerBinaryName); put("memberName", a.binary.identity.memberName); put("descriptor", a.binary.identity.descriptor) })
            })
            value.content?.let { put("content", it) }; value.declaration?.let { d -> put("declaration", buildJsonObject {
                fun position(p: SourcePosition) = buildJsonObject { put("line", p.line); put("character", p.character) }
                fun range(r: SourceRange) = buildJsonObject { put("start", position(r.start)); put("end", position(r.end)) }
                put("declarationRange", range(d.declarationRange)); put("nameRange", range(d.nameRange)); put("bindingKey", d.bindingKey); put("evidence", "JDT_SOURCE_BINDING")
            }) }
        }
    }
}
