package org.refactorkit.cli.packagedmavenmodulerename

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import java.util.spi.ToolProvider
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal data class RepositoryIdentity(
    val commit: String,
    val tree: String,
    val worktreeState: String,
    val statusSha256: String,
    val statusEntryCount: Int,
    val githubSha: String,
    val githubShaPresent: Boolean,
    val qualificationMode: String,
    val cleanExactCandidateRequired: Boolean,
    val cleanExactCandidateSatisfied: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("commit", commit)
        put("tree", tree)
        put("worktreeState", worktreeState)
        put("statusSha256", statusSha256)
        put("statusEntryCount", statusEntryCount)
        put("githubSha", githubSha)
        put("githubShaPresent", githubShaPresent)
        put("qualificationMode", qualificationMode)
        put("cleanExactCandidateRequired", cleanExactCandidateRequired)
        put("cleanExactCandidateSatisfied", cleanExactCandidateSatisfied)
    }
}

internal data class CandidateArchiveIdentity(
    val archiveName: String,
    val archiveSha256: String,
    val checksumSha256: String,
    val verifierPlatform: String,
    val verifierRecordSha256: String,
    val verifierCompleteLogSha256: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("archiveName", archiveName)
        put("archiveSha256", archiveSha256)
        put("checksumSha256", checksumSha256)
        put("verifierPlatform", verifierPlatform)
        put("verifierRecordSha256", verifierRecordSha256)
        put("verifierCompleteLogSha256", verifierCompleteLogSha256)
    }
}

internal data class BuildJdkIdentity(
    val vendor: String,
    val version: String,
    val runtimeName: String,
    val runtimeVersion: String,
    val vmVendor: String,
    val vmName: String,
    val vmVersion: String,
    val javaHomePathSha256: String,
    val releaseSha256: String,
    val javaExecutableSha256: String,
    val modulesImageSha256: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("vendor", vendor)
        put("version", version)
        put("runtimeName", runtimeName)
        put("runtimeVersion", runtimeVersion)
        put("vmVendor", vmVendor)
        put("vmName", vmName)
        put("vmVersion", vmVersion)
        put("javaHomePathSha256", javaHomePathSha256)
        put("releaseSha256", releaseSha256)
        put("javaExecutableSha256", javaExecutableSha256)
        put("modulesImageSha256", modulesImageSha256)
    }
}

internal data class CandidateRuntimeIdentity(
    val javaVersion: String,
    val releaseSha256: String,
    val javaExecutableSha256: String,
    val modulesImageSha256: String,
    val buildJdkVersionMatched: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("javaVersion", javaVersion)
        put("releaseSha256", releaseSha256)
        put("javaExecutableSha256", javaExecutableSha256)
        put("modulesImageSha256", modulesImageSha256)
        put("buildJdkVersionMatched", buildJdkVersionMatched)
    }
}

internal fun observeCandidateArchiveIdentity(
    archive: Path,
    checksumSidecar: Path,
    expectedPlatform: String,
): CandidateArchiveIdentity {
    val normalizedArchive = archive.toAbsolutePath().normalize()
    val normalizedChecksum = checksumSidecar.toAbsolutePath().normalize()
    require(Files.isRegularFile(normalizedArchive, LinkOption.NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(normalizedArchive)) {
        "Candidate archive is missing or is not a no-follow regular file"
    }
    require(Files.isRegularFile(normalizedChecksum, LinkOption.NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(normalizedChecksum)) {
        "Candidate archive checksum is missing or is not a no-follow regular file"
    }
    require(expectedPlatform.isNotBlank()) { "Candidate verifier platform identity is missing" }

    val actualArchiveSha256 = sha256(normalizedArchive)
    val checksumText = decodeUtf8Strict(Files.readAllBytes(normalizedChecksum), "candidate checksum")
    val checksumMatch = Regex("([0-9a-f]{64})  ([^/\\\\\\r\\n]+)(?:\\r?\\n)").matchEntire(checksumText)
        ?: error("Candidate archive checksum must contain exactly one hash and archive name")
    val checksumArchiveSha256 = checksumMatch.groupValues[1]
    val checksumArchiveName = checksumMatch.groupValues[2]
    check(checksumArchiveName == normalizedArchive.fileName.toString()) {
        "Candidate checksum archive name differs from the actual ZIP"
    }
    check(checksumArchiveSha256 == actualArchiveSha256) {
        "Candidate ZIP SHA-256 differs from its checksum file"
    }
    val checksumSha256 = sha256(normalizedChecksum)

    val evidenceRoot = requireNotNull(normalizedChecksum.parent?.parent) {
        "Candidate checksum has no qualification evidence root"
    }
    val verifierRoot = evidenceRoot.resolve("verifier").normalize()
    require(verifierRoot.startsWith(evidenceRoot)) { "Verifier evidence path escaped its qualification root" }
    val verifierRecord = verifierRoot.resolve("runtime-archive-verifier-status.properties")
    val verifierCompleteLog = verifierRoot.resolve("runtime-archive-verifier-complete.log")
    val verifier = readExactKeyValueRecord(
        verifierRecord,
        setOf("schemaVersion", "platform", "exitCode", "archiveSha256", "checksumSha256"),
    )
    check(verifier.getValue("schemaVersion") == "1") { "Unsupported verifier record schema" }
    check(verifier.getValue("platform") == expectedPlatform) {
        "Verifier record platform differs from the current native host"
    }
    check(verifier.getValue("exitCode") == "0") { "Runtime archive verifier did not pass" }
    check(verifier.getValue("archiveSha256") == actualArchiveSha256) {
        "Candidate ZIP SHA-256 differs from the verifier record"
    }
    check(verifier.getValue("checksumSha256") == checksumSha256) {
        "Candidate checksum SHA-256 differs from the verifier record"
    }
    require(Files.isRegularFile(verifierCompleteLog, LinkOption.NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(verifierCompleteLog)) {
        "Runtime archive verifier complete log is missing or is not a no-follow regular file"
    }
    val verifierLogText = decodeUtf8Strict(Files.readAllBytes(verifierCompleteLog), "verifier complete log")
    listOf(
        "schemaVersion=1",
        "platform=$expectedPlatform",
        "archiveSha256=$actualArchiveSha256",
        "checksumSha256=$checksumSha256",
        "exitCode=0",
    ).forEach { exactLine ->
        check(verifierLogText.lineSequence().any { it == exactLine }) {
            "Runtime archive verifier complete log omitted $exactLine"
        }
    }

    return CandidateArchiveIdentity(
        archiveName = checksumArchiveName,
        archiveSha256 = actualArchiveSha256,
        checksumSha256 = checksumSha256,
        verifierPlatform = expectedPlatform,
        verifierRecordSha256 = sha256(verifierRecord),
        verifierCompleteLogSha256 = sha256(verifierCompleteLog),
    )
}

internal fun observeBuildJdkIdentity(): BuildJdkIdentity {
    check(Runtime.version().feature() == 21) { "Packaged qualification build identity requires JDK 21" }
    val javaHome = Path.of(requiredSystemProperty("java.home")).toRealPath()
    val release = requiredRuntimeFile(javaHome.resolve("release"), "build JDK release file")
    val javaExecutable = requiredRuntimeFile(
        javaHome.resolve("bin").resolve(
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java",
        ),
        "build JDK Java executable",
    )
    val modules = requiredRuntimeFile(javaHome.resolve("lib/modules"), "build JDK modules image")
    val version = requiredSystemProperty("java.version")
    check(version.startsWith("21.")) { "Build JDK version is not an exact JDK 21 identity: $version" }
    return BuildJdkIdentity(
        vendor = requiredSystemProperty("java.vendor"),
        version = version,
        runtimeName = requiredSystemProperty("java.runtime.name"),
        runtimeVersion = requiredSystemProperty("java.runtime.version"),
        vmVendor = requiredSystemProperty("java.vm.vendor"),
        vmName = requiredSystemProperty("java.vm.name"),
        vmVersion = requiredSystemProperty("java.vm.version"),
        javaHomePathSha256 = sha256(javaHome.toString().toByteArray(StandardCharsets.UTF_8)),
        releaseSha256 = sha256(release),
        javaExecutableSha256 = sha256(javaExecutable),
        modulesImageSha256 = sha256(modules),
    )
}

internal fun observeCandidateRuntimeIdentity(
    candidateRoot: Path,
    embeddedJava: Path,
    buildJdk: BuildJdkIdentity,
): CandidateRuntimeIdentity {
    val normalizedRoot = candidateRoot.toAbsolutePath().normalize()
    val release = requiredCandidateRuntimeFile(normalizedRoot, "runtime/release")
    val modules = requiredCandidateRuntimeFile(normalizedRoot, "runtime/lib/modules")
    val normalizedEmbeddedJava = embeddedJava.toAbsolutePath().normalize()
    require(normalizedEmbeddedJava.startsWith(normalizedRoot)) {
        "Candidate embedded Java escaped the extracted runtime"
    }
    requiredRuntimeFile(normalizedEmbeddedJava, "candidate embedded Java executable")
    val releaseValues = readReleaseRecord(release)
    val javaVersion = requireNotNull(releaseValues["JAVA_VERSION"]) {
        "Candidate runtime release record omitted JAVA_VERSION"
    }
    check(javaVersion == buildJdk.version) {
        "Candidate runtime version differs from the exact build JDK version"
    }
    require(!releaseValues["MODULES"].isNullOrBlank()) {
        "Candidate runtime release record omitted its exact module identity"
    }
    return CandidateRuntimeIdentity(
        javaVersion = javaVersion,
        releaseSha256 = sha256(release),
        javaExecutableSha256 = sha256(normalizedEmbeddedJava),
        modulesImageSha256 = sha256(modules),
        buildJdkVersionMatched = true,
    )
}

private fun requiredCandidateRuntimeFile(root: Path, relative: String): Path {
    val candidate = root.resolve(relative).normalize()
    require(candidate.startsWith(root)) { "Candidate runtime identity path escaped its root" }
    return requiredRuntimeFile(candidate, "candidate runtime $relative")
}

private fun requiredRuntimeFile(path: Path, description: String): Path {
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
        "$description is missing or is not a no-follow regular file"
    }
    return path
}

private fun requiredSystemProperty(name: String): String = System.getProperty(name)
    ?.takeIf(String::isNotBlank)
    ?: error("Required build JDK identity property is missing: $name")

private fun readExactKeyValueRecord(path: Path, expectedKeys: Set<String>): Map<String, String> {
    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
        "Required verifier record is missing or is not a no-follow regular file"
    }
    val raw = decodeUtf8Strict(Files.readAllBytes(path), "verifier status record")
    val normalized = raw.replace("\r\n", "\n")
    require('\r' !in normalized && normalized.endsWith('\n')) {
        "Verifier record has an invalid line boundary"
    }
    val lines = normalized.removeSuffix("\n").split('\n')
    require(lines.isNotEmpty() && lines.none(String::isBlank)) { "Verifier record contains a missing field" }
    val values = linkedMapOf<String, String>()
    lines.forEach { line ->
        val separator = line.indexOf('=')
        require(separator > 0 && separator < line.lastIndex) { "Verifier record contains an invalid field" }
        val key = line.substring(0, separator)
        val value = line.substring(separator + 1)
        require(values.put(key, value) == null) { "Verifier record repeats field $key" }
    }
    require(values.keys == expectedKeys) { "Verifier record fields differ from the exact required identity" }
    return values
}

private fun readReleaseRecord(path: Path): Map<String, String> {
    val text = decodeUtf8Strict(Files.readAllBytes(path), "candidate runtime release record")
    val values = linkedMapOf<String, String>()
    text.lineSequence().filter(String::isNotBlank).forEach { line ->
        val separator = line.indexOf('=')
        require(separator > 0 && separator < line.lastIndex) { "Candidate runtime release field is invalid" }
        val key = line.substring(0, separator)
        val rawValue = line.substring(separator + 1)
        require(rawValue.length >= 2 && rawValue.first() == '"' && rawValue.last() == '"') {
            "Candidate runtime release value is not quoted"
        }
        require(values.put(key, rawValue.substring(1, rawValue.lastIndex)) == null) {
            "Candidate runtime release field is duplicated: $key"
        }
    }
    require(values.isNotEmpty()) { "Candidate runtime release identity is missing" }
    return values
}

internal fun observeRepositoryIdentity(repositoryRoot: Path): RepositoryIdentity {
    val normalizedRoot = repositoryRoot.toAbsolutePath().normalize()
    val observedRoot = gitText(normalizedRoot, "rev-parse", "--show-toplevel")
    assertTrue(
        Files.isSameFile(normalizedRoot, Path.of(observedRoot)),
        "Qualification repository root does not equal Git's top-level directory",
    )
    val commit = gitText(normalizedRoot, "rev-parse", "--verify", "HEAD")
    val tree = gitText(normalizedRoot, "rev-parse", "--verify", "HEAD^{tree}")
    assertTrue(commit.matches(Regex("[0-9a-f]{40}")))
    assertTrue(tree.matches(Regex("[0-9a-f]{40}")))

    val status = gitBytes(
        normalizedRoot,
        "status",
        "--porcelain=v1",
        "-z",
        "--untracked-files=all",
    )
    val clean = status.isEmpty()
    val githubShaValue = System.getenv("GITHUB_SHA")?.trim().orEmpty()
    val githubShaPresent = githubShaValue.isNotEmpty()
    if (githubShaPresent) {
        assertTrue(githubShaValue.matches(Regex("[0-9a-fA-F]{40}")))
        assertEquals(
            commit,
            githubShaValue.lowercase(Locale.ROOT),
            "GITHUB_SHA does not identify the exact runtime-observed candidate commit",
        )
    }
    val nativeQualification = System.getenv("GITHUB_ACTIONS").equals("true", ignoreCase = true) ||
        System.getProperty("refactorkit.packaged.module.rename.nativeQualification")
            .equals("true", ignoreCase = true)
    if (nativeQualification) {
        assertTrue(githubShaPresent, "Native qualification requires an exact GITHUB_SHA candidate identity")
        assertTrue(clean, "Native qualification requires a clean exact candidate worktree")
    }
    return RepositoryIdentity(
        commit = commit,
        tree = tree,
        worktreeState = if (clean) "CLEAN" else "DIRTY",
        statusSha256 = sha256(status),
        statusEntryCount = status.count { it == 0.toByte() },
        githubSha = if (githubShaPresent) githubShaValue.lowercase(Locale.ROOT) else "ABSENT",
        githubShaPresent = githubShaPresent,
        qualificationMode = if (nativeQualification) "NATIVE_CI" else "LOCAL_PRELIMINARY",
        cleanExactCandidateRequired = nativeQualification,
        cleanExactCandidateSatisfied = !nativeQualification || (clean && githubShaPresent),
    )
}

private fun gitText(repositoryRoot: Path, vararg arguments: String): String =
    decodeUtf8Strict(gitBytes(repositoryRoot, *arguments), "git ${arguments.joinToString(" ")} output")
        .trimEnd('\r', '\n')
        .also { require(it.isNotBlank()) { "Git command returned no identity" } }

private fun gitBytes(repositoryRoot: Path, vararg arguments: String): ByteArray {
    val command = listOf("git", "-C", repositoryRoot.toString()) + arguments
    val process = ProcessBuilder(command)
        .redirectErrorStream(false)
        .apply {
            environment()["GIT_OPTIONAL_LOCKS"] = "0"
            environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            environment()["GIT_CONFIG_GLOBAL"] = if (
                System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            ) "NUL" else "/dev/null"
        }
        .start()
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val stdoutThread = Thread({ process.inputStream.use { it.copyTo(stdout) } }, "qualification-git-stdout")
    val stderrThread = Thread({ process.errorStream.use { it.copyTo(stderr) } }, "qualification-git-stderr")
    stdoutThread.start()
    stderrThread.start()
    val completed = process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
    if (!completed) {
        process.destroyForcibly()
        process.waitFor()
    }
    stdoutThread.join(GIT_DRAIN_TIMEOUT.toMillis())
    stderrThread.join(GIT_DRAIN_TIMEOUT.toMillis())
    check(completed && !stdoutThread.isAlive && !stderrThread.isAlive) {
        "Git repository identity command exceeded its fail-closed timeout"
    }
    val stdoutBytes = stdout.toByteArray()
    val stderrBytes = stderr.toByteArray()
    check(stdoutBytes.size <= GIT_OUTPUT_LIMIT && stderrBytes.size <= GIT_OUTPUT_LIMIT) {
        "Git repository identity output exceeded its bounded capture"
    }
    check(process.exitValue() == 0) {
        "Git repository identity command failed: ${decodeUtf8Strict(stderrBytes, "git stderr").take(4096)}"
    }
    return stdoutBytes
}

private val GIT_TIMEOUT: Duration = Duration.ofSeconds(30)
private val GIT_DRAIN_TIMEOUT: Duration = Duration.ofSeconds(5)
private const val GIT_OUTPUT_LIMIT: Int = 8 * 1024 * 1024

internal data class CandidateReadOnlyEvidence(
    val strategy: String,
    val archiveSha256Before: String,
    val archiveSha256After: String,
    val bytePathIdentityBefore: String,
    val bytePathIdentityAfter: String,
    val hardenedTreeSha256: String,
    val regularFileCount: Int,
    val directoryCount: Int,
    val allRegularFilesReadOnly: Boolean,
    val allDirectoriesReadOnly: Boolean,
    val executeBitsPreserved: Boolean,
    val dosReadOnlyEntryCount: Int,
    val windowsAclDenyEntryCount: Int,
    val regularFileMutationDenied: Boolean,
    val regularFileMutationFailure: String,
    val directoryMutationDenied: Boolean,
    val directoryMutationFailure: String,
    val regularFileProbePath: String,
    val directoryProbePath: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("strategy", strategy)
        put("archiveSha256Before", archiveSha256Before)
        put("archiveSha256After", archiveSha256After)
        put("bytePathIdentityBefore", bytePathIdentityBefore)
        put("bytePathIdentityAfter", bytePathIdentityAfter)
        put("hardenedTreeSha256", hardenedTreeSha256)
        put("regularFileCount", regularFileCount)
        put("directoryCount", directoryCount)
        put("allRegularFilesReadOnly", allRegularFilesReadOnly)
        put("allDirectoriesReadOnly", allDirectoriesReadOnly)
        put("executeBitsPreserved", executeBitsPreserved)
        put("dosReadOnlyEntryCount", dosReadOnlyEntryCount)
        put("windowsAclDenyEntryCount", windowsAclDenyEntryCount)
        put("regularFileMutationDenied", regularFileMutationDenied)
        put("regularFileMutationFailure", regularFileMutationFailure)
        put("directoryMutationDenied", directoryMutationDenied)
        put("directoryMutationFailure", directoryMutationFailure)
        put("regularFileProbePath", regularFileProbePath)
        put("directoryProbePath", directoryProbePath)
    }
}

internal data class HardenedCandidateImage(
    val manifest: TreeManifest,
    val evidence: CandidateReadOnlyEvidence,
)

internal fun hardenCandidateImageReadOnly(candidateRoot: Path, archive: Path): HardenedCandidateImage {
    val root = candidateRoot.toAbsolutePath().normalize()
    require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root))
    val before = captureTreeManifest(root)
    require(before.entries.values.none { it.kind == ManifestPathKind.SYMBOLIC_LINK })
    val archiveBefore = sha256(archive)
    val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val hardening = if (windows) hardenWindowsCandidate(root, before) else hardenPosixCandidate(root, before)
    val hardened = captureTreeManifest(root)
    val archiveAfter = sha256(archive)

    assertEquals(archiveBefore, archiveAfter, "Candidate archive bytes changed while hardening extraction")
    assertEquals(
        bytePathIdentity(before),
        bytePathIdentity(hardened),
        "Extracted candidate bytes or path kinds changed while removing write authority",
    )
    assertEquals(0, posixWritableEntryCount(hardened))

    val regularProbe = hardened.entries.entries
        .singleOrNull { (path, entry) ->
            path.substringAfterLast('/').startsWith("refactorkit-jvm-") &&
                path.endsWith(".jar") && entry.kind == ManifestPathKind.REGULAR_FILE
        }
        ?.key
        ?: hardened.entries.entries.first { it.value.kind == ManifestPathKind.REGULAR_FILE }.key
    val directoryProbe = ".refactorkit-qualification-read-only-probe"
    val regularDenial = assertRegularFileMutationDenied(root.resolve(regularProbe))
    val directoryDenial = assertDirectoryMutationDenied(root.resolve(directoryProbe))
    val afterProbes = captureTreeManifest(root)
    assertEquals(hardened, afterProbes, "Read-only mutation probes changed the extracted candidate")
    assertEquals(archiveBefore, sha256(archive), "Read-only mutation probes changed the candidate archive")

    return HardenedCandidateImage(
        manifest = hardened,
        evidence = CandidateReadOnlyEvidence(
            strategy = if (windows) "windows-dos-read-only-plus-explicit-current-user-acl-deny" else
                "posix-remove-owner-group-others-write-preserve-execute",
            archiveSha256Before = archiveBefore,
            archiveSha256After = archiveAfter,
            bytePathIdentityBefore = bytePathIdentity(before),
            bytePathIdentityAfter = bytePathIdentity(hardened),
            hardenedTreeSha256 = hardened.sha256,
            regularFileCount = hardened.regularFileCount,
            directoryCount = hardened.directoryCount,
            allRegularFilesReadOnly = hardening.allRegularFilesReadOnly,
            allDirectoriesReadOnly = hardening.allDirectoriesReadOnly,
            executeBitsPreserved = hardening.executeBitsPreserved,
            dosReadOnlyEntryCount = hardening.dosReadOnlyEntryCount,
            windowsAclDenyEntryCount = hardening.windowsAclDenyEntryCount,
            regularFileMutationDenied = true,
            regularFileMutationFailure = regularDenial,
            directoryMutationDenied = true,
            directoryMutationFailure = directoryDenial,
            regularFileProbePath = regularProbe,
            directoryProbePath = directoryProbe,
        ),
    )
}

private data class HardeningResult(
    val allRegularFilesReadOnly: Boolean,
    val allDirectoriesReadOnly: Boolean,
    val executeBitsPreserved: Boolean,
    val dosReadOnlyEntryCount: Int,
    val windowsAclDenyEntryCount: Int,
)

private fun hardenPosixCandidate(root: Path, before: TreeManifest): HardeningResult {
    require(Files.getFileAttributeView(root, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null) {
        "POSIX qualification candidate has no POSIX permission view"
    }
    val writePermissions = setOf(
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.OTHERS_WRITE,
    )
    val paths = before.entries.keys
        .map { relative -> relative to if (relative == ".") root else root.resolve(relative) }
        .sortedByDescending { (_, path) -> path.nameCount }
    paths.forEach { (_, path) ->
        val permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        Files.setPosixFilePermissions(path, permissions - writePermissions)
    }
    val after = captureTreeManifest(root)
    val executeMaskPreserved = before.entries.all { (path, entry) ->
        val beforeMode = entry.mode ?: return@all false
        val afterMode = after.entries.getValue(path).mode ?: return@all false
        beforeMode.toInt(8) and EXECUTE_MASK == afterMode.toInt(8) and EXECUTE_MASK
    }
    return HardeningResult(
        allRegularFilesReadOnly = after.entries.filterValues { it.kind == ManifestPathKind.REGULAR_FILE }
            .values.all { entry -> requireNotNull(entry.mode).toInt(8) and WRITE_MASK == 0 },
        allDirectoriesReadOnly = after.entries.filterValues { it.kind == ManifestPathKind.DIRECTORY }
            .values.all { entry -> requireNotNull(entry.mode).toInt(8) and WRITE_MASK == 0 },
        executeBitsPreserved = executeMaskPreserved,
        dosReadOnlyEntryCount = 0,
        windowsAclDenyEntryCount = 0,
    )
}

private fun hardenWindowsCandidate(root: Path, before: TreeManifest): HardeningResult {
    val principal = root.fileSystem.userPrincipalLookupService.lookupPrincipalByName(
        System.getProperty("user.name"),
    )
    var dosCount = 0
    var aclCount = 0
    val paths = before.entries.keys
        .map { relative -> if (relative == ".") root else root.resolve(relative) }
        .sortedByDescending(Path::getNameCount)
    paths.forEach { path ->
        val dos = Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: error("Windows candidate path has no DOS attribute view")
        if (!dos.readAttributes().isReadOnly) dos.setReadOnly(true)
        assertTrue(dos.readAttributes().isReadOnly)
        dosCount += 1

        val aclView = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: error("Windows candidate path has no ACL view")
        val current = aclView.acl
        val alreadyDenied = current.any { entry ->
            entry.type() == AclEntryType.DENY && entry.principal() == principal &&
                entry.permissions().containsAll(WINDOWS_DENIED_WRITE_PERMISSIONS)
        }
        if (!alreadyDenied) {
            val denial = AclEntry.newBuilder()
                .setType(AclEntryType.DENY)
                .setPrincipal(principal)
                .setPermissions(WINDOWS_DENIED_WRITE_PERMISSIONS)
                .build()
            aclView.acl = listOf(denial) + current
        }
        assertTrue(aclView.acl.any { entry ->
            entry.type() == AclEntryType.DENY && entry.principal() == principal &&
                entry.permissions().containsAll(WINDOWS_DENIED_WRITE_PERMISSIONS)
        })
        aclCount += 1
    }
    return HardeningResult(
        allRegularFilesReadOnly = true,
        allDirectoriesReadOnly = true,
        executeBitsPreserved = true,
        dosReadOnlyEntryCount = dosCount,
        windowsAclDenyEntryCount = aclCount,
    )
}

private fun assertRegularFileMutationDenied(path: Path): String {
    try {
        Files.newByteChannel(path, StandardOpenOption.WRITE).use { }
    } catch (denied: Throwable) {
        require(isMutationDenial(denied)) { "Unexpected regular-file mutation failure: $denied" }
        return denied::class.java.simpleName
    }
    error("Extracted candidate regular file remained writable: ${path.fileName}")
}

private fun assertDirectoryMutationDenied(path: Path): String {
    try {
        Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { }
    } catch (denied: Throwable) {
        require(isMutationDenial(denied)) { "Unexpected directory mutation failure: $denied" }
        assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        return denied::class.java.simpleName
    }
    runCatching { Files.deleteIfExists(path) }
    error("Extracted candidate directory remained writable")
}

private fun isMutationDenial(failure: Throwable): Boolean =
    failure is AccessDeniedException || failure is FileSystemException || failure is SecurityException

private fun bytePathIdentity(manifest: TreeManifest): String {
    val bytes = buildString {
        manifest.entries.toSortedMap().forEach { (path, entry) ->
            append(path).append('\u0000')
            append(entry.kind.name).append('\u0000')
            append(entry.byteLength ?: "").append('\u0000')
            append(entry.sha256 ?: "").append('\u0000')
        }
    }.toByteArray(StandardCharsets.UTF_8)
    return sha256(bytes)
}

private fun posixWritableEntryCount(manifest: TreeManifest): Int = manifest.entries.values.count { entry ->
    entry.mode?.let { mode -> mode.toInt(8) and WRITE_MASK != 0 } ?: false
}

private const val WRITE_MASK: Int = 0b010_010_010
private const val EXECUTE_MASK: Int = 0b001_001_001

private val WINDOWS_DENIED_WRITE_PERMISSIONS = setOf(
    AclEntryPermission.WRITE_DATA,
    AclEntryPermission.APPEND_DATA,
    AclEntryPermission.WRITE_NAMED_ATTRS,
    AclEntryPermission.WRITE_ATTRIBUTES,
    AclEntryPermission.DELETE_CHILD,
    AclEntryPermission.DELETE,
    AclEntryPermission.WRITE_ACL,
    AclEntryPermission.WRITE_OWNER,
)

internal data class PackagedGateSelectorEvidence(
    val jvmJarPath: String,
    val jvmJarSha256: String,
    val archiveEntrySha256: String,
    val selectorClassPath: String,
    val selectorClassSha256: String,
    val selectorMethod: String,
    val selectorMethodDescriptor: String,
    val publicOperation: String,
    val diagnosticsGateId: String,
    val operationConstantOffset: Int,
    val equalityBranchTarget: Int,
    val gateConstantOffset: Int,
    val lazyAuthoritativeCallOffset: Int,
    val javapSelectorMethodSha256: String,
    val operationToGateMappingProofSha256: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("jvmJarPath", jvmJarPath)
        put("jvmJarSha256", jvmJarSha256)
        put("archiveEntrySha256", archiveEntrySha256)
        put("selectorClassPath", selectorClassPath)
        put("selectorClassSha256", selectorClassSha256)
        put("selectorMethod", selectorMethod)
        put("selectorMethodDescriptor", selectorMethodDescriptor)
        put("publicOperation", publicOperation)
        put("diagnosticsGateId", diagnosticsGateId)
        put("operationConstantOffset", operationConstantOffset)
        put("equalityBranchTarget", equalityBranchTarget)
        put("gateConstantOffset", gateConstantOffset)
        put("lazyAuthoritativeCallOffset", lazyAuthoritativeCallOffset)
        put("javapSelectorMethodSha256", javapSelectorMethodSha256)
        put("operationToGateMappingProofSha256", operationToGateMappingProofSha256)
        put("journalGateIdentityClaimed", false)
        put("identitySource", "hash-bound-packaged-selector-bytecode")
    }
}

internal fun inspectPackagedGateSelector(
    candidateRoot: Path,
    archive: Path,
    operation: String,
    gate: String,
): PackagedGateSelectorEvidence {
    val libraryRoot = candidateRoot.resolve("lib").normalize()
    require(libraryRoot.startsWith(candidateRoot) && Files.isDirectory(libraryRoot, LinkOption.NOFOLLOW_LINKS))
    val jvmJars = Files.list(libraryRoot).use { paths ->
        paths.filter { path ->
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path) &&
                path.fileName.toString().startsWith("refactorkit-jvm-") &&
                path.fileName.toString().endsWith(".jar")
        }.sorted().toList()
    }
    assertEquals(1, jvmJars.size, "Extracted candidate must contain exactly one refactorkit-jvm JAR")
    val jar = jvmJars.single()
    val relativeJar = candidateRoot.relativize(jar).invariantSeparatorsPathString
    val outerEntry = inspectArchiveEntry(archive, "refactorkit/$relativeJar")
    val jarSha256 = sha256(jar)
    assertEquals(outerEntry.sha256, jarSha256, "Extracted refactorkit-jvm JAR differs from its archive entry")

    val classBytes = JarFile(jar.toFile(), false).use { input ->
        val matching = input.entries().asSequence().filter { it.name == SELECTOR_CLASS_PATH }.toList()
        assertEquals(1, matching.size, "Packaged selector class is absent or duplicated")
        input.getInputStream(matching.single()).use { it.readAllBytes() }
    }
    assertTrue(classBytes.isNotEmpty())

    val javap = ToolProvider.findFirst("javap").orElseThrow {
        IllegalStateException("JDK 21 javap ToolProvider is required for packaged selector inspection")
    }
    val stdout = StringWriter()
    val stderr = StringWriter()
    val exit = javap.run(
        PrintWriter(stdout),
        PrintWriter(stderr),
        "-classpath",
        jar.toString(),
        "-c",
        "-p",
        "-s",
        "-constants",
        SELECTOR_CLASS_NAME,
    )
    assertEquals(0, exit, "JDK javap selector inspection failed: $stderr")
    assertTrue(stderr.toString().isBlank(), "JDK javap emitted selector-inspection stderr: $stderr")
    val normalizedOutput = stdout.toString().replace("\r\n", "\n")
    val methodStart = normalizedOutput.indexOf("select\$refactorkit_jvm(")
    assertTrue(methodStart >= 0, "Packaged selector implementation method is absent")
    val declarationStart = normalizedOutput.lastIndexOf("\n  public final", methodStart).let { index ->
        if (index < 0) 0 else index + 1
    }
    val methodEnd = normalizedOutput.indexOf("\n  private static", methodStart).let { index ->
        if (index < 0) normalizedOutput.length else index
    }
    val method = normalizedOutput.substring(declarationStart, methodEnd).trimEnd()
    assertTrue(method.contains("descriptor: $SELECTOR_DESCRIPTOR"))

    val lines = method.lines()
    val operationLineIndexes = lines.indices.filter { index ->
        lines[index].matches(instructionRegex("ldc(?:_w)?", "// String ${Regex.escape(operation)}"))
    }
    val gateLineIndexes = lines.indices.filter { index ->
        lines[index].matches(instructionRegex("ldc(?:_w)?", "// String ${Regex.escape(gate)}"))
    }
    assertEquals(1, operationLineIndexes.size, "Canonical operation constant is not unique in selector bytecode")
    assertEquals(1, gateLineIndexes.size, "Operation-owned gate constant is not unique in selector bytecode")
    val operationIndex = operationLineIndexes.single()
    val gateIndex = gateLineIndexes.single()
    assertTrue(operationIndex >= 3)
    assertTrue(gateIndex == operationIndex + 4, "Gate constant is not in the canonical operation branch")
    assertTrue(lines[operationIndex - 3].contains("PatchPlan.getOperation"))
    assertTrue(lines[operationIndex + 1].contains("Intrinsics.areEqual"))
    assertTrue(lines[operationIndex + 2].contains("ifeq"))
    assertTrue(lines[operationIndex + 3].contains("DiagnosticsGate.Companion"))
    assertTrue(lines[gateIndex + 3].contains("DiagnosticsGate\$Companion.lazyAuthoritative"))
    assertFalse(lines.subList(operationIndex, gateIndex + 4).any { it.contains("java-jdt") })

    val operationOffset = instructionOffset(lines[operationIndex])
    val branchTarget = Regex("\\bifeq\\s+(\\d+)").find(lines[operationIndex + 2])
        ?.groupValues?.get(1)?.toInt()
        ?: error("Canonical operation selector branch has no bytecode target")
    val gateOffset = instructionOffset(lines[gateIndex])
    val lazyOffset = instructionOffset(lines[gateIndex + 3])
    assertTrue(operationOffset < gateOffset && gateOffset < lazyOffset && lazyOffset < branchTarget)
    val branchTargetIndex = lines.indexOfFirst { line ->
        Regex("^\\s*$branchTarget:").containsMatchIn(line)
    }
    assertTrue(branchTargetIndex > gateIndex + 3, "Canonical selector branch does not skip to its next mapping")

    val methodHash = sha256(method.toByteArray(StandardCharsets.UTF_8))
    val proof = listOf(
        jarSha256,
        sha256(classBytes),
        SELECTOR_METHOD,
        SELECTOR_DESCRIPTOR,
        operation,
        operationOffset.toString(),
        branchTarget.toString(),
        gate,
        gateOffset.toString(),
        lazyOffset.toString(),
        methodHash,
    ).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)
    return PackagedGateSelectorEvidence(
        jvmJarPath = relativeJar,
        jvmJarSha256 = jarSha256,
        archiveEntrySha256 = outerEntry.sha256,
        selectorClassPath = SELECTOR_CLASS_PATH,
        selectorClassSha256 = sha256(classBytes),
        selectorMethod = SELECTOR_METHOD,
        selectorMethodDescriptor = SELECTOR_DESCRIPTOR,
        publicOperation = operation,
        diagnosticsGateId = gate,
        operationConstantOffset = operationOffset,
        equalityBranchTarget = branchTarget,
        gateConstantOffset = gateOffset,
        lazyAuthoritativeCallOffset = lazyOffset,
        javapSelectorMethodSha256 = methodHash,
        operationToGateMappingProofSha256 = sha256(proof),
    )
}

private fun instructionRegex(opcode: String, comment: String): Regex =
    Regex("^\\s*\\d+:\\s+$opcode\\s+#\\d+(?:,\\s*\\d+)?\\s+$comment\\s*$")

private fun instructionOffset(line: String): Int = Regex("^\\s*(\\d+):")
    .find(line)?.groupValues?.get(1)?.toInt()
    ?: error("Not a javap bytecode instruction: $line")

private const val SELECTOR_CLASS_NAME = "org.refactorkit.jvm.ManagedApplyDiagnosticsGateSelector"
private const val SELECTOR_CLASS_PATH = "org/refactorkit/jvm/ManagedApplyDiagnosticsGateSelector.class"
private const val SELECTOR_METHOD = "select\$refactorkit_jvm"
private const val SELECTOR_DESCRIPTOR =
    "(Lorg/refactorkit/core/PatchPlan;Ljava/lang/String;Lorg/refactorkit/java/JavaLanguageAdapter;" +
        "Lorg/refactorkit/kotlin/KotlinLanguageAdapter;Lkotlin/jvm/functions/Function1;" +
        "Lorg/refactorkit/jvm/ManagedApplyDiagnosticsProviderFunctions;)Lorg/refactorkit/core/DiagnosticsGate;"
