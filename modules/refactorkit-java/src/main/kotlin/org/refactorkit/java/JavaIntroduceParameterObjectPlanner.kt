package org.refactorkit.java

import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration
import org.eclipse.jdt.core.dom.ClassInstanceCreation
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.Expression
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.NodeFinder
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.SingleVariableDeclaration
import org.eclipse.jdt.core.dom.TypeDeclaration
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
import javax.lang.model.SourceVersion

internal data class JavaIntroduceParameterObjectRequest(
    val path: Path,
    val bindingKey: String,
    val typeName: String,
    val parameters: List<String>,
)

internal class JavaIntroduceParameterObjectPlanner(private val adapter: JavaLanguageAdapter) {
    private val analyzer = JdtJavaSemanticAnalyzer()

    fun preview(snapshot: ProjectSnapshot, request: JavaIntroduceParameterObjectRequest): PatchPlan {
        val source = snapshot.files.singleOrNull { it.path == request.path && it.languageId == "java" }
            ?: return refused(snapshot, "sourceUnavailable", "The selected method has no unique saved Java source.")
        if (JavaGeneratedSourcePolicy.reason(source) != null) {
            return refused(snapshot, "generatedSource", "Generated Java source cannot be changed.")
        }
        if (!SourceVersion.isIdentifier(request.typeName) || SourceVersion.isKeyword(request.typeName) ||
            request.typeName in setOf("_", "var", "yield", "record", "sealed", "permits")) {
            return refused(snapshot, "invalidTypeName", "Use a Java type identifier for the new parameter object.")
        }
        val analysis = analyzer.withBoundCompilationUnit(snapshot, request.path) { unit, _ ->
            planEdits(unit, snapshot, source.content, request)
        }
        val change = when (analysis) {
            is JdtBoundCompilationUnitResolution.Refused ->
                return refused(snapshot, "bindingIncomplete", analysis.message)
            is JdtBoundCompilationUnitResolution.Available -> when (val result = analysis.value) {
                is Refusal -> return refused(snapshot, result.code, result.message)
                is Change -> result
            }
        }
        val edits = change.edits.map { edit ->
            TextEdit(TextEdits.rangeForOffset(source.content, edit.start, edit.length), edit.replacement)
        }
        val stagedSource = source.copy(content = TextEdits.apply(source.content, edits))
        val staged = snapshot.copy(files = snapshot.files.map { if (it == source) stagedSource else it })
        val proof = analyzer.withBoundCompilationUnit(staged, request.path) { unit, _ ->
            validates(unit, change, request)
        }
        if (proof !is JdtBoundCompilationUnitResolution.Available || !proof.value) {
            return refused(snapshot, "stagedValidationFailed", "The staged signature, constructor, or call could not be validated.")
        }
        val (before, after) = runCatching { adapter.diagnostics(snapshot) to adapter.diagnostics(staged) }.getOrElse {
            return refused(snapshot, "diagnosticsUnavailable", "Java diagnostics are unavailable for the staged change.")
        }
        val baseline = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticIdentity).toSet()
        if (after.any { it.severity == Diagnostic.Severity.ERROR && diagnosticIdentity(it) !in baseline }) {
            return refused(snapshot, "stagedValidationFailed", "The parameter object introduces a Java diagnostic error.")
        }
        return PatchPlan(
            operation = OPERATION, status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash,
            confidence = 0.95, requiresUserApproval = true,
            summary = "Group ${request.parameters.joinToString(" and ")} in ${request.typeName} and update the bound call.",
            affectedFiles = setOf(request.path), workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(request.path, edits))),
            diagnosticsBefore = before, diagnosticsAfterPreview = after,
            warnings = listOf("The call allocates a parameter object and the private reflective signature changes."),
            riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun planEdits(
        unit: CompilationUnit,
        snapshot: ProjectSnapshot,
        content: String,
        request: JavaIntroduceParameterObjectRequest,
    ): Analysis {
        if (unit.problems.any { it.isError }) {
            return Refusal("bindingIncomplete", "The saved compilation unit has Java binding errors.")
        }
        val method = unit.findDeclaringNode(request.bindingKey) as? MethodDeclaration ?: return unsupportedMethod()
        val owner = method.parent as? TypeDeclaration ?: return unsupportedMethod()
        val binding = method.resolveBinding() ?: return unsupportedMethod()
        val allowedModifiers = Modifier.PRIVATE or Modifier.FINAL or Modifier.STATIC
        if (binding.isRecovered || owner.isInterface || owner.parent !== unit || method.isConstructor || method.body == null ||
            !Modifier.isPrivate(method.modifiers) || method.modifiers and allowedModifiers.inv() != 0 ||
            method.modifiers().any { it !is Modifier } || method.typeParameters().isNotEmpty() ||
            method.javadoc != null || method.isVarargs) return unsupportedMethod()

        val parameters = method.parameters().filterIsInstance<SingleVariableDeclaration>()
        val index = parameters.indexOfFirst { it.name.identifier == request.parameters.firstOrNull() }
        val pair = parameters.drop(index.coerceAtLeast(0)).take(2)
        if (index < 0 || pair.size != 2 || pair.map { it.name.identifier } != request.parameters ||
            pair.any { it.modifiers().isNotEmpty() || !it.type.isPrimitiveType || it.extraDimensions().isNotEmpty() }) {
            return Refusal("parameterGroupUnsupported", "Select two adjacent unmodified primitive parameters in declaration order.")
        }
        val parameterBindings = pair.map { parameter ->
            parameter.resolveBinding()?.variableDeclaration?.takeUnless { it.isRecovered }
                ?: return Refusal("bindingIncomplete", "A grouped parameter has no complete Java binding.")
        }
        val declarationStart = pair.first().startPosition
        val declarationEnd = end(pair.last())
        if (unit.commentList.orEmpty().filterIsInstance<ASTNode>().any { it.startPosition in declarationStart until declarationEnd }) {
            return Refusal("parameterGroupUnsupported", "Comments inside the grouped declaration must be kept outside the replaced range.")
        }
        val names = inspectNames(unit, method, pair, parameterBindings, request.typeName)
        val binaryName = "${owner.name.identifier}\$${request.typeName}"
        if (names.collision || snapshot.files.any { it.languageId == "java" && binaryName in it.content }) {
            return Refusal("nameConflict", "The new object type or generated parameter name conflicts with existing source.")
        }
        val use = names.methodUses.singleOrNull()
        val call = use?.parent as? MethodInvocation
        val caller = call?.let(::enclosingMethod)
        if (names.otherMethodBinding || call == null || call.name !== use || call.expression != null ||
            call.typeArguments().isNotEmpty() || caller == null || caller === method || caller.parent !== owner ||
            call.arguments().size != parameters.size) {
            return Refusal("callSiteUnsupported", "The private method needs one unqualified bound call in another method of the same class.")
        }

        val arguments = call.arguments().filterIsInstance<Expression>()
        val first = arguments[index].startPosition
        val last = end(arguments[index + 1])
        val wrap = SourceEdit(first, last - first, "new ${request.typeName}(${content.substring(first, last)})")
        val newline = if ("\r\n" in content) "\r\n" else "\n"
        val edits = listOf(
            SourceEdit(declarationStart, declarationEnd - declarationStart, "${request.typeName} $PARAMETER_NAME"),
            wrap,
            SourceEdit(end(owner) - 1, 0, renderType(request.typeName, pair, newline)),
        ) + names.parameterUses.map { useSite ->
            SourceEdit(useSite.startPosition, useSite.length, "$PARAMETER_NAME.${useSite.identifier}")
        }
        val stagedStart = wrap.start + edits.filter { it.start < wrap.start }.sumOf { it.replacement.length - it.length }
        return Change(edits, wrap.replacement.length, stagedStart, index, binding.parameterTypes.map { it.key },
            binding.returnType.key, owner.resolveBinding()?.qualifiedName ?: return unsupportedMethod(), method.name.identifier)
    }

    private fun inspectNames(
        unit: CompilationUnit,
        method: MethodDeclaration,
        pair: List<SingleVariableDeclaration>,
        bindings: List<IVariableBinding>,
        typeName: String,
    ): Names {
        val selected = method.resolveBinding().methodDeclaration.key
        val methodUses = mutableListOf<SimpleName>()
        val parameterUses = mutableListOf<SimpleName>()
        var collision = false
        var otherMethodBinding = false
        unit.accept(object : ASTVisitor(true) {
            override fun visit(node: SimpleName): Boolean {
                if (node.identifier == typeName || node.identifier == PARAMETER_NAME) collision = true
                if (node.identifier == method.name.identifier && node !== method.name) {
                    val binding = node.resolveBinding() as? IMethodBinding
                    if (binding?.methodDeclaration?.key == selected) methodUses += node
                    else if (binding != null) otherMethodBinding = true
                }
                if (pair.any { it.name.identifier == node.identifier && it.name !== node }) {
                    val binding = (node.resolveBinding() as? IVariableBinding)?.variableDeclaration
                    if (bindings.any { it.key == binding?.key }) parameterUses += node
                }
                return true
            }
        })
        return Names(methodUses, parameterUses, collision, otherMethodBinding)
    }

    private fun enclosingMethod(call: MethodInvocation): MethodDeclaration? =
        generateSequence(call.parent) { it.parent }.firstOrNull {
            it is MethodDeclaration || it is AnonymousClassDeclaration || it is AbstractTypeDeclaration
        } as? MethodDeclaration

    private fun validates(unit: CompilationUnit, change: Change, request: JavaIntroduceParameterObjectRequest): Boolean {
        if (unit.problems.any { it.isError }) return false
        val creation = NodeFinder.perform(unit, change.stagedStart, change.creationLength) as? ClassInstanceCreation ?: return false
        val call = creation.parent as? MethodInvocation ?: return false
        val invocation = call.resolveMethodBinding()?.takeUnless { it.isRecovered }?.methodDeclaration ?: return false
        val constructor = creation.resolveConstructorBinding()?.takeUnless { it.isRecovered } ?: return false
        val nested = constructor.declaringClass
        val expected = change.parameterTypes.toMutableList()
        val grouped = expected.subList(change.parameterIndex, change.parameterIndex + 2).toList()
        expected.removeAt(change.parameterIndex + 1)
        expected[change.parameterIndex] = nested.key
        return call.arguments().getOrNull(change.parameterIndex) === creation &&
            nested.name == request.typeName && nested.declaringClass?.qualifiedName == change.owner &&
            constructor.parameterTypes.map { it.key } == grouped &&
            invocation.declaringClass.qualifiedName == change.owner && invocation.name == change.method &&
            invocation.parameterTypes.map { it.key } == expected && invocation.returnType.key == change.returnType
    }

    private fun renderType(name: String, pair: List<SingleVariableDeclaration>, newline: String): String {
        val fields = pair.map { it.type.toString() to it.name.identifier }
        return buildList {
            add("")
            add("    private static final class $name {")
            fields.forEach { (type, field) -> add("        private final $type $field;") }
            add("")
            add("        private $name(${fields.joinToString(", ") { (type, field) -> "$type $field" }}) {")
            fields.forEach { (_, field) -> add("            this.$field = $field;") }
            add("        }")
            add("    }")
            add("")
        }.joinToString(newline)
    }

    private fun unsupportedMethod() =
        Refusal("methodUnsupported", "Select an unannotated private ordinary method in a top-level class.")

    private fun end(node: ASTNode) = node.startPosition + node.length

    private fun diagnosticIdentity(diagnostic: Diagnostic) = Triple(diagnostic.code, diagnostic.message, diagnostic.location?.path)

    private fun refused(snapshot: ProjectSnapshot, code: String, message: String) = PatchPlan(
        operation = OPERATION, status = PatchStatus.REFUSED, snapshotHash = snapshot.hash, confidence = 0.0,
        requiresUserApproval = false, summary = message, affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(),
        warnings = listOf(message), riskLevel = RiskLevel.HIGH, refusalCode = "java.$OPERATION.$code",
    )

    private sealed interface Analysis
    private data class Refusal(val code: String, val message: String) : Analysis
    private data class Change(
        val edits: List<SourceEdit>, val creationLength: Int, val stagedStart: Int, val parameterIndex: Int,
        val parameterTypes: List<String>, val returnType: String, val owner: String, val method: String,
    ) : Analysis
    private data class SourceEdit(val start: Int, val length: Int, val replacement: String)
    private data class Names(
        val methodUses: List<SimpleName>, val parameterUses: List<SimpleName>,
        val collision: Boolean, val otherMethodBinding: Boolean,
    )

    private companion object {
        const val OPERATION = "introduceParameterObject"
        const val PARAMETER_NAME = "parameterObject"
    }
}
