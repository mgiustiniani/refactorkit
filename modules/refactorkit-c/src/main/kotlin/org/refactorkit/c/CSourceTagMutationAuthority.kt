package org.refactorkit.c

import kotlinx.serialization.json.*
import java.nio.file.Path

/** Mutation authority is stricter than source-owned navigation; no filesystem or application state. */
internal object CSourceTagMutationAuthority {
    fun decode(asts: Map<Path, JsonObject>, sources: Map<Path, String>, selectedFile: Path,
        selectedOffset: Int, expectedTranslationUnits: Set<Path>): CTagReadout {
        val readout = CTagRenameFactsDecoder.decodeSource(asts, sources, selectedFile, selectedOffset, expectedTranslationUnits)
        if (readout !is CTagReadout.Found) return readout
        return try {
            val facts = readout.facts
            val declarations = facts.sites.filter { it.kind == CTagSiteKind.DECLARATION }.map {
                CClangSourceCoordinate(it.file, it.span.start, it.span.endExclusive)
            }.toSet()
            for ((tu, observations) in facts.typeObservations.groupBy { it.translationUnit }) {
                val entries = CClangSourceGraph.read(asts.getValue(tu), tu, sources)
                val byPath = entries.associateBy { it.path }
                val selectedRecords = entries.filter { it.location in declarations &&
                    text(it.node, "kind") in setOf("RecordDecl", "EnumDecl") }.map { it.path }
                val typedPaths = observations.filter { it.property == "type" }.map { it.astPath }.toSet()
                for (observation in observations) {
                    val entry = byPath.getValue(observation.astPath)
                    val kind = text(entry.node, "kind")
                    val storage = text(entry.node, "storageClass")
                    // Aliases and containing records need their own transitive consumer proof.
                    if (kind == "TypedefDecl" || kind == "FieldDecl" &&
                        selectedRecords.none { entry.path.startsWith("$it/") }) return exposed()
                    if (kind == "FunctionDecl" && storage != "static") return exposed()
                    if (kind == "VarDecl" && (storage == "extern" || entry.parent == "TU" && storage != "static")) return exposed()
                    if (observation.property == "type" && pointerType(entry.node)) {
                        val parent = byPath[entry.parent]
                        if (parent != null && text(parent.node, "kind")?.endsWith("CastExpr") == true &&
                            parent.path !in typedPaths) return exposed()
                    }
                }
            }
            readout
        } catch (failure: CClangSourceGraphFailure) {
            CTagReadout.Refused(CTagFactsRefusal.valueOf(failure.reason.name))
        } catch (_: Exception) {
            CTagReadout.Refused(CTagFactsRefusal.INVALID_AST)
        }
    }

    private fun pointerType(node: JsonObject): Boolean {
        val type = node["type"] as? JsonObject ?: return false
        return listOf("qualType", "desugaredQualType").any { text(type, it)?.contains('*') == true }
    }
    private fun text(node: JsonObject, key: String) = (node[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun exposed() = CTagReadout.Refused(CTagFactsRefusal.SOURCE_TYPE_EXPOSURE_UNQUALIFIED)
}
