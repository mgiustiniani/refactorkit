package org.refactorkit.java

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.Assignment
import org.eclipse.jdt.core.dom.Block
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.IVariableBinding
import org.eclipse.jdt.core.dom.MethodDeclaration
import org.eclipse.jdt.core.dom.NumberLiteral
import org.eclipse.jdt.core.dom.PostfixExpression
import org.eclipse.jdt.core.dom.PrefixExpression
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.TypeDeclaration
import org.eclipse.jdt.core.dom.VariableDeclarationFragment
import org.eclipse.jdt.core.dom.VariableDeclarationStatement
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildSourceSet
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
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.Files
import java.nio.file.Path

data class JavaInlineVariableRequest(
    val file: Path,
    val name: String,
)

/**
 * Conservative JDT-bound inline-variable cell. It supports one direct local
 * declaration initialized by a primitive-int literal and replaces only the
 * binding-matched read references. Any write, ambiguous binding, unsupported
 * scope, or source layout that could lose comments is refused.
 */
class JavaInlineVariablePlanner(private val adapter: JavaLanguageAdapter) {
    fun preview(snapshot: ProjectSnapshot, request: JavaInlineVariableRequest): PatchPlan {
        if (request.name.isBlank()) {
            return refused(snapshot, "java.inlineVariable.invalidRequest", "A local variable name is required.")
        }
        val file = snapshot.files.singleOrNull { it.path.normalize() == request.file.normalize() }
            ?: return refused(snapshot, "java.inlineVariable.fileUnavailable", "The selected Java file is absent or ambiguous in the snapshot.")
        if (file.languageId != "java") {
            return refused(snapshot, "java.inlineVariable.languageUnsupported", "Inline variable currently supports Java source only.")
        }
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "java.inlineVariable.generatedSource", "Generated Java source cannot be rewritten: $reason")
        }
        val before = runCatching { adapter.diagnostics(snapshot) }.getOrElse {
            return refused(snapshot, "java.inlineVariable.diagnosticsUnavailable", "Java diagnostics are unavailable for the selected snapshot.")
        }
        return withCompilationUnit(snapshot, file.path) { unit ->
            previewBoundLocal(snapshot, file.path, request.name, before, unit)
        } ?: refused(
            snapshot,
            "java.inlineVariable.analysisUnavailable",
            "A complete supported JDT source-set environment is unavailable for this local.",
        )
    }

    private fun previewBoundLocal(
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
                if (node.name.identifier == name) declarations += node
                return true
            }
        })
        val fragment = declarations.singleOrNull()
            ?: return refused(snapshot, "java.inlineVariable.declarationAmbiguous", "The selected local name must identify exactly one declaration in the Java file.")
        val statement = fragment.parent as? VariableDeclarationStatement
            ?: return refused(snapshot, "java.inlineVariable.scopeUnsupported", "Only a local variable statement can be inlined.")
        val block = statement.parent as? Block
            ?: return refused(snapshot, "java.inlineVariable.scopeUnsupported", "The declaration must be a direct statement of a method block.")
        val method = block.parent as? MethodDeclaration
            ?: return refused(snapshot, "java.inlineVariable.scopeUnsupported", "The declaration must be directly inside an ordinary method body.")
        if (method.body !== block || method.isConstructor || statement.fragments().size != 1 ||
            statement.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0 ||
            method.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0) {
            return refused(snapshot, "java.inlineVariable.scopeUnsupported", "Only one well-formed declaration fragment in an ordinary method body is supported.")
        }
        val methodBinding = method.resolveBinding()
            ?: return refused(snapshot, "java.inlineVariable.bindingIncomplete", "The containing method has no complete JDT binding.")
        val owner = method.parent as? TypeDeclaration
            ?: return refused(snapshot, "java.inlineVariable.scopeUnsupported", "The containing method must belong to an ordinary Java class.")
        val ownerBinding = owner.resolveBinding()
        if (methodBinding.isRecovered || methodBinding.key.isNullOrBlank() || owner.isInterface ||
            ownerBinding == null || ownerBinding.isRecovered || ownerBinding.key.isNullOrBlank()) {
            return refused(snapshot, "java.inlineVariable.bindingIncomplete", "The containing method or class binding is incomplete.")
        }
        val binding = fragment.resolveBinding()
            ?: return refused(snapshot, "java.inlineVariable.bindingIncomplete", "The local declaration has no JDT variable binding.")
        if (binding.isRecovered || binding.variableDeclaration.key.isNullOrBlank() || binding.isField || binding.isParameter) {
            return refused(snapshot, "java.inlineVariable.bindingIncomplete", "The declaration does not have a complete local-variable binding.")
        }
        val initializer = fragment.initializer as? NumberLiteral
            ?: return refused(snapshot, "java.inlineVariable.initializerUnsupported", "Only a primitive-int numeric literal initializer is supported.")
        if (initializer.flags and (ASTNode.MALFORMED or ASTNode.RECOVERED) != 0) {
            return refused(snapshot, "java.inlineVariable.initializerUnsupported", "The numeric literal initializer is malformed or recovered.")
        }
        val literalType = initializer.resolveTypeBinding()
        if (literalType == null || literalType.isRecovered || !literalType.isPrimitive || literalType.name != "int" ||
            initializer.resolveConstantExpressionValue() !is Int || binding.type.isRecovered ||
            !binding.type.isPrimitive || binding.type.name != "int") {
            return refused(snapshot, "java.inlineVariable.initializerUnsupported", "Only a binding-resolved primitive-int literal can be inlined.")
        }
        val sourceLine = removableDeclarationLine(content, statement)
            ?: return refused(snapshot, "java.inlineVariable.declarationLayoutUnsupported", "The declaration must occupy a comment-free line so removing it preserves surrounding source.")

        val declarationKey = binding.variableDeclaration.key
        val references = mutableListOf<SimpleName>()
        var hasWrite = false
        var hasIncompleteSameNameBinding = false
        method.body.accept(object : ASTVisitor() {
            override fun visit(node: SimpleName): Boolean {
                if (node === fragment.name || node.identifier != name) return true
                val resolvedBinding = node.resolveBinding()
                if (resolvedBinding == null || resolvedBinding.isRecovered || resolvedBinding.key.isNullOrBlank()) {
                    hasIncompleteSameNameBinding = true
                    return true
                }
                val referenceBinding = resolvedBinding as? IVariableBinding ?: return true
                if (referenceBinding.variableDeclaration.key != declarationKey || node === fragment.name) return true
                if (node.startPosition <= statement.startPosition + statement.length || isWriteReference(node)) {
                    hasWrite = true
                } else {
                    references += node
                }
                return true
            }
        })
        if (hasIncompleteSameNameBinding) {
            return refused(snapshot, "java.inlineVariable.bindingIncomplete", "Every same-named occurrence in the containing method must have a complete JDT binding.")
        }
        if (hasWrite) {
            return refused(snapshot, "java.inlineVariable.writeReferenceUnsupported", "A binding-matched write or pre-declaration reference prevents safe inlining.")
        }
        if (references.isEmpty()) {
            return refused(snapshot, "java.inlineVariable.noReads", "The selected local has no binding-matched reads to inline.")
        }

        val literalText = content.substring(initializer.startPosition, initializer.startPosition + initializer.length)
        val edits = buildList {
            references.forEach { reference ->
                add(TextEdit(TextEdits.rangeForOffset(content, reference.startPosition, reference.length), literalText))
            }
            add(TextEdit(TextEdits.rangeForOffset(content, sourceLine.first, sourceLine.second - sourceLine.first), ""))
        }
        val stagedContent = runCatching { TextEdits.apply(content, edits) }.getOrElse {
            return refused(snapshot, "java.inlineVariable.editInvalid", "The binding-matched edits overlap or do not fit the saved source.")
        }
        val staged = snapshot.copy(files = snapshot.files.map { source ->
            if (source.path.normalize() == file.path.normalize()) source.copy(content = stagedContent) else source
        })
        val stagedProof = withCompilationUnit(staged, file.path) { stagedUnit ->
            val stagedMethod = findMethod(stagedUnit, methodBinding.key) ?: return@withCompilationUnit false
            var selectedNameRemains = false
            stagedMethod.body?.accept(object : ASTVisitor() {
                override fun visit(node: VariableDeclarationFragment): Boolean {
                    if (node.name.identifier == name) selectedNameRemains = true
                    return true
                }

                override fun visit(node: SimpleName): Boolean {
                    val stagedBinding = node.resolveBinding() as? IVariableBinding
                    if (stagedBinding?.variableDeclaration?.key == declarationKey) selectedNameRemains = true
                    return true
                }
            })
            !selectedNameRemains
        }
        if (stagedProof != true) {
            return refused(snapshot, "java.inlineVariable.stagedValidationFailed", "Staged JDT bindings did not prove that the selected local was removed.")
        }
        val after = adapter.diagnostics(staged)
        val baselineErrors = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticIdentity).toSet()
        val introducedErrors = after.filter { it.severity == Diagnostic.Severity.ERROR }
            .map(::diagnosticIdentity).filterNot { it in baselineErrors }
        if (introducedErrors.isNotEmpty()) {
            return refused(snapshot, "java.inlineVariable.diagnosticsFailed", "Inlining introduces ${introducedErrors.size} diagnostic error(s).")
        }
        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.97,
            requiresUserApproval = true,
            summary = "Inline ${references.size} binding-matched read(s) of local '$name' using the primitive-int literal $literalText.",
            affectedFiles = setOf(file.path),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(file.path, edits))),
            diagnosticsBefore = before,
            diagnosticsAfterPreview = after,
            warnings = emptyList(),
            riskLevel = RiskLevel.LOW,
            evidence = RefactoringEvidence.JDT_BINDING,
        )
    }

    private fun removableDeclarationLine(content: String, statement: VariableDeclarationStatement): Pair<Int, Int>? {
        val start = statement.startPosition
        val end = start + statement.length
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

    private fun isWriteReference(name: SimpleName): Boolean {
        var node: ASTNode? = name
        while (node != null) {
            val parent = node.parent ?: return false
            if (parent is Assignment && isDescendantOrSelf(name, parent.leftHandSide)) return true
            if (parent is PrefixExpression &&
                (parent.operator == PrefixExpression.Operator.INCREMENT || parent.operator == PrefixExpression.Operator.DECREMENT) &&
                isDescendantOrSelf(name, parent.operand)) return true
            if (parent is PostfixExpression && isDescendantOrSelf(name, parent.operand)) return true
            if (parent is MethodDeclaration) return false
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

    private fun findMethod(unit: CompilationUnit, methodKey: String): MethodDeclaration? {
        var match: MethodDeclaration? = null
        unit.accept(object : ASTVisitor() {
            override fun visit(node: MethodDeclaration): Boolean {
                if (node.resolveBinding()?.key == methodKey) {
                    if (match != null) return false
                    match = node
                }
                return true
            }
        })
        return match
    }

    private fun diagnosticIdentity(diagnostic: Diagnostic): String =
        "${diagnostic.severity}|${diagnostic.code}|${diagnostic.message}|${diagnostic.location}"

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
        const val OPERATION = "inlineVariable"
    }
}
