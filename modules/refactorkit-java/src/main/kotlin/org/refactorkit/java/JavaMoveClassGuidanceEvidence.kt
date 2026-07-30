package org.refactorkit.java

import org.refactorkit.core.BuildModel
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.ProjectSnapshot
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.path.invariantSeparatorsPathString

internal sealed interface JavaMoveClassGuidanceEvidenceResult<out T> {
    data class Valid<T>(val value: T) : JavaMoveClassGuidanceEvidenceResult<T>
    data class Invalid(val code: String, val summary: String) : JavaMoveClassGuidanceEvidenceResult<Nothing>
}

internal data class JavaMoveClassGuidanceExpectedSource(
    val path: Path,
    val mavenModule: String,
    val sourceSet: String,
    val content: String,
    val contentSha256: String,
    val manifestPath: Path,
    val manifestContentSha256: String,
    val expectedContentSha256: String,
    val observedInSourceInventory: Boolean,
)

internal data class JavaMoveClassGuidanceEnumeratedSource(
    val path: Path,
    val mavenModule: String,
    val sourceSet: String,
    val content: String,
    val contentSha256: String,
)

internal object JavaMoveClassGuidanceEvidence {
    private const val SOURCE_INVENTORY_FILE = ".refactorkit-expected-source-inventory.properties"
    private const val SOURCE_INVENTORY_FORMAT = "1"
    private const val GENERATED_INVENTORY_FILE = ".refactorkit-generated-root-inventory.properties"
    private const val GENERATED_INVENTORY_FORMAT = "1"
    private const val GENERATED_INVENTORY_MARKER = "java-generated-root-inventory-v1\u0000"
    private const val MAX_SOURCE_FILES = 50_000
    private const val MAX_SOURCE_BYTES = 2_097_152L
    private const val MAX_RESIDUAL_FILES = 20_000
    private const val MAX_RESIDUAL_BYTES = 1_048_576L
    private const val MAX_EVIDENCE_BYTES = 16_384L
    private const val MAX_ARTIFACT_BYTES = MAX_SOURCE_BYTES * 64

    fun enumerateJavaSources(
        snapshot: ProjectSnapshot,
        model: BuildModel,
    ): JavaMoveClassGuidanceEvidenceResult<List<JavaMoveClassGuidanceEnumeratedSource>> = evidenceResult {
        val workspace = snapshot.workspace.root.toAbsolutePath().normalize()
        evidenceRequire(
            Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(workspace),
            "java.maven.moveClass.sourceEnumeration.invalid",
            "The workspace cannot be safely enumerated for move-class guidance.",
        )
        val roots = model.modules.flatMap { module ->
            module.sourceSets.flatMap { sourceSet ->
                sourceSet.sourceRoots.map { root -> SourceRoot(module.id, sourceSet.id, root.normalize()) }
            }
        }.sortedWith(
            compareBy<SourceRoot> { it.root.invariantSeparatorsPathString }
                .thenBy(SourceRoot::mavenModule)
                .thenBy(SourceRoot::sourceSet),
        )
        val sources = linkedMapOf<Path, JavaMoveClassGuidanceEnumeratedSource>()
        roots.forEach { sourceRoot ->
            evidenceRequire(
                javaMoveGuidanceIsSafeRelative(sourceRoot.root),
                "java.maven.moveClass.sourceRoot.invalid",
                "A Maven source root is not safely workspace-relative.",
            )
            val absoluteRoot = resolveInsideWorkspace(workspace, sourceRoot.root)
                ?: evidenceFailure(
                    "java.maven.moveClass.sourceRoot.invalid",
                    "A Maven source root escapes the workspace.",
                )
            if (!Files.exists(absoluteRoot, LinkOption.NOFOLLOW_LINKS)) return@forEach
            evidenceRequire(
                Files.isDirectory(absoluteRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(absoluteRoot),
                "java.maven.moveClass.sourceRoot.invalid",
                "A Maven source root cannot be safely enumerated.",
            )
            var failure: EvidenceFailure? = null
            Files.walkFileTree(absoluteRoot, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (Files.isSymbolicLink(dir)) {
                        failure = EvidenceFailure(
                            "java.maven.moveClass.sourceEnumeration.unsafe",
                            "A symbolic link prevents bounded source enumeration.",
                        )
                        return FileVisitResult.TERMINATE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile || Files.isSymbolicLink(file) ||
                        file.fileName?.toString().orEmpty().substringAfterLast('.', "") != "java"
                    ) return FileVisitResult.CONTINUE
                    if (sources.size >= MAX_SOURCE_FILES || attrs.size() > MAX_SOURCE_BYTES) {
                        failure = EvidenceFailure(
                            "java.maven.moveClass.sourceEnumeration.unbounded",
                            "The Java source inventory exceeds the bounded guidance limit.",
                        )
                        return FileVisitResult.TERMINATE
                    }
                    val relative = workspace.relativize(file.toAbsolutePath().normalize()).normalize()
                    if (!javaMoveGuidanceIsSafeRelative(relative)) {
                        failure = EvidenceFailure(
                            "java.maven.moveClass.sourceEnumeration.unsafe",
                            "A Java source path is not safely workspace-relative.",
                        )
                        return FileVisitResult.TERMINATE
                    }
                    val bytes = stableFileBytes(file, MAX_SOURCE_BYTES) ?: run {
                        failure = EvidenceFailure(
                            "java.maven.moveClass.sourceEnumeration.unstable",
                            "A Java source changed or became unreadable during evidence capture.",
                        )
                        return FileVisitResult.TERMINATE
                    }
                    val content = decodeUtf8(bytes) ?: run {
                        failure = EvidenceFailure(
                            "java.maven.moveClass.sourceEncoding.invalid",
                            "A Java source is not valid UTF-8.",
                        )
                        return FileVisitResult.TERMINATE
                    }
                    val candidate = JavaMoveClassGuidanceEnumeratedSource(
                        relative,
                        sourceRoot.mavenModule,
                        sourceRoot.sourceSet,
                        content,
                        javaMoveGuidanceSha256(bytes),
                    )
                    val previous = sources.putIfAbsent(relative, candidate)
                    if (previous != null &&
                        (previous.mavenModule != candidate.mavenModule || previous.sourceSet != candidate.sourceSet)
                    ) {
                        failure = EvidenceFailure(
                            "java.maven.moveClass.sourceOwnership.ambiguous",
                            "A Java source has more than one Maven source-set owner.",
                        )
                        return FileVisitResult.TERMINATE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                    failure = EvidenceFailure(
                        "java.maven.moveClass.sourceEnumeration.unreadable",
                        "A Java source could not be read during bounded enumeration.",
                    )
                    return FileVisitResult.TERMINATE
                }
            })
            failure?.let { throw it }
        }
        sources.values.sortedBy { it.path.invariantSeparatorsPathString }
    }

    fun expectedSources(
        snapshot: ProjectSnapshot,
        model: BuildModel,
    ): JavaMoveClassGuidanceEvidenceResult<List<JavaMoveClassGuidanceExpectedSource>> = evidenceResult {
        val workspace = snapshot.workspace.root.toAbsolutePath().normalize()
        val inventory = snapshot.files.associateBy { it.path.normalize() }
        model.modules.sortedBy { it.id }.mapNotNull { module ->
            val manifest = loadSnapshotManifest(
                snapshot,
                module.root,
                SOURCE_INVENTORY_FILE,
                SOURCE_MANIFEST_KEYS,
            ) ?: return@mapNotNull null
            evidenceRequire(
                manifest.properties.getProperty("formatVersion") == SOURCE_INVENTORY_FORMAT,
                "java.maven.moveClass.sourceInventory.manifest.invalid",
                "The expected source-inventory manifest has an unsupported format.",
            )
            val ownerModule = manifest.properties.required("ownerModule")
            val sourceSet = manifest.properties.required("sourceSet")
            evidenceRequire(
                ownerModule == module.id,
                "java.maven.moveClass.sourceInventory.manifest.invalid",
                "The expected source-inventory manifest owner does not match its Maven module.",
            )
            evidenceRequire(
                module.sourceSets.count { it.id == sourceSet } == 1,
                "java.maven.moveClass.sourceInventory.manifest.invalid",
                "The expected source-inventory manifest names an unknown Maven source set.",
            )
            val path = parseSafeRelative(manifest.properties.required("path"))
                ?: evidenceFailure(
                    "java.maven.moveClass.sourceInventory.manifest.invalid",
                    "The expected source-inventory path is not safely workspace-relative.",
                )
            val moduleRoot = resolveInsideWorkspace(workspace, module.root)
                ?: evidenceFailure(
                    "java.maven.moveClass.sourceInventory.manifest.invalid",
                    "The expected source-inventory module root escapes the workspace.",
                )
            val relativeModuleRoot = workspace.relativize(moduleRoot).normalize()
            evidenceRequire(
                path.startsWith(relativeModuleRoot) && path.fileName?.toString()?.endsWith(".java") == true,
                "java.maven.moveClass.sourceInventory.manifest.invalid",
                "The expected source-inventory path is outside its owner module or is not Java source.",
            )
            val expectedHash = manifest.properties.required("contentSha256").lowercase()
            evidenceRequire(
                JAVA_MOVE_GUIDANCE_SHA256_PATTERN.matches(expectedHash),
                "java.maven.moveClass.sourceInventory.manifest.invalid",
                "The expected source-inventory content identity is not SHA-256.",
            )
            val absolute = resolveInsideWorkspace(workspace, path)
                ?: evidenceFailure(
                    "java.maven.moveClass.sourceInventory.path.invalid",
                    "The expected source-inventory file escapes the workspace.",
                )
            val bytes = stableFileBytes(absolute, MAX_SOURCE_BYTES)
                ?: evidenceFailure(
                    "java.maven.moveClass.sourceInventory.inputUnavailable",
                    "An expected source-inventory file is missing, unreadable, unsafe, or unstable.",
                )
            val content = decodeUtf8(bytes)
                ?: evidenceFailure(
                    "java.maven.moveClass.sourceInventory.encoding.invalid",
                    "An expected source-inventory file is not valid UTF-8.",
                )
            val observedHash = javaMoveGuidanceSha256(bytes)
            val inventoried = inventory[path]
            if (inventoried != null) {
                // A present entry remains subject to ordinary semantic analysis. The expected manifest
                // can detect inventory loss but cannot grant authority or replace broad parse-failure fallback.
                evidenceRequire(
                    inventoried.languageId == "java" &&
                        javaMoveGuidanceSha256(inventoried.content.toByteArray(Charsets.UTF_8)) == observedHash,
                    "java.maven.moveClass.sourceInventory.snapshot.drifted",
                    "The observed source-inventory entry does not match the readable expected file.",
                )
            } else {
                evidenceRequire(
                    observedHash == expectedHash,
                    "java.maven.moveClass.sourceInventory.expectedEvidence.drifted",
                    "An omitted expected source no longer matches its manifest content identity.",
                )
            }
            JavaMoveClassGuidanceExpectedSource(
                path = path,
                mavenModule = ownerModule,
                sourceSet = sourceSet,
                content = content,
                contentSha256 = observedHash,
                manifestPath = manifest.path,
                manifestContentSha256 = manifest.contentSha256,
                expectedContentSha256 = expectedHash,
                observedInSourceInventory = inventoried != null,
            )
        }
    }

    fun classpathFingerprintBlockers(
        snapshot: ProjectSnapshot,
        model: BuildModel,
        targetCandidateSourceSets: Set<String>,
    ): JavaMoveClassGuidanceEvidenceResult<List<JavaMoveClassGuidanceBlocker>> = evidenceResult {
        val workspace = snapshot.workspace.root.toAbsolutePath().normalize()
        buildList {
            snapshot.classpathEvidence.filter { it.kind == ClasspathEvidenceKind.SYSTEM_PATH_ARTIFACT }
                .sortedBy { it.path.toString() }
                .forEach { evidence ->
                    val absolute = resolveInsideWorkspace(workspace, evidence.path)
                        ?: evidenceFailure(
                            "java.maven.moveClass.classpathEvidence.path.invalid",
                            "A system-path artifact escapes the guidance workspace.",
                        )
                    val affected = model.modules.flatMap { module ->
                        module.sourceSets.filter { sourceSet ->
                            "${module.id}:${sourceSet.id}" in targetCandidateSourceSets &&
                                sourceSet.classpathEntries.any { entry ->
                                    resolveInsideWorkspace(workspace, entry) == absolute
                                }
                        }.map { sourceSet -> module.id to sourceSet.id }
                    }.distinct().sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
                    if (affected.isEmpty()) return@forEach
                    if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return@forEach
                    val artifactBytes = stableFileBytes(absolute, MAX_ARTIFACT_BYTES)
                        ?: evidenceFailure(
                            "java.maven.moveClass.classpathEvidence.inputUnavailable",
                            "A system-path artifact is unreadable, unsafe, or unstable.",
                        )
                    val manifestPath = absolute.resolveSibling("${absolute.fileName}.refactorkit-evidence")
                    if (!Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) return@forEach
                    val manifestBytes = stableFileBytes(manifestPath, MAX_EVIDENCE_BYTES)
                        ?: evidenceFailure(
                            "java.maven.moveClass.classpathEvidence.manifest.invalid",
                            "A classpath evidence manifest is unreadable, unsafe, or unstable.",
                        )
                    val manifestContent = decodeUtf8(manifestBytes)
                        ?: evidenceFailure(
                            "java.maven.moveClass.classpathEvidence.manifest.invalid",
                            "A classpath evidence manifest is not valid UTF-8.",
                        )
                    val manifest = loadStrictClasspathManifest(manifestContent)
                    val expected = manifest.artifactSha256
                    val observed = javaMoveGuidanceSha256(artifactBytes)
                    if (expected == observed) return@forEach
                    val relativeArtifact = workspace.relativize(absolute).normalize()
                    val relativeManifest = workspace.relativize(manifestPath).normalize()
                    affected.forEach { (module, sourceSet) ->
                        add(JavaMoveClassGuidanceBlocker.SystemPathArtifactFingerprintMismatch(
                            module,
                            sourceSet,
                            relativeArtifact,
                            relativeManifest,
                            javaMoveGuidanceSha256(manifestBytes),
                            expected,
                            observed,
                        ))
                    }
                }
        }
    }

    fun generatedInventoryBlockers(
        snapshot: ProjectSnapshot,
        model: BuildModel,
    ): JavaMoveClassGuidanceEvidenceResult<List<JavaMoveClassGuidanceBlocker>> = evidenceResult {
        val workspace = snapshot.workspace.root.toAbsolutePath().normalize()
        buildList {
            model.modules.sortedBy { it.id }.forEach { module ->
                val manifest = loadSnapshotManifest(
                    snapshot,
                    module.root,
                    GENERATED_INVENTORY_FILE,
                    GENERATED_MANIFEST_KEYS,
                ) ?: return@forEach
                evidenceRequire(
                    manifest.properties.getProperty("formatVersion") == GENERATED_INVENTORY_FORMAT &&
                        manifest.properties.getProperty("ownerModule") == module.id,
                    "java.maven.moveClass.generatedRoot.manifest.invalid",
                    "The generated-root manifest format or owner is invalid.",
                )
                val sourceSetId = manifest.properties.required("sourceSet")
                val sourceSet = module.sourceSets.singleOrNull { it.id == sourceSetId }
                    ?: evidenceFailure(
                        "java.maven.moveClass.generatedRoot.manifest.invalid",
                        "The generated-root manifest names an unknown Maven source set.",
                    )
                val root = parseSafeRelative(manifest.properties.required("root"))
                    ?: evidenceFailure(
                        "java.maven.moveClass.generatedRoot.manifest.invalid",
                        "The generated-root manifest path is not safely workspace-relative.",
                    )
                evidenceRequire(
                    root in sourceSet.generatedSourceRoots.map(Path::normalize),
                    "java.maven.moveClass.generatedRoot.manifest.invalid",
                    "The generated-root manifest path is not owned by its named source set.",
                )
                val expected = manifest.properties.required("inventorySha256").lowercase()
                evidenceRequire(
                    JAVA_MOVE_GUIDANCE_SHA256_PATTERN.matches(expected),
                    "java.maven.moveClass.generatedRoot.manifest.invalid",
                    "The expected generated-root inventory identity is not SHA-256.",
                )
                val absoluteRoot = resolveInsideWorkspace(workspace, root)
                    ?: evidenceFailure(
                        "java.maven.moveClass.generatedRoot.path.invalid",
                        "The materialized generated root escapes the workspace.",
                    )
                val observed = generatedRootInventorySha256(absoluteRoot)
                    ?: evidenceFailure(
                        "java.maven.moveClass.generatedRoot.inputUnavailable",
                        "The materialized generated root is missing, unreadable, unsafe, or unbounded.",
                    )
                if (expected != observed) {
                    add(JavaMoveClassGuidanceBlocker.MaterializedGeneratedRootInventoryFingerprintMismatch(
                        module.id,
                        sourceSet.id,
                        root,
                        manifest.path,
                        manifest.contentSha256,
                        expected,
                        observed,
                    ))
                }
            }
        }
    }

    fun collectNonJavaResiduals(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
    ): JavaMoveClassGuidanceEvidenceResult<List<ResidualTextOccurrence>> = evidenceResult {
        val workspace = snapshot.workspace.root.toAbsolutePath().normalize()
        val residuals = mutableListOf<ResidualTextOccurrence>()
        var examined = 0
        var failure: EvidenceFailure? = null
        Files.walkFileTree(workspace, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(dir)) {
                    failure = EvidenceFailure(
                        "java.maven.moveClass.residualEnumeration.unsafe",
                        "A symbolic link prevents bounded residual enumeration.",
                    )
                    return FileVisitResult.TERMINATE
                }
                if (dir != workspace && dir.fileName?.toString().orEmpty() in snapshot.ignoredDirectories) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
                val relative = workspace.relativize(file.toAbsolutePath().normalize()).normalize()
                if (!javaMoveGuidanceIsSafeRelative(relative) || !isResidualText(relative)) {
                    return FileVisitResult.CONTINUE
                }
                if (examined >= MAX_RESIDUAL_FILES || attrs.size() > MAX_RESIDUAL_BYTES) {
                    failure = EvidenceFailure(
                        "java.maven.moveClass.residualEnumeration.unbounded",
                        "The residual text inventory exceeds the bounded guidance limit.",
                    )
                    return FileVisitResult.TERMINATE
                }
                examined += 1
                val bytes = stableFileBytes(file, MAX_RESIDUAL_BYTES) ?: run {
                    failure = EvidenceFailure(
                        "java.maven.moveClass.residualEnumeration.unstable",
                        "A residual text file changed or became unreadable during evidence capture.",
                    )
                    return FileVisitResult.TERMINATE
                }
                val content = decodeUtf8(bytes) ?: return FileVisitResult.CONTINUE
                val contentHash = javaMoveGuidanceSha256(bytes)
                allOccurrences(content, symbolFqn).forEach { range ->
                    residuals += ResidualTextOccurrence(relative, content, contentHash, range)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                failure = EvidenceFailure(
                    "java.maven.moveClass.residualEnumeration.unreadable",
                    "A residual text file could not be read during bounded enumeration.",
                )
                return FileVisitResult.TERMINATE
            }
        })
        failure?.let { throw it }
        residuals
    }

    data class ResidualTextOccurrence(
        val path: Path,
        val content: String,
        val contentSha256: String,
        val offsetRange: IntRange,
    )

    private fun loadSnapshotManifest(
        snapshot: ProjectSnapshot,
        moduleRoot: Path,
        fileName: String,
        expectedKeys: Set<String>,
    ): SnapshotManifest? {
        val workspace = snapshot.workspace.root.toAbsolutePath().normalize()
        val absoluteModuleRoot = resolveInsideWorkspace(workspace, moduleRoot)
            ?: evidenceFailure(
                "java.maven.moveClass.expectedEvidence.moduleRoot.invalid",
                "An expected-evidence module root escapes the workspace.",
            )
        val absolute = absoluteModuleRoot.resolve(fileName).normalize()
        val relative = workspace.relativize(absolute).normalize()
        val auxiliary = snapshot.auxiliaryFiles.singleOrNull { it.path.normalize() == relative }
        val exists = Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)
        if (!exists && auxiliary == null) return null
        val snapshotManifest = auxiliary ?: evidenceFailure(
            "java.maven.moveClass.expectedEvidence.snapshot.unbound",
            "An expected-evidence manifest is not exactly bound into the project snapshot.",
        )
        evidenceRequire(
            exists && snapshotManifest.languageId == "refactorkit-expected-evidence",
            "java.maven.moveClass.expectedEvidence.snapshot.unbound",
            "An expected-evidence manifest is not exactly bound into the project snapshot.",
        )
        val bytes = stableFileBytes(absolute, MAX_EVIDENCE_BYTES)
            ?: evidenceFailure(
                "java.maven.moveClass.expectedEvidence.manifest.invalid",
                "An expected-evidence manifest is unreadable, unsafe, unstable, or too large.",
            )
        val content = decodeUtf8(bytes)
            ?: evidenceFailure(
                "java.maven.moveClass.expectedEvidence.manifest.invalid",
                "An expected-evidence manifest is not valid UTF-8.",
            )
        evidenceRequire(
            content == snapshotManifest.content,
            "java.maven.moveClass.expectedEvidence.snapshot.drifted",
            "An expected-evidence manifest changed after snapshot capture.",
        )
        val properties = loadStrictProperties(content)
        evidenceRequire(
            properties.stringPropertyNames() == expectedKeys,
            "java.maven.moveClass.expectedEvidence.manifest.invalid",
            "An expected-evidence manifest has missing or unsupported fields.",
        )
        return SnapshotManifest(relative, javaMoveGuidanceSha256(bytes), properties)
    }

    private fun generatedRootInventorySha256(root: Path): String? {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return null
        val files = mutableListOf<Path>()
        var unsafe = false
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(dir)) {
                    unsafe = true
                    return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || Files.isSymbolicLink(file) || attrs.size() > MAX_SOURCE_BYTES ||
                    files.size >= MAX_SOURCE_FILES
                ) {
                    unsafe = true
                    return FileVisitResult.TERMINATE
                }
                files.add(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                unsafe = true
                return FileVisitResult.TERMINATE
            }
        })
        if (unsafe) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(GENERATED_INVENTORY_MARKER.toByteArray(Charsets.UTF_8))
        files.sortedBy { root.relativize(it).invariantSeparatorsPathString }.forEach { file ->
            val bytes = stableFileBytes(file, MAX_SOURCE_BYTES) ?: return null
            digest.update(root.relativize(file).invariantSeparatorsPathString.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(javaMoveGuidanceSha256(bytes).toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun stableFileBytes(path: Path, maxBytes: Long): ByteArray? {
        val before = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return null
        if (!before.isRegularFile || Files.isSymbolicLink(path) || before.size() > maxBytes) return null
        val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
        val after = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return null
        if (before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
            before.fileKey() != after.fileKey() || bytes.size.toLong() != after.size()
        ) return null
        return bytes
    }

    private fun loadStrictClasspathManifest(content: String): ClasspathEvidenceManifest {
        var artifactSha256: String? = null
        var providedTypeCount = 0
        content.lineSequence().forEach { line ->
            when {
                line.isBlank() -> Unit
                line.startsWith("artifactSha256=") -> {
                    evidenceRequire(
                        artifactSha256 == null,
                        "java.maven.moveClass.classpathEvidence.manifest.invalid",
                        "A classpath evidence manifest repeats a singleton artifact identity.",
                    )
                    val value = line.substringAfter('=').trim().lowercase()
                    evidenceRequire(
                        JAVA_MOVE_GUIDANCE_SHA256_PATTERN.matches(value),
                        "java.maven.moveClass.classpathEvidence.manifest.invalid",
                        "A classpath evidence manifest contains an invalid artifact identity.",
                    )
                    artifactSha256 = value
                }
                line.startsWith("providedType=") -> {
                    providedTypeCount += 1
                    val value = line.substringAfter('=').trim()
                    evidenceRequire(
                        providedTypeCount <= MAX_CLASSPATH_PROVIDED_TYPES &&
                            CLASSPATH_PROVIDED_TYPE_PATTERN.matches(value),
                        "java.maven.moveClass.classpathEvidence.manifest.invalid",
                        "A classpath evidence manifest contains invalid or unbounded provided-type evidence.",
                    )
                }
                else -> evidenceFailure(
                    "java.maven.moveClass.classpathEvidence.manifest.invalid",
                    "A classpath evidence manifest contains an unsupported record.",
                )
            }
        }
        return ClasspathEvidenceManifest(
            artifactSha256 = artifactSha256 ?: evidenceFailure(
                "java.maven.moveClass.classpathEvidence.manifest.invalid",
                "A classpath evidence manifest is missing its artifact identity.",
            ),
        )
    }

    private fun loadStrictProperties(content: String): Properties = runCatching {
        StrictProperties().apply { StringReader(content).use(::load) }
    }.getOrElse {
        evidenceFailure(
            "java.maven.moveClass.expectedEvidence.manifest.invalid",
            "An expected-evidence properties manifest cannot be parsed unambiguously.",
        )
    }

    private fun Properties.required(key: String): String = getProperty(key)?.trim()?.takeIf(String::isNotBlank)
        ?: evidenceFailure(
            "java.maven.moveClass.expectedEvidence.manifest.invalid",
            "An expected-evidence manifest field is blank.",
        )

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun resolveInsideWorkspace(workspace: Path, path: Path): Path? {
        val absolute = (if (path.isAbsolute) path else workspace.resolve(path)).toAbsolutePath().normalize()
        return absolute.takeIf { it.startsWith(workspace) }
    }

    private fun parseSafeRelative(value: String): Path? = runCatching { Path.of(value).normalize() }
        .getOrNull()?.takeIf(::javaMoveGuidanceIsSafeRelative)

    private fun allOccurrences(content: String, text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var offset = 0
        while (true) {
            val found = content.indexOf(text, offset)
            if (found < 0) return ranges
            ranges += found until found + text.length
            offset = found + text.length
        }
    }

    private fun isResidualText(path: Path): Boolean = path.fileName?.toString().orEmpty()
        .substringAfterLast('.', missingDelimiterValue = "").lowercase() in RESIDUAL_TEXT_EXTENSIONS

    private inline fun <T> evidenceResult(block: () -> T): JavaMoveClassGuidanceEvidenceResult<T> = try {
        JavaMoveClassGuidanceEvidenceResult.Valid(block())
    } catch (failure: EvidenceFailure) {
        JavaMoveClassGuidanceEvidenceResult.Invalid(failure.code, failure.summary)
    }

    private fun evidenceRequire(condition: Boolean, code: String, summary: String) {
        if (!condition) evidenceFailure(code, summary)
    }

    private fun evidenceFailure(code: String, summary: String): Nothing = throw EvidenceFailure(code, summary)

    private data class SourceRoot(
        val mavenModule: String,
        val sourceSet: String,
        val root: Path,
    )

    private data class SnapshotManifest(
        val path: Path,
        val contentSha256: String,
        val properties: Properties,
    )

    private data class ClasspathEvidenceManifest(
        val artifactSha256: String,
    )

    private class StrictProperties : Properties() {
        override fun put(key: Any, value: Any): Any? {
            if (containsKey(key)) throw IllegalArgumentException("duplicate properties key")
            return super.put(key, value)
        }
    }

    private class EvidenceFailure(
        val code: String,
        val summary: String,
    ) : RuntimeException(summary)

    private val SOURCE_MANIFEST_KEYS = setOf(
        "formatVersion",
        "ownerModule",
        "sourceSet",
        "path",
        "contentSha256",
    )
    private val GENERATED_MANIFEST_KEYS = setOf(
        "formatVersion",
        "ownerModule",
        "sourceSet",
        "root",
        "inventorySha256",
    )
    private val CLASSPATH_PROVIDED_TYPE_PATTERN =
        Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
    private const val MAX_CLASSPATH_PROVIDED_TYPES = 4_096
    private val RESIDUAL_TEXT_EXTENSIONS = setOf(
        "adoc",
        "csv",
        "feature",
        "html",
        "json",
        "md",
        "properties",
        "txt",
        "xml",
        "yaml",
        "yml",
    )
}
