package org.refactorkit.java

import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.NumberLiteral
import org.eclipse.jdt.core.dom.PrimitiveType
import org.eclipse.jdt.core.dom.ReturnStatement
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.Name
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildSourceRootOwnership
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

data class JavaMoveMemberRequest(
    val file: Path,
    val memberSymbol: String,
    val memberBindingKey: String,
    val memberKind: String,
    val targetType: String,
    val targetBindingKey: String,
)

/**
 * First managed Java member-move cell. It moves one package-private static,
 * no-argument int method returning a literal between two top-level classes in
 * one compilation unit. Direct, binding-matched invocations in the same
 * available source set are retargeted. Unsupported shapes fail closed.
 */
class JavaMoveMemberPlanner(private val adapter: JavaLanguageAdapter) {
    fun preview(snapshot: ProjectSnapshot, request: JavaMoveMemberRequest): PatchPlan {
        if (request.memberKind != METHOD_KIND) {
            return refused(snapshot, "java.moveMember.memberKindUnsupported", "This moveMember cell supports a Java method selection only.")
        }
        val source = snapshot.files.singleOrNull {
            it.path.normalize() == request.file.normalize() && it.languageId == "java"
        } ?: return refused(snapshot, "java.moveMember.fileUnavailable", "The selected Java source is absent or ambiguous in the snapshot.")
        JavaGeneratedSourcePolicy.reason(source)?.let { reason ->
            return refused(snapshot, "java.moveMember.generatedSource", "Generated Java source cannot be changed: $reason")
        }
        val sourceRoot = snapshot.owningBuildSourceRoots(source.path).singleOrNull()
            ?: return refused(snapshot, "java.moveMember.sourceRootUnavailable", "The selected source has no unique effective Java source-set root.")
        if (!availableSourceRoot(sourceRoot)) {
            return refused(snapshot, "java.moveMember.sourceRootUnavailable", "Member moves require an available non-generated Java source set.")
        }

        val before = runCatching { adapter.diagnostics(snapshot) }.getOrElse {
            return refused(snapshot, "java.moveMember.diagnosticsUnavailable", "Java diagnostics are unavailable for the selected snapshot.")
        }
        val analyzer = JdtJavaSemanticAnalyzer()
        val analysis = runCatching { analyzer.analyze(snapshot) }.getOrElse {
            return refused(snapshot, "java.moveMember.analysisUnavailable", "Complete compiler-bound workspace analysis is unavailable.")
        }
        if (analysis.warnings.isNotEmpty()) {
            return refused(snapshot, "java.moveMember.referencesIncomplete", "Compiler analysis has warnings, so the workspace reference set is incomplete.")
        }
        val selectedSymbol = analysis.symbols.singleOrNull {
            it.kind == JdtJavaSemanticSymbolKind.METHOD && it.path.normalize() == source.path.normalize() &&
                it.qualifiedName == request.memberSymbol && it.bindingKey == request.memberBindingKey && !it.recovered
        } ?: return refused(snapshot, "java.moveMember.selectionStale", "The selected method does not match one exact saved JDT declaration binding.")
        val targetSymbol = analysis.symbols.singleOrNull {
            it.kind == JdtJavaSemanticSymbolKind.CLASS && it.qualifiedName == request.targetType &&
                it.bindingKey == request.targetBindingKey && !it.recovered
        } ?: return refused(snapshot, "java.moveMember.targetStale", "The target type does not match one exact saved JDT declaration binding.")
        val targetFile = snapshot.files.singleOrNull { it.path.normalize() == targetSymbol.path.normalize() && it.languageId == "java" }
            ?: return refused(snapshot, "java.moveMember.targetUnavailable", "The selected target type has no unique saved Java source.")
        JavaGeneratedSourcePolicy.reason(targetFile)?.let { reason ->
            return refused(snapshot, "java.moveMember.generatedTarget", "Generated Java target types cannot be changed: $reason")
        }
        val targetRoot = snapshot.owningBuildSourceRoots(targetFile.path).singleOrNull()
            ?: return refused(snapshot, "java.moveMember.targetSourceRootUnavailable", "The target type has no unique effective Java source-set root.")
        if (!availableSourceRoot(targetRoot) || sourceRoot.module.id != targetRoot.module.id ||
            sourceRoot.sourceSet.id != targetRoot.sourceSet.id) {
            return refused(snapshot, "java.moveMember.sourceSetMismatch", "The selected member and target must belong to one available non-generated module source set.")
        }
        if (source.path.normalize() != targetFile.path.normalize()) {
            return refused(snapshot, "java.moveMember.crossCompilationUnitUnsupported", "This first moveMember cell requires source and target top-level types in the same compilation unit.")
        }

        val bound = analyzer.withBoundCompilationUnit(snapshot, source.path) { unit, _ ->
            val types = unit.types().filterIsInstance<TypeDeclaration>()
            if (unit.types().size != 2 || types.size != 2 || types.any { it.parent !== unit }) {
                return@withBoundCompilationUnit BoundSelection.Refused(
                    "java.moveMember.compilationUnitUnsupported",
                    "The first moveMember cell requires exactly two top-level classes in one compilation unit.",
                )
            }
            val ownerAndMethod = types.flatMap { owner ->
                owner.bodyDeclarations().filterIsInstance<MethodDeclaration>()
                    .filter { it.resolveBinding()?.methodDeclaration?.key == request.memberBindingKey }
                    .map { owner to it }
            }.singleOrNull() ?: return@withBoundCompilationUnit BoundSelection.Refused(
                "java.moveMember.selectionStale", "The selected method binding is absent or ambiguous in the saved compilation unit.",
            )
            val owner = ownerAndMethod.first
            val method = ownerAndMethod.second
            val methodBinding = method.resolveBinding()?.methodDeclaration
                ?: return@withBoundCompilationUnit BoundSelection.Refused(
                    "java.moveMember.bindingIncomplete", "The selected method has no complete JDT binding.",
                )
            val ownerBinding = owner.resolveBinding()
                ?: return@withBoundCompilationUnit BoundSelection.Refused(
                    "java.moveMember.bindingIncomplete", "The declaring type has no complete JDT binding.",
                )
            val target = types.singleOrNull { type -> type.resolveBinding()?.key == request.targetBindingKey }
                ?: return@withBoundCompilationUnit BoundSelection.Refused(
                    "java.moveMember.targetStale", "The requested target binding is absent or ambiguous in the saved compilation unit.",
                )
            val targetBinding = target.resolveBinding()
                ?: return@withBoundCompilationUnit BoundSelection.Refused(
                    "java.moveMember.bindingIncomplete", "The target type has no complete JDT binding.",
                )
            val packageName = unit.`package`?.name?.fullyQualifiedName.orEmpty()
            val identitySupported = method.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) == 0 &&
                !methodBinding.isRecovered && methodBinding.key == request.memberBindingKey &&
                methodBinding.declaringClass?.key == ownerBinding.key &&
                ownerBinding.qualifiedName == selectedSymbol.qualifiedName.substringBefore('#') &&
                targetBinding.qualifiedName == request.targetType && !ownerBinding.isRecovered && !targetBinding.isRecovered &&
                !ownerBinding.key.isNullOrBlank() && targetBinding.key == request.targetBindingKey &&
                ownerBinding.key != targetBinding.key
            if (!identitySupported) return@withBoundCompilationUnit BoundSelection.Refused(
                "java.moveMember.bindingIdentityUnsupported", "The selected method and target bindings do not retain their exact saved identities.",
            )
            val hierarchySupported = packageName == targetBinding.`package`?.name.orEmpty() &&
                owner.typeParameters().isEmpty() && target.typeParameters().isEmpty() &&
                owner.superInterfaceTypes().isEmpty() && target.superInterfaceTypes().isEmpty() &&
                ownerBinding.superclass?.qualifiedName == "java.lang.Object" &&
                targetBinding.superclass?.qualifiedName == "java.lang.Object" && !owner.isInterface && !target.isInterface
            if (!hierarchySupported) return@withBoundCompilationUnit BoundSelection.Refused(
                "java.moveMember.typeShapeUnsupported", "The source and target must be same-package, non-generic top-level classes without an explicit hierarchy.",
            )
            val methodShapeSupported = method.name.identifier.isNotBlank() && !method.isConstructor && method.body != null &&
                Modifier.isStatic(methodBinding.modifiers) &&
                !Modifier.isPublic(methodBinding.modifiers) && !Modifier.isProtected(methodBinding.modifiers) &&
                !Modifier.isPrivate(methodBinding.modifiers) && !Modifier.isSynchronized(methodBinding.modifiers) &&
                !Modifier.isAbstract(methodBinding.modifiers) && !Modifier.isNative(methodBinding.modifiers) &&
                method.parameters().isEmpty() && method.typeParameters().isEmpty() &&
                method.thrownExceptionTypes().isEmpty() && !method.isVarargs && method.javadoc == null &&
                method.modifiers().none { it is org.eclipse.jdt.core.dom.Annotation } &&
                (method.returnType2 as? PrimitiveType)?.primitiveTypeCode == PrimitiveType.INT &&
                methodBinding.returnType.isPrimitive && methodBinding.returnType.name == "int"
            if (!methodShapeSupported) return@withBoundCompilationUnit BoundSelection.Refused(
                "java.moveMember.methodShapeUnsupported", "Only an unannotated package-private static no-argument int method is supported.",
            )
            val returnStatement = method.body.statements().singleOrNull() as? ReturnStatement
                ?: return@withBoundCompilationUnit BoundSelection.Refused(
                    "java.moveMember.methodBodyUnsupported", "The selected method body must contain only one int literal return.",
                )
            val literal = returnStatement.expression as? NumberLiteral
                ?: return@withBoundCompilationUnit BoundSelection.Refused(
                    "java.moveMember.methodBodyUnsupported", "The selected method body must return one numeric int literal.",
                )
            if (literal.resolveTypeBinding()?.let { it.isPrimitive && it.name == "int" } != true) {
                return@withBoundCompilationUnit BoundSelection.Refused(
                    "java.moveMember.methodBodyUnsupported", "The return literal must have compiler-resolved primitive int type.",
                )
            }
            val name = method.name.identifier
            if (targetBinding.declaredMethods.any { it.name == name } || owner.bodyDeclarations()
                    .filterIsInstance<MethodDeclaration>().count { it.name.identifier == name } != 1 ||
                hierarchyHasMethodNamed(targetBinding, name)) {
                return@withBoundCompilationUnit BoundSelection.Refused(
                    "java.moveMember.targetMemberConflict", "The target hierarchy must not declare or inherit a same-named method.",
                )
            }
            val content = source.content
            val start = method.startPosition
            val end = start + method.length
            val lineStart = content.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val lineEnd = content.indexOf('\n', end).let { if (it < 0) content.length else it }
            val prefix = content.substring(lineStart, start)
            val trailing = content.substring(end, lineEnd)
            val targetClose = target.startPosition + target.length - 1
            val targetLineStart = content.lastIndexOf('\n', (targetClose - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val targetIndent = content.substring(targetLineStart, targetClose)
            if (start < 0 || end > content.length || !prefix.all(Char::isWhitespace) ||
                !trailing.all(Char::isWhitespace) || targetClose !in content.indices || content[targetClose] != '}' ||
                !targetIndent.all(Char::isWhitespace) || targetLineStart in lineStart..lineEnd) {
                return@withBoundCompilationUnit BoundSelection.Refused(
                    "java.moveMember.declarationLayoutUnsupported", "The selected declaration or target closing brace has no stable line boundary.",
                )
            }
            BoundSelection.Available(
                memberName = name,
                ownerType = ownerBinding.qualifiedName,
                targetType = targetBinding.qualifiedName,
                sourceRange = start until end,
                sourceLineStart = lineStart,
                sourceLineEnd = if (lineEnd < content.length) lineEnd + 1 else lineEnd,
                targetLineStart = targetLineStart,
                sourceMemberIndent = prefix,
                targetMemberIndent = targetIndent + "    ",
                methodText = content.substring(start, end),
                content = content,
                methodNameRange = method.name.startPosition until method.name.startPosition + method.name.length,
            )
        }
        val selection = when (bound) {
            is JdtBoundCompilationUnitResolution.Available -> bound.value
            is JdtBoundCompilationUnitResolution.Refused -> return refused(
                snapshot, "java.moveMember.analysisUnavailable", "A complete SDK-bound source-set environment is unavailable for the selected method.",
            )
        }
        if (selection is BoundSelection.Refused) return refused(snapshot, selection.code, selection.message)
        selection as BoundSelection.Available

        val declarationLocation = SourceLocation(source.path, TextEdits.rangeForOffset(selection.content,
            selection.methodNameRange.first, selection.methodNameRange.last + 1 - selection.methodNameRange.first))
        val references = adapter.semanticReferences(
            snapshot, declarationLocation, includeDeclaration = true, limit = ProtocolLimits.MAX_REFERENCE_RESULTS,
        ) as? JavaJdtReferencesProjection.Available
            ?: return refused(snapshot, "java.moveMember.referencesUnavailable", "Compiler-bound method references are unavailable.")
        if (!references.complete || references.truncated || references.warningCount != 0 ||
            references.references.size != references.total ||
            references.references.none { it.location == declarationLocation }) {
            return refused(snapshot, "java.moveMember.referencesIncomplete", "Every in-workspace method reference must be compiler-bound and complete.")
        }
        if (analysis.methodReferenceUses.any { it.methodBindingKey == request.memberBindingKey }) {
            return refused(snapshot, "java.moveMember.methodReferenceUnsupported", "Method-reference consumers are outside this first moveMember cell.")
        }

        val contentByPath = snapshot.files.filter { it.languageId == "java" }.associate { it.path.normalize() to it.content }
        val editsByPath = linkedMapOf<Path, MutableList<TextEdit>>()
        val sourceContent = source.content
        val lineEnding = if (sourceContent.contains("\r\n")) "\r\n" else "\n"
        val movedText = reindentMovedMethod(
            selection.methodText,
            selection.sourceMemberIndent,
            selection.targetMemberIndent,
            lineEnding,
        ) ?: return refused(
            snapshot,
            "java.moveMember.declarationLayoutUnsupported",
            "The selected method body has a line layout that cannot be shifted without guessing.",
        )
        val sourceLineLength = selection.sourceLineEnd - selection.sourceLineStart
        editsByPath.getOrPut(source.path.normalize()) { mutableListOf() } += TextEdit(
            TextEdits.rangeForOffset(sourceContent, selection.sourceLineStart, sourceLineLength),
            "",
        )
        editsByPath.getOrPut(source.path.normalize()) { mutableListOf() } += TextEdit(
            TextEdits.rangeForOffset(sourceContent, selection.targetLineStart, 0), movedText,
        )
        val affectedCallPaths = mutableSetOf<Path>()
        references.references.filterNot { it.location == declarationLocation }.forEach { reference ->
            val referencePath = reference.location.path.normalize()
            val callFile = snapshot.files.singleOrNull { it.languageId == "java" && it.path.normalize() == referencePath }
                ?: return refused(snapshot, "java.moveMember.referencePathUnavailable", "A compiler-bound reference is not in the saved Java source set.")
            val ownership = snapshot.owningBuildSourceRoots(callFile.path).singleOrNull()
            if (ownership == null || !availableSourceRoot(ownership) || ownership.module.id != sourceRoot.module.id ||
                ownership.sourceSet.id != sourceRoot.sourceSet.id) {
                return refused(snapshot, "java.moveMember.referenceOutsideSourceSet", "Every bound caller must belong to the same available non-generated module source set.")
            }
            val content = contentByPath[referencePath]
                ?: return refused(snapshot, "java.moveMember.referencePathUnavailable", "A caller source file is absent from the snapshot.")
            val offset = contentOffset(content, reference.location.range.start)
                ?: return refused(snapshot, "java.moveMember.referenceShapeUnsupported", "A compiler reference cannot be mapped to an exact source range.")
            val callEdit = invocationEdit(snapshot, callFile.path, offset, selection.ownerType, selection.targetType,
                request.memberBindingKey, content)
                ?: return refused(snapshot, "java.moveMember.referenceShapeUnsupported", "Only direct invocation references through the declaring type or an unqualified call are supported.")
            editsByPath.getOrPut(referencePath) { mutableListOf() } += callEdit
            affectedCallPaths.add(referencePath)
        }
        val workspaceEdit = WorkspaceEdit(editsByPath.map { (path, edits) -> FileEdit.Modify(path, edits) })
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }.getOrElse {
            return refused(snapshot, "java.moveMember.editInvalid", "The member and invocation edits cannot be staged against the captured workspace.")
        }
        val stagedFailure = runCatching {
            adapter.withDiagnosticsOverlay(staged) { overlay ->
                val stagedAnalysis = analyzer.analyze(overlay)
                if (stagedAnalysis.warnings.isNotEmpty()) {
                    val details = stagedAnalysis.warnings.take(3).joinToString("; ") { warning ->
                        "${warning.path}:${warning.line + 1}: ${warning.message}"
                    }
                    return@withDiagnosticsOverlay "java.moveMember.stagedAnalysisIncomplete" to
                        "Staged compiler analysis is incomplete after moving the method: $details"
                }
                val stagedMethod = stagedAnalysis.symbols.singleOrNull {
                    it.kind == JdtJavaSemanticSymbolKind.METHOD &&
                        it.qualifiedName.startsWith("${selection.targetType}#${selection.memberName}(") &&
                        it.path.normalize() == source.path.normalize() && !it.recovered
                } ?: return@withDiagnosticsOverlay "java.moveMember.stagedBindingFailed" to
                    "Staged JDT bindings do not prove the method is declared on the requested target type."
                if (stagedAnalysis.symbols.any {
                        it.kind == JdtJavaSemanticSymbolKind.METHOD &&
                            it.qualifiedName.startsWith("${selection.ownerType}#${selection.memberName}(") &&
                            it.path.normalize() == source.path.normalize()
                    }) {
                    return@withDiagnosticsOverlay "java.moveMember.stagedBindingFailed" to
                        "The staged declaring type still owns the moved method."
                }
                val stagedReferences = adapter.semanticReferences(
                    overlay,
                    SourceLocation(source.path, stagedMethod.sourceRange),
                    includeDeclaration = true,
                    limit = ProtocolLimits.MAX_REFERENCE_RESULTS,
                ) as? JavaJdtReferencesProjection.Available
                    ?: return@withDiagnosticsOverlay "java.moveMember.stagedReferencesFailed" to
                        "Staged compiler-bound method references are unavailable."
                if (!stagedReferences.complete || stagedReferences.truncated || stagedReferences.warningCount != 0 ||
                    stagedReferences.total != references.total) {
                    return@withDiagnosticsOverlay "java.moveMember.stagedReferencesFailed" to
                        "Staged JDT bindings do not preserve the complete invocation family."
                }
                null
            }
        }.getOrElse {
            return refused(snapshot, "java.moveMember.stagedAnalysisUnavailable", "Staged compiler-bound Java analysis is unavailable.")
        }
        if (stagedFailure != null) return refused(snapshot, stagedFailure.first, stagedFailure.second)
        val after = runCatching { adapter.diagnostics(staged) }.getOrElse {
            return refused(snapshot, "java.moveMember.stagedDiagnosticsUnavailable", "Staged Java diagnostics are unavailable.")
        }
        val priorErrors = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticIdentity).toSet()
        val introducedErrors = after.filter { it.severity == Diagnostic.Severity.ERROR }
            .map(::diagnosticIdentity).filterNot(priorErrors::contains)
        if (introducedErrors.isNotEmpty()) {
            return refused(snapshot, "java.moveMember.diagnosticsFailed", "Moving the method introduces ${introducedErrors.size} Java diagnostic error(s).")
        }
        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.97,
            requiresUserApproval = true,
            summary = "Move static method '${selection.memberName}' from ${selection.ownerType} to ${selection.targetType} and update ${affectedCallPaths.size} caller file(s).",
            affectedFiles = workspaceEdit.affectedFiles(),
            workspaceEdit = workspaceEdit,
            diagnosticsBefore = before,
            diagnosticsAfterPreview = after,
            warnings = listOf("External, binary, reflective, and resource-shaped consumers are not rewritten by this bounded member move."),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun invocationEdit(
        snapshot: ProjectSnapshot,
        path: Path,
        offset: Int,
        ownerType: String,
        targetType: String,
        methodBindingKey: String,
        content: String,
    ): TextEdit? {
        var edit: TextEdit? = null
        var found = false
        val resolution = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(snapshot, path) { unit, _ ->
            unit.accept(object : ASTVisitor() {
                override fun visit(node: MethodInvocation): Boolean {
                    if (node.name.startPosition != offset) return true
                    found = true
                    val binding = node.resolveMethodBinding()?.methodDeclaration
                    if (binding?.key != methodBindingKey || node.name.length <= 0) return false
                    val contentRange = if (node.expression == null) {
                        val target = "$targetType."
                        TextEdits.rangeForOffset(content, offset, 0) to target
                    } else {
                        val qualifier = node.expression
                        if (qualifier !is Name || qualifier.resolveTypeBinding()?.qualifiedName != ownerType) return false
                        TextEdits.rangeForOffset(content, qualifier.startPosition, qualifier.length) to targetType
                    }
                    edit = TextEdit(contentRange.first, contentRange.second)
                    return false
                }
            })
            edit
        }
        if (resolution !is JdtBoundCompilationUnitResolution.Available || !found) return null
        return resolution.value
    }

    private fun hierarchyHasMethodNamed(type: org.eclipse.jdt.core.dom.ITypeBinding, name: String): Boolean {
        val seen = mutableSetOf<String>()
        var current: org.eclipse.jdt.core.dom.ITypeBinding? = type
        while (current != null && !current.isRecovered && current.key !in seen) {
            current.key?.let(seen::add)
            if (current.declaredMethods.any { it.name == name }) return true
            current = current.superclass
        }
        return false
    }

    private fun reindentMovedMethod(
        methodText: String,
        sourceMemberIndent: String,
        targetMemberIndent: String,
        lineEnding: String,
    ): String? {
        val lines = methodText.split('\n').map { it.removeSuffix("\r") }
        if (lines.isEmpty() || lines.first().isBlank()) return null
        val shifted = lines.mapIndexed { index, line ->
            when {
                index == 0 -> targetMemberIndent + line
                line.isBlank() -> ""
                line.startsWith(sourceMemberIndent) -> targetMemberIndent + line.removePrefix(sourceMemberIndent)
                else -> return null
            }
        }
        return shifted.joinToString(lineEnding) + lineEnding
    }

    private fun availableSourceRoot(root: BuildSourceRootOwnership): Boolean =
        !root.generated && root.modelStatus == BuildModelStatus.AVAILABLE

    private fun contentOffset(content: String, position: org.refactorkit.core.SourcePosition): Int? =
        runCatching { TextEdits.offsetOf(content, position) }.getOrNull()

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

    private sealed interface BoundSelection {
        data class Available(
            val memberName: String,
            val ownerType: String,
            val targetType: String,
            val sourceRange: IntRange,
            val sourceLineStart: Int,
            val sourceLineEnd: Int,
            val targetLineStart: Int,
            val sourceMemberIndent: String,
            val targetMemberIndent: String,
            val methodText: String,
            val content: String,
            val methodNameRange: IntRange,
        ) : BoundSelection

        data class Refused(val code: String, val message: String) : BoundSelection
    }

    companion object {
        const val OPERATION = "moveMember"
        const val METHOD_KIND = "method"
    }
}
