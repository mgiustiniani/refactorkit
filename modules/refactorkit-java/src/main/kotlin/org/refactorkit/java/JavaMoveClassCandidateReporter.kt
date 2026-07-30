package org.refactorkit.java

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Reports lexical move-class candidates without granting them edit authority.
 * Exact JDT binding selection remains the sole source of managed Java edits.
 */
internal object JavaMoveClassCandidateReporter {
    fun warnings(
        snapshot: ProjectSnapshot,
        selection: JavaMoveClassSemanticSelection,
        targetFqn: String,
    ): List<String> {
        val targetBindingKey = requireNotNull(selection.target.bindingKey)
        val candidates = JavaMoveClassTargetAuthorityEvaluator.candidateInventory(
            snapshot,
            selection.closure.model,
            selection.analysis,
            targetFqn,
            targetBindingKey,
        )
        val closureNames = selection.closure.sourceSets.mapTo(sortedSetOf(), MoveAuthoritySourceSet::displayName)
        val boundTargetCount = candidates.count {
            it.classification == JavaMoveClassCandidateClassification.BOUND_TARGET
        }
        val boundOther = candidates.filter {
            it.classification == JavaMoveClassCandidateClassification.BOUND_OTHER
        }
        val unresolvedCount = candidates.count {
            it.classification == JavaMoveClassCandidateClassification.UNRESOLVED
        }
        val tokenFqnCounts = candidates.filter { it.lexicalText == targetFqn }
            .groupingBy(JavaMoveClassCandidateRecord::path)
            .eachCount()
        val javaNonCodeResiduals = snapshot.files.asSequence()
            .filter { it.languageId == "java" && targetFqn in it.content }
            .mapNotNull { file ->
                val nonCodeOccurrences = countOccurrences(file.content, targetFqn) -
                    tokenFqnCounts.getOrDefault(file.path, 0)
                sourceSetName(snapshot, file.path)?.let { sourceSet ->
                    JavaNonCodeResidual(file.path, sourceSet, nonCodeOccurrences)
                }
            }
            .filter { it.occurrences > 0 }
            .sortedBy { it.path.invariantSeparatorsPathString }
            .toList()
        val residual = runCatching { scanResidualTextCandidates(snapshot, targetFqn) }
            .getOrElse { ResidualTextScan(emptyList(), true) }

        return buildList {
            add(
                "Candidate-total JDT-classified Java evidence: BOUND_TARGET=$boundTargetCount, " +
                    "BOUND_OTHER=${boundOther.size}, UNRESOLVED=$unresolvedCount; " +
                    "JAVA_NON_CODE_RESIDUAL=${javaNonCodeResiduals.sumOf { it.occurrences }}; " +
                    "NON_JAVA_RESIDUAL=${residual.paths.size}. Lexical candidate evidence is " +
                    "completeness-and-veto/risk only; it never selects managed edits and no lexical range " +
                    "selected an edit.",
            )
            boundOther.groupBy { it.path }.toSortedMap(compareBy(Path::toString)).forEach { (path, records) ->
                val sourceSet = records.map { it.sourceSet }.distinct().single()
                val closureReason = if (sourceSet in closureNames) {
                    "its exact non-target binding differs from the selected declaration binding"
                } else {
                    "its exact non-target binding differs and $sourceSet is outside the dependency-bounded " +
                        "reverse-observer closure"
                }
                add(
                    "Java candidate excluded: path=${path.invariantSeparatorsPathString}; " +
                        "sourceSet=$sourceSet; classification=BOUND_OTHER; candidateRanges=${records.size}; " +
                        "reason=$closureReason.",
                )
            }
            javaNonCodeResiduals.forEach { candidate ->
                val closureContext = if (candidate.sourceSet !in closureNames) {
                    "${candidate.sourceSet} is outside the dependency-bounded reverse-observer closure"
                } else {
                    "${candidate.sourceSet} is inside the dependency-bounded reverse-observer closure"
                }
                add(
                    "Java non-code candidate excluded from managed edits; residual review risk: " +
                        "path=${candidate.path.invariantSeparatorsPathString}; " +
                        "sourceSet=${candidate.sourceSet}; classification=JAVA_NON_CODE_RESIDUAL; " +
                        "candidateRanges=${candidate.occurrences}; managedEdit=false; " +
                        "closureContext=$closureContext; reason=the old FQN occurs only as non-code lexical " +
                        "evidence and has no exact JDT-classified candidate range.",
                )
            }
            residual.paths.forEach { path ->
                add(
                    "Non-Java candidate residual review risk: path=${path.invariantSeparatorsPathString}; " +
                        "classification=NON_JAVA_RESIDUAL; lexicalText=$targetFqn; managedEdit=false.",
                )
            }
            if (residual.truncated) {
                add(
                    "Non-Java residual review scan was bounded or encountered an unreadable path; " +
                        "unreported text candidates remain a residual review risk and cannot select edits.",
                )
            }
        }
    }

    private fun sourceSetName(snapshot: ProjectSnapshot, path: Path): String? {
        val owner = snapshot.owningBuildSourceRoots(path).singleOrNull() ?: return null
        return "${owner.module.id}:${owner.sourceSet.id}"
    }

    private fun scanResidualTextCandidates(snapshot: ProjectSnapshot, targetFqn: String): ResidualTextScan {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return ResidualTextScan(emptyList(), true)
        val matches = mutableListOf<Path>()
        var examined = 0
        var truncated = false
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && dir.fileName.toString() in snapshot.ignoredDirectories) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
                val relative = root.relativize(file.toAbsolutePath().normalize()).normalize()
                if (relative.isAbsolute || relative.startsWith("..") || !isResidualText(relative)) {
                    return FileVisitResult.CONTINUE
                }
                if (examined >= MAX_RESIDUAL_TEXT_FILES) {
                    truncated = true
                    return FileVisitResult.TERMINATE
                }
                examined += 1
                if (attrs.size() > MAX_RESIDUAL_TEXT_BYTES) {
                    truncated = true
                    return FileVisitResult.CONTINUE
                }
                val content = runCatching { Files.readString(file, Charsets.UTF_8) }.getOrElse {
                    truncated = true
                    return FileVisitResult.CONTINUE
                }
                if (targetFqn in content) matches.add(relative)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                truncated = true
                return FileVisitResult.CONTINUE
            }
        })
        return ResidualTextScan(
            matches.distinct().sortedBy(Path::toString),
            truncated,
        )
    }

    private fun isResidualText(path: Path): Boolean {
        val name = path.fileName?.toString().orEmpty()
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in RESIDUAL_TEXT_EXTENSIONS
    }

    private fun countOccurrences(content: String, text: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            val found = content.indexOf(text, offset)
            if (found < 0) return count
            count += 1
            offset = found + text.length
        }
    }

    private data class JavaNonCodeResidual(
        val path: Path,
        val sourceSet: String,
        val occurrences: Int,
    )

    private data class ResidualTextScan(
        val paths: List<Path>,
        val truncated: Boolean,
    )

    private val RESIDUAL_TEXT_EXTENSIONS = setOf(
        "adoc",
        "csv",
        "feature",
        "html",
        "json",
        "md",
        "properties",
        "txt",
        "xml",
        "yaml",
        "yml",
    )
    private const val MAX_RESIDUAL_TEXT_FILES = 20_000
    private const val MAX_RESIDUAL_TEXT_BYTES = 1_048_576L
}
