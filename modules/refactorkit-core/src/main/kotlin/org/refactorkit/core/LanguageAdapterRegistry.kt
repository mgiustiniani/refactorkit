package org.refactorkit.core

import java.nio.file.Path

/** Language-neutral evidence vocabulary for adapter capability negotiation. */
enum class SemanticEvidenceKind {
    COMPILER,
    LANGUAGE_SERVER,
    NATIVE_AST,
    STRUCTURAL_PARSE,
    LEXICAL,
    NONE,
}

enum class CapabilityStability {
    STABLE,
    EXPERIMENTAL,
    STRUCTURAL,
    REFUSED,
    NOT_APPLICABLE,
}

enum class MutationAuthority {
    MANAGED_STABLE,
    PROPOSAL_ONLY,
    NONE,
}

enum class AdapterExecutionMode {
    IN_PROCESS,
    EXTERNAL_PROCESS,
}

data class LanguageAdapterResourceLimits(
    val requestTimeoutMillis: Long? = null,
    val maxInputBytes: Long? = null,
    val maxOutputBytes: Long? = null,
    val maxProcesses: Int? = null,
) {
    init {
        require(requestTimeoutMillis == null || requestTimeoutMillis in 1..300_000)
        require(maxInputBytes == null || maxInputBytes in 1..4L * 1024L * 1024L * 1024L)
        require(maxOutputBytes == null || maxOutputBytes in 1..4L * 1024L * 1024L * 1024L)
        require(maxProcesses == null || maxProcesses in 1..64)
    }
}

data class LanguageAdapterRuntime(
    val executionMode: AdapterExecutionMode = AdapterExecutionMode.IN_PROCESS,
    val supportsTimeout: Boolean = false,
    val supportsCancellation: Boolean = false,
    val usesWorkspaceOverlay: Boolean = false,
    val recordsProcessProvenance: Boolean = false,
    val limits: LanguageAdapterResourceLimits = LanguageAdapterResourceLimits(),
) {
    init {
        require(executionMode == AdapterExecutionMode.EXTERNAL_PROCESS || !recordsProcessProvenance) {
            "in-process adapters cannot claim process provenance"
        }
        require(!usesWorkspaceOverlay || executionMode == AdapterExecutionMode.EXTERNAL_PROCESS) {
            "workspace overlays are an external-process boundary"
        }
        require(!supportsTimeout || limits.requestTimeoutMillis != null) {
            "timeout support requires a declared request timeout"
        }
    }
}

data class LanguageCapability(
    val operation: String,
    val stability: CapabilityStability,
    val evidence: SemanticEvidenceKind,
    val mutationAuthority: MutationAuthority = MutationAuthority.NONE,
) {
    init {
        require(operation.isNotBlank()) { "language capability operation must not be blank" }
        require(mutationAuthority != MutationAuthority.MANAGED_STABLE || stability == CapabilityStability.STABLE) {
            "managed stable authority requires STABLE capability"
        }
        require(mutationAuthority != MutationAuthority.MANAGED_STABLE || evidence !in setOf(
            SemanticEvidenceKind.LEXICAL,
            SemanticEvidenceKind.NONE,
        )) { "lexical/no evidence cannot receive managed stable authority" }
    }
}

data class LanguageAdapterDescriptor(
    val languageId: String,
    val extensions: Set<String>,
    val backend: String,
    val capabilities: List<LanguageCapability>,
    val runtime: LanguageAdapterRuntime = LanguageAdapterRuntime(),
) {
    init {
        require(LANGUAGE_ID.matches(languageId)) { "language ID must be canonical lowercase kebab-case" }
        require(backend.isNotBlank()) { "language adapter backend must not be blank" }
        require(extensions.isNotEmpty()) { "language adapter must declare at least one extension" }
        require(extensions.all { EXTENSION.matches(it) }) { "extensions must be lowercase and omit the dot" }
        require(capabilities.map(LanguageCapability::operation).distinct().size == capabilities.size) {
            "language capability operations must be unique"
        }
    }

    companion object {
        private val LANGUAGE_ID = Regex("[a-z][a-z0-9-]{0,63}")
        private val EXTENSION = Regex("[a-z0-9][a-z0-9+_-]{0,15}")
    }
}

data class RegisteredLanguageAdapter(
    val descriptor: LanguageAdapterDescriptor,
    val adapter: LanguageAdapter,
) {
    init {
        require(adapter.languageId() == descriptor.languageId) {
            "adapter language ID does not match its descriptor"
        }
    }
}

sealed interface LanguageRoute {
    data class Resolved(val registration: RegisteredLanguageAdapter) : LanguageRoute
    data class Refused(val diagnostic: Diagnostic) : LanguageRoute
}

/**
 * Deterministic mixed-language router. Language IDs are authoritative for scanned
 * files; extensions are used only when routing a path without a SourceFile.
 */
class LanguageAdapterRegistry(registrations: Collection<RegisteredLanguageAdapter>) {
    private val byLanguage: Map<String, RegisteredLanguageAdapter>
    private val byExtension: Map<String, RegisteredLanguageAdapter>

    init {
        require(registrations.size <= MAX_ADAPTERS) { "language adapter registry exceeds bounded size" }
        val languages = registrations.groupBy { it.descriptor.languageId }
        require(languages.none { it.value.size > 1 }) { "language adapter IDs must be unique" }
        val extensions = registrations.flatMap { registration ->
            registration.descriptor.extensions.map { it to registration }
        }.groupBy(Pair<String, RegisteredLanguageAdapter>::first)
        require(extensions.none { it.value.size > 1 }) { "language extension ownership must be unambiguous" }
        byLanguage = registrations.associateBy { it.descriptor.languageId }.toSortedMap()
        byExtension = extensions.mapValues { it.value.single().second }.toSortedMap()
    }

    fun descriptors(): List<LanguageAdapterDescriptor> = byLanguage.values.map(RegisteredLanguageAdapter::descriptor)

    fun route(file: SourceFile): LanguageRoute = byLanguage[file.languageId]
        ?.let(LanguageRoute::Resolved)
        ?: refused("language.unsupported", "No adapter is registered for language '${file.languageId}'")

    fun route(path: Path): LanguageRoute {
        val extension = path.fileName?.toString()?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase().orEmpty()
        return byExtension[extension]?.let(LanguageRoute::Resolved)
            ?: refused("language.unsupported", "No adapter is registered for extension '${extension.ifBlank { "<none>" }}'")
    }

    fun route(request: RefactoringRequest): LanguageRoute {
        request.arguments["languageId"]?.let { requested ->
            return byLanguage[requested]?.let(LanguageRoute::Resolved)
                ?: refused("language.unsupported", "No adapter is registered for language '$requested'")
        }
        request.selection?.location?.path?.let { selectedPath ->
            request.snapshot.files.singleOrNull { it.path == selectedPath }?.let { return route(it) }
            return route(selectedPath)
        }
        request.symbolId?.let { symbolId ->
            val owners = byLanguage.values.filter { registration ->
                registration.adapter.buildSymbols(request.snapshot).symbols.any { it.id == symbolId }
            }
            return when (owners.size) {
                1 -> LanguageRoute.Resolved(owners.single())
                0 -> refused("language.symbolUnresolved", "No registered adapter owns symbol '${symbolId.value}'")
                else -> refused("language.symbolAmbiguous", "Multiple language adapters own symbol '${symbolId.value}'")
            }
        }
        return refused("language.routeMissing", "Refactoring request has no language, selection, or symbol routing evidence")
    }

    fun buildSymbols(snapshot: ProjectSnapshot): SymbolIndex = SymbolIndex(
        byLanguage.values.flatMap { it.adapter.buildSymbols(snapshot).symbols }
            .distinctBy { Triple(it.languageId, it.id, it.location) }
            .sortedWith(compareBy({ it.languageId }, { it.id.value }, { it.location.path.toString() })),
    )

    fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = byLanguage.values
        .flatMap { it.adapter.diagnostics(snapshot) }
        .sortedWith(compareBy({ it.location?.path?.toString().orEmpty() }, { it.code.orEmpty() }, { it.message }))

    fun applyRefactoring(request: RefactoringRequest): RefactoringPlan = when (val route = route(request)) {
        is LanguageRoute.Resolved -> {
            val capability = route.registration.descriptor.capabilities.singleOrNull { it.operation == request.operation }
            if (capability == null || capability.stability in setOf(
                    CapabilityStability.REFUSED,
                    CapabilityStability.NOT_APPLICABLE,
                )) refusedPlan(request, "language.operationUnsupported", "Operation '${request.operation}' is not available for ${route.registration.descriptor.languageId}")
            else {
                val plan = route.registration.adapter.applyRefactoring(request)
                if (capability.mutationAuthority == MutationAuthority.MANAGED_STABLE &&
                    !evidenceSatisfies(capability.evidence, plan.evidence)) {
                    refusedPlan(
                        request,
                        "language.evidenceInsufficient",
                        "Adapter evidence '${plan.evidence}' does not satisfy stable '${capability.evidence}' authority",
                    )
                } else plan
            }
        }
        is LanguageRoute.Refused -> refusedPlan(
            request,
            route.diagnostic.code ?: "language.routeRefused",
            route.diagnostic.message,
        )
    }

    private fun evidenceSatisfies(required: SemanticEvidenceKind, actual: RefactoringEvidence): Boolean = when (required) {
        SemanticEvidenceKind.COMPILER -> actual == RefactoringEvidence.JDT_BINDING
        SemanticEvidenceKind.LANGUAGE_SERVER -> actual == RefactoringEvidence.LANGUAGE_SERVER
        SemanticEvidenceKind.NATIVE_AST -> actual in setOf(
            RefactoringEvidence.JDT_BINDING,
            RefactoringEvidence.NATIVE_AST,
        )
        SemanticEvidenceKind.STRUCTURAL_PARSE -> actual != RefactoringEvidence.LEXICAL_FALLBACK
        SemanticEvidenceKind.LEXICAL -> actual == RefactoringEvidence.LEXICAL_FALLBACK
        SemanticEvidenceKind.NONE -> false
    }

    private fun refused(code: String, message: String) = LanguageRoute.Refused(Diagnostic(
        message = message,
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.PROJECT_STRUCTURE,
    ))

    private fun refusedPlan(request: RefactoringRequest, code: String, message: String) = PatchPlan(
        operation = request.operation,
        status = PatchStatus.REFUSED,
        snapshotHash = request.snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.STRUCTURAL,
        refusalCode = code,
    )

    companion object {
        const val MAX_ADAPTERS = 64
    }
}
