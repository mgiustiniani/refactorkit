package org.refactorkit.kotlin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DiagnosticLocationPrecision
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.core.SemanticProcessProvenance
import org.refactorkit.core.SemanticProcessSpec
import org.refactorkit.core.SemanticWorkspaceOverlay
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.kotlin.bridge.KotlinCompilerBridgeMain
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Public provenance for one compiler-backed Kotlin diagnostics result. */
data class KotlinCompilerDiagnosticsAttestation(
    val backend: String,
    val kotlinVersion: String,
    val javaVersion: String,
    val toolchainProjectionHash: String,
    val buildProjectionHash: String,
    val snapshotHash: String,
    val process: SemanticProcessProvenance?,
)

sealed interface KotlinCompilerDiagnosticsResult {
    val diagnostics: List<Diagnostic>
    val attestation: KotlinCompilerDiagnosticsAttestation

    data class Available(
        override val diagnostics: List<Diagnostic>,
        override val attestation: KotlinCompilerDiagnosticsAttestation,
    ) : KotlinCompilerDiagnosticsResult

    data class Refused(
        val reason: Diagnostic,
        override val attestation: KotlinCompilerDiagnosticsAttestation,
    ) : KotlinCompilerDiagnosticsResult {
        override val diagnostics: List<Diagnostic> = listOf(reason)
    }

    data class Error(
        val failure: Diagnostic,
        override val attestation: KotlinCompilerDiagnosticsAttestation,
    ) : KotlinCompilerDiagnosticsResult {
        override val diagnostics: List<Diagnostic> = listOf(failure)
    }
}

/** One-shot, external, compiler-backed diagnostics for the bounded Kotlin/JVM projection. */
class KotlinCompilerDiagnostics private constructor(
    private val toolchain: KotlinSemanticToolchain,
    private val execution: ExecutionConfiguration,
) {
    constructor(toolchain: KotlinSemanticToolchain) : this(
        toolchain,
        ExecutionConfiguration(REQUEST_TIMEOUT_MILLIS, KotlinCompilerBridgeMain::class.java),
    )

    internal constructor(
        toolchain: KotlinSemanticToolchain,
        requestTimeoutMillis: Long,
        bridgeClass: Class<*>,
    ) : this(toolchain, ExecutionConfiguration(requestTimeoutMillis, bridgeClass))
    fun analyze(snapshot: ProjectSnapshot): KotlinCompilerDiagnosticsResult {
        val model = snapshot.buildModels.singleOrNull { it.providerId == KotlinJvmBuildModelProjector.PROVIDER_ID }
        val buildHash = model?.attributes?.get("projectionHash").orEmpty()
        fun attestation(process: SemanticProcessProvenance? = null) = KotlinCompilerDiagnosticsAttestation(
            backend = BACKEND,
            kotlinVersion = toolchain.provenance.kotlinVersion,
            javaVersion = toolchain.provenance.javaVersion,
            toolchainProjectionHash = toolchain.provenance.projectionHash,
            buildProjectionHash = buildHash,
            snapshotHash = snapshot.hash,
            process = process,
        )
        validateToolchain()?.let { return KotlinCompilerDiagnosticsResult.Refused(it, attestation()) }
        if (snapshot.files.any { it.languageId == "kotlin" && it.path.fileName.toString().endsWith(".kts") }) return refused(
            "kotlin.scriptSemanticsUnsupported", "Kotlin script semantics remain refused", attestation(),
        )
        if (model == null || model.status != BuildModelStatus.AVAILABLE) return refused(
            "kotlin.buildModelUnavailable",
            "Compiler diagnostics require an AVAILABLE kotlin-jvm-projection-v1 build model",
            attestation(),
        )
        if (model.attributes["toolchainProjectionHash"] != toolchain.provenance.projectionHash ||
            model.attributes["backend"] != toolchain.provenance.backend) return refused(
            "kotlin.buildModelToolchainMismatch",
            "Kotlin build projection does not attest the configured compiler toolchain",
            attestation(),
        )
        validateClasspathEvidence(snapshot)?.let { return KotlinCompilerDiagnosticsResult.Refused(it, attestation()) }

        val sourceModules = model.modules.filter { it.sourceSets.isNotEmpty() }
        if (sourceModules.size != 1) return refused(
            "kotlin.compilerModuleLimit",
            "Compiler diagnostics currently require exactly one Kotlin source module",
            attestation(),
        )
        val sourceSets = sourceModules.single().sourceSets
        if (sourceSets.isEmpty() || sourceSets.any { it.attributes["kotlin.platform"] != "jvm" }) return refused(
            "kotlin.platformUnsupported", "Compiler diagnostics require proven Kotlin/JVM source sets", attestation(),
        )
        val targets = sourceSets.map { it.attributes["kotlin.jvmTarget"] }.distinct()
        val target = targets.singleOrNull()
        if (target == null || target !in SUPPORTED_JVM_TARGETS) return refused(
            "kotlin.jvmTargetUnavailable", "Compiler diagnostics require one supported declared JVM target", attestation(),
        )
        val declaredTargetJdks = sourceSets.map { it.attributes["kotlin.targetJdk"] }
        val targetJdks = declaredTargetJdks.map { it?.let(::jdkMajor) }.distinct()
        val targetJdk = targetJdks.singleOrNull()
        if (targetJdk == null || targetJdk > KotlinToolchainDiscoveryPolicy.QUALIFIED_JDK_MAJOR) return refused(
            "kotlin.targetJdkUnavailable", "Compiler diagnostics require one compatible declared target JDK", attestation(),
        )

        val roots = sourceSets.flatMap { it.sourceRoots }.map(Path::normalize).distinct()
        val sources = snapshot.files.filter { source ->
            source.languageId == "kotlin" && source.path.fileName.toString().endsWith(".kt") &&
                roots.any { root -> source.path.normalize().startsWith(root) }
        }.sortedBy { it.path.toString() }
        if (sources.isEmpty() || sources.size > MAX_SOURCE_FILES) return refused(
            "kotlin.compilerSourceLimit",
            "Compiler diagnostics require 1..$MAX_SOURCE_FILES owned Kotlin source files",
            attestation(),
        )
        val classpath = resolveClasspath(snapshot, sourceSets.flatMap { it.classpathEntries }.distinct())
            .getOrElse { return KotlinCompilerDiagnosticsResult.Refused(it.asDiagnostic(), attestation()) }

        val overlay = runCatching { SemanticWorkspaceOverlay.create(snapshot) }.getOrElse { failure ->
            return error("kotlin.compilerOverlayFailed", failure.message ?: "Kotlin compiler overlay failed", attestation())
        }
        return try {
            val outputDirectory = overlay.root.resolve(".refactorkit-kotlin-output")
            Files.createDirectory(outputDirectory)
            val overlaySources = sources.map { source ->
                requireNotNull(overlay.toOverlayPath(source.path)) { "Kotlin source cannot be mapped into compiler overlay" }
            }
            val compilerArguments = buildList {
                add("-language-version"); add("2.0")
                add("-api-version"); add("2.0")
                add("-jvm-target"); add(target)
                add("-jdk-home"); add(toolchain.jdkHome.toString())
                add("-no-stdlib"); add("-no-reflect")
                if (classpath.isNotEmpty()) {
                    add("-classpath"); add(classpath.joinToString(System.getProperty("path.separator")))
                }
                add("-d"); add(outputDirectory.toString())
                addAll(overlaySources.map(Path::toString))
            }
            val execution = execute(snapshot.hash, overlay, compilerArguments)
            val mutations = overlay.verifySourcesUnchanged()
            if (mutations.isNotEmpty()) return KotlinCompilerDiagnosticsResult.Error(mutations.first(), attestation(execution.provenance))
            parse(execution.output, snapshot, overlay, attestation(execution.provenance))
        } catch (failure: KotlinCompilerExecutionException) {
            error(failure.code, failure.message ?: "Kotlin compiler diagnostics failed", attestation(failure.provenance))
        } catch (_: Exception) {
            error("kotlin.compilerDiagnosticsFailed", "Kotlin compiler diagnostics failed", attestation())
        } finally {
            overlay.close()
        }
    }

    internal fun parseWorkerOutputForTest(output: String, snapshot: ProjectSnapshot): KotlinCompilerDiagnosticsResult {
        val model = snapshot.buildModels.singleOrNull { it.providerId == KotlinJvmBuildModelProjector.PROVIDER_ID }
        val overlay = SemanticWorkspaceOverlay.create(snapshot)
        return try {
            parse(output, snapshot, overlay, KotlinCompilerDiagnosticsAttestation(
                backend = BACKEND,
                kotlinVersion = toolchain.provenance.kotlinVersion,
                javaVersion = toolchain.provenance.javaVersion,
                toolchainProjectionHash = toolchain.provenance.projectionHash,
                buildProjectionHash = model?.attributes?.get("projectionHash").orEmpty(),
                snapshotHash = snapshot.hash,
                process = null,
            ))
        } finally {
            overlay.close()
        }
    }

    private fun execute(
        snapshotHash: String,
        overlay: SemanticWorkspaceOverlay,
        compilerArguments: List<String>,
    ): CompilerExecution {
        val bridgeLocation = Path.of(execution.bridgeClass.protectionDomain.codeSource.location.toURI())
            .toAbsolutePath().normalize()
        require(Files.exists(bridgeLocation, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(bridgeLocation)) {
            "Kotlin compiler bridge location is unavailable"
        }
        val processClasspath = (listOf(bridgeLocation) + toolchain.compilerClasspath).distinct()
        val launcherClasspath = overlay.root.resolve(".refactorkit-kotlin-compiler-classpath.jar")
        val temporaryDirectory = overlay.root.resolve(".refactorkit-kotlin-tmp")
        val homeDirectory = overlay.root.resolve(".refactorkit-kotlin-home")
        Files.createDirectories(temporaryDirectory)
        Files.createDirectories(homeDirectory)
        writeLauncherClasspathJar(launcherClasspath, processClasspath)
        val arguments = listOf(
            "-Xmx${COMPILER_HEAP_MIB}m",
            "-Dfile.encoding=UTF-8",
            "-Duser.language=en",
            "-Duser.country=US",
            "-Djava.io.tmpdir=$temporaryDirectory",
            "-Duser.home=$homeDirectory",
            "-cp",
            launcherClasspath.toString(),
            execution.bridgeClass.name,
            snapshotHash,
            overlay.root.toString(),
            "--",
        ) + compilerArguments
        val manager = ExternalSemanticProcessManager(maxProcesses = 1)
        val process = manager.launch(SemanticProcessSpec(
            id = "kotlin-compiler-${SEQUENCE.getAndIncrement()}",
            executable = toolchain.javaExecutable,
            arguments = arguments,
            workingDirectory = overlay.root,
            environment = emptyMap(),
            limits = SemanticProcessLimits(
                maxStdoutBytes = MAX_OUTPUT_BYTES,
                maxStderrBytes = MAX_STDERR_BYTES,
                gracefulShutdownMillis = 1_000,
            ),
        ))
        val reader = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "refactorkit-kotlin-compiler-output").apply { isDaemon = true }
        }
        try {
            val future = reader.submit<String> { process.output.bufferedReader(Charsets.UTF_8).readText() }
            if (!process.awaitExit(execution.requestTimeoutMillis)) {
                val provenance = process.provenance
                process.cancel()
                throw KotlinCompilerExecutionException(
                    "kotlin.compilerDiagnosticsTimeout", "Kotlin compiler diagnostics timed out", provenance,
                )
            }
            val text = try {
                future.get(2, TimeUnit.SECONDS)
            } catch (_: Exception) {
                throw KotlinCompilerExecutionException(
                    "kotlin.compilerDiagnosticsOutputInvalid",
                    "Kotlin compiler worker output was unavailable or exceeded its limit",
                    process.provenance,
                )
            }
            if (process.exitCode != 0) throw KotlinCompilerExecutionException(
                "kotlin.compilerDiagnosticsFailed",
                if (process.stderrTruncated()) "Kotlin compiler worker failed with truncated stderr"
                else "Kotlin compiler worker failed",
                process.provenance,
            )
            return CompilerExecution(text, process.provenance)
        } finally {
            reader.shutdownNow()
            process.close()
            manager.close()
        }
    }

    private fun writeLauncherClasspathJar(path: Path, entries: List<Path>) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.CLASS_PATH] = entries.joinToString(" ") {
                it.toUri().toASCIIString()
            }
        }
        JarOutputStream(Files.newOutputStream(path), manifest).use { }
    }

    private fun parse(
        output: String,
        snapshot: ProjectSnapshot,
        overlay: SemanticWorkspaceOverlay,
        attestation: KotlinCompilerDiagnosticsAttestation,
    ): KotlinCompilerDiagnosticsResult {
        val payload = runCatching { JSON.parseToJsonElement(output).jsonObject }.getOrNull()
            ?: return error("kotlin.compilerDiagnosticsInvalid", "Kotlin compiler worker returned invalid JSON", attestation)
        if (payload.int("schema") != 1 || payload.boolean("complete") != true) return error(
            payload.string("failure")?.takeIf { it.startsWith("kotlin.") } ?: "kotlin.compilerDiagnosticsIncomplete",
            "Kotlin compiler worker did not complete",
            attestation,
        )
        if (payload.string("snapshotHash") != snapshot.hash) return error(
            "kotlin.compilerDiagnosticsSnapshotMismatch", "Kotlin compiler worker attested another snapshot", attestation,
        )
        val exitCode = payload.string("exitCode") ?: return error(
            "kotlin.compilerDiagnosticsInvalid", "Kotlin compiler worker omitted its exit code", attestation,
        )
        if (exitCode !in setOf("OK", "COMPILATION_ERROR")) return error(
            "kotlin.compilerInternalError", "Kotlin compiler terminated with $exitCode", attestation,
        )
        val encoded = payload.string("xmlBase64") ?: return error(
            "kotlin.compilerDiagnosticsInvalid", "Kotlin compiler worker omitted diagnostics", attestation,
        )
        val xml = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            ?.takeIf { it.size <= MAX_XML_BYTES }
            ?: return error("kotlin.compilerDiagnosticsInvalid", "Kotlin compiler diagnostics payload is invalid", attestation)
        val diagnostics = parseXml(xml, snapshot, overlay).getOrElse {
            return error("kotlin.compilerDiagnosticsInvalid", "Kotlin compiler XML is invalid", attestation)
        }
        if (exitCode == "COMPILATION_ERROR" && diagnostics.none { it.severity == Diagnostic.Severity.ERROR }) return error(
            "kotlin.compilerDiagnosticsIncomplete", "Kotlin compiler reported failure without an error diagnostic", attestation,
        )
        return KotlinCompilerDiagnosticsResult.Available(diagnostics, attestation)
    }

    private fun parseXml(
        xml: ByteArray,
        snapshot: ProjectSnapshot,
        overlay: SemanticWorkspaceOverlay,
    ): Result<List<Diagnostic>> = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            setExpandEntityReferences(false)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        check(document.documentElement.tagName == "MESSAGES") { "Unexpected Kotlin compiler XML root" }
        val sourcePaths = snapshot.files.associateBy { it.path.normalize() }
        val diagnostics = mutableListOf<Diagnostic>()
        val children = document.documentElement.childNodes
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            val severity = when (element.tagName.uppercase()) {
                "ERROR", "EXCEPTION" -> Diagnostic.Severity.ERROR
                "WARNING", "STRONG_WARNING" -> Diagnostic.Severity.WARNING
                "INFO" -> Diagnostic.Severity.INFO
                "LOGGING", "OUTPUT" -> continue
                else -> error("Unknown Kotlin compiler diagnostic severity")
            }
            check(diagnostics.size < MAX_DIAGNOSTICS) { "Kotlin compiler diagnostic limit exceeded" }
            val message = element.textContent.orEmpty().trim().take(MAX_MESSAGE_CHARS)
            check(message.isNotBlank()) { "Kotlin compiler diagnostic message is empty" }
            val rawPath = element.getAttribute("path").takeIf { it.isNotBlank() && it != "null" }
            val workspacePath = rawPath?.let { value ->
                val overlayPath = Path.of(value).toAbsolutePath().normalize()
                val absolute = overlay.toWorkspacePath(overlayPath) ?: error("Kotlin compiler diagnostic path escapes overlay")
                val root = snapshot.workspace.root.toAbsolutePath().normalize()
                root.relativize(absolute).normalize().also { check(it in sourcePaths) }
            }
            val line = element.getAttribute("line").toIntOrNull()?.takeIf { it > 0 }
            val column = element.getAttribute("column").toIntOrNull()?.takeIf { it > 0 }
            val location = if (workspacePath != null && line != null) {
                val position = SourcePosition(line - 1, (column ?: 1) - 1)
                SourceLocation(workspacePath, SourceRange(position, position))
            } else {
                check(workspacePath == null && line == null && column == null) { "Kotlin compiler diagnostic has a partial location" }
                null
            }
            val codeMatch = DIAGNOSTIC_CODE.find(message)
            diagnostics += Diagnostic(
                message = message,
                severity = severity,
                location = location,
                code = codeMatch?.groupValues?.get(1)?.let { "KOTLIN_$it" },
                evidence = DiagnosticEvidence.COMPILER,
                category = if (element.tagName.equals("EXCEPTION", ignoreCase = true)) {
                    DiagnosticCategory.SAFETY
                } else DiagnosticCategory.TYPE_RESOLUTION,
                locationPrecision = if (location == null) DiagnosticLocationPrecision.NONE else DiagnosticLocationPrecision.LINE_ONLY,
            )
        }
        diagnostics.sortedWith(compareBy({ it.location?.path?.toString().orEmpty() }, { it.location?.range?.start?.line ?: -1 }, { it.code.orEmpty() }))
    }

    private fun validateToolchain(): Diagnostic? {
        for (evidence in toolchain.provenance.evidence) {
            val path = evidence.path.toAbsolutePath().normalize()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return diagnostic(
                "kotlin.toolchainEvidenceChanged", "Kotlin toolchain evidence is missing or unsafe",
            )
            val size = runCatching { Files.size(path) }.getOrNull()
            val hash = runCatching { sha256(path) }.getOrNull()
            if (size != evidence.size || hash != evidence.sha256) return diagnostic(
                "kotlin.toolchainEvidenceChanged", "Kotlin toolchain evidence changed after discovery",
            )
        }
        return null
    }

    private fun validateClasspathEvidence(snapshot: ProjectSnapshot): Diagnostic? = try {
        snapshot.classpathEvidence.forEach { evidence ->
            val path = if (evidence.path.isAbsolute) evidence.path.toAbsolutePath().normalize()
                else snapshot.workspace.root.resolve(evidence.path).toAbsolutePath().normalize()
            if (Files.isSymbolicLink(path) || ClasspathEvidence.fingerprint(path, evidence.kind) != evidence.fingerprint) {
                return diagnostic("kotlin.classpathEvidenceChanged", "Kotlin classpath/build evidence changed after project scan")
            }
        }
        null
    } catch (_: Exception) {
        diagnostic("kotlin.classpathEvidenceUnavailable", "Kotlin classpath/build evidence cannot be revalidated")
    }

    private fun resolveClasspath(snapshot: ProjectSnapshot, entries: List<Path>): Result<List<Path>> = runCatching {
        check(entries.size <= MAX_CLASSPATH_ENTRIES) { "kotlin.compilerClasspathLimit|Kotlin project classpath exceeds $MAX_CLASSPATH_ENTRIES entries" }
        val evidencePaths = snapshot.classpathEvidence.map { it.path.normalize() }.toSet()
        entries.sortedBy(Path::toString).map { configured ->
            val normalized = configured.normalize()
            check(normalized in evidencePaths) { "kotlin.classpathEvidenceMissing|Kotlin project classpath entry has no snapshot evidence" }
            val absolute = if (normalized.isAbsolute) normalized.toAbsolutePath().normalize()
                else snapshot.workspace.root.resolve(normalized).toAbsolutePath().normalize()
            val workspace = snapshot.workspace.root.toAbsolutePath().normalize()
            check(!absolute.startsWith(workspace)) {
                "kotlin.workspaceClasspathUnsupported|Workspace build outputs are not exposed to the read-only compiler worker"
            }
            check(!Files.isSymbolicLink(absolute) && Files.isRegularFile(absolute) &&
                absolute.fileName.toString().endsWith(".jar", ignoreCase = true)) {
                "kotlin.classpathUnavailable|Kotlin project classpath entry must be a safe external JAR"
            }
            absolute.toRealPath()
        }.distinct()
    }

    private fun Throwable.asDiagnostic(): Diagnostic {
        val raw = message.orEmpty()
        val code = raw.substringBefore('|').takeIf { it.startsWith("kotlin.") } ?: "kotlin.compilerClasspathInvalid"
        val text = raw.substringAfter('|', "Kotlin project classpath is invalid")
        return diagnostic(code, text)
    }

    private fun refused(
        code: String,
        message: String,
        attestation: KotlinCompilerDiagnosticsAttestation,
    ) = KotlinCompilerDiagnosticsResult.Refused(diagnostic(code, message), attestation)

    private fun error(
        code: String,
        message: String,
        attestation: KotlinCompilerDiagnosticsAttestation,
    ) = KotlinCompilerDiagnosticsResult.Error(diagnostic(code, message), attestation)

    private fun diagnostic(code: String, message: String) = Diagnostic(
        message = message.take(MAX_MESSAGE_CHARS),
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.COMPILER,
        category = DiagnosticCategory.SAFETY,
    )

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun jdkMajor(value: String): Int? = value.substringBefore('.').toIntOrNull()
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)?.intOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)?.booleanOrNull

    private data class CompilerExecution(val output: String, val provenance: SemanticProcessProvenance)
    private data class ExecutionConfiguration(
        val requestTimeoutMillis: Long,
        val bridgeClass: Class<*>,
    ) {
        init {
            require(requestTimeoutMillis in 1..REQUEST_TIMEOUT_MILLIS)
        }
    }
    private class KotlinCompilerExecutionException(
        val code: String,
        message: String,
        val provenance: SemanticProcessProvenance?,
    ) : RuntimeException(message)

    companion object {
        const val BACKEND = "kotlin-compiler-diagnostics-k2-v1"
        const val REQUEST_TIMEOUT_MILLIS = 30_000L
        const val MAX_OUTPUT_BYTES = 8L * 1024L * 1024L
        const val MAX_STDERR_BYTES = 64 * 1024
        const val COMPILER_HEAP_MIB = 512
        const val MAX_SOURCE_FILES = 96
        const val MAX_CLASSPATH_ENTRIES = 128
        const val MAX_DIAGNOSTICS = 500
        const val MAX_MESSAGE_CHARS = 4_096
        private const val MAX_XML_BYTES = 6 * 1024 * 1024
        private val JSON = Json { isLenient = false; ignoreUnknownKeys = false }
        private val SEQUENCE = AtomicLong(1)
        private val DIAGNOSTIC_CODE = Regex("^\\[([A-Z][A-Z0-9_]{0,63})]")
        private val SUPPORTED_JVM_TARGETS = setOf("1.8", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21")
    }
}
