package org.refactorkit.java

/** Fixed, non-secret hints from the exporter output; these are reported evidence, not root-cause certification. */
enum class GradleExportFailureCause(val code: String, val message: String) {
    CACHE_READ_ONLY("gradle.sync.cause.cacheReadOnly", "Gradle reported a read-only filesystem for its user-home cache."),
    CACHE_PERMISSION_DENIED("gradle.sync.cause.cachePermissionDenied", "Gradle reported denied filesystem access for its user-home cache."),
    FILESYSTEM_READ_ONLY("gradle.sync.cause.filesystemReadOnly", "Gradle reported a read-only filesystem; cache ownership could not be established."),
    FILESYSTEM_PERMISSION_DENIED("gradle.sync.cause.filesystemPermissionDenied", "Gradle reported denied filesystem access; cache ownership could not be established."),
    OFFLINE_DEPENDENCY_UNAVAILABLE("gradle.sync.cause.offlineDependencyUnavailable", "Gradle reported a dependency or resource unavailable in its offline cache."),
    UNKNOWN("gradle.sync.cause.unknown", "Gradle output did not establish a recognized export failure cause."),
}

internal object GradleExportFailureClassifier {
    const val MAX_OUTPUT_BYTES = 1_048_576

    fun classify(output: ByteArray, cacheRoots: Set<String>): GradleExportFailureCause {
        if (output.size > MAX_OUTPUT_BYTES) return GradleExportFailureCause.UNKNOWN
        var filesystemReadOnly = false
        var filesystemDenied = false
        var offlineMissing = false
        for (line in output.toString(Charsets.UTF_8).lineSequence()) {
            // Console output is untrusted; long lines do not establish complete diagnostic evidence.
            if (line.length > 16_384) continue
            val lower = line.lowercase(java.util.Locale.ROOT)
            val readOnly = "read-only file system" in lower || "readonlyfilesystemexception" in lower
            val denied = "permission denied" in lower || "accessdeniedexception" in lower
            val cachePath = cacheRoots.filter(String::isNotBlank).any { mentionsCachePath(line, it) }
            if (cachePath && readOnly) return GradleExportFailureCause.CACHE_READ_ONLY
            if (cachePath && denied) return GradleExportFailureCause.CACHE_PERMISSION_DENIED
            filesystemReadOnly = filesystemReadOnly || readOnly
            filesystemDenied = filesystemDenied || denied
            offlineMissing = offlineMissing || (("no cached version" in lower || "no cached resource" in lower) &&
                "available for offline mode" in lower)
        }
        return when {
            filesystemReadOnly -> GradleExportFailureCause.FILESYSTEM_READ_ONLY
            filesystemDenied -> GradleExportFailureCause.FILESYSTEM_PERMISSION_DENIED
            offlineMissing -> GradleExportFailureCause.OFFLINE_DEPENDENCY_UNAVAILABLE
            else -> GradleExportFailureCause.UNKNOWN
        }
    }

    private fun mentionsCachePath(line: String, root: String): Boolean {
        val prefix = root.trimEnd('/', '\\')
        if (prefix.isEmpty()) return false
        var index = line.indexOf(prefix)
        while (index >= 0) {
            val before = line.getOrNull(index - 1)
            val after = line.getOrNull(index + prefix.length)
            val startsPath = before == null || before.isWhitespace() || before in "\"'(=:["
            val endsRoot = after == null || after.isWhitespace() || after == '/' || after == '\\' || after in "\"')]"
            if (startsPath && endsRoot) return true
            index = line.indexOf(prefix, index + 1)
        }
        return false
    }
}
