package org.refactorkit.cli.packagedmavenmodulerename

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.TreeMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.invariantSeparatorsPathString

internal val REPORT_JSON: Json = Json {
    prettyPrint = true
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path, StandardOpenOption.READ).buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal object QualificationOracle {
    private const val RESOURCE =
        "/org/refactorkit/cli/packagedmavenmodulerename/qualification-oracle.json"

    private val document: JsonObject = QualificationOracle::class.java.getResourceAsStream(RESOURCE)
        ?.use { input -> Json.parseToJsonElement(input.readAllBytes().toString(StandardCharsets.UTF_8)).jsonObject }
        ?: error("Missing packaged Maven module-rename qualification oracle: $RESOURCE")

    val resourceBytes: ByteArray = QualificationOracle::class.java.getResourceAsStream(RESOURCE)
        ?.use { it.readAllBytes() }
        ?: error("Missing packaged Maven module-rename qualification oracle bytes")
    val resourceSha256: String = sha256(resourceBytes)

    val requirementId: String = string("requirementId")
    val featureSha256: String = string("featureSha256")
    val baselineSha256: String = string("baselineSha256")
    val approvedChangeSha256: String = string("approvedChangeSha256")
    val version: String = string("version")
    // V070-RELEASE-VERSION-TRANSITION-001: frozen oracle bytes keep their historical version.
    val releaseVersion: String = "0.7.0"
    val cliPreviewStdoutSha256: String = string("cliPreviewStdoutSha256")
    val cliPreviewStdoutBytes: Int = document.getValue("cliPreviewStdoutBytes").jsonPrimitive.int
    val operation: String = string("operation")
    val gate: String = string("gate")

    val request: RenameRequestOracle = document.getValue("request").jsonObject.let { value ->
        RenameRequestOracle(
            oldModuleDir = value.string("oldModuleDir"),
            newModuleDir = value.string("newModuleDir"),
            newArtifactId = value.string("newArtifactId"),
        )
    }

    val launcherEntries: Map<String, LauncherOracle> = document.getValue("launcherEntries").jsonObject
        .mapValues { (_, value) ->
            value.jsonObject.let { entry ->
                LauncherOracle(entry.string("posixMode"), entry.string("sha256"))
            }
        }

    val images: List<ImageOracle> = document.getValue("images").jsonArray.map { value ->
        value.jsonObject.let { image ->
            ImageOracle(
                prePath = image.string("prePath"),
                postPath = image.string("postPath"),
                preBytes = image.getValue("preBytes").jsonPrimitive.int,
                preSha256 = image.string("preSha256"),
                postBytes = image.getValue("postBytes").jsonPrimitive.int,
                postSha256 = image.string("postSha256"),
            )
        }
    }

    val edits: List<EditOracle> = document.getValue("edits").jsonArray.mapIndexed { index, value ->
        value.jsonObject.let { edit ->
            EditOracle(
                order = index + 1,
                kind = edit.string("kind"),
                path = edit.string("path"),
                range = edit.string("range"),
                newText = edit.string("newText"),
                newPath = edit.string("newPath"),
            )
        }
    }

    val affectedPaths: List<String> = strings("affectedPaths")
    val createdDirectories: List<String> = strings("createdDirectories")
    val modules: List<String> = strings("modules")
    val sourceInventory: List<String> = strings("sourceInventory")
    val auxiliaryInventory: List<String> = buildList {
        add("pom.xml")
        modules.forEach { module -> add("$module/pom.xml") }
        add("catalog-acceptance/.refactorkit-expected-source-inventory.properties")
        add("catalog-generated-support/.refactorkit-generated-root-inventory.properties")
    }

    val editTable: List<List<String>> = listOf(
        listOf("order", "FileEdit", "path", "exact source range", "exact new text", "new path"),
    ) + edits.map { edit ->
        listOf(
            edit.order.toString(),
            edit.kind,
            edit.path,
            edit.range,
            edit.newText,
            edit.newPath,
        )
    }

    val imageTable: List<List<String>> = listOf(
        listOf(
            "pre-image path",
            "post-image path",
            "pre-image bytes and SHA-256",
            "post-image bytes and SHA-256",
        ),
    ) + images.map { image ->
        listOf(
            image.prePath,
            image.postPath,
            "${image.preBytes}; ${image.preSha256}",
            "${image.postBytes}; ${image.postSha256}",
        )
    }

    val createdDirectoryTable: List<List<String>> = listOf(
        listOf("order", "normalized created directory path"),
    ) + createdDirectories.mapIndexed { index, path -> listOf((index + 1).toString(), path) }

    private fun string(name: String): String = document.string(name)

    private fun strings(name: String): List<String> = document.getValue(name).jsonArray
        .map { it.jsonPrimitive.content }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
}

internal data class RenameRequestOracle(
    val oldModuleDir: String,
    val newModuleDir: String,
    val newArtifactId: String,
)

internal data class LauncherOracle(
    val posixMode: String,
    val sha256: String,
)

internal data class ImageOracle(
    val prePath: String,
    val postPath: String,
    val preBytes: Int,
    val preSha256: String,
    val postBytes: Int,
    val postSha256: String,
)

internal data class EditOracle(
    val order: Int,
    val kind: String,
    val path: String,
    val range: String,
    val newText: String,
    val newPath: String,
)

internal fun normalizedForwardEditOracle(): JsonObject = buildJsonObject {
    put("edits", buildJsonArray {
        QualificationOracle.edits.forEach { expected ->
            add(buildJsonObject {
                when (expected.kind) {
                    "Modify" -> {
                        put("type", "modify")
                        put("path", expected.path)
                        put("textEdits", buildJsonArray {
                            val range = expected.range.split(':', '-')
                            require(range.size == 4)
                            add(buildJsonObject {
                                put("startLine", range[0].toInt())
                                put("startChar", range[1].toInt())
                                put("endLine", range[2].toInt())
                                put("endChar", range[3].toInt())
                                put("newText", expected.newText)
                            })
                        })
                    }
                    "Rename" -> {
                        put("type", "rename")
                        put("path", expected.path)
                        put("newPath", expected.newPath)
                    }
                    else -> error("Unexpected independent edit kind ${expected.kind}")
                }
            })
        }
    })
}

internal fun canonicalJsonSha256(value: JsonElement): String = sha256(canonicalJsonBytes(value))

internal fun canonicalJsonBytes(value: JsonElement): ByteArray = COMPACT_JSON.encodeToString(
    JsonElement.serializer(),
    normalizeJson(value),
).toByteArray(StandardCharsets.UTF_8)

internal fun diagnosticMultisetSha256(diagnostics: List<String>): String = canonicalJsonSha256(
    buildJsonArray { diagnostics.sorted().forEach { add(JsonPrimitive(it)) } },
)

private val COMPACT_JSON: Json = Json {
    prettyPrint = false
    explicitNulls = true
}

private fun normalizeJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> buildJsonObject {
        value.keys.sorted().forEach { key -> put(key, normalizeJson(value.getValue(key))) }
    }
    is JsonArray -> buildJsonArray { value.forEach { add(normalizeJson(it)) } }
    else -> value
}

internal enum class ManifestPathKind {
    DIRECTORY,
    REGULAR_FILE,
    SYMBOLIC_LINK,
}

internal data class ManifestEntry(
    val kind: ManifestPathKind,
    val byteLength: Long?,
    val mode: String?,
    val sha256: String?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind.name)
        byteLength?.let { put("byteLength", it) }
        mode?.let { put("mode", it) }
        sha256?.let { put("sha256", it) }
    }
}

internal data class TreeManifest(
    val entries: Map<String, ManifestEntry>,
) {
    val sha256: String = run {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.toSortedMap().forEach { (path, entry) ->
            listOf(
                path,
                entry.kind.name,
                entry.byteLength?.toString().orEmpty(),
                entry.mode.orEmpty(),
                entry.sha256.orEmpty(),
            ).forEach { field ->
                digest.update(field.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    val regularFileCount: Int get() = entries.values.count { it.kind == ManifestPathKind.REGULAR_FILE }
    val directoryCount: Int get() = entries.values.count { it.kind == ManifestPathKind.DIRECTORY }

    fun withoutEngine(): TreeManifest = TreeManifest(entries.filterKeys { path ->
        path != ".refactorkit" && !path.startsWith(".refactorkit/")
    })

    fun toJson(subject: String): JsonObject = buildJsonObject {
        put("subject", subject)
        put("sha256", sha256)
        put("entryCount", entries.size)
        put("regularFileCount", regularFileCount)
        put("directoryCount", directoryCount)
        put("entries", buildJsonObject {
            entries.toSortedMap().forEach { (path, entry) -> put(path, entry.toJson()) }
        })
    }
}

internal fun captureTreeManifest(root: Path): TreeManifest {
    require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Manifest root is not a directory" }
    require(!Files.isSymbolicLink(root)) { "Manifest root is a symbolic link" }
    val normalizedRoot = root.toAbsolutePath().normalize()
    val entries = TreeMap<String, ManifestEntry>()
    Files.walkFileTree(
        normalizedRoot,
        setOf(),
        Int.MAX_VALUE,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) {
                    "Symbolic-link directory refused at ${safeRelative(normalizedRoot, dir)}"
                }
                entries[relativeManifestPath(normalizedRoot, dir)] = ManifestEntry(
                    ManifestPathKind.DIRECTORY,
                    null,
                    posixMode(dir),
                    null,
                )
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = relativeManifestPath(normalizedRoot, file)
                if (attrs.isSymbolicLink || Files.isSymbolicLink(file)) {
                    entries[relative] = ManifestEntry(
                        ManifestPathKind.SYMBOLIC_LINK,
                        null,
                        posixMode(file),
                        null,
                    )
                    return FileVisitResult.CONTINUE
                }
                require(attrs.isRegularFile) { "Unsupported no-follow path kind at $relative" }
                entries[relative] = ManifestEntry(
                    ManifestPathKind.REGULAR_FILE,
                    attrs.size(),
                    posixMode(file),
                    sha256(file),
                )
                return FileVisitResult.CONTINUE
            }
        },
    )
    return TreeManifest(entries)
}

internal fun copyTreeNoFollow(source: Path, destination: Path): TreeManifest {
    val normalizedSource = source.toAbsolutePath().normalize()
    val normalizedDestination = destination.toAbsolutePath().normalize()
    require(Files.isDirectory(normalizedSource, LinkOption.NOFOLLOW_LINKS)) { "Fixture source is missing" }
    require(!Files.isSymbolicLink(normalizedSource)) { "Fixture root symbolic link refused" }
    require(Files.notExists(normalizedDestination, LinkOption.NOFOLLOW_LINKS)) { "Copy destination already exists" }
    Files.createDirectories(normalizedDestination)
    Files.walkFileTree(
        normalizedSource,
        setOf(),
        Int.MAX_VALUE,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) {
                    "Fixture symbolic-link directory refused at ${safeRelative(normalizedSource, dir)}"
                }
                val target = normalizedDestination.resolve(normalizedSource.relativize(dir)).normalize()
                require(target.startsWith(normalizedDestination)) { "Fixture copy escaped its destination" }
                Files.createDirectories(target)
                copyPermissions(dir, target)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = safeRelative(normalizedSource, file)
                require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file)) {
                    "Fixture symbolic link refused at $relative"
                }
                require(attrs.isRegularFile) { "Unsupported fixture path kind at $relative" }
                val target = normalizedDestination.resolve(normalizedSource.relativize(file)).normalize()
                require(target.startsWith(normalizedDestination)) { "Fixture file copy escaped its destination" }
                Files.copy(
                    file,
                    target,
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
                copyPermissions(file, target)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                val target = normalizedDestination.resolve(normalizedSource.relativize(dir)).normalize()
                copyPermissions(dir, target)
                runCatching {
                    Files.setLastModifiedTime(
                        target,
                        Files.getLastModifiedTime(dir, LinkOption.NOFOLLOW_LINKS),
                    )
                }
                return FileVisitResult.CONTINUE
            }
        },
    )
    val sourceManifest = captureTreeManifest(normalizedSource)
    val destinationManifest = captureTreeManifest(normalizedDestination)
    require(sourceManifest == destinationManifest) {
        "No-follow fixture copy differs from its permanent source"
    }
    return destinationManifest
}

internal fun deleteTreeNoFollow(root: Path) {
    if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        root,
        setOf(),
        Int.MAX_VALUE,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
    check(Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) { "No-follow cleanup left its root behind" }
}

private fun copyPermissions(source: Path, target: Path) {
    if (Files.getFileAttributeView(source, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null &&
        Files.getFileAttributeView(target, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null
    ) {
        Files.setPosixFilePermissions(
            target,
            Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS),
        )
    }
}

internal fun posixMode(path: Path): String? {
    if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) == null) {
        return null
    }
    return permissionMode(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))
}

internal fun permissionMode(permissions: Set<PosixFilePermission>): String {
    var mode = 0
    if (PosixFilePermission.OWNER_READ in permissions) mode = mode or 0b100_000_000
    if (PosixFilePermission.OWNER_WRITE in permissions) mode = mode or 0b010_000_000
    if (PosixFilePermission.OWNER_EXECUTE in permissions) mode = mode or 0b001_000_000
    if (PosixFilePermission.GROUP_READ in permissions) mode = mode or 0b000_100_000
    if (PosixFilePermission.GROUP_WRITE in permissions) mode = mode or 0b000_010_000
    if (PosixFilePermission.GROUP_EXECUTE in permissions) mode = mode or 0b000_001_000
    if (PosixFilePermission.OTHERS_READ in permissions) mode = mode or 0b000_000_100
    if (PosixFilePermission.OTHERS_WRITE in permissions) mode = mode or 0b000_000_010
    if (PosixFilePermission.OTHERS_EXECUTE in permissions) mode = mode or 0b000_000_001
    return "%04o".format(mode)
}

internal fun modeHasExecute(mode: String): Boolean = mode.toInt(8) and 0b001_001_001 != 0

private fun relativeManifestPath(root: Path, path: Path): String {
    val relative = root.relativize(path)
    return if (relative.nameCount == 0) "." else relative.invariantSeparatorsPathString
}

private fun safeRelative(root: Path, path: Path): String {
    val normalized = path.toAbsolutePath().normalize()
    require(normalized.startsWith(root)) { "Path escaped the admitted root" }
    return relativeManifestPath(root, normalized)
}

internal data class ArchiveEntryEvidence(
    val path: String,
    val regularFile: Boolean,
    val symbolicLink: Boolean,
    val byteLength: Long,
    val mode: String,
    val sha256: String,
)

internal fun inspectArchiveEntry(archive: Path, entryPath: String): ArchiveEntryEvidence {
    require(entryPath.startsWith("refactorkit/") && ".." !in entryPath.split('/')) {
        "Unsafe candidate archive entry request"
    }
    val uri = URI.create("jar:${archive.toUri()}")
    FileSystems.newFileSystem(uri, emptyMap<String, Any>()).use { fileSystem ->
        val path = fileSystem.getPath("/$entryPath")
        val basic = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        @Suppress("UNCHECKED_CAST")
        val permissions = Files.readAttributes(path, "zip:*", LinkOption.NOFOLLOW_LINKS)["permissions"]
            as? Set<PosixFilePermission>
            ?: error("ZIP entry has no POSIX permission evidence: $entryPath")
        return ArchiveEntryEvidence(
            path = entryPath,
            regularFile = basic.isRegularFile,
            symbolicLink = basic.isSymbolicLink,
            byteLength = basic.size(),
            mode = permissionMode(permissions),
            sha256 = sha256(Files.readAllBytes(path)),
        )
    }
}

internal data class WorkspaceOracleState(
    val s0Manifest: TreeManifest,
    val c1Manifest: TreeManifest,
    val s0Bytes: Map<String, ByteArray>,
    val c1Bytes: Map<String, ByteArray>,
    val sourceInventory: Set<String>,
    val auxiliaryInventory: Set<String>,
    val d0: List<String>,
) {
    val s0Identity: String get() = s0Manifest.sha256
    val s1Identity: String get() = c1Manifest.sha256
}

internal fun retainWorkspaceOracles(root: Path): WorkspaceOracleState {
    val s0Manifest = captureTreeManifest(root).withoutEngine()
    require(s0Manifest.entries.values.none { it.kind == ManifestPathKind.SYMBOLIC_LINK }) {
        "Disposable workspace contains a symbolic link"
    }
    val s0Bytes = readRegularBytes(root, s0Manifest)
    val sourceInventory = QualificationOracle.sourceInventory.toSet()
    val auxiliaryInventory = QualificationOracle.auxiliaryInventory.toSet()
    require(sourceInventory.size == 7 && auxiliaryInventory.size == 23)
    (sourceInventory + auxiliaryInventory).forEach { path ->
        require(path in s0Bytes) { "Retained scanner inventory path is missing: $path" }
    }

    val c1Bytes = LinkedHashMap(s0Bytes.mapValues { (_, bytes) -> bytes.copyOf() })
    QualificationOracle.edits.filter { it.kind == "Modify" }.forEach { edit ->
        val original = requireNotNull(c1Bytes[edit.path]) { "Missing edit pre-image ${edit.path}" }
        c1Bytes[edit.path] = replaceSourceRange(original, edit.range, edit.newText)
    }
    QualificationOracle.edits.filter { it.kind == "Rename" }.forEach { edit ->
        val moved = requireNotNull(c1Bytes.remove(edit.path)) { "Missing rename pre-image ${edit.path}" }
        require(c1Bytes.put(edit.newPath, moved) == null) { "Rename destination already existed: ${edit.newPath}" }
    }

    val c1Entries = TreeMap(s0Manifest.entries)
    QualificationOracle.edits.filter { it.kind == "Modify" }.forEach { edit ->
        val before = requireNotNull(c1Entries[edit.path])
        val bytes = requireNotNull(c1Bytes[if (edit.path == "catalog-model/pom.xml") "catalog-domain/pom.xml" else edit.path])
        if (edit.path != "catalog-model/pom.xml") {
            c1Entries[edit.path] = before.copy(byteLength = bytes.size.toLong(), sha256 = sha256(bytes))
        }
    }
    QualificationOracle.edits.filter { it.kind == "Rename" }.forEach { edit ->
        val before = requireNotNull(c1Entries.remove(edit.path)) { "Missing manifest rename source ${edit.path}" }
        val bytes = requireNotNull(c1Bytes[edit.newPath])
        c1Entries[edit.newPath] = before.copy(byteLength = bytes.size.toLong(), sha256 = sha256(bytes))
    }
    QualificationOracle.createdDirectories.forEach { directory ->
        c1Entries.putIfAbsent(
            directory,
            ManifestEntry(
                ManifestPathKind.DIRECTORY,
                null,
                directoryModeFromOldHierarchy(s0Manifest, directory),
                null,
            ),
        )
    }
    val c1Manifest = TreeManifest(c1Entries)

    QualificationOracle.images.forEach { image ->
        val pre = requireNotNull(s0Bytes[image.prePath])
        val post = requireNotNull(c1Bytes[image.postPath])
        require(pre.size == image.preBytes && sha256(pre) == image.preSha256) {
            "Independent pre-image oracle mismatch for ${image.prePath}"
        }
        require(post.size == image.postBytes && sha256(post) == image.postSha256) {
            "Independent post-image oracle mismatch for ${image.postPath}"
        }
    }

    return WorkspaceOracleState(
        s0Manifest = s0Manifest,
        c1Manifest = c1Manifest,
        s0Bytes = s0Bytes,
        c1Bytes = c1Bytes,
        sourceInventory = sourceInventory,
        auxiliaryInventory = auxiliaryInventory,
        d0 = emptyList(),
    )
}

private fun readRegularBytes(root: Path, manifest: TreeManifest): Map<String, ByteArray> = manifest.entries
    .filterValues { it.kind == ManifestPathKind.REGULAR_FILE }
    .mapValues { (path, _) -> Files.readAllBytes(root.resolve(path)) }

private fun directoryModeFromOldHierarchy(s0: TreeManifest, newDirectory: String): String? {
    val oldDirectory = newDirectory.replaceFirst("catalog-domain", "catalog-model")
    return s0.entries[oldDirectory]?.mode ?: s0.entries["catalog-model"]?.mode
}

private fun replaceSourceRange(original: ByteArray, rawRange: String, newText: String): ByteArray {
    val match = Regex("(\\d+):(\\d+)-(\\d+):(\\d+)").matchEntire(rawRange)
        ?: error("Invalid source range: $rawRange")
    val (startLine, startCharacter, endLine, endCharacter) = match.destructured.toList().map(String::toInt)
    val text = original.toString(StandardCharsets.UTF_8)
    val start = characterOffset(text, startLine, startCharacter)
    val end = characterOffset(text, endLine, endCharacter)
    require(start <= end)
    require(text.substring(start, end) == "catalog-model") {
        "Source range $rawRange did not select the independently expected literal"
    }
    val replaced = text.substring(0, start) + newText + text.substring(end)
    return replaced.toByteArray(StandardCharsets.UTF_8)
}

private fun characterOffset(text: String, line: Int, character: Int): Int {
    var offset = 0
    repeat(line) {
        val newline = text.indexOf('\n', offset)
        require(newline >= 0) { "Source range line exceeds file" }
        offset = newline + 1
    }
    val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
    require(offset + character <= lineEnd) { "Source range character exceeds line" }
    return offset + character
}

internal data class ReactorFacts(
    val directModules: List<String>,
    val packagingByModule: Map<String, String>,
    val artifactByModule: Map<String, String>,
    val pricingDependency: Triple<String, String, String>,
)

internal fun inspectReactor(rootBytes: Map<String, ByteArray>, staged: Boolean): ReactorFacts {
    val root = parseXml(requireNotNull(rootBytes["pom.xml"]))
    val modules = directChildren(directChild(root, "modules"), "module").map { it.textContent.trim() }
    val expected = QualificationOracle.modules.toMutableList().also { values ->
        if (staged) values[values.indexOf("catalog-model")] = "catalog-domain"
    }
    require(modules == expected) { "Root direct-module order differs from the independent reactor oracle" }
    require(modules.size == 20 && modules.toSet().size == 20)

    val packaging = linkedMapOf<String, String>()
    val artifacts = linkedMapOf<String, String>()
    modules.forEach { module ->
        val pom = parseXml(requireNotNull(rootBytes["$module/pom.xml"]) { "Missing child POM for $module" })
        require(directChildren(pom, "modules").isEmpty()) { "Direct child $module is an aggregator" }
        packaging[module] = directChildOrNull(pom, "packaging")?.textContent?.trim().orEmpty().ifBlank { "jar" }
        artifacts[module] = directChild(pom, "artifactId").textContent.trim()
        require(packaging.getValue(module) == "jar") { "Direct child $module is not JAR packaging" }
    }

    val pricing = parseXml(requireNotNull(rootBytes["catalog-pricing/pom.xml"]))
    val dependencies = directChild(pricing, "dependencies")
    val selectedArtifact = if (staged) "catalog-domain" else "catalog-model"
    val dependency = directChildren(dependencies, "dependency").single { element ->
        directChild(element, "artifactId").textContent.trim() == selectedArtifact
    }
    val coordinate = Triple(
        directChild(dependency, "groupId").textContent.trim(),
        directChild(dependency, "artifactId").textContent.trim(),
        directChild(dependency, "version").textContent.trim(),
    )
    require(coordinate == Triple("com.acme.refactorkit.fixture", selectedArtifact, "${'$'}{project.version}"))
    require(artifacts.getValue(selectedArtifact) == selectedArtifact)
    return ReactorFacts(modules, packaging, artifacts, coordinate)
}

private fun parseXml(bytes: ByteArray): Element {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        setExpandEntityReferences(false)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val builder = factory.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> throw IllegalStateException("External XML entity resolution is denied") }
    }
    return builder.parse(ByteArrayInputStream(bytes)).documentElement
}

private fun directChildren(parent: Element, localName: String): List<Element> = buildList {
    val children = parent.childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child is Element && (child.localName ?: child.tagName) == localName) add(child)
    }
}

private fun directChild(parent: Element, localName: String): Element =
    directChildren(parent, localName).single()

private fun directChildOrNull(parent: Element, localName: String): Element? =
    directChildren(parent, localName).singleOrNull()

internal fun writeJson(path: Path, value: JsonElement) {
    Files.createDirectories(path.parent)
    val bytes = (REPORT_JSON.encodeToString(value) + "\n").toByteArray(StandardCharsets.UTF_8)
    Files.write(
        path,
        bytes,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )
}

internal fun jsonStrings(values: Iterable<String>): JsonArray = buildJsonArray {
    values.forEach { add(JsonPrimitive(it)) }
}

internal fun readJsonObject(path: Path): JsonObject =
    Json.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8)).jsonObject

internal fun permissionNames(mode: String): Set<String> = runCatching {
    PosixFilePermissions.fromString(
        buildString {
            val value = mode.toInt(8)
            val symbols = listOf(
                0b100_000_000 to 'r', 0b010_000_000 to 'w', 0b001_000_000 to 'x',
                0b000_100_000 to 'r', 0b000_010_000 to 'w', 0b000_001_000 to 'x',
                0b000_000_100 to 'r', 0b000_000_010 to 'w', 0b000_000_001 to 'x',
            )
            symbols.forEach { (bit, symbol) -> append(if (value and bit != 0) symbol else '-') }
        },
    ).mapTo(linkedSetOf()) { it.name }
}.getOrElse { emptySet() }
