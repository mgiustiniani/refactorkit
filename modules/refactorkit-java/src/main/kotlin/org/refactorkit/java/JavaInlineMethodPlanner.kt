package org.refactorkit.java

import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.Expression
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.InfixExpression
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.NodeFinder
import org.eclipse.jdt.core.dom.NumberLiteral
import org.eclipse.jdt.core.dom.ParenthesizedExpression
import org.eclipse.jdt.core.dom.PrefixExpression
import org.eclipse.jdt.core.dom.ReturnStatement
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceFile
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/** Inlines one bound, unqualified call to a private static integer helper in its declaring class. */
internal class JavaInlineMethodPlanner(private val adapter: JavaLanguageAdapter) {
    private val analyzer = JdtJavaSemanticAnalyzer()

    fun preview(snapshot: ProjectSnapshot, path: Path, bindingKey: String): PatchPlan {
        val source = snapshot.files.singleOrNull { it.path == path && it.languageId == "java" }
            ?: return refused(snapshot, "The selected method has no unique saved Java source.")
        if (JavaGeneratedSourcePolicy.reason(source) != null) return refused(snapshot, "Generated Java methods cannot be inlined.")
        val result = analyzer.withBoundCompilationUnit(snapshot, path) { unit, _ -> edit(unit, source, bindingKey) }
        val change = (result as? JdtBoundCompilationUnitResolution.Available)?.value ?: return refused(snapshot,
            "Inline Method requires one unqualified return call to a private static zero-argument int helper with a constant expression in the same class.")
        val stagedText = TextEdits.apply(source.content, change.edits)
        val staged = snapshot.copy(files = snapshot.files.map { if (it == source) source.copy(content = stagedText) else it })
        val proof = analyzer.withBoundCompilationUnit(staged, path) { unit, _ ->
            val replacement = NodeFinder.perform(unit, change.stagedOffset, change.replacement.length) as? ParenthesizedExpression
            unit.problems.none { it.isError } && replacement != null && replacement.resolveConstantExpressionValue() == change.value &&
                replacement.parent is ReturnStatement
        }
        if (proof !is JdtBoundCompilationUnitResolution.Available || proof.value != true) {
            return refused(snapshot, "Staged Java bindings did not preserve the returned integer expression.")
        }
        val (before, after) = runCatching { adapter.diagnostics(snapshot) to adapter.diagnostics(staged) }.getOrElse {
            return refused(snapshot, "Java diagnostics are unavailable for the original and staged helper.")
        }
        val existingErrors = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticIdentity).toSet()
        if (after.any { it.severity == Diagnostic.Severity.ERROR && diagnosticIdentity(it) !in existingErrors }) {
            return refused(snapshot, "Inlining this helper introduces a Java diagnostic error.")
        }
        return PatchPlan(
            operation = "inlineMethod", status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash,
            confidence = 0.95, requiresUserApproval = true,
            summary = "Inline the bound call to '${change.name}' and remove its private helper.",
            affectedFiles = setOf(path), workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(path, change.edits))),
            diagnosticsBefore = before, diagnosticsAfterPreview = after,
            warnings = listOf("Reflection can observe removal of the private method."),
            riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun edit(unit: CompilationUnit, source: SourceFile, bindingKey: String): Change? {
        if (unit.problems.any { it.isError }) return null
        val methods = mutableListOf<MethodDeclaration>()
        unit.accept(object : ASTVisitor() {
            override fun visit(node: MethodDeclaration): Boolean {
                if (node.resolveBinding()?.takeUnless { it.isRecovered }?.methodDeclaration?.key == bindingKey) methods += node
                return true
            }
        })
        val method = methods.singleOrNull() ?: return null
        val owner = method.parent as? TypeDeclaration ?: return null
        if (owner.isInterface || owner.parent !== unit || method.modifiers != (Modifier.PRIVATE or Modifier.STATIC) ||
            method.modifiers().size != 2 || method.javadoc != null || method.parameters().isNotEmpty() ||
            method.typeParameters().isNotEmpty() || method.thrownExceptionTypes().isNotEmpty()) return null
        val binding = method.resolveBinding() ?: return null
        if (!binding.returnType.isPrimitive || binding.returnType.name != "int") return null
        val returned = method.body?.statements()?.singleOrNull() as? ReturnStatement ?: return null
        val expression = returned.expression ?: return null
        val constant = expression.resolveConstantExpressionValue() as? Int ?: return null
        if (!isIndependentIntegerExpression(expression)) return null

        val use = boundUses(unit, method, bindingKey).singleOrNull() ?: return null
        val call = use.parent as? MethodInvocation ?: return null
        val callReturn = call.parent as? ReturnStatement ?: return null
        val caller = callReturn.parent?.parent as? MethodDeclaration ?: return null
        if (call.name !== use || call.expression != null || call.arguments().isNotEmpty() || call.typeArguments().isNotEmpty() ||
            callReturn.expression !== call || caller === method || caller.parent !== owner || caller.body !== callReturn.parent) return null
        val content = source.content
        val removal = removableLines(content, method) ?: return null
        if (unit.commentList.orEmpty().filterIsInstance<ASTNode>().any { comment ->
                comment.startPosition in removal || comment.startPosition in call.startPosition until (call.startPosition + call.length)
            }) return null
        val replacement = "(${content.substring(expression.startPosition, expression.startPosition + expression.length)})"
        val removalLength = removal.last - removal.first + 1
        val offset = call.startPosition - if (removal.first < call.startPosition) removalLength else 0
        return Change(method.name.identifier, constant, offset, replacement, listOf(
            TextEdit(TextEdits.rangeForOffset(content, removal.first, removalLength), ""),
            TextEdit(TextEdits.rangeForOffset(content, call.startPosition, call.length), replacement),
        ))
    }

    private fun boundUses(unit: CompilationUnit, method: MethodDeclaration, bindingKey: String): List<SimpleName> {
        val references = mutableListOf<SimpleName>()
        unit.accept(object : ASTVisitor(true) {
            override fun visit(node: SimpleName): Boolean {
                if (node !== method.name && node.identifier == method.name.identifier) {
                    val binding = node.resolveBinding() as? IMethodBinding
                    if (binding != null && !binding.isRecovered && binding.methodDeclaration.key == bindingKey) references += node
                }
                return true
            }
        })
        return references
    }

    private fun isIndependentIntegerExpression(expression: Expression): Boolean {
        var independent = true
        expression.accept(object : ASTVisitor() {
            override fun preVisit2(node: ASTNode): Boolean {
                val allowed = node is NumberLiteral || node is InfixExpression || node is ParenthesizedExpression || node is PrefixExpression
                independent = independent && allowed
                return allowed
            }
        })
        return independent
    }

    private fun removableLines(content: String, method: MethodDeclaration): IntRange? {
        val end = method.startPosition + method.length
        val first = content.lastIndexOf('\n', method.startPosition - 1) + 1
        val next = content.indexOf('\n', end).let { if (it < 0) content.length else it + 1 }
        if (!content.substring(first, method.startPosition).isBlank() || !content.substring(end, next).isBlank()) return null
        return first until next
    }

    private fun diagnosticIdentity(diagnostic: Diagnostic) = Triple(diagnostic.code, diagnostic.message, diagnostic.location?.path)

    private fun refused(snapshot: ProjectSnapshot, message: String) = PatchPlan(
        operation = "inlineMethod", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash, confidence = 0.0,
        requiresUserApproval = false, summary = message, affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(),
        warnings = listOf(message), riskLevel = RiskLevel.HIGH, refusalCode = "java.inlineMethod.unsupported",
    )

    private data class Change(val name: String, val value: Int, val stagedOffset: Int, val replacement: String, val edits: List<TextEdit>)
}
