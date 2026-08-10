package org.refactorkit.kotlin

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots

/** Bounded compiler-backed Kotlin/JVM parameter rename change-signature row. */
class KotlinChangeSignaturePlanner(
    private val kotlin: KotlinLanguageAdapter,
) {
    fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = kotlin.compilerDiagnostics(snapshot).diagnostics

    fun previewRenameParameter(
        snapshot: ProjectSnapshot,
        symbolId: String,
        oldParameterName: String,
        newParameterName: String,
        acceptExternalConsumerRisk: Boolean = false,
    ): PatchPlan = previewRenameParameter(
        snapshot, SymbolId(symbolId), oldParameterName, newParameterName, acceptExternalConsumerRisk,
    )

    fun previewRenameParameter(
        snapshot: ProjectSnapshot,
        symbolId: SymbolId,
        oldParameterName: String,
        newParameterName: String,
        acceptExternalConsumerRisk: Boolean = false,
    ): PatchPlan {
        val operation = OPERATION
        if (!IDENTIFIER.matches(oldParameterName) || !IDENTIFIER.matches(newParameterName) ||
            oldParameterName in KEYWORDS || newParameterName in KEYWORDS) return refused(
            snapshot, "kotlin.changeSignatureParameterNameInvalid",
            "Kotlin parameter names must be safe non-keyword identifiers", operation,
        )
        if (oldParameterName == newParameterName) return refused(
            snapshot, "kotlin.changeSignatureNoChange", "Kotlin parameter name is unchanged", operation,
        )
        val catalogue = when (val result = kotlin.compilerSymbols(snapshot)) {
            is KotlinCompilerSymbolsResult.Available -> result
            is KotlinCompilerSymbolsResult.Refused -> return refused(
                snapshot, result.reason.code ?: "kotlin.changeSignatureEvidenceUnavailable", result.reason.message,
                operation,
            )
            is KotlinCompilerSymbolsResult.Error -> return refused(
                snapshot, result.failure.code ?: "kotlin.changeSignatureEvidenceUnavailable", result.failure.message,
                operation,
            )
        }
        val target = catalogue.index.symbols.singleOrNull { it.id == symbolId }
            ?: return refused(
                snapshot, "kotlin.changeSignatureTargetMissing",
                "Kotlin change-signature target is absent from the compiler catalogue", operation,
            )
        val targetEvidence = catalogue.declarations[target.id]
            ?: return refused(snapshot, "kotlin.changeSignatureIdentityMissing", "Target lacks callable evidence", operation)
        if (target.kind != Symbol.Kind.FUNCTION || targetEvidence.jvmDescriptor.isBlank() ||
            targetEvidence.overrideFamilyId.isBlank()) return refused(
            snapshot, "kotlin.changeSignatureTargetUnsupported",
            "Kotlin parameter rename requires one compiler-catalogued function", operation,
        )
        val parameters = catalogue.index.symbols.filter { symbol ->
            symbol.kind == Symbol.Kind.PARAMETER && catalogue.declarations[symbol.id]?.let { evidence ->
                evidence.jvmOwner == targetEvidence.jvmOwner && evidence.jvmName == targetEvidence.jvmName &&
                    evidence.jvmDescriptor.substringBeforeLast('@', "") == targetEvidence.jvmDescriptor
            } == true
        }
        val selected = parameters.singleOrNull { it.name == oldParameterName }
            ?: return refused(
                snapshot, "kotlin.changeSignatureParameterMissing",
                "Function has no unique compiler-catalogued parameter named '$oldParameterName'", operation,
            )
        val selectedEvidence = catalogue.declarations.getValue(selected.id)
        val ordinal = selectedEvidence.jvmDescriptor.substringAfterLast('@', "").toIntOrNull()
            ?: return refused(
                snapshot, "kotlin.changeSignatureParameterIdentityInvalid",
                "Kotlin parameter ordinal evidence is invalid", operation,
            )
        val familyId = selectedEvidence.overrideFamilyId
        if (familyId != targetEvidence.overrideFamilyId) return refused(
            snapshot, "kotlin.changeSignatureFamilyIncomplete",
            "Kotlin function and parameter override-family evidence disagree", operation,
        )
        val familyFunctions = catalogue.index.symbols.filter { symbol ->
            symbol.kind == Symbol.Kind.FUNCTION && catalogue.declarations[symbol.id]?.overrideFamilyId == familyId
        }
        if (targetEvidence.hasExternalHierarchyBoundary ||
            (targetEvidence.isHierarchyMember && familyFunctions.size < 2)) return refused(
            snapshot, "kotlin.changeSignatureExternalHierarchyUnsupported",
            "Kotlin override family crosses an external or unavailable declaration boundary", operation,
        )
        val familyParameters = catalogue.index.symbols.filter { symbol ->
            if (symbol.kind != Symbol.Kind.PARAMETER) return@filter false
            val evidence = catalogue.declarations[symbol.id] ?: return@filter false
            evidence.overrideFamilyId == familyId &&
                evidence.jvmDescriptor.substringAfterLast('@', "").toIntOrNull() == ordinal
        }
        if (familyParameters.size != familyFunctions.size || familyParameters.isEmpty()) return refused(
            snapshot, "kotlin.changeSignatureFamilyIncomplete",
            "Kotlin override family lacks one exact parameter declaration at the selected ordinal", operation,
        )
        val familyOwners = familyFunctions.map { catalogue.declarations.getValue(it.id) }
        val externallyVisible = familyOwners.any {
            it.visibility != KotlinDeclarationVisibility.PRIVATE
        }
        if (externallyVisible && !acceptExternalConsumerRisk) return refused(
            snapshot, "kotlin.changeSignatureExternalConsumerApprovalRequired",
            "Non-private Kotlin parameter rename requires explicit external-consumer-risk acceptance", operation,
        )
        val allParameters = catalogue.index.symbols.filter { it.kind == Symbol.Kind.PARAMETER }
        if (familyParameters.any { parameter ->
                val evidence = catalogue.declarations.getValue(parameter.id)
                allParameters.any { other ->
                    other.id != parameter.id && other.name == newParameterName &&
                        catalogue.declarations.getValue(other.id).let { otherEvidence ->
                            otherEvidence.jvmOwner == evidence.jvmOwner && otherEvidence.jvmName == evidence.jvmName &&
                                otherEvidence.jvmDescriptor.substringBeforeLast('@', "") ==
                                evidence.jvmDescriptor.substringBeforeLast('@', "")
                        }
                }
            }) return refused(
            snapshot, "kotlin.changeSignatureParameterConflict",
            "New Kotlin parameter name conflicts with another parameter in the exact family", operation,
        )
        val familyById = familyParameters.associateBy { it.id }
        val locations = buildList {
            familyParameters.forEach { add(it.location to it.name) }
            catalogue.usages.filter { it.targetId in familyById }.forEach { usage ->
                add(usage.location to familyById.getValue(usage.targetId).name)
            }
        }.distinctBy { it.first }
            .sortedWith(compareBy({ it.first.path.toString() }, { it.first.range.start.line }, { it.first.range.start.character }))
        if (locations.size != locations.map { it.first }.distinct().size || locations.any { (location, expected) ->
                val source = snapshot.files.singleOrNull { it.path.normalize() == location.path.normalize() }
                    ?: return@any true
                selectedText(source.content, location) != expected ||
                    snapshot.owningBuildSourceRoots(location.path).any { it.generated }
            }) return refused(
            snapshot, "kotlin.changeSignatureRangeInvalid",
            "K2 parameter declaration/use evidence contains a missing, generated, duplicate, or mismatched token", operation,
        )
        val before = when (val result = kotlin.compilerDiagnostics(snapshot)) {
            is KotlinCompilerDiagnosticsResult.Available -> result
            is KotlinCompilerDiagnosticsResult.Refused -> return refused(
                snapshot, result.reason.code ?: "kotlin.changeSignatureEvidenceUnavailable", result.reason.message,
                operation,
            )
            is KotlinCompilerDiagnosticsResult.Error -> return refused(
                snapshot, result.failure.code ?: "kotlin.changeSignatureEvidenceUnavailable", result.failure.message,
                operation,
            )
        }
        if (before.symbolFailure != null || before.symbols == null ||
            before.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) return refused(
            snapshot, "kotlin.changeSignatureBaselineIncomplete",
            "Kotlin parameter rename requires complete error-free K2 evidence", operation, before.diagnostics,
        )
        val baseline = semanticFingerprint(before, snapshot, familyById.keys)
        val workspaceEdit = WorkspaceEdit(
            locations.groupBy { it.first.path.normalize() }.toSortedMap(compareBy { it.toString() }).map { (path, entries) ->
                FileEdit.Modify(path, entries.map { (location, _) -> TextEdit(location.range, newParameterName) })
            },
        )
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }.getOrElse {
            return refused(
                snapshot, "kotlin.changeSignaturePreviewInvalid", it.message ?: "Invalid Kotlin signature preview",
                operation,
            )
        }
        val after = when (val result = kotlin.compilerDiagnostics(staged)) {
            is KotlinCompilerDiagnosticsResult.Available -> result
            is KotlinCompilerDiagnosticsResult.Refused -> return refused(
                snapshot, result.reason.code ?: "kotlin.changeSignatureStagedEvidenceUnavailable", result.reason.message,
                operation,
            )
            is KotlinCompilerDiagnosticsResult.Error -> return refused(
                snapshot, result.failure.code ?: "kotlin.changeSignatureStagedEvidenceUnavailable", result.failure.message,
                operation,
            )
        }
        if (after.symbolFailure != null || after.symbols == null ||
            after.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) return refused(
            snapshot, "kotlin.changeSignatureDiagnosticsRegression",
            "Kotlin parameter rename introduces compiler errors or incomplete symbol evidence",
            operation, after.diagnostics,
        )
        if (semanticFingerprint(after, staged, familyById.keys) != baseline) return refused(
            snapshot, "kotlin.changeSignatureBindingChanged",
            "Kotlin parameter rename changes a non-name compiler-resolved declaration or usage binding",
            operation, after.diagnostics,
        )
        val renamedParameters = after.symbols.symbols.filter { it.id in familyById.keys }
        if (renamedParameters.size != familyParameters.size || renamedParameters.any { it.name != newParameterName }) {
            return refused(
                snapshot, "kotlin.changeSignaturePostImageIdentityMissing",
                "Staged K2 evidence does not contain every renamed parameter at its unchanged JVM ordinal",
                operation, after.diagnostics,
            )
        }
        return PatchPlan(
            operation = operation,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.98,
            requiresUserApproval = true,
            summary = "Rename Kotlin parameter '$oldParameterName' to '$newParameterName' across ${familyParameters.size} exact declaration(s) and ${locations.size - familyParameters.size} resolved use(s).",
            affectedFiles = workspaceEdit.affectedFiles(),
            workspaceEdit = workspaceEdit,
            diagnosticsBefore = before.diagnostics,
            diagnosticsAfterPreview = after.diagnostics,
            warnings = if (externallyVisible) listOf(
                "Kotlin source API parameter rename: unknown external named-argument consumers were explicitly accepted.",
            ) else emptyList(),
            riskLevel = if (externallyVisible) RiskLevel.HIGH else RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.NATIVE_AST,
        )
    }

    private fun semanticFingerprint(
        result: KotlinCompilerDiagnosticsResult.Available,
        snapshot: ProjectSnapshot,
        renamedParameterIds: Set<SymbolId>,
    ): SemanticFingerprint {
        val sourceByPath = snapshot.files.associateBy { it.path.normalize() }
        fun selected(location: SourceLocation): String {
            val source = sourceByPath.getValue(location.path.normalize())
            return selectedText(source.content, location)
        }
        val symbols = requireNotNull(result.symbols).symbols.map { symbol ->
            val name = if (symbol.id in renamedParameterIds) "<renamed-parameter>" else symbol.name
            listOf(symbol.id.value, name, symbol.kind.name, symbol.location.path.normalize()).joinToString("\u0000")
        }.sorted()
        val internal = result.usages.map { usage ->
            listOf(
                usage.location.path.normalize(), usage.targetId.value,
                if (usage.targetId in renamedParameterIds) "<renamed-parameter>" else selected(usage.location),
            ).joinToString("\u0000")
        }.sorted()
        val externalTypes = result.externalTypeUsages.map {
            listOf(it.location.path.normalize(), it.jvmBinaryName, selected(it.location)).joinToString("\u0000")
        }.sorted()
        val externalCallables = result.externalCallableUsages.map {
            listOf(it.location.path.normalize(), it.jvmOwner, it.callableName, it.jvmDescriptor, selected(it.location))
                .joinToString("\u0000")
        }.sorted()
        return SemanticFingerprint(symbols, internal, externalTypes, externalCallables)
    }

    private fun selectedText(content: String, location: SourceLocation): String {
        val start = runCatching { TextEdits.offsetOf(content, location.range.start) }.getOrNull() ?: return ""
        val end = runCatching { TextEdits.offsetOf(content, location.range.end) }.getOrNull() ?: return ""
        return if (start in 0..end && end <= content.length) content.substring(start, end) else ""
    }

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        message: String,
        operation: String,
        diagnostics: List<Diagnostic> = emptyList(),
    ) = PatchPlan(
        operation = operation,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsAfterPreview = diagnostics,
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.NATIVE_AST,
        refusalCode = code,
    )

    private data class SemanticFingerprint(
        val symbols: List<String>,
        val internal: List<String>,
        val externalTypes: List<String>,
        val externalCallables: List<String>,
    )

    companion object {
        const val OPERATION = "changeSignature.renameParameter"
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,511}")
        private val KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
            "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
            "try", "typealias", "typeof", "val", "var", "when", "while",
        )
    }
}
