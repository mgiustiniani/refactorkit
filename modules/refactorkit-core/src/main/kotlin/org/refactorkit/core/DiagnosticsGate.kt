package org.refactorkit.core

/** Compiler/language diagnostics supplied to the central managed-apply gate. */
data class DiagnosticsGate(
    val id: String,
    val provider: ((ProjectSnapshot) -> List<Diagnostic>)?,
) {
    init {
        require(id.isNotBlank()) { "diagnostics gate id must not be blank" }
    }

    companion object {
        fun enabled(id: String, provider: (ProjectSnapshot) -> List<Diagnostic>) = DiagnosticsGate(id, provider)
        fun disabled(id: String) = DiagnosticsGate(id, null)
        fun authoritative(id: String, provider: AuthoritativeDiagnosticsProvider) =
            DiagnosticsGate(id, AuthoritativeDiagnosticsAdapter(provider))

        /**
         * Retains PatchEngine's authoritative path while deferring operation-gate
         * construction until the first authoritative candidate evaluation.
         */
        fun lazyAuthoritative(
            id: String,
            gateFactory: () -> DiagnosticsGate,
        ) = DiagnosticsGate(
            id,
            AuthoritativeDiagnosticsAdapter(
                LazyAuthoritativeDiagnosticsProvider(id, gateFactory),
            ),
        )
    }
}

private class AuthoritativeDiagnosticsAdapter(
    val delegate: AuthoritativeDiagnosticsProvider,
) : (ProjectSnapshot) -> List<Diagnostic> {
    override fun invoke(candidate: ProjectSnapshot): List<Diagnostic> =
        delegate.evaluate(candidate).diagnostics
}

private class LazyAuthoritativeDiagnosticsProvider(
    private val expectedGateId: String,
    gateFactory: () -> DiagnosticsGate,
) : AuthoritativeDiagnosticsProvider {
    private val delegate: AuthoritativeDiagnosticsProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val gate = gateFactory()
        require(gate.id == expectedGateId) {
            "Lazy authoritative gate factory returned '${gate.id}' instead of '$expectedGateId'"
        }
        requireNotNull(gate.authoritativeProvider) {
            "Lazy authoritative gate factory returned a non-authoritative gate '$expectedGateId'"
        }
    }

    override fun evaluate(candidate: ProjectSnapshot): AuthoritativeDiagnosticsEvaluation =
        delegate.evaluate(candidate)
}

internal val DiagnosticsGate.authoritativeProvider: AuthoritativeDiagnosticsProvider?
    get() = (provider as? AuthoritativeDiagnosticsAdapter)?.delegate

internal fun diagnosticsRegression(
    before: List<Diagnostic>,
    after: List<Diagnostic>,
): List<Diagnostic> {
    val remaining = before.filter { it.severity == Diagnostic.Severity.ERROR }
        .groupingBy(::diagnosticIdentity)
        .eachCount()
        .toMutableMap()
    return after.filter { diagnostic ->
        if (diagnostic.severity != Diagnostic.Severity.ERROR) return@filter false
        val identity = diagnosticIdentity(diagnostic)
        val count = remaining[identity] ?: 0
        if (count > 0) {
            remaining[identity] = count - 1
            false
        } else true
    }
}

internal fun diagnosticMultisetDifferenceCount(
    expected: List<Diagnostic>,
    observed: List<Diagnostic>,
): Int {
    val expectedCounts = expected.groupingBy(::diagnosticIdentity).eachCount()
    val observedCounts = observed.groupingBy(::diagnosticIdentity).eachCount()
    return (expectedCounts.keys + observedCounts.keys).sumOf { identity ->
        val difference = (expectedCounts[identity] ?: 0) - (observedCounts[identity] ?: 0)
        if (difference < 0) -difference else difference
    }
}

private fun diagnosticIdentity(diagnostic: Diagnostic): String {
    val location = diagnostic.location
    return listOf(
        diagnostic.severity.name,
        diagnostic.code.orEmpty(),
        diagnostic.evidence?.name.orEmpty(),
        diagnostic.category?.name.orEmpty(),
        diagnostic.locationPrecision.name,
        location?.path?.normalize()?.toString()?.replace('\\', '/').orEmpty(),
        location?.range?.start?.line?.toString().orEmpty(),
        location?.range?.start?.character?.toString().orEmpty(),
        location?.range?.end?.line?.toString().orEmpty(),
        location?.range?.end?.character?.toString().orEmpty(),
        diagnostic.message,
    ).joinToString("\u0000")
}
