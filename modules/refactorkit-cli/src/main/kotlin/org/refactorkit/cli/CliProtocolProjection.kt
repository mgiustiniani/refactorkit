package org.refactorkit.cli

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.TextEdit
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.invariantSeparatorsPathString

/** Closed command-catalogue versions exposed by the source-built CLI. */
internal enum class CliCommandCatalogueVersion(
    val schema: String,
    val number: Int,
) {
    V1("refactorkit.cli-command-catalog/v1", 1),
    V2("refactorkit.cli-command-catalog/v2", 2),
}

/** Strict parser and JSON infrastructure adapter for the transient command catalogue. */
internal object CliCommandCatalogueProjection {
    fun select(args: List<String>): CliCommandCatalogueVersion = when (args) {
        listOf("--json") -> CliCommandCatalogueVersion.V2
        listOf("--json", "--schema-version", "1") -> CliCommandCatalogueVersion.V1
        else -> throw IllegalArgumentException(
            "Usage: refactorkit commands --json [--schema-version 1]",
        )
    }

    fun render(version: CliCommandCatalogueVersion): String = Json.Default.encodeToString(
        buildJsonObject {
            put("schema", version.schema)
            put("schemaVersion", version.number)
            put("commands", buildJsonArray {
                add(entry(
                    version,
                    name = "java create-module",
                    operation = "java.createMavenModule",
                    requiredArguments = listOf("--module-name", "--parent-pom"),
                ))
                add(entry(
                    version,
                    name = "java move-across-maven-modules",
                    operation = "java.moveAcrossMavenModules",
                    requiredArguments = listOf("--from", "--to"),
                ))
                add(entry(
                    version,
                    name = "java rename-module",
                    operation = "java.renameMavenModule",
                    requiredArguments = listOf("--old-module-dir", "--new-module-dir"),
                ))
            })
        },
    )

    private fun entry(
        version: CliCommandCatalogueVersion,
        name: String,
        operation: String,
        requiredArguments: List<String>,
    ): JsonObject = buildJsonObject {
        put("name", name)
        put("operation", operation)
        put("aliases", buildJsonArray {})
        put("modes", buildJsonArray {
            add(JsonPrimitive("preview"))
            add(JsonPrimitive("apply"))
        })
        put("mutationAuthority", "refactorkit-managed")
        put(
            "jsonSupport",
            if (version == CliCommandCatalogueVersion.V2 && name == "java rename-module") {
                "preview-only"
            } else {
                "catalog-only"
            },
        )
        put("stability", "experimental")
        put("requiredArguments", buildJsonArray {
            requiredArguments.forEach { add(JsonPrimitive(it)) }
        })
    }
}

/** Output selection for the one qualified machine-readable module-rename preview. */
internal sealed class CliRenameModuleOutputSelection {
    object Human : CliRenameModuleOutputSelection()
    data class JsonPreview(val requestId: String) : CliRenameModuleOutputSelection()
    data class Rejected(val message: String) : CliRenameModuleOutputSelection()
}

/**
 * Inbound grammar and outbound ACL for `refactorkit.cli-result/v1` preview output.
 * It never serializes internal plan identities and has no apply or persistence authority.
 */
internal object CliRenameModulePreviewProtocol {
    private const val COMMAND = "java.renameMavenModule"
    private const val PLAN_DIGEST_SCHEMA = "refactorkit.cli-result/plan-sha256/v1"
    private const val MAX_CHANGES = 128
    private const val MAX_CHANGES_JSON_BYTES = 524_288
    private const val MAX_PATH_BYTES = 4_096
    private val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val SNAPSHOT_SHA256 = Regex("[0-9a-f]{64}")
    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")

    fun select(args: List<String>): CliRenameModuleOutputSelection {
        val requestTokens = args.withIndex().filter { it.value == "--request-id" }
        val malformedRequestTokens = args.filter { it.startsWith("--request-id") && it != "--request-id" }
        val jsonCount = args.count { it == "--json" }

        if (malformedRequestTokens.isNotEmpty()) {
            return CliRenameModuleOutputSelection.Rejected("rename-module requires --request-id <id>")
        }
        if (jsonCount == 0) {
            return if (requestTokens.isEmpty()) {
                CliRenameModuleOutputSelection.Human
            } else {
                CliRenameModuleOutputSelection.Rejected("rename-module --request-id requires --json")
            }
        }
        if (jsonCount != 1) {
            return CliRenameModuleOutputSelection.Rejected("rename-module accepts --json exactly once")
        }
        if ("--apply" in args) {
            return CliRenameModuleOutputSelection.Rejected(
                "rename-module --json supports preview only; JSON apply is not supported",
            )
        }
        if (requestTokens.size > 1) {
            return CliRenameModuleOutputSelection.Rejected("rename-module accepts --request-id exactly once")
        }

        val requestId = requestTokens.singleOrNull()?.let { token ->
            val value = args.getOrNull(token.index + 1)
            if (value == null || value.startsWith("--") || !REQUEST_ID.matches(value)) {
                return CliRenameModuleOutputSelection.Rejected(
                    "rename-module --request-id must match [A-Za-z0-9][A-Za-z0-9._:-]{0,127}",
                )
            }
            value
        } ?: "request-${UUID.randomUUID()}"

        return CliRenameModuleOutputSelection.JsonPreview(requestId)
    }

    fun render(plan: PatchPlan, requestId: String): String {
        require(REQUEST_ID.matches(requestId)) { "invalid request ID" }
        require(plan.operation == COMMAND) { "unexpected plan operation" }
        require(plan.status == PatchStatus.PREVIEW) { "only a successful PREVIEW plan can be projected" }
        require(SNAPSHOT_SHA256.matches(plan.snapshotHash)) { "invalid snapshot SHA-256" }
        require(plan.requiresUserApproval) { "module-rename preview must require approval" }
        require(plan.diagnosticsBefore.isEmpty() && plan.diagnosticsAfterPreview.isEmpty()) {
            "non-empty diagnostics are outside the qualified preview projection"
        }
        require(plan.workspaceEdit.edits.isNotEmpty()) { "module-rename preview must contain an edit" }
        require(plan.workspaceEdit.edits.size <= MAX_CHANGES) {
            "module-rename preview exceeds the admitted complete-change projection limit"
        }

        val changes = visibleChanges(plan)
        val changesJsonBytes = Json.Default.encodeToString(changes).toByteArray(StandardCharsets.UTF_8).size
        require(changesJsonBytes <= MAX_CHANGES_JSON_BYTES) {
            "module-rename preview exceeds the admitted complete-change JSON limit"
        }
        val digest = planDigest(plan)
        val planProjection = buildJsonObject {
            put("sha256", digest)
            put("snapshotSha256", plan.snapshotHash)
            put("requiresApproval", true)
            put("changeCount", plan.workspaceEdit.edits.size)
            put("changes", changes)
        }
        return Json.Default.encodeToString(buildJsonObject {
            put("schemaVersion", 1)
            put("command", COMMAND)
            put("requestId", requestId)
            put("outcome", "preview")
            put("plan", planProjection)
            put("transaction", JsonNull)
            put("diagnostics", buildJsonArray {})
            put("truncated", false)
        })
    }

    private fun visibleChanges(plan: PatchPlan): JsonArray = buildJsonArray {
        plan.workspaceEdit.edits.forEach { edit ->
            add(when (edit) {
                is FileEdit.Modify -> buildJsonObject {
                    put("kind", "modify")
                    put("path", canonicalPath(edit.path))
                    put("previousPath", JsonNull)
                }
                is FileEdit.Rename -> buildJsonObject {
                    put("kind", "move")
                    put("path", canonicalPath(edit.newPath))
                    put("previousPath", canonicalPath(edit.path))
                }
                is FileEdit.Create, is FileEdit.Delete ->
                    throw IllegalArgumentException("create/delete edits are outside refactorkit.cli-result/v1 preview")
            })
        }
    }

    private fun planDigest(plan: PatchPlan): String {
        val preimage = ByteArrayOutputStream()
        DataOutputStream(preimage).use { output ->
            output.writeFramed(PLAN_DIGEST_SCHEMA.toByteArray(StandardCharsets.UTF_8))
            output.writeFramed(COMMAND.toByteArray(StandardCharsets.UTF_8))
            output.writeFramed(plan.snapshotHash.toByteArray(StandardCharsets.UTF_8))
            output.writeByte(0x01)
            output.writeInt(plan.workspaceEdit.edits.size)
            plan.workspaceEdit.edits.forEach { edit ->
                when (edit) {
                    is FileEdit.Modify -> {
                        output.writeByte(0x01)
                        output.writeFramed(canonicalPath(edit.path).toByteArray(StandardCharsets.UTF_8))
                        val textEdits = canonicalTextEdits(edit.textEdits)
                        output.writeInt(textEdits.size)
                        textEdits.forEach { textEdit ->
                            output.writeInt(textEdit.range.start.line)
                            output.writeInt(textEdit.range.start.character)
                            output.writeInt(textEdit.range.end.line)
                            output.writeInt(textEdit.range.end.character)
                            output.writeFramed(textEdit.newText.toByteArray(StandardCharsets.UTF_8))
                        }
                    }
                    is FileEdit.Rename -> {
                        output.writeByte(0x02)
                        output.writeFramed(canonicalPath(edit.path).toByteArray(StandardCharsets.UTF_8))
                        output.writeFramed(canonicalPath(edit.newPath).toByteArray(StandardCharsets.UTF_8))
                    }
                    is FileEdit.Create, is FileEdit.Delete ->
                        throw IllegalArgumentException("create/delete edits are outside refactorkit.cli-result/v1 digest")
                }
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(preimage.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun canonicalTextEdits(edits: List<TextEdit>): List<TextEdit> {
        val sorted = edits.sortedWith(
            compareBy<TextEdit> { it.range.start.line }
                .thenBy { it.range.start.character }
                .thenBy { it.range.end.line }
                .thenBy { it.range.end.character },
        )
        sorted.zipWithNext().forEach { (left, right) ->
            require(!left.range.overlaps(right.range)) { "overlapping text edits cannot be projected" }
        }
        return sorted
    }

    private fun canonicalPath(path: Path): String {
        require(!path.isAbsolute) { "CLI result paths must be workspace-relative" }
        require(path == path.normalize()) { "CLI result paths must already be normalized" }
        val value = path.invariantSeparatorsPathString
        require(value.isNotEmpty() && value != ".") { "CLI result paths must not be empty" }
        require(!DRIVE_PREFIX.containsMatchIn(value)) { "CLI result paths must not contain a drive prefix" }
        require('\\' !in value) { "CLI result paths must use '/' separators" }
        require(value.none { character -> character.code < 0x20 || character.code == 0x7f }) {
            "CLI result paths must not contain control characters"
        }
        require(value.split('/').none { segment -> segment.isEmpty() || segment == "." || segment == ".." }) {
            "CLI result paths must contain only canonical segments"
        }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_PATH_BYTES) {
            "CLI result path exceeds the v1 byte limit"
        }
        return value
    }

    private fun DataOutputStream.writeFramed(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }
}
