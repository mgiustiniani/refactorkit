package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

enum class ClangToolchainRuntimeRefusal { ROLE_MISMATCH, INCONSISTENT_INPUTS, INCONSISTENT_OBJECT, INVALID_INPUT, INPUT_LIMIT }
sealed interface ClangToolchainRuntimeComposition {
    data class Composed(val facts: ClangToolchainRuntimeFacts) : ClangToolchainRuntimeComposition
    data class Refused(val reason: ClangToolchainRuntimeRefusal) : ClangToolchainRuntimeComposition
}

sealed interface ClangToolchainRuntimeCaptureResult {
    data object NotCaptured : ClangToolchainRuntimeCaptureResult
    class Captured internal constructor(val facts: ClangToolchainRuntimeFacts) : ClangToolchainRuntimeCaptureResult
    data class Unavailable(val diagnostic: Diagnostic, val inputDiagnostic: Diagnostic? = null) : ClangToolchainRuntimeCaptureResult
}

/** Coherent immutable supplied captures; identity construction does not prove freshness or admission. */
class ClangToolchainRuntimeFacts private constructor(val clang: ClangGlibcRuntimeResolution.Captured,
    val clangd: ClangGlibcRuntimeResolution.Captured, val formatter: ClangGlibcRuntimeResolution.Captured, val identity: String) {
    val runtimes: List<ClangGlibcRuntimeResolution.Captured> = Collections.unmodifiableList(listOf(clang, clangd, formatter))

    companion object {
        fun compose(clang: ClangGlibcRuntimeResolution.Captured, clangd: ClangGlibcRuntimeResolution.Captured,
            formatter: ClangGlibcRuntimeResolution.Captured): ClangToolchainRuntimeComposition = try {
            val runtimes = listOf(clang, clangd, formatter)
            val roles = listOf(ClangArtifactRole.CLANG, ClangArtifactRole.CLANGD, ClangArtifactRole.FORMATTER)
            if (runtimes.indices.any { runtimes[it].manifest.executable.artifact.role != roles[it] } ||
                runtimes.map { it.manifest.executable.artifact.evidence.path }.distinct().size != 3) refuse(ClangToolchainRuntimeRefusal.ROLE_MISMATCH)
            val first = clang.inputs
            val objects = mutableMapOf<Path, ClangArtifactCaptureResult.Captured>()
            for (runtime in runtimes) {
                if (runtime.observations.size > 8192) refuse(ClangToolchainRuntimeRefusal.INPUT_LIMIT)
                val input = runtime.inputs; val cache = input.cacheFacts; val evidence = input.cacheEvidence
                if (evidence.role != "glibc-loader-cache" || evidence.sha256 != cache.sha256 || !HASH.matches(cache.sha256) ||
                    evidence.size !in 48..8L * 1024 * 1024 || !direct(evidence.path) || !direct(input.preloadPath)) refuse(ClangToolchainRuntimeRefusal.INVALID_INPUT)
                if (evidence != first.cacheEvidence || input.preloadPath != first.preloadPath ||
                    cache.sha256 != first.cacheFacts.sha256 || cache.headerFlags != first.cacheFacts.headerFlags ||
                    cache.entries != first.cacheFacts.entries || cache.extensions != first.cacheFacts.extensions) refuse(ClangToolchainRuntimeRefusal.INCONSISTENT_INPUTS)
                for (obj in runtime.manifest.objects) {
                    val earlier = objects.putIfAbsent(obj.artifact.evidence.path, obj.artifact)
                    if (earlier != null && earlier != obj.artifact) refuse(ClangToolchainRuntimeRefusal.INCONSISTENT_OBJECT)
                }
            }
            val digest = RuntimeDigest("refactorkit.clang.toolchain.runtime.identity.v1")
            digest.count(3)
            for (index in roles.indices) { digest.frame(roles[index].name); digest.frame(ClangGlibcRuntimeIdentity.of(runtimes[index])) }
            ClangToolchainRuntimeComposition.Composed(ClangToolchainRuntimeFacts(clang, clangd, formatter, digest.finish()))
        } catch (failure: Refused) {
            ClangToolchainRuntimeComposition.Refused(failure.reason)
        } catch (_: CharacterCodingException) {
            ClangToolchainRuntimeComposition.Refused(ClangToolchainRuntimeRefusal.INVALID_INPUT)
        }

        private fun direct(path: Path) = path.isAbsolute && path.fileName != null && path.normalize() == path
        private class Refused(val reason: ClangToolchainRuntimeRefusal) : RuntimeException()
        private fun refuse(reason: ClangToolchainRuntimeRefusal): Nothing = throw Refused(reason)
        private val HASH = Regex("[0-9a-f]{64}")
    }
}

/** Pure framing of all retained resolution facts; never substitutes for the native currency boundary. */
internal object ClangGlibcRuntimeIdentity {
    fun of(runtime: ClangGlibcRuntimeResolution.Captured): String {
        val digest = RuntimeDigest("refactorkit.clang.glibc.resolution.identity.v1")
        digest.frame("linux-elf64-le-x86-64-cleared-environment-runpath-cache-v1")
        digest.frame(runtime.manifest.identity)
        val evidence = runtime.inputs.cacheEvidence
        digest.frame(evidence.role); digest.frame(evidence.path.toString()); digest.number(evidence.size); digest.frame(evidence.sha256)
        digest.frame("preload-absent-v1"); digest.frame(runtime.inputs.preloadPath.toString())
        val cache = runtime.inputs.cacheFacts
        digest.frame(cache.sha256); digest.count(cache.headerFlags); digest.count(cache.entries.size)
        for (entry in cache.entries) {
            digest.frame(entry.name); digest.frame(entry.path); digest.count(entry.flags); digest.number(entry.osVersion); digest.number(entry.hwcap)
        }
        digest.count(cache.extensions.size)
        for (extension in cache.extensions) {
            digest.number(extension.tag); digest.number(extension.flags); digest.number(extension.offset); digest.number(extension.size); digest.frame(extension.sha256)
        }
        digest.count(runtime.observations.size)
        for (observation in runtime.observations) {
            digest.frame(observation.requester.toString()); digest.count(observation.neededIndex)
            digest.frame(observation.kind.name); digest.frame(observation.path.toString())
        }
        return digest.finish()
    }
}

private class RuntimeDigest(version: String) {
    private val digest = MessageDigest.getInstance("SHA-256")
    init { frame(version) }
    fun frame(value: String?) {
        if (value == null) digest.update(0.toByte()) else {
            val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
            digest.update(1.toByte()); count(bytes.remaining()); digest.update(bytes)
        }
    }
    fun count(value: Int) { digest.update(ByteBuffer.allocate(4).putInt(value).array()) }
    fun number(value: Long) { digest.update(ByteBuffer.allocate(8).putLong(value).array()) }
    fun finish() = digest.digest().joinToString("") { "%02x".format(it) }
}
