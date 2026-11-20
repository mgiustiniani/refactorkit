package org.refactorkit.java

import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.Assignment
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.FieldAccess
import org.eclipse.jdt.core.dom.FieldDeclaration
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.Javadoc
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.NumberLiteral
import org.eclipse.jdt.core.dom.PostfixExpression
import org.eclipse.jdt.core.dom.PrefixExpression
import org.eclipse.jdt.core.dom.QualifiedName
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.SuperFieldAccess
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

data class JavaInlineConstantRequest(
    val file: Path,
    val name: String,
)

/**
 * Inlines one private static final primitive-int literal using only JDT-bound
 * reads from its declaring compilation unit. Unsupported declarations,
 * uncertain bindings, writes, and comment-bearing declaration layouts fail closed.
 */
class JavaInlineConstantPlanner(private val adapter: JavaLanguageAdapter) {
    fun preview(snapshot: ProjectSnapshot, request: JavaInlineConstantRequest): PatchPlan {
        if (!request.name.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) {
            return refused(snapshot, "java.inlineConstant.invalidRequest", "A Java field name is required.")
        }
        val file = snapshot.files.singleOrNull { it.path.normalize() == request.file.normalize() }
            ?: return refused(snapshot, "java.inlineConstant.fileUnavailable", "The selected Java file is absent or ambiguous in the snapshot.")
        if (file.languageId != "java") {
            return refused(snapshot, "java.inlineConstant.languageUnsupported", "Inline constant currently supports Java source only.")
        }
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "java.inlineConstant.generatedSource", "Generated Java source cannot be rewritten: $reason")
        }
        val before = runCatching { adapter.diagnostics(snapshot) }.getOrElse {
            return refused(snapshot, "java.inlineConstant.diagnosticsUnavailable", "Java diagnostics are unavailable for the selected snapshot.")
        }
        val resolution = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(snapshot, file.path) { unit, _ ->
            previewBoundField(snapshot, file.path, request.name, before, unit)
        }
        return when (resolution) {
            is JdtBoundCompilationUnitResolution.Available -> resolution.value
            is JdtBoundCompilationUnitResolution.Refused -> refused(
                snapshot,
                "java.inlineConstant.analysisUnavailable",
                "A complete supported JDT source-set environment is unavailable for this field.",
            )
        }
    }

    private fun previewBoundField(
        snapshot: ProjectSnapshot,
        filePath: Path,
        name: String,
        before: List<Diagnostic>,
        unit: CompilationUnit,
    ): PatchPlan {
        val file = snapshot.files.single { it.path.normalize() == filePath.normalize() }
        val content = file.content
        val declarations = mutableListOf<VariableDeclarationFragment>()
        unit.accept(object : ASTVisitor() {
            override fun visit(node: VariableDeclarationFragment): Boolean {
                if (node.name.identifier == name && node.parent is FieldDeclaration) declarations += node
                return true
            }
        })
        val fragment = declarations.singleOrNull()
            ?: return refused(snapshot, "java.inlineConstant.declarationAmbiguous", "The field name must identify exactly one field declaration in the selected compilation unit.")
        val field = fragment.parent as FieldDeclaration
        val owner = field.parent as? TypeDeclaration
            ?: return refused(snapshot, "java.inlineConstant.ownerUnsupported", "Only a field declared by an ordinary top-level class is supported.")
        if (owner.parent !== unit || owner.isInterface || field.fragments().size != 1 ||
            field.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0 ||
            owner.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0) {
            return refused(snapshot, "java.inlineConstant.ownerUnsupported", "Only one well-formed field fragment on an ordinary top-level class is supported.")
        }
        val binding = fragment.resolveBinding()
            ?: return refused(snapshot, "java.inlineConstant.bindingIncomplete", "The field declaration has no JDT variable binding.")
        if (binding.isRecovered || binding.variableDeclaration.key.isNullOrBlank() || !binding.isField) {
            return refused(snapshot, "java.inlineConstant.bindingIncomplete", "The field declaration does not have a complete JDT field binding.")
        }
        if (!Modifier.isPrivate(binding.modifiers) || !Modifier.isStatic(binding.modifiers) || !Modifier.isFinal(binding.modifiers) ||
            binding.type.isRecovered || !binding.type.isPrimitive || binding.type.name != "int") {
            return refused(snapshot, "java.inlineConstant.fieldNotSupported", "Only a private static final primitive-int field is supported.")
        }
        val initializer = fragment.initializer as? NumberLiteral
            ?: return refused(snapshot, "java.inlineConstant.initializerUnsupported", "Only a direct primitive-int numeric literal initializer is supported.")
        if (initializer.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0 ||
            initializer.resolveTypeBinding()?.let { it.isRecovered || !it.isPrimitive || it.name != "int" } != false ||
            initializer.resolveConstantExpressionValue() !is Int) {
            return refused(snapshot, "java.inlineConstant.initializerUnsupported", "The initializer must be a complete binding-resolved primitive-int literal.")
        }
        val declarationLine = removableDeclarationLine(content, field)
            ?: return refused(snapshot, "java.inlineConstant.declarationLayoutUnsupported", "The field declaration must occupy a comment-free line so its removal preserves surrounding source.")

        val declarationKey = binding.variableDeclaration.key
        val references = mutableListOf<SimpleName>()
        var incompleteBinding = false
        var hasWrite = false
        unit.accept(object : ASTVisitor() {
            override fun visit(node: SimpleName): Boolean {
                if (node.identifier != name || node === fragment.name || isInsideJavadoc(node)) return true
                val resolved = node.resolveBinding()
                if (resolved == null || resolved.isRecovered || resolved.key.isNullOrBlank()) {
                    incompleteBinding = true
                    return true
                }
                val variable = resolved as? IVariableBinding ?: return true
                if (variable.variableDeclaration.key != declarationKey) return true
                if (isWriteReference(node)) hasWrite = true else references += node
                return true
            }
        })
        if (incompleteBinding) {
            return refused(snapshot, "java.inlineConstant.bindingIncomplete", "Every same-named code occurrence must have a complete JDT binding.")
        }
        if (hasWrite) {
            return refused(snapshot, "java.inlineConstant.writeReferenceUnsupported", "A binding-matched write to the selected field prevents safe inlining.")
        }
        if (references.isEmpty()) {
            return refused(snapshot, "java.inlineConstant.noReads", "The selected constant has no binding-matched reads to inline.")
        }

        val literalText = content.substring(initializer.startPosition, initializer.startPosition + initializer.length)
        val replacementRanges = references.map(::fieldReadExpression).distinctBy { it.startPosition to it.length }
        val edits = buildList {
            replacementRanges.forEach { reference ->
                add(TextEdit(TextEdits.rangeForOffset(content, reference.startPosition, reference.length), literalText))
            }
            add(TextEdit(TextEdits.rangeForOffset(content, declarationLine.first, declarationLine.second - declarationLine.first), ""))
        }
        val stagedContent = runCatching { TextEdits.apply(content, edits) }.getOrElse {
            return refused(snapshot, "java.inlineConstant.editInvalid", "The binding-matched edits overlap or do not fit the saved source.")
        }
        val staged = snapshot.copy(files = snapshot.files.map { source ->
            if (source.path.normalize() == file.path.normalize()) source.copy(content = stagedContent) else source
        })
        val stagedProof = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(staged, file.path) { stagedUnit, _ ->
            var targetRemains = false
            stagedUnit.accept(object : ASTVisitor() {
                override fun visit(node: SimpleName): Boolean {
                    if (node.identifier == name && node.resolveBinding() is IVariableBinding) {
                        val stagedBinding = node.resolveBinding() as IVariableBinding
                        if (stagedBinding.variableDeclaration.key == declarationKey) targetRemains = true
                    }
                    return true
                }
            })
            !targetRemains
        }
        if (stagedProof !is JdtBoundCompilationUnitResolution.Available || stagedProof.value != true) {
            return refused(snapshot, "java.inlineConstant.stagedValidationFailed", "Staged JDT bindings did not prove that the selected field and its bound reads were removed.")
        }
        val after = adapter.diagnostics(staged)
        val baselineErrors = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticIdentity).toSet()
        val introducedErrors = after.filter { it.severity == Diagnostic.Severity.ERROR }
            .map(::diagnosticIdentity).filterNot { it in baselineErrors }
        if (introducedErrors.isNotEmpty()) {
            return refused(snapshot, "java.inlineConstant.diagnosticsFailed", "Inlining introduces ${introducedErrors.size} diagnostic error(s).")
        }
        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.95,
            requiresUserApproval = true,
            summary = "Inline ${replacementRanges.size} binding-matched read(s) of private constant '$name' using the primitive-int literal $literalText.",
            affectedFiles = setOf(file.path),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(file.path, edits))),
            diagnosticsBefore = before,
            diagnosticsAfterPreview = after,
            warnings = listOf("Reflection or external binary consumers can observe removal of a private field."),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun fieldReadExpression(name: SimpleName): ASTNode = when (val parent = name.parent) {
        is QualifiedName -> if (parent.name === name) parent else name
        is FieldAccess -> if (parent.name === name) parent else name
        is SuperFieldAccess -> if (parent.name === name) parent else name
        else -> name
    }

    private fun removableDeclarationLine(content: String, declaration: ASTNode): Pair<Int, Int>? {
        val start = declaration.startPosition
        val end = start + declaration.length
        val lineStart = content.lastIndexOf('\n', (start - 1).coerceAtLeast(0)) + 1
        val lineEnd = content.indexOf('\n', end).let { if (it < 0) content.length else it + 1 }
        val lineContentEnd = if (lineEnd > 0 && content.getOrNull(lineEnd - 1) == '\n') lineEnd - 1 else lineEnd
        val sourceLine = content.substring(lineStart, lineContentEnd)
        if (sourceLine.substring(0, start - lineStart).any { it != ' ' && it != '\t' }) return null
        if (sourceLine.substring(end - lineStart).any { it != ' ' && it != '\t' && it != '\r' }) return null
        val declarationText = content.substring(start, end)
        if ("//" in declarationText || "/*" in declarationText || "*/" in declarationText) return null
        return lineStart to lineEnd
    }

    private fun isInsideJavadoc(name: SimpleName): Boolean {
        var node: ASTNode? = name
        while (node != null) {
            if (node is Javadoc) return true
            node = node.parent
        }
        return false
    }

    private fun isWriteReference(name: SimpleName): Boolean {
        var node: ASTNode? = name
        while (node != null) {
            val parent = node.parent ?: return false
            if (parent is Assignment && isDescendantOrSelf(name, parent.leftHandSide)) return true
            if (parent is PrefixExpression &&
                (parent.operator == PrefixExpression.Operator.INCREMENT || parent.operator == PrefixExpression.Operator.DECREMENT) &&
                isDescendantOrSelf(name, parent.operand)) return true
            if (parent is PostfixExpression && isDescendantOrSelf(name, parent.operand)) return true
            if (parent is org.eclipse.jdt.core.dom.MethodDeclaration) return false
            node = parent
        }
        return false
    }

    private fun isDescendantOrSelf(node: ASTNode, ancestor: ASTNode): Boolean {
        var current: ASTNode? = node
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent
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
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.JDT_BINDING,
        refusalCode = code,
    )

    companion object {
        const val OPERATION = "inlineConstant"
    }
}
