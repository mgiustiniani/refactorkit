package org.refactorkit.treesitter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ExternalFileEditProposal
import org.refactorkit.core.ExternalWorkspaceEditProposal
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import java.net.URI
import java.nio.file.Path

sealed interface ExternalLspWorkspaceEditParsing {
    data class Accepted(val proposal: ExternalWorkspaceEditProposal) : ExternalLspWorkspaceEditParsing
    data class Refused(val diagnostics: List<Diagnostic>) : ExternalLspWorkspaceEditParsing
}

/** Strict parser for the two standard LSP WorkspaceEdit representations. */
object ExternalLspWorkspaceEditParser {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }

    fun parse(
        workspaceEditJson: String,
        providerId: String,
        providerVersion: String?,
    ): ExternalLspWorkspaceEditParsing {
        val root = try {
            json.parseToJsonElement(workspaceEditJson) as? JsonObject
                ?: return refused("externalEdit.schemaInvalid", "LSP WorkspaceEdit must be an object")
        } catch (_: Exception) {
            return refused("externalEdit.schemaInvalid", "LSP WorkspaceEdit is not valid JSON")
        }
        val unknown = root.keys - setOf("changes", "documentChanges", "changeAnnotations")
        if (unknown.isNotEmpty()) return refused("externalEdit.schemaInvalid", "LSP WorkspaceEdit has unknown fields")
        if (root["changes"] != null && root["documentChanges"] != null) {
            return refused("externalEdit.schemaAmbiguous", "LSP WorkspaceEdit cannot contain both changes and documentChanges")
        }
        val edits = try {
            when {
                root["changes"] != null -> parseChanges(root.getValue("changes"))
                root["documentChanges"] != null -> parseDocumentChanges(root.getValue("documentChanges"))
                else -> emptyList()
            }
        } catch (failure: SchemaFailure) {
            return refused(failure.code, failure.message ?: "LSP WorkspaceEdit schema is invalid")
        } catch (_: Exception) {
            return refused("externalEdit.schemaInvalid", "LSP WorkspaceEdit schema is invalid")
        }
        return ExternalLspWorkspaceEditParsing.Accepted(ExternalWorkspaceEditProposal(
            providerId = providerId,
            providerVersion = providerVersion,
            edits = edits,
        ))
    }

    private fun parseChanges(element: JsonElement): List<ExternalFileEditProposal> {
        val changes = element as? JsonObject ?: fail("externalEdit.schemaInvalid", "changes must be an object")
        return changes.entries.sortedBy(Map.Entry<String, JsonElement>::key).map { (uri, value) ->
            ExternalFileEditProposal.Modify(filePath(uri), parseTextEdits(value), documentVersion = null)
        }
    }

    private fun parseDocumentChanges(element: JsonElement): List<ExternalFileEditProposal> {
        val changes = element as? JsonArray ?: fail("externalEdit.schemaInvalid", "documentChanges must be an array")
        return changes.map { item ->
            val operation = item as? JsonObject ?: fail("externalEdit.schemaInvalid", "documentChanges entry must be an object")
            when (operation["kind"]?.string()) {
                "create" -> {
                    requireOnly(operation, setOf("kind", "uri", "options", "annotationId"))
                    rejectUnsafeOptions(operation["options"])
                    ExternalFileEditProposal.Create(filePath(operation.requiredString("uri")), "")
                }
                "rename" -> {
                    requireOnly(operation, setOf("kind", "oldUri", "newUri", "options", "annotationId"))
                    rejectUnsafeOptions(operation["options"])
                    ExternalFileEditProposal.Rename(
                        filePath(operation.requiredString("oldUri")),
                        filePath(operation.requiredString("newUri")),
                    )
                }
                "delete" -> {
                    requireOnly(operation, setOf("kind", "uri", "options", "annotationId"))
                    rejectUnsafeOptions(operation["options"])
                    ExternalFileEditProposal.Delete(filePath(operation.requiredString("uri")))
                }
                null -> {
                    requireOnly(operation, setOf("textDocument", "edits"))
                    val document = operation["textDocument"] as? JsonObject
                        ?: fail("externalEdit.schemaInvalid", "TextDocumentEdit requires textDocument")
                    requireOnly(document, setOf("uri", "version"))
                    val versionElement = document["version"]
                    val version = when (versionElement) {
                        null, JsonNull -> null
                        else -> versionElement.jsonPrimitive.takeUnless(JsonPrimitive::isString)?.intOrNull?.toLong()
                            ?: fail("externalEdit.documentVersionInvalid", "document version must be an integer or null")
                    }
                    ExternalFileEditProposal.Modify(
                        filePath(document.requiredString("uri")),
                        parseTextEdits(operation["edits"] ?: fail("externalEdit.schemaInvalid", "TextDocumentEdit requires edits")),
                        version,
                    )
                }
                else -> fail("externalEdit.operationUnsupported", "Unsupported LSP resource operation")
            }
        }
    }

    private fun parseTextEdits(element: JsonElement): List<TextEdit> {
        val edits = element as? JsonArray ?: fail("externalEdit.schemaInvalid", "text edits must be an array")
        return edits.map { editElement ->
            val edit = editElement as? JsonObject ?: fail("externalEdit.schemaInvalid", "text edit must be an object")
            requireOnly(edit, setOf("range", "newText", "annotationId"))
            val range = edit["range"] as? JsonObject ?: fail("externalEdit.schemaInvalid", "text edit requires range")
            requireOnly(range, setOf("start", "end"))
            TextEdit(
                SourceRange(position(range["start"]), position(range["end"])),
                edit.requiredString("newText"),
            )
        }
    }

    private fun position(element: JsonElement?): SourcePosition {
        val position = element as? JsonObject ?: fail("externalEdit.schemaInvalid", "range position must be an object")
        requireOnly(position, setOf("line", "character"))
        val line = position["line"]?.jsonPrimitive?.intOrNull ?: fail("externalEdit.schemaInvalid", "position line must be an integer")
        val character = position["character"]?.jsonPrimitive?.intOrNull ?: fail("externalEdit.schemaInvalid", "position character must be an integer")
        if (line < 0 || character < 0) fail("externalEdit.schemaInvalid", "position values must be non-negative")
        return SourcePosition(line, character)
    }

    private fun filePath(uri: String): Path {
        val parsed = runCatching { URI(uri) }.getOrElse { fail("externalEdit.uriInvalid", "LSP edit URI is invalid") }
        if (!parsed.scheme.equals("file", ignoreCase = true)) fail("externalEdit.uriUnsupported", "LSP edit URI must use file scheme")
        return runCatching { Path.of(parsed) }.getOrElse { fail("externalEdit.uriInvalid", "LSP edit URI cannot be converted to a path") }
    }

    private fun rejectUnsafeOptions(element: JsonElement?) {
        if (element == null || element == JsonNull) return
        val options = element as? JsonObject ?: fail("externalEdit.schemaInvalid", "resource operation options must be an object")
        requireOnly(options, setOf("overwrite", "ignoreIfExists", "recursive", "ignoreIfNotExists"))
        if (options.values.any { it.jsonPrimitive.booleanOrNull == true }) {
            fail("externalEdit.unsafeOptions", "Overwrite, ignore, and recursive resource-operation options are refused")
        }
    }

    private fun requireOnly(value: JsonObject, allowed: Set<String>) {
        if ((value.keys - allowed).isNotEmpty()) fail("externalEdit.schemaInvalid", "LSP edit object has unknown fields")
    }

    private fun JsonObject.requiredString(name: String): String = this[name]?.string()
        ?: fail("externalEdit.schemaInvalid", "LSP edit requires string field '$name'")

    private fun JsonElement.string(): String? = (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun fail(code: String, message: String): Nothing = throw SchemaFailure(code, message)

    private fun refused(code: String, message: String) = ExternalLspWorkspaceEditParsing.Refused(listOf(Diagnostic(
        message = message,
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.SAFETY,
    )))

    private class SchemaFailure(val code: String, message: String) : IllegalArgumentException(message)
}
