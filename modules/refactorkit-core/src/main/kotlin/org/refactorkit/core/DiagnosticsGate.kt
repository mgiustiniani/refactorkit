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
    }
}

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
