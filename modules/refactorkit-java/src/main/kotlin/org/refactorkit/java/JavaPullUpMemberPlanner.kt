package org.refactorkit.java

import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.Annotation
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.NumberLiteral
import org.eclipse.jdt.core.dom.PrimitiveType
import org.eclipse.jdt.core.dom.ReturnStatement
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.Path

data class JavaPullUpMemberRequest(
    val file: Path,
    val methodSymbol: String,
    val targetType: String,
)

/**
 * First managed pull-up cell. It moves one final, zero-argument primitive-int
 * literal method to its direct superclass when both declarations share one
 * source file and one available non-generated source set. All other shapes
 * fail closed before producing an edit.
 */
class JavaPullUpMemberPlanner(private val adapter: JavaLanguageAdapter) {
    fun preview(snapshot: ProjectSnapshot, request: JavaPullUpMemberRequest): PatchPlan {
        val file = snapshot.files.singleOrNull {
            it.path.normalize() == request.file.normalize() && it.languageId == "java"
        } ?: return refused(snapshot, "java.hierarchy.pullUp.fileUnavailable", "The selected Java source is absent or ambiguous in the snapshot.")
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "java.hierarchy.pullUp.generatedSource", "Generated Java source cannot be changed: $reason")
        }

        val sourceRoot = snapshot.owningBuildSourceRoots(file.path).singleOrNull()
            ?: return refused(snapshot, "java.hierarchy.pullUp.sourceRootUnavailable", "The selected file has no unique effective Java source-set root.")
        if (sourceRoot.generated || sourceRoot.modelStatus != org.refactorkit.core.BuildModelStatus.AVAILABLE) {
            return refused(snapshot, "java.hierarchy.pullUp.sourceRootUnavailable", "Pull-up requires an available non-generated Java source set.")
        }

        val before = runCatching { adapter.diagnostics(snapshot) }.getOrElse {
            return refused(snapshot, "java.hierarchy.pullUp.diagnosticsUnavailable", "Java diagnostics are unavailable for the selected snapshot.")
        }
        val resolution = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(snapshot, file.path) { unit, _ ->
            previewBound(snapshot, file.path, request, before, unit, sourceRoot)
        }
        return when (resolution) {
            is JdtBoundCompilationUnitResolution.Available -> resolution.value
            is JdtBoundCompilationUnitResolution.Refused -> refused(
                snapshot,
                "java.hierarchy.pullUp.analysisUnavailable",
                "A complete SDK-bound Java source-set environment is unavailable for the selected method.",
            )
        }
    }

    private fun previewBound(
        snapshot: ProjectSnapshot,
        filePath: Path,
        request: JavaPullUpMemberRequest,
        before: List<Diagnostic>,
        unit: CompilationUnit,
        sourceRoot: org.refactorkit.core.BuildSourceRootOwnership,
    ): PatchPlan {
        val file = snapshot.files.single { it.path.normalize() == filePath.normalize() }
        val content = file.content
        val types = unit.types().filterIsInstance<TypeDeclaration>()
        if (unit.types().size != 2 || types.size != 2 || types.any { it.parent !== unit }) {
            return refused(snapshot, "java.hierarchy.pullUp.compilationUnitUnsupported", "The bounded pull-up cell requires exactly two top-level classes in one compilation unit.")
        }

        val ownerAndMethod = types.flatMap { owner ->
            owner.bodyDeclarations().filterIsInstance<MethodDeclaration>()
                .filter { method ->
                    val binding = method.resolveBinding() ?: return@filter false
                    binding.key.isNotBlank() &&
                        "${binding.declaringClass?.qualifiedName}#${methodSignatureForPullUp(binding)}" == request.methodSymbol
                }
                .map { method -> owner to method }
        }.singleOrNull()
        val owner = ownerAndMethod?.first
            ?: return refused(snapshot, "java.hierarchy.pullUp.methodUnavailable", "The selection must identify exactly one saved JDT-bound method declaration.")
        val method = ownerAndMethod.second
        val ownerBinding = owner.resolveBinding()
            ?: return refused(snapshot, "java.hierarchy.pullUp.bindingIncomplete", "The selected child class has no JDT type binding.")
        val methodBinding = method.resolveBinding()
            ?: return refused(snapshot, "java.hierarchy.pullUp.bindingIncomplete", "The selected method has no JDT method binding.")
        val packageName = unit.`package`?.name?.fullyQualifiedName.orEmpty()
        val target = types.singleOrNull { it.resolveBinding()?.qualifiedName == request.targetType }
            ?: return refused(snapshot, "java.hierarchy.pullUp.targetUnavailable", "The requested target must identify exactly one top-level type in the same source file.")
        val targetBinding = target.resolveBinding()
            ?: return refused(snapshot, "java.hierarchy.pullUp.bindingIncomplete", "The requested superclass has no JDT type binding.")
        val directSuperclass = ownerBinding.superclass
        if (owner.isInterface || target.isInterface || ownerBinding.isRecovered || targetBinding.isRecovered ||
            ownerBinding.key.isNullOrBlank() || targetBinding.key.isNullOrBlank() ||
            ownerBinding.qualifiedName == request.targetType || directSuperclass == null || directSuperclass.isRecovered ||
            directSuperclass.key != targetBinding.key || directSuperclass.qualifiedName != request.targetType ||
            packageName != targetBinding.`package`?.name.orEmpty() || owner.typeParameters().isNotEmpty() || target.typeParameters().isNotEmpty() ||
            owner.superInterfaceTypes().isNotEmpty() || target.superInterfaceTypes().isNotEmpty() ||
            owner.modifiers().any { it.toString() == "sealed" || it.toString() == "non-sealed" } ||
            target.modifiers().any { it.toString() == "sealed" || it.toString() == "non-sealed" }) {
            return refused(snapshot, "java.hierarchy.pullUp.hierarchyUnsupported", "The selected class and target must be ordinary same-package classes linked by the exact direct-superclass binding.")
        }
        if (method.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0 || methodBinding.isRecovered ||
            methodBinding.key.isNullOrBlank() || methodBinding.declaringClass?.key != ownerBinding.key ||
            method.name.identifier.isBlank()) {
            return refused(snapshot, "java.hierarchy.pullUp.bindingIncomplete", "The selected method binding is incomplete or belongs to another type.")
        }
        if (method.isConstructor || method.body == null || !Modifier.isFinal(methodBinding.modifiers) ||
            Modifier.isPrivate(methodBinding.modifiers) || Modifier.isStatic(methodBinding.modifiers) ||
            Modifier.isSynchronized(methodBinding.modifiers) || method.typeParameters().isNotEmpty() ||
            method.parameters().isNotEmpty() || method.thrownExceptionTypes().isNotEmpty() || method.isVarargs ||
            method.modifiers().any { it is Annotation } ||
            (method.returnType2 as? PrimitiveType)?.primitiveTypeCode != PrimitiveType.INT ||
            !methodBinding.returnType.isPrimitive || methodBinding.returnType.name != "int") {
            return refused(snapshot, "java.hierarchy.pullUp.signatureUnsupported", "Only an unannotated final, non-static, zero-argument int method with accessible visibility is supported.")
        }
        val body = method.body.statements().singleOrNull() as? ReturnStatement
            ?: return refused(snapshot, "java.hierarchy.pullUp.bodyUnsupported", "The method body must contain exactly one int return statement.")
        val literal = body.expression as? NumberLiteral
            ?: return refused(snapshot, "java.hierarchy.pullUp.bodyUnsupported", "The return expression must be one numeric int literal.")
        if (literal.resolveTypeBinding()?.let { it.isPrimitive && it.name == "int" } != true) {
            return refused(snapshot, "java.hierarchy.pullUp.bodyUnsupported", "The return literal must have compiler-resolved primitive int type.")
        }
        if (owner.bodyDeclarations().filterIsInstance<MethodDeclaration>().count { it.name.identifier == method.name.identifier } != 1 ||
            hasMethodInHierarchy(targetBinding, method.name.identifier)) {
            return refused(snapshot, "java.hierarchy.pullUp.memberConflict", "The method must be unambiguous and no same-named method may exist on the target or its ancestors.")
        }

        val methodSymbol = JdtJavaSemanticAnalyzer().analyze(snapshot).symbols.singleOrNull {
            it.qualifiedName == request.methodSymbol && it.kind == JdtJavaSemanticSymbolKind.METHOD &&
                it.path.normalize() == file.path.normalize() && it.bindingKey == methodBinding.methodDeclaration.key && !it.recovered
        } ?: return refused(snapshot, "java.hierarchy.pullUp.selectionUnavailable", "The selected JDT method symbol is absent or ambiguous in complete workspace analysis.")
        val referenceProjection = adapter.semanticReferences(
            snapshot,
            SourceLocation(file.path, methodSymbol.sourceRange),
            includeDeclaration = true,
            limit = ProtocolLimits.MAX_REFERENCE_RESULTS,
        ) as? JavaJdtReferencesProjection.Available
            ?: return refused(snapshot, "java.hierarchy.pullUp.referencesUnavailable", "Compiler-bound method references are unavailable.")
        if (!referenceProjection.complete || referenceProjection.warningCount != 0 || referenceProjection.truncated ||
            referenceProjection.references.none { it.location == SourceLocation(file.path, methodSymbol.sourceRange) }) {
            return refused(snapshot, "java.hierarchy.pullUp.referencesIncomplete", "Every reference to the selected method must be compiler-bound and complete.")
        }
        val foreignReference = referenceProjection.references.firstOrNull { reference ->
            snapshot.owningBuildSourceRoots(reference.location.path).singleOrNull()?.let { ownership ->
                ownership.module.id != sourceRoot.module.id || ownership.sourceSet.id != sourceRoot.sourceSet.id ||
                    ownership.modelStatus != org.refactorkit.core.BuildModelStatus.AVAILABLE || ownership.generated
            } != false
        }
        if (foreignReference != null) {
            return refused(snapshot, "java.hierarchy.pullUp.usageOutsideSourceSet", "All bound method references must be owned by the same available non-generated source set.")
        }
        val semanticBefore = runCatching { JdtJavaSemanticAnalyzer().analyze(snapshot) }.getOrElse {
            return refused(snapshot, "java.hierarchy.pullUp.referencesUnavailable", "Complete compiler-bound workspace analysis is unavailable.")
        }
        if (semanticBefore.warnings.isNotEmpty() || semanticBefore.methodReferenceUses.any {
                it.methodBindingKey == methodBinding.methodDeclaration.key
            }) {
            return refused(snapshot, "java.hierarchy.pullUp.referenceShapeUnsupported", "Incomplete analysis or method-reference expressions prevent a safe pull-up.")
        }

        val sourceStart = method.startPosition
        val sourceEnd = sourceStart + method.length
        val lineStart = content.lastIndexOf('\n', (sourceStart - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val originalIndent = content.substring(lineStart, sourceStart)
        val parentClose = target.startPosition + target.length - 1
        if (sourceStart < 0 || sourceEnd > content.length || !originalIndent.all(Char::isWhitespace) ||
            parentClose !in 0 until content.length || content[parentClose] != '}') {
            return refused(snapshot, "java.hierarchy.pullUp.declarationLayoutUnsupported", "The selected method or target class has no stable line-oriented edit boundary.")
        }
        val methodLineEnd = when {
            content.startsWith("\r\n", sourceEnd) -> sourceEnd + 2
            content.startsWith("\n", sourceEnd) -> sourceEnd + 1
            else -> sourceEnd
        }
        val targetLineStart = content.lastIndexOf('\n', parentClose - 1).let { if (it < 0) 0 else it + 1 }
        val targetIndent = content.substring(targetLineStart, parentClose)
        if (!targetIndent.all(Char::isWhitespace) || targetLineStart in lineStart until methodLineEnd) {
            return refused(snapshot, "java.hierarchy.pullUp.declarationLayoutUnsupported", "The target closing brace must be on a stable line boundary.")
        }
        val lineEnding = if (content.contains("\r\n")) "\r\n" else "\n"
        val movedMethod = content.substring(sourceStart, sourceEnd).lines().joinToString(lineEnding) { line ->
            "    " + if (originalIndent.isNotEmpty() && line.startsWith(originalIndent)) line.removePrefix(originalIndent) else line
        } + lineEnding
        val edit = WorkspaceEdit(listOf(FileEdit.Modify(file.path, listOf(
            TextEdit(TextEdits.rangeForOffset(content, lineStart, methodLineEnd - lineStart), ""),
            TextEdit(TextEdits.rangeForOffset(content, targetLineStart, 0), movedMethod),
        ))))
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, edit) }.getOrElse {
            return refused(snapshot, "java.hierarchy.pullUp.editInvalid", "The pull-up edits cannot be staged against the captured workspace.")
        }
        val stagedMethodRangeResolution = runCatching {
            JdtJavaSemanticAnalyzer().withBoundCompilationUnit(staged, file.path) { stagedUnit, _ ->
                val stagedChild = stagedUnit.types().filterIsInstance<TypeDeclaration>()
                    .singleOrNull { it.resolveBinding()?.qualifiedName == ownerBinding.qualifiedName }
                    ?: return@withBoundCompilationUnit null
                val stagedParent = stagedUnit.types().filterIsInstance<TypeDeclaration>()
                    .singleOrNull { it.resolveBinding()?.qualifiedName == request.targetType }
                    ?: return@withBoundCompilationUnit null
                val stagedChildBinding = stagedChild.resolveBinding() ?: return@withBoundCompilationUnit null
                val stagedParentBinding = stagedParent.resolveBinding() ?: return@withBoundCompilationUnit null
                val stagedMovedMethod = stagedParent.bodyDeclarations().filterIsInstance<MethodDeclaration>()
                    .singleOrNull { it.name.identifier == method.name.identifier }
                    ?: return@withBoundCompilationUnit null
                val movedBinding = stagedMovedMethod.resolveBinding() ?: return@withBoundCompilationUnit null
                if (stagedChildBinding.isRecovered || stagedParentBinding.isRecovered || movedBinding.isRecovered ||
                    stagedChildBinding.superclass?.key != stagedParentBinding.key ||
                    movedBinding.declaringClass?.key != stagedParentBinding.key ||
                    !Modifier.isFinal(movedBinding.modifiers) ||
                    stagedChild.bodyDeclarations().filterIsInstance<MethodDeclaration>().any { it.name.identifier == method.name.identifier }) {
                    return@withBoundCompilationUnit null
                }
                TextEdits.rangeForOffset(staged.files.single { it.path.normalize() == file.path.normalize() }.content,
                    stagedMovedMethod.name.startPosition, stagedMovedMethod.name.length)
            }
        }.getOrNull()
        val stagedMethodRange = when (stagedMethodRangeResolution) {
            is JdtBoundCompilationUnitResolution.Available -> stagedMethodRangeResolution.value
            is JdtBoundCompilationUnitResolution.Refused, null -> null
        }
        if (stagedMethodRange == null) {
            return refused(snapshot, "java.hierarchy.pullUp.stagedBindingFailed", "Staged JDT bindings do not prove the method is declared on the direct superclass and inherited by the child.")
        }
        val stagedReferences = adapter.semanticReferences(
            staged,
            SourceLocation(file.path, stagedMethodRange),
            includeDeclaration = true,
            limit = ProtocolLimits.MAX_REFERENCE_RESULTS,
        ) as? JavaJdtReferencesProjection.Available
        if (stagedReferences == null || !stagedReferences.complete || stagedReferences.warningCount != 0 ||
            stagedReferences.truncated || stagedReferences.total != referenceProjection.total) {
            return refused(snapshot, "java.hierarchy.pullUp.stagedReferencesFailed", "Staged compiler bindings do not preserve the complete selected method reference set.")
        }
        val after = runCatching { adapter.diagnostics(staged) }.getOrElse {
            return refused(snapshot, "java.hierarchy.pullUp.stagedDiagnosticsUnavailable", "Staged Java diagnostics are unavailable.")
        }
        val priorErrors = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticIdentity).toSet()
        val introducedErrors = after.filter { it.severity == Diagnostic.Severity.ERROR }
            .map(::diagnosticIdentity).filterNot(priorErrors::contains)
        if (introducedErrors.isNotEmpty()) {
            return refused(snapshot, "java.hierarchy.pullUp.diagnosticsFailed", "Pulling up the method introduces ${introducedErrors.size} Java diagnostic error(s).")
        }
        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.95,
            requiresUserApproval = true,
            summary = "Move final method '${method.name.identifier}' from ${ownerBinding.qualifiedName} to its direct superclass ${targetBinding.qualifiedName}.",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
            diagnosticsBefore = before,
            diagnosticsAfterPreview = after,
            warnings = listOf("The hierarchy change may affect reflection and external or resource-shaped consumers; these are not analyzed by this bounded operation."),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun hasMethodInHierarchy(type: org.eclipse.jdt.core.dom.ITypeBinding, name: String): Boolean {
        val seen = mutableSetOf<String>()
        var current: org.eclipse.jdt.core.dom.ITypeBinding? = type
        while (current != null && !current.isRecovered && current.key !in seen) {
            current.key?.let(seen::add)
            if (current.declaredMethods.any { it.name == name }) return true
            current = current.superclass
        }
        return false
    }

    private fun diagnosticIdentity(diagnostic: Diagnostic): String =
        "${diagnostic.severity}|${diagnostic.code}|${diagnostic.message}|${diagnostic.location}"

    private fun refused(snapshot: ProjectSnapshot, code: String, message: String) = PatchPlan(
        operation = OPERATION,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = emptyList(),
        diagnosticsAfterPreview = emptyList(),
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.JDT_BINDING,
        refusalCode = code,
    )

    companion object {
        const val OPERATION = "pullUpMember"
    }
}

private fun methodSignatureForPullUp(binding: org.eclipse.jdt.core.dom.IMethodBinding): String =
    "${binding.name}(${binding.parameterTypes.joinToString(",") { it.qualifiedName }})"
