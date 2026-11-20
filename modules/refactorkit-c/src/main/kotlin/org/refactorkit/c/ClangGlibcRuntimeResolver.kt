package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import java.util.Collections

enum class ClangGlibcSearchKind { DIRECTORY_ABSENT, HARDWARE_ABSENT, CANDIDATE_ABSENT, RUNPATH, CACHE, LOADED }
data class ClangGlibcSearchObservation(val requester: Path, val neededIndex: Int, val kind: ClangGlibcSearchKind, val path: Path)

sealed interface ClangGlibcRuntimeResolution {
    class Captured internal constructor(val manifest: ClangRuntimeManifest,
        val inputs: ClangGlibcRuntimeInputCaptureResult.Captured, observations: List<ClangGlibcSearchObservation>) : ClangGlibcRuntimeResolution {
        val observations: List<ClangGlibcSearchObservation> = Collections.unmodifiableList(ArrayList(observations))
    }
    data class Unavailable(val diagnostic: Diagnostic, val inputDiagnostic: Diagnostic? = null) : ClangGlibcRuntimeResolution
}

sealed interface ClangGlibcRuntimeCurrency {
    data object Current : ClangGlibcRuntimeCurrency
    data class NotCurrent(val diagnostic: Diagnostic, val inputDiagnostic: Diagnostic? = null) : ClangGlibcRuntimeCurrency
}

/** Static read-only resolution in the declared empty-environment profile; never execution admission. */
class ClangGlibcRuntimeResolver {
    private val runtimeInputs = ClangGlibcRuntimeInputCapture()

    fun capture(executable: Path, role: ClangArtifactRole, cacheFile: Path = ClangGlibcRuntimeInputCapture.SYSTEM_CACHE,
        preloadFile: Path = ClangGlibcRuntimeInputCapture.SYSTEM_PRELOAD): ClangGlibcRuntimeResolution = try {
        val inputs = when (val read = runtimeInputs.capture(cacheFile, preloadFile)) {
            is ClangGlibcRuntimeInputCaptureResult.Captured -> read
            is ClangGlibcRuntimeInputCaptureResult.Unavailable -> refuse("inputUnavailable", "Runtime data could not be captured", read.diagnostic)
        }
        var remaining = MAX_TOTAL_BYTES
        fun read(path: Path, artifactRole: ClangArtifactRole): ClangNativePathCaptureResult.Captured {
            if (remaining < 64) refuse("inputLimit", "Runtime acquisition exhausted its byte budget")
            return when (val result = ClangNativePathCapture(minOf(MAX_OBJECT_BYTES, remaining).toInt()).capture(path, artifactRole)) {
                is ClangNativePathCaptureResult.Captured -> result.also { remaining -= it.artifact.evidence.size }
                is ClangNativePathCaptureResult.Unavailable -> refuse("artifactUnavailable", "Selected runtime object could not be captured", result.diagnostic)
            }
        }
        val root = read(executable, role)
        val interpreterPath = root.artifact.facts.interpreter?.let(Path::of)
            ?: refuse("unsupportedSearch", "Selected executable has no declared interpreter")
        val interpreter = read(interpreterPath, ClangArtifactRole.INTERPRETER)
        val objects = linkedMapOf(root.artifact.evidence.path to root, interpreter.artifact.evidence.path to interpreter)
        if (objects.size != 2) refuse("ambiguousGraph", "Executable and interpreter must be distinct objects")
        val loaded = mutableMapOf<String, Path>()
        fun register(obj: ClangNativePathCaptureResult.Captured, selectedName: String? = null) {
            val physical = obj.artifact.evidence.path
            for (name in listOfNotNull(selectedName, obj.artifact.facts.soname, obj.requestedPath.fileName?.toString()).distinct()) {
                val earlier = loaded.putIfAbsent(name, physical)
                if (earlier != null && earlier != physical) refuse("ambiguousGraph", "Runtime object names conflict")
            }
        }
        register(root); register(interpreter)
        val pending = ArrayDeque<ClangNativePathCaptureResult.Captured>(); pending.add(root); pending.add(interpreter)
        val bindings = mutableListOf<ClangRuntimeDependencyBinding>()
        val observations = mutableListOf<ClangGlibcSearchObservation>()
        fun observe(requester: Path, index: Int, kind: ClangGlibcSearchKind, path: Path) {
            if (observations.size >= MAX_OBSERVATIONS) refuse("inputLimit", "Runtime search exceeded its observation budget")
            observations += ClangGlibcSearchObservation(requester, index, kind, path)
        }
        while (pending.isNotEmpty()) {
            val requester = pending.removeFirst()
            for (index in requester.artifact.facts.needed.indices) {
                if (bindings.size >= MAX_EDGES) refuse("inputLimit", "Runtime dependency graph exceeded its edge budget")
                val search = when (val result = CGlibcDependencySearch.propose(requester, index, inputs.cacheFacts)) {
                    is CGlibcDependencySearchResult.Proposed -> result.search
                    is CGlibcDependencySearchResult.Refused -> refuse("search_${result.reason.name.lowercase()}", "Runtime search metadata was refused")
                }
                val existing = loaded[search.neededName]
                val target = if (existing != null) {
                    observe(search.requester, index, ClangGlibcSearchKind.LOADED, existing); existing
                } else {
                    var selected: Path? = null
                    for (candidate in search.runpathCandidates) {
                        when (val directory = directories(candidate.parent)) {
                            is Directory.Missing -> observe(search.requester, index, ClangGlibcSearchKind.DIRECTORY_ABSENT, directory.path)
                            is Directory.Present -> {
                                val hardware = directory.path.resolve("glibc-hwcaps")
                                if (node(hardware) != null) refuse("unsupportedHardwareSearch", "Hardware-specific RUNPATH directories require an additional profile")
                                observe(search.requester, index, ClangGlibcSearchKind.HARDWARE_ABSENT, hardware)
                                if (node(directory.path.resolve(search.neededName)) == null) {
                                    observe(search.requester, index, ClangGlibcSearchKind.CANDIDATE_ABSENT, directory.path.resolve(search.neededName))
                                } else {
                                    selected = candidate
                                    observe(search.requester, index, ClangGlibcSearchKind.RUNPATH, candidate)
                                }
                                verify(directory.keys)
                            }
                        }
                        if (selected != null) break
                    }
                    if (selected == null) {
                        selected = when (val fallback = search.cacheFallback) {
                            is CGlibcCacheLookup.Candidate -> Path.of(fallback.path)
                            CGlibcCacheLookup.Absent -> refuse("defaultSearchUnavailable", "Cache has no native candidate; default search authority is unavailable")
                            is CGlibcCacheLookup.Ambiguous -> refuse("ambiguousCache", "Cache candidates are ambiguous")
                            CGlibcCacheLookup.UnsupportedSelection -> refuse("unsupportedCache", "Cache requires unsupported native selection")
                            CGlibcCacheLookup.InvalidName -> refuse("invalidName", "Cache dependency name is invalid")
                        }
                        observe(search.requester, index, ClangGlibcSearchKind.CACHE, selected)
                    }
                    if (objects.size >= MAX_OBJECTS) refuse("inputLimit", "Runtime graph exceeded its object budget")
                    val captured = read(selected, ClangArtifactRole.SHARED_LIBRARY)
                    val physical = captured.artifact.evidence.path
                    val earlier = objects[physical]
                    if (earlier == null) { objects[physical] = captured; pending.add(captured) }
                    else if (earlier.artifact.facts != captured.artifact.facts) refuse("changed", "A repeated runtime object changed during acquisition")
                    register(earlier ?: captured, search.neededName)
                    physical
                }
                bindings += ClangRuntimeDependencyBinding(search.requester, index, target)
            }
        }
        val libraries = objects.values.filter { it !== root && it !== interpreter }
        val manifest = when (val result = ClangRuntimeManifest.compose(root, interpreter, libraries, bindings)) {
            is ClangRuntimeManifestComposition.Composed -> result.manifest
            is ClangRuntimeManifestComposition.Refused -> refuse("graph_${result.reason.name.lowercase()}", "Resolved runtime graph is incoherent")
        }
        remaining = MAX_TOTAL_BYTES
        for (obj in manifest.objects) {
            if (read(obj.requestedPath, obj.artifact.role) != obj) refuse("changed", "Selected runtime object changed during resolution")
        }
        for (observation in observations) if (observation.kind in ABSENCES) verifyAbsence(observation.path)
        when (val current = runtimeInputs.checkCurrent(inputs)) {
            ClangGlibcRuntimeInputCurrency.Current -> Unit
            is ClangGlibcRuntimeInputCurrency.NotCurrent -> refuse("changed", "Runtime data changed during resolution", current.diagnostic)
        }
        ClangGlibcRuntimeResolution.Captured(manifest, inputs, observations)
    } catch (failure: Refused) {
        ClangGlibcRuntimeResolution.Unavailable(failure.diagnostic, failure.inputDiagnostic)
    } catch (_: IOException) {
        unavailable("unavailable", "Runtime search input could not be inspected")
    } catch (_: SecurityException) {
        unavailable("unavailable", "Runtime search access was refused")
    } catch (_: UnsupportedOperationException) {
        unavailable("unavailable", "Runtime search requires unsupported filesystem operations")
    } catch (_: IllegalArgumentException) {
        unavailable("invalidPath", "Runtime search path is outside the supported representation")
    }

    fun checkCurrent(captured: ClangGlibcRuntimeResolution.Captured): ClangGlibcRuntimeCurrency =
        when (val fresh = capture(captured.manifest.executable.requestedPath, captured.manifest.executable.artifact.role,
            captured.inputs.cacheEvidence.path, captured.inputs.preloadPath)) {
            is ClangGlibcRuntimeResolution.Unavailable -> ClangGlibcRuntimeCurrency.NotCurrent(fresh.diagnostic, fresh.inputDiagnostic)
            is ClangGlibcRuntimeResolution.Captured -> if (fresh.manifest.identity == captured.manifest.identity &&
                fresh.inputs.cacheEvidence == captured.inputs.cacheEvidence && fresh.inputs.preloadPath == captured.inputs.preloadPath &&
                fresh.observations == captured.observations) ClangGlibcRuntimeCurrency.Current
            else ClangGlibcRuntimeCurrency.NotCurrent(diagnostic("changed", "Runtime resolution differs from its original capture"))
        }

    private sealed interface Directory {
        data class Present(val path: Path, val keys: List<Pair<Path, Any>>) : Directory
        data class Missing(val path: Path) : Directory
    }
    private fun directories(raw: Path): Directory {
        if (!raw.isAbsolute || raw.nameCount > 256) refuse("inputLimit", "Search directory representation exceeds its profile")
        val keys = mutableListOf<Pair<Path, Any>>()
        var current = raw.root
        fun direct(path: Path): Boolean {
            val attrs = node(path) ?: return false
            if (!attrs.isDirectory || attrs.isSymbolicLink) refuse("unsupportedSearch", "Search directory ancestors must be direct directories")
            keys += path to (attrs.fileKey() ?: refuse("unavailable", "Search directory identity is unavailable"))
            return true
        }
        if (!direct(current)) refuse("unavailable", "Search root directory is unavailable")
        for (part in raw) {
            if (part.toString() == ".") continue
            if (part.toString() == "..") current = current.parent ?: current.root
            else current = current.resolve(part)
            if (!direct(current)) { verify(keys); return Directory.Missing(current) }
        }
        verify(keys); return Directory.Present(current, keys.toList())
    }
    private fun verify(keys: List<Pair<Path, Any>>) {
        for ((path, key) in keys) {
            val attrs = node(path)
            if (attrs == null || !attrs.isDirectory || attrs.isSymbolicLink || attrs.fileKey() != key) {
                refuse("changed", "Search directory changed during inspection")
            }
        }
    }
    private fun verifyAbsence(path: Path) {
        val parent = directories(path.parent)
        if (parent !is Directory.Present || node(path) != null) refuse("changed", "An unsuccessful runtime search input changed")
        verify(parent.keys)
    }
    private fun node(path: Path): BasicFileAttributes? = try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
        catch (_: NoSuchFileException) { null }
    private class Refused(val diagnostic: Diagnostic, val inputDiagnostic: Diagnostic?) : RuntimeException()
    private fun refuse(code: String, message: String, input: Diagnostic? = null): Nothing = throw Refused(diagnostic(code, message), input)
    private fun unavailable(code: String, message: String) = ClangGlibcRuntimeResolution.Unavailable(diagnostic(code, message))
    private fun diagnostic(code: String, message: String) = Diagnostic(message, Diagnostic.Severity.ERROR,
        code = "clang.glibcResolution.$code", evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.SAFETY)

    companion object {
        private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_OBJECT_BYTES = 512L * 1024 * 1024
        private const val MAX_OBJECTS = 256
        private const val MAX_EDGES = 4096
        private const val MAX_OBSERVATIONS = 8192
        private val ABSENCES = setOf(ClangGlibcSearchKind.DIRECTORY_ABSENT, ClangGlibcSearchKind.HARDWARE_ABSENT, ClangGlibcSearchKind.CANDIDATE_ABSENT)
    }
}
