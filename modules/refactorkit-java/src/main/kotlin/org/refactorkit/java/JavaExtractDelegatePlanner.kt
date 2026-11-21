package org.refactorkit.java

import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration
import org.eclipse.jdt.core.dom.Block
import org.eclipse.jdt.core.dom.BooleanLiteral
import org.eclipse.jdt.core.dom.CastExpression
import org.eclipse.jdt.core.dom.CharacterLiteral
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.ConditionalExpression
import org.eclipse.jdt.core.dom.FieldAccess
import org.eclipse.jdt.core.dom.FieldDeclaration
import org.eclipse.jdt.core.dom.IBinding
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.InfixExpression
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.NumberLiteral
import org.eclipse.jdt.core.dom.ParenthesizedExpression
import org.eclipse.jdt.core.dom.PrefixExpression
import org.eclipse.jdt.core.dom.PrimitiveType
import org.eclipse.jdt.core.dom.QualifiedName
import org.eclipse.jdt.core.dom.ReturnStatement
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.SingleVariableDeclaration
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
import javax.lang.model.SourceVersion

internal data class JavaExtractDelegateRequest(
    val path: Path,
    val bindingKey: String,
    val typeName: String,
    val members: List<String>,
)

internal class JavaExtractDelegatePlanner(private val adapter: JavaLanguageAdapter) {
    private val analyzer = JdtJavaSemanticAnalyzer()

    fun preview(snapshot: ProjectSnapshot, request: JavaExtractDelegateRequest): PatchPlan {
        val source = snapshot.files.singleOrNull { it.path == request.path && it.languageId == "java" }
            ?: return refused(snapshot, "sourceUnavailable", "The selected method has no unique saved Java source.")
        if (JavaGeneratedSourcePolicy.reason(source) != null) {
            return refused(snapshot, "generatedSource", "Generated Java source cannot be changed.")
        }
        if (!SourceVersion.isIdentifier(request.typeName) || SourceVersion.isKeyword(request.typeName) ||
            request.typeName in setOf("_", "var", "yield", "record", "sealed", "permits")) {
            return refused(snapshot, "invalidTypeName", "Use a Java type identifier for the new delegate.")
        }
        if (request.members.size !in 2..4 || request.members.toSet().size != request.members.size) {
            return refused(snapshot, "memberGroupUnsupported", "Choose two to four distinct member names.")
        }
        val analysis = analyzer.withBoundCompilationUnit(snapshot, request.path) { unit, _ ->
            analyze(unit, snapshot, source.content, request)
        }
        val change = when (analysis) {
            is JdtBoundCompilationUnitResolution.Refused -> return refused(snapshot, "bindingIncomplete", analysis.message)
            is JdtBoundCompilationUnitResolution.Available -> when (val result = analysis.value) {
                is Refusal -> return refused(snapshot, result.code, result.message)
                is Change -> result
            }
        }
        val updated = source.copy(content = TextEdits.apply(source.content, change.edits))
        val staged = snapshot.copy(files = snapshot.files.map { if (it == source) updated else it })
        val proof = analyzer.withBoundCompilationUnit(staged, request.path) { unit, _ -> validate(unit, request, change) }
        if (proof !is JdtBoundCompilationUnitResolution.Available || !proof.value) {
            return refused(snapshot, "stagedValidationFailed", "The staged delegate or its bound references could not be verified.")
        }
        val (before, after) = runCatching { adapter.diagnostics(snapshot) to adapter.diagnostics(staged) }.getOrElse {
            return refused(snapshot, "diagnosticsUnavailable", "Java diagnostics are unavailable for the staged delegate.")
        }
        val baseline = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticIdentity).toSet()
        if (after.any { it.severity == Diagnostic.Severity.ERROR && diagnosticIdentity(it) !in baseline }) {
            return refused(snapshot, "stagedValidationFailed", "The delegate introduces a Java diagnostic error.")
        }
        return PatchPlan(
            operation = OPERATION, status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash, confidence = 0.95,
            requiresUserApproval = true, summary = "Move ${request.members.joinToString(", ")} into ${request.typeName}.",
            affectedFiles = setOf(request.path), workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(request.path, change.edits))),
            diagnosticsBefore = before, diagnosticsAfterPreview = after,
            warnings = listOf("The declaring class of private members changes. Reflection, stack traces and class-file identity may change."),
            riskLevel = RiskLevel.HIGH, evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun analyze(
        unit: CompilationUnit,
        snapshot: ProjectSnapshot,
        content: String,
        request: JavaExtractDelegateRequest,
    ): Analysis {
        if (unit.problems.any { it.isError }) {
            return Refusal("bindingIncomplete", "The saved compilation unit has Java binding errors.")
        }
        val selected = unit.findDeclaringNode(request.bindingKey) as? MethodDeclaration ?: return unsupportedGroup()
        val owner = selected.parent as? TypeDeclaration ?: return unsupportedGroup()
        val ownerKey = owner.resolveBinding()?.takeUnless { it.isRecovered }?.key ?: return unsupportedGroup()
        if (owner.isInterface || owner.parent !== unit || selected.name.identifier !in request.members ||
            content.getOrNull(owner.startPosition + owner.length - 1) != '}') return unsupportedGroup()
        val binaryName = "${owner.name.identifier}\$${request.typeName}"
        if (snapshot.files.any { it.languageId == "java" && (binaryName in it.content || it.path.fileName.toString() == "$binaryName.java") }) {
            return Refusal("nameConflict", "The delegate binary name already occurs in workspace source.")
        }

        val declarations = declarations(owner).filter { it.name.identifier in request.members }
        if (declarations.map { it.name.identifier }.groupingBy { it }.eachCount() != request.members.associateWith { 1 }) {
            return unsupportedGroup()
        }
        val methods = declarations.mapNotNull { it.node as? MethodDeclaration }
        if (methods.size < 2 || declarations.size - methods.size > 1 || methods.any { !supportedMethod(it) }) {
            return unsupportedGroup()
        }
        val field = declarations.singleOrNull { it.node is FieldDeclaration }
        if (field != null && !supportedConstant(field)) {
            return Refusal("initializationUnsupported", "The selected field must be a private static final int with a literal initializer.")
        }
        val members = declarations.map { member ->
            Member(member, facts(member) ?: return Refusal("bindingIncomplete", "A selected member has an incomplete Java binding."))
        }
        val byKey = members.associateBy { it.facts.key }
        val internal = mutableMapOf<Use, Int>()
        for (method in methods) {
            val source = memberKey(method.resolveBinding())
                ?: return Refusal("bindingIncomplete", "A selected method has an incomplete Java binding.")
            val problem = inspectBody(method, byKey.keys) { key -> internal.merge(Use(source, key), 1, Int::plus) }
            if (problem != null) return problem
        }
        if (!connected(byKey.keys, internal.keys)) {
            return Refusal("dependencyUnsupported", "Every selected declaration must be connected by a bound reference.")
        }

        val callSites = mutableListOf<SimpleName>()
        val external = mutableMapOf<String, Int>()
        var problem: Refusal? = null
        unit.accept(object : ASTVisitor(true) {
            override fun visit(name: SimpleName): Boolean {
                if (name.identifier == request.typeName) {
                    problem = problem ?: Refusal("nameConflict", "The delegate type name already occurs in this compilation unit.")
                }
                val key = memberKey(name.resolveBinding()) ?: return true
                if (name.isDeclaration || key !in byKey || members.any { isInside(name, it.declaration.node) }) return true

                val parent = name.parent
                val supportedUse = if (byKey.getValue(key).declaration.node is MethodDeclaration) {
                    parent is MethodInvocation && parent.name === name && parent.expression == null && parent.typeArguments().isEmpty()
                } else {
                    parent !is QualifiedName && parent !is FieldAccess
                }
                if (!insideOwnerMethodBody(name, owner)) {
                    problem = problem ?: Refusal("initializationUnsupported",
                        "A reference to the group occurs outside an ordinary method body in the source class.")
                } else if (!supportedUse) {
                    problem = problem ?: Refusal("dependencyUnsupported", "The group has a qualified or unsupported external reference.")
                } else {
                    callSites += name
                    external.merge(key, 1, Int::plus)
                }
                return true
            }
        })
        problem?.let { return it }
        if (callSites.isEmpty()) return unsupportedGroup()

        val sourceOrder = members.sortedBy { it.declaration.node.startPosition }
        val edits = sourceOrder.map { removeDeclaration(content, it.declaration.node) } +
            callSites.map { name -> edit(content, name.startPosition, 0, "${request.typeName}.") } +
            insertDelegate(content, owner, sourceOrder, request.typeName)
        val namesByKey = byKey.mapValues { it.value.declaration.name.identifier }
        return Change(
            edits, ownerKey, members.associate { it.declaration.name.identifier to it.facts.signature },
            internal.mapKeys { (use, _) -> Use(namesByKey.getValue(use.from), namesByKey.getValue(use.to)) },
            external.mapKeys { (key, _) -> namesByKey.getValue(key) },
        )
    }

    private fun inspectBody(method: MethodDeclaration, group: Set<String>, recordUse: (String) -> Unit): Refusal? {
        val body = method.body
        if (body.statements().singleOrNull() !is ReturnStatement) return unsupportedGroup()
        val parameters = method.parameters().filterIsInstance<SingleVariableDeclaration>()
            .mapNotNull { memberKey(it.resolveBinding()) }.toSet()
        if (parameters.size != method.parameters().size) {
            return Refusal("bindingIncomplete", "A selected method parameter has an incomplete Java binding.")
        }
        var problem: Refusal? = null
        body.accept(object : ASTVisitor() {
            override fun preVisit2(node: ASTNode): Boolean {
                if (problem != null) return false
                val supported = when (node) {
                    is Block, is ReturnStatement, is SimpleName, is NumberLiteral, is BooleanLiteral, is CharacterLiteral,
                    is ParenthesizedExpression, is InfixExpression, is ConditionalExpression, is CastExpression -> true
                    is PrimitiveType -> node.annotations().isEmpty()
                    is PrefixExpression -> node.operator in VALUE_PREFIX_OPERATORS
                    is MethodInvocation -> node.expression == null && node.typeArguments().isEmpty()
                    else -> false
                }
                if (!supported) {
                    problem = Refusal("dependencyUnsupported",
                        "The selected method body requires an expression or receiver outside the supported primitive group.")
                }
                return supported
            }

            override fun visit(name: SimpleName): Boolean {
                val key = memberKey(name.resolveBinding())
                when (key) {
                    null -> problem = Refusal("bindingIncomplete", "A name in a selected method has no complete binding.")
                    in group -> recordUse(key)
                    in parameters -> Unit
                    else -> problem = Refusal("dependencyUnsupported", "A selected method uses unselected member ${name.identifier}.")
                }
                return true
            }
        })
        return problem
    }

    private fun supportedMethod(method: MethodDeclaration): Boolean =
        !method.isConstructor && method.body != null && method.modifiers == (Modifier.PRIVATE or Modifier.STATIC) &&
            method.modifiers().all { it is Modifier } && method.javadoc == null &&
            method.typeParameters().isEmpty() && method.thrownExceptionTypes().isEmpty() && method.extraDimensions().isEmpty() &&
            method.returnType2.let { it is PrimitiveType && it.primitiveTypeCode != PrimitiveType.VOID && it.annotations().isEmpty() } &&
            method.parameters().filterIsInstance<SingleVariableDeclaration>().all {
                val type = it.type
                type is PrimitiveType && type.annotations().isEmpty() && !it.isVarargs &&
                    it.extraDimensions().isEmpty() && it.modifiers().all { modifier -> modifier is Modifier }
            }

    private fun supportedConstant(member: Declaration): Boolean {
        val field = member.node as FieldDeclaration
        val variable = member.name.parent as VariableDeclarationFragment
        val type = field.type
        return field.modifiers == (Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL) &&
            field.modifiers().all { it is Modifier } && field.javadoc == null && field.fragments().size == 1 &&
            type is PrimitiveType && type.primitiveTypeCode == PrimitiveType.INT && type.annotations().isEmpty() &&
            variable.extraDimensions().isEmpty() && variable.initializer is NumberLiteral &&
            variable.resolveBinding()?.constantValue is Int
    }

    private fun insideOwnerMethodBody(name: SimpleName, owner: TypeDeclaration): Boolean {
        var enclosing = name.parent
        while (enclosing != null && enclosing !== owner) {
            when (enclosing) {
                is MethodDeclaration -> return !enclosing.isConstructor && enclosing.parent === owner &&
                    enclosing.body?.let { isInside(name, it) } == true
                is AbstractTypeDeclaration, is AnonymousClassDeclaration -> return false
            }
            enclosing = enclosing.parent
        }
        return false
    }

    private fun connected(keys: Set<String>, uses: Set<Use>): Boolean {
        val connected = mutableSetOf(keys.first())
        do {
            val previous = connected.size
            for ((from, to) in uses) {
                if (from in connected || to in connected) connected.addAll(listOf(from, to))
            }
        } while (connected.size > previous)
        return connected == keys
    }

    private fun validate(unit: CompilationUnit, request: JavaExtractDelegateRequest, change: Change): Boolean {
        if (unit.problems.any { it.isError }) return false
        val owner = unit.findDeclaringNode(change.ownerKey) as? TypeDeclaration ?: return false
        if (declarations(owner).any { it.name.identifier in request.members }) return false
        val delegate = owner.types.singleOrNull { it.name.identifier == request.typeName } ?: return false
        val actual = declarations(delegate).associate { member ->
            member.name.identifier to (facts(member) ?: return false)
        }
        if (actual.mapValues { it.value.signature } != change.signatures) return false

        val names = actual.entries.associate { (name, value) -> value.key to name }
        val internal = mutableMapOf<Use, Int>()
        val external = mutableMapOf<String, Int>()
        unit.accept(object : ASTVisitor(true) {
            override fun visit(name: SimpleName): Boolean {
                val target = names[memberKey(name.resolveBinding())] ?: return true
                if (name.isDeclaration) return true
                val method = generateSequence(name.parent) { it.parent }.filterIsInstance<MethodDeclaration>().firstOrNull()
                if (method != null && method.parent === delegate) {
                    internal.merge(Use(method.name.identifier, target), 1, Int::plus)
                } else {
                    external.merge(target, 1, Int::plus)
                }
                return true
            }
        })
        return internal == change.internalUses && external == change.externalUses
    }

    private fun declarations(owner: TypeDeclaration): List<Declaration> =
        owner.methods.map { Declaration(it, it.name) } + owner.fields.flatMap { field ->
            field.fragments().filterIsInstance<VariableDeclarationFragment>().map { Declaration(field, it.name) }
        }

    private fun facts(declaration: Declaration): BindingFacts? {
        val binding = declaration.name.resolveBinding()
        val key = memberKey(binding) ?: return null
        val signature = when (binding) {
            is IMethodBinding -> Signature(binding.parameterTypes.map { it.key }, binding.returnType.key, binding.modifiers, null)
            is IVariableBinding -> Signature(null, binding.type.key, binding.modifiers, binding.constantValue as? Int)
            else -> return null
        }
        return BindingFacts(key, signature)
    }

    private fun memberKey(binding: IBinding?): String? = when (val resolved = binding?.takeUnless { it.isRecovered }) {
        is IMethodBinding -> resolved.methodDeclaration?.takeUnless { it.isRecovered }?.key
        is IVariableBinding -> resolved.variableDeclaration?.takeUnless { it.isRecovered }?.key
        else -> null
    }

    private fun removeDeclaration(content: String, node: ASTNode): TextEdit {
        val start = node.startPosition
        val end = start + node.length
        val lineStart = content.lastIndexOf('\n', start - 1) + 1
        val newline = content.indexOf('\n', end)
        if (newline >= 0 && content.substring(lineStart, start).isBlank() && content.substring(end, newline).isBlank()) {
            return edit(content, lineStart, newline + 1 - lineStart, "")
        }
        return edit(content, start, node.length, "")
    }

    private fun insertDelegate(content: String, owner: TypeDeclaration, members: List<Member>, name: String): TextEdit {
        val newline = if ("\r\n" in content) "\r\n" else "\n"
        val first = members.first().declaration.node.startPosition
        val indent = content.substring(content.lastIndexOf('\n', first - 1) + 1, first)
            .takeIf { it.isNotEmpty() && it.all { char -> char == ' ' || char == '\t' } } ?: "    "
        val close = owner.startPosition + owner.length - 1
        val lineStart = content.lastIndexOf('\n', close - 1) + 1
        val insertAt = if (content.substring(lineStart, close).isBlank()) lineStart else close
        val delegate = buildString {
            append(newline).append(indent).append("private static final class ").append(name).append(" {").append(newline)
            for (member in members) {
                val node = member.declaration.node
                val moved = content.substring(node.startPosition, node.startPosition + node.length)
                append(indent).append(indent).append(moved.replace("\n", "\n$indent")).append(newline)
            }
            append(indent).append('}').append(newline)
        }
        return edit(content, insertAt, 0, delegate)
    }

    private fun isInside(node: ASTNode, scope: ASTNode): Boolean = generateSequence(node.parent) { it.parent }.any { it === scope }

    private fun edit(content: String, start: Int, length: Int, replacement: String) =
        TextEdit(TextEdits.rangeForOffset(content, start, length), replacement)

    private fun diagnosticIdentity(diagnostic: Diagnostic) = Triple(diagnostic.code, diagnostic.message, diagnostic.location?.path)

    private fun unsupportedGroup() = Refusal("memberGroupUnsupported",
        "Select a closed group of private static primitive-return methods in one top-level class, with at most one private literal constant.")

    private fun refused(snapshot: ProjectSnapshot, code: String, message: String) = PatchPlan(
        operation = OPERATION, status = PatchStatus.REFUSED, snapshotHash = snapshot.hash, confidence = 0.0,
        requiresUserApproval = false, summary = message, affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(),
        warnings = listOf(message), riskLevel = RiskLevel.HIGH, refusalCode = "java.$OPERATION.$code",
    )

    private sealed interface Analysis
    private data class Refusal(val code: String, val message: String) : Analysis
    private data class Declaration(val node: ASTNode, val name: SimpleName)
    private data class Member(val declaration: Declaration, val facts: BindingFacts)
    private data class BindingFacts(val key: String, val signature: Signature)
    private data class Signature(val parameters: List<String>?, val type: String, val modifiers: Int, val constant: Int?)
    private data class Use(val from: String, val to: String)
    private data class Change(
        val edits: List<TextEdit>,
        val ownerKey: String,
        val signatures: Map<String, Signature>,
        val internalUses: Map<Use, Int>,
        val externalUses: Map<String, Int>,
    ) : Analysis

    private companion object {
        const val OPERATION = "extractDelegate"
        val VALUE_PREFIX_OPERATORS = setOf(PrefixExpression.Operator.PLUS, PrefixExpression.Operator.MINUS,
            PrefixExpression.Operator.COMPLEMENT, PrefixExpression.Operator.NOT)
    }
}
