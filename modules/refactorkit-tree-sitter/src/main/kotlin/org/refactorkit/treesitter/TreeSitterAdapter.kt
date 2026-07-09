package org.refactorkit.treesitter

import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Paths

/**
 * Façade for structural multi-language analysis.
 *
 * ## Availability levels
 *
 * | Condition                          | [isAvailable] | Outline / Search source           |
 * |------------------------------------|---------------|-----------------------------------|
 * | No binding, no system property     | `false`       | [GenericOutline] regex fallback   |
 * | System property `…native=true`     | `true`        | [GenericOutline] regex fallback   |
 * | [setNativeBinding] called          | `true`        | [TreeSitterNativeBinding]         |
 *
 * When a [TreeSitterNativeBinding] is registered and [TreeSitterNativeBinding.supports]
 * returns true for the requested language, the native binding is preferred for both
 * outline extraction and identifier search (bypassing [CommentLiteralFilter]).
 *
 * ## System property
 *
 * Set `-Drefactorkit.treesitter.native=true` to advertise native availability without
 * a concrete binding (useful for integration tests or future dynamic loading).
 */
class TreeSitterAdapter(
    private var nativeBinding: TreeSitterNativeBinding? = null,
) {
    /**
     * Register a [TreeSitterNativeBinding]. Must be called before the first
     * [outline], [search], or [findIdentifier] call to take effect.
     */
    fun setNativeBinding(binding: TreeSitterNativeBinding) {
        nativeBinding = binding
    }

    /**
     * Returns true when a native binding is registered or the system property
     * [SYSTEM_PROPERTY_NATIVE] is set to `"true"`.
     */
    fun isAvailable(): Boolean =
        nativeBinding != null || System.getProperty(SYSTEM_PROPERTY_NATIVE).toBoolean()

    /**
     * Extract a structural outline from [content] for [languageId].
     *
     * Delegates to [TreeSitterNativeBinding.outline] when available and the language
     * is supported; otherwise falls back to [GenericOutline].
     */
    fun outline(content: String, languageId: String): List<GenericOutline.OutlineItem> {
        val binding = nativeBinding
        if (binding != null && binding.supports(languageId)) {
            return binding.outline(content, languageId)
        }
        return GenericOutline.outline(content, languageId)
    }

    /**
     * Structural search over [content] for [pattern] in [languageId].
     * Always uses [GenericStructuralSearch] (pattern search does not benefit from
     * parse-tree accuracy in the same way that identifier search does).
     */
    fun search(
        content: String,
        pattern: String,
        languageId: String,
        wholeWord: Boolean = false,
    ): List<GenericStructuralSearch.SearchMatch> =
        GenericStructuralSearch.search(content, pattern, languageId, wholeWord)

    /**
     * Find all whole-identifier occurrences of [identifier] in [content], excluding
     * those inside comments or string literals.
     *
     * Delegates to [TreeSitterNativeBinding.findIdentifier] when available and the
     * language is supported (parse-tree-accurate). Falls back to
     * [GenericStructuralSearch.findIdentifier] + [CommentLiteralFilter] (heuristic).
     */
    fun findIdentifier(
        content: String,
        identifier: String,
        languageId: String,
    ): List<GenericStructuralSearch.SearchMatch> {
        val binding = nativeBinding
        if (binding != null && binding.supports(languageId)) {
            return binding.findIdentifier(content, identifier, languageId)
        }
        return CommentLiteralFilter.filter(
            GenericStructuralSearch.findIdentifier(content, identifier, languageId),
            content, languageId,
        )
    }

    /**
     * Preview structural refactorings for any language.
     * Currently supports `localRename` only.
     */
    fun applyRefactoring(request: RefactoringRequest): PatchPlan = when (request.operation) {
        "localRename" -> {
            val file = request.arguments["file"]
                ?: return refused(request, "localRename requires arguments.file")
            val from = request.arguments["from"]
                ?: request.symbolId?.value?.substringAfterLast("::")
                ?: return refused(request, "localRename requires arguments.from or symbolId")
            val to = request.arguments["to"]
                ?: request.arguments["newName"]
                ?: return refused(request, "localRename requires arguments.to or arguments.newName")
            GenericLocalRenamePlanner().preview(request.snapshot, Paths.get(file), from, to)
        }
        else -> refused(request, "Unsupported structural refactoring: ${request.operation}")
    }

    private fun refused(request: RefactoringRequest, reason: String): PatchPlan = PatchPlan(
        operation = request.operation,
        status = PatchStatus.REFUSED,
        snapshotHash = request.snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(reason),
        riskLevel = RiskLevel.HIGH,
    )

    companion object {
        /**
         * System property that signals native Tree-sitter availability externally.
         * Set to `"true"` to make [isAvailable] return true without a binding.
         * Value: `"refactorkit.treesitter.native"`.
         */
        const val SYSTEM_PROPERTY_NATIVE = "refactorkit.treesitter.native"
    }
}
