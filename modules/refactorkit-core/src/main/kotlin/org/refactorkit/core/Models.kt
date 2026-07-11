package org.refactorkit.core

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@JvmInline
value class SymbolId(val value: String)

@JvmInline
value class PlanId(val value: String) {
    companion object {
        fun new(): PlanId = PlanId("plan-${UUID.randomUUID()}")
    }
}

@JvmInline
value class TransactionId(val value: String) {
    companion object {
        fun new(): TransactionId = TransactionId("transaction-${UUID.randomUUID()}")
    }
}

data class Workspace(
    val root: Path,
)

data class Module(
    val name: String,
    val root: Path,
    val sourceRoots: List<Path> = emptyList(),
    val classpathEntries: List<Path> = emptyList(),
)

data class SourceFile(
    val path: Path,
    val content: String,
    val languageId: String,
)

data class ProjectSnapshot(
    val workspace: Workspace,
    val modules: List<Module>,
    val files: List<SourceFile>,
    val hash: String = hashFiles(files),
) {
    companion object {
        fun hashFiles(files: List<SourceFile>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            files.sortedBy { it.path.toString() }.forEach { file ->
                digest.update(file.path.toString().toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(file.content.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

data class SourcePosition(
    val line: Int,
    val character: Int,
) : Comparable<SourcePosition> {
    init {
        require(line >= 0) { "line must be zero-based and non-negative" }
        require(character >= 0) { "character must be zero-based and non-negative" }
    }

    override fun compareTo(other: SourcePosition): Int =
        compareValuesBy(this, other, SourcePosition::line, SourcePosition::character)
}

data class SourceRange(
    val start: SourcePosition,
    val end: SourcePosition,
) {
    init {
        require(start <= end) { "range start must be before or equal to end" }
    }

    fun overlaps(other: SourceRange): Boolean = start < other.end && other.start < end
}

data class SourceLocation(
    val path: Path,
    val range: SourceRange,
)

data class TextEdit(
    val range: SourceRange,
    val newText: String,
)

sealed interface FileEdit {
    val path: Path

    data class Modify(
        override val path: Path,
        val textEdits: List<TextEdit>,
    ) : FileEdit

    data class Create(
        override val path: Path,
        val content: String,
        val overwrite: Boolean = false,
    ) : FileEdit

    data class Delete(
        override val path: Path,
    ) : FileEdit

    data class Rename(
        override val path: Path,
        val newPath: Path,
    ) : FileEdit
}

data class WorkspaceEdit(
    val edits: List<FileEdit> = emptyList(),
) {
    fun affectedFiles(): Set<Path> = buildSet {
        edits.forEach { edit ->
            add(edit.path)
            if (edit is FileEdit.Rename) add(edit.newPath)
        }
    }
}

enum class PatchStatus {
    PREVIEW,
    APPLIED,
    REFUSED,
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

data class Diagnostic(
    val message: String,
    val severity: Severity,
    val location: SourceLocation? = null,
    val code: String? = null,
) {
    enum class Severity {
        INFO,
        WARNING,
        ERROR,
    }
}

data class PatchPlan(
    val id: PlanId = PlanId.new(),
    val operation: String,
    val status: PatchStatus = PatchStatus.PREVIEW,
    val snapshotHash: String,
    val confidence: Double,
    val requiresUserApproval: Boolean = true,
    val summary: String,
    val affectedFiles: Set<Path>,
    val workspaceEdit: WorkspaceEdit,
    val diagnosticsBefore: List<Diagnostic> = emptyList(),
    val diagnosticsAfterPreview: List<Diagnostic> = emptyList(),
    val warnings: List<String> = emptyList(),
    val riskLevel: RiskLevel = RiskLevel.LOW,
)

data class Transaction(
    val id: TransactionId = TransactionId.new(),
    val planId: PlanId,
    val appliedAt: Instant = Instant.now(),
    val snapshotHashBefore: String,
    val rollbackEdit: WorkspaceEdit,
)

data class Symbol(
    val id: SymbolId,
    val name: String,
    val kind: Kind,
    val location: SourceLocation,
    val languageId: String,
) {
    enum class Kind {
        CLASS,
        INTERFACE,
        ENUM,
        RECORD,
        ANNOTATION,
        METHOD,
        FIELD,
        CONSTRUCTOR,
        PACKAGE,
        UNKNOWN,
    }
}

data class Reference(
    val symbolId: SymbolId,
    val location: SourceLocation,
)

data class SymbolIndex(
    val symbols: List<Symbol>,
) {
    fun search(query: String): List<Symbol> = symbols.filter {
        it.name.contains(query, ignoreCase = true) || it.id.value.contains(query, ignoreCase = true)
    }
}

data class ParseResult(
    val file: SourceFile,
    val diagnostics: List<Diagnostic> = emptyList(),
)

data class SymbolResolution(
    val symbol: Symbol?,
    val diagnostics: List<Diagnostic> = emptyList(),
)

data class CodeSelection(
    val location: SourceLocation,
)

data class RefactoringDescriptor(
    val id: String,
    val label: String,
    val riskLevel: RiskLevel,
)

data class RefactoringRequest(
    val operation: String,
    val symbolId: SymbolId? = null,
    val selection: CodeSelection? = null,
    val arguments: Map<String, String> = emptyMap(),
    val snapshot: ProjectSnapshot,
)

typealias RefactoringPlan = PatchPlan

interface LanguageAdapter {
    fun languageId(): String
    fun parse(file: SourceFile): ParseResult
    fun buildSymbols(project: ProjectSnapshot): SymbolIndex
    fun resolveSymbol(location: SourceLocation): SymbolResolution
    fun findReferences(symbolId: SymbolId): List<Reference>
    fun diagnostics(project: ProjectSnapshot): List<Diagnostic>
    fun availableRefactorings(selection: CodeSelection): List<RefactoringDescriptor>
    fun applyRefactoring(request: RefactoringRequest): RefactoringPlan
    fun formatEdits(edits: List<TextEdit>): List<TextEdit>
}
