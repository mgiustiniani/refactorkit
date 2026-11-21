package org.refactorkit.java

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Symbol
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.owningBuildSourceRoots
import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ASTVisitor
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration
import org.eclipse.jdt.core.dom.Comment
import org.eclipse.jdt.core.dom.CompilationUnit
import org.eclipse.jdt.core.dom.ImportDeclaration
import org.eclipse.jdt.core.dom.Javadoc
import org.eclipse.jdt.core.dom.PackageDeclaration
import org.eclipse.jdt.core.dom.QualifiedName
import org.eclipse.jdt.core.dom.StringLiteral
import java.nio.file.Path

/** Typed request for the bounded exact-package Java migration. */
data class JavaMovePackageRequest(
    val fromPackage: String,
    val toPackage: String,
)

/** Preview planner shared by the Java provider and the legacy recipe step. */
class JavaMovePackagePlanner(
    private val adapter: JavaLanguageAdapter = JavaLanguageAdapter(),
) {
    fun preview(snapshot: ProjectSnapshot, request: JavaMovePackageRequest): PatchPlan {
        val fromPackage = request.fromPackage
        val toPackage = request.toPackage
        if (!isValidPackageName(fromPackage) || !isValidPackageName(toPackage)) {
            return refused(snapshot, "Invalid Java package name: '$fromPackage' -> '$toPackage'.")
        }
        if (fromPackage == toPackage) {
            return refused(snapshot, "Source and target packages are identical.")
        }

        val javaFiles = snapshot.files.filter {
            it.languageId == "java" && JavaGeneratedSourcePolicy.reason(it) == null
        }.sortedBy { it.path.toString() }
        val units = linkedMapOf<SourceFile, CompilationUnit>()
        for (file in javaFiles) {
            val attributes = snapshot.owningBuildSourceRoots(file.path).singleOrNull()?.sourceSet?.attributes
            val sourceLevel = when (val decision = JavaProjectSourceLevelPolicy.resolve(
                attributes?.get("java.release"), attributes?.get("java.sourceLevel"),
            )) {
                is JavaProjectSourceLevelDecision.Supported -> decision.sourceLevel
                is JavaProjectSourceLevelDecision.Refused -> return refused(snapshot, decision.message, decision.code)
            }
            units[file] = parse(file, sourceLevel)
        }
        val movedFiles = javaFiles.filter { units.getValue(it).`package`?.name?.fullyQualifiedName == fromPackage }
        if (movedFiles.isEmpty()) {
            return PatchPlan(
                operation = OPERATION,
                snapshotHash = snapshot.hash,
                confidence = 1.0,
                summary = "No exact-package Java compilation units to process.",
                affectedFiles = emptySet(),
                workspaceEdit = WorkspaceEdit(),
                evidence = RefactoringEvidence.STRUCTURAL,
            )
        }

        val targetBySource = linkedMapOf<Path, Path>()
        for (file in movedFiles) {
            val target = packageTargetPath(file.path, fromPackage, toPackage)
                ?: return refused(snapshot, "Cannot safely derive a package path for ${file.path}; its path does not match $fromPackage.")
            targetBySource[file.path.normalize()] = target
        }
        val existing = snapshot.files.map { it.path.normalize() }.toSet()
        val movingSources = targetBySource.keys
        val conflict = targetBySource.values.firstOrNull { target -> target in existing && target !in movingSources }
        if (conflict != null || targetBySource.values.toSet().size != targetBySource.size) {
            return refused(snapshot, "Target package conflict: ${conflict ?: "multiple units map to one target path"} already exists.")
        }

        val typeKinds = setOf(Symbol.Kind.CLASS, Symbol.Kind.INTERFACE, Symbol.Kind.ENUM, Symbol.Kind.RECORD, Symbol.Kind.ANNOTATION)
        val symbolKinds = adapter.buildSymbols(snapshot).symbols
            .filter {
                it.kind in typeKinds && it.location.path.normalize() in movingSources &&
                    it.id.value.substringBeforeLast('.') == fromPackage
            }
            .groupingBy { it.kind.name.lowercase() }
            .eachCount()
        val declaredTypes = movedFiles.flatMap { file ->
            units.getValue(file).types().filterIsInstance<AbstractTypeDeclaration>().map { it.name.identifier }
        }.toSet()
        val sourcePathLiterals = targetBySource.mapNotNull { (source, target) ->
            val oldPath = source.toString().replace('\\', '/')
            val newPath = target.toString().replace('\\', '/')
            val marker = SOURCE_ROOT.find(oldPath) ?: return@mapNotNull null
            val offset = marker.range.first + if (oldPath[marker.range.first] == '/') 1 else 0
            quoted(oldPath.substring(offset)) to quoted(newPath.substring(offset))
        }.toMap()
        val literalReplacements = sourcePathLiterals + (quoted(fromPackage) to quoted(toPackage))
        val modifications = mutableListOf<FileEdit>()
        for ((file, unit) in units) {
            val edits = try {
                collectEdits(file, unit, request, declaredTypes, literalReplacements)
            } catch (_: CommentedPackageName) {
                return refused(
                    snapshot, "A comment interrupts an affected Java package name in ${file.path}.",
                    "java.movePackage.commentedPackageName",
                )
            }
            if (edits.isNotEmpty()) modifications += FileEdit.Modify(file.path.normalize(), edits)
        }
        val renames = targetBySource.entries.sortedBy { it.key.toString() }
            .map { (source, target) -> FileEdit.Rename(source, target) }
        val workspaceEdit = WorkspaceEdit(modifications + renames)
        val packageInfoCount = movedFiles.count { it.path.fileName.toString() == "package-info.java" }
        val kindSummary = symbolKinds.toSortedMap().entries.joinToString(", ") { "${it.key}=${it.value}" }
            .ifBlank { "types=${declaredTypes.size}" }
        return PatchPlan(
            operation = OPERATION,
            snapshotHash = snapshot.hash,
            confidence = 0.92,
            summary = "Move package $fromPackage -> $toPackage: units=${movedFiles.size}, $kindSummary, package-info=$packageInfoCount.",
            affectedFiles = workspaceEdit.affectedFiles(),
            workspaceEdit = workspaceEdit,
            warnings = listOf(
                "The migration updates Java declarations, imports, qualified references, and exact package-name or moved-source-path string literals. It preserves comments, other literals, and build metadata.",
                "Generated sources and build outputs are excluded from mutation planning.",
            ),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    private fun refused(snapshot: ProjectSnapshot, reason: String, code: String? = null) = PatchPlan(
        operation = OPERATION,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        refusalCode = code,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        evidence = RefactoringEvidence.STRUCTURAL,
    )

    private fun isValidPackageName(packageName: String): Boolean =
        packageName.isNotBlank() && packageName.split('.').all { segment ->
            segment.isNotEmpty() &&
                (segment.first().isLetter() || segment.first() == '_' || segment.first() == '$') &&
                segment.all { it.isLetterOrDigit() || it == '_' || it == '$' }
        }

    private fun packageTargetPath(path: Path, fromPackage: String, toPackage: String): Path? {
        val parent = path.parent ?: return null
        val parentSegments = (0 until parent.nameCount).map { parent.getName(it).toString() }
        val oldSegments = fromPackage.split('.')
        if (parentSegments.size < oldSegments.size || parentSegments.takeLast(oldSegments.size) != oldSegments) return null
        var target = path.root ?: Path.of("")
        parentSegments.dropLast(oldSegments.size).forEach { target = target.resolve(it) }
        toPackage.split('.').forEach { target = target.resolve(it) }
        return target.resolve(path.fileName).normalize()
    }

    private fun parse(file: SourceFile, sourceLevel: Int): CompilationUnit {
        val compliance = if (sourceLevel == 8) JavaCore.VERSION_1_8 else sourceLevel.toString()
        return ASTParser.newParser(AST.getJLSLatest()).apply {
            setKind(ASTParser.K_COMPILATION_UNIT)
            setSource(file.content.toCharArray())
            setCompilerOptions(JavaCore.getOptions().also { JavaCore.setComplianceOptions(compliance, it) })
        }.createAST(null) as CompilationUnit
    }

    private fun collectEdits(
        file: SourceFile,
        unit: CompilationUnit,
        request: JavaMovePackageRequest,
        declaredTypes: Set<String>,
        literalReplacements: Map<String, String>,
    ): List<TextEdit> {
        val edits = mutableListOf<TextEdit>()
        val comments = unit.commentList.orEmpty().filterIsInstance<Comment>()
        fun replace(node: ASTNode, value: String) {
            val start = node.startPosition
            val end = start + node.length
            if (comments.any { comment ->
                    comment.startPosition < end && start < comment.startPosition + comment.length
                }) throw CommentedPackageName()
            edits += TextEdit(
                SourceRange(
                    TextEdits.positionForOffset(file.content, start),
                    TextEdits.positionForOffset(file.content, end),
                ),
                value,
            )
        }
        unit.accept(object : ASTVisitor() {
            override fun visit(node: PackageDeclaration): Boolean {
                if (node.name.fullyQualifiedName == request.fromPackage) replace(node.name, request.toPackage)
                return true
            }

            override fun visit(node: ImportDeclaration): Boolean {
                if (!node.isStatic && node.isOnDemand && node.name.fullyQualifiedName == request.fromPackage) {
                    replace(node.name, request.toPackage)
                    return false
                }
                return true
            }

            override fun visit(node: QualifiedName): Boolean {
                val outer = generateSequence(node as ASTNode) { it.parent as? QualifiedName }.last()
                if (outer.parent is PackageDeclaration) return false
                if (node.qualifier.fullyQualifiedName == request.fromPackage && node.name.identifier in declaredTypes) {
                    replace(node.qualifier, request.toPackage)
                    return false
                }
                return true
            }

            override fun visit(node: StringLiteral): Boolean {
                val spelling = file.content.substring(node.startPosition, node.startPosition + node.length)
                literalReplacements[spelling]?.let { replace(node, it) }
                return false
            }

            override fun visit(node: Javadoc): Boolean = false
        })
        return edits
    }

    private fun quoted(value: String): String = "\"$value\""

    private class CommentedPackageName : RuntimeException()

    companion object {
        const val OPERATION = "movePackage"
        private val SOURCE_ROOT = Regex("(?:^|/)src/(?:main|test)/java/")
    }
}
