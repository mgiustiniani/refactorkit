package org.refactorkit.java

import org.refactorkit.core.*
import java.nio.file.Path

enum class JavaEditorQueryKind { HOVER, DEFINITION, REFERENCES, DOCUMENT_SYMBOLS, WORKSPACE_SYMBOLS, MUTATION_SELECTION, DIAGNOSTICS }
data class JavaEditorQueryRequest(val kind: JavaEditorQueryKind, val path: Path, val position: SourcePosition,
                                 val limit: Int, val includeDeclaration: Boolean = true, val query: String = "")
sealed interface JavaEditorQueryPayload {
    data class Hover(val value: JavaJdtHoverProjection.Available) : JavaEditorQueryPayload
    data class Definition(val value: JavaJdtDefinitionProjection.Available) : JavaEditorQueryPayload
    data class References(val value: JavaJdtReferencesProjection.Available) : JavaEditorQueryPayload
    data class Symbols(val values: List<Symbol>, val total: Int, val provenanceHash: String) : JavaEditorQueryPayload
    data class MutationSelection(val value:JavaEditorMutationSelection,val boundContext:JavaBoundContext) : JavaEditorQueryPayload
    data class Diagnostics(val values: List<Diagnostic>, val analyzedSourceCount: Int, val provenanceHash: String,
                           val providerId: String = JdtJavaAnalysisCache.PROVIDER_ID,
                           val backend: String = JdtJavaAnalysisCache.BACKEND) : JavaEditorQueryPayload
}
sealed interface JavaEditorQueryResult {
    data class Available(val context: JavaBoundContext, val overlay: ImmutableEditorOverlay,
                         val adapter: JavaLanguageAdapter, val payload: JavaEditorQueryPayload) : JavaEditorQueryResult
    data class Refused(val code: String, val message: String) : JavaEditorQueryResult
}

sealed interface JavaEditorReadResult<out T> {
    data class Available<T>(val context: JavaBoundContext, val overlay: ImmutableEditorOverlay, val value: T) : JavaEditorReadResult<T>
    data class Refused(val code: String, val message: String) : JavaEditorReadResult<Nothing>
}

/** Bounded application session. No transport owns version state or queries live workspace source text. */
class JavaEditorOverlaySession {
    private data class Observation(val version: Long, val hash: String)
    private val observed = LinkedHashMap<Path, Observation>()
    private var root: Path? = null
    fun clear() { observed.clear(); root = null }

    /** Application callback stays inside staging of this exact closed overlay and shared version history. */
    fun <T> read(contextRequest: JavaProjectContextRequest, path: Path, expectedOverlayHash: String,
                 scan: () -> ProjectSnapshot, overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay,
                 cancellation: SemanticCancellationToken,
                 query: (JavaBoundContext, ImmutableEditorOverlay, ProjectSnapshot) -> T): JavaEditorReadResult<T> {
        require(expectedOverlayHash.matches(Regex("[a-f0-9]{64}"))) { "Selected overlay hash must be SHA-256" }
        val acquired = JavaBoundContext.acquire(contextRequest, scan, cancellation)
        if (acquired is JavaBoundContextResolution.Refused) return JavaEditorReadResult.Refused(acquired.code, acquired.message)
        val context = (acquired as JavaBoundContextResolution.Available).context
        val overlay = overlayFactory(context.snapshot)
        return try {
            if (root != context.snapshot.workspace.root) clear()
            assertCurrent(overlay)
            if (overlay.document(path) == null) return JavaEditorReadResult.Refused("java.overlay.selectionMissing", "Selected path must be in the closed editor overlay")
            if (overlay.overlayHash != expectedOverlayHash) return JavaEditorReadResult.Refused("java.overlay.hashStale", "Closed editor documents differ from the selected overlay proof")
            acceptTransaction(emptyList(), overlay)
            val provider = AuthoritativeDiagnosticsEvaluation(overlay.providerSnapshot, emptyList()).snapshot
                .copy(semanticAnalysisAuthority = context.authority)
            val result = JavaLanguageAdapter().withDiagnosticsOverlay(provider) { staged ->
                context.authority.requireCurrent()
                val analysis = JdtJavaSemanticAnalyzer().analyze(staged, cancellation).copy(snapshotHash = provider.hash)
                val bound = JavaBoundContext.closedEditorContext(context, provider, analysis)
                query(bound, overlay, staged)
            }
            val drift = context.verifyAfterQuery(scan)
            if (drift != null) JavaEditorReadResult.Refused(drift.code, drift.message)
            else {
                assertCurrent(overlay)
                JavaEditorReadResult.Available(context, overlay, result)
            }
        } catch (failure: JavaContextAuthorityException) { JavaEditorReadResult.Refused(failure.code, failure.message.orEmpty()) }
        catch (_: JdtJavaAnalysisCancelledException) { JavaEditorReadResult.Refused("intelligence.cancelled", "Java editor read was cancelled") }
        catch (_: Exception) { JavaEditorReadResult.Refused("java.overlay.analysisUnavailable", "Closed Java editor analysis could not be completed") }
    }

    internal fun assertCurrent(overlay: ImmutableEditorOverlay) {
        if (root != null && root != overlay.providerSnapshot.workspace.root) throw JavaContextAuthorityException(
            "java.overlay.versionStale", "Editor history belongs to another workspace")
        if (overlay.documents.any { document -> observed[document.path]?.let { previous ->
                document.version < previous.version || document.version == previous.version &&
                    overlay.authority(document.path)?.contentSha256 != previous.hash
            } == true }) throw JavaContextAuthorityException("java.overlay.versionStale", "Editor authority is older than the observed version history")
    }

    internal fun acceptTransaction(previous: List<ImmutableEditorOverlayDocument>, overlay: ImmutableEditorOverlay) {
        assertCurrent(overlay)
        val current = overlay.documents.map { it.path }.toSet()
        if ((observed.keys + current).size > 5_000) throw JavaContextAuthorityException("java.overlay.limitExceeded", "Editor version history exceeds its bounded limit")
        previous.filter { it.path !in current }.forEach { observed.remove(it.path) }
        overlay.documents.forEach { observed[it.path] = Observation(it.version, requireNotNull(overlay.authority(it.path)).contentSha256) }
        root = overlay.providerSnapshot.workspace.root
    }

    fun query(contextRequest: JavaProjectContextRequest, request: JavaEditorQueryRequest,
              scan: () -> ProjectSnapshot, overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay,
              cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE): JavaEditorQueryResult {
        val acquired = JavaBoundContext.acquire(contextRequest, scan, cancellation)
        if (acquired is JavaBoundContextResolution.Refused) return refused(acquired.code, acquired.message)
        val context = (acquired as JavaBoundContextResolution.Available).context
        val overlay = overlayFactory(context.snapshot)
        if (overlay.document(request.path) == null) return refused("java.overlay.selectionMissing", "Query path must be present in the closed overlay")
        if (request.kind == JavaEditorQueryKind.DIAGNOSTICS) diagnosticsContextRefusal(context, overlay)?.let { return it }
        val selectedRoot = context.snapshot.workspace.root
        if (root != selectedRoot) clear()
        root = selectedRoot
        val current = overlay.documents.associate { document ->
            document.path to Observation(document.version, requireNotNull(overlay.authority(document.path)).contentSha256)
        }
        if (current.any { (path, version) -> observed[path]?.let { previous ->
                version.version < previous.version || version.version == previous.version && version.hash != previous.hash
            } == true }) return refused("java.overlay.versionStale", "Editor document version or content identity is obsolete")
        if ((observed.keys + current.keys).size > 5_000) return refused("java.overlay.limitExceeded", "Editor version history exceeds the bounded project limit")
        observed.putAll(current)
        return try {
            val providerSnapshot = AuthoritativeDiagnosticsEvaluation(overlay.providerSnapshot, emptyList()).snapshot
                .copy(semanticAnalysisAuthority = context.authority)
            val result = JavaLanguageAdapter().withDiagnosticsOverlay(providerSnapshot) { staged ->
                val cache = JdtJavaAnalysisCache(analyze = { candidate, token ->
                    require(candidate.hash == providerSnapshot.hash)
                    context.authority.requireCurrent()
                    JdtJavaSemanticAnalyzer().analyze(staged, token).copy(snapshotHash = candidate.hash)
                })
                val adapter = JavaLanguageAdapter(cache)
                val location = SourceLocation(request.path, SourceRange(request.position, request.position))
                val payload = when (request.kind) {
                    JavaEditorQueryKind.DIAGNOSTICS -> {
                        val paths = providerSnapshot.files.filter { it.languageId == "java" }.map { it.path }.toSet()
                        val native = JavaNativeCompilerDiagnostics().capture(staged, providerSnapshot.hash, context.authority, cancellation)
                        val remaining = paths - native?.paths.orEmpty()
                        val legacy = if (remaining.isEmpty()) null else if (native == null) cache.get(providerSnapshot, cancellation) else {
                            val subset = staged.copy(files = staged.files.filter { it.languageId != "java" || it.path in remaining })
                            JdtJavaAnalysisCache(analyze = { candidate, token ->
                                context.authority.requireCurrent()
                                JdtJavaSemanticAnalyzer().analyze(subset, token).copy(snapshotHash = candidate.hash)
                            }).get(providerSnapshot, cancellation)
                        }
                        if (legacy != null && legacy.analysis.entryPointSources.map { it.path }.toSet() != remaining) return@withDiagnosticsOverlay refused(
                            "java.diagnostics.analysisIncomplete", "Compiler analysis did not cover every captured Java source")
                        val values = (native?.diagnostics.orEmpty() + legacy?.let {
                            adapter.compilerDiagnostics(providerSnapshot, it.analysis)
                        }.orEmpty()).distinct().sortedWith(compareBy<Diagnostic> { ProtocolPath.serialize(requireNotNull(it.location).path) }
                            .thenBy { it.location!!.range.start }.thenBy { it.code }.thenBy { it.message })
                        val bytes = values.sumOf { it.message.toByteArray(Charsets.UTF_8).size.toLong() +
                            ProtocolPath.serialize(requireNotNull(it.location).path).toByteArray(Charsets.UTF_8).size }
                        if (values.size > request.limit || bytes > ProtocolLimits.MAX_PREVIEW_DIAGNOSTIC_BYTES ||
                            values.any { it.message.length > ProtocolLimits.MAX_DIAGNOSTIC_MESSAGE_CHARS }) return@withDiagnosticsOverlay refused(
                            "java.diagnostics.limitExceeded", "Complete compiler errors exceed the requested diagnostic count or message budget")
                        if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
                        val provenance = if (native == null) requireNotNull(legacy).provenanceHash else java.security.MessageDigest.getInstance("SHA-256")
                            .digest("${providerSnapshot.hash}\n${native.provenanceHash}\n${legacy?.provenanceHash.orEmpty()}".toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                        JavaEditorQueryPayload.Diagnostics(values, paths.size, provenance,
                            if (native == null) JdtJavaAnalysisCache.PROVIDER_ID else if (legacy == null) "java-sdk-compiler-diagnostics-v1" else "java-sdk-and-jdt-compiler-diagnostics-v1",
                            if (native == null) JdtJavaAnalysisCache.BACKEND else native.backend + if (legacy == null) "" else "+${JdtJavaAnalysisCache.BACKEND}")
                    }
                    JavaEditorQueryKind.MUTATION_SELECTION -> {
                        val bound=JavaBoundContext.closedEditorContext(context,providerSnapshot,cache.get(providerSnapshot,cancellation).analysis)
                        val selection=JavaMutationSelectionService.select(bound,request.path,request.position)
                        JavaEditorQueryPayload.MutationSelection(JavaEditorMutationSelection.create(overlay,selection),bound)
                    }
                    JavaEditorQueryKind.HOVER -> when (val value = adapter.semanticHover(providerSnapshot, location, cancellation)) {
                        is JavaJdtHoverProjection.Available -> JavaEditorQueryPayload.Hover(value)
                        is JavaJdtHoverProjection.Refused -> return@withDiagnosticsOverlay refused(value.diagnostic.code ?: "java.overlay.unavailable", value.diagnostic.message)
                    }
                    JavaEditorQueryKind.DEFINITION -> when (val value = adapter.semanticDefinition(providerSnapshot, location, cancellation)) {
                        is JavaJdtDefinitionProjection.Available -> JavaEditorQueryPayload.Definition(value)
                        is JavaJdtDefinitionProjection.Refused -> return@withDiagnosticsOverlay refused(value.diagnostic.code ?: "java.overlay.unavailable", value.diagnostic.message)
                    }
                    JavaEditorQueryKind.REFERENCES -> when (val value = adapter.semanticReferences(providerSnapshot, location, request.includeDeclaration, request.limit, cancellation)) {
                        is JavaJdtReferencesProjection.Available -> JavaEditorQueryPayload.References(value)
                        is JavaJdtReferencesProjection.Refused -> return@withDiagnosticsOverlay refused(value.diagnostic.code ?: "java.overlay.unavailable", value.diagnostic.message)
                    }
                    JavaEditorQueryKind.DOCUMENT_SYMBOLS, JavaEditorQueryKind.WORKSPACE_SYMBOLS -> {
                        val value = adapter.semanticDefinition(providerSnapshot, location, cancellation)
                        if (value is JavaJdtDefinitionProjection.Refused) return@withDiagnosticsOverlay refused(value.diagnostic.code ?: "java.overlay.unavailable", value.diagnostic.message)
                        value as JavaJdtDefinitionProjection.Available
                        val symbols = value.symbols.filter { symbol ->
                            (request.kind == JavaEditorQueryKind.WORKSPACE_SYMBOLS || symbol.location.path == request.path) &&
                                (request.query.isEmpty() || symbol.name.contains(request.query, true) || symbol.id.value.contains(request.query, true))
                        }.sortedWith(compareBy<Symbol> { ProtocolPath.serialize(it.location.path) }.thenBy { it.location.range.start.line }
                            .thenBy { it.location.range.start.character }.thenBy { it.id.value })
                        JavaEditorQueryPayload.Symbols(symbols.take(request.limit), symbols.size, value.provenanceHash)
                    }
                }
                JavaEditorQueryResult.Available(context, overlay, adapter, payload)
            }
            val drift = context.verifyAfterQuery(scan)
            if (drift != null) refused(drift.code, drift.message)
            else if (current.any { (path, value) -> observed[path] != value }) refused("java.overlay.versionStale", "Editor version changed during the query")
            else result
        } catch (failure: JavaContextAuthorityException) { refused(failure.code, failure.message.orEmpty()) }
        catch (_: JdtJavaAnalysisCancelledException) { refused("intelligence.cancelled", "Java editor query was cancelled") }
        catch (_: Exception) { refused("java.overlay.analysisUnavailable", "Closed Java editor analysis could not be completed") }
    }
    companion object {
        fun admitMutationSourceAuthority(mode: String?) {
            if (mode != null && mode != "saved-snapshot") throw JavaContextAuthorityException(
                "java.context.dirtyMutationUnsupported", "Java editor mutations require a qualified editor-mediated apply contract")
        }
    }
    private fun diagnosticsContextRefusal(context: JavaBoundContext, overlay: ImmutableEditorOverlay): JavaEditorQueryResult.Refused? {
        for (file in overlay.providerSnapshot.files.filter { it.languageId == "java" }) {
            val owner = context.snapshot.owningJavaBuildSourceRoots(file.path).singleOrNull()
                ?: return refused("java.diagnostics.sourceSetUnavailable", "Every captured Java source needs one authoritative source-set owner")
            val sourceSet = owner.sourceSet
            if (owner.modelStatus != BuildModelStatus.AVAILABLE || sourceSet.attributes["java.classpath.status"] == "unavailable") return refused(
                "java.context.classpathUnavailable", "Compiler diagnostics require the complete effective compile classpath")
            if (sourceSet.compilerOptionsAvailability != BuildFactAvailability.COMPLETE) return refused(
                sourceSet.compilerOptionsRefusalCode ?: "java.context.compilerOptionsUnavailable", "Compiler diagnostics require complete effective compiler options")
            if (sourceSet.sourceRootEvidence.map { it.path }.toSet() != sourceSet.sourceRoots.toSet() ||
                sourceSet.sourceRootEvidence.any { it.availability != BuildFactAvailability.COMPLETE }) return refused(
                "java.context.sourceRootProvenanceIncomplete", "Compiler diagnostics require complete captured source-root provenance")
        }
        return null
    }
    private fun refused(code: String, message: String) = JavaEditorQueryResult.Refused(code, message)
}
