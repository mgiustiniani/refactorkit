package org.refactorkit.java

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.ArrayAccess
import org.eclipse.jdt.core.dom.ArrayCreation
import org.eclipse.jdt.core.dom.ArrayInitializer
import org.eclipse.jdt.core.dom.Assignment
import org.eclipse.jdt.core.dom.BooleanLiteral
import org.eclipse.jdt.core.dom.Block
import org.eclipse.jdt.core.dom.CastExpression
import org.eclipse.jdt.core.dom.CharacterLiteral
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor
import org.eclipse.jdt.core.dom.ChildPropertyDescriptor
import org.eclipse.jdt.core.dom.ClassInstanceCreation
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.ConditionalExpression
import org.eclipse.jdt.core.dom.Expression
import org.eclipse.jdt.core.dom.FieldAccess
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.InfixExpression
import org.eclipse.jdt.core.dom.LambdaExpression
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.MethodReference
import org.eclipse.jdt.core.dom.NullLiteral
import org.eclipse.jdt.core.dom.NumberLiteral
import org.eclipse.jdt.core.dom.ParenthesizedExpression
import org.eclipse.jdt.core.dom.PostfixExpression
import org.eclipse.jdt.core.dom.PrefixExpression
import org.eclipse.jdt.core.dom.ReturnStatement
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.SimplePropertyDescriptor
import org.eclipse.jdt.core.dom.SingleVariableDeclaration
import org.eclipse.jdt.core.dom.StringLiteral
import org.eclipse.jdt.core.dom.SuperMethodInvocation
import org.eclipse.jdt.core.dom.Type
import org.eclipse.jdt.core.dom.TypeLiteral
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.eclipse.jdt.core.dom.VariableDeclarationStatement
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.Files
import java.nio.file.Path
import javax.lang.model.SourceVersion

data class JavaExtractVariableRequest(
    val file: Path,
    val selection: SourceRange,
    val name: String,
)

/**
 * Binding-backed Java extract-variable cell. It accepts a complete, resolved
 * expression inside a direct method return when hoisting it before the return
 * preserves evaluation order and control flow. Unsupported or non-denotable
 * types refuse without an edit.
 */
class JavaExtractVariablePlanner(private val adapter: JavaLanguageAdapter) {
    fun preview(snapshot: ProjectSnapshot, request: JavaExtractVariableRequest): PatchPlan {
        if (!validIdentifier(request.name)) {
            return refused(snapshot, "java.extractVariable.invalidName", "The requested local name is not a valid Java identifier.")
        }
        val file = snapshot.files.singleOrNull { it.path.normalize() == request.file.normalize() }
            ?: return refused(snapshot, "java.extractVariable.fileUnavailable", "The selected Java file is absent or ambiguous in the snapshot.")
        if (file.languageId != "java") {
            return refused(snapshot, "java.extractVariable.languageUnsupported", "Extract variable currently supports Java source only.")
        }
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "java.extractVariable.generatedSource", "Generated Java source cannot be rewritten: $reason")
        }

        val startOffset: Int
        val endOffset: Int
        try {
            startOffset = TextEdits.offsetOf(file.content, request.selection.start)
            endOffset = TextEdits.offsetOf(file.content, request.selection.end)
        } catch (_: IllegalArgumentException) {
            return refused(snapshot, "java.extractVariable.selectionInvalid", "The selection is outside the saved Java source.")
        }
        if (startOffset >= endOffset) {
            return refused(snapshot, "java.extractVariable.selectionInvalid", "The selection must be non-empty and ordered.")
        }

        val beforeDiagnostics = runCatching { adapter.diagnostics(snapshot) }.getOrElse {
            return refused(snapshot, "java.extractVariable.diagnosticsUnavailable", "Java diagnostics are unavailable for the selected snapshot.")
        }
        val planned = withCompilationUnit(snapshot, file.path) { unit ->
            previewBoundSelection(snapshot, file.path, startOffset, endOffset, request.name, beforeDiagnostics, unit)
        }
        return planned ?: refused(
            snapshot,
            "java.extractVariable.analysisUnavailable",
            "A complete supported JDT source-set environment is unavailable for this selection.",
        )
    }

    private fun previewBoundSelection(
        snapshot: ProjectSnapshot,
        filePath: Path,
        startOffset: Int,
        endOffset: Int,
        name: String,
        beforeDiagnostics: List<Diagnostic>,
        unit: CompilationUnit,
    ): PatchPlan {
        val file = snapshot.files.single { it.path.normalize() == filePath.normalize() }
        val matchingExpressions = mutableListOf<Expression>()
        unit.accept(object : ASTVisitor() {
            override fun preVisit(node: ASTNode) {
                if (node is Expression && node.startPosition == startOffset && node.length == endOffset - startOffset) {
                    matchingExpressions += node
                }
            }
        })
        val expression = matchingExpressions.singleOrNull()
            ?: return refused(snapshot, "java.extractVariable.selectionNotExpression", "Selection must match one complete Java expression.")
        if (expression.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0) {
            return refused(snapshot, "java.extractVariable.selectionMalformed", "The selected expression is malformed or recovered.")
        }
        val returnStatement = generateSequence(expression.parent) { it.parent }
            .filterIsInstance<ReturnStatement>().firstOrNull()
            ?: return refused(snapshot, "java.extractVariable.selectionNotReturnExpression", "The selected expression must be inside a direct return statement.")
        val returnExpression = returnStatement.expression
            ?: return refused(snapshot, "java.extractVariable.selectionNotReturnExpression", "A return statement with a value is required.")
        if (returnStatement.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0 ||
            !isDescendantOrSelf(expression, returnExpression)) {
            return refused(snapshot, "java.extractVariable.selectionNotReturnExpression", "The selected expression is not inside a well-formed return value.")
        }
        val block = returnStatement.parent as? Block
            ?: return refused(snapshot, "java.extractVariable.returnShapeUnsupported", "The return statement must be directly in a method body.")
        val method = block.parent as? MethodDeclaration
            ?: return refused(snapshot, "java.extractVariable.returnShapeUnsupported", "The return statement must be directly in an ordinary method body.")
        if (method.body !== block || method.isConstructor || method.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0) {
            return refused(snapshot, "java.extractVariable.returnShapeUnsupported", "Only a direct return in a well-formed ordinary method body is supported.")
        }
        val owner = method.parent as? org.eclipse.jdt.core.dom.TypeDeclaration
            ?: return refused(snapshot, "java.extractVariable.ownerUnsupported", "The method must be declared directly in an ordinary Java class.")
        if (owner.isInterface || owner.resolveBinding()?.let { it.isRecovered || it.key.isNullOrBlank() } != false) {
            return refused(snapshot, "java.extractVariable.bindingIncomplete", "The containing class does not have a complete JDT binding.")
        }
        val methodBinding = method.resolveBinding()
            ?: return refused(snapshot, "java.extractVariable.bindingIncomplete", "The containing method does not have a JDT binding.")
        if (methodBinding.isRecovered || methodBinding.key.isNullOrBlank() || methodBinding.returnType.isRecovered ||
            methodBinding.returnType.isPrimitive && methodBinding.returnType.name == "void") {
            return refused(snapshot, "java.extractVariable.bindingIncomplete", "The containing method does not have a complete value-returning binding.")
        }
        val expressionType = expression.resolveTypeBinding()
            ?: return refused(snapshot, "java.extractVariable.bindingIncomplete", "The selected expression has no resolved JDT type.")
        if (expressionType.isRecovered || expressionType.key.isNullOrBlank()) {
            return refused(snapshot, "java.extractVariable.bindingIncomplete", "The selected expression type binding is incomplete.")
        }
        if (expression is LambdaExpression || expression is MethodReference || expressionType.isCapture ||
            expressionType.isIntersectionType || expressionType.isNullType || expressionType.isAnonymous ||
            expressionType.isLocal || expressionType.isWildcardType) {
            return refused(snapshot, "java.extractVariable.expressionTypeUnsupported", "The selected expression has a poly or non-denotable type.")
        }
        val typeSource = renderDenotableType(expressionType)
            ?: return refused(snapshot, "java.extractVariable.expressionTypeUnsupported", "The selected expression type cannot be written as a denotable local type.")
        evaluationRefusal(expression, returnExpression)?.let { (code, message) ->
            return refused(snapshot, code, message)
        }
        var incompleteNameBinding = false
        expression.accept(object : ASTVisitor() {
            override fun visit(node: SimpleName): Boolean {
                val binding = node.resolveBinding()
                if (binding == null || binding.isRecovered || binding.key.isNullOrBlank()) incompleteNameBinding = true
                return true
            }
        })
        if (incompleteNameBinding) {
            return refused(snapshot, "java.extractVariable.bindingIncomplete", "Every name in the selected expression must resolve completely.")
        }

        var nameCollision = false
        method.accept(object : ASTVisitor() {
            override fun visit(node: VariableDeclarationFragment): Boolean {
                if (node.name.identifier == name) nameCollision = true
                return true
            }

            override fun visit(node: SingleVariableDeclaration): Boolean {
                if (node.name.identifier == name) nameCollision = true
                return true
            }
        })
        if (nameCollision) {
            return refused(snapshot, "java.extractVariable.nameConflict", "A variable named '$name' is already declared in the containing method.")
        }
        if (returnExpressionHasFieldName(returnExpression, name)) {
            return refused(snapshot, "java.extractVariable.nameConflict", "The requested local name would shadow a field reference in the return expression.")
        }

        val lineStart = file.content.lastIndexOf('\n', (returnStatement.startPosition - 1).coerceAtLeast(0)) + 1
        val indentation = file.content.substring(lineStart, returnStatement.startPosition)
        if (indentation.any { it != ' ' && it != '\t' }) {
            return refused(snapshot, "java.extractVariable.returnPlacementUnsupported", "The return statement must begin on its own source line.")
        }
        val lineEnding = if (file.content.contains("\r\n")) "\r\n" else "\n"
        val originalExpression = file.content.substring(startOffset, endOffset)
        val originalExpressionFingerprint = bindingFingerprint(expression)
        val originalReturnFingerprint = structuralFingerprint(returnExpression, expression, expressionType.key, null)
        val edits = listOf(
            TextEdit(TextEdits.rangeForOffset(file.content, returnStatement.startPosition, 0), "$typeSource $name = $originalExpression;$lineEnding$indentation"),
            TextEdit(TextEdits.rangeForOffset(file.content, startOffset, endOffset - startOffset), name),
        )
        val stagedContent = TextEdits.apply(file.content, edits)
        val staged = snapshot.copy(files = snapshot.files.map { source ->
            if (source.path.normalize() == file.path.normalize()) source.copy(content = stagedContent) else source
        })
        val stagedStructure = withCompilationUnit(staged, file.path) { stagedUnit ->
            validateStagedStructure(
                stagedUnit,
                name,
                methodBinding.key,
                expressionType.key,
                originalExpressionFingerprint,
                originalReturnFingerprint,
            )
        }
        if (stagedStructure != true) {
            return refused(snapshot, "java.extractVariable.stagedValidationFailed", "Staged JDT bindings do not preserve the extracted expression and return-context bindings.")
        }
        val afterDiagnostics = adapter.diagnostics(staged)
        val baselineErrors = beforeDiagnostics.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticIdentity).toSet()
        val introducedErrors = afterDiagnostics.filter { it.severity == Diagnostic.Severity.ERROR }
            .map(::diagnosticIdentity).filterNot { it in baselineErrors }
        if (introducedErrors.isNotEmpty()) {
            return refused(snapshot, "java.extractVariable.diagnosticsFailed", "Extraction introduces ${introducedErrors.size} diagnostic error(s).")
        }
        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.97,
            requiresUserApproval = true,
            summary = "Extract the selected return subexpression into local '$name'.",
            affectedFiles = setOf(file.path),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(file.path, edits))),
            diagnosticsBefore = beforeDiagnostics,
            diagnosticsAfterPreview = afterDiagnostics,
            warnings = emptyList(),
            riskLevel = RiskLevel.MEDIUM,
            evidence = org.refactorkit.core.RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun validateStagedStructure(
        unit: CompilationUnit,
        name: String,
        methodBindingKey: String,
        expectedTypeKey: String,
        expectedInitializerFingerprint: String,
        expectedReturnFingerprint: String,
    ): Boolean {
        val method = findMethod(unit, methodBindingKey) ?: return false
        val body = method.body ?: return false
        val declarations = body.statements().filterIsInstance<VariableDeclarationStatement>()
            .flatMap { statement -> statement.fragments().filterIsInstance<VariableDeclarationFragment>().map { statement to it } }
            .filter { (_, fragment) -> fragment.name.identifier == name }
        val declaration = declarations.singleOrNull() ?: return false
        val fragment = declaration.second
        val variableBinding = fragment.resolveBinding() ?: return false
        if (variableBinding.isRecovered || variableBinding.type.isRecovered || variableBinding.type.key != expectedTypeKey) return false
        val initializer = fragment.initializer ?: return false
        if (bindingFingerprint(initializer) != expectedInitializerFingerprint) return false

        val statements = body.statements()
        val declarationIndex = statements.indexOf(declaration.first)
        val returnStatement = statements.drop(declarationIndex + 1).filterIsInstance<ReturnStatement>().firstOrNull() ?: return false
        if (declarationIndex < 0 || statements.getOrNull(declarationIndex + 1) !== returnStatement) return false
        val variableKey = variableBinding.variableDeclaration.key
        var references = 0
        var referenceValid = true
        returnStatement.expression?.accept(object : ASTVisitor() {
            override fun visit(node: SimpleName): Boolean {
                val binding = node.resolveBinding() as? IVariableBinding
                if (binding?.variableDeclaration?.key == variableKey) references++
                if (node.identifier == name && (binding == null || binding.variableDeclaration.key != variableKey)) referenceValid = false
                return true
            }
        })
        val stagedReturnExpression = returnStatement.expression ?: return false
        val actualReturnFingerprint = structuralFingerprint(stagedReturnExpression, null, expectedTypeKey, variableKey)
        return references == 1 && referenceValid && actualReturnFingerprint == expectedReturnFingerprint
    }

    private fun findMethod(unit: CompilationUnit, methodBindingKey: String): MethodDeclaration? {
        var match: MethodDeclaration? = null
        unit.accept(object : ASTVisitor() {
            override fun visit(node: MethodDeclaration): Boolean {
                if (node.resolveBinding()?.key == methodBindingKey) {
                    if (match != null) return false
                    match = node
                }
                return true
            }
        })
        return match
    }

    private fun isDescendantOrSelf(node: ASTNode, ancestor: ASTNode): Boolean {
        var current: ASTNode? = node
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun directChild(node: ASTNode, parent: ASTNode): ASTNode? {
        var current = node
        while (current.parent != null && current.parent !== parent) current = current.parent
        return current.takeIf { it.parent === parent }
    }

    private fun evaluationRefusal(expression: Expression, returnExpression: Expression): Pair<String, String>? {
        var child: ASTNode = expression
        while (child !== returnExpression) {
            val parent = child.parent ?: return "java.extractVariable.evaluationPathUnsupported" to
                "The selected expression is not on a supported evaluation path to the return value."
            if (parent is ConditionalExpression) {
                return "java.extractVariable.conditionalEvaluationUnsupported" to
                    "Expressions inside conditional branches cannot be hoisted without changing when they execute."
            }
            val ordered = orderedEvaluationChildren(parent)
                ?: return "java.extractVariable.evaluationPathUnsupported" to
                    "The selected expression is nested in an unsupported evaluation context."
            val selectedChild = directChild(child, parent)
                ?: return "java.extractVariable.evaluationPathUnsupported" to
                    "The selected expression is not a recognized child of its evaluation context."
            val selectedIndex = ordered.indexOfFirst { it === selectedChild }
            if (selectedIndex < 0) {
                return "java.extractVariable.evaluationPathUnsupported" to
                    "The selected expression is not in a supported evaluation position."
            }
            if (parent is InfixExpression &&
                (parent.operator == InfixExpression.Operator.CONDITIONAL_AND || parent.operator == InfixExpression.Operator.CONDITIONAL_OR) &&
                selectedIndex > 0) {
                return "java.extractVariable.conditionalEvaluationUnsupported" to
                    "An expression on the right side of && or || may not execute and cannot be hoisted."
            }
            if (ordered.take(selectedIndex).filterIsInstance<Expression>().any { preceding -> !isSafeEvaluationPrefix(preceding) }) {
                return "java.extractVariable.evaluationOrderUnsupported" to
                    "Hoisting would move the selected expression before an earlier effectful or potentially throwing expression."
            }
            child = parent
        }
        return null
    }

    private fun orderedEvaluationChildren(node: ASTNode): List<ASTNode>? = when (node) {
        is ParenthesizedExpression -> listOf(node.expression)
        is CastExpression -> listOf(node.expression)
        is PrefixExpression -> listOf(node.operand)
        is PostfixExpression -> listOf(node.operand)
        is InfixExpression -> listOf(node.leftOperand, node.rightOperand) + node.extendedOperands().filterIsInstance<ASTNode>()
        is MethodInvocation -> listOfNotNull(node.expression) + node.arguments().filterIsInstance<ASTNode>()
        is SuperMethodInvocation -> listOfNotNull(node.qualifier) + node.arguments().filterIsInstance<ASTNode>()
        is ClassInstanceCreation -> listOfNotNull(node.expression) + node.arguments().filterIsInstance<ASTNode>()
        is ArrayAccess -> listOf(node.array, node.index)
        is Assignment -> listOf(node.leftHandSide, node.rightHandSide)
        is ArrayCreation -> node.dimensions().filterIsInstance<ASTNode>() + listOfNotNull(node.initializer)
        is ArrayInitializer -> node.expressions().filterIsInstance<ASTNode>()
        is FieldAccess -> listOf(node.expression)
        is org.eclipse.jdt.core.dom.InstanceofExpression -> listOf(node.leftOperand)
        else -> null
    }

    private fun isSafeEvaluationPrefix(expression: Expression): Boolean {
        val type = expression.resolveTypeBinding() ?: return false
        if (type.isRecovered || type.isCapture || type.isNullType || type.isIntersectionType) return false
        return when (expression) {
            is BooleanLiteral, is CharacterLiteral, is NumberLiteral, is StringLiteral, is TypeLiteral -> true
            is NullLiteral -> false
            is SimpleName -> {
                val binding = expression.resolveBinding() as? IVariableBinding ?: return false
                !binding.isRecovered && !binding.key.isNullOrBlank() &&
                    ((binding.isField && binding.constantValue != null && binding.type.isPrimitive) ||
                        (!binding.isField && binding.type.isPrimitive))
            }
            is ParenthesizedExpression -> isSafeEvaluationPrefix(expression.expression)
            is CastExpression -> expression.type.resolveBinding()?.isPrimitive == true &&
                expression.expression.resolveTypeBinding()?.isPrimitive == true && isSafeEvaluationPrefix(expression.expression)
            is PrefixExpression -> expression.operator != PrefixExpression.Operator.INCREMENT &&
                expression.operator != PrefixExpression.Operator.DECREMENT && type.isPrimitive &&
                isSafeEvaluationPrefix(expression.operand)
            is InfixExpression -> {
                val unsafeOperator = expression.operator == InfixExpression.Operator.DIVIDE ||
                    expression.operator == InfixExpression.Operator.REMAINDER ||
                    expression.operator == InfixExpression.Operator.CONDITIONAL_AND ||
                    expression.operator == InfixExpression.Operator.CONDITIONAL_OR
                !unsafeOperator && type.isPrimitive &&
                    (listOf(expression.leftOperand, expression.rightOperand) + expression.extendedOperands().filterIsInstance<Expression>())
                        .all(::isSafeEvaluationPrefix)
            }
            is org.eclipse.jdt.core.dom.InstanceofExpression -> isSafeEvaluationPrefix(expression.leftOperand)
            else -> false
        }
    }

    private fun returnExpressionHasFieldName(expression: Expression, name: String): Boolean {
        var collision = false
        expression.accept(object : ASTVisitor() {
            override fun visit(node: SimpleName): Boolean {
                if (node.identifier == name && (node.resolveBinding() as? IVariableBinding)?.isField == true) collision = true
                return true
            }
        })
        return collision
    }

    private fun renderDenotableType(type: ITypeBinding, allowWildcard: Boolean = false): String? {
        if (type.isRecovered || type.key.isNullOrBlank() || type.isCapture || type.isIntersectionType || type.isNullType ||
            type.isAnonymous || type.isLocal) return null
        if (type.isWildcardType) {
            if (!allowWildcard) return null
            val bound = type.bound ?: return "?"
            val rendered = renderDenotableType(bound, allowWildcard = false) ?: return null
            return if (type.isUpperbound) "? extends $rendered" else "? super $rendered"
        }
        if (type.isPrimitive) return type.name.takeIf(String::isNotBlank)
        if (type.isArray) {
            val element = renderDenotableType(type.elementType, allowWildcard = false) ?: return null
            return element + "[]".repeat(type.dimensions)
        }
        if (type.isTypeVariable) return type.name.takeIf { SourceVersion.isIdentifier(it) && !SourceVersion.isKeyword(it) }
        val declaration = type.typeDeclaration ?: return null
        val qualifiedName = declaration.qualifiedName.takeIf(String::isNotBlank) ?: return null
        if (declaration.isMember && !org.eclipse.jdt.core.dom.Modifier.isStatic(declaration.modifiers) &&
            declaration.declaringClass?.typeParameters?.isNotEmpty() == true) return null
        if (type.isRawType && declaration.typeParameters.isNotEmpty()) return null
        val arguments = type.typeArguments.map { argument -> renderDenotableType(argument, allowWildcard = true) ?: return null }
        return if (arguments.isEmpty()) qualifiedName else "$qualifiedName<${arguments.joinToString(", ")}>"
    }

    private fun bindingFingerprint(node: ASTNode): String = structuralFingerprint(node, null, null, null)

    private fun structuralFingerprint(
        node: ASTNode,
        extractedExpression: Expression?,
        extractedTypeKey: String?,
        extractedVariableKey: String?,
    ): String {
        if (node === extractedExpression) return "EXTRACTED:${extractedTypeKey.orEmpty()}"
        if (node is SimpleName && extractedVariableKey != null) {
            val binding = node.resolveBinding() as? IVariableBinding
            if (binding?.variableDeclaration?.key == extractedVariableKey) return "EXTRACTED:${extractedTypeKey.orEmpty()}"
        }
        return buildString {
            append(node.nodeType).append('{')
            node.structuralPropertiesForType().forEach { descriptor ->
                append(descriptor.toString()).append('=')
                when (descriptor) {
                    is ChildPropertyDescriptor -> (node.getStructuralProperty(descriptor) as? ASTNode)?.let { child ->
                        append(structuralFingerprint(child, extractedExpression, extractedTypeKey, extractedVariableKey))
                    }
                    is ChildListPropertyDescriptor -> (node.getStructuralProperty(descriptor) as? List<*>)
                        ?.filterIsInstance<ASTNode>()?.forEach { child ->
                            append(structuralFingerprint(child, extractedExpression, extractedTypeKey, extractedVariableKey)).append(';')
                        }
                    is SimplePropertyDescriptor -> append(node.getStructuralProperty(descriptor)?.toString().orEmpty())
                }
                append('|')
            }
            bindingKey(node)?.let { append("binding=").append(it).append('|') }
            typeKey(node)?.let { append("type=").append(it).append('|') }
            append('}')
        }
    }

    private fun bindingKey(node: ASTNode): String? = when (node) {
        is SimpleName -> node.resolveBinding()?.key
        is MethodInvocation -> node.resolveMethodBinding()?.key
        is SuperMethodInvocation -> node.resolveMethodBinding()?.key
        is ClassInstanceCreation -> node.resolveConstructorBinding()?.key
        else -> null
    }

    private fun typeKey(node: ASTNode): String? = (node as? Expression)?.resolveTypeBinding()?.key

    private fun <T> withCompilationUnit(
        snapshot: ProjectSnapshot,
        filePath: Path,
        action: (CompilationUnit) -> T,
    ): T? {
        if (snapshot.semanticAnalysisAuthority != null) {
            return when (val result = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(snapshot, filePath) { unit, _ -> action(unit) }) {
                is JdtBoundCompilationUnitResolution.Available -> result.value
                is JdtBoundCompilationUnitResolution.Refused -> null
            }
        }
        val file = snapshot.files.singleOrNull { it.path.normalize() == filePath.normalize() } ?: return null
        val ownership = snapshot.owningBuildSourceRoots(file.path).singleOrNull() ?: return null
        if (ownership.generated || ownership.modelStatus != BuildModelStatus.AVAILABLE) return null
        val model = snapshot.buildModels.singleOrNull { it.providerId == ownership.providerId } ?: return null
        val sourceSet = ownership.sourceSet
        if (sourceSet.attributes["java.classpath.status"] == "unavailable") return null
        val sourceContext = JavaBuildSourceSetContextResolver.resolve(model, ownership.module, sourceSet)
        if (usesModulePath(sourceSet) || snapshot.files.any { candidate ->
                candidate.languageId == "java" && candidate.path.fileName?.toString() == "module-info.java" &&
                    ownership.module.sourceSets.flatMap { it.sourceRoots }.any(candidate.path.normalize()::startsWith)
            }) return null

        val root = snapshot.workspace.root
        fun absolute(path: Path): Path = if (path.isAbsolute) path.normalize() else root.resolve(path).toAbsolutePath().normalize()
        val sourceRoots = sourceContext.sourceRoots.map(::absolute).filter { Files.isDirectory(it) }.map(Path::toString).distinct().toTypedArray()
        val classpath = sourceContext.classpathEntries.map(::absolute).filter { Files.exists(it) }.map(Path::toString).distinct().toTypedArray()
        val packageName = JavaPackageUtil.extractPackage(file.content)
        val unitName = buildString {
            if (packageName.isNotEmpty()) append(packageName.replace('.', '/')).append('/')
            append(file.path.fileName.toString())
        }
        val compliance = if (sourceContext.sourceLevel == 8) JavaCore.VERSION_1_8 else sourceContext.sourceLevel.toString()
        return JdtClasspathResourceScope().use { resources ->
            val parser = ASTParser.newParser(AST.getJLSLatest())
            parser.setKind(ASTParser.K_COMPILATION_UNIT)
            parser.setSource(file.content.toCharArray())
            parser.setUnitName(unitName)
            parser.setCompilerOptions(JavaCore.getOptions().also { JavaCore.setComplianceOptions(compliance, it) })
            parser.setEnvironment(classpath, sourceRoots, null, true)
            parser.setResolveBindings(true)
            parser.setBindingsRecovery(true)
            parser.setStatementsRecovery(false)
            resources.prepare(parser)
            val unit = parser.createAST(null) as? CompilationUnit ?: return@use null
            if (unit.problems.any { problem -> problem.isError && problem.id and org.eclipse.jdt.core.compiler.IProblem.Syntax != 0 }) {
                null
            } else action(unit)
        }
    }

    private fun usesModulePath(sourceSet: BuildSourceSet): Boolean = sourceSet.compilerOptions.any { option ->
        Regex("(?:^|\\s)(?:--module-path|-p)(?:=|\\s|$)").containsMatchIn(option.value)
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
        evidence = org.refactorkit.core.RefactoringEvidence.JDT_BINDING,
        refusalCode = code,
    )

    private fun validIdentifier(name: String): Boolean =
        name.isNotBlank() && SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name) && name != "_"

    companion object {
        const val OPERATION = "extractVariable"
    }
}
