package org.refactorkit.java

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.PlanId
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Symbol
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/**
 * Generates a [PatchPlan] that renames Java types. Clean JDT binding evidence
 * drives semantic edits; top-level types retain the existing lexical fallback.
 * Nested types require clean JDT evidence because a lexical fallback cannot
 * safely distinguish the nested declaration from same-named types.
 */
class JavaRenameClassPlanner(private val adapter: JavaLanguageAdapter) {

    fun preview(snapshot: ProjectSnapshot, symbolFqn: String, newSimpleName: String): PatchPlan {
        val requestedOldSimple = JavaPackageUtil.simpleName(symbolFqn)

        // Validate target name
        if (!isValidJavaIdentifier(newSimpleName)) {
            return refused(snapshot, "renameClass", "Invalid Java identifier: $newSimpleName")
        }
        if (requestedOldSimple == newSimpleName) {
            return refused(snapshot, "renameClass", "Old and new type names are the same: $requestedOldSimple")
        }

        // The language-neutral index currently contains top-level types only. JDT
        // supplies the exact nested declaration identity without widening that index.
        val index = adapter.buildSymbols(snapshot)
        val symbol = index.symbols.find {
            it.id.value == symbolFqn && it.kind in RENAMEABLE_KINDS
        }
        val semanticAnalysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        val jdtTarget = semanticAnalysis?.symbols?.singleOrNull { candidate ->
            candidate.qualifiedName == symbolFqn && candidate.kind in JDT_RENAMEABLE_KINDS
        }
        val nestedTarget = jdtTarget?.takeIf { it.ownerQualifiedName != null }
        if (symbol == null && nestedTarget == null) {
            return refused(snapshot, "renameClass", "Symbol not found or not a renameable type: $symbolFqn")
        }
        val oldSimple = jdtTarget?.simpleName ?: symbol!!.name
        if (oldSimple == newSimpleName) {
            return refused(snapshot, "renameClass", "Old and new type names are the same: $oldSimple")
        }
        val oldPkg = JavaPackageUtil.packageOf(symbolFqn).takeIf { nestedTarget == null }.orEmpty()
        val newFqn = nestedTarget?.ownerQualifiedName?.let { "$it.$newSimpleName" }
            ?: JavaPackageUtil.fqn(oldPkg, newSimpleName)
        if (nestedTarget != null && (
                semanticAnalysis?.warnings?.isNotEmpty() != false ||
                    nestedTarget.recovered || nestedTarget.bindingKey.isNullOrBlank()
                )
        ) {
            return refused(snapshot, "renameClass", "Nested type rename requires clean JDT binding evidence: $symbolFqn")
        }

        val declarationPath = symbol?.location?.path ?: nestedTarget!!.path
        val declarationFile = snapshot.files.find { it.path == declarationPath }
            ?: return refused(snapshot, "renameClass", "Declaration file not found: $declarationPath")
        JavaGeneratedSourcePolicy.reason(declarationFile)?.let { reason ->
            return refused(snapshot, "renameClass", "Generated source cannot be rewritten: ${declarationFile.path} ($reason)")
        }
        val newFilePath = if (nestedTarget == null) {
            declarationFile.path.resolveSibling("$newSimpleName.java")
        } else {
            null
        }
        val targetExists = if (nestedTarget != null) {
            semanticAnalysis!!.symbols.any { it.qualifiedName == newFqn && it.path == declarationFile.path }
        } else {
            index.symbols.any { it.id.value == newFqn } || snapshot.files.any { it.path == newFilePath }
        }
        if (targetExists) {
            val targetDetail = newFilePath?.let { " ($it)" }.orEmpty()
            return refused(snapshot, "renameClass", "Rename target already exists: $newFqn$targetDetail")
        }

        val jdtEvidence = buildJdtRenameEvidence(semanticAnalysis, symbolFqn, newSimpleName, declarationFile.path)
        if (nestedTarget != null && jdtEvidence == null) {
            return refused(snapshot, "renameClass", "Nested type rename requires clean JDT binding evidence: $symbolFqn")
        }
        jdtEvidence?.let { evidence ->
            val frameworkAssessment = JavaFrameworkDetector.assess(declarationFile)
            val affectedPaths = evidence.modifications.mapTo(mutableSetOf()) { it.path }
            affectedPaths.add(declarationFile.path)
            if (newFilePath != null) affectedPaths.add(newFilePath)
            val warnings = mutableListOf(
                "JDT type binding selected $symbolFqn; declaration, constructor, and reference edits use exact JDT source ranges.",
                "String literals and comments are NOT scanned. Reflection and annotation processor output require manual review.",
            )
            if (nestedTarget != null) {
                warnings += "Nested type rename keeps the enclosing source file at its existing path."
            }
            warnings += frameworkAssessment.warnings("renameClass")
            if (affectedPaths.size > 10) warnings += "Large rename: ${affectedPaths.size} files affected."
            return PatchPlan(
                operation = "renameClass",
                status = PatchStatus.PREVIEW,
                snapshotHash = snapshot.hash,
                confidence = 0.96,
                requiresUserApproval = true,
                summary = "Rename $oldSimple → $newSimpleName (FQN: $newFqn) using JDT binding evidence. ${affectedPaths.size} file(s) affected.",
                affectedFiles = affectedPaths,
                workspaceEdit = WorkspaceEdit(
                    evidence.modifications + listOfNotNull(newFilePath?.let { FileEdit.Rename(declarationFile.path, it) }),
                ),
                warnings = warnings,
                riskLevel = when {
                    frameworkAssessment.hasFindings -> RiskLevel.HIGH
                    affectedPaths.size > 10 -> RiskLevel.MEDIUM
                    else -> RiskLevel.LOW
                },
                evidence = RefactoringEvidence.JDT_BINDING,
            )
        }

        val edits = mutableListOf<FileEdit>()
        val affectedPaths = mutableSetOf<Path>()
        val warnings = mutableListOf<String>()

        // 1. Modify the declaration file (class decl + constructor refs + local refs)
        val declModify = buildModify(declarationFile, oldSimple, newSimpleName, oldFqn = symbolFqn, newFqn = newFqn, replaceSimple = true)
        if (declModify.textEdits.isNotEmpty()) {
            edits += declModify
            affectedPaths.add(declarationFile.path)
        }

        // 2. Rename the source file itself
        val topLevelNewFilePath = newFilePath
            ?: return refused(snapshot, "renameClass", "Nested type rename requires clean JDT binding evidence: $symbolFqn")
        edits += FileEdit.Rename(declarationFile.path, topLevelNewFilePath)
        affectedPaths.add(declarationFile.path)
        affectedPaths.add(topLevelNewFilePath)

        // 3. Update all other Java files
        for (file in snapshot.files) {
            if (file.path == declarationFile.path || file.languageId != "java") continue

            val filePackage = JavaPackageUtil.extractPackage(file.content)
            val hasDirectImport = file.content.contains("import $symbolFqn;")
            val hasFqnUsage = file.content.contains(symbolFqn)
            val isSamePackage = filePackage == oldPkg && oldPkg.isNotEmpty()
            val replaceSimple = hasDirectImport || isSamePackage

            if (!hasDirectImport && !hasFqnUsage && !isSamePackage) continue

            val modify = buildModify(file, oldSimple, newSimpleName, oldFqn = symbolFqn, newFqn = newFqn, replaceSimple = replaceSimple)
            if (modify.textEdits.isNotEmpty()) {
                edits += modify
                affectedPaths.add(file.path)
            }
        }

        // 4. Compute risk and emit standard/framework-aware warnings
        val frameworkAssessment = JavaFrameworkDetector.assess(declarationFile)
        val riskLevel = when {
            frameworkAssessment.hasFindings -> RiskLevel.HIGH
            affectedPaths.size > 10 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        warnings += "JDT type-binding evidence was unavailable or not clean; rename uses lexical fallback. Review carefully."
        warnings += "String literals and comments are NOT scanned. Reflection and annotation processor output require manual review."
        warnings += frameworkAssessment.warnings("renameClass")
        if (affectedPaths.size > 10) warnings += "Large rename: ${affectedPaths.size} files affected."

        return PatchPlan(
            operation = "renameClass",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.92,
            requiresUserApproval = true,
            summary = "Rename $oldSimple → $newSimpleName (FQN: $newFqn). ${affectedPaths.size} file(s) affected.",
            affectedFiles = affectedPaths,
            workspaceEdit = WorkspaceEdit(edits),
            warnings = warnings,
            riskLevel = riskLevel,
            evidence = RefactoringEvidence.LEXICAL_FALLBACK,
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun buildJdtRenameEvidence(
        analysis: JdtJavaSemanticAnalysisResult?,
        symbolFqn: String,
        newSimpleName: String,
        declarationPath: Path,
    ): JdtRenameEvidence? {
        analysis ?: return null
        if (analysis.warnings.isNotEmpty()) return null
        val target = analysis.symbols.singleOrNull { symbol ->
            symbol.qualifiedName == symbolFqn && symbol.kind in JDT_RENAMEABLE_KINDS
        } ?: return null
        if (target.path != declarationPath || target.recovered) return null
        val bindingKey = target.bindingKey ?: return null
        val editsByPath = linkedMapOf<Path, MutableList<TextEdit>>()
        fun add(path: Path, range: SourceRange) {
            editsByPath.getOrPut(path) { mutableListOf() } += TextEdit(range, newSimpleName)
        }
        add(target.path, target.sourceRange)
        analysis.symbols
            .filter { symbol ->
                symbol.kind == JdtJavaSemanticSymbolKind.CONSTRUCTOR &&
                    symbol.ownerQualifiedName == symbolFqn &&
                    symbol.path == declarationPath
            }
            .forEach { add(it.path, it.sourceRange) }
        analysis.references
            .filter { it.bindingKey == bindingKey }
            .forEach { add(it.path, it.sourceRange) }
        val modifications = editsByPath.map { (path, textEdits) ->
            FileEdit.Modify(
                path,
                textEdits.distinctBy {
                    "${it.range.start.line}:${it.range.start.character}:${it.range.end.line}:${it.range.end.character}"
                }.sortedWith(compareBy({ it.range.start.line }, { it.range.start.character })),
            )
        }
        return JdtRenameEvidence(modifications)
    }

    private data class JdtRenameEvidence(val modifications: List<FileEdit.Modify>)

    private fun buildModify(
        file: SourceFile,
        oldSimple: String,
        newSimple: String,
        oldFqn: String,
        newFqn: String,
        replaceSimple: Boolean,
    ): FileEdit.Modify {
        val content = file.content
        val textEdits = mutableListOf<TextEdit>()
        val coveredOffsets = mutableSetOf<Int>()

        // Replace FQN first (e.g. com.example.UserManager → com.example.AccountManager)
        for (range in JavaLexer.findOccurrences(content, oldFqn)) {
            textEdits += makeEdit(content, range, newFqn)
            for (offset in range) coveredOffsets += offset
        }

        // Replace simple name where applicable, skipping already-covered FQN positions
        if (replaceSimple) {
            for (range in JavaLexer.findOccurrences(content, oldSimple)) {
                if (range.first !in coveredOffsets) {
                    textEdits += makeEdit(content, range, newSimple)
                }
            }
        }

        val sorted = textEdits.sortedWith(compareBy({ it.range.start.line }, { it.range.start.character }))
        return FileEdit.Modify(file.path, sorted)
    }

    private fun makeEdit(content: String, range: IntRange, replacement: String): TextEdit {
        val start = TextEdits.positionForOffset(content, range.first)
        val end = TextEdits.positionForOffset(content, range.last + 1)
        return TextEdit(SourceRange(start, end), replacement)
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

    private fun isValidJavaIdentifier(name: String): Boolean {
        if (name.isEmpty()) return false
        if (!name[0].isLetter() && name[0] != '_' && name[0] != '$') return false
        return name.all { JavaLexer.isIdentChar(it) }
    }

    companion object {
        private val RENAMEABLE_KINDS = setOf(
            Symbol.Kind.CLASS,
            Symbol.Kind.INTERFACE,
            Symbol.Kind.ENUM,
            Symbol.Kind.RECORD,
            Symbol.Kind.ANNOTATION,
        )
        private val JDT_RENAMEABLE_KINDS = setOf(
            JdtJavaSemanticSymbolKind.CLASS,
            JdtJavaSemanticSymbolKind.INTERFACE,
            JdtJavaSemanticSymbolKind.ENUM,
            JdtJavaSemanticSymbolKind.RECORD,
            JdtJavaSemanticSymbolKind.ANNOTATION,
        )
    }
}
