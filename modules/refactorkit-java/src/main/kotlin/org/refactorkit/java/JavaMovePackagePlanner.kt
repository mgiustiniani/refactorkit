package org.refactorkit.java

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Symbol
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
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

        val packageDeclaration = Regex("(?m)^\\s*package\\s+${Regex.escape(fromPackage)}\\s*;")
        val movedFiles = snapshot.files.filter { file ->
            file.languageId == "java" && packageDeclaration.containsMatchIn(file.content) &&
                JavaGeneratedSourcePolicy.reason(file) == null
        }.sortedBy { it.path.toString() }
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
        val declaredTypes = movedFiles.flatMap(::topLevelTypeNames).toSortedSet()
        val qualifiedNames = declaredTypes.map { "$fromPackage.$it" to "$toPackage.$it" }
        val sourcePathReferences = targetBySource.mapNotNull { (source, target) ->
            val oldPath = source.toString().replace('\\', '/')
            val newPath = target.toString().replace('\\', '/')
            val marker = Regex("(?:^|/)src/(?:main|test)/java/").find(oldPath) ?: return@mapNotNull null
            val offset = marker.range.first + if (oldPath[marker.range.first] == '/') 1 else 0
            oldPath.substring(offset) to newPath.substring(offset)
        }
        val wildcardImport = Regex("(?m)^(\\s*import\\s+(?:static\\s+)?)${Regex.escape(fromPackage)}\\.(\\*\\s*;)")
        val modifications = mutableListOf<FileEdit>()
        for (file in snapshot.files.sortedBy { it.path.toString() }) {
            if (file.languageId != "java" || JavaGeneratedSourcePolicy.reason(file) != null) continue
            var after = file.content
            if (file.path.normalize() in movingSources) after = packageDeclaration.replaceFirst(after, "package $toPackage;")
            qualifiedNames.forEach { (oldName, newName) ->
                after = after.replace(
                    Regex("(?<![A-Za-z0-9_$])${Regex.escape(oldName)}(?![A-Za-z0-9_$])"),
                    newName,
                )
            }
            sourcePathReferences.forEach { (oldPath, newPath) -> after = after.replace(oldPath, newPath) }
            after = after.replace("\"$fromPackage\"", "\"$toPackage\"")
            after = wildcardImport.replace(after, "$1$toPackage.$2")
            fullFileReplacement(file.path.normalize(), file.content, after)?.let(modifications::add)
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
                "Java compilation units, imports, fully qualified references, exact package-name literals, and exact moved-unit source-path literals are in scope; other strings and build metadata are not migrated.",
                "Generated sources and build outputs are excluded from mutation planning.",
            ),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    private fun refused(snapshot: ProjectSnapshot, reason: String) = PatchPlan(
        operation = OPERATION,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
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

    private fun topLevelTypeNames(file: org.refactorkit.core.SourceFile): List<String> =
        Regex("(?m)^(?:\\s*(?:public|protected|private|abstract|final|sealed|non-sealed|static|strictfp)\\s+)*" +
            "(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)")
            .findAll(file.content)
            .map { it.groupValues[1] }
            .toList()

    private fun fullFileReplacement(path: Path, before: String, after: String): FileEdit.Modify? =
        if (before == after) null else FileEdit.Modify(
            path,
            listOf(TextEdit(
                SourceRange(SourcePosition(0, 0), TextEdits.positionForOffset(before, before.length)),
                after,
            )),
        )

    companion object {
        const val OPERATION = "movePackage"
    }
}
