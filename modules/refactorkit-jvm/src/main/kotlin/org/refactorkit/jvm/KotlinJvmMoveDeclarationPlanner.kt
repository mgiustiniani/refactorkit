package org.refactorkit.jvm

import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots
import org.refactorkit.java.JdtJavaSemanticAnalysisResult
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import org.refactorkit.kotlin.KotlinDeclarationVisibility
import org.refactorkit.kotlin.KotlinLanguageAdapter
import java.nio.file.Files
import java.nio.file.Path

/** Bounded K5 row for moving one public top-level Kotlin/JVM type inside one source set. */
class KotlinJvmMoveDeclarationPlanner(
    private val kotlin: KotlinLanguageAdapter,
    private val java: JdtJavaSemanticAnalyzer = JdtJavaSemanticAnalyzer(),
) {
    fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = when (val evidence = analyzeMixed(snapshot)) {
        is MixedEvidence.Available -> evidence.kotlin.diagnostics + javaDiagnostics(evidence.java)
        is MixedEvidence.Refused -> listOf(failure(evidence.code, evidence.message))
    }

    fun preview(
        snapshot: ProjectSnapshot,
        symbolId: SymbolId,
        targetPackage: String,
        acceptExternalConsumerRisk: Boolean = false,
    ): PatchPlan {
        if (!PACKAGE.matches(targetPackage) || targetPackage.split('.').any { it in KEYWORDS }) return refused(
            snapshot, "kotlin.moveTargetPackageInvalid", "Kotlin move target package is invalid",
        )
        if (!acceptExternalConsumerRisk) return refused(
            snapshot, "kotlin.moveExternalConsumerApprovalRequired",
            "Public Kotlin/JVM move requires explicit acceptance of unknown external-consumer risk",
        )
        val catalogue = when (val result = kotlin.compilerSymbols(snapshot)) {
            is KotlinCompilerSymbolsResult.Available -> result
            is KotlinCompilerSymbolsResult.Refused -> return refused(
                snapshot, result.reason.code ?: "kotlin.moveEvidenceUnavailable", result.reason.message,
            )
            is KotlinCompilerSymbolsResult.Error -> return refused(
                snapshot, result.failure.code ?: "kotlin.moveEvidenceUnavailable", result.failure.message,
            )
        }
        val target = catalogue.index.symbols.singleOrNull { it.id == symbolId }
            ?: return refused(snapshot, "kotlin.moveTargetMissing", "Kotlin move target is absent from the compiler catalogue")
        val declaration = catalogue.declarations[target.id]
            ?: return refused(snapshot, "kotlin.moveIdentityUnavailable", "Kotlin move target lacks JVM identity evidence")
        if (target.kind !in TYPE_KINDS || declaration.visibility != KotlinDeclarationVisibility.PUBLIC ||
            declaration.jvmIdentity != declaration.jvmOwner || declaration.jvmDescriptor.isNotEmpty() ||
            '$' in declaration.jvmIdentity) return refused(
            snapshot, "kotlin.moveDeclarationUnsupported",
            "Initial Kotlin move supports one public top-level JVM type",
        )
        val oldPackage = declaration.jvmIdentity.substringBeforeLast('.', "")
        if (oldPackage.isEmpty()) return refused(
            snapshot, "kotlin.moveDefaultPackageUnsupported", "Initial Kotlin move does not support the default package",
        )
        if (oldPackage == targetPackage) return refused(
            snapshot, "kotlin.moveNoChange", "Kotlin move source and target packages are identical",
        )
        val source = snapshot.file(target.location.path) ?: return refused(
            snapshot, "kotlin.moveDeclarationFileMissing", "Kotlin declaration file is absent from the snapshot",
        )
        if (!hasOneTopLevelDeclaration(source, target.name)) return refused(
            snapshot, "kotlin.moveFileShapeUnsupported",
            "Initial Kotlin move requires the target to be the only top-level declaration in its file",
        )
        if (dynamicRisk(snapshot, target.name)) return refused(
            snapshot, "kotlin.moveDynamicOrFrameworkReference",
            "Quoted reflection, serialization or framework evidence prevents the initial Kotlin move row",
        )
        val ownership = snapshot.owningBuildSourceRoots(source.path)
        val ownedRoots = ownership.map { it.root.normalize() }.distinct()
        if (ownership.isEmpty() || ownedRoots.size != 1 ||
            ownership.any { it.generated || it.modelStatus != BuildModelStatus.AVAILABLE }) return refused(
            snapshot, "kotlin.moveSourceOwnershipUnavailable",
            "Kotlin move requires one authoritative non-generated source-root path",
        )
        val destination = ownedRoots.single().resolve(targetPackage.replace('.', '/')).resolve(source.path.fileName).normalize()
        val newIdentity = "$targetPackage.${target.name}"
        if (destination == source.path.normalize() || snapshot.files.any { it.path.normalize() == destination } ||
            Files.exists(snapshot.workspace.root.resolve(destination)) ||
            catalogue.declarations.values.any { it.jvmIdentity == newIdentity }) return refused(
            snapshot, "kotlin.moveDestinationConflict", "Kotlin move destination already exists",
        )

        val before = when (val evidence = analyzeMixed(snapshot)) {
            is MixedEvidence.Available -> evidence
            is MixedEvidence.Refused -> return refused(snapshot, evidence.code, evidence.message)
        }
        if (before.kotlin.symbolFailure != null || before.kotlin.diagnostics.any { it.severity == Diagnostic.Severity.ERROR } ||
            before.java.warnings.isNotEmpty()) return refused(
            snapshot, "kotlin.moveBaselineIncomplete", "Kotlin move requires complete clean K2/JDT evidence",
            before.kotlin.diagnostics + javaDiagnostics(before.java),
        )
        val javaUses = before.java.bindingUses.filter { it.symbolQualifiedName == declaration.jvmIdentity }
        if (javaUses.isEmpty()) return refused(
            snapshot, "kotlin.moveCrossLanguageReferenceMissing", "Kotlin move requires at least one JDT-bound Java consumer",
        )
        val kotlinUsageFiles = catalogue.usages.filter { it.targetId == target.id }
            .map { it.location.path.normalize() }.filter { it != source.path.normalize() }.toSet()
        if (kotlinUsageFiles.isEmpty()) return refused(
            snapshot, "kotlin.moveKotlinReferenceMissing", "Kotlin move requires at least one K2-bound Kotlin consumer",
        )
        val javaUsageFiles = javaUses.map { it.path.normalize() }.toSet()
        val consumerPaths = kotlinUsageFiles + javaUsageFiles
        if (consumerPaths.any { path -> snapshot.owningBuildSourceRoots(path).any { it.generated } }) return refused(
            snapshot, "kotlin.moveGeneratedReference", "Kotlin move consumer belongs to generated source",
        )

        val edits = mutableListOf<FileEdit>()
        edits += FileEdit.Modify(source.path, listOf(packageEdit(source, oldPackage, targetPackage)
            ?: return refused(snapshot, "kotlin.movePackageDeclarationInvalid", "Kotlin package declaration is not exact")))
        for (path in consumerPaths.sortedBy { it.toString() }) {
            val consumer = snapshot.file(path) ?: return refused(
                snapshot, "kotlin.moveReferenceFileMissing", "Kotlin move consumer is absent from the snapshot",
            )
            val importEdit = consumerImportEdit(
                consumer, oldPackage, declaration.jvmIdentity, newIdentity, target.name,
            ) ?: return refused(
                snapshot, "kotlin.moveImportShapeUnsupported",
                "Kotlin move requires one exact explicit import or one compiler-proven same-package implicit consumer",
            )
            edits += FileEdit.Modify(path, listOf(importEdit))
        }
        edits += FileEdit.Rename(source.path, destination)
        val workspaceEdit = WorkspaceEdit(edits)
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }.getOrElse {
            return refused(snapshot, "kotlin.movePreviewInvalid", it.message ?: "Kotlin move preview is invalid")
        }
        val after = when (val evidence = analyzeMixed(staged)) {
            is MixedEvidence.Available -> evidence
            is MixedEvidence.Refused -> return refused(snapshot, evidence.code, evidence.message)
        }
        val introduced = introducedDiagnostics(
            before.kotlin.diagnostics + javaDiagnostics(before.java),
            after.kotlin.diagnostics + javaDiagnostics(after.java),
        )
        if (introduced.isNotEmpty()) return refused(
            snapshot, "kotlin.moveDiagnosticsRegression",
            "Kotlin move introduces ${introduced.size} compiler error(s)", introduced,
        )
        if (after.kotlin.declarations.values.none { it.jvmIdentity == newIdentity } ||
            after.java.bindingUses.none { it.symbolQualifiedName == newIdentity }) return refused(
            snapshot, "kotlin.movePostImageIdentityMissing",
            "Staged K2/JDT evidence does not resolve the moved JVM identity",
        )
        return PatchPlan(
            operation = "moveDeclaration", status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash,
            confidence = 0.91, requiresUserApproval = true,
            summary = "Move public Kotlin type '${target.name}' from '$oldPackage' to '$targetPackage' across ${consumerPaths.size} compiler-proven consumer file(s).",
            affectedFiles = workspaceEdit.affectedFiles(), workspaceEdit = workspaceEdit,
            diagnosticsBefore = before.kotlin.diagnostics + javaDiagnostics(before.java),
            diagnosticsAfterPreview = after.kotlin.diagnostics + javaDiagnostics(after.java),
            warnings = listOf("Public JVM package move: unknown external consumers were explicitly accepted for this preview."),
            riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.NATIVE_AST,
        )
    }

    private fun analyzeMixed(snapshot: ProjectSnapshot): MixedEvidence {
        var javaEvidence: JdtJavaSemanticAnalysisResult? = null
        val kotlinResult = kotlin.compilerDiagnosticsWithOutput(snapshot) { output ->
            javaEvidence = java.analyze(snapshot, additionalClasspathEntries = listOf(output))
        }
        return when (kotlinResult) {
            is KotlinCompilerDiagnosticsResult.Available -> kotlinResult.symbolFailure?.let {
                MixedEvidence.Refused(it.code ?: "kotlin.moveKotlinEvidenceUnavailable", it.message)
            } ?: javaEvidence?.let { MixedEvidence.Available(kotlinResult, it) }
                ?: MixedEvidence.Refused("kotlin.moveBinaryEvidenceUnavailable", "Kotlin move lacks JVM binary evidence")
            is KotlinCompilerDiagnosticsResult.Refused -> MixedEvidence.Refused(
                kotlinResult.reason.code ?: "kotlin.moveEvidenceUnavailable", kotlinResult.reason.message,
            )
            is KotlinCompilerDiagnosticsResult.Error -> MixedEvidence.Refused(
                kotlinResult.failure.code ?: "kotlin.moveEvidenceUnavailable", kotlinResult.failure.message,
            )
        }
    }

    private fun packageEdit(source: SourceFile, oldPackage: String, targetPackage: String): TextEdit? {
        val match = Regex("(?m)^package\\s+(${Regex.escape(oldPackage)})\\s*$").find(source.content) ?: return null
        val range = match.groups[1]!!.range
        return offsetEdit(source.content, range.first, range.last + 1, targetPackage)
    }

    private fun consumerImportEdit(
        source: SourceFile,
        oldPackage: String,
        oldIdentity: String,
        newIdentity: String,
        simpleName: String,
    ): TextEdit? {
        val terminator = if (source.languageId == "java") "\\s*;" else ""
        val explicit = Regex(
            "(?m)^[ \\t]*import\\s+(${Regex.escape(oldIdentity)})$terminator[ \\t]*$",
        ).findAll(source.content).toList()
        if (explicit.size == 1) {
            val range = explicit.single().groups[1]!!.range
            return offsetEdit(source.content, range.first, range.last + 1, newIdentity)
        }
        if (explicit.isNotEmpty() || exactPackage(source) != oldPackage || oldIdentity in source.content) return null
        val unsafeImport = Regex(
            "(?m)^[ \\t]*import\\s+(?:static\\s+)?(?:${Regex.escape(oldPackage)}\\.\\*|" +
                "[A-Za-z_][A-Za-z0-9_.]*\\.${Regex.escape(simpleName)})(?:\\s+as\\s+\\w+)?[ \\t]*;?[ \\t]*$",
        )
        if (unsafeImport.containsMatchIn(source.content)) return null
        val packageMatch = packageLine(source) ?: return null
        val newline = if ("\r\n" in source.content) "\r\n" else "\n"
        var insertionOffset = packageMatch.range.last + 1
        if (source.content.startsWith(newline, insertionOffset)) insertionOffset += newline.length
        val semicolon = if (source.languageId == "java") ";" else ""
        return offsetEdit(
            source.content, insertionOffset, insertionOffset,
            "import $newIdentity$semicolon$newline",
        )
    }

    private fun exactPackage(source: SourceFile): String? = packageLine(source)?.groups?.get(1)?.value

    private fun packageLine(source: SourceFile): MatchResult? {
        val terminator = if (source.languageId == "java") "\\s*;" else ""
        val matches = Regex(
            "(?m)^[ \\t]*package\\s+([A-Za-z_][A-Za-z0-9_.]*)$terminator[ \\t]*$",
        ).findAll(source.content).toList()
        return matches.singleOrNull()
    }

    private fun dynamicRisk(snapshot: ProjectSnapshot, simpleName: String): Boolean {
        val quotedName = Regex("[\\\"'][^\\\"'\\n]*\\b${Regex.escape(simpleName)}\\b[^\\\"'\\n]*[\\\"']")
        val frameworkAnnotation = Regex("@(Entity|Table|JsonTypeName|JsonSubTypes|Component|Service|Repository|Controller)\\b")
        return snapshot.files.any { file ->
            file.languageId in setOf("java", "kotlin") &&
                (quotedName.containsMatchIn(file.content) || frameworkAnnotation.containsMatchIn(file.content))
        }
    }

    private fun hasOneTopLevelDeclaration(source: SourceFile, name: String): Boolean {
        val declarations = Regex(
            "(?m)^(?:(?:public|internal|private|protected|open|abstract|sealed|data|value|enum|annotation)\\s+)*(?:class|interface|object|fun|val|var|typealias)\\s+([A-Za-z_][A-Za-z0-9_]*)",
        ).findAll(source.content).toList()
        return declarations.size == 1 && declarations.single().groups[1]?.value == name
    }

    private fun offsetEdit(content: String, start: Int, end: Int, replacement: String) = TextEdit(
        SourceRange(TextEdits.positionForOffset(content, start), TextEdits.positionForOffset(content, end)), replacement,
    )

    private fun ProjectSnapshot.file(path: Path): SourceFile? = files.singleOrNull { it.path.normalize() == path.normalize() }

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

    private fun failure(code: String, message: String) = Diagnostic(
        message = message, severity = Diagnostic.Severity.ERROR, code = code,
        evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY,
    )

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        message: String,
        diagnostics: List<Diagnostic> = emptyList(),
    ) = PatchPlan(
        operation = "moveDeclaration", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash,
        confidence = 0.0, requiresUserApproval = false, summary = message, affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(), diagnosticsAfterPreview = diagnostics, warnings = listOf(message),
        riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.NATIVE_AST, refusalCode = code,
    )

    private sealed interface MixedEvidence {
        data class Available(
            val kotlin: KotlinCompilerDiagnosticsResult.Available,
            val java: JdtJavaSemanticAnalysisResult,
        ) : MixedEvidence
        data class Refused(val code: String, val message: String) : MixedEvidence
    }

    companion object {
        private val PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*){0,127}")
        private val TYPE_KINDS = setOf(
            Symbol.Kind.CLASS, Symbol.Kind.INTERFACE, Symbol.Kind.OBJECT, Symbol.Kind.ENUM, Symbol.Kind.ANNOTATION,
        )
        private val KEYWORDS = setOf(
            "class", "object", "interface", "fun", "val", "var", "when", "is", "in", "as", "private",
            "public", "internal", "protected", "return", "package", "import", "typealias",
        )
    }
}
