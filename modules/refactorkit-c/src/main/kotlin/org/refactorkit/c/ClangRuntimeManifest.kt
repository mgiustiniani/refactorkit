package org.refactorkit.c

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

data class ClangRuntimeDependencyBinding(val requester: Path, val neededIndex: Int, val target: Path)

enum class ClangRuntimeManifestRefusal {
    INPUT_LIMIT, INVALID_INPUT, ROLE_MISMATCH, INCOMPLETE_GRAPH, AMBIGUOUS_GRAPH, NAME_MISMATCH, UNREACHABLE_OBJECT,
}

sealed interface ClangRuntimeManifestComposition {
    data class Composed(val manifest: ClangRuntimeManifest) : ClangRuntimeManifestComposition
    data class Refused(val reason: ClangRuntimeManifestRefusal) : ClangRuntimeManifestComposition
}

/** Pure consistency of supplied captures; neither loader resolution nor execution admission. */
class ClangRuntimeManifest private constructor(
    val executable: ClangNativePathCaptureResult.Captured,
    val interpreter: ClangNativePathCaptureResult.Captured,
    val objects: List<ClangNativePathCaptureResult.Captured>,
    val bindings: List<ClangRuntimeDependencyBinding>,
    val identity: String,
) {
    companion object {
        fun compose(executable: ClangNativePathCaptureResult.Captured,
            interpreter: ClangNativePathCaptureResult.Captured,
            libraries: List<ClangNativePathCaptureResult.Captured>,
            bindings: List<ClangRuntimeDependencyBinding>): ClangRuntimeManifestComposition = try {
            if (libraries.size > MAX_OBJECTS - 2 || bindings.size > MAX_EDGES) refuse(ClangRuntimeManifestRefusal.INPUT_LIMIT)
            val root = snapshot(executable)
            val loader = snapshot(interpreter)
            val objects = listOf(root, loader) + libraries.map(::snapshot)
            var totalBytes = 0L
            for (obj in objects) {
                validate(obj)
                totalBytes += obj.artifact.evidence.size
                if (totalBytes > MAX_TOTAL_BYTES) refuse(ClangRuntimeManifestRefusal.INPUT_LIMIT)
            }
            validateRoles(root, loader, objects.drop(2))
            val byPath = objects.associateBy { it.artifact.evidence.path }
            if (byPath.size != objects.size) refuse(ClangRuntimeManifestRefusal.AMBIGUOUS_GRAPH)
            val rootPath = root.artifact.evidence.path
            val loaderPath = loader.artifact.evidence.path
            val names = mutableMapOf<String, Path>()
            for (obj in objects.drop(1)) {
                val path = obj.artifact.evidence.path
                for (name in listOfNotNull(obj.artifact.facts.soname, obj.requestedPath.fileName?.toString()).distinct()) {
                    val previous = names.putIfAbsent(name, path)
                    if (previous != null && previous != path) refuse(ClangRuntimeManifestRefusal.AMBIGUOUS_GRAPH)
                }
            }
            val expectedEdges = objects.sumOf { it.artifact.facts.needed.size }
            if (expectedEdges > MAX_EDGES) refuse(ClangRuntimeManifestRefusal.INPUT_LIMIT)
            val selected = bindings.toList()
            val edges = mutableMapOf<Pair<Path, Int>, ClangRuntimeDependencyBinding>()
            val selectedNames = mutableMapOf<String, Path>()
            for (edge in selected) {
                physicalPath(edge.requester); physicalPath(edge.target)
                val requester = byPath[edge.requester] ?: refuse(ClangRuntimeManifestRefusal.INCOMPLETE_GRAPH)
                val target = byPath[edge.target] ?: refuse(ClangRuntimeManifestRefusal.INCOMPLETE_GRAPH)
                val needed = requester.artifact.facts.needed.getOrNull(edge.neededIndex)
                    ?: refuse(ClangRuntimeManifestRefusal.INCOMPLETE_GRAPH)
                if (edges.put(edge.requester to edge.neededIndex, edge) != null) refuse(ClangRuntimeManifestRefusal.AMBIGUOUS_GRAPH)
                if (edge.target == rootPath || needed !in listOfNotNull(target.artifact.facts.soname,
                        target.requestedPath.fileName?.toString())) refuse(ClangRuntimeManifestRefusal.NAME_MISMATCH)
                val previous = selectedNames.putIfAbsent(needed, edge.target)
                if (previous != null && previous != edge.target) refuse(ClangRuntimeManifestRefusal.AMBIGUOUS_GRAPH)
            }
            if (edges.size != expectedEdges) refuse(ClangRuntimeManifestRefusal.INCOMPLETE_GRAPH)
            val reached = mutableSetOf<Path>()
            val pending = java.util.ArrayDeque<Path>()
            pending.add(rootPath); pending.add(loaderPath)
            val outgoing = selected.groupBy { it.requester }
            while (pending.isNotEmpty()) {
                val path = pending.removeFirst()
                if (reached.add(path)) outgoing[path].orEmpty().forEach { pending.add(it.target) }
            }
            if (reached.size != objects.size) refuse(ClangRuntimeManifestRefusal.UNREACHABLE_OBJECT)
            val canonicalObjects = immutable(objects.sortedBy { it.artifact.evidence.path.toString() })
            val canonicalEdges = immutable(selected.sortedWith(compareBy<ClangRuntimeDependencyBinding> { it.requester.toString() }
                .thenBy { it.neededIndex }.thenBy { it.target.toString() }))
            ClangRuntimeManifestComposition.Composed(ClangRuntimeManifest(root, loader, canonicalObjects, canonicalEdges,
                identity(rootPath, loaderPath, canonicalObjects, canonicalEdges)))
        } catch (failure: Refused) {
            ClangRuntimeManifestComposition.Refused(failure.reason)
        } catch (_: CharacterCodingException) {
            ClangRuntimeManifestComposition.Refused(ClangRuntimeManifestRefusal.INVALID_INPUT)
        }

        private fun snapshot(input: ClangNativePathCaptureResult.Captured): ClangNativePathCaptureResult.Captured {
            if (input.aliases.size > MAX_ALIASES || input.artifact.facts.needed.size > MAX_NEEDED) {
                refuse(ClangRuntimeManifestRefusal.INPUT_LIMIT)
            }
            return input.copy(aliases = immutable(input.aliases), artifact = input.artifact.copy(
                facts = input.artifact.facts.copy(needed = immutable(input.artifact.facts.needed))))
        }

        private fun validate(input: ClangNativePathCaptureResult.Captured) {
            absolutePath(input.requestedPath)
            for (alias in input.aliases) { physicalPath(alias.path); text(alias.target.toString(), false) }
            if (input.aliases.map { it.path }.distinct().size != input.aliases.size) refuse(ClangRuntimeManifestRefusal.INVALID_INPUT)
            val artifact = input.artifact; val evidence = artifact.evidence; val facts = artifact.facts
            physicalPath(evidence.path)
            if (evidence.role != artifact.role.evidenceRole || evidence.sha256 != facts.sha256 ||
                !HASH.matches(evidence.sha256) || evidence.size < 64 || facts.dynamicFlags < 0 || facts.dynamicFlags1 < 0) {
                refuse(ClangRuntimeManifestRefusal.INVALID_INPUT)
            }
            if (evidence.size > MAX_OBJECT_BYTES) refuse(ClangRuntimeManifestRefusal.INPUT_LIMIT)
            facts.interpreter?.let { text(it, false); absolutePath(Path.of(it)) }
            facts.needed.forEach(::name)
            facts.soname?.let(::name)
            facts.rpath?.let { text(it, true) }; facts.runpath?.let { text(it, true) }
        }

        private fun validateRoles(root: ClangNativePathCaptureResult.Captured,
            loader: ClangNativePathCaptureResult.Captured, libraries: List<ClangNativePathCaptureResult.Captured>) {
            if (root.artifact.role !in setOf(ClangArtifactRole.CLANG, ClangArtifactRole.CLANGD, ClangArtifactRole.FORMATTER) ||
                !root.artifact.facts.hasDynamicSegment || root.artifact.facts.interpreter == null ||
                loader.artifact.role != ClangArtifactRole.INTERPRETER ||
                loader.artifact.facts.interpreter != null ||
                root.artifact.facts.interpreter != loader.requestedPath.toString()) refuse(ClangRuntimeManifestRefusal.ROLE_MISMATCH)
            if (libraries.any { it.artifact.role != ClangArtifactRole.SHARED_LIBRARY }) refuse(ClangRuntimeManifestRefusal.ROLE_MISMATCH)
            for (obj in listOf(loader) + libraries) {
                val facts = obj.artifact.facts
                if (facts.objectType != CElfObjectType.DYNAMIC || !facts.hasDynamicSegment ||
                    facts.dynamicFlags1 and DF_1_PIE != 0L) refuse(ClangRuntimeManifestRefusal.ROLE_MISMATCH)
            }
        }

        private fun name(value: String) {
            text(value, false)
            if (value in setOf(".", "..") || value.any { it == '/' || it == '\\' || it == '$' }) refuse(ClangRuntimeManifestRefusal.INVALID_INPUT)
        }
        private fun absolutePath(path: Path) {
            text(path.toString(), false)
            if (!path.isAbsolute || path.fileName == null || Path.of(path.toString()) != path) refuse(ClangRuntimeManifestRefusal.INVALID_INPUT)
        }
        private fun physicalPath(path: Path) {
            absolutePath(path)
            if (path.normalize() != path) refuse(ClangRuntimeManifestRefusal.INVALID_INPUT)
        }
        private fun text(value: String, empty: Boolean) {
            if ((!empty && value.isEmpty()) || value.any(Char::isISOControl)) refuse(ClangRuntimeManifestRefusal.INVALID_INPUT)
            if (value.length > MAX_STRING_BYTES) refuse(ClangRuntimeManifestRefusal.INPUT_LIMIT)
            if (encoded(value).remaining() > MAX_STRING_BYTES) refuse(ClangRuntimeManifestRefusal.INPUT_LIMIT)
        }

        private fun identity(root: Path, loader: Path, objects: List<ClangNativePathCaptureResult.Captured>,
            edges: List<ClangRuntimeDependencyBinding>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.frame("refactorkit.clang.runtime.manifest.v1")
            digest.frame("linux-elf64-le-x86-64")
            digest.frame(root.toString()); digest.frame(loader.toString()); digest.count(objects.size)
            for (obj in objects) {
                digest.frame(obj.requestedPath.toString()); digest.count(obj.aliases.size)
                for (alias in obj.aliases) { digest.frame(alias.path.toString()); digest.frame(alias.target.toString()) }
                val artifact = obj.artifact; val evidence = artifact.evidence; val facts = artifact.facts
                digest.frame(artifact.role.name); digest.frame(evidence.role); digest.frame(evidence.path.toString())
                digest.number(evidence.size); digest.frame(evidence.sha256); digest.frame(facts.objectType.name)
                digest.frame(facts.interpreter); digest.count(facts.needed.size); facts.needed.forEach { digest.frame(it) }
                digest.frame(facts.soname); digest.frame(facts.rpath); digest.frame(facts.runpath)
                digest.number(facts.dynamicFlags); digest.number(facts.dynamicFlags1)
                digest.update(if (facts.hasDynamicSegment) 1.toByte() else 0.toByte())
            }
            digest.count(edges.size)
            for (edge in edges) { digest.frame(edge.requester.toString()); digest.count(edge.neededIndex); digest.frame(edge.target.toString()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        private fun MessageDigest.frame(value: String?) {
            if (value == null) update(0.toByte()) else {
                val bytes = encoded(value)
                update(1.toByte()); count(bytes.remaining()); update(bytes)
            }
        }
        private fun encoded(value: String) = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        private fun MessageDigest.count(value: Int) { update(ByteBuffer.allocate(4).putInt(value).array()) }
        private fun MessageDigest.number(value: Long) { update(ByteBuffer.allocate(8).putLong(value).array()) }
        private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
        private class Refused(val reason: ClangRuntimeManifestRefusal) : RuntimeException()
        private fun refuse(reason: ClangRuntimeManifestRefusal): Nothing = throw Refused(reason)
        private val HASH = Regex("[0-9a-f]{64}")
        private const val MAX_OBJECTS = 256
        private const val MAX_EDGES = 4096
        private const val MAX_NEEDED = 512
        private const val MAX_ALIASES = 32
        private const val MAX_STRING_BYTES = 4096
        private const val MAX_OBJECT_BYTES = 512L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        private const val DF_1_PIE = 0x08000000L
    }
}
