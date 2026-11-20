package org.refactorkit.java

import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.Annotation
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.Expression
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.InfixExpression
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.NumberLiteral
import org.eclipse.jdt.core.dom.ParenthesizedExpression
import org.eclipse.jdt.core.dom.PrefixExpression
import org.eclipse.jdt.core.dom.PrimitiveType
import org.eclipse.jdt.core.dom.ReturnStatement
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
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.Path
import javax.lang.model.SourceVersion

data class JavaExtractSuperclassRequest(
    val file: Path,
    val typeSymbol: String,
    val superclassName: String,
    val methodName: String,
)

/**
 * First managed extract-superclass cell. It moves one pure primitive-int method
 * into a same-package base class; every method that reads state or invokes code
 * refuses before a workspace edit.
 */
class JavaExtractSuperclassPlanner(private val adapter: JavaLanguageAdapter) {
    fun preview(snapshot: ProjectSnapshot, request: JavaExtractSuperclassRequest): PatchPlan {
        if (!validIdentifier(request.superclassName)) {
            return refused(snapshot, "java.extractSuperclass.invalidName", "The requested superclass name is not a valid Java identifier.")
        }
        if (!validIdentifier(request.methodName)) {
            return refused(snapshot, "java.extractSuperclass.invalidMethod", "A valid Java method name is required.")
        }
        val file = snapshot.files.singleOrNull { it.path.normalize() == request.file.normalize() }
            ?: return refused(snapshot, "java.extractSuperclass.fileUnavailable", "The selected Java source is absent or ambiguous in the snapshot.")
        if (file.languageId != "java") {
            return refused(snapshot, "java.extractSuperclass.languageUnsupported", "Extract superclass supports Java source only.")
        }
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "java.extractSuperclass.generatedSource", "Generated Java source cannot be changed: $reason")
        }
        val before = runCatching { adapter.diagnostics(snapshot) }.getOrElse {
            return refused(snapshot, "java.extractSuperclass.diagnosticsUnavailable", "Java diagnostics are unavailable for the selected snapshot.")
        }
        val resolution = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(snapshot, file.path) { unit, _ ->
            previewBound(snapshot, file.path, request, before, unit)
        }
        return when (resolution) {
            is JdtBoundCompilationUnitResolution.Available -> resolution.value
            is JdtBoundCompilationUnitResolution.Refused -> refused(
                snapshot,
                "java.extractSuperclass.analysisUnavailable",
                "A complete SDK-bound Java source-set environment is unavailable for the selected type.",
            )
        }
    }

    private fun previewBound(
        snapshot: ProjectSnapshot,
        filePath: Path,
        request: JavaExtractSuperclassRequest,
        before: List<Diagnostic>,
        unit: CompilationUnit,
    ): PatchPlan {
        val file = snapshot.files.single { it.path.normalize() == filePath.normalize() }
        val content = file.content
        val owner = unit.types().filterIsInstance<TypeDeclaration>()
            .singleOrNull { it.resolveBinding()?.qualifiedName == request.typeSymbol }
            ?: return refused(snapshot, "java.extractSuperclass.typeUnavailable", "The symbol must identify exactly one top-level JDT-bound class in the selected file.")
        val ownerBinding = owner.resolveBinding()
            ?: return refused(snapshot, "java.extractSuperclass.bindingIncomplete", "The selected class has no JDT type binding.")
        if (owner.parent !== unit || owner.isInterface || ownerBinding.isRecovered || ownerBinding.key.isNullOrBlank() ||
            ownerBinding.qualifiedName != request.typeSymbol || unit.types().size != 1 ||
            owner.superclassType != null || owner.superInterfaceTypes().isNotEmpty() || owner.typeParameters().isNotEmpty() ||
            owner.modifiers().any { it.toString() == "sealed" || it.toString() == "non-sealed" }) {
            return refused(snapshot, "java.extractSuperclass.hierarchyUnsupported", "Only one top-level ordinary class without an existing superclass, interfaces, or type parameters is supported.")
        }

        val methods = owner.bodyDeclarations().filterIsInstance<MethodDeclaration>()
            .filter { it.name.identifier == request.methodName }
        val method = methods.singleOrNull()
            ?: return refused(snapshot, "java.extractSuperclass.methodAmbiguous", "The method name must identify exactly one declaration on the selected class.")
        val binding = method.resolveBinding()
            ?: return refused(snapshot, "java.extractSuperclass.bindingIncomplete", "The selected method has no JDT method binding.")
        if (method.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0 || binding.isRecovered ||
            binding.key.isNullOrBlank() || binding.declaringClass?.key != ownerBinding.key) {
            return refused(snapshot, "java.extractSuperclass.bindingIncomplete", "The selected method binding is incomplete or belongs to another type.")
        }
        val returnType = method.returnType2 as? PrimitiveType
        val returnBinding = binding.returnType
        if (method.isConstructor || method.body == null || !Modifier.isPublic(binding.modifiers) ||
            Modifier.isStatic(binding.modifiers) || Modifier.isSynchronized(binding.modifiers) ||
            method.typeParameters().isNotEmpty() || method.thrownExceptionTypes().isNotEmpty() || method.isVarargs ||
            method.modifiers().any { it is Annotation } || returnType?.primitiveTypeCode != PrimitiveType.INT ||
            returnBinding == null || returnBinding.isRecovered || !isInt(returnBinding) ||
            method.parameters().filterIsInstance<SingleVariableDeclaration>().any { parameter ->
                parameter.type !is PrimitiveType || (parameter.type as PrimitiveType).primitiveTypeCode != PrimitiveType.INT ||
                    parameter.extraDimensions().isNotEmpty() || parameter.modifiers().any { it is Annotation }
            } || binding.parameterTypes.size != method.parameters().size || binding.parameterTypes.any { !isInt(it) || it.isRecovered }) {
            return refused(snapshot, "java.extractSuperclass.signatureUnsupported", "Only a public, unannotated, non-synchronized instance method with an int return and int parameters is supported.")
        }
        val statement = method.body.statements().singleOrNull() as? ReturnStatement
            ?: return refused(snapshot, "java.extractSuperclass.bodyUnsupported", "The method body must contain exactly one int return statement.")
        val expression = statement.expression
            ?: return refused(snapshot, "java.extractSuperclass.bodyUnsupported", "The selected return statement must return an int expression.")
        if (!isMovableIntExpression(expression, binding) || expression.resolveTypeBinding()?.let(::isInt) != true) {
            return refused(snapshot, "java.extractSuperclass.bodyUnsupported", "The return expression may use only int parameters, numeric int literals, parentheses, and primitive int arithmetic.")
        }

        val packageName = unit.`package`?.name?.fullyQualifiedName.orEmpty()
        val sourceRoot = snapshot.owningBuildSourceRoots(file.path).singleOrNull()
            ?: return refused(snapshot, "java.extractSuperclass.sourceRootUnavailable", "The selected class has no unique effective source-set root.")
        if (sourceRoot.generated || sourceRoot.modelStatus != org.refactorkit.core.BuildModelStatus.AVAILABLE) {
            return refused(snapshot, "java.extractSuperclass.sourceRootUnavailable", "Extract superclass requires an available non-generated Java source set.")
        }
        val workspaceRoot = snapshot.workspace.root.toAbsolutePath().normalize()
        val declaredRoot = sourceRoot.root.normalize()
        val relativeRoot = if (declaredRoot.isAbsolute) {
            if (!declaredRoot.startsWith(workspaceRoot)) {
                return refused(snapshot, "java.extractSuperclass.targetOutsideWorkspace", "The selected source root is outside the workspace.")
            }
            workspaceRoot.relativize(declaredRoot)
        } else declaredRoot
        val packagePath = if (packageName.isBlank()) Path.of("") else Path.of(packageName.replace('.', '/'))
        val superclassPath = relativeRoot.resolve(packagePath).resolve("${request.superclassName}.java").normalize()
        if (superclassPath.isAbsolute || superclassPath.startsWith("..")) {
            return refused(snapshot, "java.extractSuperclass.targetOutsideWorkspace", "The generated superclass path escapes the workspace.")
        }
        if (snapshot.trackedFiles.any { it.path.normalize() == superclassPath }) {
            return refused(snapshot, "java.extractSuperclass.nameConflict", "The requested superclass file already exists.")
        }
        val declaredTypePattern = Regex("\\b(?:class|interface|enum|record)\\s+${Regex.escape(request.superclassName)}\\b|@interface\\s+${Regex.escape(request.superclassName)}\\b")
        if (snapshot.files.any { candidate ->
                candidate.languageId == "java" && JavaPackageUtil.extractPackage(candidate.content) == packageName &&
                    declaredTypePattern.containsMatchIn(candidate.content)
            }) {
            return refused(snapshot, "java.extractSuperclass.nameConflict", "A same-package type already uses the requested superclass name.")
        }

        val openingBrace = content.indexOf('{', owner.name.startPosition + owner.name.length)
        if (openingBrace < 0 || openingBrace >= owner.startPosition + owner.length - 1 ||
            !content.substring(owner.name.startPosition + owner.name.length, openingBrace).isBlank()) {
            return refused(snapshot, "java.extractSuperclass.declarationLayoutUnsupported", "The selected class header does not have a simple insertion point for its superclass clause.")
        }
        val methodStart = method.startPosition
        val methodEnd = methodStart + method.length
        if (methodStart < 0 || methodEnd > content.length) {
            return refused(snapshot, "java.extractSuperclass.declarationLayoutUnsupported", "The selected method has an invalid source range.")
        }
        val lineStart = content.lastIndexOf('\n', (methodStart - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val originalIndent = content.substring(lineStart, methodStart)
        if (!originalIndent.all(Char::isWhitespace)) {
            return refused(snapshot, "java.extractSuperclass.declarationLayoutUnsupported", "The selected method does not begin at a stable line boundary.")
        }
        val lineEnding = if (content.contains("\r\n")) "\r\n" else "\n"
        val methodSource = content.substring(methodStart, methodEnd)
        val parentMethod = methodSource.lines().joinToString(lineEnding) { line ->
            "    " + if (originalIndent.isNotEmpty() && line.startsWith(originalIndent)) line.removePrefix(originalIndent) else line
        }
        val packagePrefix = if (packageName.isBlank()) "" else "package $packageName;$lineEnding$lineEnding"
        val superclassText = buildString {
            append(packagePrefix)
            append("public class ${request.superclassName} {").append(lineEnding)
            append(parentMethod).append(lineEnding)
            append('}').append(lineEnding)
        }
        val edit = WorkspaceEdit(listOf(
            FileEdit.Create(superclassPath, superclassText),
            FileEdit.Modify(file.path, listOf(
                TextEdit(TextEdits.rangeForOffset(content, methodStart, method.length), ""),
                TextEdit(TextEdits.rangeForOffset(content, openingBrace, 0), "extends ${request.superclassName} "),
            )),
        ))
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, edit) }.getOrElse {
            return refused(snapshot, "java.extractSuperclass.editInvalid", "The superclass extraction edits cannot be staged against the captured workspace.")
        }
        val stagedProof = runCatching {
            adapter.withDiagnosticsOverlay(staged) { overlay ->
                val stagedOwner = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(overlay, file.path) { stagedUnit, _ ->
                    val stagedType = stagedUnit.types().filterIsInstance<TypeDeclaration>()
                        .singleOrNull { it.resolveBinding()?.key == ownerBinding.key }
                        ?: return@withBoundCompilationUnit false
                    val stagedBinding = stagedType.resolveBinding() ?: return@withBoundCompilationUnit false
                    val parent = stagedBinding.superclass
                    val parentMethod = parent?.declaredMethods?.singleOrNull { it.name == request.methodName }
                        ?: return@withBoundCompilationUnit false
                    !stagedBinding.isRecovered && stagedBinding.key == ownerBinding.key &&
                        parent.qualifiedName == qualified(packageName, request.superclassName) &&
                        !parent.isRecovered && !parentMethod.isRecovered && Modifier.isPublic(parentMethod.modifiers) &&
                        isInt(parentMethod.returnType) && parentMethod.parameterTypes.size == binding.parameterTypes.size &&
                        parentMethod.parameterTypes.zip(binding.parameterTypes).all { (left, right) -> left.isEqualTo(right) } &&
                        stagedBinding.declaredMethods.none { it.name == request.methodName }
                }
                stagedOwner is JdtBoundCompilationUnitResolution.Available && stagedOwner.value
            }
        }.getOrDefault(false)
        if (!stagedProof) {
            return refused(snapshot, "java.extractSuperclass.stagedValidationFailed", "Staged JDT bindings do not prove that the selected method moved to the generated superclass.")
        }
        val after = runCatching { adapter.diagnostics(staged) }.getOrElse {
            return refused(snapshot, "java.extractSuperclass.diagnosticsUnavailable", "Staged Java diagnostics are unavailable.")
        }
        val priorErrors = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticIdentity).toSet()
        val introducedErrors = after.filter { it.severity == Diagnostic.Severity.ERROR }
            .map(::diagnosticIdentity).filterNot(priorErrors::contains)
        if (introducedErrors.isNotEmpty()) {
            return refused(snapshot, "java.extractSuperclass.diagnosticsFailed", "Extracting the superclass introduces ${introducedErrors.size} Java diagnostic error(s).")
        }
        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.94,
            requiresUserApproval = true,
            summary = "Move public method '${request.methodName}' into ${qualified(packageName, request.superclassName)} and make ${request.typeSymbol} extend it.",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
            diagnosticsBefore = before,
            diagnosticsAfterPreview = after,
            warnings = listOf("The public method moves to a new superclass; reflection, declared-member enumeration, and external type consumers may observe the hierarchy change."),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun isMovableIntExpression(expression: Expression, method: org.eclipse.jdt.core.dom.IMethodBinding): Boolean = when (expression) {
        is NumberLiteral -> expression.resolveTypeBinding()?.let(::isInt) == true
        is SimpleName -> {
            val variable = expression.resolveBinding() as? IVariableBinding
            variable != null && variable.isParameter && variable.declaringMethod?.methodDeclaration?.key == method.methodDeclaration.key &&
                isInt(variable.type) && !variable.isRecovered
        }
        is ParenthesizedExpression -> isMovableIntExpression(expression.expression, method)
        is PrefixExpression -> expression.operator in setOf(PrefixExpression.Operator.PLUS, PrefixExpression.Operator.MINUS) &&
            isMovableIntExpression(expression.operand, method) && expression.resolveTypeBinding()?.let(::isInt) == true
        is InfixExpression -> expression.operator in setOf(
            InfixExpression.Operator.PLUS, InfixExpression.Operator.MINUS, InfixExpression.Operator.TIMES,
            InfixExpression.Operator.DIVIDE, InfixExpression.Operator.REMAINDER,
        ) && expression.extendedOperands().isEmpty() && isMovableIntExpression(expression.leftOperand, method) &&
            isMovableIntExpression(expression.rightOperand, method) && expression.resolveTypeBinding()?.let(::isInt) == true
        else -> false
    }

    private fun isInt(binding: ITypeBinding): Boolean = binding.isPrimitive && binding.name == "int"

    private fun qualified(packageName: String, name: String) = if (packageName.isBlank()) name else "$packageName.$name"

    private fun validIdentifier(name: String): Boolean =
        name.isNotBlank() && SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name) && name != "_"

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
        const val OPERATION = "extractSuperclass"
    }
}
