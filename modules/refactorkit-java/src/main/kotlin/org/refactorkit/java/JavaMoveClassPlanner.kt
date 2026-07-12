package org.refactorkit.java

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Symbol
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Generates a [PatchPlan] that moves a Java class to a new package.
 *
 * Updates:
 * - `package` declaration in the moved file.
 * - File path (rename edit).
 * - All `import` statements referencing the old FQN.
 * - All FQN references in source files.
 * - Adds a new import in same-package files that previously used the simple name.
 */
class JavaMoveClassPlanner(private val adapter: JavaLanguageAdapter) {

    fun preview(snapshot: ProjectSnapshot, symbolFqn: String, targetPackage: String): PatchPlan {
        val oldPkg = JavaPackageUtil.packageOf(symbolFqn)
        val simpleName = JavaPackageUtil.simpleName(symbolFqn)
        val newFqn = JavaPackageUtil.fqn(targetPackage, simpleName)

        if (oldPkg == targetPackage) {
            return refused(snapshot, "moveClass", "Source and target packages are the same: $targetPackage")
        }
        if (!isValidPackageName(targetPackage)) {
            return refused(snapshot, "moveClass", "Invalid target package: $targetPackage")
        }

        val index = adapter.buildSymbols(snapshot)
        val symbol = index.symbols.find { it.id.value == symbolFqn && it.kind in MOVEABLE_KINDS }
            ?: return refused(snapshot, "moveClass", "Symbol not found or not a moveable type: $symbolFqn")

        val declarationFile = snapshot.files.find { it.path == symbol.location.path }
            ?: return refused(snapshot, "moveClass", "Declaration file not found: ${symbol.location.path}")
        JavaGeneratedSourcePolicy.reason(declarationFile)?.let { reason ->
            return refused(snapshot, "moveClass", "Generated source cannot be rewritten: ${declarationFile.path} ($reason)")
        }
        val newRelativePath = computeNewPath(declarationFile.path, oldPkg, targetPackage, simpleName)
        if (index.symbols.any { it.id.value == newFqn } || snapshot.files.any { it.path == newRelativePath }) {
            return refused(snapshot, "moveClass", "Move target already exists: $newFqn ($newRelativePath)")
        }
        val jdtReferencePaths = findJdtReferencePaths(snapshot, symbolFqn, declarationFile.path)

        val edits = mutableListOf<FileEdit>()
        val affectedPaths = mutableSetOf<Path>()
        val warnings = mutableListOf<String>()

        // 1. Update package declaration in the declaration file
        val updatedDecl = rewritePackageDeclaration(declarationFile, oldPkg, targetPackage)
        edits += updatedDecl
        affectedPaths.add(declarationFile.path)

        // 2. Rename (move) the file to the new package directory
        edits += FileEdit.Rename(declarationFile.path, newRelativePath)
        affectedPaths.add(declarationFile.path)
        affectedPaths.add(newRelativePath)

        // 3. Update all other files
        for (file in snapshot.files) {
            if (file.path == declarationFile.path || file.languageId != "java") continue

            val filePkg = JavaPackageUtil.extractPackage(file.content)
            val hasOldImport = file.content.contains("import $symbolFqn;")
            val hasFqn = file.content.contains(symbolFqn)
            val wasInSamePkg = filePkg == oldPkg && oldPkg.isNotEmpty()
            val nowInSamePkg = filePkg == targetPackage

            if (jdtReferencePaths != null) {
                if (file.path !in jdtReferencePaths) continue
            } else if (!hasOldImport && !hasFqn && !wasInSamePkg) {
                continue
            }

            val fileEdits = mutableListOf<TextEdit>()
            val fileContent = file.content

            val coveredOffsets = mutableSetOf<Int>()

            // Replace old import with new import (or remove if now in same package).
            // Mark the entire import so the nested FQN occurrence is not edited twice.
            if (hasOldImport) {
                val importText = "import $symbolFqn;"
                val newImportText = if (nowInSamePkg) "" else "import $newFqn;"
                for (range in JavaLexer.findOccurrences(fileContent, importText)) {
                    fileEdits += makeEdit(fileContent, range, newImportText)
                    range.forEach(coveredOffsets::add)
                }
            }

            // Replace old FQN references outside direct imports.
            if (hasFqn) {
                for (range in JavaLexer.findOccurrences(fileContent, symbolFqn)) {
                    if (range.first !in coveredOffsets) {
                        fileEdits += makeEdit(fileContent, range, newFqn)
                    }
                }
            }

            // Files that were in the same package now need an explicit import
            if (wasInSamePkg && !nowInSamePkg && !hasOldImport) {
                val insertOffset = insertImportOffset(fileContent)
                val pos = TextEdits.positionForOffset(fileContent, insertOffset)
                fileEdits += TextEdit(SourceRange(pos, pos), "import $newFqn;\n")
            }

            if (fileEdits.isNotEmpty()) {
                val sorted = fileEdits.sortedWith(compareBy({ it.range.start.line }, { it.range.start.character }))
                edits += FileEdit.Modify(file.path, sorted)
                affectedPaths.add(file.path)
            }
        }

        val frameworkAssessment = JavaFrameworkDetector.assess(declarationFile)
        warnings += if (jdtReferencePaths != null) {
            "JDT type binding selected ${jdtReferencePaths.size} referencing file(s); package/import/FQN edits are scoped to those files."
        } else {
            "JDT type-binding evidence was unavailable or not clean; move uses lexical file scoping. Review carefully."
        }
        warnings += "String literals and comments are NOT scanned. Reflection and annotation processor output require manual review."
        warnings += frameworkAssessment.warnings("moveClass")

        return PatchPlan(
            operation = "moveClass",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = if (jdtReferencePaths != null) 0.94 else 0.90,
            requiresUserApproval = true,
            summary = "Move $simpleName from $oldPkg → $targetPackage. ${affectedPaths.size} file(s) affected.",
            affectedFiles = affectedPaths,
            workspaceEdit = WorkspaceEdit(edits),
            warnings = warnings,
            riskLevel = if (frameworkAssessment.hasFindings) RiskLevel.HIGH else RiskLevel.MEDIUM,
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun findJdtReferencePaths(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        declarationPath: Path,
    ): Set<Path>? {
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        if (analysis.warnings.isNotEmpty()) return null
        val target = analysis.symbols.singleOrNull { symbol ->
            symbol.qualifiedName == symbolFqn && symbol.kind in JDT_MOVEABLE_KINDS
        } ?: return null
        val bindingKey = target.bindingKey ?: return null
        return analysis.references
            .asSequence()
            .filter { it.bindingKey == bindingKey && it.path != declarationPath }
            .map { it.path }
            .toSet()
    }

    private fun rewritePackageDeclaration(file: SourceFile, oldPkg: String, newPkg: String): FileEdit.Modify {
        val content = file.content
        val pkgRegex = Regex("""(?m)^(\s*package\s+)([\w.]+)(\s*;)""")
        val match = pkgRegex.find(content)
        return if (match != null) {
            val range = match.groups[2]!!.range
            val start = TextEdits.positionForOffset(content, range.first)
            val end = TextEdits.positionForOffset(content, range.last + 1)
            FileEdit.Modify(file.path, listOf(TextEdit(SourceRange(start, end), newPkg)))
        } else {
            // No package declaration — prepend one
            val pos = TextEdits.positionForOffset(content, 0)
            FileEdit.Modify(file.path, listOf(TextEdit(SourceRange(pos, pos), "package $newPkg;\n\n")))
        }
    }

    private fun computeNewPath(oldPath: Path, oldPkg: String, newPkg: String, simpleName: String): Path {
        // Heuristic: walk up from the file to find the source root, then rebuild.
        val oldPkgParts = if (oldPkg.isEmpty()) 0 else oldPkg.split('.').size
        var current: Path? = oldPath.parent
        repeat(oldPkgParts) { current = current?.parent }
        val sourceRoot = current ?: Paths.get(".")
        val newPkgPath = JavaPackageUtil.packageToPath(newPkg)
        return sourceRoot.resolve(newPkgPath).resolve("$simpleName.java")
    }

    private fun makeEdit(content: String, range: IntRange, replacement: String): TextEdit {
        val start = TextEdits.positionForOffset(content, range.first)
        val end = TextEdits.positionForOffset(content, range.last + 1)
        return TextEdit(SourceRange(start, end), replacement)
    }

    private fun insertImportOffset(content: String): Int {
        // Insert after existing package declaration, or at the top of the file
        val pkgMatch = Regex("""(?m)^package\s+[\w.]+\s*;""").find(content)
        return if (pkgMatch != null) pkgMatch.range.last + 1 else 0
    }

    private fun isValidPackageName(packageName: String): Boolean =
        packageName.isNotBlank() && packageName.split('.').all { segment ->
            segment.isNotEmpty() &&
                (segment.first().isLetter() || segment.first() == '_' || segment.first() == '$') &&
                segment.all(JavaLexer::isIdentChar)
        }

    private fun refused(snapshot: ProjectSnapshot, operation: String, reason: String) = PatchPlan(
        operation = operation,
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

    companion object {
        private val MOVEABLE_KINDS = setOf(
            Symbol.Kind.CLASS,
            Symbol.Kind.INTERFACE,
            Symbol.Kind.ENUM,
            Symbol.Kind.RECORD,
            Symbol.Kind.ANNOTATION,
        )
        private val JDT_MOVEABLE_KINDS = setOf(
            JdtJavaSemanticSymbolKind.CLASS,
            JdtJavaSemanticSymbolKind.INTERFACE,
            JdtJavaSemanticSymbolKind.ENUM,
            JdtJavaSemanticSymbolKind.RECORD,
            JdtJavaSemanticSymbolKind.ANNOTATION,
        )
    }
}
