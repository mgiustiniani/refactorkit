package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GradleExportFailureCauseSpec : StringSpec({
    val home = "/owned/cache"
    fun classify(text: String, roots: Set<String> = setOf(home)) =
        GradleExportFailureClassifier.classify(text.toByteArray(Charsets.UTF_8), roots)

    "a native cache lock read-only error identifies only the configured cache" {
        classify("java.io.FileNotFoundException: $home/native/libnative-platform.so.lock (Read-only file system)") shouldBe
            GradleExportFailureCause.CACHE_READ_ONLY
    }
    "a denied cache journal lock reports cache access evidence" {
        classify("java.io.FileNotFoundException: $home/caches/journal-1/journal.lock (Permission denied)") shouldBe
            GradleExportFailureCause.CACHE_PERMISSION_DENIED
    }
    "a path sharing only a cache string prefix remains an unowned filesystem error" {
        classify("$home-unrelated/file.lock (Permission denied)") shouldBe GradleExportFailureCause.FILESYSTEM_PERMISSION_DENIED
    }
    "a cache path embedded in a different absolute path is not cache ownership" {
        classify("/other$home/native/file.lock (Permission denied)") shouldBe GradleExportFailureCause.FILESYSTEM_PERMISSION_DENIED
    }
    "a permission marker on another line does not attribute an unrelated cache path" {
        classify("Cache directory: $home/caches\n/other/file (Read-only file system)") shouldBe GradleExportFailureCause.FILESYSTEM_READ_ONLY
    }
    "a captured canonical cache alias is recognized without filesystem operations" {
        classify("/physical/cache/native/file (Read-only file system)", setOf(home, "/physical/cache")) shouldBe GradleExportFailureCause.CACHE_READ_ONLY
    }
    "missing module metadata in offline mode has a distinct cause" {
        classify("No cached version of org.fixture:missing:1.0 available for offline mode.") shouldBe GradleExportFailureCause.OFFLINE_DEPENDENCY_UNAVAILABLE
    }
    "missing artifact bytes in offline mode uses the marker observed by the IDE" {
        classify("Could not download junit-4.13.2.jar\nNo cached version available for offline mode") shouldBe GradleExportFailureCause.OFFLINE_DEPENDENCY_UNAVAILABLE
    }
    "offline resource cache failures use the same bounded resolution cause" {
        classify("No cached resource available for offline mode") shouldBe GradleExportFailureCause.OFFLINE_DEPENDENCY_UNAVAILABLE
    }
    "a generic resolution error does not manufacture offline dependency evidence" {
        classify("Could not resolve all files for configuration compileClasspath") shouldBe GradleExportFailureCause.UNKNOWN
    }
    "an oversized observation is not silently treated as complete evidence" {
        GradleExportFailureClassifier.classify(ByteArray(GradleExportFailureClassifier.MAX_OUTPUT_BYTES + 1), setOf(home)) shouldBe GradleExportFailureCause.UNKNOWN
    }
    "fixed cause fields cannot reveal injected paths credentials URLs or terminal controls" {
        val cause = classify("$home/native/secret.lock (Read-only file system)\npassword=do-not-leak https://user:secret@example.invalid \u001b[31m")
        cause shouldBe GradleExportFailureCause.CACHE_READ_ONLY
        cause.code shouldBe "gradle.sync.cause.cacheReadOnly"
        cause.message shouldBe "Gradle reported a read-only filesystem for its user-home cache."
        GradleExportFailureCause.values().all { it.message.length in 1..256 && it.message.none(Char::isISOControl) } shouldBe true
    }
})
