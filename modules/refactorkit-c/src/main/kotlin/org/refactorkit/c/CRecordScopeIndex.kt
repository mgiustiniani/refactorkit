package org.refactorkit.c

import kotlinx.serialization.json.jsonPrimitive

internal class CRecordScopeFailure(val reason: CRecordNavigationRefusal) : RuntimeException()

/** Native compound scopes and declaration order for the admitted C record-read profile. */
internal class CRecordScopeIndex(entries: List<CClangSourceEntry>) {
    private val byPath = entries.associateBy { it.path }
    private val order = entries.mapIndexed { i, entry -> entry.path to i }.toMap()
    private val compounds = entries.filter { kind(it) == "CompoundStmt" }
    init {
        if (byPath.size != entries.size) fail(CRecordNavigationRefusal.INVALID_AST)
        if (compounds.size > 512) fail(CRecordNavigationRefusal.INPUT_LIMIT)
        compounds.forEach { compound ->
            val begin = compound.begin ?: fail(CRecordNavigationRefusal.INVALID_AST)
            val end = compound.end ?: fail(CRecordNavigationRefusal.INVALID_AST)
            if (begin.file != end.file || begin.start >= end.start) fail(CRecordNavigationRefusal.INVALID_AST)
        }
    }

    fun declarationScope(entry: CClangSourceEntry): String {
        if (entry.parent == "TU") return "TU"
        val parent = byPath[entry.parent] ?: fail(CRecordNavigationRefusal.INVALID_AST)
        val compound = byPath[parent.parent] ?: fail(CRecordNavigationRefusal.INVALID_AST)
        if (kind(parent) != "DeclStmt" || kind(compound) != "CompoundStmt")
            fail(CRecordNavigationRefusal.UNSUPPORTED_SOURCE)
        val begin = compound.begin ?: fail(CRecordNavigationRefusal.INVALID_AST)
        val end = compound.end ?: fail(CRecordNavigationRefusal.INVALID_AST)
        val location = entry.location ?: fail(CRecordNavigationRefusal.INVALID_AST)
        if (location.file != begin.file) fail(CRecordNavigationRefusal.UNSUPPORTED_SOURCE)
        if (location.start < begin.end || location.end > end.start) fail(CRecordNavigationRefusal.INVALID_AST)
        return compound.path
    }

    fun visible(roots: List<CClangSourceEntry>, point: CClangSourceCoordinate): CClangSourceEntry? {
        val active = compounds.filter { scope -> scope.begin?.let { begin -> scope.end?.let { end ->
            begin.file == point.file && end.file == point.file && begin.start <= point.start && end.end >= point.end
        } } == true }.sortedBy { it.end!!.end - it.begin!!.start }.map { it.path } + "TU"
        for (scope in active) {
            val candidates = roots.filter { root -> declarationScope(root) == scope &&
                (scope == "TU" || root.location?.let { it.file == point.file && it.start <= point.start } == true) }
            if (candidates.size > 1) fail(CRecordNavigationRefusal.AMBIGUOUS_FAMILY)
            candidates.singleOrNull()?.let { return it }
        }
        return null
    }

    fun precedes(declaration: CClangSourceEntry, anchor: CClangSourceEntry): Boolean =
        (order[declaration.path] ?: fail(CRecordNavigationRefusal.INVALID_AST)) <=
            (order[anchor.path] ?: fail(CRecordNavigationRefusal.INVALID_AST))

    private fun kind(entry: CClangSourceEntry) = entry.node["kind"]?.jsonPrimitive?.content
    private fun fail(reason: CRecordNavigationRefusal): Nothing = throw CRecordScopeFailure(reason)
}
