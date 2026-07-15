package org.refactorkit.core

import java.nio.file.Path
import java.security.MessageDigest

data class WorkspaceIndexSourceOwnership(
    val providerId: String,
    val moduleId: String,
    val sourceSetId: String,
    val generated: Boolean,
)

/** Immutable saved-document inventory entry bound to one exact ProjectSnapshot. */
data class WorkspaceIndexedSource(
    val path: Path,
    val languageId: String,
    val contentSha256: String,
    val utf8Bytes: Long,
    val documentMode: SemanticDocumentMode = SemanticDocumentMode.SAVED_SNAPSHOT,
    val documentVersion: Long? = null,
    val ownerships: List<WorkspaceIndexSourceOwnership> = emptyList(),
)

enum class WorkspaceIndexCompleteness {
    SOURCE_INVENTORY,
    DECLARATIONS,
    SEMANTIC,
}

/** Bounded symbol partition supplied by one language/backend for one snapshot. */
data class WorkspaceSymbolContribution(
    val providerId: String,
    val languageId: String,
    val backend: String,
    val evidence: SemanticEvidenceKind,
    val completeness: WorkspaceIndexCompleteness,
    val snapshotHash: String,
    val symbols: List<Symbol>,
    val truncated: Boolean = false,
    val provenanceHash: String? = null,
) {
    init {
        require(PROVIDER_ID.matches(providerId)) { "workspace index provider ID is invalid" }
        require(LANGUAGE_ID.matches(languageId)) { "workspace index language ID is invalid" }
        require(backend.isNotBlank() && backend.length <= 128) { "workspace index backend is invalid" }
        require(evidence != SemanticEvidenceKind.NONE) { "workspace index contribution requires evidence" }
        require(completeness != WorkspaceIndexCompleteness.SOURCE_INVENTORY) {
            "symbol contribution cannot claim source-inventory-only completeness"
        }
        require(provenanceHash == null || Regex("[0-9a-f]{64}").matches(provenanceHash)) {
            "workspace index provider provenance hash is invalid"
        }
        require(symbols.size <= ProtocolLimits.MAX_WORKSPACE_INDEX_PROVIDER_SYMBOLS) {
            "workspace index provider symbol limit exceeded"
        }
    }

    companion object {
        private val PROVIDER_ID = Regex("[a-z][a-z0-9.-]{0,127}")
        private val LANGUAGE_ID = Regex("[a-z][a-z0-9-]{0,63}")
    }
}

data class WorkspaceIndexedSymbol(
    val providerId: String,
    val backend: String,
    val evidence: SemanticEvidenceKind,
    val completeness: WorkspaceIndexCompleteness,
    val symbol: Symbol,
)

data class WorkspaceIndexChanges(
    val added: Set<Path>,
    val modified: Set<Path>,
    val deleted: Set<Path>,
    val unchanged: Set<Path>,
) {
    val changed: Set<Path> = added + modified + deleted
}

data class WorkspaceIndexReconciliation(
    val index: WorkspaceIndex,
    val changes: WorkspaceIndexChanges,
    val invalidatedProviders: Set<String>,
)

/**
 * Provider-neutral, immutable workspace index.
 *
 * Compiler/language-server objects stay inside adapters. Core stores only
 * normalized durable source and symbol projections.
 */
class WorkspaceIndex private constructor(
    val snapshotHash: String,
    val generation: Long,
    val sources: List<WorkspaceIndexedSource>,
    val contributions: List<WorkspaceSymbolContribution>,
    private val sourceLineLengths: Map<Path, IntArray>,
) {
    private val sourceByPath = sources.associateBy(WorkspaceIndexedSource::path)

    init {
        require(SHA256.matches(snapshotHash)) { "workspace index snapshot hash is invalid" }
        require(generation >= 1) { "workspace index generation must be positive" }
        require(sources.size <= ProtocolLimits.MAX_WORKSPACE_INDEX_SOURCES) {
            "workspace index source limit exceeded"
        }
        require(sources.map(WorkspaceIndexedSource::path).distinct().size == sources.size) {
            "workspace index source paths must be unique"
        }
        require(contributions.map(WorkspaceSymbolContribution::providerId).distinct().size == contributions.size) {
            "workspace index provider IDs must be unique"
        }
        require(contributions.sumOf { it.symbols.size } <= ProtocolLimits.MAX_WORKSPACE_INDEX_SYMBOLS) {
            "workspace index total symbol limit exceeded"
        }
        contributions.forEach(::validateContribution)
    }

    val symbolCount: Int = contributions.sumOf { it.symbols.size }

    fun withContribution(contribution: WorkspaceSymbolContribution): WorkspaceIndex {
        require(contribution.snapshotHash == snapshotHash) { "workspace index contribution snapshot is stale" }
        val updated = (contributions.filterNot { it.providerId == contribution.providerId } + contribution)
            .sortedBy(WorkspaceSymbolContribution::providerId)
        return WorkspaceIndex(snapshotHash, generation + 1, sources, updated, sourceLineLengths)
    }

    fun withoutProvider(providerId: String): WorkspaceIndex = WorkspaceIndex(
        snapshotHash,
        generation + 1,
        sources,
        contributions.filterNot { it.providerId == providerId },
        sourceLineLengths,
    )

    fun searchSymbols(
        query: String = "",
        languageId: String? = null,
        path: Path? = null,
    ): List<WorkspaceIndexedSymbol> {
        require(query.length <= ProtocolLimits.MAX_INTELLIGENCE_QUERY_CHARS) {
            "workspace index query is too long"
        }
        val normalizedPath = path?.let(::normalizeRelative)
        val evidenceOrder = mapOf(
            SemanticEvidenceKind.COMPILER to 0,
            SemanticEvidenceKind.LANGUAGE_SERVER to 1,
            SemanticEvidenceKind.NATIVE_AST to 2,
            SemanticEvidenceKind.STRUCTURAL_PARSE to 3,
            SemanticEvidenceKind.LEXICAL to 4,
            SemanticEvidenceKind.NONE to 5,
        )
        return contributions.asSequence()
            .filter { languageId == null || it.languageId == languageId }
            .flatMap { contribution -> contribution.symbols.asSequence().map { symbol ->
                WorkspaceIndexedSymbol(
                    contribution.providerId,
                    contribution.backend,
                    contribution.evidence,
                    contribution.completeness,
                    symbol,
                )
            } }
            .filter { indexed ->
                (normalizedPath == null || indexed.symbol.location.path == normalizedPath) &&
                    (query.isBlank() || indexed.symbol.name.contains(query, ignoreCase = true) ||
                        indexed.symbol.id.value.contains(query, ignoreCase = true))
            }
            .sortedWith(compareBy<WorkspaceIndexedSymbol>(
                { it.symbol.languageId },
                { it.symbol.name.lowercase() },
                { it.symbol.id.value },
                { evidenceOrder.getValue(it.evidence) },
                { it.providerId },
                { it.symbol.location.path.toString() },
                { it.symbol.location.range.start.line },
                { it.symbol.location.range.start.character },
            ))
            .distinctBy { indexed -> Triple(
                indexed.symbol.languageId,
                indexed.symbol.id,
                indexed.symbol.location,
            ) }
            .toList()
    }

    /**
     * Reconcile source inventory. A provider partition survives only when every
     * source of its language is unchanged; semantic dependency invalidation is
     * deliberately conservative.
     */
    fun reconcile(snapshot: ProjectSnapshot): WorkspaceIndexReconciliation {
        val nextSources = indexSources(snapshot)
        val nextByPath = nextSources.associateBy(WorkspaceIndexedSource::path)
        val oldPaths = sourceByPath.keys
        val nextPaths = nextByPath.keys
        val added = nextPaths - oldPaths
        val deleted = oldPaths - nextPaths
        val shared = oldPaths intersect nextPaths
        val modified = shared.filterTo(linkedSetOf()) { sourceByPath.getValue(it) != nextByPath.getValue(it) }
        val unchanged = shared - modified
        val sourceChanges = added + deleted + modified
        val nonSourceEvidenceChanged = snapshot.hash != snapshotHash && sourceChanges.isEmpty()
        val changedLanguages = if (nonSourceEvidenceChanged) {
            contributions.mapTo(linkedSetOf(), WorkspaceSymbolContribution::languageId)
        } else sourceChanges.mapNotNullTo(linkedSetOf()) { path ->
            nextByPath[path]?.languageId ?: sourceByPath[path]?.languageId
        }
        val retained = contributions.filterNot { it.languageId in changedLanguages }
            .map { it.copy(snapshotHash = snapshot.hash) }
        val invalidated = contributions.filter { it.languageId in changedLanguages }
            .mapTo(sortedSetOf(), WorkspaceSymbolContribution::providerId)
        return WorkspaceIndexReconciliation(
            index = WorkspaceIndex(
                snapshot.hash,
                generation + 1,
                nextSources,
                retained,
                snapshot.files.associate { normalizeRelative(it.path) to lineLengths(it.content) },
            ),
            changes = WorkspaceIndexChanges(added, modified, deleted, unchanged),
            invalidatedProviders = invalidated,
        )
    }

    private fun validateContribution(contribution: WorkspaceSymbolContribution) {
        require(contribution.snapshotHash == snapshotHash) { "workspace index contribution snapshot is stale" }
        require(contribution.symbols.map { it.id to it.location }.distinct().size == contribution.symbols.size) {
            "workspace index provider symbol entries must be unique"
        }
        contribution.symbols.forEach { symbol ->
            require(symbol.languageId == contribution.languageId) {
                "workspace index symbol language does not match provider"
            }
            val path = normalizeRelative(symbol.location.path)
            val source = sourceByPath[path] ?: error("workspace index symbol source is not indexed: $path")
            require(source.languageId == symbol.languageId) { "workspace index source language mismatch" }
            require(validPosition(sourceLineLengths.getValue(path), symbol.location.range.start) &&
                validPosition(sourceLineLengths.getValue(path), symbol.location.range.end)) {
                "workspace index symbol range is outside source content"
            }
        }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun create(snapshot: ProjectSnapshot, generation: Long = 1): WorkspaceIndex {
            return WorkspaceIndex(
                snapshot.hash,
                generation,
                indexSources(snapshot),
                emptyList(),
                snapshot.files.associate { normalizeRelative(it.path) to lineLengths(it.content) },
            )
        }

        private fun indexSources(snapshot: ProjectSnapshot): List<WorkspaceIndexedSource> {
            require(snapshot.files.size <= ProtocolLimits.MAX_WORKSPACE_INDEX_SOURCES) {
                "workspace index source limit exceeded"
            }
            return snapshot.files.map { source ->
                val path = normalizeRelative(source.path)
                val bytes = source.content.toByteArray(Charsets.UTF_8)
                val ownerships = snapshot.owningBuildSourceRoots(path).map { ownership ->
                    WorkspaceIndexSourceOwnership(
                        ownership.providerId,
                        ownership.module.id,
                        ownership.sourceSet.id,
                        ownership.generated,
                    )
                }.distinct().sortedWith(compareBy(
                    WorkspaceIndexSourceOwnership::providerId,
                    WorkspaceIndexSourceOwnership::moduleId,
                    WorkspaceIndexSourceOwnership::sourceSetId,
                ))
                WorkspaceIndexedSource(
                    path,
                    source.languageId,
                    sha256(bytes),
                    bytes.size.toLong(),
                    ownerships = ownerships,
                )
            }.sortedBy { it.path.toString() }
        }

        private fun normalizeRelative(path: Path): Path {
            val normalized = path.normalize()
            require(!normalized.isAbsolute && normalized.toString().isNotBlank() && !normalized.startsWith("..")) {
                "workspace index path must be workspace-relative"
            }
            return normalized
        }

        private fun lineLengths(content: String): IntArray {
            val lengths = ArrayList<Int>()
            var start = 0
            content.forEachIndexed { index, character ->
                if (character == '\n') {
                    lengths += index - start
                    start = index + 1
                }
            }
            lengths += content.length - start
            return lengths.toIntArray()
        }

        private fun validPosition(lineLengths: IntArray, position: SourcePosition): Boolean =
            position.line in lineLengths.indices && position.character <= lineLengths[position.line]

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Mutable ownership is session-scoped; each published index value is immutable. */
class WorkspaceIndexSession {
    @Volatile private var current: WorkspaceIndex? = null

    @Synchronized
    fun open(snapshot: ProjectSnapshot): WorkspaceIndex {
        val previous = current
        val index = if (previous == null) WorkspaceIndex.create(snapshot) else previous.reconcile(snapshot).index
        current = index
        return index
    }

    @Synchronized
    fun reconcile(snapshot: ProjectSnapshot): WorkspaceIndexReconciliation {
        val index = requireNotNull(current) { "workspace index session is not open" }
        val reconciliation = index.reconcile(snapshot)
        current = reconciliation.index
        return reconciliation
    }

    @Synchronized
    fun contribute(contribution: WorkspaceSymbolContribution): WorkspaceIndex {
        val index = requireNotNull(current) { "workspace index session is not open" }
        val updated = index.withContribution(contribution)
        current = updated
        return updated
    }

    @Synchronized
    fun removeProvider(providerId: String): WorkspaceIndex? {
        val updated = current?.withoutProvider(providerId)
        current = updated
        return updated
    }

    fun snapshot(): WorkspaceIndex? = current

    @Synchronized
    fun clear() {
        current = null
    }
}
