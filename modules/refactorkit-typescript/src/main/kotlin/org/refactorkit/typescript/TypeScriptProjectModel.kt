package org.refactorkit.typescript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolPath
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

enum class TypeScriptProjectModelStatus { AVAILABLE, REFUSED }
enum class TypeScriptConfigKind { TYPESCRIPT, JAVASCRIPT }
enum class JavaScriptPackageType { MODULE, COMMONJS, UNSPECIFIED }

data class TypeScriptConfigPattern(val baseDirectory: Path, val pattern: String)

data class TypeScriptCompilerOptions(
    val allowJs: Boolean? = null,
    val checkJs: Boolean? = null,
    val declaration: Boolean? = null,
    val composite: Boolean? = null,
    val rootDirectory: Path? = null,
    val outputDirectory: Path? = null,
    val baseUrl: Path? = null,
    val jsx: String? = null,
    val module: String? = null,
    val moduleResolution: String? = null,
    val paths: Map<String, List<String>> = emptyMap(),
)

data class TypeScriptProject(
    val configPath: Path,
    val kind: TypeScriptConfigKind,
    val extendsConfigs: List<Path>,
    val references: List<Path>,
    val files: List<Path>,
    val include: List<TypeScriptConfigPattern>,
    val exclude: List<TypeScriptConfigPattern>,
    val compilerOptions: TypeScriptCompilerOptions,
    val packageType: JavaScriptPackageType,
    val packageManifest: Path?,
)

data class TypeScriptConfigEvidence(val path: Path, val sha256: String, val size: Long)

data class TypeScriptProjectModel(
    val providerId: String = PROVIDER_ID,
    val status: TypeScriptProjectModelStatus,
    val projects: List<TypeScriptProject>,
    val evidence: List<TypeScriptConfigEvidence>,
    val diagnostics: List<Diagnostic>,
    val projectionHash: String,
) {
    companion object { const val PROVIDER_ID = "typescript-config-declarative-v1" }
}

data class TypeScriptProjectModelPolicy(
    val maxConfigs: Int = 256,
    val maxExtendsDepth: Int = 16,
    val maxConfigBytes: Long = 1_048_576,
    val maxReferencesPerConfig: Int = 256,
    val maxPatternsPerConfig: Int = 4_096,
    val maxPathAliases: Int = 512,
    val maxAliasTargets: Int = 128,
    val maxStringLength: Int = 4_096,
) {
    init {
        require(maxConfigs in 1..4_096)
        require(maxExtendsDepth in 1..64)
        require(maxConfigBytes in 1..16_777_216)
        require(maxReferencesPerConfig in 1..4_096)
        require(maxPatternsPerConfig in 1..100_000)
        require(maxPathAliases in 1..4_096)
        require(maxAliasTargets in 1..4_096)
        require(maxStringLength in 1..65_536)
    }
}

/** Bounded declarative tsconfig/jsconfig builder. It never executes Node or project code. */
class TypeScriptProjectModelBuilder(
    private val policy: TypeScriptProjectModelPolicy = TypeScriptProjectModelPolicy(),
) {
    fun build(workspaceRoot: Path): TypeScriptProjectModel {
        val root = canonicalWorkspace(workspaceRoot) ?: return refused(listOf(diagnostic(
            "typescript.workspaceInvalid", "TypeScript workspace must be an existing non-symlink directory",
        )))
        val diagnostics = mutableListOf<Diagnostic>()
        val roots = runCatching { discoverConfigs(root, diagnostics) }.getOrElse {
            diagnostics += diagnostic("typescript.configDiscoveryFailed", "TypeScript config discovery failed")
            emptyList()
        }
        if (diagnostics.isNotEmpty()) return refused(diagnostics)
        if (roots.isEmpty()) return refused(listOf(diagnostic(
            "typescript.configMissing", "No tsconfig.json or jsconfig.json was found",
        )))

        val loaded = linkedMapOf<Path, LoadedConfig>()
        val evidence = linkedMapOf<Path, TypeScriptConfigEvidence>()
        roots.forEach { path -> load(path, root, 0, mutableListOf(), loaded, evidence, diagnostics) }
        if (diagnostics.isNotEmpty()) return refused(diagnostics, evidence.values)
        val projectPaths = roots.toMutableSet()
        var changed = true
        while (changed) {
            changed = false
            projectPaths.toList().forEach { projectPath ->
                loaded[projectPath]?.references.orEmpty().forEach { if (projectPaths.add(it)) changed = true }
            }
        }
        val projects = loaded.entries.filter { it.key in projectPaths }.map { (path, effective) ->
            val packageMetadata = nearestPackageMetadata(path.parent, root, evidence, diagnostics)
            TypeScriptProject(
                configPath = relativePath(root, path),
                kind = if (path.fileName.toString() == "jsconfig.json") TypeScriptConfigKind.JAVASCRIPT else TypeScriptConfigKind.TYPESCRIPT,
                extendsConfigs = effective.extendsConfigs.map { relativePath(root, it) },
                references = effective.references.map { relativePath(root, it) },
                files = effective.files,
                include = effective.include,
                exclude = effective.exclude,
                compilerOptions = effective.options,
                packageType = packageMetadata?.type ?: JavaScriptPackageType.UNSPECIFIED,
                packageManifest = packageMetadata?.path?.let { relativePath(root, it) },
            )
        }
        if (diagnostics.isNotEmpty()) return refused(diagnostics, evidence.values)
        validateReferenceCycles(projects, diagnostics)
        if (diagnostics.isNotEmpty()) return refused(diagnostics, evidence.values)
        val orderedProjects = projects.sortedBy { it.configPath.toString() }
        val orderedEvidence = evidence.values.sortedBy { it.path.toString() }
        return TypeScriptProjectModel(
            status = TypeScriptProjectModelStatus.AVAILABLE,
            projects = orderedProjects,
            evidence = orderedEvidence,
            diagnostics = emptyList(),
            projectionHash = projectionHash(orderedProjects, orderedEvidence),
        )
    }

    fun build(snapshot: ProjectSnapshot): TypeScriptProjectModel = build(snapshot.workspace.root)

    private fun discoverConfigs(root: Path, diagnostics: MutableList<Diagnostic>): List<Path> {
        val configs = mutableListOf<Path>()
        Files.walkFileTree(root, setOf(), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && (dir.fileName.toString() in IGNORED_DIRECTORIES || Files.isSymbolicLink(dir))) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && file.fileName.toString() in CONFIG_NAMES) {
                    configs.add(file.toAbsolutePath().normalize())
                    if (configs.size > policy.maxConfigs) {
                        diagnostics += diagnostic("typescript.configLimit", "TypeScript config count exceeds ${policy.maxConfigs}")
                        return FileVisitResult.TERMINATE
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })
        return configs.sortedBy(Path::toString)
    }

    private fun load(
        config: Path,
        root: Path,
        depth: Int,
        stack: MutableList<Path>,
        cache: MutableMap<Path, LoadedConfig>,
        evidence: MutableMap<Path, TypeScriptConfigEvidence>,
        diagnostics: MutableList<Diagnostic>,
    ): LoadedConfig? {
        cache[config]?.let { return it }
        if (depth > policy.maxExtendsDepth) {
            diagnostics += diagnostic("typescript.extendsDepth", "TypeScript extends depth exceeds ${policy.maxExtendsDepth}")
            return null
        }
        if (config in stack) {
            diagnostics += diagnostic("typescript.extendsCycle", "TypeScript config extends cycle detected")
            return null
        }
        val file = requireWorkspaceFile(config, root, diagnostics, "typescript.configPathInvalid") ?: return null
        val bytes = readBounded(file, policy.maxConfigBytes, diagnostics, "typescript.configFileLimit") ?: return null
        evidence[root.relativize(file)] = evidence(root.relativize(file), bytes)
        val configText = decodeUtf8(bytes) ?: run {
            diagnostics += diagnostic("typescript.configEncoding", "TypeScript config must be valid UTF-8")
            return null
        }
        val json = parseJsonc(configText, diagnostics, file) ?: return null
        stack.add(file)
        val extendsValue = json.optionalString("extends", diagnostics)
        if (diagnostics.isNotEmpty()) return null
        val parentPath = extendsValue?.let { resolveConfigReference(file.parent, it, root, diagnostics, "typescript.extendsUnsupported") }
        val parent = parentPath?.let { load(it, root, depth + 1, stack, cache, evidence, diagnostics) }
        stack.removeAt(stack.lastIndex)
        if (parentPath != null && parent == null) return null

        val own = parseOwnConfig(json, file, root, diagnostics) ?: return null
        val effective = if (parent == null) own else merge(parent, own).copy(
            extendsConfigs = parent.extendsConfigs + listOf(parentPath!!),
        )
        cache[file] = effective
        if (cache.size > policy.maxConfigs) {
            diagnostics += diagnostic("typescript.configLimit", "TypeScript config graph exceeds ${policy.maxConfigs}")
            return null
        }
        effective.references.forEach { reference ->
            load(reference, root, depth + 1, mutableListOf(), cache, evidence, diagnostics)
        }
        return effective
    }

    private fun parseOwnConfig(
        json: JsonObject,
        config: Path,
        root: Path,
        diagnostics: MutableList<Diagnostic>,
    ): LoadedConfig? {
        val directory = config.parent
        val optionsElement = json["compilerOptions"]
        val optionsJson = when (optionsElement) {
            null -> JsonObject(emptyMap())
            is JsonObject -> optionsElement
            else -> {
                diagnostics += diagnostic("typescript.configShapeInvalid", "compilerOptions must be an object")
                return null
            }
        }
        val baseUrl = optionsJson.optionalString("baseUrl", diagnostics)?.let {
            resolveDirectoryOption(directory, it, root, diagnostics, "baseUrl")
        }
        val pathBase = baseUrl?.let(root::resolve) ?: directory
        val paths = parsePaths(optionsJson["paths"], pathBase, root, diagnostics) ?: return null
        val files = parseStringArray(json["files"], policy.maxPatternsPerConfig, "files", diagnostics)
            ?.mapNotNull { resolveSourceFile(directory, it, root, diagnostics) } ?: emptyList()
        val include = parsePatterns(json["include"], directory, root, diagnostics) ?: emptyList()
        val exclude = parsePatterns(json["exclude"], directory, root, diagnostics) ?: emptyList()
        if (files.size + include.size + exclude.size > policy.maxPatternsPerConfig) {
            diagnostics += diagnostic("typescript.patternLimit", "TypeScript file/include/exclude patterns exceed ${policy.maxPatternsPerConfig}")
            return null
        }
        val referencesJson = json["references"]
        val references = if (referencesJson == null) emptyList() else {
            val array = referencesJson as? JsonArray ?: run {
                diagnostics += diagnostic("typescript.referenceInvalid", "TypeScript references must be an array")
                return null
            }
            if (array.size > policy.maxReferencesPerConfig) {
                diagnostics += diagnostic("typescript.referenceLimit", "TypeScript references exceed ${policy.maxReferencesPerConfig}")
                return null
            }
            array.mapNotNull { element ->
                val reference = element as? JsonObject
                val path = reference?.string("path")
                if (path == null) {
                    diagnostics += diagnostic("typescript.referenceInvalid", "TypeScript reference requires string path")
                    null
                } else resolveConfigReference(directory, path, root, diagnostics, "typescript.referenceInvalid")
            }
        }
        if (diagnostics.isNotEmpty()) return null

        val options = TypeScriptCompilerOptions(
            allowJs = optionsJson.boolean("allowJs", diagnostics),
            checkJs = optionsJson.boolean("checkJs", diagnostics),
            declaration = optionsJson.boolean("declaration", diagnostics),
            composite = optionsJson.boolean("composite", diagnostics),
            rootDirectory = optionsJson.optionalString("rootDir", diagnostics)?.let { resolveDirectoryOption(directory, it, root, diagnostics, "rootDir") },
            outputDirectory = optionsJson.optionalString("outDir", diagnostics)?.let { resolveDirectoryOption(directory, it, root, diagnostics, "outDir") },
            baseUrl = baseUrl,
            jsx = optionsJson.optionalString("jsx", diagnostics)?.bounded("jsx", diagnostics),
            module = optionsJson.optionalString("module", diagnostics)?.bounded("module", diagnostics),
            moduleResolution = optionsJson.optionalString("moduleResolution", diagnostics)?.bounded("moduleResolution", diagnostics),
            paths = paths,
        )
        if (diagnostics.isNotEmpty()) return null
        return LoadedConfig(
            options = options,
            files = files.distinct().sortedBy(Path::toString),
            include = include.distinct(),
            exclude = exclude.distinct(),
            references = references.distinct().sortedBy(Path::toString),
            extendsConfigs = emptyList(),
            ownFiles = json.containsKey("files"),
            ownInclude = json.containsKey("include"),
            ownExclude = json.containsKey("exclude"),
            ownPaths = optionsJson.containsKey("paths"),
        )
    }

    private fun validateReferenceCycles(projects: List<TypeScriptProject>, diagnostics: MutableList<Diagnostic>) {
        val byPath = projects.associateBy(TypeScriptProject::configPath)
        val visiting = mutableSetOf<Path>()
        val visited = mutableSetOf<Path>()
        fun visit(path: Path): Boolean {
            if (path in visiting) return false
            if (!visited.add(path)) return true
            visiting.add(path)
            val valid = byPath[path]?.references.orEmpty().all(::visit)
            visiting.remove(path)
            return valid
        }
        if (byPath.keys.any { !visit(it) }) {
            diagnostics += diagnostic("typescript.referenceCycle", "TypeScript project reference cycle detected")
        }
    }

    private fun merge(parent: LoadedConfig, child: LoadedConfig): LoadedConfig = LoadedConfig(
        options = TypeScriptCompilerOptions(
            allowJs = child.options.allowJs ?: parent.options.allowJs,
            checkJs = child.options.checkJs ?: parent.options.checkJs,
            declaration = child.options.declaration ?: parent.options.declaration,
            composite = child.options.composite ?: parent.options.composite,
            rootDirectory = child.options.rootDirectory ?: parent.options.rootDirectory,
            outputDirectory = child.options.outputDirectory ?: parent.options.outputDirectory,
            baseUrl = child.options.baseUrl ?: parent.options.baseUrl,
            jsx = child.options.jsx ?: parent.options.jsx,
            module = child.options.module ?: parent.options.module,
            moduleResolution = child.options.moduleResolution ?: parent.options.moduleResolution,
            paths = if (child.ownPaths) child.options.paths else parent.options.paths,
        ),
        files = if (child.ownFiles) child.files else parent.files,
        include = if (child.ownInclude) child.include else parent.include,
        exclude = if (child.ownExclude) child.exclude else parent.exclude,
        references = child.references,
        extendsConfigs = parent.extendsConfigs,
        ownFiles = child.ownFiles,
        ownInclude = child.ownInclude,
        ownExclude = child.ownExclude,
        ownPaths = child.ownPaths,
    )

    private fun parsePaths(
        element: JsonElement?,
        baseDirectory: Path,
        root: Path,
        diagnostics: MutableList<Diagnostic>,
    ): Map<String, List<String>> {
        if (element == null) return emptyMap()
        val objectValue = element as? JsonObject ?: run {
            diagnostics += diagnostic("typescript.pathsInvalid", "compilerOptions.paths must be an object")
            return emptyMap()
        }
        if (objectValue.size > policy.maxPathAliases) {
            diagnostics += diagnostic("typescript.pathAliasLimit", "TypeScript path aliases exceed ${policy.maxPathAliases}")
            return emptyMap()
        }
        return objectValue.entries.sortedBy(Map.Entry<String, JsonElement>::key).mapNotNull { (alias, targetsElement) ->
            if (!validPattern(alias)) {
                diagnostics += diagnostic("typescript.pathsInvalid", "TypeScript path alias is invalid")
                return@mapNotNull null
            }
            val targets = parseStringArray(targetsElement, policy.maxAliasTargets, "path alias targets", diagnostics)
                ?.map { it.replace('\\', '/') } ?: return@mapNotNull null
            if (targets.any {
                    !validPattern(it) || absoluteLike(it) || !baseDirectory.resolve(it).normalize().startsWith(root)
                }) {
                diagnostics += diagnostic("typescript.pathsInvalid", "TypeScript path alias target is invalid")
                return@mapNotNull null
            }
            alias to targets
        }.toMap()
    }

    private fun parsePatterns(
        element: JsonElement?,
        directory: Path,
        root: Path,
        diagnostics: MutableList<Diagnostic>,
    ): List<TypeScriptConfigPattern>? {
        val values = parseStringArray(element, policy.maxPatternsPerConfig, "patterns", diagnostics) ?: return null
        val relativeBase = root.relativize(directory)
        return values.mapNotNull { pattern ->
            val normalized = pattern.replace('\\', '/')
            if (!validPattern(normalized) || absoluteLike(normalized) || !directory.resolve(normalized).normalize().startsWith(root)) {
                diagnostics += diagnostic("typescript.patternInvalid", "TypeScript include/exclude pattern is unsafe")
                null
            } else TypeScriptConfigPattern(relativeBase, normalized)
        }
    }

    private fun parseStringArray(
        element: JsonElement?,
        limit: Int,
        label: String,
        diagnostics: MutableList<Diagnostic>,
    ): List<String>? {
        if (element == null) return emptyList()
        val array = element as? JsonArray ?: run {
            diagnostics += diagnostic("typescript.configShapeInvalid", "TypeScript $label must be an array")
            return null
        }
        if (array.size > limit) {
            diagnostics += diagnostic("typescript.patternLimit", "TypeScript $label exceeds $limit entries")
            return null
        }
        return array.mapNotNull { value ->
            val text = (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            if (text == null || text.length > policy.maxStringLength || '\u0000' in text) {
                diagnostics += diagnostic("typescript.configShapeInvalid", "TypeScript $label contains invalid string")
                null
            } else text
        }
    }

    private fun resolveSourceFile(
        directory: Path,
        raw: String,
        root: Path,
        diagnostics: MutableList<Diagnostic>,
    ): Path? {
        if (absoluteLike(raw) || globCharacters.any(raw::contains)) {
            diagnostics += diagnostic("typescript.filePathInvalid", "TypeScript files entry must be a safe relative file path")
            return null
        }
        val path = directory.resolve(raw).normalize()
        if (!path.startsWith(root) || path == root || traversesSymlink(root, path)) {
            diagnostics += diagnostic("typescript.filePathInvalid", "TypeScript files entry escapes workspace or traverses symlink")
            return null
        }
        return root.relativize(path)
    }

    private fun resolveDirectoryOption(
        directory: Path,
        raw: String,
        root: Path,
        diagnostics: MutableList<Diagnostic>,
        name: String,
    ): Path? {
        if (raw.length > policy.maxStringLength || absoluteLike(raw) || globCharacters.any(raw::contains)) {
            diagnostics += diagnostic("typescript.compilerPathInvalid", "TypeScript $name must be a safe relative path")
            return null
        }
        val path = directory.resolve(raw).normalize()
        if (!path.startsWith(root) || traversesSymlink(root, path)) {
            diagnostics += diagnostic("typescript.compilerPathInvalid", "TypeScript $name escapes workspace or traverses symlink")
            return null
        }
        return root.relativize(path)
    }

    private fun resolveConfigReference(
        directory: Path,
        raw: String,
        root: Path,
        diagnostics: MutableList<Diagnostic>,
        code: String,
    ): Path? {
        if (raw.length > policy.maxStringLength || absoluteLike(raw) || raw.startsWith("@") || raw.contains(':')) {
            diagnostics += diagnostic(code, "Only workspace-relative declarative config references are supported")
            return null
        }
        var candidate = directory.resolve(raw).normalize()
        if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) candidate = candidate.resolve("tsconfig.json")
        if (!candidate.fileName.toString().endsWith(".json")) candidate = candidate.resolveSibling(candidate.fileName.toString() + ".json")
        if (!candidate.startsWith(root) || traversesSymlink(root, candidate)) {
            diagnostics += diagnostic(code, "TypeScript config reference escapes workspace or traverses symlink")
            return null
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            diagnostics += diagnostic("typescript.configReferenceMissing", "Referenced TypeScript config is missing")
            return null
        }
        return candidate.toAbsolutePath().normalize()
    }

    private fun nearestPackageMetadata(
        start: Path,
        root: Path,
        evidence: MutableMap<Path, TypeScriptConfigEvidence>,
        diagnostics: MutableList<Diagnostic>,
    ): PackageMetadata? {
        var directory: Path? = start
        while (directory != null && directory.startsWith(root)) {
            val manifest = directory.resolve("package.json")
            if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                val file = requireWorkspaceFile(manifest, root, diagnostics, "typescript.packageManifestInvalid") ?: return null
                val bytes = readBounded(file, PACKAGE_MANIFEST_BYTES, diagnostics, "typescript.packageManifestLimit") ?: return null
                evidence[root.relativize(file)] = evidence(root.relativize(file), bytes)
                val packageText = decodeUtf8(bytes)
                val json = packageText?.let { runCatching { STRICT_JSON.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                if (json == null) {
                    diagnostics += diagnostic("typescript.packageManifestInvalid", "package.json must be strict bounded JSON")
                    return null
                }
                if (json.containsKey("type") && json.string("type") == null) {
                    diagnostics += diagnostic("typescript.packageTypeInvalid", "package.json type must be a string")
                    return null
                }
                val type = when (json.string("type")) {
                    "module" -> JavaScriptPackageType.MODULE
                    "commonjs" -> JavaScriptPackageType.COMMONJS
                    null -> JavaScriptPackageType.UNSPECIFIED
                    else -> {
                        diagnostics += diagnostic("typescript.packageTypeInvalid", "package.json type must be module or commonjs")
                        return null
                    }
                }
                return PackageMetadata(file, type)
            }
            if (directory == root) break
            directory = directory.parent
        }
        return null
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    private fun parseJsonc(text: String, diagnostics: MutableList<Diagnostic>, path: Path): JsonObject? {
        val stripped = JsoncSanitizer.sanitize(text).getOrElse {
            diagnostics += diagnostic("typescript.configSyntax", "Invalid JSONC syntax in ${path.fileName}")
            return null
        }
        val parsed = runCatching { STRICT_JSON.parseToJsonElement(stripped) as? JsonObject }.getOrNull()
        if (parsed == null) diagnostics += diagnostic("typescript.configSyntax", "TypeScript config must be a JSONC object")
        return parsed
    }

    private fun requireWorkspaceFile(
        path: Path,
        root: Path,
        diagnostics: MutableList<Diagnostic>,
        code: String,
    ): Path? {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(root) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(normalized) || traversesSymlink(root, normalized)) {
            diagnostics += diagnostic(code, "TypeScript model input is outside workspace, missing, or symlinked")
            return null
        }
        return normalized
    }

    private fun readBounded(
        path: Path,
        maxBytes: Long,
        diagnostics: MutableList<Diagnostic>,
        code: String,
    ): ByteArray? {
        val size = runCatching { Files.size(path) }.getOrNull()
        if (size == null || size !in 1..maxBytes) {
            diagnostics += diagnostic(code, "TypeScript model input exceeds bounded size")
            return null
        }
        val bytes = runCatching { Files.readAllBytes(path) }.getOrNull()
        if (bytes == null || bytes.size.toLong() != size || bytes.size > maxBytes) {
            diagnostics += diagnostic(code, "TypeScript model input changed or could not be read exactly")
            return null
        }
        return bytes
    }

    private fun traversesSymlink(root: Path, target: Path): Boolean {
        if (!target.startsWith(root)) return true
        var current = root
        root.relativize(target).forEach { segment ->
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }

    private fun relativePath(root: Path, path: Path): Path =
        if (path.isAbsolute) root.relativize(path.normalize()) else path.normalize()

    private fun canonicalWorkspace(path: Path): Path? {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) return null
        return runCatching { normalized.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull()
    }

    private fun String.bounded(name: String, diagnostics: MutableList<Diagnostic>): String? {
        if (length > policy.maxStringLength || '\u0000' in this) {
            diagnostics += diagnostic("typescript.compilerOptionInvalid", "TypeScript $name option is invalid")
            return null
        }
        return this
    }

    private fun validPattern(value: String): Boolean =
        value.isNotBlank() && value.length <= policy.maxStringLength && '\u0000' !in value

    private fun absoluteLike(value: String): Boolean = value.startsWith('/') || value.startsWith('\\') ||
        WINDOWS_ABSOLUTE.containsMatchIn(value) || value.contains("://")

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.optionalString(name: String, diagnostics: MutableList<Diagnostic>): String? {
        val value = this[name] ?: return null
        val text = (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        if (text == null || text.length > policy.maxStringLength || '\u0000' in text) {
            diagnostics += diagnostic("typescript.compilerOptionInvalid", "TypeScript $name must be a bounded string")
            return null
        }
        return text
    }

    private fun JsonObject.boolean(name: String, diagnostics: MutableList<Diagnostic>): Boolean? {
        val value = this[name] ?: return null
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive.isString || primitive.content !in setOf("true", "false")) {
            diagnostics += diagnostic("typescript.compilerOptionInvalid", "TypeScript $name must be boolean")
            return null
        }
        return primitive.content.toBooleanStrict()
    }

    private fun evidence(path: Path, bytes: ByteArray) = TypeScriptConfigEvidence(
        path,
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        bytes.size.toLong(),
    )

    private fun projectionHash(projects: List<TypeScriptProject>, evidence: List<TypeScriptConfigEvidence>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(vararg values: String) = values.forEach { value ->
            digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0)
        }
        projects.forEach { project ->
            add("project", ProtocolPath.serialize(project.configPath), project.kind.name, project.packageType.name)
            project.extendsConfigs.forEach { add("extends", ProtocolPath.serialize(it)) }
            project.references.forEach { add("reference", ProtocolPath.serialize(it)) }
            project.files.forEach { add("file", ProtocolPath.serialize(it)) }
            project.include.forEach { add("include", ProtocolPath.serialize(it.baseDirectory), it.pattern) }
            project.exclude.forEach { add("exclude", ProtocolPath.serialize(it.baseDirectory), it.pattern) }
            project.packageManifest?.let { add("package", ProtocolPath.serialize(it)) }
            val options = project.compilerOptions
            add(
                "options", options.allowJs.toString(), options.checkJs.toString(), options.declaration.toString(), options.composite.toString(),
                options.rootDirectory?.let(ProtocolPath::serialize).orEmpty(),
                options.outputDirectory?.let(ProtocolPath::serialize).orEmpty(),
                options.baseUrl?.let(ProtocolPath::serialize).orEmpty(),
                options.jsx.orEmpty(), options.module.orEmpty(), options.moduleResolution.orEmpty(),
            )
            options.paths.toSortedMap().forEach { (alias, targets) -> add("path", alias, *targets.toTypedArray()) }
        }
        evidence.forEach { add("evidence", ProtocolPath.serialize(it.path), it.sha256, it.size.toString()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun refused(
        diagnostics: List<Diagnostic>,
        evidence: Collection<TypeScriptConfigEvidence> = emptyList(),
    ) = TypeScriptProjectModel(
        status = TypeScriptProjectModelStatus.REFUSED,
        projects = emptyList(),
        evidence = evidence.sortedBy { it.path.toString() },
        diagnostics = diagnostics.distinctBy { it.code to it.message },
        projectionHash = "",
    )

    private fun diagnostic(code: String, message: String) = Diagnostic(
        message = message,
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.PROJECT_STRUCTURE,
    )

    private data class LoadedConfig(
        val options: TypeScriptCompilerOptions,
        val files: List<Path>,
        val include: List<TypeScriptConfigPattern>,
        val exclude: List<TypeScriptConfigPattern>,
        val references: List<Path>,
        val extendsConfigs: List<Path>,
        val ownFiles: Boolean,
        val ownInclude: Boolean,
        val ownExclude: Boolean,
        val ownPaths: Boolean,
    )

    private data class PackageMetadata(val path: Path, val type: JavaScriptPackageType)

    companion object {
        private val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = true }
        private val CONFIG_NAMES = setOf("tsconfig.json", "jsconfig.json")
        private val IGNORED_DIRECTORIES = setOf(
            ".git", ".gradle", ".idea", ".refactorkit", "build", "target", "dist", "out", "coverage", "node_modules", "__pycache__",
        )
        private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[\\\\/]")
        private val globCharacters = setOf('*', '?', '[', ']', '{', '}')
        private const val PACKAGE_MANIFEST_BYTES = 65_536L
    }
}

internal object JsoncSanitizer {
    fun sanitize(input: String): Result<String> = runCatching {
        val noComments = StringBuilder(input.length)
        var index = 0
        var inString = false
        var escaped = false
        while (index < input.length) {
            val current = input[index]
            if (inString) {
                noComments.append(current)
                when {
                    escaped -> escaped = false
                    current == '\\' -> escaped = true
                    current == '"' -> inString = false
                }
                index++
                continue
            }
            if (current == '"') {
                inString = true
                noComments.append(current)
                index++
                continue
            }
            if (current == '/' && index + 1 < input.length && input[index + 1] == '/') {
                noComments.append("  ")
                index += 2
                while (index < input.length && input[index] !in setOf('\r', '\n')) {
                    noComments.append(' ')
                    index++
                }
                continue
            }
            if (current == '/' && index + 1 < input.length && input[index + 1] == '*') {
                noComments.append("  ")
                index += 2
                var closed = false
                while (index < input.length) {
                    if (input[index] == '*' && index + 1 < input.length && input[index + 1] == '/') {
                        noComments.append("  ")
                        index += 2
                        closed = true
                        break
                    }
                    noComments.append(if (input[index] in setOf('\r', '\n')) input[index] else ' ')
                    index++
                }
                check(closed) { "unterminated JSONC block comment" }
                continue
            }
            noComments.append(current)
            index++
        }
        check(!inString) { "unterminated JSONC string" }

        val output = StringBuilder(noComments.length)
        index = 0
        inString = false
        escaped = false
        while (index < noComments.length) {
            val current = noComments[index]
            if (inString) {
                output.append(current)
                when {
                    escaped -> escaped = false
                    current == '\\' -> escaped = true
                    current == '"' -> inString = false
                }
                index++
                continue
            }
            if (current == '"') {
                inString = true
                output.append(current)
                index++
                continue
            }
            if (current == ',') {
                var lookahead = index + 1
                while (lookahead < noComments.length && noComments[lookahead].isWhitespace()) lookahead++
                if (lookahead < noComments.length && noComments[lookahead] in setOf('}', ']')) {
                    output.append(' ')
                    index++
                    continue
                }
            }
            output.append(current)
            index++
        }
        output.toString()
    }
}
