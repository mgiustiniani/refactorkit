package org.refactorkit.jvm

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots
import org.refactorkit.java.JdtJavaSemanticAnalysisResult
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import org.refactorkit.java.JdtJavaSemanticSymbol
import org.refactorkit.java.JdtJavaSemanticSymbolKind
import org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult
import org.refactorkit.kotlin.KotlinLanguageAdapter
import java.nio.file.Files

/** Symmetric shared-JVM row: a public top-level Java class used by Kotlin. */
class JavaKotlinPublicTypeRenamePlanner(
    private val kotlin: KotlinLanguageAdapter,
    private val java: JdtJavaSemanticAnalyzer = JdtJavaSemanticAnalyzer(),
    private val javaCompiler: JavaEphemeralCompiler = JavaEphemeralCompiler(),
) {
    fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = when (val evidence = analyzeMixed(snapshot)) {
        is MixedEvidence.Available -> evidence.kotlin.diagnostics + javaDiagnostics(evidence.java)
        is MixedEvidence.Refused -> listOf(Diagnostic(
            message = evidence.message, severity = Diagnostic.Severity.ERROR, code = evidence.code,
            evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY,
        ))
    }

    fun preview(
        snapshot: ProjectSnapshot,
        symbolId: SymbolId,
        newName: String,
        acceptExternalConsumerRisk: Boolean = false,
    ): PatchPlan {
        if (!IDENTIFIER.matches(newName) || newName in KEYWORDS) return refused(
            snapshot, "jvm.renameTargetInvalid", "Shared JVM rename target is not a safe Java/Kotlin identifier",
        )
        if (snapshot.files.none { it.languageId == "java" } || snapshot.files.none { it.languageId == "kotlin" }) return refused(
            snapshot, "jvm.renameMixedSnapshotRequired", "Shared JVM rename requires Java and Kotlin sources",
        )
        val initialJava = java.analyze(snapshot)
        if (initialJava.warnings.isNotEmpty()) return refused(
            snapshot, "jvm.renameBaselineIncomplete", "Java-to-Kotlin rename requires a clean JDT baseline",
            javaDiagnostics(initialJava),
        )
        val target = initialJava.symbols.singleOrNull {
            it.qualifiedName == symbolId.value && it.kind == JdtJavaSemanticSymbolKind.CLASS && it.ownerQualifiedName == null
        } ?: return refused(
            snapshot, "jvm.renameJavaTargetMissing", "Initial Java-to-Kotlin rename requires one JDT-bound top-level Java class",
        )
        if (target.bindingKey.isNullOrBlank() || !isExplicitPublicClass(snapshot, target)) return refused(
            snapshot, "jvm.renamePublicTypeUnsupported", "Initial Java-to-Kotlin rename requires an explicitly public JDT-bound class",
        )
        if (!acceptExternalConsumerRisk) return refused(
            snapshot, "jvm.renameExternalConsumerApprovalRequired",
            "Public JVM rename requires explicit acceptance of unknown external-consumer risk",
        )
        if (newName == target.simpleName) return refused(snapshot, "jvm.renameNoChange", "Shared JVM rename target is unchanged")
        val packageName = target.qualifiedName.substringBeforeLast('.', "")
        val renamedIdentity = if (packageName.isEmpty()) newName else "$packageName.$newName"
        if (initialJava.symbols.any { it.qualifiedName == renamedIdentity }) return refused(
            snapshot, "jvm.renameConflict", "Shared JVM rename target collides with an existing Java declaration",
        )
        if (snapshot.owningBuildSourceRoots(target.path).any { it.generated }) return refused(
            snapshot, "jvm.renameGeneratedSource", "Shared JVM rename target belongs to generated source",
        )
        dynamicRisk(snapshot, target.simpleName)?.let { return refused(snapshot, "jvm.renameDynamicReference", it) }
        val targetFile = snapshot.files.singleOrNull { it.path.normalize() == target.path.normalize() }
            ?: return refused(snapshot, "jvm.renameRangeInvalid", "Java target file is absent from the immutable snapshot")
        val destination = target.path.resolveSibling("$newName.java")
        if (destination != target.path && (snapshot.files.any { it.path.normalize() == destination.normalize() } ||
                Files.exists(snapshot.workspace.root.resolve(destination)))) return refused(
            snapshot, "jvm.renameFileConflict", "Renamed Java source filename already exists",
        )

        val beforeEvidence = analyzeMixed(snapshot)
        val before = when (beforeEvidence) {
            is MixedEvidence.Available -> beforeEvidence
            is MixedEvidence.Refused -> return refused(snapshot, beforeEvidence.code, beforeEvidence.message)
        }
        if (before.kotlin.diagnostics.any { it.severity == Diagnostic.Severity.ERROR } || before.java.warnings.isNotEmpty()) return refused(
            snapshot, "jvm.renameBaselineIncomplete", "Java-to-Kotlin rename requires clean ECJ/K2/JDT evidence",
            before.kotlin.diagnostics + javaDiagnostics(before.java),
        )
        val kotlinUses = before.kotlin.externalTypeUsages.filter { it.jvmBinaryName == target.qualifiedName }
        if (kotlinUses.isEmpty()) return refused(
            snapshot, "jvm.renameCrossLanguageReferenceMissing", "K2 found no Kotlin use for the Java binary identity",
        )
        val javaLocations = (listOf(SourceLocation(target.path, target.sourceRange)) + initialJava.references
            .filter { it.symbolQualifiedName == target.qualifiedName }.map { SourceLocation(it.path, it.sourceRange) })
        val locations = (javaLocations + kotlinUses.map { it.location }).distinct()
            .sortedWith(compareBy({ it.path.toString() }, { it.range.start.line }, { it.range.start.character }))
        if (locations.any { snapshot.owningBuildSourceRoots(it.path).any { owner -> owner.generated } }) return refused(
            snapshot, "jvm.renameGeneratedSource", "Shared JVM rename reference belongs to generated source",
        )
        if (locations.any { location ->
                val source = snapshot.files.singleOrNull { it.path.normalize() == location.path.normalize() } ?: return@any true
                selectedText(source.content, location) != target.simpleName
            }) return refused(snapshot, "jvm.renameRangeInvalid", "Shared JVM evidence contains an invalid exact token")

        val modifications = locations.groupBy { it.path.normalize() }.toSortedMap(compareBy { it.toString() }).map { (path, ranges) ->
            FileEdit.Modify(path, ranges.map { TextEdit(it.range, newName) })
        }
        val edit = WorkspaceEdit(modifications + FileEdit.Rename(targetFile.path, destination))
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, edit) }.getOrElse {
            return refused(snapshot, "jvm.renamePreviewInvalid", it.message ?: "Shared JVM preview is invalid")
        }
        val afterEvidence = analyzeMixed(staged)
        val after = when (afterEvidence) {
            is MixedEvidence.Available -> afterEvidence
            is MixedEvidence.Refused -> return refused(snapshot, afterEvidence.code, afterEvidence.message)
        }
        val introduced = introducedDiagnostics(
            before.kotlin.diagnostics + javaDiagnostics(before.java),
            after.kotlin.diagnostics + javaDiagnostics(after.java),
        )
        if (introduced.isNotEmpty()) return refused(
            snapshot, "jvm.renameDiagnosticsRegression", "Shared JVM rename introduces ${introduced.size} compiler error(s)", introduced,
        )
        if (after.java.symbols.none { it.qualifiedName == renamedIdentity && it.kind == JdtJavaSemanticSymbolKind.CLASS } ||
            after.kotlin.externalTypeUsages.none { it.jvmBinaryName == renamedIdentity }) return refused(
            snapshot, "jvm.renamePostImageIdentityMissing", "K2/JDT did not resolve the renamed Java binary identity",
        )
        return PatchPlan(
            operation = "renameSymbol", status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash,
            confidence = 0.92, requiresUserApproval = true,
            summary = "Rename public Java type '${target.simpleName}' to '$newName' across ${locations.size} JDT/K2-proven token(s).",
            affectedFiles = edit.affectedFiles(), workspaceEdit = edit,
            diagnosticsBefore = before.kotlin.diagnostics + javaDiagnostics(before.java),
            diagnosticsAfterPreview = after.kotlin.diagnostics + javaDiagnostics(after.java),
            warnings = listOf("Public JVM API rename: unknown external consumers were explicitly accepted for this preview."),
            riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun analyzeMixed(snapshot: ProjectSnapshot): MixedEvidence {
        var kotlinEvidence: KotlinCompilerDiagnosticsResult? = null
        val compilation = javaCompiler.compile(snapshot) { javaJar ->
            kotlinEvidence = kotlin.compilerDiagnosticsWithAdditionalClasspath(snapshot, listOf(javaJar))
        }
        if (compilation is JavaEphemeralCompilationResult.Refused) return MixedEvidence.Refused(
            compilation.code, compilation.message,
        )
        return when (val result = kotlinEvidence) {
            is KotlinCompilerDiagnosticsResult.Available -> result.symbolFailure?.let { failure ->
                MixedEvidence.Refused(
                    failure.code ?: "jvm.renameKotlinUsageEvidenceUnavailable",
                    failure.message,
                )
            } ?: MixedEvidence.Available(result, java.analyze(snapshot))
            is KotlinCompilerDiagnosticsResult.Refused -> MixedEvidence.Refused(
                result.reason.code ?: "jvm.renameEvidenceUnavailable", result.reason.message,
            )
            is KotlinCompilerDiagnosticsResult.Error -> MixedEvidence.Refused(
                result.failure.code ?: "jvm.renameEvidenceUnavailable", result.failure.message,
            )
            null -> MixedEvidence.Refused("jvm.renameBinaryEvidenceUnavailable", "Java compilation did not publish complete JVM evidence")
        }
    }

    private fun isExplicitPublicClass(snapshot: ProjectSnapshot, target: JdtJavaSemanticSymbol): Boolean {
        val source = snapshot.files.singleOrNull { it.path.normalize() == target.path.normalize() } ?: return false
        return Regex("\\bpublic\\s+(?:(?:final|abstract|sealed|non-sealed)\\s+)*class\\s+${Regex.escape(target.simpleName)}\\b")
            .containsMatchIn(source.content)
    }

    private fun javaDiagnostics(result: JdtJavaSemanticAnalysisResult) = result.warnings.map { warning ->
        Diagnostic(
            message = warning.message, severity = Diagnostic.Severity.ERROR,
            location = SourceLocation(warning.path, warning.sourceRange), code = "java.jdt.${warning.problemId}",
            evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.TYPE_RESOLUTION,
        )
    }

    private fun introducedDiagnostics(before: List<Diagnostic>, after: List<Diagnostic>): List<Diagnostic> {
        val baseline = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticKey).toSet()
        return after.filter { it.severity == Diagnostic.Severity.ERROR && diagnosticKey(it) !in baseline }
    }

    private fun diagnosticKey(diagnostic: Diagnostic) = listOf(
        diagnostic.code.orEmpty(), diagnostic.location?.path?.toString().orEmpty(),
        diagnostic.location?.range?.toString().orEmpty(), diagnostic.message,
    )

    private fun dynamicRisk(snapshot: ProjectSnapshot, oldName: String): String? {
        val quoted = Regex("[\\\"'][^\\\"'\\n]*\\b${Regex.escape(oldName)}\\b[^\\\"'\\n]*[\\\"']")
        return if (snapshot.files.any { it.languageId in setOf("java", "kotlin") && quoted.containsMatchIn(it.content) }) {
            "Quoted reflection, serialization, framework or configuration name candidate prevents shared JVM rename"
        } else null
    }

    private fun selectedText(content: String, location: SourceLocation): String {
        val range = location.range
        val lines = content.split('\n')
        if (range.start.line != range.end.line || range.start.line !in lines.indices) return ""
        val line = lines[range.start.line]
        if (range.start.character !in 0..line.length || range.end.character !in 0..line.length) return ""
        return line.substring(range.start.character, range.end.character)
    }

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        message: String,
        diagnostics: List<Diagnostic> = emptyList(),
    ) = PatchPlan(
        operation = "renameSymbol", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash,
        confidence = 0.0, requiresUserApproval = false, summary = message,
        affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(), diagnosticsAfterPreview = diagnostics,
        warnings = listOf(message), riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.JDT_BINDING,
        refusalCode = code,
    )

    private sealed interface MixedEvidence {
        data class Available(
            val kotlin: KotlinCompilerDiagnosticsResult.Available,
            val java: JdtJavaSemanticAnalysisResult,
        ) : MixedEvidence
        data class Refused(val code: String, val message: String) : MixedEvidence
    }

    companion object {
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,511}")
        private val KEYWORDS = setOf(
            "class", "interface", "enum", "record", "public", "private", "protected", "static", "final",
            "abstract", "sealed", "return", "package", "import", "object", "fun", "val", "var", "when",
        )
    }
}
