package org.refactorkit.java

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.Assignment
import org.eclipse.jdt.core.dom.ClassInstanceCreation
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.CreationReference
import org.eclipse.jdt.core.dom.ExpressionMethodReference
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.LambdaExpression
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.PostfixExpression
import org.eclipse.jdt.core.dom.PrefixExpression
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.Statement
import org.eclipse.jdt.core.dom.SuperMethodInvocation
import org.eclipse.jdt.core.dom.ThisExpression
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.TypeMethodReference
import org.eclipse.jdt.core.dom.VariableDeclarationStatement
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.refactorkit.core.FileEdit
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.Files
import java.nio.file.Path

/**
 * Conservative Extract Method preview planner.
 *
 * Unbound compatibility shape:
 * - Java source file only.
 * - 1-based inclusive line range.
 * - Extracts selected complete lines into `private void <methodName>()`.
 * - Replaces the selected block with `<methodName>();` at the original indentation.
 * - Inserts the new method before the final class closing brace.
 *
 * SDK-bound semantic shape:
 * - a contiguous range of complete direct statements inside a resolved method body;
 * - resolved enclosing-method parameters and preceding method-local values read by the range
 *   become helper parameters;
 * - exactly one declared local used after the range becomes the helper result;
 * - checked exceptions from resolved calls are declared on the helper;
 * - unsupported, recovered, or non-denotable bindings fail closed.
 *
 * Conservative refusals for the compatibility shape:
 * - invalid method name
 * - invalid/blank range
 * - method name already exists in the file
 * - selected block contains `return`, `throw`, `break`, `continue`, or `yield`
 * - selected block appears to contain a declaration/method/class declaration
 * - selected block uses local variables/parameters declared before the range
 * - selected block declares locals that are used after the range
 *
 * The lexical compatibility route is review-only and cannot qualify managed Apply.
 * The binding-aware route is managed only for the single-local shape above after
 * staged binding and diagnostic validation.
 */
class JavaExtractMethodPlanner {

    fun preview(
        snapshot: ProjectSnapshot,
        filePath: Path,
        startLine: Int,
        endLine: Int,
        methodName: String,
    ): PatchPlan {
        if (!isValidJavaIdentifier(methodName)) {
            return refused(snapshot, "Invalid Java method name: $methodName")
        }
        if (startLine <= 0 || endLine < startLine) {
            return refused(snapshot, "Invalid line range: startLine=$startLine endLine=$endLine (lines are 1-based, inclusive)")
        }

        val file = snapshot.files.find { it.path == filePath }
            ?: return refused(snapshot, "File not found in snapshot: $filePath")
        if (file.languageId != "java") return refused(snapshot, "Extract method currently supports Java files only: $filePath")
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "Generated source cannot be rewritten: $filePath ($reason)")
        }
        val content = file.content
        if (Regex("""\b${Regex.escape(methodName)}\s*\(""").containsMatchIn(content)) {
            return refused(snapshot, "Method already exists or is already called in file: $methodName")
        }

        if (snapshot.semanticAnalysisAuthority is JavaContextAuthority) {
            return previewBindingAware(snapshot, filePath, startLine, endLine, methodName)
        }

        if (endLine >= startLine) {
            val diagnostics = runCatching { JavaLanguageAdapter().diagnostics(snapshot) }.getOrNull()
            if (diagnostics != null) {
                val semantic = withSourceSetCompilationUnit(snapshot, filePath) { unit ->
                    previewBoundSelection(snapshot, filePath, startLine, endLine, methodName, unit, diagnostics)
                }
                if (semantic != null) return semantic
            }
        }

        val lineStarts = computeLineStarts(content)
        val startZero = startLine - 1
        val endZero = endLine - 1
        if (startZero !in lineStarts.indices || endZero !in lineStarts.indices) {
            return refused(snapshot, "Line range is outside file: startLine=$startLine endLine=$endLine")
        }

        val startOffset = lineStarts[startZero]
        val endOffset = if (endZero + 1 < lineStarts.size) lineStarts[endZero + 1] else content.length
        val selected = content.substring(startOffset, endOffset)
        if (selected.isBlank()) return refused(snapshot, "Selected range is blank")

        validateSelection(snapshot, content, selected, startOffset, endOffset)?.let { return it }

        val insertOffset = findFinalClassBrace(content)
            ?: return refused(snapshot, "Could not find final class closing brace for method insertion")
        if (insertOffset <= endOffset) {
            return refused(snapshot, "Selected range appears to include the class closing brace")
        }

        val firstSelectedLine = selected.lineSequence().firstOrNull().orEmpty()
        val callIndent = firstSelectedLine.takeWhile { it == ' ' || it == '\t' }
        val classCloseLineStart = lineStarts.lastOrNull { it <= insertOffset } ?: 0
        val classIndent = content.substring(classCloseLineStart, insertOffset).takeWhile { it == ' ' || it == '\t' }
        val methodIndent = classIndent + "    "
        val bodyIndent = methodIndent + "    "

        val normalizedBody = selected.trimEnd('\n', '\r')
            .lines()
            .joinToString("\n") { line ->
                if (line.isBlank()) "" else bodyIndent + line.removePrefix(callIndent)
            }
        val methodText = "\n$methodIndent private void $methodName() {\n$normalizedBody\n$methodIndent}\n"
        val replacement = "$callIndent$methodName();\n"

        val replaceEdit = TextEdit(
            SourceRange(
                TextEdits.positionForOffset(content, startOffset),
                TextEdits.positionForOffset(content, endOffset),
            ),
            replacement,
        )
        val insertPos = TextEdits.positionForOffset(content, insertOffset)
        val insertEdit = TextEdit(SourceRange(insertPos, insertPos), methodText)

        return PatchPlan(
            operation = "extractMethod",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.70,
            requiresUserApproval = true,
            summary = "Extract lines $startLine-$endLine from $filePath into method $methodName().",
            affectedFiles = setOf(filePath),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(filePath, listOf(replaceEdit, insertEdit)))),
            warnings = listOf(
                "Limited extract-method MVP: only no-argument private void methods are supported.",
                "Selection was refused if local variables, return values, exceptions, or complex control flow were detected.",
            ),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.LEXICAL_FALLBACK,
        )
    }

    /**
     * A deliberately narrow semantic shape: one complete single-line local declaration
     * inside a resolved method body. Resolved method parameters become helper parameters,
     * the local becomes the helper result, and checked exceptions from resolved calls are
     * declared on the helper. Every unsupported or incomplete binding fails closed.
     */
    private fun previewBindingAware(
        snapshot: ProjectSnapshot,
        filePath: Path,
        startLine: Int,
        endLine: Int,
        methodName: String,
    ): PatchPlan {
        val resolution = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(snapshot, filePath) { unit, _ ->
            previewBoundSelection(snapshot, filePath, startLine, endLine, methodName, unit, null)
                ?: refused(snapshot, "Binding-aware extraction requires a selected local declaration")
        }
        return when (resolution) {
            is JdtBoundCompilationUnitResolution.Available -> resolution.value
            is JdtBoundCompilationUnitResolution.Refused -> refused(snapshot, resolution.message)
        }
    }

    private fun previewBoundSelection(
        snapshot: ProjectSnapshot,
        filePath: Path,
        startLine: Int,
        endLine: Int,
        methodName: String,
        unit: CompilationUnit,
        beforeDiagnostics: List<Diagnostic>?,
    ): PatchPlan? {
        val content = snapshot.files.single { it.path.normalize() == filePath.normalize() }.content
        if (startLine <= 0 || endLine < startLine) {
            return refused(snapshot, "Invalid binding-aware extract-method line range")
        }
        val lineStarts = computeLineStarts(content)
        val firstLineIndex = startLine - 1
        val lastLineIndex = endLine - 1
        if (firstLineIndex !in lineStarts.indices || lastLineIndex !in lineStarts.indices) {
            return refused(snapshot, "Line range is outside file: startLine=$startLine endLine=$endLine")
        }
        val lineStart = lineStarts[firstLineIndex]
        val selectionEnd = lineStarts.getOrNull(lastLineIndex + 1) ?: content.length
        var firstLineContentEnd = lineStarts.getOrNull(firstLineIndex + 1) ?: content.length
        while (firstLineContentEnd > lineStart && (content[firstLineContentEnd - 1] == '\n' || content[firstLineContentEnd - 1] == '\r')) {
            firstLineContentEnd--
        }
        var lastLineContentEnd = selectionEnd
        while (lastLineContentEnd > lineStarts[lastLineIndex] &&
            (content[lastLineContentEnd - 1] == '\n' || content[lastLineContentEnd - 1] == '\r')
        ) {
            lastLineContentEnd--
        }
        val firstLine = content.substring(lineStart, firstLineContentEnd)
        val statementStart = lineStart + firstLine.indexOfFirst { it != ' ' && it != '\t' }
            .let { if (it < 0) firstLine.length else it }
        if (statementStart == firstLineContentEnd) return refused(snapshot, "Selected range is blank")

        data class BoundSelection(
            val method: MethodDeclaration,
            val statements: List<Statement>,
            val firstStatementIndex: Int,
            val lastStatementIndex: Int,
        )

        val matches = mutableListOf<BoundSelection>()
        unit.accept(object : ASTVisitor() {
            override fun visit(node: MethodDeclaration): Boolean {
                val body = node.body ?: return true
                val statements = body.statements().filterIsInstance<Statement>()
                val firstIndex = statements.indexOfFirst { it.startPosition == statementStart }
                if (firstIndex < 0) return true
                val selected = mutableListOf<Statement>()
                var cursor = firstIndex
                var partialStatement = false
                while (cursor < statements.size && statements[cursor].startPosition < lastLineContentEnd) {
                    val candidate = statements[cursor]
                    val candidateEnd = candidate.startPosition + candidate.length
                    if (candidateEnd > lastLineContentEnd) {
                        partialStatement = true
                        break
                    }
                    selected += candidate
                    cursor++
                }
                val selectedEnd = selected.lastOrNull()?.let { it.startPosition + it.length }
                if (!partialStatement && selected.isNotEmpty() && selectedEnd != null &&
                    content.substring(selectedEnd, lastLineContentEnd).all(Char::isWhitespace)
                ) {
                    matches += BoundSelection(node, selected, firstIndex, firstIndex + selected.lastIndex)
                }
                return true
            }
        })
        val selection = matches.singleOrNull()
            ?: return refused(snapshot, "Binding-aware selection must contain complete contiguous statements directly inside one resolved method body")
        val method = selection.method
        val selectedStatements = selection.statements
        // Plain statement blocks continue through the review-only compatibility route. A range
        // containing a local declaration is a semantic candidate and must fail closed below.
        if (selectedStatements.none { it is VariableDeclarationStatement }) return null
        if (method.isConstructor || method.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0 ||
            selectedStatements.any { it.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0 }
        ) {
            return refused(snapshot, "The selected statements or enclosing method have incomplete syntax")
        }
        val owner = method.parent as? TypeDeclaration
            ?: return refused(snapshot, "Binding-aware extraction currently requires a method directly in a class")
        val ownerBinding = owner.resolveBinding()
        if (owner.isInterface || ownerBinding == null || ownerBinding.isRecovered || ownerBinding.isLocal || ownerBinding.isAnonymous) {
            return refused(snapshot, "Binding-aware extraction requires a resolved member class")
        }
        if (owner.startPosition + owner.length <= 0 || content.getOrNull(owner.startPosition + owner.length - 1) != '}') {
            return refused(snapshot, "Could not locate the resolved class closing brace for method insertion")
        }

        val selectedLocalKeys = linkedSetOf<String>()
        val selectedLocalFragments = linkedMapOf<String, Pair<VariableDeclarationFragment, VariableDeclarationStatement>>()
        var localBindingIssue: String? = null
        selectedStatements.forEach { selectedStatement ->
            selectedStatement.accept(object : ASTVisitor() {
                override fun visit(node: VariableDeclarationFragment): Boolean {
                    val binding = node.resolveBinding()
                    val key = binding?.variableDeclaration?.key
                    if (binding == null || binding.isRecovered || key.isNullOrBlank()) {
                        localBindingIssue = "A local declaration in the selected statements has no complete binding"
                        return true
                    }
                    selectedLocalKeys += key
                    (node.parent as? VariableDeclarationStatement)?.let { selectedLocalFragments[key] = node to it }
                    return true
                }

                override fun visit(node: org.eclipse.jdt.core.dom.SingleVariableDeclaration): Boolean {
                    val binding = node.resolveBinding()
                    val key = binding?.variableDeclaration?.key
                    if (binding == null || binding.isRecovered || key.isNullOrBlank()) {
                        localBindingIssue = "A local declaration in the selected statements has no complete binding"
                    } else {
                        selectedLocalKeys += key
                    }
                    return true
                }
            })
        }
        localBindingIssue?.let { return refused(snapshot, it) }

        val selectedEnd = selectedStatements.last().startPosition + selectedStatements.last().length
        val liveOutKeys = linkedSetOf<String>()
        method.body!!.statements().filterIsInstance<Statement>()
            .drop(selection.lastStatementIndex + 1)
            .forEach { laterStatement ->
                laterStatement.accept(object : ASTVisitor() {
                    override fun visit(node: SimpleName): Boolean {
                        val variable = node.resolveBinding() as? IVariableBinding ?: return true
                        val key = variable.variableDeclaration.key
                        if (key in selectedLocalKeys) liveOutKeys += key
                        return true
                    }
                })
            }
        if (liveOutKeys.size != 1) {
            return refused(snapshot, "Binding-aware extraction currently requires exactly one selected local used after the range")
        }
        val outputKey = liveOutKeys.single()
        val (fragment, outputStatement) = selectedLocalFragments[outputKey]
            ?: return refused(snapshot, "The selected output must be one direct local declaration in the method body")
        if (selectedStatements.none { it === outputStatement } || outputStatement.fragments().size != 1) {
            return refused(snapshot, "The selected output must be one complete local declaration with a single variable")
        }
        val initializer = fragment.initializer
            ?: return refused(snapshot, "The selected output local must have an initializer")
        val outputBinding = fragment.resolveBinding()
            ?: return refused(snapshot, "The selected output local has no resolved binding")
        if (outputBinding.isRecovered || outputBinding.isField || outputBinding.isParameter) {
            return refused(snapshot, "The selected output is not a resolved local variable")
        }
        val outputType = renderDenotableType(outputBinding.type)
            ?: return refused(snapshot, "The selected output local has a type that cannot be declared by the extracted helper")

        val parameters = method.parameters().filterIsInstance<org.eclipse.jdt.core.dom.SingleVariableDeclaration>()
        val parameterBindings = linkedMapOf<String, org.eclipse.jdt.core.dom.SingleVariableDeclaration>()
        for (parameter in parameters) {
            val binding = parameter.resolveBinding()
                ?: return refused(snapshot, "An enclosing method parameter has no resolved binding")
            if (binding.isRecovered || binding.key.isNullOrBlank()) {
                return refused(snapshot, "An enclosing method parameter has an incomplete binding")
            }
            if (parameter.modifiers().any { it is org.eclipse.jdt.core.dom.Annotation }) {
                return refused(snapshot, "Annotated method parameters cannot yet be copied to the extracted helper")
            }
            parameterBindings[binding.variableDeclaration.key] = parameter
        }

        // A direct local declaration before the selected range is safe to capture as a helper
        // parameter only when the selection's resolved binding points to that declaration. We
        // deliberately do not collect declarations nested in control-flow blocks or loops.
        val priorLocalFragments = linkedMapOf<String, VariableDeclarationFragment>()
        method.body!!.statements().filterIsInstance<Statement>()
            .take(selection.firstStatementIndex)
            .filterIsInstance<VariableDeclarationStatement>()
            .forEach { declaration ->
                declaration.fragments().filterIsInstance<VariableDeclarationFragment>().forEach { fragment ->
                    val binding = fragment.resolveBinding()
                    val key = binding?.variableDeclaration?.key
                    if (binding != null && !binding.isRecovered && !binding.isField && !binding.isParameter && !key.isNullOrBlank()) {
                        priorLocalFragments[key] = fragment
                    }
                }
            }

        fun writeTargetIssue(expression: org.eclipse.jdt.core.dom.Expression): String? {
            val variable = when (expression) {
                is SimpleName -> expression.resolveBinding() as? IVariableBinding
                is org.eclipse.jdt.core.dom.QualifiedName -> expression.resolveBinding() as? IVariableBinding
                is org.eclipse.jdt.core.dom.FieldAccess -> expression.resolveFieldBinding()
                is org.eclipse.jdt.core.dom.SuperFieldAccess -> expression.resolveFieldBinding()
                else -> null
            } ?: return "Complex assignment targets are not supported by binding-aware extraction"
            return if (variable.isField || variable.variableDeclaration.key in selectedLocalKeys) null
            else "Writes to values declared outside the selected statements are not supported"
        }

        var invalidReference: String? = null
        val usedParameterKeys = linkedSetOf<String>()
        val usedPriorLocalKeys = linkedSetOf<String>()
        val invokedMethods = mutableListOf<IMethodBinding>()
        val selectedVisitor = object : ASTVisitor() {
            override fun visit(node: LambdaExpression): Boolean { invalidReference = "Lambda expressions are not supported by binding-aware extraction"; return false }
            override fun visit(node: org.eclipse.jdt.core.dom.AnonymousClassDeclaration): Boolean { invalidReference = "Anonymous classes are not supported by binding-aware extraction"; return false }
            override fun visit(node: ExpressionMethodReference): Boolean { invalidReference = "Method references are not supported by binding-aware extraction"; return false }
            override fun visit(node: TypeMethodReference): Boolean { invalidReference = "Method references are not supported by binding-aware extraction"; return false }
            override fun visit(node: CreationReference): Boolean { invalidReference = "Constructor references are not supported by binding-aware extraction"; return false }
            override fun visit(node: org.eclipse.jdt.core.dom.ReturnStatement): Boolean { invalidReference = "Return statements inside the selected range are not supported"; return false }
            override fun visit(node: org.eclipse.jdt.core.dom.ThrowStatement): Boolean { invalidReference = "Throw statements inside the selected range are not supported"; return false }
            override fun visit(node: org.eclipse.jdt.core.dom.BreakStatement): Boolean { invalidReference = "Break statements inside the selected range are not supported"; return false }
            override fun visit(node: org.eclipse.jdt.core.dom.ContinueStatement): Boolean { invalidReference = "Continue statements inside the selected range are not supported"; return false }
            override fun visit(node: org.eclipse.jdt.core.dom.YieldStatement): Boolean { invalidReference = "Yield statements inside the selected range are not supported"; return false }
            override fun visit(node: Assignment): Boolean {
                invalidReference = writeTargetIssue(node.leftHandSide)
                return invalidReference == null
            }
            override fun visit(node: PostfixExpression): Boolean {
                invalidReference = writeTargetIssue(node.operand)
                return invalidReference == null
            }
            override fun visit(node: PrefixExpression): Boolean {
                if (node.operator == PrefixExpression.Operator.INCREMENT || node.operator == PrefixExpression.Operator.DECREMENT) {
                    invalidReference = writeTargetIssue(node.operand)
                    return invalidReference == null
                }
                return true
            }
            override fun visit(node: MethodInvocation): Boolean {
                val binding = node.resolveMethodBinding()
                if (binding == null || binding.isRecovered) invalidReference = "A method invoked by the selected range has no complete binding"
                else invokedMethods += binding
                return true
            }
            override fun visit(node: SuperMethodInvocation): Boolean {
                val binding = node.resolveMethodBinding()
                if (binding == null || binding.isRecovered) invalidReference = "A method invoked by the selected range has no complete binding"
                else invokedMethods += binding
                return true
            }
            override fun visit(node: ClassInstanceCreation): Boolean {
                if (node.anonymousClassDeclaration != null) {
                    invalidReference = "Anonymous classes are not supported by binding-aware extraction"
                    return false
                }
                val binding = node.resolveConstructorBinding()
                if (binding == null || binding.isRecovered) invalidReference = "A constructor invoked by the selected range has no complete binding"
                else invokedMethods += binding
                return true
            }
            override fun visit(node: SimpleName): Boolean {
                val binding = node.resolveBinding()
                if (binding == null || binding.isRecovered) {
                    invalidReference = "A name in the selected range has no complete binding"
                    return true
                }
                val variable = binding as? IVariableBinding ?: return true
                if (variable.isField) {
                    if (!Modifier.isStatic(method.modifiers) && !Modifier.isStatic(variable.modifiers)) return true
                    if (Modifier.isStatic(method.modifiers) && !Modifier.isStatic(variable.modifiers)) {
                        invalidReference = "A static enclosing method cannot extract a reference to an instance field"
                    }
                    return true
                }
                val parameterKey = variable.variableDeclaration.key
                if (parameterKey in selectedLocalKeys) return true
                if (parameterKey in parameterBindings) usedParameterKeys += parameterKey
                else if (parameterKey in priorLocalFragments) usedPriorLocalKeys += parameterKey
                else {
                    invalidReference = "The selected range reads a local value that is not declared in the selection, an enclosing method parameter, or a preceding direct method-body declaration"
                }
                return true
            }
            override fun visit(node: ThisExpression): Boolean {
                if (Modifier.isStatic(method.modifiers)) invalidReference = "A static enclosing method cannot extract an instance reference"
                return true
            }
        }
        selectedStatements.forEach { it.accept(selectedVisitor) }
        invalidReference?.let { return refused(snapshot, it) }

        val usedParameters = parameters.filter { parameter ->
            parameter.resolveBinding()?.variableDeclaration?.key in usedParameterKeys
        }
        val usedPriorLocals = priorLocalFragments.filterKeys { it in usedPriorLocalKeys }.values.toList()
        val renderedParameters = mutableListOf<String>()
        val callArguments = mutableListOf<String>()
        for (parameter in usedParameters) {
            val binding = parameter.resolveBinding() ?: return refused(snapshot, "An enclosing method parameter has no resolved binding")
            val type = renderDenotableType(binding.type)
                ?: return refused(snapshot, "A referenced method parameter has a type that cannot be declared by the extracted helper")
            renderedParameters += "$type ${parameter.name.identifier}"
            callArguments += parameter.name.identifier
        }
        for (local in usedPriorLocals) {
            val binding = local.resolveBinding()
                ?: return refused(snapshot, "A referenced preceding local has no resolved binding")
            if (binding.isRecovered || binding.isField || binding.isParameter) {
                return refused(snapshot, "A referenced preceding local has an incomplete binding")
            }
            if (binding.type.key.isNullOrBlank()) {
                return refused(snapshot, "A referenced preceding local has no stable type binding")
            }
            val type = renderDenotableType(binding.type)
                ?: return refused(snapshot, "A referenced preceding local has a type that cannot be declared by the extracted helper")
            renderedParameters += "$type ${local.name.identifier}"
            callArguments += local.name.identifier
        }
        if (renderedParameters.size != renderedParameters.map { it.substringAfterLast(' ') }.distinct().size) {
            return refused(snapshot, "Captured helper inputs have colliding parameter names")
        }

        val checkedExceptions = linkedMapOf<String, ITypeBinding>()
        if (invokedMethods.any { it.exceptionTypes.isNotEmpty() }) {
            val runtimeException = unit.ast.resolveWellKnownType("java.lang.RuntimeException")
                ?: return refused(snapshot, "The attested Java platform cannot resolve RuntimeException while checking helper exceptions")
            val errorType = unit.ast.resolveWellKnownType("java.lang.Error")
                ?: return refused(snapshot, "The attested Java platform cannot resolve Error while checking helper exceptions")
            for (invocation in invokedMethods) {
                for (exception in invocation.exceptionTypes) {
                    if (exception.isRecovered) return refused(snapshot, "A checked exception binding from the selected initializer is incomplete")
                    if (exception.isSubTypeCompatible(runtimeException) || exception.isSubTypeCompatible(errorType)) continue
                    if (renderDenotableType(exception) == null) {
                        return refused(snapshot, "A checked exception has a type that cannot be declared by the extracted helper")
                    }
                    checkedExceptions.putIfAbsent(exception.erasure.key, exception)
                }
            }
        }
        val renderedExceptions = checkedExceptions.values.mapNotNull(::renderDenotableType).sorted()
        if (renderedExceptions.size != checkedExceptions.size) {
            return refused(snapshot, "A checked exception has a type that cannot be declared by the extracted helper")
        }

        val outputPrefix = content.substring(outputStatement.startPosition, initializer.startPosition)
        if (!outputPrefix.contains('=')) return refused(snapshot, "The selected output declaration has no simple initializer")
        val leadingIndent = content.substring(lineStart, statementStart)
        if (leadingIndent.any { it != ' ' && it != '\t' }) {
            return refused(snapshot, "The selected statements must begin after indentation only")
        }
        val separator = content.substring(lastLineContentEnd, selectionEnd)
        val invocation = "$methodName(${callArguments.joinToString(", ")})"
        val replacement = leadingIndent + outputPrefix + invocation + ";" + separator

        val insertOffset = owner.startPosition + owner.length - 1
        val closeLineStart = lineStarts.lastOrNull { it <= insertOffset } ?: 0
        val classIndent = content.substring(closeLineStart, insertOffset).takeWhile { it == ' ' || it == '\t' }
        val methodIndent = classIndent + "    "
        val bodyIndent = methodIndent + "    "
        val isStatic = Modifier.isStatic(method.modifiers)
        val staticModifier = if (isStatic) "static " else ""
        val throws = if (renderedExceptions.isEmpty()) "" else " throws ${renderedExceptions.joinToString(", ")}"
        val selectedSource = content.substring(selectedStatements.first().startPosition, selectedEnd)
        val helperBody = selectedSource.lines().joinToString("\n") { sourceLine ->
            if (sourceLine.isBlank()) ""
            else bodyIndent + if (sourceLine.startsWith(leadingIndent)) sourceLine.removePrefix(leadingIndent) else sourceLine
        }
        val helper = "\n${methodIndent}private ${staticModifier}${outputType} $methodName(${renderedParameters.joinToString(", ")})$throws {\n" +
            "$helperBody\n${bodyIndent}return ${fragment.name.identifier};\n" +
            "${methodIndent}}\n"

        val replace = TextEdit(
            SourceRange(TextEdits.positionForOffset(content, lineStart), TextEdits.positionForOffset(content, selectionEnd)),
            replacement,
        )
        val insertPosition = TextEdits.positionForOffset(content, insertOffset)
        val insert = TextEdit(SourceRange(insertPosition, insertPosition), helper)
        val stagedContent = TextEdits.apply(content, listOf(replace, insert))
        val staged = snapshot.copy(files = snapshot.files.map { source ->
            if (source.path.normalize() == filePath.normalize()) source.copy(content = stagedContent) else source
        })
        val stagedBindings = withSourceSetCompilationUnit(staged, filePath) { stagedUnit ->
            preservesExtractedBindings(
                stagedUnit,
                methodName,
                method.resolveBinding()?.methodDeclaration?.key,
                fragment.name.identifier,
                outputBinding.type.key,
                renderedParameters.map { it.substringAfterLast(' ') },
                usedParameters.mapNotNull { it.resolveBinding()?.type?.key } + usedPriorLocals.mapNotNull { it.resolveBinding()?.type?.key },
                checkedExceptions.values.map { it.erasure.key },
                isStatic,
            )
        }
        if (stagedBindings != true) {
            return refused(snapshot, "Staged extract-method bindings did not preserve the output, helper call, parameters and exceptions")
        }
        val afterDiagnostics = beforeDiagnostics?.let { before ->
            val after = runCatching { JavaLanguageAdapter().diagnostics(staged) }.getOrElse {
                return refused(snapshot, "Staged Java diagnostics are unavailable for extract method")
            }
            val existingErrors = before.filter { it.severity == Diagnostic.Severity.ERROR }
                .map(::diagnosticIdentity).toSet()
            val introducedErrors = after.filter { it.severity == Diagnostic.Severity.ERROR }
                .map(::diagnosticIdentity).filterNot { it in existingErrors }
            if (introducedErrors.isNotEmpty()) {
                return refused(snapshot, "Extract method introduces ${introducedErrors.size} Java diagnostic error(s)")
            }
            after
        }
        return PatchPlan(
            operation = "extractMethod",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.86,
            requiresUserApproval = true,
            summary = "Extract ${selectedStatements.size} selected statements and their single live-out result from $filePath into binding-aware helper $methodName.",
            affectedFiles = setOf(filePath),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(filePath, listOf(replace, insert)))),
            diagnosticsBefore = beforeDiagnostics.orEmpty(),
            diagnosticsAfterPreview = afterDiagnostics.orEmpty(),
            warnings = listOf(
                "Binding-aware extraction requires complete JDT bindings, resolved method-parameter or preceding direct-local inputs, one live-out local, and checked-exception propagation.",
            ),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun preservesExtractedBindings(
        unit: CompilationUnit,
        helperName: String,
        enclosingMethodKey: String?,
        outputName: String,
        outputTypeKey: String,
        parameterNames: List<String>,
        parameterTypeKeys: List<String>,
        exceptionTypeKeys: List<String>,
        expectedStatic: Boolean,
    ): Boolean {
        if (enclosingMethodKey == null || parameterNames.size != parameterTypeKeys.size) return false
        val helpers = mutableListOf<MethodDeclaration>()
        val outputCalls = mutableListOf<MethodInvocation>()
        unit.accept(object : ASTVisitor() {
            override fun visit(node: MethodDeclaration): Boolean {
                if (node.name.identifier == helperName) helpers += node
                if (node.resolveBinding()?.methodDeclaration?.key == enclosingMethodKey) {
                    node.body?.accept(object : ASTVisitor() {
                        override fun visit(node: VariableDeclarationFragment): Boolean {
                            if (node.name.identifier == outputName) {
                                (node.initializer as? MethodInvocation)?.let(outputCalls::add)
                            }
                            return true
                        }
                    })
                }
                return true
            }
        })
        val helper = helpers.singleOrNull() ?: return false
        val helperBinding = helper.resolveBinding() ?: return false
        if (helperBinding.isRecovered || helperBinding.returnType.isRecovered || helperBinding.returnType.key != outputTypeKey) return false
        if (Modifier.isStatic(helper.modifiers) != expectedStatic) return false
        val parameters = helper.parameters().filterIsInstance<org.eclipse.jdt.core.dom.SingleVariableDeclaration>()
        if (parameters.map { it.name.identifier } != parameterNames || parameters.size != parameterTypeKeys.size) return false
        if (parameters.map { it.resolveBinding()?.type?.key } != parameterTypeKeys) return false
        val exceptions = helper.thrownExceptionTypes().filterIsInstance<org.eclipse.jdt.core.dom.Type>()
            .map { it.resolveBinding()?.erasure?.key ?: return false }.sorted()
        if (exceptions != exceptionTypeKeys.sorted()) return false
        val call = outputCalls.singleOrNull() ?: return false
        val callBinding = call.resolveMethodBinding() ?: return false
        return !callBinding.isRecovered && callBinding.methodDeclaration.key == helperBinding.methodDeclaration.key
    }

    private fun <T> withSourceSetCompilationUnit(
        snapshot: ProjectSnapshot,
        filePath: Path,
        action: (CompilationUnit) -> T,
    ): T? {
        val file = snapshot.files.singleOrNull { it.path.normalize() == filePath.normalize() } ?: return null
        val ownership = snapshot.owningBuildSourceRoots(file.path).singleOrNull() ?: return null
        if (ownership.generated || ownership.modelStatus != BuildModelStatus.AVAILABLE) return null
        val model = snapshot.buildModels.singleOrNull { it.providerId == ownership.providerId } ?: return null
        val sourceSet = ownership.sourceSet
        if (sourceSet.attributes["java.classpath.status"] == "unavailable" || sourceSet.compilerOptions.any { option ->
                Regex("(?:^|\\s)(?:--module-path|-p)(?:=|\\s|$)").containsMatchIn(option.value)
            }) return null
        if (snapshot.files.any { candidate ->
                candidate.languageId == "java" && candidate.path.fileName?.toString() == "module-info.java" &&
                    ownership.module.sourceSets.flatMap { it.sourceRoots }.any(candidate.path.normalize()::startsWith)
            }) return null
        val context = JavaBuildSourceSetContextResolver.resolve(model, ownership.module, sourceSet)
        val root = snapshot.workspace.root
        fun absolute(path: Path): Path = if (path.isAbsolute) path.normalize() else root.resolve(path).toAbsolutePath().normalize()
        val sourceRoots = context.sourceRoots.map(::absolute).filter { Files.isDirectory(it) }.map(Path::toString).distinct().toTypedArray()
        val classpath = context.classpathEntries.map(::absolute).filter { Files.exists(it) }.map(Path::toString).distinct().toTypedArray()
        val packageName = JavaPackageUtil.extractPackage(file.content)
        val unitName = buildString {
            if (packageName.isNotEmpty()) append(packageName.replace('.', '/')).append('/')
            append(file.path.fileName.toString())
        }
        val compliance = if (context.sourceLevel == 8) JavaCore.VERSION_1_8 else context.sourceLevel.toString()
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
            if (unit.problems.any { it.isError && it.id and org.eclipse.jdt.core.compiler.IProblem.Syntax != 0 }) null
            else action(unit)
        }
    }

    private fun diagnosticIdentity(diagnostic: Diagnostic): String =
        "${diagnostic.severity}|${diagnostic.code}|${diagnostic.message}|${diagnostic.location}"

    private fun renderDenotableType(type: ITypeBinding): String? {
        if (type.isRecovered || type.isCapture || type.isTypeVariable || type.isIntersectionType || type.isNullType ||
            type.isAnonymous || type.isLocal || type.isWildcardType || type.typeAnnotations.isNotEmpty()) return null
        if (type.isArray) {
            val element = renderDenotableType(type.elementType) ?: return null
            return element + "[]".repeat(type.dimensions)
        }
        if (type.isPrimitive) return type.name.takeIf(String::isNotBlank)
        if (type.isParameterizedType) {
            val declaringClass = type.declaringClass
            if (type.isMember && !Modifier.isStatic(type.modifiers) && declaringClass?.typeParameters?.isNotEmpty() == true) {
                return null
            }
            val rawName = type.typeDeclaration.qualifiedName.takeIf(String::isNotBlank) ?: return null
            val arguments = type.typeArguments.map { argument -> renderDenotableType(argument) ?: return null }
            return if (arguments.isEmpty()) rawName else "$rawName<${arguments.joinToString(", ")}>"
        }
        return type.qualifiedName.takeIf(String::isNotBlank)
    }

    private fun validateSelection(
        snapshot: ProjectSnapshot,
        content: String,
        selected: String,
        startOffset: Int,
        endOffset: Int,
    ): PatchPlan? {
        val forbiddenTokens = listOf("return", "throw", "break", "continue", "yield")
        forbiddenTokens.firstOrNull { JavaLexer.findOccurrences(selected, it).isNotEmpty() }?.let {
            return refused(snapshot, "Selection contains '$it'; return values/exceptions/control flow are not supported by extract-method MVP")
        }

        if (Regex("""\b(class|interface|enum|record)\b""").containsMatchIn(selected)) {
            return refused(snapshot, "Selection appears to contain a type declaration")
        }
        if (Regex("""\)\s*\{""").containsMatchIn(selected)) {
            return refused(snapshot, "Selection appears to contain a method/control block declaration; select complete statements only")
        }
        if (selected.count { it == '{' } != selected.count { it == '}' }) {
            return refused(snapshot, "Selection has unbalanced braces")
        }

        val prefix = content.substring(0, startOffset)
        val suffix = content.substring(endOffset)
        val selectedIdentifiers = identifiers(selected)
        val declaredBefore = localDeclarationNames(prefix) + nearestMethodParameterNames(prefix)
        val externalLocals = selectedIdentifiers.intersect(declaredBefore)
        if (externalLocals.isNotEmpty()) {
            return refused(snapshot, "Selection uses local variable(s)/parameter(s) declared before the range: ${externalLocals.sorted().joinToString(", ")}. Parameter extraction is not supported yet.")
        }

        val declaredInSelection = localDeclarationNames(selected)
        val usedAfter = declaredInSelection.filter { name -> JavaLexer.findOccurrences(suffix, name).isNotEmpty() }.toSet()
        if (usedAfter.isNotEmpty()) {
            return refused(snapshot, "Selection declares local variable(s) used after the range: ${usedAfter.sorted().joinToString(", ")}. Return-value extraction is not supported yet.")
        }
        return null
    }

    private fun computeLineStarts(content: String): List<Int> = buildList {
        add(0)
        content.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
    }

    private fun findFinalClassBrace(content: String): Int? {
        var i = content.length - 1
        while (i >= 0 && content[i].isWhitespace()) i--
        return if (i >= 0 && content[i] == '}') i else null
    }

    private fun identifiers(content: String): Set<String> = Regex("""\b[A-Za-z_$][A-Za-z0-9_$]*\b""")
        .findAll(content)
        .map { it.value }
        .filter { it !in JAVA_KEYWORDS }
        .toSet()

    private fun localDeclarationNames(content: String): Set<String> {
        val results = mutableSetOf<String>()
        val declaration = Regex(
            """\b(?:final\s+)?(?:[A-Z_a-z$][\w$]*(?:<[^;=(){}]*>)?(?:\[\])?\s+)+([a-zA-Z_$][\w$]*)\s*(?:=|;|,)""",
        )
        declaration.findAll(content).forEach { match -> results += match.groupValues[1] }
        return results
    }

    private fun nearestMethodParameterNames(prefix: String): Set<String> {
        val methodRegex = Regex("""(?:public|protected|private|static|final|synchronized|native|abstract|\s)+[\w<>\[\], ?]+\s+\w+\s*\(([^)]*)\)\s*\{""")
        val params = methodRegex.findAll(prefix).lastOrNull()?.groupValues?.get(1)?.trim().orEmpty()
        if (params.isBlank()) return emptySet()
        return params.split(',')
            .mapNotNull { param -> param.trim().split(Regex("""\s+""")).lastOrNull()?.removePrefix("...") }
            .filter { it.isNotBlank() && isValidJavaIdentifier(it) }
            .toSet()
    }

    private fun refused(snapshot: ProjectSnapshot, reason: String) = PatchPlan(
        operation = "extractMethod",
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(reason),
        riskLevel = RiskLevel.HIGH,
    )

    private fun isValidJavaIdentifier(name: String): Boolean {
        if (name.isEmpty()) return false
        if (!name[0].isLetter() && name[0] != '_' && name[0] != '$') return false
        return name.all { JavaLexer.isIdentChar(it) } && name !in JAVA_KEYWORDS
    }

    companion object {
        private val JAVA_KEYWORDS = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super",
            "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "var", "yield", "record", "sealed", "permits", "non-sealed",
        )
    }
}
