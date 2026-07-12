package org.refactorkit.java

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.Reference
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/**
 * Generates a [PatchPlan] for safe-deleting a Java type.
 *
 * Rules:
 * - Refuses deletion if any reference to the symbol exists in the project.
 * - In forced mode (`force = true`) deletion is allowed even if references exist,
 *   but a HIGH-risk warning is emitted.
 * - Only deletes the source file; build configuration changes are out of scope.
 */
class JavaSafeDeletePlanner(private val adapter: JavaLanguageAdapter) {

    fun preview(snapshot: ProjectSnapshot, symbolFqn: String, force: Boolean = false): PatchPlan {
        val simpleName = JavaPackageUtil.simpleName(symbolFqn)
        val oldPkg = JavaPackageUtil.packageOf(symbolFqn)

        val index = adapter.buildSymbols(snapshot)
        val symbol = index.symbols.find { it.id.value == symbolFqn && it.kind in DELETEABLE_KINDS }
            ?: return refused(snapshot, "safeDelete", "Symbol not found: $symbolFqn")

        val declarationFile = snapshot.files.find { it.path == symbol.location.path }
            ?: return refused(snapshot, "safeDelete", "Declaration file not found: ${symbol.location.path}")
        JavaGeneratedSourcePolicy.reason(declarationFile)?.let { reason ->
            return refused(snapshot, "safeDelete", "Generated source cannot be deleted: ${declarationFile.path} ($reason)")
        }

        val frameworkAssessment = JavaFrameworkDetector.assess(declarationFile)

        // Prefer exact JDT type-binding references when semantic analysis is clean.
        val semanticReferences = findJdtTypeReferences(snapshot, symbolFqn, declarationFile.path)
        val references = semanticReferences
            ?: findReferences(snapshot, symbolFqn, simpleName, oldPkg, declarationFile.path)
        val referenceEvidence = if (semanticReferences != null) "JDT binding" else "lexical fallback"

        if (references.isNotEmpty() && !force) {
            val refList = references.take(20).joinToString("\n") { ref ->
                "  ${ref.location.path}:${ref.location.range.start.line + 1}"
            }
            val extra = if (references.size > 20) "\n  ... and ${references.size - 20} more" else ""
            return refused(
                snapshot, "safeDelete",
                "Cannot delete $symbolFqn: ${references.size} reference(s) found using $referenceEvidence evidence.\n$refList$extra\n" +
                    "Use --force to delete anyway (dangerous).",
            )
        }

        val warnings = mutableListOf<String>()
        if (references.isNotEmpty()) {
            warnings += "Forced delete: ${references.size} reference(s) were found and ignored. This will break the build."
        }
        warnings += if (semanticReferences != null) {
            "Java source references were evaluated using exact JDT type-binding evidence."
        } else {
            "JDT type-binding evidence was unavailable or not clean; Java source references use lexical fallback. Review carefully."
        }
        warnings += "Build configuration (pom.xml, build.gradle) is not scanned for references."
        warnings += frameworkAssessment.warnings("safeDelete")

        return PatchPlan(
            operation = "safeDelete",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = if (references.isEmpty()) 1.0 else 0.3,
            requiresUserApproval = true,
            summary = "Delete $symbolFqn (${declarationFile.path}).",
            affectedFiles = setOf(declarationFile.path),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Delete(declarationFile.path))),
            warnings = warnings,
            riskLevel = if (references.isNotEmpty() || frameworkAssessment.hasFindings) RiskLevel.HIGH else RiskLevel.LOW,
            evidence = if (semanticReferences != null) RefactoringEvidence.JDT_BINDING else RefactoringEvidence.LEXICAL_FALLBACK,
        )
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun findJdtTypeReferences(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        declarationPath: Path,
    ): List<Reference>? {
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        if (analysis.warnings.isNotEmpty()) return null
        val target = analysis.symbols.singleOrNull { symbol ->
            symbol.qualifiedName == symbolFqn && symbol.kind in JDT_DELETEABLE_KINDS
        } ?: return null
        val bindingKey = target.bindingKey ?: return null
        return analysis.references
            .filter { it.bindingKey == bindingKey && it.path != declarationPath }
            .map { reference ->
                Reference(
                    symbolId = SymbolId(symbolFqn),
                    location = SourceLocation(reference.path, reference.sourceRange),
                )
            }
            .distinctBy {
                "${it.location.path}:${it.location.range.start.line}:${it.location.range.start.character}:${it.location.range.end.character}"
            }
            .sortedWith(compareBy<Reference> { it.location.path.toString() }
                .thenBy { it.location.range.start.line }
                .thenBy { it.location.range.start.character })
    }

    private fun findReferences(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        simpleName: String,
        pkg: String,
        declarationPath: Path,
    ): List<Reference> {
        val results = mutableListOf<Reference>()
        for (file in snapshot.files) {
            if (file.path == declarationPath || file.languageId != "java") continue

            val filePkg = JavaPackageUtil.extractPackage(file.content)
            val hasImport = file.content.contains("import $symbolFqn;")
            val hasFqn = file.content.contains(symbolFqn)
            val isSamePkg = filePkg == pkg && pkg.isNotEmpty()

            if (!hasImport && !hasFqn && !isSamePkg) continue

            // Look for FQN
            for (range in JavaLexer.findOccurrences(file.content, symbolFqn)) {
                val pos = TextEdits.positionForOffset(file.content, range.first)
                results += Reference(
                    symbolId = SymbolId(symbolFqn),
                    location = SourceLocation(file.path, SourceRange(pos, pos)),
                )
            }

            // Look for simple name in same-package or importing files
            if (hasImport || isSamePkg) {
                for (range in JavaLexer.findOccurrences(file.content, simpleName)) {
                    val pos = TextEdits.positionForOffset(file.content, range.first)
                    results += Reference(
                        symbolId = SymbolId(symbolFqn),
                        location = SourceLocation(file.path, SourceRange(pos, pos)),
                    )
                }
            }
        }
        return results.distinctBy { "${it.location.path}:${it.location.range.start.line}" }
    }

    private fun refused(snapshot: ProjectSnapshot, operation: String, reason: String) = PatchPlan(
        operation = operation,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(reason),
        riskLevel = RiskLevel.HIGH,
    )

    companion object {
        private val DELETEABLE_KINDS = setOf(
            Symbol.Kind.CLASS,
            Symbol.Kind.INTERFACE,
            Symbol.Kind.ENUM,
            Symbol.Kind.RECORD,
            Symbol.Kind.ANNOTATION,
        )
        private val JDT_DELETEABLE_KINDS = setOf(
            JdtJavaSemanticSymbolKind.CLASS,
            JdtJavaSemanticSymbolKind.INTERFACE,
            JdtJavaSemanticSymbolKind.ENUM,
            JdtJavaSemanticSymbolKind.RECORD,
            JdtJavaSemanticSymbolKind.ANNOTATION,
        )
    }
}
