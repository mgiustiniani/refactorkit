package org.refactorkit.java

import kotlinx.serialization.json.*
import org.refactorkit.core.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** SPI adapter; explicit declarations never upgrade conventional-layout guesses. */
internal class DeclaredJavaProjectProvider : BuildModelProvider {
    override val id: String = ID
    override fun discover(request: BuildModelRequest): BuildModel =
        captureIfDeclared(request.workspaceRoot, true)?.buildModels?.single()
            ?: BuildModel(ID, BuildModelStatus.UNAVAILABLE, emptyList())

    fun captureIfDeclared(root: Path, includeBuildModels: Boolean): ProjectSnapshot? =
        DeclaredJavaProjectCapture.read(root.toAbsolutePath().normalize(), includeBuildModels)

    companion object {
        const val ID = "java-declared-v1"
        val DESCRIPTOR: Path = Path.of(".refactorkit/java-project.json")
    }
}

private class DeclaredJavaFailure(val code: String, message: String) : IOException(message)

/** Application service: decode explicit intent, capture inputs, project the shared Build Model. */
private object DeclaredJavaProjectCapture {
    private val descriptor = DeclaredJavaProjectProvider.DESCRIPTOR
    private const val provider = DeclaredJavaProjectProvider.ID

    fun read(root: Path, includeBuildModels: Boolean): ProjectSnapshot? {
        val input = OwnedSourceInventoryReader.readWorkspaceTextInput(root, descriptor, 262_144)
        if (input is OwnedWorkspaceTextReadout.Absent) return null
        val text = (input as OwnedWorkspaceTextReadout.Captured).content
        val auxiliary = listOf(SourceFile(descriptor, text, "java-declared-project"))
        try {
            DeclaredJavaJsonShape.check(text)
            val declaration = objectValue(Json.parseToJsonElement(text), setOf("schemaVersion", "sdkHome", "modules"))
            require(string(declaration, "schemaVersion") == "java-declared-project-v1")
            val sdk = Path.of(string(declaration, "sdkHome"))
            require(sdk.isAbsolute && Files.isDirectory(sdk))
            val compilerHome = sdk.toRealPath().toString()
            val moduleRows = array(declaration, "modules", 64)
            require(moduleRows.isNotEmpty())
            val modules = moduleRows.map { row ->
                val module = objectValue(row, setOf("id", "root", "sourceSets"))
                val id = string(module, "id", 128)
                val moduleRoot = relative(string(module, "root"))
                val sets = array(module, "sourceSets", 64).map { value ->
                    val set = objectValue(value, setOf("id", "kind", "sourceRoots", "generatedSourceRoots", "outputDirectories", "compileClasspath", "runtimeClasspath", "release", "proc"))
                    val kind = when (string(set, "kind")) {
                        "main" -> SourceSetKind.MAIN
                        "test" -> SourceSetKind.TEST
                        "integration-test" -> SourceSetKind.INTEGRATION_TEST
                        "custom" -> SourceSetKind.CUSTOM
                        else -> throw IllegalArgumentException("Unsupported declared source-set kind")
                    }
                    val release = set["release"] as? JsonPrimitive
                    require(release != null && !release.isString && release.intOrNull != null && release.int in 8..27)
                    require(string(set, "proc") == "none")
                    fun paths(name: String, source: Boolean) = array(set, name, 256).map { item ->
                        val raw = item as? JsonPrimitive
                        require(raw != null && raw.isString)
                        val path = if (source) relative(raw.content) else classpath(raw.content)
                        if (source) require(root.resolve(path).normalize().startsWith(root.resolve(moduleRoot).normalize()))
                        path
                    }.also { require(it.distinct().size == it.size) }
                    val roots = paths("sourceRoots", true); require(roots.isNotEmpty())
                    val generated = paths("generatedSourceRoots", true); require(generated.all { it in roots })
                    val outputs = paths("outputDirectories", true); require(outputs.none { it.toString().isEmpty() })
                    val attributes = mapOf("java.buildSystem" to "declared", "java.buildModel.status" to "available",
                        "java.sourceLevel" to release.int.toString(), "java.release" to release.int.toString(),
                        "java.compilerHome" to compilerHome, "java.platformSelection" to "declared-release")
                    BuildSourceSet(string(set, "id", 128), kind, sourceRoots = roots, generatedSourceRoots = generated,
                        outputDirectories = outputs, classpathEntries = paths("compileClasspath", false),
                        runtimeClasspathEntries = paths("runtimeClasspath", false), attributes = attributes,
                        sourceRootEvidence = roots.map { path -> BuildSourceRootEvidence(path,
                            if (path in generated) BuildSourceRootOrigin.GENERATED else BuildSourceRootOrigin.BUILD_DESCRIPTOR,
                            provider, descriptorPath = descriptor) },
                        compilerOptions = listOf(BuildCompilerOption("release", release.int.toString(), "java-declared-project-v1"),
                            BuildCompilerOption("proc", "none", "java-declared-project-v1")),
                        compilerOptionsAvailability = BuildFactAvailability.COMPLETE,
                        resolvedArtifactAvailability = BuildFactAvailability.NOT_DECLARED,
                        annotationProcessors = BuildAnnotationProcessorConfiguration(BuildFactAvailability.COMPLETE, mode = "none"))
                }
                require(sets.isNotEmpty())
                BuildModule(id, id, root.resolve(moduleRoot), sets, attributes = mapOf("java.buildSystem" to "declared"))
            }
            require(modules.map { it.id }.distinct().size == modules.size)
            require(modules.sumOf { it.sourceSets.size } <= 256)
            val allOutputs = modules.flatMap { it.sourceSets }.flatMap { it.outputDirectories }.map { root.resolve(it).normalize() }
            require(allOutputs.distinct().size == allOutputs.size)
            val rootOwners = modules.flatMap { module -> module.sourceSets.flatMap { set -> set.sourceRoots.map { Triple(module.id, set.id, it) } } }
            require(rootOwners.size <= 1_024)
            for ((index, owner) in rootOwners.withIndex()) for (other in rootOwners.drop(index + 1)) {
                if (owner.first != other.first || owner.second != other.second)
                    require(!contains(owner.third, other.third) && !contains(other.third, owner.third))
            }
            val moduleDirectories = (listOf(root) + modules.map { it.root }).distinct()
            if (moduleDirectories.any { directory -> listOf("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
                    .any { Files.exists(directory.resolve(it), LinkOption.NOFOLLOW_LINKS) } })
                throw DeclaredJavaFailure("java.context.declaredProjectConflict", "An explicit plain-Java model cannot replace a Maven or Gradle descriptor")
            val inventory = OwnedSourceInventoryReader(mapOf("java" to "java"), ignoredDirectories = emptySet(), allowMissingRoot = true)
                .captureSourceRoots(root, rootOwners.map { it.third }.distinct())
            if (inventory.regularPaths.any { it.fileName.toString() in setOf("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") })
                throw DeclaredJavaFailure("java.context.declaredProjectConflict", "A selected source inventory contains a Maven or Gradle descriptor")
            val declaredOutputs = modules.flatMap { it.sourceSets }.flatMap { it.outputDirectories }.map { root.resolve(it).normalize() }.toSet()
            val inputCapture = DeclaredJavaClasspathCapture()
            val evidence = modules.flatMap { it.sourceSets }.flatMap { it.classpathEntries + it.runtimeClasspathEntries + it.outputDirectories }
                .distinct().map { path -> inputCapture.capture(root, path, root.resolve(path).normalize() in declaredOutputs) } +
                ClasspathEvidence.capture(root, descriptor, ClasspathEvidenceKind.DECLARATION_FILE)
            require(OwnedSourceInventoryReader.readWorkspaceTextInput(root, descriptor, 262_144) == input)
            val legacy = modules.map { module ->
                val main = module.sourceSets.filter { it.kind == SourceSetKind.MAIN }
                val test = module.sourceSets.filter { it.kind in setOf(SourceSetKind.TEST, SourceSetKind.INTEGRATION_TEST) }
                org.refactorkit.core.Module(module.id, module.root, sourceRoots = module.sourceSets.flatMap { it.sourceRoots }.distinct(),
                    classpathEntries = module.sourceSets.flatMap { it.classpathEntries }.distinct(),
                    languageSettings = main.firstOrNull()?.attributes ?: module.sourceSets.first().attributes,
                    mainSourceRoots = main.flatMap { it.sourceRoots }, testSourceRoots = test.flatMap { it.sourceRoots },
                    generatedSourceRoots = main.flatMap { it.generatedSourceRoots }, generatedTestSourceRoots = test.flatMap { it.generatedSourceRoots },
                    mainClasspathEntries = main.flatMap { it.classpathEntries }, mainRuntimeClasspathEntries = main.flatMap { it.runtimeClasspathEntries },
                    testClasspathEntries = test.flatMap { it.classpathEntries }, mainOutputDirectories = main.flatMap { it.outputDirectories },
                    testOutputDirectories = test.flatMap { it.outputDirectories })
            }
            val model = BuildModel(provider, BuildModelStatus.AVAILABLE, modules, attributes = mapOf(
                "ecosystem" to "java", "strategy" to "explicit-declaration", "buildCodeExecution" to "denied",
                "credentialsAccess" to "denied", "networkAccess" to "denied", "java.declared.workspaceRoot" to root.toString()))
            return ProjectSnapshot(Workspace(root), legacy, inventory.sources, classpathEvidence = evidence,
                buildModels = if (includeBuildModels) listOf(model) else emptyList(), auxiliaryFiles = auxiliary)
        } catch (failure: OwnedSourceCaptureException) {
            throw failure
        } catch (failure: Exception) {
            val code = (failure as? DeclaredJavaFailure)?.code ?: "java.context.declaredProjectInvalid"
            val model = BuildModel(provider, BuildModelStatus.UNAVAILABLE, emptyList(),
                diagnostics = listOf(BuildModelDiagnostic(code, "Declared Java project inputs are invalid, unavailable or ambiguous", severity = Diagnostic.Severity.ERROR)))
            return ProjectSnapshot(Workspace(root), emptyList(), emptyList(),
                buildModels = if (includeBuildModels) listOf(model) else emptyList(), auxiliaryFiles = auxiliary)
        }
    }

    private fun objectValue(value: JsonElement, keys: Set<String>): JsonObject {
        val objectValue = value as? JsonObject ?: throw IllegalArgumentException("Object required")
        require(objectValue.keys == keys); return objectValue
    }
    private fun array(value: JsonObject, key: String, max: Int): JsonArray =
        requireNotNull(value[key] as? JsonArray).also { require(it.size <= max) }
    private fun string(value: JsonObject, key: String, max: Int = 4_096): String {
        val primitive = value[key] as? JsonPrimitive
        require(primitive != null && primitive.isString && primitive.content.isNotBlank() && '\u0000' !in primitive.content && primitive.content.toByteArray(Charsets.UTF_8).size <= max)
        return primitive.content
    }
    private fun relative(raw: String): Path {
        require(raw.isNotBlank() && raw.toByteArray(Charsets.UTF_8).size <= 4_096 && '\\' !in raw && '\u0000' !in raw)
        val path = Path.of(raw); require(!path.isAbsolute && !path.normalize().startsWith("..") && (raw == "." || path == path.normalize()))
        return path.normalize()
    }
    private fun classpath(raw: String): Path {
        require(raw.isNotBlank() && raw.toByteArray(Charsets.UTF_8).size <= 4_096 && '\\' !in raw && '\u0000' !in raw)
        val path = Path.of(raw); require(path == path.normalize() && !path.startsWith(".."))
        return path
    }
    private fun contains(parent: Path, child: Path): Boolean = parent.toString().isEmpty() || child.startsWith(parent)
}

/** Cheap preflight bounds nesting and rejects duplicate keys before JSON materialization. */
private object DeclaredJavaJsonShape {
    fun check(text: String) {
        val frames = mutableListOf<Pair<Char, MutableSet<String>>>(); var index = 0
        while (index < text.length) {
            when (val char = text[index]) {
                '{', '[' -> { require(frames.size < 16); frames += char to mutableSetOf() }
                '}', ']' -> { require(frames.isNotEmpty() && frames.last().first == if (char == '}') '{' else '['); frames.removeAt(frames.lastIndex) }
                '"' -> {
                    val start = index++; var escaped = false
                    while (index < text.length) {
                        val next = text[index]
                        if (!escaped && next == '"') break
                        escaped = !escaped && next == '\\'; index++
                    }
                    require(index < text.length)
                    var after = index + 1; while (after < text.length && text[after].isWhitespace()) after++
                    if (after < text.length && text[after] == ':') {
                        require(frames.lastOrNull()?.first == '{')
                        require(frames.last().second.add(Json.parseToJsonElement(text.substring(start, index + 1)).jsonPrimitive.content))
                    }
                }
            }
            index++
        }
        require(frames.isEmpty())
    }
}

/** Per-capture bounded, no-follow filesystem port producing standard ENTRY fingerprints. */
private class DeclaredJavaClasspathCapture {
    private var entries = 0
    private var bytes = 0L
    private val captured = mutableMapOf<Path, String>()

    fun capture(root: Path, path: Path, allowMissing: Boolean): ClasspathEvidence {
        val absolute = root.resolve(path).toAbsolutePath().normalize()
        val fingerprint = captured.getOrPut(absolute) {
            try {
                Files.newDirectoryStream(absolute.root).use { opened ->
                    val owned = opened as? SecureDirectoryStream<Path> ?: unavailable()
                    descend(owned, absolute, 0) { parent, name ->
                        val before = attributes(parent, name)
                        val digest = MessageDigest.getInstance("SHA-256")
                        when {
                            before.isRegularFile -> {
                                require(name.toString().substringAfterLast('.').lowercase() in setOf("jar", "zip"))
                                digest.update("file\u0000".toByteArray()); read(parent, name, before, digest)
                            }
                            before.isDirectory -> parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { directory ->
                                require(same(before, directory.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()))
                                val files = mutableListOf<Pair<Path, BasicFileAttributes>>()
                                inventory(directory, Path.of(""), files, 0)
                                digest.update("directory\u0000".toByteArray())
                                files.sortedBy { it.first.toString() }.forEach { (relative, attributes) ->
                                    digest.update(relative.toString().toByteArray()); digest.update(0)
                                    descend(directory, relative, 0) { owner, file -> read(owner, file, attributes, digest) }
                                    digest.update(0)
                                }
                            }
                            else -> unavailable()
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                }
            } catch (_: NoSuchFileException) {
                if (allowMissing) "missing" else unavailable()
            } catch (failure: DeclaredJavaFailure) { throw failure }
            catch (_: Exception) { unavailable() }
        }
        return ClasspathEvidence(path, ClasspathEvidenceKind.ENTRY, fingerprint)
    }

    private fun <T> descend(directory: SecureDirectoryStream<Path>, path: Path, index: Int,
                          action: (SecureDirectoryStream<Path>, Path) -> T): T {
        if (++entries > 25_000 || path.nameCount > 64) unavailable()
        val name = path.getName(index)
        if (index == path.nameCount - 1) return action(directory, name)
        val before = attributes(directory, name); if (!before.isDirectory) unavailable()
        return directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { child ->
            if (!same(before, child.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes())) unavailable()
            descend(child, path, index + 1, action)
        }
    }

    private fun inventory(directory: SecureDirectoryStream<Path>, relative: Path,
                          files: MutableList<Pair<Path, BasicFileAttributes>>, depth: Int) {
        if (depth > 64) unavailable()
        val before = directory.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes()
        directory.map { it.fileName }.sortedBy { it.toString() }.forEach { name ->
            if (++entries > 25_000) unavailable()
            val attributes = attributes(directory, name); val path = relative.resolve(name)
            when {
                attributes.isRegularFile -> { if (attributes.size() > 128L * 1024 * 1024) unavailable(); files += path to attributes }
                attributes.isDirectory -> directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { child ->
                    if (!same(attributes, child.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes())) unavailable()
                    inventory(child, path, files, depth + 1)
                }
                else -> unavailable()
            }
        }
        if (!same(before, directory.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes())) unavailable()
    }

    private fun read(directory: SecureDirectoryStream<Path>, name: Path, expected: BasicFileAttributes, digest: MessageDigest) {
        if (!expected.isRegularFile || expected.size() > 128L * 1024 * 1024 || !same(expected, attributes(directory, name))) unavailable()
        directory.newByteChannel(name, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteBuffer.allocate(8_192); var total = 0L
            while (true) {
                buffer.clear(); val count = channel.read(buffer); if (count < 0) break
                total += count; bytes += count
                if (total > expected.size() || bytes > 512L * 1024 * 1024) unavailable()
                digest.update(buffer.array(), 0, count)
            }
            if (total != expected.size()) unavailable()
        }
        if (!same(expected, attributes(directory, name))) unavailable()
    }

    private fun attributes(directory: SecureDirectoryStream<Path>, name: Path): BasicFileAttributes =
        directory.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes().also {
            if (it.isSymbolicLink || it.fileKey() == null) unavailable()
        }
    private fun same(a: BasicFileAttributes, b: BasicFileAttributes): Boolean =
        a.fileKey() != null && a.fileKey() == b.fileKey() && a.isRegularFile == b.isRegularFile &&
            a.isDirectory == b.isDirectory && a.size() == b.size() && a.lastModifiedTime() == b.lastModifiedTime()
    private fun unavailable(): Nothing = throw DeclaredJavaFailure("java.context.declaredClasspathUnavailable", "Declared classpath input cannot be captured safely within its bounds")
}
