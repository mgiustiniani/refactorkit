package org.refactorkit.java

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
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
data class JavaMoveClassPreview(
    val plan: PatchPlan,
    val targetAuthorityLease: JavaMoveClassTargetAuthorityLease?,
)

class JavaMoveClassPlanner(private val adapter: JavaLanguageAdapter) {

    fun preview(snapshot: ProjectSnapshot, symbolFqn: String, targetPackage: String): PatchPlan =
        previewWithAuthority(snapshot, symbolFqn, targetPackage).plan

    fun previewWithAuthority(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        targetPackage: String,
    ): JavaMoveClassPreview = previewResult(snapshot, symbolFqn, targetPackage)

    private fun previewResult(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        targetPackage: String,
    ): JavaMoveClassPreview {
        fun withoutAuthority(plan: PatchPlan) = JavaMoveClassPreview(plan, null)
        val oldPkg = JavaPackageUtil.packageOf(symbolFqn)
        val simpleName = JavaPackageUtil.simpleName(symbolFqn)
        val newFqn = JavaPackageUtil.fqn(targetPackage, simpleName)

        if (oldPkg == targetPackage) {
            return withoutAuthority(refused(snapshot, "moveClass", "Source and target packages are the same: $targetPackage"))
        }
        if (!isValidPackageName(targetPackage)) {
            return withoutAuthority(refused(snapshot, "moveClass", "Invalid target package: $targetPackage"))
        }

        // Structural Maven descriptor closure is a prerequisite, not a semantic-edit diagnostic.
        // Refuse before symbol analysis so selected descriptor loss cannot produce a plan or lease.
        val dependencyGraphFailures = snapshot.modules.asSequence()
            .filter { module ->
                module.languageSettings["java.buildSystem"] == "maven" &&
                    module.languageSettings["java.dependencyGraph.status"] != "complete"
            }
            .mapNotNull { it.languageSettings["java.dependencyGraph.message"] }
            .flatMap { it.split("; ").asSequence() }
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (dependencyGraphFailures.isNotEmpty()) {
            val selectedDescriptorFailures = dependencyGraphFailures.filter {
                it.startsWith("MAVEN_SELECTED_DESCRIPTOR_")
            }.sorted()
            val missingDescriptor = dependencyGraphFailures.firstOrNull {
                it.startsWith("MAVEN_DEPENDENCY_DESCRIPTOR_MISSING ")
            }
            val blockers = when {
                selectedDescriptorFailures.isNotEmpty() -> selectedDescriptorFailures
                missingDescriptor != null -> listOf(missingDescriptor)
                else -> listOf(dependencyGraphFailures.first())
            }
            val code = when {
                selectedDescriptorFailures.any { it.startsWith("MAVEN_SELECTED_DESCRIPTOR_MISSING ") } ->
                    "java.maven.selectedDescriptor.missing"
                selectedDescriptorFailures.any { it.startsWith("MAVEN_SELECTED_DESCRIPTOR_MALFORMED ") } ->
                    "java.maven.selectedDescriptor.malformed"
                selectedDescriptorFailures.any { it.startsWith("MAVEN_SELECTED_DESCRIPTOR_DRIFTED ") } ->
                    "java.maven.selectedDescriptor.drifted"
                missingDescriptor != null -> "java.maven.dependencyDescriptor.missing"
                else -> "java.maven.dependencyGraph.incomplete"
            }
            return withoutAuthority(refused(
                snapshot,
                "moveClass",
                "Maven dependency traversal is structurally incomplete: ${blockers.joinToString(" | ")}",
                code,
            ))
        }

        val index = adapter.buildSymbols(snapshot)
        val symbol = index.symbols.find { it.id.value == symbolFqn && it.kind in MOVEABLE_KINDS }
            ?: return withoutAuthority(refused(snapshot, "moveClass", "Symbol not found or not a moveable type: $symbolFqn"))

        val declarationFile = snapshot.files.find { it.path == symbol.location.path }
            ?: return withoutAuthority(refused(snapshot, "moveClass", "Declaration file not found: ${symbol.location.path}"))
        JavaGeneratedSourcePolicy.reason(declarationFile)?.let { reason ->
            return withoutAuthority(
                refused(snapshot, "moveClass", "Generated source cannot be rewritten: ${declarationFile.path} ($reason)"),
            )
        }
        val newRelativePath = computeNewPath(declarationFile.path, oldPkg, targetPackage, simpleName)
        if (index.symbols.any { it.id.value == newFqn } || snapshot.files.any { it.path == newRelativePath }) {
            return withoutAuthority(refused(snapshot, "moveClass", "Move target already exists: $newFqn ($newRelativePath)"))
        }
        val availableSelection = JavaMoveClassTargetAuthorityEvaluator.availableSelection(
            snapshot,
            symbolFqn,
            declarationFile.path,
        )
        val offlinePreparation = if (availableSelection == null) {
            JavaMoveClassTargetAuthorityEvaluator.prepare(snapshot, symbolFqn, declarationFile.path)
        } else null
        val offlinePrepared = (offlinePreparation as? JavaMoveClassOfflineAuthorityPreparation.Eligible)?.prepared
        val authorityBlockers = (offlinePreparation as? JavaMoveClassOfflineAuthorityPreparation.Ineligible)
            ?.blockers.orEmpty()
        val semanticSelection = availableSelection ?: offlinePrepared?.selection
        val jdtSelection = semanticSelection?.let { selection ->
            JdtReferenceSelection(
                referencePaths = selection.references.mapTo(linkedSetOf()) { it.path },
                semanticSelection = selection,
            )
        }
        val jdtReferencePaths = jdtSelection?.referencePaths
        if (jdtReferencePaths == null) {
            val unsafeSamePackage = snapshot.files.firstOrNull { file ->
                file.path != declarationFile.path && file.languageId == "java" &&
                    JavaGeneratedSourcePolicy.reason(file) == null &&
                    JavaPackageUtil.extractPackage(file.content) == oldPkg &&
                    !file.content.contains("import $symbolFqn;") &&
                    !file.content.contains(symbolFqn) &&
                    JavaLexer.findOccurrences(file.content, simpleName).isNotEmpty()
            }
            if (unsafeSamePackage != null) {
                return withoutAuthority(refused(
                    snapshot,
                    "moveClass",
                    "Lexical fallback cannot prove simple-name ownership in ${unsafeSamePackage.path}; JDT binding evidence is required.",
                    "java.moveClass.lexicalScopeUnsafe",
                ))
            }
        }

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

        // 3. Update all other files. Semantic selection uses exact binding ranges;
        // lexical scanning is retained only for review-only fallback plans.
        for (file in snapshot.files) {
            if (file.path == declarationFile.path || file.languageId != "java" ||
                JavaGeneratedSourcePolicy.reason(file) != null
            ) continue
            val fileEdits = if (jdtSelection != null) {
                bindingDerivedReferenceEdits(
                    file,
                    jdtSelection.semanticSelection.references,
                    symbolFqn,
                    newFqn,
                    oldPkg,
                    targetPackage,
                )
            } else {
                lexicalReferenceEdits(file, symbolFqn, newFqn, oldPkg, targetPackage)
            }
            if (fileEdits.isNotEmpty()) {
                edits += FileEdit.Modify(file.path, fileEdits)
                affectedPaths.add(file.path)
            }
        }

        val workspaceEdit = WorkspaceEdit(edits)
        val targetAuthorityLease = offlinePrepared?.let { prepared ->
            JavaMoveClassTargetAuthorityEvaluator.complete(
                prepared,
                targetPackage,
                newRelativePath,
                workspaceEdit,
            )
        }
        val semanticEligible = jdtSelection != null && (offlinePrepared == null || targetAuthorityLease != null)
        val frameworkAssessment = JavaFrameworkDetector.assess(declarationFile)
        warnings += if (semanticEligible) {
            "JDT type binding selected ${jdtSelection?.referencePaths?.size} referencing file(s); " +
                "every package/import/FQN edit is binding-derived or structurally consequent."
        } else {
            "JDT type-binding evidence was unavailable or not clean; move uses lexical file scoping. Review carefully."
        }
        if (semanticEligible) {
            val selection = checkNotNull(jdtSelection).semanticSelection
            val closure = selection.closure
            val observers = (closure.sourceSets - closure.owner).map(MoveAuthoritySourceSet::displayName).sorted()
            warnings += "Authoritative dependency-bounded reverse-observer closure: target owner " +
                "${closure.owner.displayName()}; observers " +
                observers.ifEmpty { listOf("none") }.joinToString(", ") + "."
            if (selection.excludedWarningSourceSets.isNotEmpty()) {
                warnings += selection.excludedWarningSourceSets
                    .map(MoveAuthoritySourceSet::displayName)
                    .sorted()
                    .joinToString(", ") +
                    " excluded from the dependency-bounded reverse-observer closure; " +
                    "those JDT warnings did not demote semantic authority."
            }
            warnings += JavaMoveClassCandidateReporter.warnings(snapshot, selection, symbolFqn)
        }
        if (targetAuthorityLease != null) {
            warnings += "Target-scoped Maven moveClass authority lease: reactorStructureStatus=COMPLETE, " +
                "observerClosureStatus=COMPLETE, externalClasspathStatus=OFFLINE_MISSING; " +
                "${targetAuthorityLease.offlineMissingEntries.size + targetAuthorityLease.selectedMissingBinaryRecords.size} " +
                "enumerated leaf absence(s), " +
                "${targetAuthorityLease.candidatesBefore.size} candidate record(s), " +
                "${targetAuthorityLease.retainedDiagnosticsBefore.size} exactly retained diagnostic(s)."
        } else if (authorityBlockers.isNotEmpty()) {
            warnings += "Target-scoped Maven moveClass authority was not leased: ${authorityBlockers.joinToString("; ")}"
        } else if (offlinePrepared != null) {
            warnings += "Target-scoped Maven moveClass authority was not leased because staged binding or diagnostic evidence changed."
        }
        warnings += "String literals, comments, and non-Java text are never edited; reported matches and other " +
            "reflection or annotation-processor output require manual review."
        warnings += frameworkAssessment.warnings("moveClass")

        val plan = PatchPlan(
            operation = "moveClass",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = if (semanticEligible) 0.94 else 0.90,
            requiresUserApproval = true,
            summary = "Move $simpleName from $oldPkg → $targetPackage. ${affectedPaths.size} file(s) affected.",
            affectedFiles = affectedPaths,
            workspaceEdit = workspaceEdit,
            diagnosticsBefore = targetAuthorityLease?.retainedDiagnosticsBefore
                ?.map(JavaMoveClassRetainedDiagnosticIdentity::toDiagnostic).orEmpty(),
            diagnosticsAfterPreview = targetAuthorityLease?.retainedDiagnosticsStaged
                ?.map(JavaMoveClassRetainedDiagnosticIdentity::toDiagnostic).orEmpty(),
            warnings = warnings,
            riskLevel = if (frameworkAssessment.hasFindings) RiskLevel.HIGH else RiskLevel.MEDIUM,
            evidence = if (semanticEligible) RefactoringEvidence.JDT_BINDING else RefactoringEvidence.LEXICAL_FALLBACK,
            authorityLease = targetAuthorityLease?.coreLease,
        )
        return JavaMoveClassPreview(plan, targetAuthorityLease)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun bindingDerivedReferenceEdits(
        file: SourceFile,
        references: List<JdtJavaSemanticReference>,
        oldFqn: String,
        newFqn: String,
        oldPackage: String,
        targetPackage: String,
    ): List<TextEdit> {
        val exactReferences = references.filter { it.path == file.path && !it.recovered }
        if (exactReferences.isEmpty()) return emptyList()
        val content = file.content
        val filePackage = JavaPackageUtil.extractPackage(content)
        val oldImports = JavaLexer.extractImports(content).filter { !it.isStatic && it.name == oldFqn }
        val fqnRanges = JavaLexer.findOccurrences(content, oldFqn)
        val edits = linkedMapOf<Pair<SourceRange, String>, TextEdit>()
        exactReferences.forEach { reference ->
            val referenceStart = TextEdits.offsetOf(content, reference.sourceRange.start)
            val referenceEnd = TextEdits.offsetOf(content, reference.sourceRange.end)
            val boundImport = oldImports.singleOrNull { import ->
                referenceStart >= import.startOffset && referenceEnd <= import.endOffset
            }
            if (boundImport != null) {
                val range = if (filePackage == targetPackage) {
                    TextEdits.rangeForOffset(content, boundImport.startOffset, boundImport.endOffset - boundImport.startOffset)
                } else {
                    val nameStart = content.indexOf(boundImport.name, boundImport.startOffset)
                    TextEdits.rangeForOffset(content, nameStart, boundImport.name.length)
                }
                val replacement = if (filePackage == targetPackage) "" else newFqn
                edits.putIfAbsent(range to replacement, TextEdit(range, replacement))
                return@forEach
            }
            val boundFqn = fqnRanges.singleOrNull { range ->
                referenceStart >= range.first && referenceEnd <= range.last + 1
            }
            if (boundFqn != null) {
                val range = TextEdits.rangeForOffset(content, boundFqn.first, boundFqn.last - boundFqn.first + 1)
                edits.putIfAbsent(range to newFqn, TextEdit(range, newFqn))
            }
        }
        if (filePackage == oldPackage && oldPackage.isNotEmpty() && oldImports.isEmpty()) {
            val insertOffset = insertImportOffset(content)
            val position = TextEdits.positionForOffset(content, insertOffset)
            val range = SourceRange(position, position)
            edits.putIfAbsent(range to "import $newFqn;\n", TextEdit(range, "import $newFqn;\n"))
        }
        return edits.values.sortedWith(compareBy({ it.range.start.line }, { it.range.start.character }))
    }

    private fun lexicalReferenceEdits(
        file: SourceFile,
        oldFqn: String,
        newFqn: String,
        oldPackage: String,
        targetPackage: String,
    ): List<TextEdit> {
        val content = file.content
        val filePackage = JavaPackageUtil.extractPackage(content)
        val hasOldImport = content.contains("import $oldFqn;")
        val hasFqn = content.contains(oldFqn)
        if (!hasOldImport && !hasFqn) return emptyList()
        val nowInSamePackage = filePackage == targetPackage
        val edits = mutableListOf<TextEdit>()
        val coveredOffsets = mutableSetOf<Int>()
        if (hasOldImport) {
            val importText = "import $oldFqn;"
            val replacement = if (nowInSamePackage) "" else "import $newFqn;"
            JavaLexer.findOccurrences(content, importText).forEach { range ->
                edits += makeEdit(content, range, replacement)
                range.forEach(coveredOffsets::add)
            }
        }
        if (hasFqn) {
            JavaLexer.findOccurrences(content, oldFqn).forEach { range ->
                if (range.first !in coveredOffsets) edits += makeEdit(content, range, newFqn)
            }
        }
        if (filePackage == oldPackage && oldPackage.isNotEmpty() && !nowInSamePackage && !hasOldImport) {
            val insertOffset = insertImportOffset(content)
            val position = TextEdits.positionForOffset(content, insertOffset)
            edits += TextEdit(SourceRange(position, position), "import $newFqn;\n")
        }
        return edits.distinct().sortedWith(compareBy({ it.range.start.line }, { it.range.start.character }))
    }

    private data class JdtReferenceSelection(
        val referencePaths: Set<Path>,
        val semanticSelection: JavaMoveClassSemanticSelection,
    )

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
        val pkgMatch = Regex("""(?m)^package\s+[\w.]+\s*;""").find(content) ?: return 0
        var offset = pkgMatch.range.last + 1
        if (content.startsWith("\r\n", offset)) offset += 2
        else if (content.startsWith("\n", offset)) offset += 1
        return offset
    }

    private fun isValidPackageName(packageName: String): Boolean =
        packageName.isNotBlank() && packageName.split('.').all { segment ->
            segment.isNotEmpty() &&
                (segment.first().isLetter() || segment.first() == '_' || segment.first() == '$') &&
                segment.all(JavaLexer::isIdentChar)
        }

    private fun refused(
        snapshot: ProjectSnapshot,
        operation: String,
        reason: String,
        code: String? = null,
    ) = PatchPlan(
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
        refusalCode = code,
    )

    companion object {
        private val MOVEABLE_KINDS = setOf(
            Symbol.Kind.CLASS,
            Symbol.Kind.INTERFACE,
            Symbol.Kind.ENUM,
            Symbol.Kind.RECORD,
            Symbol.Kind.ANNOTATION,
        )
    }
}
