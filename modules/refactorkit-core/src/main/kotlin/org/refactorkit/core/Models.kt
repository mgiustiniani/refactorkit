package org.refactorkit.core

import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashMap
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
    init {
        require(isValid(value)) { "Invalid transaction ID" }
    }

    companion object {
        private val PATTERN = Regex("transaction-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        fun new(): TransactionId = TransactionId("transaction-${UUID.randomUUID()}")

        fun parseOrNull(value: String): TransactionId? =
            value.takeIf(::isValid)?.let(::TransactionId)

        fun isValid(value: String): Boolean = PATTERN.matches(value)
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
    val dependencies: List<String> = emptyList(),
    val languageSettings: Map<String, String> = emptyMap(),
    val mainSourceRoots: List<Path> = sourceRoots,
    val testSourceRoots: List<Path> = emptyList(),
    val generatedSourceRoots: List<Path> = emptyList(),
    val generatedTestSourceRoots: List<Path> = emptyList(),
    val mainClasspathEntries: List<Path> = classpathEntries,
    val mainRuntimeClasspathEntries: List<Path> = mainClasspathEntries,
    val testClasspathEntries: List<Path> = classpathEntries,
    val mainDependencies: List<String> = dependencies,
    val testDependencies: List<String> = dependencies,
    val mainOutputDirectories: List<Path> = emptyList(),
    val testOutputDirectories: List<Path> = emptyList(),
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
    val sourceExtensions: Set<String> = inferSourceExtensions(files),
    val ignoredDirectories: Set<String> = DEFAULT_IGNORED_DIRECTORIES,
    val classpathEvidence: List<ClasspathEvidence> = emptyList(),
    val buildModels: List<BuildModel> = emptyList(),
    val auxiliaryFiles: List<SourceFile> = emptyList(),
) {
    init {
        require(buildModels.map(BuildModel::providerId).distinct().size == buildModels.size) {
            "build-model provider IDs must be unique within a snapshot"
        }
        val auxiliaryPaths = auxiliaryFiles.map { it.path.normalize() }
        require(auxiliaryPaths.distinct().size == auxiliaryPaths.size) {
            "auxiliary workspace file paths must be unique"
        }
        require(auxiliaryPaths.all { !it.isAbsolute && !it.startsWith("..") }) {
            "auxiliary workspace files must use safe workspace-relative paths"
        }
        require(files.map { it.path.normalize() }.intersect(auxiliaryPaths.toSet()).isEmpty()) {
            "language source files and auxiliary workspace files must have disjoint paths"
        }
    }

    val trackedFiles: List<SourceFile> get() = files + auxiliaryFiles

    val hash: String = hashSnapshot(
        modules, files, sourceExtensions, ignoredDirectories, classpathEvidence, buildModels, auxiliaryFiles,
    )

    companion object {
        val DEFAULT_IGNORED_DIRECTORIES: Set<String> = setOf(
            ".git", ".gradle", ".idea", ".refactorkit",
            "build", "target", "dist", "out", "coverage", "node_modules", "__pycache__",
        )

        fun inferSourceExtensions(files: List<SourceFile>): Set<String> = files.mapNotNull { file ->
            file.path.fileName?.toString()?.substringAfterLast('.', missingDelimiterValue = "")?.takeIf(String::isNotEmpty)
        }.toSet()

        fun hashSnapshot(
            modules: List<Module>,
            files: List<SourceFile>,
            sourceExtensions: Set<String>,
            ignoredDirectories: Set<String>,
            classpathEvidence: List<ClasspathEvidence> = emptyList(),
            buildModels: List<BuildModel> = emptyList(),
            auxiliaryFiles: List<SourceFile> = emptyList(),
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            modules.sortedBy { it.name }.forEach { module ->
                digest.update("module\u0000${module.name}\u0000${module.root.toAbsolutePath().normalize()}\u0000".toByteArray(Charsets.UTF_8))
                module.sourceRoots.sortedBy(Path::toString).forEach { digest.update("sourceRoot\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                module.classpathEntries.sortedBy(Path::toString).forEach { digest.update("classpath\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                module.mainRuntimeClasspathEntries.sortedBy(Path::toString).forEach {
                    digest.update("mainRuntimeClasspath\u0000$it\u0000".toByteArray(Charsets.UTF_8))
                }
                module.dependencies.sorted().forEach { digest.update("moduleDependency\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                module.languageSettings.toSortedMap().forEach { (key, value) ->
                    digest.update("languageSetting\u0000$key\u0000$value\u0000".toByteArray(Charsets.UTF_8))
                }
                module.mainSourceRoots.sortedBy(Path::toString).forEach { digest.update("mainSourceRoot\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                module.testSourceRoots.sortedBy(Path::toString).forEach { digest.update("testSourceRoot\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                module.generatedSourceRoots.sortedBy(Path::toString).forEach { digest.update("generatedSourceRoot\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                module.generatedTestSourceRoots.sortedBy(Path::toString).forEach { digest.update("generatedTestSourceRoot\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                module.mainClasspathEntries.sortedBy(Path::toString).forEach { digest.update("mainClasspath\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                module.testClasspathEntries.sortedBy(Path::toString).forEach { digest.update("testClasspath\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                module.mainDependencies.sorted().forEach { digest.update("mainModuleDependency\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                module.testDependencies.sorted().forEach { digest.update("testModuleDependency\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                module.mainOutputDirectories.sortedBy(Path::toString).forEach { digest.update("mainOutput\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                module.testOutputDirectories.sortedBy(Path::toString).forEach { digest.update("testOutput\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
            }
            sourceExtensions.sorted().forEach { digest.update("extension\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
            ignoredDirectories.sorted().forEach { digest.update("ignored\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
            classpathEvidence.sortedWith(compareBy<ClasspathEvidence> { it.path.toString() }.thenBy { it.kind.name }).forEach { evidence ->
                digest.update("classpathEvidence\u0000${evidence.path}\u0000${evidence.kind}\u0000${evidence.fingerprint}\u0000".toByteArray(Charsets.UTF_8))
            }
            buildModels.sortedBy(BuildModel::providerId).forEach { model ->
                digest.update("buildModel\u0000${model.providerId}\u0000${model.status}\u0000".toByteArray(Charsets.UTF_8))
                model.attributes.toSortedMap().forEach { (key, value) ->
                    digest.update("buildModelAttribute\u0000$key\u0000$value\u0000".toByteArray(Charsets.UTF_8))
                }
                model.diagnostics.sortedWith(compareBy<BuildModelDiagnostic> { it.moduleId.orEmpty() }.thenBy { it.code }.thenBy { it.message }).forEach { diagnostic ->
                    digest.update("buildModelDiagnostic\u0000${diagnostic.moduleId}\u0000${diagnostic.code}\u0000${diagnostic.severity}\u0000${diagnostic.message}\u0000".toByteArray(Charsets.UTF_8))
                }
                model.modules.sortedBy(BuildModule::id).forEach { buildModule ->
                    digest.update("buildModule\u0000${buildModule.id}\u0000${buildModule.name}\u0000${buildModule.root}\u0000".toByteArray(Charsets.UTF_8))
                    buildModule.attributes.toSortedMap().forEach { (key, value) ->
                        digest.update("buildModuleAttribute\u0000$key\u0000$value\u0000".toByteArray(Charsets.UTF_8))
                    }
                    buildModule.sourceSets.sortedBy(BuildSourceSet::id).forEach { sourceSet ->
                        digest.update("sourceSet\u0000${sourceSet.id}\u0000${sourceSet.kind}\u0000".toByteArray(Charsets.UTF_8))
                        sourceSet.sourceRoots.sortedBy(Path::toString).forEach { digest.update("sourceSetRoot\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                        sourceSet.generatedSourceRoots.sortedBy(Path::toString).forEach { digest.update("sourceSetGeneratedRoot\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                        sourceSet.outputDirectories.sortedBy(Path::toString).forEach { digest.update("sourceSetOutput\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                        sourceSet.classpathEntries.sortedBy(Path::toString).forEach { digest.update("sourceSetClasspath\u0000$it\u0000".toByteArray(Charsets.UTF_8)) }
                        sourceSet.runtimeClasspathEntries.sortedBy(Path::toString).forEach {
                            digest.update("sourceSetRuntimeClasspath\u0000$it\u0000".toByteArray(Charsets.UTF_8))
                        }
                        sourceSet.moduleDependencies.sortedWith(compareBy<BuildDependency> { it.targetModuleId }.thenBy { it.scope.name }).forEach { dependency ->
                            digest.update("sourceSetDependency\u0000${dependency.targetModuleId}\u0000${dependency.scope}\u0000".toByteArray(Charsets.UTF_8))
                        }
                        sourceSet.languageFacets.sortedBy(BuildLanguageFacet::languageId).forEach { facet ->
                            digest.update("sourceSetLanguageFacet\u0000${facet.languageId}\u0000${facet.platformId}\u0000${facet.compilerId}\u0000${facet.sourceVersion}\u0000${facet.targetVersion}\u0000${facet.targetRuntimeVersion}\u0000${facet.evidence}\u0000".toByteArray(Charsets.UTF_8))
                            facet.compilerPluginIds.sorted().forEach { plugin ->
                                digest.update("sourceSetLanguagePlugin\u0000${facet.languageId}\u0000$plugin\u0000".toByteArray(Charsets.UTF_8))
                            }
                        }
                        sourceSet.attributes.toSortedMap().forEach { (key, value) ->
                            digest.update("sourceSetAttribute\u0000$key\u0000$value\u0000".toByteArray(Charsets.UTF_8))
                        }
                    }
                }
            }
            auxiliaryFiles.sortedBy { it.path.toString() }.forEach { file ->
                digest.update("auxiliaryFile\u0000".toByteArray(Charsets.UTF_8))
                digest.update(file.path.toString().toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(file.languageId.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(file.content.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            files.sortedBy { it.path.toString() }.forEach { file ->
                digest.update(file.path.toString().toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(file.languageId.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(file.content.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

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

enum class RefactoringEvidence {
    JDT_BINDING,
    LANGUAGE_SERVER,
    NATIVE_AST,
    STRUCTURAL,
    LEXICAL_FALLBACK,
}

/**
 * A generic workspace file whose exact content is required by an operation's
 * authority decision even though the file is not itself a managed edit.
 *
 * Core deliberately knows only a caller-defined [kind], a safe relative path,
 * and a content identity. Language-specific candidate semantics remain in the
 * language adapter's immutable [attributes].
 */
class OperationAuthorityFileEvidence(
    val kind: String,
    path: Path,
    val expectedContentSha256: String,
    attributes: Map<String, String> = emptyMap(),
) {
    val path: Path = path.normalize()
    private val attributeValues: Map<String, String> = Collections.unmodifiableMap(
        LinkedHashMap(attributes.toSortedMap()),
    )
    val attributes: Map<String, String> get() = attributeValues

    init {
        require(kind.isNotBlank()) { "operation-authority file-evidence kind must not be blank" }
        require(!path.isAbsolute && path == this.path && !this.path.startsWith("..")) {
            "operation-authority file-evidence path must be normalized and workspace-relative"
        }
        require(SHA256.matches(expectedContentSha256)) {
            "operation-authority expected file content hash must be SHA-256"
        }
        require(attributeValues.keys.all(String::isNotBlank)) {
            "operation-authority file-evidence attribute keys must not be blank"
        }
    }

    override fun equals(other: Any?): Boolean = other is OperationAuthorityFileEvidence &&
        kind == other.kind && path == other.path && expectedContentSha256 == other.expectedContentSha256 &&
        attributeValues == other.attributeValues

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + expectedContentSha256.hashCode()
        result = 31 * result + attributeValues.hashCode()
        return result
    }

    override fun toString(): String =
        "OperationAuthorityFileEvidence(kind=$kind, path=$path, expectedContentSha256=$expectedContentSha256, attributes=$attributeValues)"

    private companion object {
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}

enum class OperationAuthorityEvidenceCompleteness {
    COMPLETE,
    TRUNCATED,
}

/**
 * Immutable, operation-specific evidence that must still match the supplied
 * snapshot when a managed preview reaches the under-lock write boundary.
 *
 * The language planner owns the semantic meaning of [kind], [evidenceHash],
 * and [attributes]. Core enforces that the lease is complete, belongs to this
 * exact operation/snapshot and normalized workspace edit, and that every
 * required classpath or non-managed file evidence record is revalidated under
 * the workspace lock.
 */
class OperationAuthorityLease(
    val kind: String,
    val operation: String,
    val snapshotHash: String,
    val evidenceHash: String,
    val evidenceCompleteness: OperationAuthorityEvidenceCompleteness =
        OperationAuthorityEvidenceCompleteness.COMPLETE,
    val workspaceEditSha256: String? = null,
    requiredClasspathEvidence: Collection<ClasspathEvidence> = emptyList(),
    requiredFileEvidence: Collection<OperationAuthorityFileEvidence> = emptyList(),
    attributes: Map<String, String> = emptyMap(),
) {
    private val requiredClasspathEvidenceValues: List<ClasspathEvidence> =
        Collections.unmodifiableList(ArrayList(requiredClasspathEvidence))
    private val requiredFileEvidenceValues: List<OperationAuthorityFileEvidence> =
        Collections.unmodifiableList(ArrayList(requiredFileEvidence))
    private val attributeValues: Map<String, String> = Collections.unmodifiableMap(
        LinkedHashMap(attributes.toSortedMap()),
    )

    val requiredClasspathEvidence: List<ClasspathEvidence> get() = requiredClasspathEvidenceValues
    val requiredFileEvidence: List<OperationAuthorityFileEvidence> get() = requiredFileEvidenceValues
    val requiredFileEvidenceSha256: String = fileEvidenceSha256(requiredFileEvidenceValues)
    val attributes: Map<String, String> get() = attributeValues

    init {
        require(kind.isNotBlank()) { "operation-authority lease kind must not be blank" }
        require(operation.isNotBlank()) { "operation-authority lease operation must not be blank" }
        require(SHA256.matches(snapshotHash)) { "operation-authority snapshot hash must be SHA-256" }
        require(SHA256.matches(evidenceHash)) { "operation-authority evidence hash must be SHA-256" }
        require(workspaceEditSha256 == null || SHA256.matches(workspaceEditSha256)) {
            "operation-authority workspace-edit identity must be SHA-256 when present"
        }
        require(requiredClasspathEvidenceValues.distinctBy { it.path.normalize() to it.kind }.size ==
            requiredClasspathEvidenceValues.size
        ) { "operation-authority classpath evidence keys must be unique" }
        require(requiredFileEvidenceValues.distinctBy(OperationAuthorityFileEvidence::path).size ==
            requiredFileEvidenceValues.size
        ) { "operation-authority required file-evidence paths must be unique" }
        require(attributeValues.keys.all(String::isNotBlank)) {
            "operation-authority attribute keys must not be blank"
        }
    }

    override fun equals(other: Any?): Boolean = other is OperationAuthorityLease &&
        kind == other.kind && operation == other.operation && snapshotHash == other.snapshotHash &&
        evidenceHash == other.evidenceHash && evidenceCompleteness == other.evidenceCompleteness &&
        workspaceEditSha256 == other.workspaceEditSha256 &&
        requiredClasspathEvidenceValues == other.requiredClasspathEvidenceValues &&
        requiredFileEvidenceValues == other.requiredFileEvidenceValues && attributeValues == other.attributeValues

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + operation.hashCode()
        result = 31 * result + snapshotHash.hashCode()
        result = 31 * result + evidenceHash.hashCode()
        result = 31 * result + evidenceCompleteness.hashCode()
        result = 31 * result + workspaceEditSha256.hashCode()
        result = 31 * result + requiredClasspathEvidenceValues.hashCode()
        result = 31 * result + requiredFileEvidenceValues.hashCode()
        result = 31 * result + attributeValues.hashCode()
        return result
    }

    override fun toString(): String = "OperationAuthorityLease(kind=$kind, operation=$operation, " +
        "snapshotHash=$snapshotHash, evidenceHash=$evidenceHash, " +
        "evidenceCompleteness=$evidenceCompleteness, workspaceEditSha256=$workspaceEditSha256, " +
        "requiredClasspathEvidence=$requiredClasspathEvidenceValues, " +
        "requiredFileEvidence=$requiredFileEvidenceValues, attributes=$attributeValues)"

    companion object {
        private val SHA256 = Regex("[a-f0-9]{64}")

        fun fileEvidenceSha256(
            evidence: Collection<OperationAuthorityFileEvidence>,
            observedContentIdentities: Map<Path, String> = emptyMap(),
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            evidence.sortedBy { it.path.toString().replace('\\', '/') }.forEach { record ->
                listOf(
                    record.kind,
                    record.path.toString().replace('\\', '/'),
                    observedContentIdentities[record.path] ?: record.expectedContentSha256,
                ).forEach { part ->
                    digest.update(part.toByteArray(Charsets.UTF_8))
                    digest.update(0)
                }
                record.attributes.toSortedMap().forEach { (key, value) ->
                    digest.update(key.toByteArray(Charsets.UTF_8))
                    digest.update(0)
                    digest.update(value.toByteArray(Charsets.UTF_8))
                    digest.update(0)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

enum class DiagnosticEvidence {
    COMPILER,
    STRUCTURAL,
    TRANSACTION,
}

enum class DiagnosticCategory {
    SYNTAX,
    TYPE_RESOLUTION,
    PROJECT_STRUCTURE,
    SAFETY,
}

enum class DiagnosticLocationPrecision {
    EXACT_RANGE,
    LINE_ONLY,
    NONE,
}

/**
 * Language-neutral structured diagnostic data with deterministic iteration order.
 * The caller's map is copied so diagnostics cannot change after construction.
 */
class DiagnosticDetails @JvmOverloads constructor(fields: Map<String, String> = emptyMap()) {
    private val fieldValues: Map<String, String> = Collections.unmodifiableMap(
        LinkedHashMap(fields.toSortedMap()),
    )

    val fields: Map<String, String> get() = fieldValues
    val isEmpty: Boolean get() = fieldValues.isEmpty()

    init {
        require(fieldValues.keys.all(String::isNotBlank)) {
            "diagnostic detail field names must not be blank"
        }
    }

    operator fun get(field: String): String? = fieldValues[field]

    override fun equals(other: Any?): Boolean = other is DiagnosticDetails && fieldValues == other.fieldValues

    override fun hashCode(): Int = fieldValues.hashCode()

    override fun toString(): String = "DiagnosticDetails(fields=$fieldValues)"

    companion object {
        @JvmField
        val EMPTY: DiagnosticDetails = DiagnosticDetails()
    }
}

data class Diagnostic @JvmOverloads constructor(
    val message: String,
    val severity: Severity,
    val location: SourceLocation? = null,
    val code: String? = null,
    val evidence: DiagnosticEvidence? = null,
    val category: DiagnosticCategory? = null,
    val locationPrecision: DiagnosticLocationPrecision =
        if (location == null) DiagnosticLocationPrecision.NONE else DiagnosticLocationPrecision.EXACT_RANGE,
    val details: DiagnosticDetails = DiagnosticDetails.EMPTY,
) {
    init {
        require((location == null) == (locationPrecision == DiagnosticLocationPrecision.NONE)) {
            "diagnostic location and precision must agree"
        }
    }
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
    val evidence: RefactoringEvidence = RefactoringEvidence.STRUCTURAL,
    val refusalCode: String? = null,
    val authorityLease: OperationAuthorityLease? = null,
)

enum class ApprovalKind {
    EXPLICIT_APPLY,
    NOT_REQUIRED,
    LEGACY_UNRECORDED,
}

data class ApplyAuthorization(
    val approved: Boolean,
    val surface: String,
    val actor: String,
) {
    init {
        require(surface.isNotBlank()) { "approval surface must not be blank" }
        require(actor.isNotBlank()) { "approval actor must not be blank" }
    }

    companion object {
        fun explicit(surface: String, actor: String = "caller") = ApplyAuthorization(true, surface, actor)
        fun missing(surface: String, actor: String = "caller") = ApplyAuthorization(false, surface, actor)
    }
}

data class ApprovalRecord(
    val kind: ApprovalKind,
    val surface: String,
    val actor: String,
    val recordedAt: Instant,
) {
    companion object {
        fun legacy() = ApprovalRecord(ApprovalKind.LEGACY_UNRECORDED, "legacy", "unknown", Instant.EPOCH)
    }
}

data class Transaction(
    val id: TransactionId = TransactionId.new(),
    val planId: PlanId,
    val appliedAt: Instant = Instant.now(),
    val snapshotHashBefore: String,
    val rollbackEdit: WorkspaceEdit,
    val approval: ApprovalRecord = ApprovalRecord.legacy(),
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
        OBJECT,
        INTERFACE,
        ENUM,
        RECORD,
        ANNOTATION,
        METHOD,
        FUNCTION,
        FIELD,
        PROPERTY,
        VARIABLE,
        CONSTANT,
        PARAMETER,
        TYPE_ALIAS,
        TYPE_PARAMETER,
        NAMESPACE,
        MODULE,
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
