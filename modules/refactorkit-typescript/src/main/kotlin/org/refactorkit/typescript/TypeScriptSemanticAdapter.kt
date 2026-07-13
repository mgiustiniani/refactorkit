package org.refactorkit.typescript

import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.CapabilityStability
import org.refactorkit.core.CodeSelection
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.FileEdit
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
import org.refactorkit.core.SemanticEvidenceKind
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SymbolId
import org.refactorkit.core.SymbolIndex
import org.refactorkit.core.SymbolResolution
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.treesitter.ExternalLspAdapter
import org.refactorkit.treesitter.ExternalSemanticDiagnostics
import org.refactorkit.treesitter.ExternalSemanticSessionProvenance
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

interface TypeScriptSemanticClient : AutoCloseable {
    val isRunning: Boolean
    val provenance: ExternalSemanticSessionProvenance?
    fun start(snapshot: ProjectSnapshot)
    fun supports(capability: String): Boolean
    fun buildSymbols(snapshot: ProjectSnapshot): SymbolIndex
    fun resolveSymbol(location: SourceLocation): SymbolResolution
    fun findReferences(symbolId: SymbolId): List<Reference>
    fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic>
    fun synchronizedDiagnostics(snapshot: ProjectSnapshot): ExternalSemanticDiagnostics
    fun requestRename(snapshot: ProjectSnapshot, location: SourceLocation, newName: String): ExternalWorkspaceEditNormalization
}

class ExternalTypeScriptSemanticClient(
    languageId: String,
    toolchain: TypeScriptSemanticToolchain,
) : TypeScriptSemanticClient {
    private val adapter = ExternalLspAdapter(languageId, toolchain.command)
    override val isRunning: Boolean get() = adapter.isRunning
    override val provenance: ExternalSemanticSessionProvenance? get() = adapter.sessionProvenance
    override fun start(snapshot: ProjectSnapshot) = adapter.start(snapshot)
    override fun supports(capability: String): Boolean = adapter.supportsServerCapability(capability)
    override fun buildSymbols(snapshot: ProjectSnapshot): SymbolIndex = adapter.buildSymbols(snapshot)
    override fun resolveSymbol(location: SourceLocation): SymbolResolution = adapter.resolveSymbol(location)
    override fun findReferences(symbolId: SymbolId): List<Reference> = adapter.findReferences(symbolId)
    override fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = adapter.diagnostics(snapshot)
    override fun synchronizedDiagnostics(snapshot: ProjectSnapshot): ExternalSemanticDiagnostics =
        adapter.synchronizedDiagnostics(snapshot)
    override fun requestRename(
        snapshot: ProjectSnapshot,
        location: SourceLocation,
        newName: String,
    ): ExternalWorkspaceEditNormalization = adapter.requestRename(snapshot, location, newName)
    override fun close() = adapter.close()
}

/** Experimental compiler/LSP-backed adapter. It returns proposals, never direct writes. */
class TypeScriptSemanticAdapter(
    private val languageId: String,
    private val toolchain: TypeScriptSemanticToolchain,
    private val projectModel: TypeScriptProjectModel,
    private val client: TypeScriptSemanticClient = ExternalTypeScriptSemanticClient(languageId, toolchain),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : LanguageAdapter, AutoCloseable {
    private var activeSnapshot: ProjectSnapshot? = null
    private var acceptedServerProvenance: ServerProvenanceSignature? = null
    private val restartAttempts = ArrayDeque<Long>()
    private var lastRestartMillis: Long? = null

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
        val result = start(snapshot)
        if (result is TypeScriptSemanticStart.Refused) activeSnapshot = previous
        return result
    }

    override fun languageId(): String = languageId
    override fun parse(file: SourceFile): ParseResult = ParseResult(file)

    override fun buildSymbols(project: ProjectSnapshot): SymbolIndex =
        if (active(project)) client.buildSymbols(project) else SymbolIndex(emptyList())

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
    fun diagnosticsGate(): DiagnosticsGate = DiagnosticsGate.enabled("typescript-lsp-exact") { snapshot ->
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
                val completeness = semanticCompleteness()
                PatchPlan(
                    operation = request.operation,
                    status = PatchStatus.PREVIEW,
                    snapshotHash = request.snapshot.hash,
                    confidence = when (completeness.mode) {
                        TypeScriptSemanticCompletenessMode.FULL_TYPESCRIPT -> 0.9
                        TypeScriptSemanticCompletenessMode.CHECKED_JAVASCRIPT -> 0.75
                        TypeScriptSemanticCompletenessMode.MIXED_JAVASCRIPT -> 0.6
                        TypeScriptSemanticCompletenessMode.DYNAMIC_JAVASCRIPT -> 0.5
                    },
                    requiresUserApproval = true,
                    summary = "Rename ${languageId} symbol to $newName in ${edit.edits.size} file operation(s).",
                    affectedFiles = affectedPaths(edit),
                    workspaceEdit = edit,
                    diagnosticsBefore = before,
                    diagnosticsAfterPreview = after,
                    warnings = listOf(
                        "Experimental language-server proposal; apply requires the exact TypeScript diagnostics gate.",
                        completeness.summary,
                        "Provider=${toolchain.provenance.providerId} TypeScript=${toolchain.provenance.typeScriptVersion}",
                    ),
                    riskLevel = if (languageId == "typescript") RiskLevel.MEDIUM else RiskLevel.HIGH,
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
        client.close()
    }

    private fun active(snapshot: ProjectSnapshot): Boolean =
        activeSnapshot?.hash == snapshot.hash && client.isRunning

    private fun semanticScopeCompatible(snapshot: ProjectSnapshot): Boolean {
        val active = activeSnapshot ?: return false
        return client.isRunning &&
            active.workspace.root.toAbsolutePath().normalize() == snapshot.workspace.root.toAbsolutePath().normalize() &&
            active.modules == snapshot.modules && active.buildModels == snapshot.buildModels &&
            active.classpathEvidence == snapshot.classpathEvidence &&
            active.files.map { it.path.normalize() to it.languageId }.toSet() ==
                snapshot.files.map { it.path.normalize() to it.languageId }.toSet()
    }

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
        const val RESTART_WINDOW_MILLIS = 60_000L

        private val REQUIRED_CAPABILITIES = listOf(
            "definitionProvider", "referencesProvider", "renameProvider",
            "documentSymbolProvider", "textDocumentSync",
        )

        fun descriptor(languageId: String) = LanguageAdapterDescriptor(
            languageId = languageId,
            extensions = if (languageId == "typescript") setOf("ts") else setOf("js"),
            backend = TypeScriptToolchainProvenance.PROVIDER_ID,
            capabilities = listOf(
                LanguageCapability("definition", CapabilityStability.EXPERIMENTAL, SemanticEvidenceKind.LANGUAGE_SERVER),
                LanguageCapability("references", CapabilityStability.EXPERIMENTAL, SemanticEvidenceKind.LANGUAGE_SERVER),
                LanguageCapability("diagnostics", CapabilityStability.EXPERIMENTAL, SemanticEvidenceKind.LANGUAGE_SERVER),
                LanguageCapability(
                    "renameSymbol", CapabilityStability.EXPERIMENTAL,
                    SemanticEvidenceKind.LANGUAGE_SERVER, MutationAuthority.PROPOSAL_ONLY,
                ),
            ),
            runtime = ExternalLspAdapter.descriptor(languageId, if (languageId == "typescript") setOf("ts") else setOf("js")).runtime,
        )
    }
}
