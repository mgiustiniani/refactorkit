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

data class JavaPushDownMemberRequest(
    val file: Path,
    val methodSymbol: String,
    val targetType: String,
)

/** The first managed push-down cell is limited to one binding-proven method and direct child. */
class JavaPushDownMemberPlanner(private val adapter: JavaLanguageAdapter) {
    fun preview(snapshot: ProjectSnapshot, request: JavaPushDownMemberRequest): PatchPlan {
        val file = snapshot.files.singleOrNull {
            it.path.normalize() == request.file.normalize() && it.languageId == "java"
        } ?: return refused(snapshot, "java.hierarchy.pushDown.fileUnavailable", "The selected Java source is absent or ambiguous in the snapshot.")
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "java.hierarchy.pushDown.generatedSource", "Generated Java source cannot be changed: $reason")
        }

        val sourceRoot = snapshot.owningBuildSourceRoots(file.path).singleOrNull()
            ?: return refused(snapshot, "java.hierarchy.pushDown.sourceRootUnavailable", "The selected file has no unique effective Java source-set root.")
        if (sourceRoot.generated || sourceRoot.modelStatus != org.refactorkit.core.BuildModelStatus.AVAILABLE) {
            return refused(snapshot, "java.hierarchy.pushDown.sourceRootUnavailable", "Push-down requires an available non-generated Java source set.")
        }

        val before = runCatching { adapter.diagnostics(snapshot) }.getOrElse {
            return refused(snapshot, "java.hierarchy.pushDown.diagnosticsUnavailable", "Java diagnostics are unavailable for the selected snapshot.")
        }
        val resolution = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(snapshot, file.path) { unit, _ ->
            previewBound(snapshot, file.path, request, before, unit, sourceRoot)
        }
        return when (resolution) {
            is JdtBoundCompilationUnitResolution.Available -> resolution.value
            is JdtBoundCompilationUnitResolution.Refused -> refused(
                snapshot,
                "java.hierarchy.pushDown.analysisUnavailable",
                "A complete SDK-bound Java source-set environment is unavailable for the selected method.",
            )
        }
    }

    private fun previewBound(
        snapshot: ProjectSnapshot,
        filePath: Path,
        request: JavaPushDownMemberRequest,
        before: List<Diagnostic>,
        unit: CompilationUnit,
        sourceRoot: org.refactorkit.core.BuildSourceRootOwnership,
    ): PatchPlan {
        val file = snapshot.files.single { it.path.normalize() == filePath.normalize() }
        val content = file.content
        val types = unit.types().filterIsInstance<TypeDeclaration>()
        if (unit.types().size != 2 || types.size != 2 || types.any { it.parent !== unit }) {
            return refused(snapshot, "java.hierarchy.pushDown.compilationUnitUnsupported", "The bounded push-down cell requires exactly two top-level classes in one compilation unit.")
        }

        val ownerAndMethod = types.flatMap { owner ->
            owner.bodyDeclarations().filterIsInstance<MethodDeclaration>()
                .filter { method ->
                    val binding = method.resolveBinding() ?: return@filter false
                    binding.key.isNotBlank() &&
                        "${binding.declaringClass?.qualifiedName}#${methodSignature(binding)}" == request.methodSymbol
                }
                .map { method -> owner to method }
        }.singleOrNull()
        val owner = ownerAndMethod?.first
            ?: return refused(snapshot, "java.hierarchy.pushDown.methodUnavailable", "The selection must identify exactly one saved JDT-bound method declaration.")
        val method = ownerAndMethod.second
        val ownerBinding = owner.resolveBinding()
            ?: return refused(snapshot, "java.hierarchy.pushDown.bindingIncomplete", "The selected parent class has no JDT type binding.")
        val methodBinding = method.resolveBinding()
            ?: return refused(snapshot, "java.hierarchy.pushDown.bindingIncomplete", "The selected method has no JDT method binding.")
        val packageName = unit.`package`?.name?.fullyQualifiedName.orEmpty()
        val target = types.singleOrNull { it.resolveBinding()?.qualifiedName == request.targetType }
            ?: return refused(snapshot, "java.hierarchy.pushDown.targetUnavailable", "The requested target must identify exactly one top-level type in the same source file.")
        val targetBinding = target.resolveBinding()
            ?: return refused(snapshot, "java.hierarchy.pushDown.bindingIncomplete", "The requested child class has no JDT type binding.")
        val directChildren = runCatching { JdtJavaSemanticAnalyzer().analyze(snapshot) }
            .getOrElse { return refused(snapshot, "java.hierarchy.pushDown.hierarchyUnavailable", "Complete compiler-bound hierarchy analysis is unavailable.") }
        if (directChildren.warnings.isNotEmpty()) {
            return refused(snapshot, "java.hierarchy.pushDown.hierarchyIncomplete", "The workspace hierarchy contains unresolved compiler evidence.")
        }
        val children = directChildren.typeInheritance.filter { it.supertypeBindingKey == ownerBinding.key }
        if (owner.isInterface || target.isInterface || ownerBinding.isRecovered || targetBinding.isRecovered ||
            ownerBinding.key.isNullOrBlank() || targetBinding.key.isNullOrBlank() ||
            ownerBinding.qualifiedName == request.targetType || targetBinding.superclass?.key != ownerBinding.key ||
            targetBinding.superclass?.qualifiedName != ownerBinding.qualifiedName ||
            packageName != ownerBinding.`package`?.name.orEmpty() || packageName != targetBinding.`package`?.name.orEmpty() ||
            owner.typeParameters().isNotEmpty() || target.typeParameters().isNotEmpty() ||
            owner.superInterfaceTypes().isNotEmpty() || target.superInterfaceTypes().isNotEmpty() ||
            !Modifier.isAbstract(ownerBinding.modifiers) || Modifier.isPublic(ownerBinding.modifiers) ||
            !Modifier.isFinal(targetBinding.modifiers) || Modifier.isPublic(targetBinding.modifiers) ||
            children.size != 1 || children.single().subtypeBindingKey != targetBinding.key ||
            children.single().evidence != JdtJavaSemanticEvidence.JDT_BINDING) {
            return refused(snapshot, "java.hierarchy.pushDown.hierarchyUnsupported", "The parent and target must be package-private ordinary classes linked by the complete direct-superclass binding, with one final source child.")
        }
        if (method.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0 || methodBinding.isRecovered ||
            methodBinding.key.isNullOrBlank() || methodBinding.declaringClass?.key != ownerBinding.key ||
            method.name.identifier.isBlank()) {
            return refused(snapshot, "java.hierarchy.pushDown.bindingIncomplete", "The selected method binding is incomplete or belongs to another type.")
        }
        if (method.isConstructor || method.body == null || !Modifier.isFinal(methodBinding.modifiers) ||
            !Modifier.isProtected(methodBinding.modifiers) || Modifier.isPrivate(methodBinding.modifiers) ||
            Modifier.isStatic(methodBinding.modifiers) || Modifier.isSynchronized(methodBinding.modifiers) ||
            method.typeParameters().isNotEmpty() || method.parameters().isNotEmpty() ||
            method.thrownExceptionTypes().isNotEmpty() || method.isVarargs ||
            method.modifiers().any { it is Annotation } ||
            (method.returnType2 as? PrimitiveType)?.primitiveTypeCode != PrimitiveType.INT ||
            !methodBinding.returnType.isPrimitive || methodBinding.returnType.name != "int") {
            return refused(snapshot, "java.hierarchy.pushDown.signatureUnsupported", "Only an unannotated protected final, non-static, zero-argument int method is supported.")
        }
        val body = method.body.statements().singleOrNull() as? ReturnStatement
            ?: return refused(snapshot, "java.hierarchy.pushDown.bodyUnsupported", "The method body must contain exactly one int return statement.")
        val literal = body.expression as? NumberLiteral
            ?: return refused(snapshot, "java.hierarchy.pushDown.bodyUnsupported", "The return expression must be one numeric int literal.")
        if (literal.resolveTypeBinding()?.let { it.isPrimitive && it.name == "int" } != true) {
            return refused(snapshot, "java.hierarchy.pushDown.bodyUnsupported", "The return literal must have compiler-resolved primitive int type.")
        }
        if (owner.bodyDeclarations().filterIsInstance<MethodDeclaration>().count { it.name.identifier == method.name.identifier } != 1 ||
            target.bodyDeclarations().filterIsInstance<MethodDeclaration>().any { it.name.identifier == method.name.identifier } ||
            hasMethodInHierarchy(ownerBinding.superclass, method.name.identifier)) {
            return refused(snapshot, "java.hierarchy.pushDown.memberConflict", "The method must be unambiguous, absent from the child, and absent from the parent's ancestors.")
        }

        val methodSymbol = directChildren.symbols.singleOrNull {
            it.qualifiedName == request.methodSymbol && it.kind == JdtJavaSemanticSymbolKind.METHOD &&
                it.path.normalize() == file.path.normalize() && it.bindingKey == methodBinding.methodDeclaration.key && !it.recovered
        } ?: return refused(snapshot, "java.hierarchy.pushDown.selectionUnavailable", "The selected JDT method symbol is absent or ambiguous in complete workspace analysis.")
        val referenceProjection = adapter.semanticReferences(
            snapshot,
            SourceLocation(file.path, methodSymbol.sourceRange),
            includeDeclaration = true,
            limit = ProtocolLimits.MAX_REFERENCE_RESULTS,
        ) as? JavaJdtReferencesProjection.Available
            ?: return refused(snapshot, "java.hierarchy.pushDown.referencesUnavailable", "Compiler-bound method references are unavailable.")
        val invocations = directChildren.invocations.filter { it.methodBindingKey == methodBinding.methodDeclaration.key }
        if (!referenceProjection.complete || referenceProjection.warningCount != 0 || referenceProjection.truncated ||
            referenceProjection.references.size != referenceProjection.total ||
            referenceProjection.references.none { it.location == SourceLocation(file.path, methodSymbol.sourceRange) } ||
            referenceProjection.references.any { it.location.path.normalize() != file.path.normalize() } ||
            invocations.isEmpty() || invocations.size + 1 != referenceProjection.total ||
            invocations.any {
                it.evidence != JdtJavaSemanticEvidence.JDT_BINDING || it.methodBindingRecovered || it.callerBindingRecovered ||
                    it.callerMethodBindingKey.isNullOrBlank() || it.callerMethodQualifiedName?.startsWith("${targetBinding.qualifiedName}#") != true ||
                    it.path.normalize() != file.path.normalize()
            } || directChildren.methodReferenceUses.any { it.methodBindingKey == methodBinding.methodDeclaration.key }) {
            return refused(snapshot, "java.hierarchy.pushDown.referencesIncomplete", "Every method use must be a complete compiler-bound invocation in the exact final child class.")
        }
        val expectedLocations = invocations.map { SourceLocation(it.path, it.nameRange) }.toSet() +
            SourceLocation(file.path, methodSymbol.sourceRange)
        if (referenceProjection.references.map { it.location }.toSet() != expectedLocations) {
            return refused(snapshot, "java.hierarchy.pushDown.referenceShapeUnsupported", "The compiler reference set contains an invocation shape outside this bounded cell.")
        }

        val sourceStart = method.startPosition
        val sourceEnd = sourceStart + method.length
        val lineStart = content.lastIndexOf('\n', (sourceStart - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val originalIndent = content.substring(lineStart, sourceStart)
        val targetClose = target.startPosition + target.length - 1
        if (sourceStart < 0 || sourceEnd > content.length || !originalIndent.all(Char::isWhitespace) ||
            targetClose !in 0 until content.length || content[targetClose] != '}') {
            return refused(snapshot, "java.hierarchy.pushDown.declarationLayoutUnsupported", "The selected method or child class has no stable line-oriented edit boundary.")
        }
        val methodLineEnd = when {
            content.startsWith("\r\n", sourceEnd) -> sourceEnd + 2
            content.startsWith("\n", sourceEnd) -> sourceEnd + 1
            else -> sourceEnd
        }
        val targetLineStart = content.lastIndexOf('\n', targetClose - 1).let { if (it < 0) 0 else it + 1 }
        val targetIndent = content.substring(targetLineStart, targetClose)
        if (!targetIndent.all(Char::isWhitespace) || targetLineStart in lineStart until methodLineEnd) {
            return refused(snapshot, "java.hierarchy.pushDown.declarationLayoutUnsupported", "The child closing brace must be on a stable line boundary.")
        }
        val lineEnding = if (content.contains("\r\n")) "\r\n" else "\n"
        val movedMethod = content.substring(sourceStart, sourceEnd).lines().joinToString(lineEnding) { line ->
            "    " + if (originalIndent.isNotEmpty() && line.startsWith(originalIndent)) line.removePrefix(originalIndent) else line
        } + lineEnding
        val edit = WorkspaceEdit(listOf(FileEdit.Modify(file.path, listOf(
            TextEdit(TextEdits.rangeForOffset(content, targetLineStart, 0), movedMethod),
            TextEdit(TextEdits.rangeForOffset(content, lineStart, methodLineEnd - lineStart), ""),
        ))))
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, edit) }.getOrElse {
            return refused(snapshot, "java.hierarchy.pushDown.editInvalid", "The push-down edits cannot be staged against the captured workspace.")
        }
        val stagedBinding = runCatching {
            JdtJavaSemanticAnalyzer().withBoundCompilationUnit(staged, file.path) { stagedUnit, _ ->
                val stagedTypes = stagedUnit.types().filterIsInstance<TypeDeclaration>()
                val stagedParent = stagedTypes.singleOrNull { it.resolveBinding()?.qualifiedName == ownerBinding.qualifiedName }
                    ?: return@withBoundCompilationUnit null
                val stagedChild = stagedTypes.singleOrNull { it.resolveBinding()?.qualifiedName == targetBinding.qualifiedName }
                    ?: return@withBoundCompilationUnit null
                val stagedParentBinding = stagedParent.resolveBinding() ?: return@withBoundCompilationUnit null
                val stagedChildBinding = stagedChild.resolveBinding() ?: return@withBoundCompilationUnit null
                val stagedMovedMethod = stagedChild.bodyDeclarations().filterIsInstance<MethodDeclaration>()
                    .singleOrNull { it.name.identifier == method.name.identifier }
                    ?: return@withBoundCompilationUnit null
                val movedBinding = stagedMovedMethod.resolveBinding() ?: return@withBoundCompilationUnit null
                if (stagedParentBinding.isRecovered || stagedChildBinding.isRecovered || movedBinding.isRecovered ||
                    stagedChildBinding.superclass?.key != stagedParentBinding.key ||
                    movedBinding.declaringClass?.key != stagedChildBinding.key || !Modifier.isFinal(movedBinding.modifiers) ||
                    stagedParent.bodyDeclarations().filterIsInstance<MethodDeclaration>().any { it.name.identifier == method.name.identifier }) {
                    return@withBoundCompilationUnit null
                }
                val stagedContent = staged.files.single { it.path.normalize() == file.path.normalize() }.content
                TextEdits.rangeForOffset(stagedContent, stagedMovedMethod.name.startPosition, stagedMovedMethod.name.length)
            }
        }.getOrNull()
        val stagedMethodRange = when (stagedBinding) {
            is JdtBoundCompilationUnitResolution.Available -> stagedBinding.value
            is JdtBoundCompilationUnitResolution.Refused, null -> null
        } ?: return refused(snapshot, "java.hierarchy.pushDown.stagedBindingFailed", "Staged JDT bindings do not prove the method is declared on the final child.")
        val stagedReferences = adapter.semanticReferences(
            staged,
            SourceLocation(file.path, stagedMethodRange),
            includeDeclaration = true,
            limit = ProtocolLimits.MAX_REFERENCE_RESULTS,
        ) as? JavaJdtReferencesProjection.Available
        if (stagedReferences == null || !stagedReferences.complete || stagedReferences.warningCount != 0 ||
            stagedReferences.truncated || stagedReferences.total != referenceProjection.total) {
            return refused(snapshot, "java.hierarchy.pushDown.stagedReferencesFailed", "Staged compiler bindings do not preserve the complete method reference set.")
        }
        val after = runCatching { adapter.diagnostics(staged) }.getOrElse {
            return refused(snapshot, "java.hierarchy.pushDown.stagedDiagnosticsUnavailable", "Staged Java diagnostics are unavailable.")
        }
        val priorErrors = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticIdentity).toSet()
        val introducedErrors = after.filter { it.severity == Diagnostic.Severity.ERROR }
            .map(::diagnosticIdentity).filterNot(priorErrors::contains)
        if (introducedErrors.isNotEmpty()) {
            return refused(snapshot, "java.hierarchy.pushDown.diagnosticsFailed", "Pushing down the method introduces ${introducedErrors.size} Java diagnostic error(s).")
        }
        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.95,
            requiresUserApproval = true,
            summary = "Move final method '${method.name.identifier}' from ${ownerBinding.qualifiedName} to its only direct child ${targetBinding.qualifiedName}.",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
            diagnosticsBefore = before,
            diagnosticsAfterPreview = after,
            warnings = listOf("The hierarchy change may affect reflection and external or resource-shaped consumers; these are not analyzed by this bounded operation."),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun hasMethodInHierarchy(type: org.eclipse.jdt.core.dom.ITypeBinding?, name: String): Boolean {
        val seen = mutableSetOf<String>()
        var current = type
        while (current != null && !current.isRecovered && current.key !in seen) {
            current.key?.let(seen::add)
            if (current.declaredMethods.any { it.name == name }) return true
            current = current.superclass
        }
        return false
    }

    private fun methodSignature(binding: org.eclipse.jdt.core.dom.IMethodBinding): String =
        "${binding.name}(${binding.parameterTypes.joinToString(",") { it.qualifiedName }})"

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
        const val OPERATION = "pushDownMember"
    }
}
