package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ProjectSnapshot

internal sealed interface CPrototypeTagAcquisitionResult {
    data object NotRequired : CPrototypeTagAcquisitionResult
    data class Available(val facts: CPrototypeTagNavigationFacts) : CPrototypeTagAcquisitionResult
    data class Unavailable(val diagnostic: Diagnostic) : CPrototypeTagAcquisitionResult
}

/** Application acquisition only: compiler-owned candidates -> bounded native requests -> pure proof. */
internal object CPrototypeTagFactsAcquisition {
    fun capture(snapshot: ProjectSnapshot, captured: CBoundedTranslationUnitAsts,
        toolchain: ClangSemanticToolchain, processes: ExternalSemanticProcessManager,
        diagnosticPrefix: String): CPrototypeTagAcquisitionResult {
        fun unavailable(detail: String) = CPrototypeTagAcquisitionResult.Unavailable(Diagnostic(
            "Native prototype tag binding evidence is unavailable: $detail", Diagnostic.Severity.ERROR,
            code = "$diagnosticPrefix.prototypeBindingUnavailable", evidence = DiagnosticEvidence.COMPILER,
            category = DiagnosticCategory.TYPE_RESOLUTION))
        val queries = when (val result = CPrototypeTagFactsDecoder.queries(captured.asts, captured.sources, captured.logicalPaths)) {
            is CPrototypeTagQueriesReadout.Found -> result.queries
            is CPrototypeTagQueriesReadout.Refused -> return unavailable(result.reason.name)
        }
        if (queries.isEmpty()) return CPrototypeTagAcquisitionResult.NotRequired
        if (toolchain.provenance.clangdVersion != "22.1.8") return unavailable("CLANGD_VERSION_UNQUALIFIED")
        return try {
            val root = snapshot.workspace.root.toAbsolutePath().normalize()
            val inputs = captured.logicalPaths.entries.associate { it.value to captured.sources.getValue(it.key) }
            val responses = ClangdSemanticClient(toolchain, processes).use { client ->
                client.start(root)
                val producer = client.processProvenance() ?: return unavailable("MISSING_PROCESS_PROVENANCE")
                val retained = toolchain.provenance.evidence.singleOrNull { it.role == "clangd-executable" }
                    ?: return unavailable("MISSING_CLANGD_IDENTITY")
                if (producer.executableSha256 != retained.sha256) return unavailable("CLANGD_IDENTITY_MISMATCH")
                for (file in queries.map { it.cursor.file }.distinct()) {
                    if (!client.didOpen(root.resolve(file), inputs.getValue(file))) return unavailable("SOURCE_OPEN_UNAVAILABLE")
                }
                queries.associate { query ->
                    val point = query.cursor.range.start
                    val response = when (val result = client.symbolInfo(root.resolve(query.cursor.file), point.line, point.character)) {
                        is CClangdSymbolInfoResponse.Available -> result.body
                        is CClangdSymbolInfoResponse.Unavailable -> return unavailable(result.diagnostics.firstOrNull()?.code ?: "NATIVE_RESPONSE_UNAVAILABLE")
                    }
                    query.cursor to response
                }
            }
            when (val proof = CPrototypeTagFactsDecoder.decode(captured.asts, captured.sources, root, responses, captured.logicalPaths)) {
                is CPrototypeTagReadout.Found -> CPrototypeTagAcquisitionResult.Available(proof.facts)
                is CPrototypeTagReadout.Refused -> unavailable(proof.reason.name)
            }
        } catch (failure: CClangdConfigurationException) {
            CPrototypeTagAcquisitionResult.Unavailable(failure.diagnostic)
        } catch (_: Exception) {
            unavailable("NATIVE_ACQUISITION_FAILED")
        }
    }
}
