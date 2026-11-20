package org.refactorkit.c

import kotlinx.serialization.json.*
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdits
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

internal enum class CCompilerDeclarationKind { FUNCTION, PARAMETER, VARIABLE, STRUCT, UNION, ENUM, TYPEDEF, ENUMERATOR, FIELD, LABEL }
internal enum class CCompilerLookupSpace { ORDINARY, TAG, MEMBER, LABEL }
internal enum class CCompilerLinkage { EXTERNAL, INTERNAL, NONE }
internal data class CCompilerDeclaration(
    val id: String,
    val translationUnit: Path,
    val file: Path,
    val range: SourceRange,
    val name: String,
    val kind: CCompilerDeclarationKind,
    val lookupSpace: CCompilerLookupSpace,
    val linkage: CCompilerLinkage,
    val scopeId: String,
)
internal data class CCompilerDeclarationSearch(val items: List<CCompilerDeclaration>, val total: Int, val truncated: Boolean)
internal enum class CCompilerDeclarationRefusal { INPUT_LIMIT, INCOMPLETE_CONTEXT, UNOWNED_SOURCE, SOURCE_UNSUPPORTED, INVALID_AST, SOURCE_MISMATCH, MACRO_OR_UNOWNED_RANGE }
internal sealed interface CCompilerDeclarationReadout {
    data class Found(val index: CCompilerDeclarationIndex) : CCompilerDeclarationReadout
    data class Refused(val reason: CCompilerDeclarationRefusal) : CCompilerDeclarationReadout
}
private class DeclarationFailure(val reason: CCompilerDeclarationRefusal) : RuntimeException()
private fun declarationRequire(value: Boolean, reason: CCompilerDeclarationRefusal) { if (!value) throw DeclarationFailure(reason) }

/** Immutable per-TU declarations; no equivalence between separate compiler bindings is inferred. */
internal class CCompilerDeclarationIndex private constructor(declarations: List<CCompilerDeclaration>) {
    val declarations: List<CCompilerDeclaration> = Collections.unmodifiableList(ArrayList(declarations))

    fun search(text: String, translationUnit: Path? = null, file: Path? = null, limit: Int = 256): CCompilerDeclarationSearch {
        require(text.length <= 512 && limit in 1..256) { "C declaration query exceeds its bounded profile" }
        val selected = declarations.filter { it.name.contains(text, ignoreCase = true) &&
            (translationUnit == null || it.translationUnit == translationUnit) && (file == null || it.file == file) }
        return CCompilerDeclarationSearch(Collections.unmodifiableList(ArrayList(selected.take(limit))), selected.size, selected.size > limit)
    }

    fun withPrototypeTags(facts: CPrototypeTagNavigationFacts): CCompilerDeclarationIndex {
        val combined = declarations + facts.declarations
        require(combined.size <= 16_384 && combined.map { it.id }.toSet().size == combined.size)
        return CCompilerDeclarationIndex(combined.sortedWith(compareBy(
            { it.translationUnit.toString() }, { it.file.toString() }, { it.range.start.line }, { it.range.start.character }, { it.kind.name })))
    }

    companion object {
        fun analyze(asts: Map<Path, JsonObject>, sources: Map<Path, String>,
            logicalPaths: Map<Path, Path> = sources.keys.associateWith { it }): CCompilerDeclarationReadout = try {
            declarationRequire(asts.size <= 16 && sources.size <= 32 && sources.values.sumOf { it.length.toLong() } <= 16 * 1024 * 1024,
                CCompilerDeclarationRefusal.INPUT_LIMIT)
            declarationRequire(sources.isNotEmpty() && logicalPaths.keys == sources.keys &&
                logicalPaths.values.toSet().size == sources.size && sources.keys.all { it == it.normalize() } &&
                logicalPaths.values.all { !it.isAbsolute && it == it.normalize() && it.toString().isNotBlank() && !it.startsWith("..") },
                CCompilerDeclarationRefusal.UNOWNED_SOURCE)
            declarationRequire(logicalPaths.values.all { it.toString().endsWith(".c") || it.toString().endsWith(".h") } &&
                CHeaderFieldSourcePolicy.check(sources) == CHeaderFieldSourceAdmission.Admitted,
                CCompilerDeclarationRefusal.SOURCE_UNSUPPORTED)
            val expected = sources.keys.filter { logicalPaths.getValue(it).toString().endsWith(".c") }.toSet()
            declarationRequire(expected.isNotEmpty() && asts.keys == expected, CCompilerDeclarationRefusal.INCOMPLETE_CONTEXT)
            val declarations = mutableListOf<CCompilerDeclaration>()
            for ((tu, ast) in asts.entries.sortedBy { logicalPaths.getValue(it.key).toString() }) {
                val logicalTu = logicalPaths.getValue(tu)
                val entries = CClangSourceGraph.read(ast, tu, sources)
                val byPath = entries.associateBy { it.path }
                val variables = linkedMapOf<String, CClangSourceEntry>()
                entries.filter { string(it.node, "kind") == "VarDecl" }.forEach { entry ->
                    string(entry.node, "id")?.let { id ->
                        declarationRequire(variables.putIfAbsent(id, entry) == null, CCompilerDeclarationRefusal.INVALID_AST)
                    }
                }
                val observedIds = mutableMapOf<String, CCompilerDeclaration>()
                for (entry in entries) {
                    val node = entry.node
                    if (node["isImplicit"] == JsonPrimitive(true)) continue
                    val nativeKind = string(node, "kind") ?: throw DeclarationFailure(CCompilerDeclarationRefusal.INVALID_AST)
                    val name = string(node, "name")?.takeIf { it.isNotBlank() } ?: continue
                    val kind = kind(node, nativeKind)
                    if (kind == null) {
                        declarationRequire(!nativeKind.endsWith("Decl"), CCompilerDeclarationRefusal.SOURCE_UNSUPPORTED)
                        continue
                    }
                    declarationRequire(name.length <= 512 && name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")), CCompilerDeclarationRefusal.SOURCE_UNSUPPORTED)
                    val location = (if (kind == CCompilerDeclarationKind.LABEL) entry.begin else entry.location)
                        ?: throw DeclarationFailure(CCompilerDeclarationRefusal.INVALID_AST)
                    val content = sources.getValue(location.file)
                    declarationRequire(content.substring(location.start, location.end) == name, CCompilerDeclarationRefusal.SOURCE_MISMATCH)
                    val logicalFile = logicalPaths.getValue(location.file)
                    val nativeId = string(node, if (kind == CCompilerDeclarationKind.LABEL) "declId" else "id")
                        ?: throw DeclarationFailure(CCompilerDeclarationRefusal.INVALID_AST)
                    declarationRequire(nativeId.length in 1..256, CCompilerDeclarationRefusal.INVALID_AST)
                    val linkage = when {
                        kind == CCompilerDeclarationKind.FUNCTION -> if (string(node, "storageClass") == "static") CCompilerLinkage.INTERNAL else CCompilerLinkage.EXTERNAL
                        kind == CCompilerDeclarationKind.VARIABLE -> {
                            var origin = entry
                            val seen = mutableSetOf<String>()
                            while (true) {
                                val id = string(origin.node, "id") ?: throw DeclarationFailure(CCompilerDeclarationRefusal.INVALID_AST)
                                declarationRequire(seen.add(id) && seen.size <= 256, CCompilerDeclarationRefusal.INVALID_AST)
                                val previous = string(origin.node, "previousDecl") ?: break
                                val prior = variables[previous] ?: throw DeclarationFailure(CCompilerDeclarationRefusal.INVALID_AST)
                                declarationRequire(string(prior.node, "name") == name, CCompilerDeclarationRefusal.INVALID_AST)
                                origin = prior
                            }
                            var parent = byPath[origin.parent]
                            var inFunction = false
                            while (parent != null) {
                                if (string(parent.node, "kind") == "FunctionDecl") inFunction = true
                                parent = byPath[parent.parent]
                            }
                            when {
                                !inFunction && string(origin.node, "storageClass") == "static" -> CCompilerLinkage.INTERNAL
                                !inFunction || string(origin.node, "storageClass") == "extern" -> CCompilerLinkage.EXTERNAL
                                else -> CCompilerLinkage.NONE
                            }
                        }
                        else -> CCompilerLinkage.NONE
                    }
                    val declaration = CCompilerDeclaration(
                        id = identity(logicalTu, logicalFile, kind, location.start, location.end, name),
                        translationUnit = logicalTu, file = logicalFile,
                        range = SourceRange(TextEdits.positionForOffset(content, location.start), TextEdits.positionForOffset(content, location.end)),
                        name = name, kind = kind, lookupSpace = lookupSpace(kind), linkage = linkage,
                        scopeId = "${ProtocolPath.serialize(logicalTu)}#${entry.parent}",
                    )
                    val previous = observedIds.putIfAbsent(nativeId, declaration)
                    declarationRequire(previous == null || previous == declaration, CCompilerDeclarationRefusal.INVALID_AST)
                    if (previous == null) declarations.add(declaration)
                    declarationRequire(declarations.size <= 16_384, CCompilerDeclarationRefusal.INPUT_LIMIT)
                }
            }
            declarationRequire(declarations.map { it.id }.toSet().size == declarations.size, CCompilerDeclarationRefusal.INVALID_AST)
            CCompilerDeclarationReadout.Found(CCompilerDeclarationIndex(declarations.sortedWith(compareBy(
                { it.translationUnit.toString() }, { it.file.toString() }, { it.range.start.line }, { it.range.start.character }, { it.kind.name }))))
        } catch (failure: DeclarationFailure) {
            CCompilerDeclarationReadout.Refused(failure.reason)
        } catch (failure: CClangSourceGraphFailure) {
            CCompilerDeclarationReadout.Refused(when (failure.reason) {
                CClangSourceGraphRefusal.INPUT_LIMIT -> CCompilerDeclarationRefusal.INPUT_LIMIT
                CClangSourceGraphRefusal.INVALID_AST -> CCompilerDeclarationRefusal.INVALID_AST
                CClangSourceGraphRefusal.MACRO_OR_UNOWNED_RANGE -> CCompilerDeclarationRefusal.MACRO_OR_UNOWNED_RANGE
            })
        }

        private fun string(node: JsonObject, key: String): String? = (node[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun kind(node: JsonObject, value: String): CCompilerDeclarationKind? = when (value) {
            "FunctionDecl" -> CCompilerDeclarationKind.FUNCTION
            "ParmVarDecl" -> CCompilerDeclarationKind.PARAMETER
            "VarDecl" -> CCompilerDeclarationKind.VARIABLE
            "RecordDecl" -> when (string(node, "tagUsed")) {
                "struct" -> CCompilerDeclarationKind.STRUCT
                "union" -> CCompilerDeclarationKind.UNION
                else -> throw DeclarationFailure(CCompilerDeclarationRefusal.INVALID_AST)
            }
            "EnumDecl" -> CCompilerDeclarationKind.ENUM
            "TypedefDecl" -> CCompilerDeclarationKind.TYPEDEF
            "EnumConstantDecl" -> CCompilerDeclarationKind.ENUMERATOR
            "FieldDecl" -> CCompilerDeclarationKind.FIELD
            "LabelStmt" -> CCompilerDeclarationKind.LABEL
            else -> null
        }

        private fun lookupSpace(kind: CCompilerDeclarationKind): CCompilerLookupSpace = when (kind) {
            CCompilerDeclarationKind.STRUCT, CCompilerDeclarationKind.UNION, CCompilerDeclarationKind.ENUM -> CCompilerLookupSpace.TAG
            CCompilerDeclarationKind.FIELD -> CCompilerLookupSpace.MEMBER
            CCompilerDeclarationKind.LABEL -> CCompilerLookupSpace.LABEL
            else -> CCompilerLookupSpace.ORDINARY
        }

        internal fun identity(tu: Path, file: Path, kind: CCompilerDeclarationKind, start: Int, end: Int, name: String): String {
            val fields = listOf(ProtocolPath.serialize(tu), ProtocolPath.serialize(file), kind.name, start.toString(), end.toString(), name)
            val input = fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
            return "c-decl:" + MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) }
        }
    }
}
