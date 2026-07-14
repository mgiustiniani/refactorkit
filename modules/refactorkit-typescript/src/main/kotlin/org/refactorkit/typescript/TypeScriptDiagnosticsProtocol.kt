package org.refactorkit.typescript

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticLocationPrecision
import org.refactorkit.core.ImmutableEditorOverlay
import org.refactorkit.core.ImmutableEditorOverlayDocument
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.RefactorKitVersion
import org.refactorkit.treesitter.ExternalSemanticDiagnostics
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

sealed interface TypeScriptDiagnosticsSourceAuthority {
    data object SavedDisk : TypeScriptDiagnosticsSourceAuthority
    data class ImmutableEditorOverlay(val documents: List<EditorOverlayDocument>) : TypeScriptDiagnosticsSourceAuthority
}

typealias EditorOverlayDocument = ImmutableEditorOverlayDocument

data class TypeScriptDiagnosticsRequest(
    val requestId: String,
    val languageId: String,
    val expectedSnapshotHash: String,
    val semanticLease: String,
    val sourceAuthority: TypeScriptDiagnosticsSourceAuthority,
)

/** Additive API 0.2 diagnostics contract used by daemon, CLI, and MCP. */
object TypeScriptDiagnosticsProtocol {
    const val METHOD = "diagnostics.v2"

    fun parse(params: JsonObject): TypeScriptDiagnosticsRequest {
        val requestId = params.requiredString("requestId").also {
            require(it.length <= ProtocolLimits.MAX_DIAGNOSTICS_REQUEST_ID_CHARS && REQUEST_ID.matches(it)) {
                "requestId is invalid or exceeds ${ProtocolLimits.MAX_DIAGNOSTICS_REQUEST_ID_CHARS} characters"
            }
        }
        val languageId = params.requiredString("languageId").also {
            require(it in setOf("typescript", "javascript")) { "languageId must be typescript or javascript" }
        }
        val expectedSnapshotHash = params.requiredString("expectedSnapshotHash").also {
            require(SHA256.matches(it)) { "expectedSnapshotHash must be lowercase SHA-256" }
        }
        val semanticLease = params.requiredString("semanticLease").also {
            require(LEASE.matches(it)) { "semanticLease is invalid" }
        }
        val source = params["sourceAuthority"] as? JsonObject
            ?: throw IllegalArgumentException("sourceAuthority object is required")
        val authority = when (source.requiredString("kind")) {
            "saved-disk" -> {
                require(source.keys == setOf("kind")) { "saved-disk authority accepts only kind" }
                TypeScriptDiagnosticsSourceAuthority.SavedDisk
            }
            "immutable-editor-overlay" -> {
                require(source.keys == setOf("kind", "documents")) {
                    "immutable-editor-overlay authority requires only kind and documents"
                }
                val values = source["documents"]?.jsonArray
                    ?: throw IllegalArgumentException("sourceAuthority.documents is required")
                require(values.size in 1..ProtocolLimits.MAX_DIAGNOSTICS_OVERLAY_DOCUMENTS) {
                    "editor overlay document count is outside the bounded range"
                }
                val documents = values.map { parseDocument(it.jsonObject) }
                require(documents.map { it.path.normalize() }.distinct().size == documents.size) {
                    "editor overlay contains duplicate paths"
                }
                val total = documents.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() }
                require(total <= ProtocolLimits.MAX_DIAGNOSTICS_OVERLAY_BYTES) {
                    "editor overlay exceeds ${ProtocolLimits.MAX_DIAGNOSTICS_OVERLAY_BYTES} UTF-8 bytes"
                }
                TypeScriptDiagnosticsSourceAuthority.ImmutableEditorOverlay(documents)
            }
            else -> throw IllegalArgumentException("sourceAuthority.kind is unsupported")
        }
        return TypeScriptDiagnosticsRequest(requestId, languageId, expectedSnapshotHash, semanticLease, authority)
    }

    fun execute(
        request: TypeScriptDiagnosticsRequest,
        savedSnapshot: ProjectSnapshot,
        activeLease: String,
        semantic: TypeScriptSemanticAdapter,
    ): JsonObject {
        val authority = authorityMetadata(request.sourceAuthority, savedSnapshot.hash)
        if (request.expectedSnapshotHash != savedSnapshot.hash) return refusal(
            request, savedSnapshot.hash, savedSnapshot.hash, activeLease, authority,
            "diagnostics.snapshotStale", "Expected project snapshot is stale",
        )
        if (request.semanticLease != activeLease || semantic.activeSnapshotHash() != savedSnapshot.hash) return refusal(
            request, savedSnapshot.hash, savedSnapshot.hash, activeLease, authority,
            "diagnostics.semanticLeaseStale", "Semantic session lease is stale or belongs to another snapshot",
        )
        validateSavedSnapshot(savedSnapshot)?.let { message ->
            return refusal(
                request, savedSnapshot.hash, savedSnapshot.hash, activeLease, authority,
                "diagnostics.savedSnapshotStale", message,
            )
        }
        val providerSnapshot = when (val source = request.sourceAuthority) {
            TypeScriptDiagnosticsSourceAuthority.SavedDisk -> savedSnapshot
            is TypeScriptDiagnosticsSourceAuthority.ImmutableEditorOverlay -> runCatching {
                ImmutableEditorOverlay.create(
                    savedSnapshot,
                    source.documents,
                    expectedLanguageId = request.languageId,
                ).providerSnapshot
            }.getOrElse { failure ->
                return refusal(
                    request, savedSnapshot.hash, savedSnapshot.hash, activeLease, authority,
                    "diagnostics.overlayInvalid", failure.message ?: "Editor overlay is invalid",
                )
            }
        }
        val result = semantic.exactDiagnostics(providerSnapshot)
        return when (result) {
            is ExternalSemanticDiagnostics.Available -> ready(
                request = request,
                projectSnapshotHash = savedSnapshot.hash,
                providerSnapshotHash = providerSnapshot.hash,
                activeLease = activeLease,
                authority = authorityMetadata(request.sourceAuthority, savedSnapshot.hash, providerSnapshot.hash),
                attestation = semantic.compilerAttestation(),
                process = result.processProvenance,
                diagnostics = result.diagnostics,
                snapshot = providerSnapshot,
            )
            is ExternalSemanticDiagnostics.Unavailable -> failure(
                request = request,
                projectSnapshotHash = savedSnapshot.hash,
                providerSnapshotHash = providerSnapshot.hash,
                activeLease = activeLease,
                authority = authorityMetadata(request.sourceAuthority, savedSnapshot.hash, providerSnapshot.hash),
                attestation = semantic.compilerAttestation(),
                diagnostic = result.diagnostic,
            )
        }
    }

    fun notReady(
        request: TypeScriptDiagnosticsRequest,
        currentSnapshotHash: String,
        activeLease: String? = null,
    ): JsonObject = refusal(
        request,
        currentSnapshotHash,
        currentSnapshotHash,
        activeLease,
        authorityMetadata(request.sourceAuthority, currentSnapshotHash),
        "diagnostics.semanticSessionNotReady",
        "Exact compiler diagnostics require typescript.semantic.start and its current semanticLease",
    )

    private fun ready(
        request: TypeScriptDiagnosticsRequest,
        projectSnapshotHash: String,
        providerSnapshotHash: String,
        activeLease: String,
        authority: JsonObject,
        attestation: TypeScriptCompilerAttestation,
        process: org.refactorkit.core.SemanticProcessProvenance?,
        diagnostics: List<Diagnostic>,
        snapshot: ProjectSnapshot,
    ): JsonObject {
        val rendered = diagnostics.map { renderDiagnostic(it, snapshot) }
        var kept = rendered
        var response = envelope(
            request, "ready", projectSnapshotHash, providerSnapshotHash, activeLease, authority,
            attestation, process, null, kept, diagnostics.size, false,
        )
        while (response.utf8Size() > ProtocolLimits.MAX_DIAGNOSTICS_RESPONSE_BYTES && kept.isNotEmpty()) {
            kept = kept.dropLast(1)
            response = envelope(
                request, "ready", projectSnapshotHash, providerSnapshotHash, activeLease, authority,
                attestation, process, null, kept, diagnostics.size, true,
            )
        }
        check(response.utf8Size() <= ProtocolLimits.MAX_DIAGNOSTICS_RESPONSE_BYTES) {
            "diagnostics response envelope exceeds bounded size"
        }
        return response
    }

    private fun failure(
        request: TypeScriptDiagnosticsRequest,
        projectSnapshotHash: String,
        providerSnapshotHash: String,
        activeLease: String,
        authority: JsonObject,
        attestation: TypeScriptCompilerAttestation,
        diagnostic: Diagnostic,
    ): JsonObject {
        val status = if (diagnostic.code in EXECUTION_FAILURE_CODES) "error" else "refused"
        return envelope(
            request, status, projectSnapshotHash, providerSnapshotHash, activeLease, authority,
            attestation, null, failureObject(diagnostic.code ?: "diagnostics.unavailable", diagnostic.message),
            emptyList(), 0, false,
        )
    }

    private fun refusal(
        request: TypeScriptDiagnosticsRequest,
        projectSnapshotHash: String,
        providerSnapshotHash: String,
        activeLease: String?,
        authority: JsonObject,
        code: String,
        message: String,
    ): JsonObject = envelope(
        request, "refused", projectSnapshotHash, providerSnapshotHash, activeLease, authority,
        null, null, failureObject(code, message), emptyList(), 0, false,
    )

    private fun envelope(
        request: TypeScriptDiagnosticsRequest,
        status: String,
        projectSnapshotHash: String,
        providerSnapshotHash: String,
        activeLease: String?,
        authority: JsonObject,
        attestation: TypeScriptCompilerAttestation?,
        process: org.refactorkit.core.SemanticProcessProvenance?,
        failure: JsonObject?,
        diagnostics: List<JsonObject>,
        totalDiagnostics: Int,
        truncated: Boolean,
    ): JsonObject = buildJsonObject {
        put("schemaVersion", TypeScriptDiagnosticsContract.CONTRACT_VERSION)
        put("requestId", request.requestId)
        put("languageId", request.languageId)
        put("status", status)
        put("provider", buildJsonObject {
            put("name", RefactorKitVersion.NAME)
            put("version", RefactorKitVersion.VERSION)
            put("apiVersion", RefactorKitVersion.API_VERSION)
        })
        put("backend", TypeScriptDiagnosticsContract.BACKEND)
        put(
            "semanticLease",
            (if (status == "ready" || status == "error") activeLease else request.semanticLease)
                ?.let(::JsonPrimitive) ?: JsonNull,
        )
        put("projectSnapshotHash", projectSnapshotHash)
        put("providerSnapshotHash", providerSnapshotHash)
        put("sourceAuthority", authority)
        put("rangeEncoding", "utf-16")
        put("rangeCapability", "exact-utf16-or-explicit-partial")
        put("runtime", runtimeMetadata())
        put("compilerAttestation", attestation?.let { value -> buildJsonObject {
            put("toolchainProvider", value.toolchainProvider)
            put("nodeVersion", value.nodeVersion)
            put("typeScriptVersion", value.typeScriptVersion)
            put("compilerSha256", value.compilerSha256)
            put("toolchainEvidenceSha256", value.toolchainEvidenceSha256)
            put("process", process?.let { provenance -> buildJsonObject {
                put("executableSha256", provenance.executableSha256)
                put("argumentsSha256", provenance.argumentsSha256)
                put("processId", provenance.pid)
            }} ?: JsonNull)
        }} ?: JsonNull)
        put("failure", failure ?: JsonNull)
        put("diagnostics", JsonArray(diagnostics))
        put("diagnosticsTotal", totalDiagnostics)
        put("diagnosticsReturned", diagnostics.size)
        put("truncated", truncated)
        put("responseBytes", 0)
    }.withResponseBytes()

    private fun renderDiagnostic(diagnostic: Diagnostic, snapshot: ProjectSnapshot): JsonObject {
        val location = diagnostic.location
        return buildJsonObject {
            put("severity", diagnostic.severity.name.lowercase())
            put("message", diagnostic.message.take(ProtocolLimits.MAX_DIAGNOSTIC_MESSAGE_CHARS))
            diagnostic.code?.let { put("code", it.take(64)) }
            diagnostic.evidence?.let { put("evidence", it.name.lowercase()) }
            diagnostic.category?.let { put("category", it.name.lowercase().replace('_', '-')) }
            put("location", when {
                location == null || diagnostic.locationPrecision == DiagnosticLocationPrecision.NONE ->
                    buildJsonObject { put("kind", "none") }
                diagnostic.locationPrecision == DiagnosticLocationPrecision.LINE_ONLY -> buildJsonObject {
                    put("kind", "line")
                    put("path", normalizedPath(snapshot, location.path))
                    put("line", location.range.start.line)
                }
                else -> buildJsonObject {
                    put("kind", "range")
                    put("path", normalizedPath(snapshot, location.path))
                    put("encoding", "utf-16")
                    put("range", buildJsonObject {
                        put("start", buildJsonObject {
                            put("line", location.range.start.line)
                            put("character", location.range.start.character)
                        })
                        put("end", buildJsonObject {
                            put("line", location.range.end.line)
                            put("character", location.range.end.character)
                        })
                    })
                }
            })
        }
    }

    private fun validateSavedSnapshot(snapshot: ProjectSnapshot): String? {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        snapshot.files.forEach { source ->
            val relative = source.path.normalize()
            if (relative.isAbsolute || relative.startsWith("..") || relative.toString().isBlank()) {
                return "Opened project snapshot contains an unsafe source path"
            }
            val file = root.resolve(relative).normalize()
            if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                return "Saved source '${ProtocolPath.serialize(relative)}' is missing or unsafe"
            }
            val bytes = runCatching { Files.readAllBytes(file) }.getOrNull()
                ?: return "Saved source '${ProtocolPath.serialize(relative)}' cannot be read"
            if (!bytes.contentEquals(source.content.toByteArray(Charsets.UTF_8))) {
                return "Saved source '${ProtocolPath.serialize(relative)}' changed after project.open"
            }
        }
        return null
    }

    private fun authorityMetadata(
        authority: TypeScriptDiagnosticsSourceAuthority,
        baseSnapshotHash: String,
        providerSnapshotHash: String = baseSnapshotHash,
    ): JsonObject = when (authority) {
        TypeScriptDiagnosticsSourceAuthority.SavedDisk -> buildJsonObject {
            put("kind", "saved-disk")
            put("baseSnapshotHash", baseSnapshotHash)
        }
        is TypeScriptDiagnosticsSourceAuthority.ImmutableEditorOverlay -> buildJsonObject {
            put("kind", "immutable-editor-overlay")
            put("baseSnapshotHash", baseSnapshotHash)
            put("overlayHash", ImmutableEditorOverlay.computeHash(authority.documents))
            put("providerSnapshotHash", providerSnapshotHash)
            put("documents", buildJsonArray {
                authority.documents.sortedBy { it.path.toString() }.forEach { document ->
                    add(buildJsonObject {
                        put("path", ProtocolPath.serialize(document.path.normalize()))
                        put("version", document.version)
                        put("contentSha256", sha256(document.content.toByteArray(Charsets.UTF_8)))
                    })
                }
            })
        }
    }

    private fun runtimeMetadata() = buildJsonObject {
        put("executionMode", "external-process")
        put("supportsTimeout", true)
        put("supportsCancellation", true)
        put("usesWorkspaceOverlay", true)
        put("recordsProcessProvenance", true)
        put("limits", buildJsonObject {
            put("requestTimeoutMillis", TypeScriptDiagnosticsContract.REQUEST_TIMEOUT_MILLIS)
            put("maxInputBytes", TypeScriptDiagnosticsContract.MAX_INPUT_BYTES)
            put("maxOutputBytes", TypeScriptDiagnosticsContract.MAX_OUTPUT_BYTES)
            put("maxStderrBytes", TypeScriptDiagnosticsContract.MAX_STDERR_BYTES)
            put("maxProcesses", TypeScriptDiagnosticsContract.MAX_PROCESSES)
            put("compilerHeapMiB", TypeScriptDiagnosticsContract.COMPILER_HEAP_MIB)
            put("maxDiagnostics", TypeScriptDiagnosticsContract.MAX_DIAGNOSTICS)
            put("maxDiagnosticMessageChars", TypeScriptDiagnosticsContract.MAX_DIAGNOSTIC_MESSAGE_CHARS)
            put("maxResponseBytes", ProtocolLimits.MAX_DIAGNOSTICS_RESPONSE_BYTES)
        })
    }

    private fun parseDocument(value: JsonObject): EditorOverlayDocument {
        require(value.keys == setOf("path", "version", "content")) { "overlay document fields are invalid" }
        val rawPath = value.requiredString("path")
        val path = runCatching { Path.of(rawPath).normalize() }.getOrElse { throw IllegalArgumentException("overlay path is invalid") }
        require(!path.isAbsolute && !path.startsWith("..") && path.toString().isNotBlank()) { "overlay path is unsafe" }
        val version = (value["version"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
            ?: throw IllegalArgumentException("overlay version must be an integer")
        require(version >= 0) { "overlay version must be non-negative" }
        val content = value.requiredString("content")
        return EditorOverlayDocument(path, version.toLong(), content)
    }

    private fun normalizedPath(snapshot: ProjectSnapshot, path: Path): String {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val normalized = if (path.isAbsolute) path.toAbsolutePath().normalize() else root.resolve(path).normalize()
        require(normalized.startsWith(root) && normalized != root) { "diagnostic path is outside workspace" }
        return ProtocolPath.serialize(root.relativize(normalized))
    }

    private fun failureObject(code: String, message: String) = buildJsonObject {
        put("code", code.take(128))
        put("message", message.take(ProtocolLimits.MAX_DIAGNOSTIC_MESSAGE_CHARS))
    }

    private fun JsonObject.withResponseBytes(): JsonObject {
        var result = this
        repeat(8) {
            val bytes = result.toString().toByteArray(Charsets.UTF_8).size
            val updated = JsonObject(result.toMutableMap().apply { put("responseBytes", JsonPrimitive(bytes)) })
            if (updated.toString().toByteArray(Charsets.UTF_8).size == bytes) return updated
            result = updated
        }
        error("diagnostics response byte count did not converge")
    }

    private fun JsonObject.utf8Size(): Int = toString().toByteArray(Charsets.UTF_8).size
    private fun JsonObject.requiredString(name: String): String = (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)?.content?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("$name is required and must be a non-blank string")
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val LEASE = Regex("semantic-[0-9a-f-]{36}")
    private val EXECUTION_FAILURE_CODES = setOf(
        "typescript.compilerDiagnosticsTimeout",
        "typescript.compilerDiagnosticsFailed",
        "typescript.compilerDiagnosticsInvalid",
        "typescript.compilerDiagnosticsIncomplete",
        "typescript.compilerDiagnosticsInternal",
    )
}
