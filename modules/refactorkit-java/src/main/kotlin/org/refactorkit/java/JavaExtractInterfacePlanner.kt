package org.refactorkit.java

import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.ITypeBinding
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.Modifier
import org.eclipse.jdt.core.dom.PrimitiveType
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

data class JavaExtractInterfaceRequest(
    val file: Path,
    val typeSymbol: String,
    val interfaceName: String,
    val methodName: String,
)

/**
 * First managed extract-interface cell. It extracts one public instance method
 * with primitive parameter and return types from a top-level class that has no
 * existing hierarchy. Every broader shape refuses before a workspace edit.
 */
class JavaExtractInterfacePlanner(private val adapter: JavaLanguageAdapter) {
    fun preview(snapshot: ProjectSnapshot, request: JavaExtractInterfaceRequest): PatchPlan {
        if (!validIdentifier(request.interfaceName)) {
            return refused(snapshot, "java.extractInterface.invalidName", "The requested interface name is not a valid Java identifier.")
        }
        if (!validIdentifier(request.methodName)) {
            return refused(snapshot, "java.extractInterface.invalidMethod", "A valid Java method name is required.")
        }
        val file = snapshot.files.singleOrNull { it.path.normalize() == request.file.normalize() }
            ?: return refused(snapshot, "java.extractInterface.fileUnavailable", "The selected Java source is absent or ambiguous in the snapshot.")
        if (file.languageId != "java") {
            return refused(snapshot, "java.extractInterface.languageUnsupported", "Extract interface supports Java source only.")
        }
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "java.extractInterface.generatedSource", "Generated Java source cannot be changed: $reason")
        }
        val before = runCatching { adapter.diagnostics(snapshot) }.getOrElse {
            return refused(snapshot, "java.extractInterface.diagnosticsUnavailable", "Java diagnostics are unavailable for the selected snapshot.")
        }
        val resolution = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(snapshot, file.path) { unit, _ ->
            previewBound(snapshot, file.path, request, before, unit)
        }
        return when (resolution) {
            is JdtBoundCompilationUnitResolution.Available -> resolution.value
            is JdtBoundCompilationUnitResolution.Refused -> refused(
                snapshot,
                "java.extractInterface.analysisUnavailable",
                "A complete SDK-bound Java source-set environment is unavailable for the selected type.",
            )
        }
    }

    private fun previewBound(
        snapshot: ProjectSnapshot,
        filePath: Path,
        request: JavaExtractInterfaceRequest,
        before: List<Diagnostic>,
        unit: CompilationUnit,
    ): PatchPlan {
        val file = snapshot.files.single { it.path.normalize() == filePath.normalize() }
        val content = file.content
        val owner = unit.types().asSequence().filterIsInstance<TypeDeclaration>()
            .singleOrNull { it.resolveBinding()?.qualifiedName == request.typeSymbol }
            ?: return refused(snapshot, "java.extractInterface.typeUnavailable", "The symbol must identify exactly one top-level JDT-bound class in the selected file.")
        val ownerBinding = owner.resolveBinding()
            ?: return refused(snapshot, "java.extractInterface.bindingIncomplete", "The selected class has no JDT type binding.")
        if (owner.parent !== unit || owner.isInterface || ownerBinding.isRecovered || ownerBinding.key.isNullOrBlank() ||
            ownerBinding.qualifiedName != request.typeSymbol || unit.types().size != 1 ||
            owner.superclassType != null || owner.superInterfaceTypes().isNotEmpty() || owner.typeParameters().isNotEmpty() ||
            owner.modifiers().any { it.toString() == "sealed" || it.toString() == "non-sealed" }) {
            return refused(snapshot, "java.extractInterface.hierarchyUnsupported", "Only one top-level ordinary class without an existing superclass, interfaces, or type parameters is supported.")
        }

        val methods = owner.bodyDeclarations().filterIsInstance<MethodDeclaration>()
            .filter { it.name.identifier == request.methodName }
        val method = methods.singleOrNull()
            ?: return refused(snapshot, "java.extractInterface.methodAmbiguous", "The method name must identify exactly one declaration on the selected class.")
        val methodBinding = method.resolveBinding()
            ?: return refused(snapshot, "java.extractInterface.bindingIncomplete", "The selected method has no JDT method binding.")
        if (method.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0 || methodBinding.isRecovered ||
            methodBinding.key.isNullOrBlank() || methodBinding.declaringClass?.key != ownerBinding.key) {
            return refused(snapshot, "java.extractInterface.bindingIncomplete", "The selected method binding is incomplete or belongs to another type.")
        }
        if (method.isConstructor || method.body == null || !Modifier.isPublic(methodBinding.modifiers) ||
            Modifier.isStatic(methodBinding.modifiers) || method.typeParameters().isNotEmpty() ||
            method.thrownExceptionTypes().isNotEmpty() || method.isVarargs || method.modifiers().any { it is org.eclipse.jdt.core.dom.Annotation } ||
            method.parameters().filterIsInstance<SingleVariableDeclaration>().any { parameter ->
                parameter.type !is PrimitiveType || parameter.extraDimensions().isNotEmpty() ||
                    parameter.modifiers().any { it is org.eclipse.jdt.core.dom.Annotation }
            }) {
            return refused(snapshot, "java.extractInterface.signatureUnsupported", "Only a public concrete instance method without annotations, type parameters, varargs, or declared exceptions is supported.")
        }
        val returnType = method.returnType2
        val returnBinding = methodBinding.returnType
        if ((returnType !is PrimitiveType && returnBinding?.name != "void") || returnBinding == null ||
            returnBinding.isRecovered || (!returnBinding.isPrimitive && returnBinding.name != "void") ||
            methodBinding.parameterTypes.size != method.parameters().size ||
            methodBinding.parameterTypes.any { it.isRecovered || !it.isPrimitive }) {
            return refused(snapshot, "java.extractInterface.signatureUnsupported", "This first extract-interface cell supports primitive return and parameter types only.")
        }

        val packageName = unit.`package`?.name?.fullyQualifiedName.orEmpty()
        val sourceRoot = snapshot.owningBuildSourceRoots(file.path).singleOrNull()
            ?: return refused(snapshot, "java.extractInterface.sourceRootUnavailable", "The selected class has no unique effective source-set root.")
        if (sourceRoot.generated || sourceRoot.modelStatus != org.refactorkit.core.BuildModelStatus.AVAILABLE) {
            return refused(snapshot, "java.extractInterface.sourceRootUnavailable", "Extract interface requires an available non-generated Java source set.")
        }
        val workspaceRoot = snapshot.workspace.root.toAbsolutePath().normalize()
        val declaredRoot = sourceRoot.root.normalize()
        val relativeRoot = if (declaredRoot.isAbsolute) {
            if (!declaredRoot.startsWith(workspaceRoot)) {
                return refused(snapshot, "java.extractInterface.targetOutsideWorkspace", "The selected source root is outside the workspace.")
            }
            workspaceRoot.relativize(declaredRoot)
        } else declaredRoot
        val packagePath = if (packageName.isBlank()) Path.of("") else Path.of(packageName.replace('.', '/'))
        val interfacePath = relativeRoot.resolve(packagePath).resolve("${request.interfaceName}.java").normalize()
        if (interfacePath.isAbsolute || interfacePath.startsWith("..")) {
            return refused(snapshot, "java.extractInterface.targetOutsideWorkspace", "The generated interface path escapes the workspace.")
        }
        if (snapshot.trackedFiles.any { it.path.normalize() == interfacePath }) {
            return refused(snapshot, "java.extractInterface.nameConflict", "The requested interface file already exists.")
        }
        val typeNamePattern = Regex("\\b(?:class|interface|enum|record)\\s+${Regex.escape(request.interfaceName)}\\b|@interface\\s+${Regex.escape(request.interfaceName)}\\b")
        if (snapshot.files.any { candidate ->
                candidate.path.normalize() != file.path.normalize() && candidate.languageId == "java" &&
                    JavaPackageUtil.extractPackage(candidate.content) == packageName && typeNamePattern.containsMatchIn(candidate.content)
            }) {
            return refused(snapshot, "java.extractInterface.nameConflict", "A same-package type already uses the requested interface name.")
        }

        val openingBrace = content.indexOf('{', owner.name.startPosition + owner.name.length)
        if (openingBrace < 0 || openingBrace >= owner.startPosition + owner.length - 1 ||
            !content.substring(owner.name.startPosition + owner.name.length, openingBrace).isBlank()) {
            return refused(snapshot, "java.extractInterface.declarationLayoutUnsupported", "The selected class header does not have a simple insertion point for its interface clause.")
        }
        val parameters = method.parameters().filterIsInstance<SingleVariableDeclaration>()
        val parameterText = parameters.joinToString(", ") { "${it.type} ${it.name.identifier}" }
        val lineEnding = if (content.contains("\r\n")) "\r\n" else "\n"
        val packagePrefix = if (packageName.isBlank()) "" else "package $packageName;$lineEnding$lineEnding"
        val interfaceText = buildString {
            append(packagePrefix)
            append("public interface ${request.interfaceName} {").append(lineEnding)
            append("    ${returnType} ${request.methodName}($parameterText);").append(lineEnding)
            append('}').append(lineEnding)
        }
        val classEdit = TextEdit(TextEdits.rangeForOffset(content, openingBrace, 0), "implements ${request.interfaceName} ")
        val edit = WorkspaceEdit(listOf(
            FileEdit.Create(interfacePath, interfaceText),
            FileEdit.Modify(file.path, listOf(classEdit)),
        ))
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, edit) }.getOrElse {
            return refused(snapshot, "java.extractInterface.editInvalid", "The interface extraction edits cannot be staged against the captured workspace.")
        }
        val stagedProof = runCatching {
            adapter.withDiagnosticsOverlay(staged) { overlay ->
                val stagedOwner = JdtJavaSemanticAnalyzer().withBoundCompilationUnit(overlay, file.path) { stagedUnit, _ ->
                    val stagedClass = stagedUnit.types().filterIsInstance<TypeDeclaration>()
                        .singleOrNull { it.resolveBinding()?.key == ownerBinding.key }
                        ?: return@withBoundCompilationUnit false
                    val stagedClassBinding = stagedClass.resolveBinding() ?: return@withBoundCompilationUnit false
                    val declaredInterface = stagedClassBinding.interfaces.singleOrNull { it.qualifiedName == qualified(packageName, request.interfaceName) }
                        ?: return@withBoundCompilationUnit false
                    if (declaredInterface.isRecovered || !declaredInterface.isInterface) return@withBoundCompilationUnit false
                    val stagedMethod = stagedClass.bodyDeclarations().filterIsInstance<MethodDeclaration>()
                        .singleOrNull { it.resolveBinding()?.key == methodBinding.key }?.resolveBinding()
                        ?: return@withBoundCompilationUnit false
                    val interfaceMethod = declaredInterface.declaredMethods.singleOrNull { it.name == request.methodName }
                        ?: return@withBoundCompilationUnit false
                    !stagedClassBinding.isRecovered && stagedClassBinding.key == ownerBinding.key &&
                        !stagedMethod.isRecovered && stagedMethod.key == methodBinding.key &&
                        !interfaceMethod.isRecovered && stagedMethod.overrides(interfaceMethod) &&
                        stagedMethod.parameterTypes.size == interfaceMethod.parameterTypes.size &&
                        stagedMethod.parameterTypes.zip(interfaceMethod.parameterTypes).all { (left, right) -> left.isEqualTo(right) } &&
                        stagedMethod.returnType.isEqualTo(interfaceMethod.returnType)
                }
                stagedOwner is JdtBoundCompilationUnitResolution.Available && stagedOwner.value
            }
        }.getOrDefault(false)
        if (!stagedProof) {
            return refused(snapshot, "java.extractInterface.stagedValidationFailed", "Staged JDT bindings do not prove that the class implements the generated interface method.")
        }
        val after = runCatching { adapter.diagnostics(staged) }.getOrElse {
            return refused(snapshot, "java.extractInterface.diagnosticsUnavailable", "Staged Java diagnostics are unavailable.")
        }
        val priorErrors = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticIdentity).toSet()
        val introducedErrors = after.filter { it.severity == Diagnostic.Severity.ERROR }
            .map(::diagnosticIdentity).filterNot(priorErrors::contains)
        if (introducedErrors.isNotEmpty()) {
            return refused(snapshot, "java.extractInterface.diagnosticsFailed", "Extracting the interface introduces ${introducedErrors.size} Java diagnostic error(s).")
        }
        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.96,
            requiresUserApproval = true,
            summary = "Extract public method '${request.methodName}' into ${qualified(packageName, request.interfaceName)} and make ${request.typeSymbol} implement it.",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
            diagnosticsBefore = before,
            diagnosticsAfterPreview = after,
            warnings = listOf("The selected public method becomes part of a new source-level interface contract."),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

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
        const val OPERATION = "extractInterface"
    }
}
