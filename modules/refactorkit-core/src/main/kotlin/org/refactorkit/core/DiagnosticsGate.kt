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

private fun diagnosticIdentity(diagnostic: Diagnostic): String =
    "${diagnostic.code.orEmpty()}\u0000${diagnostic.message}"
