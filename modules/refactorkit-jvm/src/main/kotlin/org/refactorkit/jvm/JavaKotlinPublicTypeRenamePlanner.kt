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
import org.refactorkit.core.TextEdits
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
        val target = initialJava.symbols.singleOrNull { it.qualifiedName == symbolId.value }
            ?: return refused(
                snapshot, "jvm.renameJavaTargetMissing",
                "Initial Java-to-Kotlin rename requires one JDT-bound Java class or method",
            )
        val supportedClass = target.kind == JdtJavaSemanticSymbolKind.CLASS && target.ownerQualifiedName == null &&
            isExplicitPublicClass(snapshot, target)
        val supportedMethod = target.kind == JdtJavaSemanticSymbolKind.METHOD && target.ownerQualifiedName != null &&
            isExplicitPublicMethod(snapshot, target) && initialJava.symbols.count {
                it.kind == JdtJavaSemanticSymbolKind.METHOD && it.ownerQualifiedName == target.ownerQualifiedName &&
                    it.simpleName == target.simpleName
            } == 1 && initialJava.overrideRelations.none {
                it.overridingSymbolQualifiedName == target.qualifiedName || it.overriddenSymbolQualifiedName == target.qualifiedName
            }
        if (target.bindingKey.isNullOrBlank() || (!supportedClass && !supportedMethod)) return refused(
            snapshot, "jvm.renamePublicDeclarationUnsupported",
            "Initial Java-to-Kotlin rename requires an explicitly public top-level class or one non-overloaded non-override public method",
        )
        if (!acceptExternalConsumerRisk) return refused(
            snapshot, "jvm.renameExternalConsumerApprovalRequired",
            "Public JVM rename requires explicit acceptance of unknown external-consumer risk",
        )
        if (newName == target.simpleName) return refused(snapshot, "jvm.renameNoChange", "Shared JVM rename target is unchanged")
        val renamedIdentity = if (supportedClass) {
            val packageName = target.qualifiedName.substringBeforeLast('.', "")
            if (packageName.isEmpty()) newName else "$packageName.$newName"
        } else {
            "${target.ownerQualifiedName}#$newName(${target.qualifiedName.substringAfter('(', "").removeSuffix(")")})"
        }
        if (initialJava.symbols.any { it.qualifiedName == renamedIdentity } || supportedMethod && initialJava.symbols.any {
                it.ownerQualifiedName == target.ownerQualifiedName && it.simpleName == newName
            }) return refused(
            snapshot, "jvm.renameConflict", "Shared JVM rename target collides with an existing Java declaration",
        )
        if (snapshot.owningBuildSourceRoots(target.path).any { it.generated }) return refused(
            snapshot, "jvm.renameGeneratedSource", "Shared JVM rename target belongs to generated source",
        )
        dynamicRisk(snapshot, target.simpleName)?.let { return refused(snapshot, "jvm.renameDynamicReference", it) }
        val targetFile = snapshot.files.singleOrNull { it.path.normalize() == target.path.normalize() }
            ?: return refused(snapshot, "jvm.renameRangeInvalid", "Java target file is absent from the immutable snapshot")
        val destination = if (supportedClass) target.path.resolveSibling("$newName.java") else target.path
        if (supportedClass && destination != target.path &&
            (snapshot.files.any { it.path.normalize() == destination.normalize() } ||
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
        val kotlinUseLocations = if (supportedClass) {
            before.kotlin.externalTypeUsages.filter { it.jvmBinaryName == target.qualifiedName }.map { it.location }
        } else {
            before.kotlin.externalCallableUsages.filter {
                it.jvmOwner == target.ownerQualifiedName && it.callableName == target.simpleName
            }.map { it.location }
        }
        if (kotlinUseLocations.isEmpty()) return refused(
            snapshot, "jvm.renameCrossLanguageReferenceMissing", "K2 found no Kotlin use for the Java binary identity",
        )
        val javaLocations = (listOf(SourceLocation(target.path, target.sourceRange)) + initialJava.references
            .filter { it.symbolQualifiedName == target.qualifiedName }.map { SourceLocation(it.path, it.sourceRange) })
        val locations = (javaLocations + kotlinUseLocations).distinct()
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
        val edit = WorkspaceEdit(
            modifications + if (supportedClass) listOf(FileEdit.Rename(targetFile.path, destination)) else emptyList(),
        )
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
        val postJavaResolved = after.java.symbols.any {
            it.qualifiedName == renamedIdentity && it.kind == if (supportedClass) JdtJavaSemanticSymbolKind.CLASS else JdtJavaSemanticSymbolKind.METHOD
        }
        val postKotlinResolved = if (supportedClass) {
            after.kotlin.externalTypeUsages.any { it.jvmBinaryName == renamedIdentity }
        } else {
            after.kotlin.externalCallableUsages.any {
                it.jvmOwner == target.ownerQualifiedName && it.callableName == newName
            }
        }
        if (!postJavaResolved || !postKotlinResolved) return refused(
            snapshot, "jvm.renamePostImageIdentityMissing", "K2/JDT did not resolve the renamed Java binary identity",
        )
        return PatchPlan(
            operation = "renameSymbol", status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash,
            confidence = 0.92, requiresUserApproval = true,
            summary = "Rename public Java ${if (supportedClass) "type" else "method"} '${target.simpleName}' to '$newName' across ${locations.size} JDT/K2-proven token(s).",
            affectedFiles = edit.affectedFiles(), workspaceEdit = edit,
            diagnosticsBefore = before.kotlin.diagnostics + javaDiagnostics(before.java),
            diagnosticsAfterPreview = after.kotlin.diagnostics + javaDiagnostics(after.java),
            warnings = listOf("Public JVM API rename: unknown external consumers were explicitly accepted for this preview."),
            riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    fun previewAddParameter(
        snapshot: ProjectSnapshot,
        symbolId: SymbolId,
        parameterType: String,
        parameterName: String,
        defaultExpression: String,
        acceptExternalConsumerRisk: Boolean = false,
    ): PatchPlan {
        val operation = "changeSignature.addParameter"
        if (snapshot.files.none { it.languageId == "java" } || snapshot.files.none { it.languageId == "kotlin" }) {
            return refused(snapshot, "jvm.signatureMixedSnapshotRequired", "Shared JVM add-parameter requires Java and Kotlin sources", operation = operation)
        }
        if (!acceptExternalConsumerRisk) return refused(snapshot, "jvm.signatureExternalConsumerApprovalRequired", "Public JVM signature change requires explicit external-consumer risk acceptance", operation = operation)
        if (!IDENTIFIER.matches(parameterName) || !SAFE_TYPE.matches(parameterType.trim()) || !SAFE_DEFAULT.matches(defaultExpression.trim())) {
            return refused(snapshot, "jvm.signatureArgumentInvalid", "Shared JVM add-parameter arguments are not in the bounded safe grammar", operation = operation)
        }
        val before = when (val evidence = analyzeMixed(snapshot)) {
            is MixedEvidence.Available -> evidence
            is MixedEvidence.Refused -> return refused(snapshot, evidence.code, evidence.message, operation = operation)
        }
        val baseline = before.kotlin.diagnostics + javaDiagnostics(before.java)
        if (baseline.any { it.severity == Diagnostic.Severity.ERROR }) return refused(snapshot, "jvm.signatureBaselineIncomplete", "Shared JVM add-parameter requires clean ECJ/K2/JDT evidence", baseline, operation)
        val target = before.java.symbols.singleOrNull { it.qualifiedName == symbolId.value && it.kind == JdtJavaSemanticSymbolKind.METHOD }
            ?: return refused(snapshot, "jvm.signatureJavaTargetMissing", "Shared JVM add-parameter requires one exact JDT method identity", operation = operation)
        if (!isExplicitPublicMethod(snapshot, target) || before.java.symbols.count {
                it.ownerQualifiedName == target.ownerQualifiedName && it.simpleName == target.simpleName && it.kind == JdtJavaSemanticSymbolKind.METHOD
            } != 1 || before.java.overrideRelations.any {
                it.overridingSymbolQualifiedName == target.qualifiedName || it.overriddenSymbolQualifiedName == target.qualifiedName
            }) return refused(snapshot, "jvm.signaturePublicMethodUnsupported", "Initial shared JVM add-parameter requires one non-overloaded non-hierarchy public method", operation = operation)
        if (snapshot.owningBuildSourceRoots(target.path).any { it.generated }) return refused(snapshot, "jvm.signatureGeneratedSource", "Shared JVM target belongs to generated source", operation = operation)
        if (snapshot.files.filter { it.languageId == "kotlin" }.any { Regex("::\\s*${Regex.escape(target.simpleName)}\\b").containsMatchIn(it.content) }) {
            return refused(snapshot, "jvm.signatureCallableReferenceUnsupported", "Kotlin callable references require a separate functional migration", operation = operation)
        }
        val method = before.java.methods.singleOrNull { it.qualifiedName == target.qualifiedName }
            ?: return refused(snapshot, "jvm.signatureMethodEvidenceMissing", "JDT method range evidence is missing", operation = operation)
        val parameters = before.java.parameters.filter { it.methodQualifiedName == method.qualifiedName }.sortedBy { it.index }
        if (parameters.any { it.name == parameterName }) return refused(snapshot, "jvm.signatureParameterConflict", "Parameter already exists", operation = operation)
        val javaCalls = before.java.invocations.filter { it.methodQualifiedName == method.qualifiedName }
        if (javaCalls.any { it.argumentRanges.size != parameters.size }) return refused(snapshot, "jvm.signatureJavaCallsIncomplete", "JDT Java invocation evidence is incomplete", operation = operation)
        val kotlinCalls = before.kotlin.externalCallableUsages.filter {
            it.jvmOwner == target.ownerQualifiedName && it.callableName == target.simpleName
        }
        if (kotlinCalls.isEmpty()) return refused(snapshot, "jvm.signatureKotlinCallerMissing", "K2 found no Kotlin caller for the Java JVM identity", operation = operation)
        val edits = linkedMapOf<java.nio.file.Path, MutableList<TextEdit>>()
        val declarationFile = snapshot.files.single { it.path == method.path }
        val declaration = selectedText(declarationFile.content, SourceLocation(method.path, method.parameterListRange)).trim()
        edits.getOrPut(method.path) { mutableListOf() } += TextEdit(method.parameterListRange,
            listOf(declaration, "${parameterType.trim()} $parameterName").filter { it.isNotEmpty() }.joinToString(", "))
        javaCalls.forEach { call ->
            val file = snapshot.files.single { it.path == call.path }
            val current = selectedText(file.content, SourceLocation(file.path, call.argumentListRange)).trim()
            edits.getOrPut(file.path) { mutableListOf() } += TextEdit(call.argumentListRange,
                listOf(current, defaultExpression.trim()).filter { it.isNotEmpty() }.joinToString(", "))
        }
        kotlinCalls.forEach { usage ->
            val file = snapshot.files.singleOrNull { it.path.normalize() == usage.location.path.normalize() }
                ?: return refused(snapshot, "jvm.signatureKotlinRangeInvalid", "K2 Kotlin caller file is absent", operation = operation)
            val range = callArgumentInterior(file.content, usage.location)
                ?: return refused(snapshot, "jvm.signatureKotlinCallShapeUnsupported", "K2 usage is not one bounded Kotlin call expression", operation = operation)
            val current = selectedText(file.content, SourceLocation(file.path, range)).trim()
            edits.getOrPut(file.path) { mutableListOf() } += TextEdit(range,
                listOf(current, defaultExpression.trim()).filter { it.isNotEmpty() }.joinToString(", "))
        }
        val workspaceEdit = WorkspaceEdit(edits.map { (path, values) -> FileEdit.Modify(path, values.distinctBy { it.range }.sortedByDescending { TextEdits.offsetOf(snapshot.files.single { file -> file.path == path }.content, it.range.start) }) })
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }.getOrElse {
            return refused(snapshot, "jvm.signaturePreviewInvalid", it.message ?: "Shared JVM signature preview is invalid", operation = operation)
        }
        val after = when (val evidence = analyzeMixed(staged)) {
            is MixedEvidence.Available -> evidence
            is MixedEvidence.Refused -> return refused(snapshot, evidence.code, evidence.message, operation = operation)
        }
        val introduced = introducedDiagnostics(baseline, after.kotlin.diagnostics + javaDiagnostics(after.java))
        if (introduced.isNotEmpty()) return refused(snapshot, "jvm.signatureDiagnosticsRegression", "Shared JVM add-parameter introduces ${introduced.size} compiler error(s)", introduced, operation)
        val stagedMethod = after.java.methods.singleOrNull {
            it.qualifiedName.substringBefore('(') == method.qualifiedName.substringBefore('(') &&
                after.java.parameters.count { parameter -> parameter.methodQualifiedName == it.qualifiedName } == parameters.size + 1
        } ?: return refused(snapshot, "jvm.signaturePostImageIdentityMissing", "JDT did not resolve the changed method identity", operation = operation)
        val stagedKotlinCalls = after.kotlin.externalCallableUsages.count { it.jvmOwner == target.ownerQualifiedName && it.callableName == target.simpleName }
        if (stagedKotlinCalls != kotlinCalls.size) return refused(snapshot, "jvm.signatureKotlinCallerDrift", "K2 Kotlin caller identity/count changed", operation = operation)
        return PatchPlan(operation = operation, status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash, confidence = 0.93,
            requiresUserApproval = true, summary = "Add '$parameterType $parameterName' to ${target.qualifiedName}; update ${javaCalls.size} JDT and ${kotlinCalls.size} K2 caller(s).",
            affectedFiles = workspaceEdit.affectedFiles(), workspaceEdit = workspaceEdit, diagnosticsBefore = baseline,
            diagnosticsAfterPreview = after.kotlin.diagnostics + javaDiagnostics(after.java),
            warnings = listOf("Public JVM API change: unknown external consumers were explicitly accepted."), riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.JDT_BINDING)
    }

    private fun callArgumentInterior(content: String, location: SourceLocation): org.refactorkit.core.SourceRange? {
        var index = TextEdits.offsetOf(content, location.range.end)
        while (index < content.length && content[index].isWhitespace()) index++
        if (index >= content.length || content[index] != '(') return null
        val open = index++
        var depth = 1
        while (index < content.length) {
            when (content[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return TextEdits.rangeForOffset(content, open + 1, index - open - 1)
                '"', '\'' -> {
                    val quote = content[index++]
                    while (index < content.length && content[index] != quote) { if (content[index] == '\\') index++; index++ }
                }
            }
            index++
        }
        return null
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

    private fun isExplicitPublicMethod(snapshot: ProjectSnapshot, target: JdtJavaSemanticSymbol): Boolean {
        val source = snapshot.files.singleOrNull { it.path.normalize() == target.path.normalize() } ?: return false
        return Regex(
            "\\bpublic\\s+(?:(?:final|abstract|static|synchronized|native|strictfp)\\s+)*" +
                "[^;{}=]+\\s+${Regex.escape(target.simpleName)}\\s*\\(",
        ).containsMatchIn(source.content)
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
        operation: String = "renameSymbol",
    ) = PatchPlan(
        operation = operation, status = PatchStatus.REFUSED, snapshotHash = snapshot.hash,
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
        private val SAFE_TYPE = Regex("[A-Za-z_$][A-Za-z0-9_$.]*(?:<[^;{}()]+>)?(?:\\[\\])?")
        private val SAFE_DEFAULT = Regex("[^;\\r\\n,]{1,1024}")
        private val KEYWORDS = setOf(
            "class", "interface", "enum", "record", "public", "private", "protected", "static", "final",
            "abstract", "sealed", "return", "package", "import", "object", "fun", "val", "var", "when",
        )
    }
}
