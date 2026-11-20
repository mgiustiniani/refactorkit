package org.refactorkit.c

import org.refactorkit.core.OwnedSourceCaptureException
import org.refactorkit.core.OwnedSourceCaptureFailure
import org.refactorkit.core.OwnedSourceInventoryReader
import org.refactorkit.core.SourceCapturePolicy
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

/** Explicit library-client authorization; provenance and license are caller declarations, not verification. */
data class CReadOnlySdkRequest(val root: Path, val provenance: String, val license: String)

/** Exact captured SDK identity. This metadata alone does not assert that the inputs are still current. */
data class CReadOnlySdkProvenance(val root: Path, val provenance: String, val license: String, val identity: String)

/** Immutable retained SDK inputs captured by discovery, separate from executable/runtime evidence. */
class CReadOnlySdkInputs internal constructor(contexts: List<CNativeSysrootContext>) {
    private val contexts = Collections.unmodifiableList(contexts.toList())
    init {
        require(contexts.size <= CNativeSysrootPolicy.MAX_ROOTS && contexts.all { it.sdkRequest != null })
    }
    val requests: List<CReadOnlySdkRequest> = Collections.unmodifiableList(contexts.map { requireNotNull(it.sdkRequest) })
    val provenance: List<CReadOnlySdkProvenance> = Collections.unmodifiableList(contexts.map { requireNotNull(it.sdkProvenance()) })
    val identity: String? = CReadOnlySdkPolicy.sdkBundleIdentity(provenance)
    internal val sources: Map<Path, String> = Collections.unmodifiableMap(contexts.flatMap { it.readOnlySources.entries }
        .associate { it.key to it.value })
    fun isCurrent(): Boolean = contexts.all { it.isCurrent() }
    companion object { val EMPTY = CReadOnlySdkInputs(emptyList()) }
}

internal enum class CReadOnlySdkScope { ADMITTED, INVALID, INPUT_LIMIT }
internal enum class CReadOnlySdkRename { SOURCE_OWNED, READ_ONLY, UNAVAILABLE }

/** Pure scope, dependency closure, identity and write-ownership rules. */
internal object CReadOnlySdkPolicy {
    fun scope(workspace: Path, requests: List<CReadOnlySdkRequest>): CReadOnlySdkScope {
        if (requests.size > CNativeSysrootPolicy.MAX_ROOTS) return CReadOnlySdkScope.INPUT_LIMIT
        if (requests.isNotEmpty() && (!workspace.isAbsolute || workspace != workspace.normalize())) return CReadOnlySdkScope.INVALID
        fun metadata(value: String) = value.isNotBlank() && value.none(Char::isISOControl) &&
            runCatching { utf8(value).size <= 1024 }.getOrDefault(false)
        if (requests.any { r -> !r.root.isAbsolute || r.root != r.root.normalize() || r.root.parent == null ||
                r.root.toString().length > 4096 || r.root.toString().any(Char::isISOControl) || r.root.startsWith(workspace) || workspace.startsWith(r.root) ||
                !metadata(r.provenance) || !metadata(r.license) }) return CReadOnlySdkScope.INVALID
        if (requests.indices.any { a -> requests.indices.any { z -> a != z && requests[a].root.startsWith(requests[z].root) } })
            return CReadOnlySdkScope.INVALID
        return CReadOnlySdkScope.ADMITTED
    }

    fun closedHeaders(headers: Map<Path, String>): Boolean {
        if (headers.isEmpty() || headers.size > CNativeSysrootPolicy.MAX_HEADERS ||
            headers.keys.any { it.isAbsolute || it != it.normalize() || !it.startsWith(Path.of("usr/include")) || !it.toString().endsWith(".h") }) return false
        // Virtual first-party roots drive the existing pure closure reader; no probe is written or executed.
        val probes = headers.keys.sortedBy { it.toString() }.mapIndexed { index, header ->
            Path.of("sdk_probe_$index.c") to "#include \"${header.toString().replace('\\', '/')}\"\n"
        }.toMap()
        return CQuotedIncludeClosure.analyze(headers + probes, probes.keys.associateWith { listOf(Path.of("usr/include")) }) is CQuotedIncludeReadout.Found
    }

    fun identity(request: CReadOnlySdkRequest, entries: List<CNativeSysrootEntry>): String {
        val values = listOf("c-read-only-sdk-v1", request.root.toString(), request.provenance, request.license) +
            entries.sortedBy { it.path.toString() }.flatMap { listOf(it.path.toString(), it.kind.name, it.bytes.toString(), it.sha256) }
        return fingerprint(values)
    }

    fun sdkBundleIdentity(records: List<CReadOnlySdkProvenance>): String? {
        require(records.size <= CNativeSysrootPolicy.MAX_ROOTS)
        if (records.isEmpty()) return null
        return fingerprint(listOf("c-sdk-inputs-v1", records.size.toString()) + records.sortedBy { it.root.toString() }.flatMap {
            listOf(it.root.toString(), it.provenance, it.license, it.identity)
        })
    }

    fun bindToolchain(legacyIdentity: String, sdkIdentity: String?): String =
        if (sdkIdentity == null) legacyIdentity else fingerprint(listOf("c-sdk-bound-toolchain-v1", legacyIdentity, sdkIdentity))

    private fun fingerprint(values: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in values) {
            val bytes = utf8(value)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII)); digest.update(':'.code.toByte()); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun utf8(value: String): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    fun rename(workspace: Path, selected: Path, definition: Path?, requests: List<CReadOnlySdkRequest>): CReadOnlySdkRename {
        fun sdk(path: Path) = requests.any { path.normalize().startsWith(it.root) }
        if (sdk(selected) || (definition != null && sdk(definition))) return CReadOnlySdkRename.READ_ONLY
        if (definition == null || !definition.isAbsolute || !definition.normalize().startsWith(workspace)) return CReadOnlySdkRename.UNAVAILABLE
        return CReadOnlySdkRename.SOURCE_OWNED
    }

    fun edits(workspace: Path, files: List<Path>, requests: List<CReadOnlySdkRequest>): CReadOnlySdkRename {
        if (files.any { file -> requests.any { file.normalize().startsWith(it.root) } }) return CReadOnlySdkRename.READ_ONLY
        if (files.any { !it.isAbsolute || !it.normalize().startsWith(workspace) }) return CReadOnlySdkRename.UNAVAILABLE
        return CReadOnlySdkRename.SOURCE_OWNED
    }
}

internal enum class CNativeSysrootRefusal {
    INVALID_OPTIONS, UNSUPPORTED_SPELLING, MULTIPLE_SYSROOT_FLAGS, UNOWNED_SYSROOT,
    METADATA_MISMATCH, INCONSISTENT_CONTEXTS, UNSUPPORTED_LAYOUT, INPUT_LIMIT, CAPTURE_UNAVAILABLE, SDK_SCOPE_INVALID,
}
internal sealed interface CNativeSysrootAdmission {
    data object ImplicitDefault : CNativeSysrootAdmission
    data class Owned(val root: Path) : CNativeSysrootAdmission
    data class PerTranslationUnit(val roots: List<Path>) : CNativeSysrootAdmission
    data class Refused(val reason: CNativeSysrootRefusal) : CNativeSysrootAdmission
}
internal enum class CNativeSysrootEntryKind { DIRECTORY, HEADER, OTHER }
internal data class CNativeSysrootEntry(
    val path: Path, val kind: CNativeSysrootEntryKind, val bytes: Int = 0, val sha256: String = "",
)
internal sealed interface CNativeSysrootLayout {
    data object Admitted : CNativeSysrootLayout
    data class Refused(val reason: CNativeSysrootRefusal) : CNativeSysrootLayout
}

/** Pure selection and footprint qualification; never opens paths or invents search directories. */
internal object CNativeSysrootPolicy {
    // At most eight bounded captures: no more than 64 MiB of header bytes in total.
    const val MAX_ROOTS = 8
    const val MAX_ENTRIES = 64
    const val MAX_HEADERS = 16
    const val MAX_FILE_BYTES = 1_048_576
    const val MAX_TOTAL_BYTES = 8_388_608
    const val MAX_DEPTH = 16

    fun select(workspace: Path, units: List<CCompilationUnit>, readOnlySdks: List<CReadOnlySdkRequest> = emptyList()): CNativeSysrootAdmission {
        if (units.size > CCompilationDatabasePolicy.DEFAULT_MAX_UNITS) return refused(CNativeSysrootRefusal.INPUT_LIMIT)
        if (!workspace.isAbsolute || workspace != workspace.normalize()) return refused(CNativeSysrootRefusal.UNOWNED_SYSROOT)
        when (CReadOnlySdkPolicy.scope(workspace, readOnlySdks)) {
            CReadOnlySdkScope.ADMITTED -> Unit
            CReadOnlySdkScope.INPUT_LIMIT -> return refused(CNativeSysrootRefusal.INPUT_LIMIT)
            CReadOnlySdkScope.INVALID -> return refused(CNativeSysrootRefusal.SDK_SCOPE_INVALID)
        }
        val selections = mutableSetOf<CNativeSysrootAdmission>()
        val sourceContexts = mutableMapOf<Path, CNativeSysrootAdmission>()
        val parser = CCompilationDatabaseParser()
        for (unit in units) {
            val options = try { parser.optionValues(unit.arguments) } catch (_: RuntimeException) {
                return refused(CNativeSysrootRefusal.INVALID_OPTIONS)
            }
            val roots = options.filter { it.first in setOf("--sysroot", "-isysroot") }
            if (roots.any { it.first == "-isysroot" }) return refused(CNativeSysrootRefusal.UNSUPPORTED_SPELLING)
            if (roots.size > 1) return refused(CNativeSysrootRefusal.MULTIPLE_SYSROOT_FLAGS)
            val value = roots.singleOrNull()?.second
            val selection = if (value == null) {
                if (unit.sysroot != null) return refused(CNativeSysrootRefusal.METADATA_MISMATCH)
                CNativeSysrootAdmission.ImplicitDefault
            } else {
                if (value.isBlank()) return refused(CNativeSysrootRefusal.INVALID_OPTIONS)
                val root = try { unit.directory.resolve(value).normalize() } catch (_: RuntimeException) {
                    return refused(CNativeSysrootRefusal.INVALID_OPTIONS)
                }
                if (!root.isAbsolute || root == workspace || (!root.startsWith(workspace) && readOnlySdks.none { it.root == root })) return refused(CNativeSysrootRefusal.UNOWNED_SYSROOT)
                if (unit.sysroot != root) return refused(CNativeSysrootRefusal.METADATA_MISMATCH)
                CNativeSysrootAdmission.Owned(root)
            }
            val previous = sourceContexts.put(unit.file.normalize(), selection)
            if (previous != null && previous != selection) return refused(CNativeSysrootRefusal.INCONSISTENT_CONTEXTS)
            selections += selection
        }
        val roots = selections.filterIsInstance<CNativeSysrootAdmission.Owned>().map { it.root }.sortedBy { it.toString() }
        if (roots.size > MAX_ROOTS) return refused(CNativeSysrootRefusal.INPUT_LIMIT)
        return if (selections.size <= 1) selections.singleOrNull() ?: CNativeSysrootAdmission.ImplicitDefault
        else CNativeSysrootAdmission.PerTranslationUnit(Collections.unmodifiableList(roots))
    }

    fun layout(entries: List<CNativeSysrootEntry>): CNativeSysrootLayout {
        fun reject(reason: CNativeSysrootRefusal) = CNativeSysrootLayout.Refused(reason)
        val headers = entries.filter { it.kind == CNativeSysrootEntryKind.HEADER }
        if (entries.size > MAX_ENTRIES || headers.size > MAX_HEADERS ||
            entries.any { it.path.nameCount > MAX_DEPTH || it.bytes > MAX_FILE_BYTES } ||
            headers.sumOf { it.bytes.toLong() } > MAX_TOTAL_BYTES) return reject(CNativeSysrootRefusal.INPUT_LIMIT)
        if (entries.map { it.path }.distinct().size != entries.size || headers.isEmpty()) return reject(CNativeSysrootRefusal.UNSUPPORTED_LAYOUT)
        val directories = entries.filter { it.kind == CNativeSysrootEntryKind.DIRECTORY }.map { it.path }.toSet()
        val empty = Path.of(""); val usr = Path.of("usr"); val include = Path.of("usr/include")
        if (!directories.containsAll(listOf(empty, usr, include))) return reject(CNativeSysrootRefusal.UNSUPPORTED_LAYOUT)
        for (entry in entries) {
            val path = entry.path
            if (path.isAbsolute || path != path.normalize() || path.startsWith("..") || entry.bytes < 0) return reject(CNativeSysrootRefusal.UNSUPPORTED_LAYOUT)
            if (path != empty && (path.parent ?: empty) !in directories) return reject(CNativeSysrootRefusal.UNSUPPORTED_LAYOUT)
            when (entry.kind) {
                CNativeSysrootEntryKind.DIRECTORY -> if ((path != empty && path != usr && !path.startsWith(include)) || entry.bytes != 0 || entry.sha256.isNotEmpty()) return reject(CNativeSysrootRefusal.UNSUPPORTED_LAYOUT)
                CNativeSysrootEntryKind.HEADER -> if (!path.startsWith(include) || !path.fileName.toString().endsWith(".h") || !entry.sha256.matches(Regex("[0-9a-f]{64}"))) return reject(CNativeSysrootRefusal.UNSUPPORTED_LAYOUT)
                CNativeSysrootEntryKind.OTHER -> return reject(CNativeSysrootRefusal.UNSUPPORTED_LAYOUT)
            }
        }
        return CNativeSysrootLayout.Admitted
    }
    private fun refused(reason: CNativeSysrootRefusal) = CNativeSysrootAdmission.Refused(reason)
}

internal class CNativeSysrootCaptureException(val reason: CNativeSysrootRefusal) : IllegalArgumentException(reason.name)

private data class CNativeSysrootCapture(val entries: List<CNativeSysrootEntry>, val sources: Map<Path, String>)

/** Owned filesystem adapter. Its immutable footprint includes empty directories and every regular path. */
internal class CNativeSysrootContext private constructor(
    private val workspace: Path, val root: Path, captured: CNativeSysrootCapture,
    val sdkRequest: CReadOnlySdkRequest? = null,
) {
    private val footprint = Collections.unmodifiableList(captured.entries.toList())
    val readOnlySources: Map<Path, String> = Collections.unmodifiableMap(
        if (sdkRequest == null) emptyMap() else LinkedHashMap(captured.sources))
    fun isCurrent(): Boolean = runCatching { read(workspace, root, sdkRequest).entries == footprint }.getOrDefault(false)
    fun sdkProvenance(): CReadOnlySdkProvenance? = sdkRequest?.let {
        CReadOnlySdkProvenance(it.root, it.provenance, it.license, CReadOnlySdkPolicy.identity(it, footprint))
    }

    companion object {
        fun capture(workspace: Path, root: Path): CNativeSysrootContext = CNativeSysrootContext(workspace, root, read(workspace, root))
        fun captureReadOnly(request: CReadOnlySdkRequest): CNativeSysrootContext {
            val anchor = requireNotNull(request.root.parent)
            return CNativeSysrootContext(anchor, request.root, read(anchor, request.root, request), request)
        }
        private fun read(workspace: Path, root: Path, sdk: CReadOnlySdkRequest? = null): CNativeSysrootCapture {
            if (!root.startsWith(workspace) || root == workspace) throw CNativeSysrootCaptureException(CNativeSysrootRefusal.UNOWNED_SYSROOT)
            val relative = workspace.relativize(root)
            val inventory = try {
                OwnedSourceInventoryReader(mapOf("h" to "c"), SourceCapturePolicy(
                    maxFileBytes = CNativeSysrootPolicy.MAX_FILE_BYTES, maxTotalBytes = CNativeSysrootPolicy.MAX_TOTAL_BYTES,
                    maxSources = CNativeSysrootPolicy.MAX_HEADERS, maxEntries = CNativeSysrootPolicy.MAX_ENTRIES,
                    maxDepth = CNativeSysrootPolicy.MAX_DEPTH,
                ), ignoredDirectories = emptySet()).captureSourceRoots(workspace, listOf(relative))
            } catch (failure: OwnedSourceCaptureException) {
                val limit = failure.code in setOf(OwnedSourceCaptureFailure.FILE_BYTES_EXCEEDED, OwnedSourceCaptureFailure.TOTAL_BYTES_EXCEEDED,
                    OwnedSourceCaptureFailure.SOURCES_EXCEEDED, OwnedSourceCaptureFailure.ENTRIES_EXCEEDED, OwnedSourceCaptureFailure.DEPTH_EXCEEDED)
                throw CNativeSysrootCaptureException(if (limit) CNativeSysrootRefusal.INPUT_LIMIT else CNativeSysrootRefusal.CAPTURE_UNAVAILABLE)
            }
            val sources = inventory.sources.associateBy { it.path }
            val entries = inventory.directories.filter { it.startsWith(relative) }.map {
                CNativeSysrootEntry(relative.relativize(it), CNativeSysrootEntryKind.DIRECTORY)
            } + inventory.regularPaths.map { absolute ->
                val path = workspace.relativize(absolute)
                val source = sources[path]
                if (source == null) CNativeSysrootEntry(relative.relativize(path), CNativeSysrootEntryKind.OTHER)
                else {
                    val bytes = source.content.toByteArray(Charsets.UTF_8)
                    CNativeSysrootEntry(relative.relativize(path), CNativeSysrootEntryKind.HEADER, bytes.size,
                        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                }
            }
            when (val result = CNativeSysrootPolicy.layout(entries)) {
                CNativeSysrootLayout.Admitted -> Unit
                is CNativeSysrootLayout.Refused -> throw CNativeSysrootCaptureException(result.reason)
            }
            if (sdk != null && !CReadOnlySdkPolicy.closedHeaders(inventory.sources.associate {
                    relative.relativize(it.path) to it.content
                })) throw CNativeSysrootCaptureException(CNativeSysrootRefusal.UNSUPPORTED_LAYOUT)
            return CNativeSysrootCapture(entries.sortedBy { it.path.toString() },
                inventory.sources.associate { workspace.resolve(it.path).normalize() to it.content })
        }
    }
}
