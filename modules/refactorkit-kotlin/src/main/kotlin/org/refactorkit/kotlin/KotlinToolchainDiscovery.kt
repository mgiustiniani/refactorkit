package org.refactorkit.kotlin

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.jar.Manifest

data class KotlinToolchainDiscoveryPolicy(
    val allowWorkspaceLocalToolchain: Boolean = false,
    val supportedKotlinVersions: Set<String> = setOf(QUALIFIED_KOTLIN_VERSION),
    val minimumJdkMajor: Int = QUALIFIED_JDK_MAJOR,
    val maximumJdkMajor: Int = QUALIFIED_JDK_MAJOR,
    val maxClasspathEntries: Int = 64,
    val maxJarEntries: Int = 100_000,
    val maxJarBytes: Long = 256L * 1024L * 1024L,
    val maxTotalBytes: Long = 512L * 1024L * 1024L,
) {
    init {
        require(supportedKotlinVersions.isNotEmpty() && supportedKotlinVersions.all(STRICT_VERSION::matches))
        require(minimumJdkMajor in 8..100 && maximumJdkMajor >= minimumJdkMajor)
        require(maxClasspathEntries in 0..256)
        require(maxJarEntries in 1..1_000_000)
        require(maxJarBytes in 1..1024L * 1024L * 1024L)
        require(maxTotalBytes in maxJarBytes..4L * 1024L * 1024L * 1024L)
    }

    companion object {
        const val QUALIFIED_KOTLIN_VERSION = "2.0.21"
        const val QUALIFIED_JDK_MAJOR = 21
        private val STRICT_VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
    }
}

data class KotlinToolchainRequest(
    val workspaceRoot: Path,
    val jdkHome: Path? = null,
    val compilerJar: Path? = null,
    val compilerClasspath: List<Path> = emptyList(),
)

data class KotlinToolchainFileEvidence(
    val role: String,
    val path: Path,
    val sha256: String,
    val size: Long,
)

data class KotlinToolchainProvenance(
    val providerId: String = PROVIDER_ID,
    val backend: String = BACKEND,
    val javaVersion: String,
    val kotlinVersion: String,
    val compilerDistributionVersion: String,
    val projectionHash: String,
    val evidence: List<KotlinToolchainFileEvidence>,
) {
    companion object {
        const val PROVIDER_ID = "kotlin-compiler-explicit-v1"
        const val BACKEND = "kotlin-compiler-embeddable-k2"
    }
}

data class KotlinSemanticToolchain(
    val jdkHome: Path,
    val javaExecutable: Path,
    val compilerJar: Path,
    val compilerClasspath: List<Path>,
    val provenance: KotlinToolchainProvenance,
)

sealed interface KotlinToolchainDiscovery {
    data class Available(val toolchain: KotlinSemanticToolchain) : KotlinToolchainDiscovery
    data class Refused(val diagnostics: List<Diagnostic>) : KotlinToolchainDiscovery
}

/**
 * Declaratively validates an explicitly configured Kotlin/JVM compiler toolchain.
 * Discovery never executes Java, the compiler, Gradle, Maven, plugins, processors,
 * kapt, KSP, or workspace code.
 */
class KotlinToolchainDiscoverer(
    private val policy: KotlinToolchainDiscoveryPolicy = KotlinToolchainDiscoveryPolicy(),
) {
    fun discover(request: KotlinToolchainRequest): KotlinToolchainDiscovery {
        val diagnostics = mutableListOf<Diagnostic>()
        val workspace = requireDirectory(request.workspaceRoot, "workspace", diagnostics)
            ?: return KotlinToolchainDiscovery.Refused(diagnostics)
        val jdkHome = request.jdkHome?.let { requireDirectory(it, "JDK home", diagnostics) } ?: run {
            diagnostics += refusal("kotlin.toolchainNotConfigured", "Explicit jdkHome is required")
            null
        }
        val compilerJar = request.compilerJar?.let {
            requireFile(it, "Kotlin compiler JAR", diagnostics, policy.maxJarBytes)
        } ?: run {
            diagnostics += refusal("kotlin.toolchainNotConfigured", "Explicit compilerJar is required")
            null
        }
        if (jdkHome == null || compilerJar == null) return KotlinToolchainDiscovery.Refused(diagnostics)
        if (!policy.allowWorkspaceLocalToolchain && (jdkHome.startsWith(workspace) || compilerJar.startsWith(workspace))) {
            diagnostics += refusal(
                "kotlin.workspaceToolchainRefused",
                "Kotlin JDK/compiler inputs are workspace-local and require explicit policy",
            )
            return KotlinToolchainDiscovery.Refused(diagnostics)
        }
        if (request.compilerClasspath.isEmpty()) {
            diagnostics += refusal(
                "kotlin.compilerClasspathInvalid",
                "Explicit Kotlin compiler runtime classpath is required",
            )
            return KotlinToolchainDiscovery.Refused(diagnostics)
        }
        if (request.compilerClasspath.size > policy.maxClasspathEntries) {
            diagnostics += refusal(
                "kotlin.compilerClasspathLimit",
                "Kotlin compiler classpath exceeds ${policy.maxClasspathEntries} entries",
            )
            return KotlinToolchainDiscovery.Refused(diagnostics)
        }

        val classpath = mutableListOf<Path>()
        request.compilerClasspath.forEachIndexed { index, configured ->
            val entry = requireFile(configured, "Kotlin compiler classpath entry $index", diagnostics, policy.maxJarBytes)
                ?: return@forEachIndexed
            if (!entry.fileName.toString().endsWith(".jar", ignoreCase = true)) {
                diagnostics += refusal("kotlin.compilerClasspathInvalid", "Kotlin compiler classpath entries must be JAR files")
            } else if (!policy.allowWorkspaceLocalToolchain && entry.startsWith(workspace)) {
                diagnostics += refusal(
                    "kotlin.workspaceToolchainRefused",
                    "Kotlin compiler classpath entry is workspace-local and requires explicit policy",
                )
            } else if (!validateJar(entry, diagnostics, "classpath entry $index")) {
                Unit
            } else {
                classpath.add(entry)
            }
        }
        if (diagnostics.isNotEmpty()) return KotlinToolchainDiscovery.Refused(diagnostics.distinctBy { it.code to it.message })
        if ((listOf(compilerJar) + classpath).distinct().size != classpath.size + 1) {
            diagnostics += refusal("kotlin.compilerClasspathInvalid", "Kotlin compiler classpath contains duplicate files")
            return KotlinToolchainDiscovery.Refused(diagnostics)
        }

        val releaseFile = requireFile(jdkHome.resolve("release"), "JDK release metadata", diagnostics, MAX_RELEASE_BYTES)
        val javaExecutable = requireFile(
            jdkHome.resolve("bin").resolve(if (isWindows()) "java.exe" else "java"),
            "JDK Java executable",
            diagnostics,
            MAX_EXECUTABLE_BYTES,
        )
        if (releaseFile == null || javaExecutable == null) return KotlinToolchainDiscovery.Refused(diagnostics)
        if (!isWindows() && !Files.isExecutable(javaExecutable)) {
            diagnostics += refusal("kotlin.toolchainPathInvalid", "JDK Java executable is not executable")
            return KotlinToolchainDiscovery.Refused(diagnostics)
        }
        if (!releaseFile.startsWith(jdkHome) || !javaExecutable.startsWith(jdkHome)) {
            diagnostics += refusal("kotlin.toolchainPathInvalid", "JDK metadata or executable resolves outside jdkHome")
            return KotlinToolchainDiscovery.Refused(diagnostics)
        }

        val javaVersion = readJavaVersion(releaseFile, diagnostics)
            ?: return KotlinToolchainDiscovery.Refused(diagnostics)
        val javaMajor = javaMajor(javaVersion)
        if (javaMajor == null || javaMajor !in policy.minimumJdkMajor..policy.maximumJdkMajor) {
            diagnostics += refusal(
                "kotlin.jdkVersionUnsupported",
                "JDK major must be ${policy.minimumJdkMajor}..${policy.maximumJdkMajor}; detected '${javaVersion.take(64)}'",
            )
            return KotlinToolchainDiscovery.Refused(diagnostics)
        }

        val compilerIdentity = readCompilerIdentity(compilerJar, diagnostics)
            ?: return KotlinToolchainDiscovery.Refused(diagnostics)
        if (compilerIdentity.kotlinVersion !in policy.supportedKotlinVersions) {
            diagnostics += refusal(
                "kotlin.compilerVersionUnsupported",
                "Kotlin compiler version must be one of ${policy.supportedKotlinVersions.sorted().joinToString()}",
            )
            return KotlinToolchainDiscovery.Refused(diagnostics)
        }

        val orderedClasspath = listOf(compilerJar) + classpath
        val evidenceInputs = buildList {
            add("jdk-release" to releaseFile)
            add("java-executable" to javaExecutable)
            add("kotlin-compiler" to compilerJar)
            classpath.forEachIndexed { index, path -> add("compiler-classpath-${index.toString().padStart(3, '0')}" to path) }
        }
        val totalBytes = runCatching { evidenceInputs.sumOf { (_, path) -> Files.size(path) } }.getOrElse {
            diagnostics += refusal("kotlin.toolchainEvidenceUnavailable", "Kotlin toolchain size evidence cannot be read")
            return KotlinToolchainDiscovery.Refused(diagnostics)
        }
        if (totalBytes > policy.maxTotalBytes) {
            diagnostics += refusal("kotlin.toolchainFileLimit", "Kotlin toolchain evidence exceeds the total byte limit")
            return KotlinToolchainDiscovery.Refused(diagnostics)
        }
        val evidence = readEvidence(evidenceInputs, diagnostics)
        if (diagnostics.isNotEmpty()) return KotlinToolchainDiscovery.Refused(diagnostics)

        val confirmedJavaVersion = readJavaVersion(releaseFile, diagnostics)
        val confirmedCompilerIdentity = readCompilerIdentity(compilerJar, diagnostics)
        classpath.forEachIndexed { index, path -> validateJar(path, diagnostics, "classpath entry $index") }
        val confirmedEvidence = readEvidence(evidenceInputs, diagnostics)
        if (diagnostics.isNotEmpty()) return KotlinToolchainDiscovery.Refused(diagnostics.distinctBy { it.code to it.message })
        if (confirmedJavaVersion != javaVersion || confirmedCompilerIdentity != compilerIdentity ||
            confirmedEvidence.map { Triple(it.role, it.sha256, it.size) } != evidence.map { Triple(it.role, it.sha256, it.size) }) {
            diagnostics += refusal("kotlin.toolchainChanged", "Kotlin toolchain changed during declarative discovery")
            return KotlinToolchainDiscovery.Refused(diagnostics)
        }

        val projectionHash = projectionHash(
            javaVersion,
            compilerIdentity.kotlinVersion,
            compilerIdentity.distributionVersion,
            orderedClasspath,
            confirmedEvidence,
        )
        return KotlinToolchainDiscovery.Available(KotlinSemanticToolchain(
            jdkHome = jdkHome,
            javaExecutable = javaExecutable,
            compilerJar = compilerJar,
            compilerClasspath = orderedClasspath,
            provenance = KotlinToolchainProvenance(
                javaVersion = javaVersion,
                kotlinVersion = compilerIdentity.kotlinVersion,
                compilerDistributionVersion = compilerIdentity.distributionVersion,
                projectionHash = projectionHash,
                evidence = confirmedEvidence.sortedBy(KotlinToolchainFileEvidence::role),
            ),
        ))
    }

    private fun requireDirectory(
        path: Path,
        label: String,
        diagnostics: MutableList<Diagnostic>,
    ): Path? {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            diagnostics += refusal("kotlin.toolchainPathInvalid", "$label must be an existing non-symlink directory")
            return null
        }
        val real = runCatching { normalized.toRealPath() }.getOrNull()
        if (real == null) diagnostics += refusal("kotlin.toolchainPathInvalid", "$label cannot be canonicalized")
        return real
    }

    private fun requireFile(
        path: Path,
        label: String,
        diagnostics: MutableList<Diagnostic>,
        maxBytes: Long,
    ): Path? {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            diagnostics += refusal("kotlin.toolchainPathInvalid", "$label must be an existing non-symlink file")
            return null
        }
        val real = runCatching { normalized.toRealPath() }.getOrNull()
        val size = real?.let { runCatching { Files.size(it) }.getOrNull() }
        if (real == null || size == null || size !in 1..maxBytes) {
            diagnostics += refusal("kotlin.toolchainFileLimit", "$label size is outside the bounded range")
            return null
        }
        return real
    }

    private fun readJavaVersion(path: Path, diagnostics: MutableList<Diagnostic>): String? {
        val text = readBounded(path, MAX_RELEASE_BYTES).getOrElse {
            diagnostics += refusal("kotlin.jdkMetadataInvalid", "JDK release metadata cannot be read")
            return null
        }.toString(Charsets.UTF_8)
        val entries = linkedMapOf<String, String>()
        for (line in text.lineSequence().filter(String::isNotBlank)) {
            val name = line.substringBefore('=', missingDelimiterValue = "")
            val raw = line.substringAfter('=', missingDelimiterValue = "")
            if (!METADATA_KEY.matches(name) || raw.length !in 2..MAX_RELEASE_VALUE_CHARS ||
                !raw.startsWith('"') || !raw.endsWith('"') || name in entries) {
                diagnostics += refusal("kotlin.jdkMetadataInvalid", "JDK release metadata has invalid shape")
                return null
            }
            entries[name] = raw.removeSurrounding("\"")
        }
        val version = entries["JAVA_VERSION"]
        if (version == null || !JAVA_VERSION.matches(version)) {
            diagnostics += refusal("kotlin.jdkMetadataInvalid", "JDK release metadata has no valid JAVA_VERSION")
            return null
        }
        return version
    }

    private fun readCompilerIdentity(path: Path, diagnostics: MutableList<Diagnostic>): CompilerIdentity? = try {
        JarFile(path.toFile(), false).use { jar ->
            if (jar.size() !in 1..policy.maxJarEntries) {
                diagnostics += refusal("kotlin.compilerJarLimit", "Kotlin compiler JAR entry count is outside the bounded range")
                return null
            }
            val sentinel = jar.getJarEntry(COMPILER_SENTINEL)
            if (sentinel == null || sentinel.isDirectory || sentinel.size !in 1..MAX_SENTINEL_BYTES) {
                diagnostics += refusal("kotlin.compilerIdentityInvalid", "Kotlin compiler JAR has no bounded K2JVMCompiler entry")
                return null
            }
            val manifestEntry = jar.getJarEntry(JarFile.MANIFEST_NAME)
            if (manifestEntry == null || manifestEntry.size !in 1..MAX_MANIFEST_BYTES) {
                diagnostics += refusal("kotlin.compilerIdentityInvalid", "Kotlin compiler JAR has no bounded manifest")
                return null
            }
            val manifestBytes = jar.getInputStream(manifestEntry).use { input ->
                input.readNBytes(MAX_MANIFEST_BYTES.toInt() + 1)
            }
            if (manifestBytes.size !in 1..MAX_MANIFEST_BYTES.toInt()) {
                diagnostics += refusal("kotlin.compilerIdentityInvalid", "Kotlin compiler manifest exceeds its byte limit")
                return null
            }
            val attributes = Manifest(ByteArrayInputStream(manifestBytes)).mainAttributes
            val title = attributes.getValue("Implementation-Title")
            val vendor = attributes.getValue("Implementation-Vendor")
            val distributionVersion = attributes.getValue("Implementation-Version")
            val kotlinVersion = distributionVersion?.let(KOTLIN_DISTRIBUTION_VERSION::find)?.groupValues?.get(1)
            if (title != EXPECTED_COMPILER_TITLE || vendor != EXPECTED_COMPILER_VENDOR ||
                distributionVersion.isNullOrBlank() || distributionVersion.length > 128 || kotlinVersion == null) {
                diagnostics += refusal("kotlin.compilerIdentityInvalid", "Expected JetBrains kotlin-compiler-embeddable identity")
                return null
            }
            CompilerIdentity(kotlinVersion, distributionVersion)
        }
    } catch (_: Exception) {
        diagnostics += refusal("kotlin.compilerIdentityInvalid", "Kotlin compiler JAR cannot be parsed safely")
        null
    }

    private fun validateJar(path: Path, diagnostics: MutableList<Diagnostic>, label: String): Boolean = try {
        JarFile(path.toFile(), false).use { jar ->
            if (jar.size() !in 1..policy.maxJarEntries) {
                diagnostics += refusal("kotlin.compilerJarLimit", "Kotlin $label entry count is outside the bounded range")
                false
            } else true
        }
    } catch (_: Exception) {
        diagnostics += refusal("kotlin.compilerClasspathInvalid", "Kotlin $label is not a valid JAR")
        false
    }

    private fun readEvidence(
        inputs: List<Pair<String, Path>>,
        diagnostics: MutableList<Diagnostic>,
    ): List<KotlinToolchainFileEvidence> = inputs.mapNotNull { (role, path) ->
        evidence(role, path, if (role == "jdk-release") MAX_RELEASE_BYTES else if (role == "java-executable") {
            MAX_EXECUTABLE_BYTES
        } else policy.maxJarBytes).getOrElse {
            diagnostics += refusal(
                "kotlin.toolchainEvidenceUnavailable",
                it.message ?: "Kotlin toolchain evidence cannot be read exactly",
            )
            null
        }
    }

    private fun evidence(role: String, path: Path, maxBytes: Long): Result<KotlinToolchainFileEvidence> = runCatching {
        val size = Files.size(path)
        check(size in 1..maxBytes) { "$role size is outside the bounded range" }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= maxBytes) { "$role changed or exceeded its evidence limit" }
                digest.update(buffer, 0, count)
            }
            check(total == size) { "$role changed while evidence was read" }
        }
        KotlinToolchainFileEvidence(role, path, digest.digest().toHex(), size)
    }

    private fun readBounded(path: Path, maxBytes: Long): Result<ByteArray> = runCatching {
        val size = Files.size(path)
        check(size in 1..maxBytes)
        val bytes = Files.readAllBytes(path)
        check(bytes.size.toLong() == size && bytes.size <= maxBytes)
        bytes
    }

    private fun projectionHash(
        javaVersion: String,
        kotlinVersion: String,
        distributionVersion: String,
        classpath: List<Path>,
        evidence: List<KotlinToolchainFileEvidence>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(vararg values: String) = values.forEach {
            digest.update(it.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        add(KotlinToolchainProvenance.PROVIDER_ID, KotlinToolchainProvenance.BACKEND, javaVersion, kotlinVersion, distributionVersion)
        val evidenceByPath = evidence.associateBy(KotlinToolchainFileEvidence::path)
        classpath.forEach { path ->
            val item = requireNotNull(evidenceByPath[path])
            add("classpath", path.toString(), item.sha256, item.size.toString())
        }
        evidence.filter { it.role in setOf("jdk-release", "java-executable") }.sortedBy(KotlinToolchainFileEvidence::role).forEach {
            add(it.role, it.path.toString(), it.sha256, it.size.toString())
        }
        return digest.digest().toHex()
    }

    private fun javaMajor(version: String): Int? = if (version.startsWith("1.")) {
        version.substringAfter("1.").substringBefore('.').toIntOrNull()
    } else version.substringBefore('.').substringBefore('+').substringBefore('-').toIntOrNull()

    private fun refusal(code: String, message: String) = Diagnostic(
        message = message,
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.SAFETY,
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private data class CompilerIdentity(val kotlinVersion: String, val distributionVersion: String)

    companion object {
        private const val EXPECTED_COMPILER_TITLE = "kotlin-compiler-embeddable"
        private const val EXPECTED_COMPILER_VENDOR = "JetBrains"
        private const val COMPILER_SENTINEL = "org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class"
        private const val MAX_MANIFEST_BYTES = 65_536L
        private const val MAX_RELEASE_BYTES = 65_536L
        private const val MAX_RELEASE_VALUE_CHARS = 32_768
        private const val MAX_EXECUTABLE_BYTES = 512L * 1024L * 1024L
        private const val MAX_SENTINEL_BYTES = 16L * 1024L * 1024L
        private val KOTLIN_DISTRIBUTION_VERSION = Regex("^([0-9]+\\.[0-9]+\\.[0-9]+)(?:[-+][0-9A-Za-z.-]{1,96})?$")
        private val METADATA_KEY = Regex("[A-Z][A-Z0-9_]{0,63}")
        private val JAVA_VERSION = Regex("[0-9][0-9A-Za-z._+-]{0,63}")
    }
}
