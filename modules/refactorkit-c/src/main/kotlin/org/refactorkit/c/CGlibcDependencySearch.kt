package org.refactorkit.c

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.Collections

enum class CGlibcSearchRefusal { INVALID_INPUT, UNSUPPORTED_PROFILE, INPUT_LIMIT }

sealed interface CGlibcDependencySearchResult {
    data class Proposed(val search: CGlibcDependencySearch) : CGlibcDependencySearchResult
    data class Refused(val reason: CGlibcSearchRefusal) : CGlibcDependencySearchResult
}

/** Ordered supplied-metadata proposal; filesystem selection and runtime admission are separate. */
class CGlibcDependencySearch private constructor(val requester: Path, val neededIndex: Int, val neededName: String,
    val runpathCandidates: List<Path>, val cacheFallback: CGlibcCacheLookup) {
    companion object {
        fun propose(requester: ClangNativePathCaptureResult.Captured, neededIndex: Int,
            cache: CGlibcLoaderCacheFacts): CGlibcDependencySearchResult = try {
            val neededName = requester.artifact.facts.needed.getOrNull(neededIndex)
                ?: refuse(CGlibcSearchRefusal.INVALID_INPUT)
            text(neededName)
            if (neededName in setOf(".", "..") || neededName.any { it == '/' || it == '\\' || it == '$' }) {
                refuse(CGlibcSearchRefusal.INVALID_INPUT)
            }
            val path = requester.artifact.evidence.path
            text(path.toString())
            if (!path.isAbsolute || path.fileName == null || path.normalize() != path || Path.of(path.toString()) != path) {
                refuse(CGlibcSearchRefusal.INVALID_INPUT)
            }
            val facts = requester.artifact.facts
            if (facts.rpath != null || facts.dynamicFlags1 and NODEFLIB != 0L) refuse(CGlibcSearchRefusal.UNSUPPORTED_PROFILE)
            val candidates = facts.runpath?.let { runpath ->
                text(runpath)
                val directories = runpath.split(':')
                if (directories.size > 64) refuse(CGlibcSearchRefusal.INPUT_LIMIT)
                directories.map { entry ->
                    if (entry.isEmpty()) refuse(CGlibcSearchRefusal.UNSUPPORTED_PROFILE)
                    val token = when {
                        entry.startsWith("\$ORIGIN") -> "\$ORIGIN"
                        entry.startsWith("\${ORIGIN}") -> "\${ORIGIN}"
                        else -> null
                    }
                    val expanded = if (token != null) {
                        val suffix = entry.substring(token.length)
                        if (suffix.isNotEmpty() && !suffix.startsWith('/')) refuse(CGlibcSearchRefusal.UNSUPPORTED_PROFILE)
                        text(requester.requestedPath.toString())
                        if (requester.aliases.isNotEmpty() || requester.requestedPath.normalize() != path) refuse(CGlibcSearchRefusal.UNSUPPORTED_PROFILE)
                        path.parent.toString() + suffix
                    } else entry
                    text(expanded)
                    if ('$' in expanded || !Path.of(expanded).isAbsolute) refuse(CGlibcSearchRefusal.UNSUPPORTED_PROFILE)
                    Path.of(expanded).resolve(neededName).also { text(it.toString()) }
                }
            }.orEmpty()
            CGlibcDependencySearchResult.Proposed(CGlibcDependencySearch(path, neededIndex, neededName,
                Collections.unmodifiableList(ArrayList(candidates)), cache.nativeCandidate(neededName)))
        } catch (failure: Refused) {
            CGlibcDependencySearchResult.Refused(failure.reason)
        } catch (_: CharacterCodingException) {
            CGlibcDependencySearchResult.Refused(CGlibcSearchRefusal.INVALID_INPUT)
        } catch (_: IllegalArgumentException) {
            CGlibcDependencySearchResult.Refused(CGlibcSearchRefusal.INVALID_INPUT)
        }

        private fun text(value: String) {
            if (value.isEmpty() || value.any(Char::isISOControl)) refuse(CGlibcSearchRefusal.INVALID_INPUT)
            if (value.length > 4096) refuse(CGlibcSearchRefusal.INPUT_LIMIT)
            val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
            if (bytes.remaining() > 4096) refuse(CGlibcSearchRefusal.INPUT_LIMIT)
        }
        private class Refused(val reason: CGlibcSearchRefusal) : RuntimeException()
        private fun refuse(reason: CGlibcSearchRefusal): Nothing = throw Refused(reason)
        private const val NODEFLIB = 0x800L
    }
}
