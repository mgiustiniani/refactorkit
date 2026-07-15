package org.refactorkit.java

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticCancellationToken
import java.security.MessageDigest

class JdtJavaAnalysisLimitException(message: String) : RuntimeException(message)
class JdtJavaAnalysisCancelledException : RuntimeException("JDT analysis was cancelled")

data class JdtJavaCachedAnalysis(
    val snapshotHash: String,
    val analysis: JdtJavaSemanticAnalysisResult,
    val provenanceHash: String,
)

data class JdtJavaAnalysisCacheStatus(
    val entries: Int,
    val hits: Long,
    val misses: Long,
)

/** Session-owned bounded cache; no JDT compiler object crosses the snapshot key. */
class JdtJavaAnalysisCache(
    private val maxEntries: Int = 2,
    private val analyze: (ProjectSnapshot, SemanticCancellationToken) -> JdtJavaSemanticAnalysisResult =
        { snapshot, cancellation -> JdtJavaSemanticAnalyzer().analyze(snapshot, cancellation) },
) {
    private val entries = object : LinkedHashMap<String, JdtJavaCachedAnalysis>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JdtJavaCachedAnalysis>?): Boolean =
            size > maxEntries
    }
    private var hits = 0L
    private var misses = 0L

    init { require(maxEntries in 1..8) { "JDT cache entry limit is invalid" } }

    @Synchronized
    fun get(
        snapshot: ProjectSnapshot,
        cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE,
    ): JdtJavaCachedAnalysis {
        if (cancellation.isCancellationRequested()) throw JdtJavaAnalysisCancelledException()
        entries[snapshot.hash]?.let {
            hits++
            return it
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
        val cached = JdtJavaCachedAnalysis(
            snapshot.hash,
            result,
            sha256("$PROVIDER_ID\n${snapshot.hash}\n$javaFiles\n$javaBytes\n${result.symbols.size}\n${result.references.size}"),
        )
        entries[snapshot.hash] = cached
        return cached
    }

    @Synchronized
    fun status(): JdtJavaAnalysisCacheStatus = JdtJavaAnalysisCacheStatus(entries.size, hits, misses)

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        const val PROVIDER_ID = "java-jdt-bindings-v1"
        const val BACKEND = "eclipse-jdt-3.44-bindings-v1"
        const val MAX_JAVA_SOURCE_FILES = 10_000
        const val MAX_JAVA_SOURCE_BYTES = 268_435_456L

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
