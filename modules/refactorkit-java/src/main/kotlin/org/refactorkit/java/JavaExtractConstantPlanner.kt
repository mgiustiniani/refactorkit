package org.refactorkit.java

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.Comment
import org.eclipse.jdt.core.dom.Expression
import org.eclipse.jdt.core.dom.FieldAccess
import org.eclipse.jdt.core.dom.FieldDeclaration
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.Name
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.Files
import java.nio.file.Path
import javax.lang.model.SourceVersion
import org.eclipse.jdt.core.dom.IMethodBinding
import org.eclipse.jdt.core.dom.InfixExpression
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.NumberLiteral
import org.eclipse.jdt.core.dom.ReturnStatement

data class JavaExtractConstantRequest(
    val file: Path,
    val selection: SourceRange,
    val name: String,
)

/**
 * Conservative JDT-bound extract-constant cell. It moves one complete
 * compile-time primitive-int expression from an ordinary method in a top-level
 * class to a private static final field, then proves that the staged field
 * initializer and replacement reference preserve the original bindings/value.
 */
class JavaExtractConstantPlanner(private val adapter: JavaLanguageAdapter) {
    fun preview(snapshot: ProjectSnapshot, request: JavaExtractConstantRequest): PatchPlan =
        preview(snapshot, request, instanceField = false)

    fun previewInstanceField(snapshot: ProjectSnapshot, request: JavaExtractConstantRequest): PatchPlan {
        val plan = preview(snapshot, request, instanceField = true)
        return plan.copy(
            operation = "extractField",
            summary = if (plan.status == PatchStatus.PREVIEW) {
                "Extract the selected compile-time integer expression into private final instance field '${request.name}'."
            } else plan.summary,
            warnings = if (plan.status == PatchStatus.PREVIEW) {
                plan.warnings + "The private field may be observed by reflection or serialization consumers."
            } else plan.warnings,
            refusalCode = plan.refusalCode?.replace("java.extractConstant.", "java.extractField."),
        )
    }

    private fun preview(snapshot: ProjectSnapshot, request: JavaExtractConstantRequest, instanceField: Boolean): PatchPlan {
        if (!validIdentifier(request.name)) {
            return refused(snapshot, "java.extractConstant.invalidName", "The requested constant name is not a valid Java identifier.")
        }
        val file = snapshot.files.singleOrNull { it.path.normalize() == request.file.normalize() }
            ?: return refused(snapshot, "java.extractConstant.fileUnavailable", "The selected Java file is absent or ambiguous in the snapshot.")
        if (file.languageId != "java") {
            return refused(snapshot, "java.extractConstant.languageUnsupported", "Extract constant currently supports Java source only.")
        }
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "java.extractConstant.generatedSource", "Generated Java source cannot be rewritten: $reason")
        }

        val startOffset: Int
        val endOffset: Int
        try {
            startOffset = TextEdits.offsetOf(file.content, request.selection.start)
            endOffset = TextEdits.offsetOf(file.content, request.selection.end)
        } catch (_: IllegalArgumentException) {
            return refused(snapshot, "java.extractConstant.selectionInvalid", "The selection is outside the saved Java source.")
        }
        if (startOffset >= endOffset) {
            return refused(snapshot, "java.extractConstant.selectionInvalid", "The selection must be non-empty and ordered.")
        }

        val before = runCatching { adapter.diagnostics(snapshot) }.getOrElse {
            return refused(snapshot, "java.extractConstant.diagnosticsUnavailable", "Java diagnostics are unavailable for the selected snapshot.")
        }
        val result = withCompilationUnit(snapshot, file.path) { unit ->
            previewBoundSelection(snapshot, file.path, startOffset, endOffset, request.name, before, unit, instanceField)
        }
        return result ?: refused(
            snapshot,
            "java.extractConstant.analysisUnavailable",
            "A complete supported JDT source-set environment is unavailable for this selection.",
        )
    }

    private fun previewBoundSelection(
        snapshot: ProjectSnapshot,
        filePath: Path,
        startOffset: Int,
        endOffset: Int,
        name: String,
        before: List<Diagnostic>,
        unit: CompilationUnit,
        instanceField: Boolean,
    ): PatchPlan {
        val file = snapshot.files.single { it.path.normalize() == filePath.normalize() }
        val content = file.content
        val expressions = mutableListOf<Expression>()
        unit.accept(object : ASTVisitor() {
            override fun preVisit(node: ASTNode) {
                if (node is Expression && node.startPosition == startOffset && node.length == endOffset - startOffset) {
                    expressions += node
                }
            }
        })
        val expression = expressions.singleOrNull()
            ?: return refused(snapshot, "java.extractConstant.selectionNotExpression", "Selection must match one complete Java expression.")
        if (expression.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0) {
            return refused(snapshot, "java.extractConstant.syntaxIncomplete", "The selected expression has incomplete syntax.")
        }
        val originalValue = expression.resolveConstantExpressionValue()
            ?: return refused(
                snapshot,
                "java.extractConstant.notCompileTimeConstant",
                "Only expressions with a JDT-proven compile-time constant value can be extracted.",
            )
        val expressionType = expression.resolveTypeBinding()
            ?: return refused(snapshot, "java.extractConstant.bindingIncomplete", "The selected expression has no resolved JDT type.")
        if (expressionType.isRecovered || !expressionType.isPrimitive || expressionType.name != "int") {
            return refused(snapshot, "java.extractConstant.typeUnsupported", "This first extract-constant cell supports resolved primitive-int expressions only.")
        }
        if (originalValue !is Int) {
            return refused(snapshot, "java.extractConstant.constantValueUnsupported", "The selected expression did not resolve to a primitive-int constant value.")
        }
        val originalNameBindings = bindingKeys(expression)
            ?: return refused(snapshot, "java.extractConstant.bindingIncomplete", "Every name in the selected expression must resolve completely.")

        val method = expression.ancestor<MethodDeclaration>()
            ?: return refused(snapshot, "java.extractConstant.methodRequired", "The expression must be inside an ordinary method body.")
        if (method.isConstructor || method.body == null || method.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0) {
            return refused(snapshot, "java.extractConstant.methodRequired", "The expression must be inside a well-formed ordinary method body.")
        }
        if (instanceField && Modifier.isStatic(method.modifiers)) {
            return refused(snapshot, "java.extractConstant.staticMethodUnsupported", "An instance field cannot replace an expression in a static method.")
        }
        val owner = method.parent as? TypeDeclaration
            ?: return refused(snapshot, "java.extractConstant.ownerUnsupported", "The method must be declared directly in an ordinary Java class.")
        val ownerBinding = owner.resolveBinding()
        if (owner.isInterface || ownerBinding == null || ownerBinding.isRecovered || ownerBinding.key.isNullOrBlank() ||
            ownerBinding.qualifiedName.isNullOrBlank() || owner.parent !== unit) {
            return refused(snapshot, "java.extractConstant.ownerUnsupported", "The method must belong to a completely bound top-level ordinary class.")
        }

        val fieldCollision = owner.bodyDeclarations().filterIsInstance<FieldDeclaration>().any { field ->
            field.fragments().filterIsInstance<VariableDeclarationFragment>().any { it.name.identifier == name }
        }
        if (fieldCollision) {
            return refused(snapshot, "java.extractConstant.nameConflict", "The containing class already declares a field named '$name'.")
        }
        if (expression.acceptsName(name)) {
            return refused(snapshot, "java.extractConstant.nameConflict", "The requested constant name occurs in the selected initializer.")
        }

        val insertion = fieldInsertion(unit, owner, content)
            ?: return refused(
                snapshot,
                "java.extractConstant.fieldPlacementUnsupported",
                "The containing class has no unambiguous line-oriented member insertion point.",
            )
        val qualifiedOwner = ownerBinding.qualifiedName
        val replacement = if (instanceField) "this.$name" else "$qualifiedOwner.$name"
        val originalExpression = content.substring(startOffset, endOffset)
        val lineEnding = if (content.contains("\r\n")) "\r\n" else "\n"
        val modifiers = if (instanceField) "private final" else "private static final"
        val fieldText = "${insertion.memberIndent}$modifiers int $name = $originalExpression;$lineEnding$lineEnding"
        val edits = listOf(
            TextEdit(TextEdits.rangeForOffset(content, insertion.offset, 0), fieldText),
            TextEdit(TextEdits.rangeForOffset(content, startOffset, endOffset - startOffset), replacement),
        )
        val stagedContent = runCatching { TextEdits.apply(content, edits) }.getOrElse {
            return refused(snapshot, "java.extractConstant.editConstructionFailed", "The proposed source edits could not be normalized.")
        }
        val staged = snapshot.copy(files = snapshot.files.map { source ->
            if (source.path.normalize() == file.path.normalize()) source.copy(content = stagedContent) else source
        })
        val replacementStart = startOffset + fieldText.length
        val stagedProof = withCompilationUnit(staged, file.path) { stagedUnit ->
            validateStaged(stagedUnit, ownerBinding.key, name, originalValue, originalNameBindings, replacement, replacementStart, instanceField)
        }
        if (stagedProof != true) {
            return refused(snapshot, "java.extractConstant.stagedValidationFailed", "Staged JDT bindings do not prove the extracted constant preserves the selected expression.")
        }
        val after = runCatching { adapter.diagnostics(staged) }.getOrElse {
            return refused(snapshot, "java.extractConstant.diagnosticsUnavailable", "Staged Java diagnostics are unavailable.")
        }
        val previousErrors = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticIdentity).toSet()
        val introduced = after.filter { it.severity == Diagnostic.Severity.ERROR }
            .map(::diagnosticIdentity).filterNot(previousErrors::contains)
        if (introduced.isNotEmpty()) {
            return refused(snapshot, "java.extractConstant.diagnosticsFailed", "Extraction introduces ${introduced.size} diagnostic error(s).")
        }

        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.97,
            requiresUserApproval = true,
            summary = "Extract the selected compile-time integer expression into private static final field '$name'.",
            affectedFiles = setOf(file.path),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(file.path, edits))),
            diagnosticsBefore = before,
            diagnosticsAfterPreview = after,
            warnings = emptyList(),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun fieldInsertion(unit: CompilationUnit, owner: TypeDeclaration, content: String): FieldInsertion? {
        val openingBrace = content.indexOf('{', owner.name.startPosition + owner.name.length)
        val closingBrace = owner.startPosition + owner.length - 1
        if (openingBrace < 0 || closingBrace <= openingBrace || content.getOrNull(closingBrace) != '}') return null
        val firstMember = owner.bodyDeclarations().filterIsInstance<ASTNode>().firstOrNull()
        val anchor = firstMember?.startPosition ?: closingBrace
        if (anchor <= openingBrace) return null
        val lineStart = content.lastIndexOf('\n', (anchor - 1).coerceAtLeast(0)) + 1
        if (lineStart <= openingBrace || content.substring(lineStart, anchor).any { it != ' ' && it != '\t' }) return null
        val commentsBeforeMember = unit.commentList.filterIsInstance<Comment>().any { comment ->
            comment.startPosition > openingBrace && comment.startPosition < anchor
        }
        if (commentsBeforeMember) return null
        return FieldInsertion(lineStart, content.substring(lineStart, anchor))
    }

    private fun validateStaged(
        unit: CompilationUnit,
        ownerKey: String,
        fieldName: String,
        expectedValue: Int,
        expectedInitializerBindings: List<String>,
        replacement: String,
        replacementStart: Int,
        instanceField: Boolean,
    ): Boolean {
        val owner = unit.types().filterIsInstance<TypeDeclaration>().singleOrNull { it.resolveBinding()?.key == ownerKey }
            ?: return false
        val matchingFragments = owner.bodyDeclarations().filterIsInstance<FieldDeclaration>().flatMap { field ->
            field.fragments().filterIsInstance<VariableDeclarationFragment>().filter { it.name.identifier == fieldName }
                .map { field to it }
        }
        val (field, fragment) = matchingFragments.singleOrNull() ?: return false
        if (!Modifier.isPrivate(field.modifiers) ||
            Modifier.isStatic(field.modifiers) == instanceField ||
            !Modifier.isFinal(field.modifiers)) return false
        val fieldBinding = fragment.resolveBinding() ?: return false
        if (fieldBinding.isRecovered || !fieldBinding.isField || !fieldBinding.type.isPrimitive || fieldBinding.type.name != "int" ||
            fieldBinding.constantValue != expectedValue) return false
        val initializer = fragment.initializer ?: return false
        if (initializer.resolveConstantExpressionValue() != expectedValue || bindingKeys(initializer) != expectedInitializerBindings) return false

        val references = mutableListOf<ASTNode>()
        unit.accept(object : ASTVisitor() {
            override fun preVisit(node: ASTNode) {
                if ((node is Name || node is FieldAccess) &&
                    node.startPosition == replacementStart && node.length == replacement.length) references += node
            }
        })
        val reference = references.singleOrNull() ?: return false
        val referenceBinding = when (reference) {
            is Name -> reference.resolveBinding() as? IVariableBinding
            is FieldAccess -> reference.resolveFieldBinding()
            else -> null
        } ?: return false
        return !referenceBinding.isRecovered && referenceBinding.variableDeclaration.key == fieldBinding.variableDeclaration.key
    }

    private fun bindingKeys(expression: Expression): List<String>? {
        val keys = mutableListOf<String>()
        var complete = true
        expression.accept(object : ASTVisitor() {
            override fun visit(node: SimpleName): Boolean {
                val binding = node.resolveBinding()
                if (binding == null || binding.isRecovered || binding.key.isNullOrBlank()) complete = false
                else keys += binding.key
                return true
            }
        })
        return keys.takeIf { complete }
    }

    private inline fun <reified T : ASTNode> ASTNode.ancestor(): T? {
        var current: ASTNode? = this.parent
        while (current != null) {
            if (current is T) return current
            current = current.parent
        }
        return null
    }

    private fun Expression.acceptsName(identifier: String): Boolean {
        var found = false
        accept(object : ASTVisitor() {
            override fun visit(node: SimpleName): Boolean {
                if (node.identifier == identifier) found = true
                return true
            }
        })
        return found
    }

    private fun <T> withCompilationUnit(snapshot: ProjectSnapshot, filePath: Path, action: (CompilationUnit) -> T): T? {
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
        val sourceRoots = sourceContext.sourceRoots.map(::absolute).filter(Files::isDirectory).map(Path::toString).distinct().toTypedArray()
        val classpath = sourceContext.classpathEntries.map(::absolute).filter(Files::exists).map(Path::toString).distinct().toTypedArray()
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
            if (unit.problems.any { it.isError && it.id and org.eclipse.jdt.core.compiler.IProblem.Syntax != 0 }) null
            else action(unit)
        }
    }

    private fun usesModulePath(sourceSet: BuildSourceSet): Boolean = sourceSet.compilerOptions.any { option ->
        Regex("(?:^|\\s)(?:--module-path|-p)(?:=|\\s|$)").containsMatchIn(option.value)
    }

    private fun diagnosticIdentity(diagnostic: Diagnostic): String =
        "${diagnostic.severity}|${diagnostic.code}|${diagnostic.message}|${diagnostic.location}"

    private fun validIdentifier(name: String): Boolean =
        name.isNotBlank() && SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name) && name != "_"

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

    private data class FieldInsertion(val offset: Int, val memberIndent: String)

    companion object {
        const val OPERATION = "extractConstant"
    }
}

data class JavaExtractParameterRequest(
    val file: Path,
    val selection: SourceRange,
    val name: String,
)

/**
 * First bound extract-parameter cell. It supports one exact `int` return
 * expression of the form `parameter + integerLiteral`, one private static
 * method with one `int` parameter, and one same-file direct call whose argument
 * is a bound `int` identifier. Everything else fails closed.
 */
class JavaExtractParameterPlanner(private val adapter: JavaLanguageAdapter) {
    fun preview(snapshot: ProjectSnapshot, request: JavaExtractParameterRequest): PatchPlan {
        if (!validIdentifier(request.name)) {
            return refused(snapshot, "java.extractParameter.invalidName", "The requested parameter name is not a valid Java identifier.")
        }
        val file = snapshot.files.singleOrNull { it.path.normalize() == request.file.normalize() }
            ?: return refused(snapshot, "java.extractParameter.fileUnavailable", "The selected Java file is absent or ambiguous in the snapshot.")
        if (file.languageId != "java") {
            return refused(snapshot, "java.extractParameter.languageUnsupported", "Extract parameter supports Java source only.")
        }
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "java.extractParameter.generatedSource", "Generated Java source cannot be rewritten: $reason")
        }

        val start: Int
        val end: Int
        try {
            start = TextEdits.offsetOf(file.content, request.selection.start)
            end = TextEdits.offsetOf(file.content, request.selection.end)
        } catch (_: IllegalArgumentException) {
            return refused(snapshot, "java.extractParameter.selectionInvalid", "The selection is outside the saved Java source.")
        }
        if (start >= end) {
            return refused(snapshot, "java.extractParameter.selectionInvalid", "The selection must be non-empty and ordered.")
        }

        val diagnosticsBefore = runCatching { adapter.diagnostics(snapshot) }.getOrElse {
            return refused(snapshot, "java.extractParameter.diagnosticsUnavailable", "Java diagnostics are unavailable for the selected snapshot.")
        }
        val sourceErrors = diagnosticsBefore.filter {
            it.severity == Diagnostic.Severity.ERROR && it.location?.path?.normalize() == file.path.normalize()
        }
        if (sourceErrors.isNotEmpty()) {
            return refused(snapshot, "java.extractParameter.sourceDiagnosticsUnclean", "The selected source has baseline compiler errors.")
        }

        val analysis = runCatching { JdtJavaSemanticAnalyzer().analyze(snapshot) }.getOrElse {
            return refused(snapshot, "java.extractParameter.analysisUnavailable", "Complete JDT project analysis is unavailable.")
        }
        val unit = withBoundUnit(snapshot, file.path) { parsed -> parsed }
            ?: return refused(snapshot, "java.extractParameter.analysisUnavailable", "The selected source has no current SDK-bound JDT compilation unit.")
        return previewBound(snapshot, file.path, start, end, request.name, diagnosticsBefore, analysis, unit)
    }

    private fun previewBound(
        snapshot: ProjectSnapshot,
        filePath: Path,
        start: Int,
        end: Int,
        parameterName: String,
        diagnosticsBefore: List<Diagnostic>,
        analysis: JdtJavaSemanticAnalysisResult,
        unit: CompilationUnit,
    ): PatchPlan {
        val file = snapshot.files.single { it.path.normalize() == filePath.normalize() }
        val expressions = mutableListOf<Expression>()
        unit.accept(object : ASTVisitor() {
            override fun preVisit(node: ASTNode) {
                if (node is Expression && node.startPosition == start && node.length == end - start) expressions += node
            }
        })
        val expression = expressions.singleOrNull()
            ?: return refused(snapshot, "java.extractParameter.selectionNotExpression", "Selection must match one complete Java expression.")
        if (expression.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0) {
            return refused(snapshot, "java.extractParameter.syntaxIncomplete", "The selected expression has incomplete syntax.")
        }
        val returnStatement = expression.parent as? ReturnStatement
            ?: return refused(snapshot, "java.extractParameter.selectionNotReturnExpression", "The selected expression must be the complete value of a return statement.")
        if (returnStatement.expression !== expression) {
            return refused(snapshot, "java.extractParameter.selectionNotReturnExpression", "The selection must cover the complete return expression.")
        }
        val body = returnStatement.parent as? org.eclipse.jdt.core.dom.Block
            ?: return refused(snapshot, "java.extractParameter.methodShapeUnsupported", "The return must be the method's sole body statement.")
        val method = body.parent as? MethodDeclaration
            ?: return refused(snapshot, "java.extractParameter.methodRequired", "The selected return must belong to a Java method.")
        if (method.body !== body || body.statements().size != 1 || method.isConstructor || method.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0) {
            return refused(snapshot, "java.extractParameter.methodShapeUnsupported", "Only a well-formed method with one return statement is supported.")
        }
        if (!Modifier.isPrivate(method.modifiers) || !Modifier.isStatic(method.modifiers)) {
            return refused(snapshot, "java.extractParameter.methodVisibilityUnsupported", "The first cell supports private static methods only.")
        }
        val owner = method.parent as? TypeDeclaration
            ?: return refused(snapshot, "java.extractParameter.ownerUnsupported", "The method must be declared in an ordinary Java class.")
        val ownerBinding = owner.resolveBinding()
        val methodBinding = method.resolveBinding()
        if (owner.isInterface || ownerBinding == null || ownerBinding.isRecovered || ownerBinding.key.isNullOrBlank() ||
            ownerBinding.qualifiedName.isNullOrBlank() || owner.parent !== unit || methodBinding == null ||
            methodBinding.isRecovered || methodBinding.methodDeclaration.key.isNullOrBlank()) {
            return refused(snapshot, "java.extractParameter.bindingIncomplete", "The containing class or method has no complete JDT binding.")
        }
        if (method.parameters().size != 1) {
            return refused(snapshot, "java.extractParameter.methodShapeUnsupported", "The first cell supports exactly one existing method parameter.")
        }
        val originalParameter = method.parameters().single() as? org.eclipse.jdt.core.dom.SingleVariableDeclaration
            ?: return refused(snapshot, "java.extractParameter.bindingIncomplete", "The existing method parameter is not a complete declaration.")
        val originalParameterBinding = originalParameter.resolveBinding()
        val returnType = method.returnType2?.resolveBinding()
        if (originalParameterBinding == null || originalParameterBinding.isRecovered || !originalParameterBinding.isParameter ||
            !isInt(originalParameterBinding.type) || returnType == null || returnType.isRecovered || !isInt(returnType)) {
            return refused(snapshot, "java.extractParameter.typeUnsupported", "The method and its sole parameter must have resolved primitive-int types.")
        }
        if (parameterName == originalParameter.name.identifier) {
            return refused(snapshot, "java.extractParameter.nameConflict", "The new parameter name already exists in the method signature.")
        }

        val infix = expression as? InfixExpression
            ?: return refused(snapshot, "java.extractParameter.expressionUnsupported", "Only a primitive-int addition expression is supported.")
        if (infix.operator != InfixExpression.Operator.PLUS || infix.extendedOperands().isNotEmpty() || !isInt(infix.resolveTypeBinding())) {
            return refused(snapshot, "java.extractParameter.expressionUnsupported", "Only one primitive-int addition with exactly two operands is supported.")
        }
        val left = infix.leftOperand as? SimpleName
            ?: return refused(snapshot, "java.extractParameter.expressionUnsupported", "The left operand must be the existing method parameter.")
        val leftBinding = left.resolveBinding() as? IVariableBinding
            ?: return refused(snapshot, "java.extractParameter.bindingIncomplete", "The expression parameter has no JDT variable binding.")
        val right = infix.rightOperand as? NumberLiteral
            ?: return refused(snapshot, "java.extractParameter.expressionUnsupported", "The right operand must be an integer literal.")
        val literalValue = right.resolveConstantExpressionValue() as? Int
            ?: return refused(snapshot, "java.extractParameter.expressionUnsupported", "The literal must resolve to a primitive-int constant.")
        if (leftBinding.isRecovered || leftBinding.variableDeclaration.key != originalParameterBinding.variableDeclaration.key) {
            return refused(snapshot, "java.extractParameter.expressionBindingMismatch", "The selected expression must use the method's exact parameter binding.")
        }

        val methodKey = methodBinding.methodDeclaration.key
        val methodFact = analysis.methods.singleOrNull { it.bindingKey == methodKey && it.path.normalize() == file.path.normalize() }
            ?: return refused(snapshot, "java.extractParameter.bindingIncomplete", "Whole-project JDT method evidence is missing or ambiguous.")
        if (methodFact.evidence != JdtJavaSemanticEvidence.JDT_BINDING ||
            analysis.overrideRelations.any { it.overridingBindingKey == methodKey || it.overriddenBindingKey == methodKey }) {
            return refused(snapshot, "java.extractParameter.methodFamilyUnsupported", "Method hierarchy or binding evidence is not closed for this first cell.")
        }
        if (analysis.methodReferences.any { it.methodBindingKey == methodKey } ||
            analysis.methodReferenceUses.any { it.methodBindingKey == methodKey }) {
            return refused(snapshot, "java.extractParameter.methodReferenceUnsupported", "Method references to the selected method are outside this cell.")
        }
        val invocationFacts = analysis.invocations.filter { it.methodBindingKey == methodKey }
        val invocationFact = invocationFacts.singleOrNull()
            ?: return refused(snapshot, "java.extractParameter.callSitesUnsupported", "Exactly one complete in-workspace call site is required.")
        if (invocationFact.evidence != JdtJavaSemanticEvidence.JDT_BINDING ||
            invocationFact.path.normalize() != file.path.normalize() ||
            invocationFact.argumentRanges.size != 1 || invocationFact.receiverKind != JdtJavaInvocationReceiverKind.IMPLICIT ||
            invocationFact.callerMethodQualifiedName?.substringBefore('#') != ownerBinding.qualifiedName) {
            return refused(snapshot, "java.extractParameter.callSitesUnsupported", "The only call must be direct, bound, same-file, and owned by the same top-level class.")
        }

        val calls = mutableListOf<MethodInvocation>()
        unit.accept(object : ASTVisitor() {
            override fun visit(node: MethodInvocation): Boolean {
                val binding = node.resolveMethodBinding()
                if (binding?.methodDeclaration?.key == methodKey) calls += node
                return true
            }
        })
        val call = calls.singleOrNull()
            ?: return refused(snapshot, "java.extractParameter.callSitesUnsupported", "The exact JDT invocation is not present in the selected compilation unit.")
        if (call.expression != null || call.arguments().size != 1 ||
            call.startPosition < 0 || call.startPosition + call.length > file.content.length) {
            return refused(snapshot, "java.extractParameter.callSitesUnsupported", "Only one unqualified one-argument invocation is supported.")
        }
        val actualArgument = call.arguments().single() as? SimpleName
            ?: return refused(snapshot, "java.extractParameter.callArgumentUnsupported", "The caller argument must be a simple local or parameter identifier.")
        val actualBinding = actualArgument.resolveBinding() as? IVariableBinding
            ?: return refused(snapshot, "java.extractParameter.bindingIncomplete", "The caller argument has no JDT variable binding.")
        if (actualBinding.isRecovered || actualBinding.isField || !isInt(actualBinding.type) || actualBinding.key.isNullOrBlank()) {
            return refused(snapshot, "java.extractParameter.callArgumentUnsupported", "The caller identifier must bind to a local or parameter of primitive-int type.")
        }
        val parameterPosition = TextEdits.rangeForOffset(file.content, originalParameter.startPosition + originalParameter.length, 0).start
        val parameterRange = SourceRange(parameterPosition, parameterPosition)
        val argumentEnd = actualArgument.startPosition + actualArgument.length
        val argumentInsertion = TextEdits.rangeForOffset(file.content, argumentEnd, 0).start
        val callArgumentRange = SourceRange(argumentInsertion, argumentInsertion)
        val callArgumentText = "${actualArgument.identifier} + ${right.token}"
        val replacement = listOf(
            TextEdit(requestSelection(file.content, start, end), parameterName),
            TextEdit(parameterRange, ", int $parameterName"),
            TextEdit(callArgumentRange, ", $callArgumentText"),
        )
        val stagedContent = runCatching { TextEdits.apply(file.content, replacement) }.getOrElse {
            return refused(snapshot, "java.extractParameter.editConstructionFailed", "The proposed source edits could not be normalized.")
        }
        val staged = snapshot.copy(files = snapshot.files.map { source ->
            if (source.path.normalize() == file.path.normalize()) source.copy(content = stagedContent) else source
        })
        val stagedAnalysis = runCatching { JdtJavaSemanticAnalyzer().analyze(staged) }.getOrElse {
            return refused(snapshot, "java.extractParameter.stagedValidationFailed", "Staged whole-project JDT analysis is unavailable.")
        }
        val stagedUnit = withBoundUnit(staged, file.path) { parsed -> parsed }
            ?: return refused(snapshot, "java.extractParameter.stagedValidationFailed", "Staged source no longer has a current JDT compilation unit.")
        if (!validateStaged(
                stagedUnit, stagedAnalysis, file.path, ownerBinding.key, method.name.identifier,
                originalParameter.name.identifier, parameterName, actualBinding.variableDeclaration.key,
                literalValue, callArgumentText,
            )) {
            return refused(snapshot, "java.extractParameter.stagedValidationFailed", "Staged JDT bindings do not prove the new parameter and call argument remain equivalent.")
        }
        val diagnosticsAfter = runCatching { adapter.diagnostics(staged) }.getOrElse {
            return refused(snapshot, "java.extractParameter.diagnosticsUnavailable", "Staged Java diagnostics are unavailable.")
        }
        if (introducedErrors(diagnosticsBefore, diagnosticsAfter).isNotEmpty()) {
            return refused(snapshot, "java.extractParameter.diagnosticsFailed", "Extract parameter introduces compiler diagnostic errors.")
        }

        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.97,
            requiresUserApproval = true,
            summary = "Promote the selected integer return expression to parameter '$parameterName' and update its bound call site.",
            affectedFiles = setOf(file.path),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(file.path, replacement))),
            diagnosticsBefore = diagnosticsBefore,
            diagnosticsAfterPreview = diagnosticsAfter,
            warnings = listOf("Exactly one private static method and one same-file direct invocation are JDT-bound; other call shapes refuse."),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun validateStaged(
        unit: CompilationUnit,
        analysis: JdtJavaSemanticAnalysisResult,
        filePath: Path,
        ownerKey: String,
        methodName: String,
        originalParameterName: String,
        newParameterName: String,
        expectedCallerBindingKey: String,
        expectedLiteral: Int,
        expectedCallArgumentText: String,
    ): Boolean {
        val owner = unit.types().filterIsInstance<TypeDeclaration>().singleOrNull { it.resolveBinding()?.key == ownerKey }
            ?: return false
        val methods = owner.bodyDeclarations().filterIsInstance<MethodDeclaration>().filter { candidate ->
            candidate.name.identifier == methodName && candidate.parameters().size == 2 &&
                (candidate.parameters()[0] as? org.eclipse.jdt.core.dom.SingleVariableDeclaration)?.name?.identifier == originalParameterName &&
                (candidate.parameters()[1] as? org.eclipse.jdt.core.dom.SingleVariableDeclaration)?.name?.identifier == newParameterName
        }
        val method = methods.singleOrNull() ?: return false
        val methodBinding = method.resolveBinding() ?: return false
        if (methodBinding.isRecovered || !Modifier.isPrivate(method.modifiers) || !Modifier.isStatic(method.modifiers)) return false
        val parameters = method.parameters().mapNotNull { it as? org.eclipse.jdt.core.dom.SingleVariableDeclaration }
        if (parameters.size != 2 || parameters.any { !isInt(it.resolveBinding()?.type) }) return false
        val newParameterBinding = parameters[1].resolveBinding() ?: return false
        if (newParameterBinding.isRecovered || !newParameterBinding.isParameter) return false
        val returned = method.body?.statements()?.singleOrNull() as? ReturnStatement ?: return false
        val returnedName = returned.expression as? SimpleName ?: return false
        val returnedBinding = returnedName.resolveBinding() as? IVariableBinding ?: return false
        if (returnedName.identifier != newParameterName || returnedBinding.isRecovered ||
            returnedBinding.variableDeclaration.key != newParameterBinding.variableDeclaration.key) return false

        val stagedInvocations = analysis.invocations.filter { it.methodBindingKey == methodBinding.methodDeclaration.key }
        val invocation = stagedInvocations.singleOrNull() ?: return false
        if (invocation.path.normalize() != filePath.normalize() || invocation.argumentRanges.size != 2 ||
            invocation.evidence != JdtJavaSemanticEvidence.JDT_BINDING) return false
        var call: MethodInvocation? = null
        unit.accept(object : ASTVisitor() {
            override fun visit(node: MethodInvocation): Boolean {
                if (node.resolveMethodBinding()?.methodDeclaration?.key == methodBinding.methodDeclaration.key) {
                    if (call != null) return false
                    call = node
                }
                return true
            }
        })
        val stagedCall = call ?: return false
        if (stagedCall.expression != null || stagedCall.arguments().size != 2) return false
        val first = stagedCall.arguments()[0] as? SimpleName ?: return false
        val firstBinding = first.resolveBinding() as? IVariableBinding ?: return false
        val second = stagedCall.arguments()[1] as? InfixExpression ?: return false
        val secondLeft = second.leftOperand as? SimpleName ?: return false
        val secondLeftBinding = secondLeft.resolveBinding() as? IVariableBinding ?: return false
        val secondRight = second.rightOperand as? NumberLiteral ?: return false
        return second.operator == InfixExpression.Operator.PLUS && second.extendedOperands().isEmpty() &&
            firstBinding.variableDeclaration.key == expectedCallerBindingKey &&
            secondLeftBinding.variableDeclaration.key == expectedCallerBindingKey &&
            secondRight.resolveConstantExpressionValue() == expectedLiteral && isInt(second.resolveTypeBinding()) &&
            expectedCallArgumentText == "${secondLeft.identifier} + ${secondRight.token}"
    }

    private fun <T> withBoundUnit(snapshot: ProjectSnapshot, filePath: Path, action: (CompilationUnit) -> T): T? =
        when (val result = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(snapshot, filePath) { unit, _ -> action(unit) }) {
            is JdtBoundCompilationUnitResolution.Available -> result.value
            is JdtBoundCompilationUnitResolution.Refused -> null
        }

    private fun introducedErrors(before: List<Diagnostic>, after: List<Diagnostic>): List<Diagnostic> {
        val baseline = before.filter { it.severity == Diagnostic.Severity.ERROR }.groupingBy(::diagnosticIdentity).eachCount().toMutableMap()
        return after.filter { it.severity == Diagnostic.Severity.ERROR }.filter { diagnostic ->
            val identity = diagnosticIdentity(diagnostic)
            val remaining = baseline.getOrDefault(identity, 0)
            if (remaining == 0) true else {
                baseline[identity] = remaining - 1
                false
            }
        }
    }

    private fun diagnosticIdentity(diagnostic: Diagnostic): String =
        "${diagnostic.severity}|${diagnostic.code}|${diagnostic.message}|${diagnostic.location}"

    private fun isInt(type: org.eclipse.jdt.core.dom.ITypeBinding?): Boolean =
        type != null && !type.isRecovered && type.isPrimitive && type.name == "int"

    private fun requestSelection(content: String, start: Int, end: Int): SourceRange =
        TextEdits.rangeForOffset(content, start, end - start)

    private fun validIdentifier(name: String): Boolean =
        name.isNotBlank() && SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name) && name != "_"

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
        const val OPERATION = "extractParameter"
    }
}
