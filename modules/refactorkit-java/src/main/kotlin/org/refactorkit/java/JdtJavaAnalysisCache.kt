package org.refactorkit.java

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticCancellationToken
import java.security.MessageDigest

class JdtJavaAnalysisLimitException(message: String) : RuntimeException(message)
class JdtJavaAnalysisCancelledException : RuntimeException("JDT analysis was cancelled")
class JdtJavaAnalysisAttestationException : RuntimeException("JDT analysis belongs to another snapshot")

data class JdtJavaCachedAnalysis(
    val snapshotHash: String,
    val analysis: JdtJavaSemanticAnalysisResult,
    val provenanceHash: String,
    val semanticInputHash: String = snapshotHash,
)

data class JdtJavaAnalysisCacheStatus(
    val entries: Int,
    val hits: Long,
    val misses: Long,
    val crossSnapshotHits: Long = 0,
)

/** Session-owned bounded cache; no JDT compiler object crosses the snapshot key. */
class JdtJavaAnalysisCache(
    private val maxEntries: Int = 2,
    private val analyze: (ProjectSnapshot, SemanticCancellationToken) -> JdtJavaSemanticAnalysisResult =
        { snapshot, cancellation -> JdtJavaSemanticAnalyzer().analyze(snapshot, cancellation) },
) {
    private val entries = LinkedHashMap<String, JdtJavaCachedAnalysis>(maxEntries, 0.75f, true)
    private var hits = 0L
    private var misses = 0L
    private var crossSnapshotHits = 0L

    init { require(maxEntries in 1..8) { "JDT cache entry limit is invalid" } }

    @Synchronized
    fun get(
        snapshot: ProjectSnapshot,
        cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE,
    ): JdtJavaCachedAnalysis {
        if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
        val semanticInputHash = semanticInputHash(snapshot)
        entries[semanticInputHash]?.let { cached ->
            hits++
            if (cached.snapshotHash != snapshot.hash) crossSnapshotHits++
            return if (cached.snapshotHash == snapshot.hash) cached else cached.copy(
                snapshotHash = snapshot.hash,
                analysis = cached.analysis.copy(snapshotHash = snapshot.hash),
            )
        }
        val javaSources = snapshot.files.filter { it.languageId == "java" }
        val javaFiles = javaSources.size
        if (javaFiles > MAX_JAVA_SOURCE_FILES) throw JdtJavaAnalysisLimitException(
            "JDT analysis exceeds $MAX_JAVA_SOURCE_FILES Java source files",
        )
        var javaBytes = 0L
        javaSources.forEach { source ->
            javaBytes += source.content.toByteArray(Charsets.UTF_8).size
            if (javaBytes > MAX_JAVA_SOURCE_BYTES) throw JdtJavaAnalysisLimitException(
                "JDT analysis exceeds $MAX_JAVA_SOURCE_BYTES UTF-8 source bytes",
            )
        }
        misses++
        val result = analyze(snapshot, cancellation)
        if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
        if (result.snapshotHash != snapshot.hash) throw JdtJavaAnalysisAttestationException()
        val cached = JdtJavaCachedAnalysis(
            snapshot.hash,
            result,
            sha256("$PROVIDER_ID\n$semanticInputHash\n$javaFiles\n$javaBytes\n${result.symbols.size}\n${result.references.size}"),
            semanticInputHash,
        )
        entries[semanticInputHash] = cached
        if (entries.size > maxEntries) entries.entries.iterator().run { next(); remove() }
        return cached
    }

    @Synchronized
    fun status(): JdtJavaAnalysisCacheStatus = JdtJavaAnalysisCacheStatus(
        entries.size, hits, misses, crossSnapshotHits,
    )

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        const val PROVIDER_ID = "java-jdt-bindings-v1"
        const val BACKEND = "eclipse-jdt-3.44-bindings-v1"
        const val MAX_JAVA_SOURCE_FILES = 10_000
        const val MAX_JAVA_SOURCE_BYTES = 268_435_456L

        private fun semanticInputHash(snapshot: ProjectSnapshot): String {
            val semantic = snapshot.copy(
                files = snapshot.files.filter { it.languageId == "java" },
                sourceExtensions = setOf("java"),
                buildModels = snapshot.buildModels.filterNot { model ->
                    model.providerId == "kotlin-jvm-projection-v1" ||
                        model.providerId.startsWith("typescript-") ||
                        model.attributes["ecosystem"] in setOf("typescript", "javascript")
                },
            )
            return semantic.hash
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
