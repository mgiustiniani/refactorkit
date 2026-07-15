package org.refactorkit.typescript

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.buildSourceRootOwnerships
import org.refactorkit.core.CapabilityStability
import org.refactorkit.core.CodeSelection
import org.refactorkit.core.CompletionTrigger
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.FileEdit
import org.refactorkit.core.ImmutableEditorOverlay
import org.refactorkit.core.LanguageAdapter
import org.refactorkit.core.LanguageAdapterDescriptor
import org.refactorkit.core.LanguageCapability
import org.refactorkit.core.MutationAuthority
import org.refactorkit.core.ParseResult
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.Reference
import org.refactorkit.core.RefactoringDescriptor
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SemanticCompletionItem
import org.refactorkit.core.SemanticEvidenceKind
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.SymbolId
import org.refactorkit.core.SymbolIndex
import org.refactorkit.core.SymbolResolution
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.treesitter.BonedeTreeSitterBinding
import org.refactorkit.treesitter.ExternalCompletionProjection
import org.refactorkit.treesitter.ExternalLspAdapter
import org.refactorkit.treesitter.ExternalHoverProjection
import org.refactorkit.treesitter.ExternalSemanticDiagnostics
import org.refactorkit.treesitter.ExternalSemanticSessionProvenance
import org.refactorkit.treesitter.ExternalSymbolProjection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.security.MessageDigest

enum class TypeScriptSemanticCompletenessMode {
    FULL_TYPESCRIPT,
    CHECKED_JAVASCRIPT,
    DYNAMIC_JAVASCRIPT,
    MIXED_JAVASCRIPT,
}

data class TypeScriptSemanticCompleteness(
    val mode: TypeScriptSemanticCompletenessMode,
    val managedMutationEligible: Boolean,
    val summary: String,
)

sealed interface TypeScriptSemanticStart {
    data class Started(val provenance: ExternalSemanticSessionProvenance?) : TypeScriptSemanticStart
    data class Refused(val diagnostics: List<Diagnostic>) : TypeScriptSemanticStart
}

sealed interface TypeScriptClientSymbolProjection {
    data class Available(val index: SymbolIndex, val truncated: Boolean) : TypeScriptClientSymbolProjection
    data class Refused(val code: String, val message: String) : TypeScriptClientSymbolProjection
}

sealed interface TypeScriptCompletionProjection {
    data class Available(
        val items: List<SemanticCompletionItem>,
        val incomplete: Boolean,
        val provenanceHash: String,
    ) : TypeScriptCompletionProjection
    data class Refused(val diagnostic: Diagnostic) : TypeScriptCompletionProjection
}

sealed interface TypeScriptHoverProjection {
    data class Available(
        val range: SourceRange?,
        val sections: List<org.refactorkit.core.SemanticHoverSection>,
        val provenanceHash: String,
    ) : TypeScriptHoverProjection
    data class Refused(val diagnostic: Diagnostic) : TypeScriptHoverProjection
}

sealed interface TypeScriptSymbolProjection {
    data class Available(
        val index: SymbolIndex,
        val truncated: Boolean,
        val provenanceHash: String,
    ) : TypeScriptSymbolProjection

    data class Refused(val diagnostic: Diagnostic) : TypeScriptSymbolProjection
}

interface TypeScriptSemanticClient : AutoCloseable {
    val isRunning: Boolean
    val provenance: ExternalSemanticSessionProvenance?
    fun start(snapshot: ProjectSnapshot)
    fun supports(capability: String): Boolean
    fun buildSymbols(snapshot: ProjectSnapshot): SymbolIndex
    fun buildSymbolProjection(snapshot: ProjectSnapshot, limit: Int): TypeScriptClientSymbolProjection {
        val symbols = buildSymbols(snapshot).symbols
        return TypeScriptClientSymbolProjection.Available(
            SymbolIndex(symbols.take(limit)),
            symbols.size > limit,
        )
    }
    fun buildOverlayDocumentSymbols(
        savedSnapshot: ProjectSnapshot,
        overlay: ImmutableEditorOverlay,
        targetPath: Path,
        limit: Int,
    ): TypeScriptClientSymbolProjection {
        val symbols = buildSymbols(overlay.providerSnapshot).symbols.filter { it.location.path.normalize() == targetPath.normalize() }
        return TypeScriptClientSymbolProjection.Available(SymbolIndex(symbols.take(limit)), symbols.size > limit)
    }
    fun buildOverlayCompletion(
        savedSnapshot: ProjectSnapshot,
        overlay: ImmutableEditorOverlay,
        targetPath: Path,
        position: SourcePosition,
        trigger: CompletionTrigger,
        triggerCharacter: String?,
        limit: Int,
    ): TypeScriptCompletionProjection = TypeScriptCompletionProjection.Refused(Diagnostic(
        "TypeScript completion is unavailable", Diagnostic.Severity.ERROR, code = "semantic.completionUnavailable",
    ))
    fun buildOverlayHover(
        savedSnapshot: ProjectSnapshot,
        overlay: ImmutableEditorOverlay,
        targetPath: Path,
        position: SourcePosition,
    ): TypeScriptHoverProjection = TypeScriptHoverProjection.Refused(Diagnostic(
        "TypeScript hover is unavailable", Diagnostic.Severity.ERROR, code = "semantic.hoverUnavailable",
    ))
    fun searchWorkspaceSymbols(query: String): List<org.refactorkit.core.Symbol>
    fun resolveSymbol(location: SourceLocation): SymbolResolution
    fun findReferences(symbolId: SymbolId): List<Reference>
    fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic>
    fun synchronizedDiagnostics(snapshot: ProjectSnapshot): ExternalSemanticDiagnostics
    fun requestRename(snapshot: ProjectSnapshot, location: SourceLocation, newName: String): ExternalWorkspaceEditNormalization
}

class ExternalTypeScriptSemanticClient(
    languageId: String,
    toolchain: TypeScriptSemanticToolchain,
    projectModel: TypeScriptProjectModel,
) : TypeScriptSemanticClient {
    private val adapter = ExternalLspAdapter(
        languageId,
        toolchain.command,
        initializationOptionsJson = buildJsonObject {
            put("tsserver", buildJsonObject {
                put("path", toolchain.typeScriptServerEntrypoint.toAbsolutePath().normalize().toString())
            })
        }.toString(),
    )
    private val compilerDiagnostics = TypeScriptCompilerDiagnostics(toolchain, projectModel)
    private val evidencePaths = projectModel.evidence.map { it.path.normalize() }.toSet()
    private var auxiliaryFiles: List<SourceFile> = emptyList()
    override val isRunning: Boolean get() = adapter.isRunning
    override val provenance: ExternalSemanticSessionProvenance? get() = adapter.sessionProvenance
    override fun start(snapshot: ProjectSnapshot) {
        auxiliaryFiles = snapshot.files.filter { it.path.normalize() in evidencePaths }
        adapter.start(snapshot)
    }
    override fun supports(capability: String): Boolean = adapter.supportsServerCapability(capability)
    override fun buildSymbols(snapshot: ProjectSnapshot): SymbolIndex = adapter.buildSymbols(snapshot)
    override fun buildSymbolProjection(snapshot: ProjectSnapshot, limit: Int): TypeScriptClientSymbolProjection =
        when (val projection = adapter.buildSymbolProjection(snapshot, limit)) {
            is ExternalSymbolProjection.Available -> TypeScriptClientSymbolProjection.Available(
                projection.index,
                projection.truncated,
            )
            is ExternalSymbolProjection.Unavailable -> TypeScriptClientSymbolProjection.Refused(
                projection.failure.code,
                projection.failure.message,
            )
        }
    override fun buildOverlayDocumentSymbols(
        savedSnapshot: ProjectSnapshot,
        overlay: ImmutableEditorOverlay,
        targetPath: Path,
        limit: Int,
    ): TypeScriptClientSymbolProjection = when (val projection = adapter.buildOverlayDocumentSymbolProjection(
        savedSnapshot, overlay, targetPath, limit,
    )) {
        is ExternalSymbolProjection.Available -> TypeScriptClientSymbolProjection.Available(
            projection.index, projection.truncated,
        )
        is ExternalSymbolProjection.Unavailable -> TypeScriptClientSymbolProjection.Refused(
            projection.failure.code, projection.failure.message,
        )
    }
    override fun buildOverlayCompletion(
        savedSnapshot: ProjectSnapshot,
        overlay: ImmutableEditorOverlay,
        targetPath: Path,
        position: SourcePosition,
        trigger: CompletionTrigger,
        triggerCharacter: String?,
        limit: Int,
    ): TypeScriptCompletionProjection = when (val projection = adapter.buildOverlayCompletion(
        savedSnapshot, overlay, targetPath, position, trigger, triggerCharacter, limit,
    )) {
        is ExternalCompletionProjection.Available -> TypeScriptCompletionProjection.Available(
            projection.items, projection.incomplete, "",
        )
        is ExternalCompletionProjection.Unavailable -> TypeScriptCompletionProjection.Refused(Diagnostic(
            projection.failure.message, Diagnostic.Severity.ERROR, code = projection.failure.code,
        ))
    }
    override fun buildOverlayHover(
        savedSnapshot: ProjectSnapshot,
        overlay: ImmutableEditorOverlay,
        targetPath: Path,
        position: SourcePosition,
    ): TypeScriptHoverProjection = when (val projection = adapter.buildOverlayHover(
        savedSnapshot, overlay, targetPath, position,
    )) {
        is ExternalHoverProjection.Available -> TypeScriptHoverProjection.Available(
            projection.range, projection.sections, "",
        )
        is ExternalHoverProjection.Unavailable -> TypeScriptHoverProjection.Refused(Diagnostic(
            projection.failure.message, Diagnostic.Severity.ERROR, code = projection.failure.code,
        ))
    }
    override fun searchWorkspaceSymbols(query: String): List<org.refactorkit.core.Symbol> =
        adapter.searchWorkspaceSymbols(query)
    override fun resolveSymbol(location: SourceLocation): SymbolResolution = adapter.resolveSymbol(location)
    override fun findReferences(symbolId: SymbolId): List<Reference> = adapter.findReferences(symbolId)
    override fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = adapter.diagnostics(snapshot)
    override fun synchronizedDiagnostics(snapshot: ProjectSnapshot): ExternalSemanticDiagnostics =
        compilerDiagnostics.analyze(snapshot, auxiliaryFiles)
    override fun requestRename(
        snapshot: ProjectSnapshot,
        location: SourceLocation,
        newName: String,
    ): ExternalWorkspaceEditNormalization = adapter.requestRename(snapshot, location, newName)
    override fun close() {
        auxiliaryFiles = emptyList()
        adapter.close()
    }
}

/** Experimental compiler/LSP-backed adapter. It returns proposals, never direct writes. */
class TypeScriptSemanticAdapter(
    private val languageId: String,
    private val toolchain: TypeScriptSemanticToolchain,
    private val projectModel: TypeScriptProjectModel,
    private val client: TypeScriptSemanticClient = ExternalTypeScriptSemanticClient(languageId, toolchain, projectModel),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : LanguageAdapter, AutoCloseable {
    private var activeSnapshot: ProjectSnapshot? = null
    private var acceptedServerProvenance: ServerProvenanceSignature? = null
    private val restartAttempts = ArrayDeque<Long>()
    private var lastRestartMillis: Long? = null
    private val approvedStagedSnapshotHashes = linkedSetOf<String>()
    private val overlayVersions = linkedMapOf<Path, Pair<Long, String>>()

    init {
        require(languageId in setOf("typescript", "javascript")) { "TypeScript semantic adapter language is invalid" }
    }

    fun start(snapshot: ProjectSnapshot): TypeScriptSemanticStart {
        if (client.isRunning) return refusedStart("typescript.semanticAlreadyStarted", "TypeScript semantic adapter is already started")
        if (activeSnapshot != null) return refusedStart(
            "typescript.semanticRestartRequired", "Crashed TypeScript semantic sessions must use bounded restart",
        )
        if (projectModel.status != TypeScriptProjectModelStatus.AVAILABLE) {
            return TypeScriptSemanticStart.Refused(projectModel.diagnostics.ifEmpty {
                listOf(diagnostic("typescript.modelUnavailable", "TypeScript project model is unavailable"))
            })
        }
        val buildModel = snapshot.buildModels.singleOrNull { it.providerId == TypeScriptProjectModel.PROVIDER_ID }
        if (buildModel == null || buildModel.status != BuildModelStatus.AVAILABLE ||
            buildModel.attributes["projectionHash"] != projectModel.projectionHash) {
            return refusedStart(
                "typescript.modelSnapshotMismatch",
                "TypeScript project model is missing from or stale against ProjectSnapshot",
            )
        }
        if (toolchain.provenance.evidence.any { !verifyEvidence(it) }) {
            return refusedStart("typescript.toolchainEvidenceChanged", "TypeScript semantic toolchain changed after discovery")
        }
        val auxiliary = mutableListOf<SourceFile>()
        for (item in projectModel.evidence) {
            val absolute = snapshot.workspace.root.toAbsolutePath().normalize().resolve(item.path).normalize()
            if (!absolute.startsWith(snapshot.workspace.root.toAbsolutePath().normalize()) ||
                !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(absolute)) {
                return refusedStart("typescript.modelEvidenceChanged", "TypeScript project evidence is missing or unsafe")
            }
            val bytes = runCatching { Files.readAllBytes(absolute) }.getOrNull()
                ?: return refusedStart("typescript.modelEvidenceChanged", "TypeScript project evidence cannot be read")
            if (bytes.size.toLong() != item.size || sha256(bytes) != item.sha256) {
                return refusedStart("typescript.modelEvidenceChanged", "TypeScript project evidence changed after discovery")
            }
            val text = decodeUtf8(bytes)
                ?: return refusedStart("typescript.modelEvidenceInvalid", "TypeScript project evidence is not UTF-8")
            if (snapshot.files.none { it.path.normalize() == item.path.normalize() }) {
                auxiliary += SourceFile(item.path.normalize(), text, "jsonc")
            }
        }
        val semanticSnapshot = snapshot.copy(files = (snapshot.files + auxiliary).sortedBy { it.path.toString() })
        return try {
            client.start(semanticSnapshot)
            val missing = REQUIRED_CAPABILITIES.filterNot(client::supports)
            if (missing.isNotEmpty()) {
                client.close()
                refusedStart(
                    "typescript.serverCapabilityMissing",
                    "TypeScript language server lacks required capabilities: ${missing.joinToString(",")}",
                )
            } else {
                val actualProvenance = client.provenance?.let(::provenanceSignature)
                if (acceptedServerProvenance != null && actualProvenance != acceptedServerProvenance) {
                    client.close()
                    refusedStart(
                        "typescript.serverProvenanceChanged",
                        "TypeScript language-server provenance changed across restart",
                    )
                } else {
                    if (acceptedServerProvenance == null) acceptedServerProvenance = actualProvenance
                    activeSnapshot = snapshot
                    TypeScriptSemanticStart.Started(client.provenance)
                }
            }
        } catch (failure: Exception) {
            client.close()
            refusedStart("typescript.serverStartFailed", failure.message ?: "TypeScript language server failed to start")
        }
    }

    /** Explicit, bounded crash recovery; callers never get an implicit process restart. */
    fun restart(snapshot: ProjectSnapshot): TypeScriptSemanticStart {
        val previous = activeSnapshot ?: return refusedStart(
            "typescript.semanticNotStarted", "TypeScript semantic adapter has no session to restart",
        )
        if (client.isRunning) return refusedStart(
            "typescript.semanticAlreadyStarted", "TypeScript semantic adapter is still running",
        )
        if (previous.hash != snapshot.hash) return refusedStart(
            "typescript.restartSnapshotMismatch", "TypeScript semantic restart requires the original snapshot",
        )
        val now = currentTimeMillis()
        if (lastRestartMillis?.let { now < it } == true) return refusedStart(
            "typescript.restartClockInvalid", "TypeScript semantic restart clock moved backwards",
        )
        lastRestartMillis = now
        while (restartAttempts.firstOrNull()?.let { now - it >= RESTART_WINDOW_MILLIS } == true) {
            restartAttempts.removeFirst()
        }
        if (restartAttempts.size >= MAX_RESTARTS_PER_WINDOW) return refusedStart(
            "typescript.restartLimitExceeded",
            "TypeScript semantic restart limit of $MAX_RESTARTS_PER_WINDOW per ${RESTART_WINDOW_MILLIS / 1_000}s was exceeded",
        )
        restartAttempts.addLast(now)
        client.close()
        activeSnapshot = null
        overlayVersions.clear()
        val result = start(snapshot)
        if (result is TypeScriptSemanticStart.Refused) activeSnapshot = previous
        return result
    }

    fun sessionProvenance(): ExternalSemanticSessionProvenance? = client.provenance
    fun isRunning(): Boolean = client.isRunning
    fun activeSnapshotHash(): String? = activeSnapshot?.hash
    fun compilerAttestation(): TypeScriptCompilerAttestation = toolchain.compilerAttestation()

    /** Exact compiler diagnostics for the saved snapshot or a validated immutable source overlay. */
    fun exactDiagnostics(snapshot: ProjectSnapshot): ExternalSemanticDiagnostics {
        if (!diagnosticScopeCompatible(snapshot)) return unavailableDiagnostics(
            "typescript.diagnosticsScopeMismatch",
            "Exact diagnostics snapshot is outside the active semantic lease",
        )
        if (toolchain.provenance.evidence.any { !verifyEvidence(it) }) return unavailableDiagnostics(
            "typescript.toolchainEvidenceChanged",
            "TypeScript semantic toolchain changed after session startup",
        )
        if (!projectEvidenceUnchanged(snapshot.workspace.root)) return unavailableDiagnostics(
            "typescript.modelEvidenceChanged",
            "TypeScript project evidence changed after session startup",
        )
        return client.synchronizedDiagnostics(snapshot)
    }

    override fun languageId(): String = languageId
    override fun parse(file: SourceFile): ParseResult = ParseResult(file)

    override fun buildSymbols(project: ProjectSnapshot): SymbolIndex =
        if (active(project)) client.buildSymbols(project) else SymbolIndex(emptyList())

    fun symbolProjection(
        project: ProjectSnapshot,
        limit: Int = org.refactorkit.core.ProtocolLimits.MAX_WORKSPACE_INDEX_PROVIDER_SYMBOLS,
    ): TypeScriptSymbolProjection {
        require(limit in 1..org.refactorkit.core.ProtocolLimits.MAX_WORKSPACE_INDEX_PROVIDER_SYMBOLS) {
            "TypeScript symbol projection limit is outside the safe range"
        }
        if (!active(project)) return TypeScriptSymbolProjection.Refused(diagnostic(
            "typescript.symbolIndexSessionStale",
            "TypeScript symbol projection requires the active saved snapshot",
        ))
        if (toolchain.provenance.evidence.any { !verifyEvidence(it) }) {
            return TypeScriptSymbolProjection.Refused(diagnostic(
                "typescript.toolchainEvidenceChanged",
                "TypeScript semantic toolchain changed before symbol projection",
            ))
        }
        if (!projectEvidenceUnchanged(project.workspace.root)) return TypeScriptSymbolProjection.Refused(diagnostic(
            "typescript.modelEvidenceChanged",
            "TypeScript project evidence changed before symbol projection",
        ))
        val languageFileCount = project.files.count { it.languageId == languageId }
        if (languageFileCount > ExternalLspAdapter.MAX_DOCUMENT_SYMBOL_FILES) {
            return TypeScriptSymbolProjection.Refused(diagnostic(
                "typescript.symbolIndexFileLimit",
                "TypeScript symbol projection exceeds ${ExternalLspAdapter.MAX_DOCUMENT_SYMBOL_FILES} source files",
            ))
        }
        return try {
            when (val projection = client.buildSymbolProjection(project, limit)) {
                is TypeScriptClientSymbolProjection.Available -> TypeScriptSymbolProjection.Available(
                    projection.index,
                    projection.truncated,
                    symbolProjectionProvenanceHash(),
                )
                is TypeScriptClientSymbolProjection.Refused -> TypeScriptSymbolProjection.Refused(diagnostic(
                    projection.code,
                    projection.message,
                ))
            }
        } catch (failure: Exception) {
            TypeScriptSymbolProjection.Refused(diagnostic(
                "typescript.symbolIndexUnavailable",
                failure.message ?: "TypeScript language server symbol projection failed",
            ))
        }
    }

    fun overlayCompletion(
        savedSnapshot: ProjectSnapshot,
        overlay: ImmutableEditorOverlay,
        targetPath: Path,
        position: SourcePosition,
        trigger: CompletionTrigger,
        triggerCharacter: String?,
        limit: Int,
    ): TypeScriptCompletionProjection {
        validateOverlayRequest(savedSnapshot, overlay)?.let { return TypeScriptCompletionProjection.Refused(it) }
        val source = overlay.providerSnapshot.files.singleOrNull { it.path.normalize() == targetPath.normalize() }
            ?: return TypeScriptCompletionProjection.Refused(diagnostic(
                "typescript.completionTargetMissing", "Completion target is not part of the editor overlay",
            ))
        if (!validPosition(source.content, position)) return TypeScriptCompletionProjection.Refused(diagnostic(
            "typescript.completionPositionInvalid", "Completion position is outside the overlay document",
        ))
        return when (val result = client.buildOverlayCompletion(
            savedSnapshot, overlay, targetPath, position, trigger, triggerCharacter, limit,
        )) {
            is TypeScriptCompletionProjection.Available -> {
                if (!validCompletionEdits(source.content, result.items)) return TypeScriptCompletionProjection.Refused(diagnostic(
                    "typescript.completionEditInvalid", "Completion provider returned an out-of-document or overlapping edit",
                ))
                rememberOverlayVersions(overlay)
                result.copy(provenanceHash = symbolProjectionProvenanceHash())
            }
            is TypeScriptCompletionProjection.Refused -> result
        }
    }

    fun overlayHover(
        savedSnapshot: ProjectSnapshot,
        overlay: ImmutableEditorOverlay,
        targetPath: Path,
        position: SourcePosition,
    ): TypeScriptHoverProjection {
        validateOverlayRequest(savedSnapshot, overlay)?.let { return TypeScriptHoverProjection.Refused(it) }
        val source = overlay.providerSnapshot.files.single { it.path.normalize() == targetPath.normalize() }
        if (!validPosition(source.content, position)) return TypeScriptHoverProjection.Refused(diagnostic(
            "typescript.hoverPositionInvalid", "Hover position is outside the overlay document",
        ))
        return when (val result = client.buildOverlayHover(savedSnapshot, overlay, targetPath, position)) {
            is TypeScriptHoverProjection.Available -> {
                rememberOverlayVersions(overlay)
                result.copy(provenanceHash = symbolProjectionProvenanceHash())
            }
            is TypeScriptHoverProjection.Refused -> result
        }
    }

    fun overlayDocumentSymbols(
        savedSnapshot: ProjectSnapshot,
        overlay: ImmutableEditorOverlay,
        targetPath: Path,
        limit: Int = org.refactorkit.core.ProtocolLimits.MAX_SYMBOL_RESULTS,
    ): TypeScriptSymbolProjection {
        require(limit in 1..org.refactorkit.core.ProtocolLimits.MAX_SYMBOL_RESULTS)
        validateOverlayRequest(savedSnapshot, overlay)?.let { return TypeScriptSymbolProjection.Refused(it) }
        return when (val result = try {
            client.buildOverlayDocumentSymbols(savedSnapshot, overlay, targetPath, limit)
        } catch (failure: RuntimeException) {
            return TypeScriptSymbolProjection.Refused(diagnostic(
                "typescript.overlaySymbolsUnavailable",
                failure.message ?: "TypeScript overlay document symbols failed",
            ))
        }) {
            is TypeScriptClientSymbolProjection.Available -> {
                rememberOverlayVersions(overlay)
                TypeScriptSymbolProjection.Available(result.index, result.truncated, symbolProjectionProvenanceHash())
            }
            is TypeScriptClientSymbolProjection.Refused -> TypeScriptSymbolProjection.Refused(
                diagnostic(result.code, result.message),
            )
        }
    }

    private fun validateOverlayRequest(savedSnapshot: ProjectSnapshot, overlay: ImmutableEditorOverlay): Diagnostic? {
        if (!active(savedSnapshot) || overlay.baseSnapshotHash != savedSnapshot.hash) return diagnostic(
            "typescript.overlaySessionStale", "Editor overlay requires the active saved snapshot",
        )
        if (toolchain.provenance.evidence.any { !verifyEvidence(it) } || !projectEvidenceUnchanged(savedSnapshot.workspace.root)) {
            return diagnostic("typescript.overlayEvidenceChanged", "TypeScript toolchain or project evidence changed before overlay query")
        }
        overlay.documents.forEach { document ->
            val hash = sha256(document.content.toByteArray(Charsets.UTF_8))
            val previous = overlayVersions[document.path.normalize()]
            if (previous != null && (document.version < previous.first ||
                    document.version == previous.first && hash != previous.second)) {
                return diagnostic("typescript.overlayVersionStale", "Editor overlay document version is stale")
            }
        }
        return null
    }

    private fun rememberOverlayVersions(overlay: ImmutableEditorOverlay) {
        overlay.documents.forEach { document ->
            overlayVersions[document.path.normalize()] = document.version to
                sha256(document.content.toByteArray(Charsets.UTF_8))
        }
    }

    private fun validPosition(content: String, position: SourcePosition): Boolean {
        val lines = content.split('\n')
        return position.line in lines.indices && position.character <= lines[position.line].length
    }

    private fun validCompletionEdits(content: String, items: List<SemanticCompletionItem>): Boolean = items.all { item ->
        val edits = listOfNotNull(item.replacementRange?.let { TextEdit(it, item.insertText ?: item.label) }) + item.additionalTextEdits
        edits.all { validPosition(content, it.range.start) && validPosition(content, it.range.end) } &&
            edits.indices.none { left -> edits.indices.any { right -> left < right && edits[left].range.overlaps(edits[right].range) } }
    }

    fun searchWorkspaceSymbols(project: ProjectSnapshot, query: String): List<org.refactorkit.core.Symbol> =
        if (active(project)) {
            // Real TypeScript servers discover configured projects lazily after a
            // text document is opened. Build the bounded document index first so
            // workspace/symbol observes the exact snapshot rather than an empty
            // unopened project.
            val index = client.buildSymbols(project)
            (index.symbols.filter { it.name.contains(query, ignoreCase = true) } +
                client.searchWorkspaceSymbols(query))
                .distinctBy { symbol ->
                    val root = project.workspace.root.toAbsolutePath().normalize()
                    val normalized = symbol.location.path.normalize()
                    val workspacePath = if (normalized.isAbsolute && normalized.startsWith(root)) {
                        root.relativize(normalized)
                    } else normalized
                    listOf(
                        workspacePath.toString().replace('\\', '/'),
                        symbol.kind.name,
                        symbol.name,
                    ).joinToString("|")
                }
                .take(org.refactorkit.core.ProtocolLimits.MAX_SYMBOL_RESULTS)
        } else emptyList()

    override fun resolveSymbol(location: SourceLocation): SymbolResolution =
        if (activeSnapshot != null && client.isRunning) client.resolveSymbol(location)
        else SymbolResolution(null, listOf(diagnostic("typescript.semanticNotStarted", "TypeScript semantic adapter is not started")))

    override fun findReferences(symbolId: SymbolId): List<Reference> =
        if (activeSnapshot != null && client.isRunning) client.findReferences(symbolId) else emptyList()

    fun semanticCompleteness(): TypeScriptSemanticCompleteness {
        if (languageId == "typescript") return TypeScriptSemanticCompleteness(
            TypeScriptSemanticCompletenessMode.FULL_TYPESCRIPT, true,
            "TypeScript sources use compiler-backed semantic checking.",
        )
        val states = projectModel.projects.map { it.compilerOptions.checkJs == true }.toSet()
        return when (states) {
            setOf(true) -> TypeScriptSemanticCompleteness(
                TypeScriptSemanticCompletenessMode.CHECKED_JAVASCRIPT, true,
                "JavaScript sources are compiler-checked with checkJs=true.",
            )
            setOf(false) -> TypeScriptSemanticCompleteness(
                TypeScriptSemanticCompletenessMode.DYNAMIC_JAVASCRIPT, false,
                "JavaScript compiler checking is disabled; dynamic references may be incomplete.",
            )
            else -> TypeScriptSemanticCompleteness(
                TypeScriptSemanticCompletenessMode.MIXED_JAVASCRIPT, false,
                "JavaScript projects mix checked and dynamic compiler completeness.",
            )
        }
    }

    override fun diagnostics(project: ProjectSnapshot): List<Diagnostic> = when {
        !active(project) -> listOf(diagnostic("typescript.semanticNotStarted", "TypeScript semantic adapter is not started for this snapshot"))
        else -> when (val result = client.synchronizedDiagnostics(project)) {
            is ExternalSemanticDiagnostics.Available -> result.diagnostics
            is ExternalSemanticDiagnostics.Unavailable -> listOf(result.diagnostic)
        }
    }

    /** Exact-version semantic gate required by PatchEngine for managed TypeScript apply. */
    fun diagnosticsGate(): DiagnosticsGate = DiagnosticsGate.enabled("typescript-compiler-exact-v1") { snapshot ->
        check(semanticScopeCompatible(snapshot)) { "TypeScript diagnostics snapshot is outside the active semantic scope" }
        check(semanticCompleteness().managedMutationEligible) {
            "typescript.semanticCompletenessInsufficient: ${semanticCompleteness().summary}"
        }
        check(toolchain.provenance.evidence.all(::verifyEvidence)) {
            "typescript.toolchainEvidenceChanged: TypeScript semantic toolchain changed before managed apply"
        }
        check(projectEvidenceUnchanged(snapshot.workspace.root)) {
            "typescript.modelEvidenceChanged: TypeScript project evidence changed before managed apply"
        }
        when (val result = client.synchronizedDiagnostics(snapshot)) {
            is ExternalSemanticDiagnostics.Available -> result.diagnostics
            is ExternalSemanticDiagnostics.Unavailable -> error("${result.diagnostic.code}: ${result.diagnostic.message}")
        }
    }

    override fun availableRefactorings(selection: CodeSelection): List<RefactoringDescriptor> = listOf(
        RefactoringDescriptor("renameSymbol", "Rename TypeScript/JavaScript symbol", RiskLevel.MEDIUM),
    )

    override fun applyRefactoring(request: RefactoringRequest): PatchPlan {
        if (!active(request.snapshot)) return refusedPlan(
            request, "typescript.semanticNotStarted", "TypeScript semantic adapter is not started for this snapshot",
        )
        if (request.operation != "renameSymbol") return refusedPlan(
            request, "language.operationUnsupported", "Unsupported TypeScript operation '${request.operation}'",
        )
        val location = request.selection?.location ?: return refusedPlan(
            request, "typescript.renameSelectionMissing", "TypeScript rename requires a source selection",
        )
        val newName = request.arguments["newName"] ?: return refusedPlan(
            request, "typescript.renameTargetMissing", "TypeScript rename requires arguments.newName",
        )
        if (!isSafeIdentifier(newName)) return refusedPlan(
            request, "typescript.renameTargetInvalid", "TypeScript rename target is not a safe non-reserved identifier",
        )
        client.buildSymbols(request.snapshot)
        val resolution = client.resolveSymbol(location)
        val resolvedSymbol = resolution.symbol ?: return refusedPlan(
            request, "typescript.renameSymbolUnresolved", "TypeScript rename target could not be resolved exactly",
            resolution.diagnostics.ifEmpty {
                listOf(diagnostic("typescript.renameSymbolUnresolved", "TypeScript rename target could not be resolved exactly"))
            },
        )
        val symbol = if (resolvedSymbol.kind == org.refactorkit.core.Symbol.Kind.UNKNOWN) {
            val classified = structuralSymbolKind(request.snapshot, resolvedSymbol.location)
            if (classified == null) resolvedSymbol else resolvedSymbol.copy(kind = classified)
        } else resolvedSymbol
        if (symbol.kind == org.refactorkit.core.Symbol.Kind.UNKNOWN) return refusedPlan(
            request, "typescript.renameKindUnclassified",
            "TypeScript rename target has semantic identity but no supported structural kind",
        )
        if (symbol.kind in UNSUPPORTED_RENAME_KINDS) return refusedPlan(
            request, "typescript.renameKindUnsupported",
            "TypeScript semantic rename does not support ${symbol.kind.name.lowercase()} symbols",
        )
        if (newName == symbol.name) return refusedPlan(
            request, "typescript.renameNoChange", "TypeScript rename target is unchanged",
        )
        val externalConsumerRisk = exportedDeclaration(request.snapshot, symbol.location)
        val externalConsumerOverride = request.arguments["allowExternalConsumers"]?.toBooleanStrictOrNull() == true
        if (externalConsumerRisk && !externalConsumerOverride) return refusedPlan(
            request,
            "typescript.externalConsumersUnknown",
            "Exported TypeScript/JavaScript symbol may have consumers outside the bounded workspace; explicit allowExternalConsumers=true is required",
        )
        // Real TypeScript servers load configured projects lazily. Bounded
        // document-symbol, workspace-symbol, and reference requests form a
        // semantic project barrier before rename; without the reference request
        // some Windows servers return a declaration-only syntactic edit.
        repeat(SEMANTIC_RENAME_BARRIER_ATTEMPTS) { attempt ->
            client.buildSymbols(request.snapshot)
            client.searchWorkspaceSymbols(symbol.name)
            client.findReferences(symbol.id)
            if (attempt < SEMANTIC_RENAME_BARRIER_ATTEMPTS - 1) try {
                Thread.sleep(SEMANTIC_RENAME_BARRIER_DELAY_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return refusedPlan(
                    request, "typescript.semanticBarrierInterrupted",
                    "TypeScript semantic project barrier was interrupted",
                )
            }
        }
        val dynamicReferences = dynamicStringReferences(request.snapshot, symbol.name)
        val dynamicReferenceOverride = request.arguments["allowDynamicReferences"]?.toBooleanStrictOrNull() == true
        if (dynamicReferences.isNotEmpty() && !dynamicReferenceOverride) return refusedPlan(
            request,
            "typescript.dynamicReferencesUnknown",
            "Found ${dynamicReferences.size} string/decorator/reflection candidate(s) that semantic rename cannot prove; explicit allowDynamicReferences=true is required",
        )
        return when (val result = client.requestRename(request.snapshot, location, newName)) {
            is ExternalWorkspaceEditNormalization.Refused -> {
                val first = result.diagnostics.firstOrNull()
                refusedPlan(
                    request,
                    first?.code ?: "typescript.renameRefused",
                    first?.message ?: "TypeScript language server refused rename",
                    result.diagnostics,
                )
            }
            is ExternalWorkspaceEditNormalization.Accepted -> {
                val edit = result.normalized.workspaceEdit
                val ownershipFailure = validateProjectOwnership(request.snapshot, edit).firstOrNull()
                if (ownershipFailure != null) return refusedPlan(
                    request, ownershipFailure.code ?: "typescript.projectOwnershipUnavailable",
                    ownershipFailure.message, listOf(ownershipFailure),
                )
                val before = when (val diagnostics = client.synchronizedDiagnostics(request.snapshot)) {
                    is ExternalSemanticDiagnostics.Available -> diagnostics.diagnostics
                    is ExternalSemanticDiagnostics.Unavailable -> return refusedPlan(
                        request, diagnostics.diagnostic.code ?: "typescript.diagnosticsUnavailable",
                        diagnostics.diagnostic.message, listOf(diagnostics.diagnostic),
                    )
                }
                val staged = runCatching { WorkspaceEditSimulator.apply(request.snapshot, edit) }.getOrElse { failure ->
                    return refusedPlan(
                        request, "typescript.previewSimulationFailed",
                        failure.message ?: "TypeScript rename preview could not be simulated",
                    )
                }
                val after = when (val diagnostics = client.synchronizedDiagnostics(staged)) {
                    is ExternalSemanticDiagnostics.Available -> diagnostics.diagnostics
                    is ExternalSemanticDiagnostics.Unavailable -> return refusedPlan(
                        request, diagnostics.diagnostic.code ?: "typescript.diagnosticsUnavailable",
                        diagnostics.diagnostic.message, listOf(diagnostics.diagnostic),
                    )
                }
                val regressions = diagnosticsRegression(before, after)
                if (regressions.isNotEmpty()) return refusedPlan(
                    request,
                    "typescript.diagnosticsRegression",
                    "TypeScript rename introduces ${regressions.size} new compiler error(s): " +
                        regressions.take(3).joinToString("; ") { it.message },
                    regressions,
                )
                rememberApprovedStagedSnapshot(staged.hash)
                val completeness = semanticCompleteness()
                PatchPlan(
                    operation = request.operation,
                    status = PatchStatus.PREVIEW,
                    snapshotHash = request.snapshot.hash,
                    confidence = when {
                        dynamicReferences.isNotEmpty() -> 0.55
                        externalConsumerRisk -> 0.65
                        else -> when (completeness.mode) {
                            TypeScriptSemanticCompletenessMode.FULL_TYPESCRIPT -> 0.9
                            TypeScriptSemanticCompletenessMode.CHECKED_JAVASCRIPT -> 0.75
                            TypeScriptSemanticCompletenessMode.MIXED_JAVASCRIPT -> 0.6
                            TypeScriptSemanticCompletenessMode.DYNAMIC_JAVASCRIPT -> 0.5
                        }
                    },
                    requiresUserApproval = true,
                    summary = "Rename ${symbol.kind.name.lowercase()} '${symbol.name}' to '$newName' in ${edit.edits.size} file operation(s).",
                    affectedFiles = affectedPaths(edit),
                    workspaceEdit = edit,
                    diagnosticsBefore = before,
                    diagnosticsAfterPreview = after,
                    warnings = listOf(
                        "Experimental language-server proposal; apply requires the exact TypeScript diagnostics gate.",
                        completeness.summary,
                        if (externalConsumerRisk) {
                            "Explicit external-consumer override accepted; callers outside the workspace are not proven updated."
                        } else "No exported library boundary was detected at the selected symbol.",
                        if (dynamicReferences.isNotEmpty()) {
                            "Explicit dynamic-reference override accepted for ${dynamicReferences.size} candidate(s): " +
                                dynamicReferences.take(5).joinToString()
                        } else "No exact quoted symbol-name candidate was detected.",
                        "Provider=${toolchain.provenance.providerId} TypeScript=${toolchain.provenance.typeScriptVersion}",
                    ),
                    riskLevel = if (
                        languageId == "typescript" && !externalConsumerRisk && dynamicReferences.isEmpty()
                    ) RiskLevel.MEDIUM else RiskLevel.HIGH,
                    evidence = RefactoringEvidence.LANGUAGE_SERVER,
                )
            }
        }
    }

    override fun formatEdits(edits: List<TextEdit>): List<TextEdit> = edits

    override fun close() {
        activeSnapshot = null
        acceptedServerProvenance = null
        restartAttempts.clear()
        lastRestartMillis = null
        approvedStagedSnapshotHashes.clear()
        overlayVersions.clear()
        client.close()
    }

    private fun active(snapshot: ProjectSnapshot): Boolean =
        activeSnapshot?.hash == snapshot.hash && client.isRunning

    private fun semanticScopeCompatible(snapshot: ProjectSnapshot): Boolean {
        val active = activeSnapshot ?: return false
        return diagnosticScopeCompatible(snapshot) && snapshot.hash in approvedSemanticSnapshotHashes(active.hash)
    }

    private fun diagnosticScopeCompatible(snapshot: ProjectSnapshot): Boolean {
        val active = activeSnapshot ?: return false
        return client.isRunning &&
            active.workspace.root.toAbsolutePath().normalize() == snapshot.workspace.root.toAbsolutePath().normalize() &&
            active.modules == snapshot.modules && active.buildModels == snapshot.buildModels &&
            active.classpathEvidence == snapshot.classpathEvidence
    }

    private fun unavailableDiagnostics(code: String, message: String) = ExternalSemanticDiagnostics.Unavailable(
        diagnostic(code, message),
    )

    private fun rememberApprovedStagedSnapshot(hash: String) {
        approvedStagedSnapshotHashes += hash
        while (approvedStagedSnapshotHashes.size > MAX_APPROVED_STAGED_SNAPSHOTS) {
            approvedStagedSnapshotHashes.remove(approvedStagedSnapshotHashes.first())
        }
    }

    private fun approvedSemanticSnapshotHashes(activeHash: String): Set<String> =
        approvedStagedSnapshotHashes + activeHash

    private fun validateProjectOwnership(snapshot: ProjectSnapshot, edit: WorkspaceEdit): List<Diagnostic> {
        val roots = snapshot.buildSourceRootOwnerships().filter {
            it.providerId == TypeScriptProjectModel.PROVIDER_ID && it.modelStatus == BuildModelStatus.AVAILABLE
        }
        return affectedPaths(edit).sortedBy(Path::toString).mapNotNull { path ->
            val normalized = path.normalize()
            val candidates = roots.filter { normalized.startsWith(it.root) }
            val longest = candidates.maxOfOrNull { it.root.nameCount }
            val owners = if (longest == null) emptyList() else candidates.filter { it.root.nameCount == longest }
            when {
                owners.isEmpty() -> diagnostic(
                    "typescript.projectOwnershipMissing",
                    "TypeScript edit path '$normalized' has no authoritative project ownership",
                )
                owners.size > 1 -> diagnostic(
                    "typescript.projectOwnershipAmbiguous",
                    "TypeScript edit path '$normalized' has ${owners.size} equally specific project owners",
                )
                owners.single().generated -> diagnostic(
                    "typescript.generatedSourceReadOnly",
                    "TypeScript edit path '$normalized' is generated and read-only",
                )
                else -> null
            }
        }
    }

    private fun diagnosticsRegression(before: List<Diagnostic>, after: List<Diagnostic>): List<Diagnostic> {
        val existing = before.filter { it.severity == Diagnostic.Severity.ERROR }.map(::diagnosticKey).toSet()
        return after.filter { it.severity == Diagnostic.Severity.ERROR && diagnosticKey(it) !in existing }
    }

    private fun diagnosticKey(value: Diagnostic): String = listOf(
        value.code.orEmpty(), value.message, value.location?.path?.normalize()?.toString().orEmpty(),
        value.location?.range?.start?.line?.toString().orEmpty(),
        value.location?.range?.start?.character?.toString().orEmpty(),
    ).joinToString("|")

    private fun affectedPaths(edit: WorkspaceEdit): Set<Path> = edit.edits.flatMap { operation ->
        when (operation) {
            is FileEdit.Rename -> listOf(operation.path, operation.newPath)
            else -> listOf(operation.path)
        }
    }.toSet()

    private fun refusedStart(code: String, message: String) = TypeScriptSemanticStart.Refused(listOf(diagnostic(code, message)))

    private fun refusedPlan(
        request: RefactoringRequest,
        code: String,
        message: String,
        diagnostics: List<Diagnostic> = listOf(diagnostic(code, message)),
    ) = PatchPlan(
        operation = request.operation,
        status = PatchStatus.REFUSED,
        snapshotHash = request.snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsAfterPreview = diagnostics,
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.LANGUAGE_SERVER,
        refusalCode = code,
    )

    private fun diagnostic(code: String, message: String) = Diagnostic(
        message = message,
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.SAFETY,
    )

    private fun structuralSymbolKind(snapshot: ProjectSnapshot, location: SourceLocation): org.refactorkit.core.Symbol.Kind? {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val relative = if (location.path.isAbsolute) {
            val absolute = location.path.toAbsolutePath().normalize()
            if (!absolute.startsWith(root)) return null else root.relativize(absolute)
        } else location.path.normalize()
        val source = snapshot.files.singleOrNull { it.path.normalize() == relative } ?: return null
        return BonedeTreeSitterBinding().symbolKindAt(
            source.content, location.range.start.line, location.range.start.character, languageId,
        )
    }

    private fun dynamicStringReferences(snapshot: ProjectSnapshot, symbolName: String): List<String> {
        val quotedName = Regex("(['\\\"`])${Regex.escape(symbolName)}\\1")
        return snapshot.files.asSequence()
            .filter { it.languageId in setOf("typescript", "javascript") }
            .flatMap { source ->
                source.content.lineSequence().mapIndexedNotNull { index, line ->
                    if (quotedName.containsMatchIn(line)) "${source.path}:${index + 1}" else null
                }
            }
            .take(MAX_DYNAMIC_REFERENCE_CANDIDATES)
            .toList()
    }

    private fun exportedDeclaration(snapshot: ProjectSnapshot, location: SourceLocation): Boolean {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val relative = if (location.path.isAbsolute) {
            val absolute = location.path.toAbsolutePath().normalize()
            if (!absolute.startsWith(root)) return true else root.relativize(absolute)
        } else location.path.normalize()
        val source = snapshot.files.singleOrNull { it.path.normalize() == relative } ?: return true
        val line = source.content.lineSequence().elementAtOrNull(location.range.start.line) ?: return true
        val declarationPrefix = line.take(location.range.start.character.coerceAtMost(line.length))
        val librarySurface = projectModel.projects.any {
            it.compilerOptions.declaration == true || it.compilerOptions.composite == true ||
                it.packageExportsDeclared || it.packageTypesDeclared
        } || projectModel.projects.flatMap { it.references }.isNotEmpty()
        return librarySurface && EXPORT_MARKER.containsMatchIn(declarationPrefix)
    }

    private fun isSafeIdentifier(value: String): Boolean {
        if (value.isBlank() || value.length > 1_024 || '\u0000' in value || value in RESERVED_IDENTIFIERS) return false
        val identifier = value.removePrefix("#")
        if (identifier.isEmpty() || value.count { it == '#' } > if (value.startsWith('#')) 1 else 0) return false
        var offset = 0
        var first = true
        while (offset < identifier.length) {
            val codePoint = identifier.codePointAt(offset)
            val valid = if (first) {
                codePoint == '_'.code || codePoint == '$'.code || Character.isUnicodeIdentifierStart(codePoint)
            } else {
                codePoint == '_'.code || codePoint == '$'.code || codePoint == 0x200C || codePoint == 0x200D ||
                    Character.isUnicodeIdentifierPart(codePoint)
            }
            if (!valid) return false
            first = false
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private fun symbolProjectionProvenanceHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: Any?) {
            digest.update(value?.toString().orEmpty().toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        add(toolchain.provenance.providerId)
        add(toolchain.provenance.nodeVersion)
        add(toolchain.provenance.languageServerVersion)
        add(toolchain.provenance.typeScriptVersion)
        toolchain.provenance.evidence.sortedBy { it.role }.forEach { evidence ->
            add(evidence.role)
            add(evidence.sha256)
            add(evidence.size)
        }
        add(projectModel.projectionHash)
        val server = acceptedServerProvenance
        add(server?.serverName)
        add(server?.serverVersion)
        add(server?.capabilitiesSha256)
        add(server?.executableSha256)
        add(server?.argumentsSha256)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun provenanceSignature(provenance: ExternalSemanticSessionProvenance) = ServerProvenanceSignature(
        provenance.serverName,
        provenance.serverVersion,
        provenance.capabilitiesSha256,
        provenance.process.executableSha256,
        provenance.process.argumentsSha256,
    )

    private fun projectEvidenceUnchanged(workspaceRoot: Path): Boolean {
        val root = workspaceRoot.toAbsolutePath().normalize()
        return projectModel.evidence.all { item ->
            val absolute = root.resolve(item.path).normalize()
            if (!absolute.startsWith(root) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(absolute) || runCatching { Files.size(absolute) }.getOrNull() != item.size) {
                false
            } else runCatching { sha256(Files.readAllBytes(absolute)) == item.sha256 }.getOrDefault(false)
        }
    }

    private fun verifyEvidence(item: ToolchainFileEvidence): Boolean {
        if (!Files.isRegularFile(item.path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(item.path)) return false
        if (runCatching { Files.size(item.path) }.getOrNull() != item.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        return runCatching {
            Files.newInputStream(item.path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > item.size) return false
                    digest.update(buffer, 0, count)
                }
            }
            total == item.size && digest.digest().joinToString("") { "%02x".format(it) } == item.sha256
        }.getOrDefault(false)
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private data class ServerProvenanceSignature(
        val serverName: String?,
        val serverVersion: String?,
        val capabilitiesSha256: String,
        val executableSha256: String,
        val argumentsSha256: String,
    )

    companion object {
        const val MAX_RESTARTS_PER_WINDOW = 3
        const val MAX_DYNAMIC_REFERENCE_CANDIDATES = 50
        const val SEMANTIC_RENAME_BARRIER_ATTEMPTS = 10
        const val SEMANTIC_RENAME_BARRIER_DELAY_MILLIS = 100L
        const val MAX_APPROVED_STAGED_SNAPSHOTS = 128
        const val RESTART_WINDOW_MILLIS = 60_000L

        private val EXPORT_MARKER = Regex("(?:^|\\s)export(?:\\s|$)")
        private val UNSUPPORTED_RENAME_KINDS = setOf(
            org.refactorkit.core.Symbol.Kind.UNKNOWN,
            org.refactorkit.core.Symbol.Kind.CONSTRUCTOR,
            org.refactorkit.core.Symbol.Kind.PACKAGE,
        )
        private val RESERVED_IDENTIFIERS = setOf(
            "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default",
            "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for", "function",
            "if", "implements", "import", "in", "instanceof", "interface", "let", "new", "null", "package",
            "private", "protected", "public", "return", "static", "super", "switch", "this", "throw", "true",
            "try", "typeof", "var", "void", "while", "with", "yield",
        )
        private val REQUIRED_CAPABILITIES = listOf(
            "definitionProvider", "referencesProvider", "renameProvider", "prepareRenameProvider",
            "documentSymbolProvider", "workspaceSymbolProvider", "textDocumentSync",
        )

        fun descriptor(languageId: String) = LanguageAdapterDescriptor(
            languageId = languageId,
            extensions = if (languageId == "typescript") setOf("ts", "tsx") else setOf("js", "jsx"),
            backend = TypeScriptToolchainProvenance.PROVIDER_ID,
            capabilities = listOf(
                LanguageCapability("completion", CapabilityStability.EXPERIMENTAL, SemanticEvidenceKind.LANGUAGE_SERVER),
                LanguageCapability("definition", CapabilityStability.EXPERIMENTAL, SemanticEvidenceKind.LANGUAGE_SERVER),
                LanguageCapability("workspaceSymbols", CapabilityStability.EXPERIMENTAL, SemanticEvidenceKind.LANGUAGE_SERVER),
                LanguageCapability("hover", CapabilityStability.EXPERIMENTAL, SemanticEvidenceKind.LANGUAGE_SERVER),
                LanguageCapability("references", CapabilityStability.EXPERIMENTAL, SemanticEvidenceKind.LANGUAGE_SERVER),
                LanguageCapability(
                    operation = "diagnostics",
                    stability = CapabilityStability.EXPERIMENTAL,
                    evidence = SemanticEvidenceKind.COMPILER,
                    mutationAuthority = MutationAuthority.NONE,
                    backend = TypeScriptDiagnosticsContract.BACKEND,
                    runtime = TypeScriptDiagnosticsContract.runtime,
                    diagnosticSnapshotModes = TypeScriptDiagnosticsContract.snapshotModes,
                    diagnosticRangeCapability = TypeScriptDiagnosticsContract.rangeCapability,
                ),
                LanguageCapability(
                    "renameSymbol", CapabilityStability.EXPERIMENTAL,
                    SemanticEvidenceKind.LANGUAGE_SERVER, MutationAuthority.PROPOSAL_ONLY,
                ),
            ),
            runtime = ExternalLspAdapter.descriptor(
                languageId,
                if (languageId == "typescript") setOf("ts", "tsx") else setOf("js", "jsx"),
            ).runtime,
        )
    }
}
