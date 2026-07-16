package org.refactorkit.kotlin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.SymbolIndex
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
    val ephemeralClasspathHash: String = "",
)

data class KotlinCompilerResolvedUsage(
    val targetId: SymbolId,
    val location: SourceLocation,
)

data class KotlinCompilerExternalTypeUsage(
    val jvmBinaryName: String,
    val location: SourceLocation,
)

data class KotlinCompilerExternalCallableUsage(
    val jvmOwner: String,
    val callableName: String,
    val location: SourceLocation,
)

enum class KotlinDeclarationVisibility { PUBLIC, INTERNAL, PROTECTED, PRIVATE }

data class KotlinCompilerDeclarationEvidence(
    val id: SymbolId,
    val visibility: KotlinDeclarationVisibility,
    val jvmIdentity: String = "",
    val jvmOwner: String = "",
    val jvmDescriptor: String = "",
)

private class CompiledOutputConsumerException(cause: Throwable) : RuntimeException(cause)

private data class ParsedKotlinSymbols(
    val index: SymbolIndex,
    val usages: List<KotlinCompilerResolvedUsage>,
    val externalTypeUsages: List<KotlinCompilerExternalTypeUsage>,
    val externalCallableUsages: List<KotlinCompilerExternalCallableUsage>,
    val declarations: Map<SymbolId, KotlinCompilerDeclarationEvidence>,
)

sealed interface KotlinCompilerDiagnosticsResult {
    val diagnostics: List<Diagnostic>
    val attestation: KotlinCompilerDiagnosticsAttestation

    data class Available(
        override val diagnostics: List<Diagnostic>,
        override val attestation: KotlinCompilerDiagnosticsAttestation,
        val symbols: SymbolIndex? = null,
        val usages: List<KotlinCompilerResolvedUsage> = emptyList(),
        val externalTypeUsages: List<KotlinCompilerExternalTypeUsage> = emptyList(),
        val externalCallableUsages: List<KotlinCompilerExternalCallableUsage> = emptyList(),
        val declarations: Map<SymbolId, KotlinCompilerDeclarationEvidence> = emptyMap(),
        val symbolFailure: Diagnostic? = null,
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

sealed interface KotlinCompilerSymbolsResult {
    val attestation: KotlinCompilerDiagnosticsAttestation

    data class Available(
        val index: SymbolIndex,
        override val attestation: KotlinCompilerDiagnosticsAttestation,
        val usages: List<KotlinCompilerResolvedUsage> = emptyList(),
        val externalTypeUsages: List<KotlinCompilerExternalTypeUsage> = emptyList(),
        val externalCallableUsages: List<KotlinCompilerExternalCallableUsage> = emptyList(),
        val declarations: Map<SymbolId, KotlinCompilerDeclarationEvidence> = emptyMap(),
    ) : KotlinCompilerSymbolsResult

    data class Refused(
        val reason: Diagnostic,
        override val attestation: KotlinCompilerDiagnosticsAttestation,
    ) : KotlinCompilerSymbolsResult

    data class Error(
        val failure: Diagnostic,
        override val attestation: KotlinCompilerDiagnosticsAttestation,
    ) : KotlinCompilerSymbolsResult
}

/** One-shot external compiler-backed diagnostics and bounded JVM declaration symbols. */
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
    fun analyze(snapshot: ProjectSnapshot): KotlinCompilerDiagnosticsResult = analyzeInternal(snapshot, emptyList(), null)

    /**
     * Supplies compiler-produced classes only while their immutable disposable overlay is alive.
     * The consumer must not retain the path or mutate the output directory.
     */
    fun analyzeWithCompiledOutput(
        snapshot: ProjectSnapshot,
        consumer: (Path) -> Unit,
    ): KotlinCompilerDiagnosticsResult = analyzeInternal(snapshot, emptyList(), consumer)

    /** Adds hash-bound ephemeral JVM classes without changing the declared project classpath. */
    fun analyzeWithAdditionalClasspath(
        snapshot: ProjectSnapshot,
        additionalClasspath: List<Path>,
    ): KotlinCompilerDiagnosticsResult = analyzeInternal(snapshot, additionalClasspath, null)

    private fun analyzeInternal(
        snapshot: ProjectSnapshot,
        additionalClasspath: List<Path>,
        compiledOutputConsumer: ((Path) -> Unit)?,
    ): KotlinCompilerDiagnosticsResult {
        var ephemeralClasspathHash = ""
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
            ephemeralClasspathHash = ephemeralClasspathHash,
        )
        val ephemeralClasspath = runCatching { validateEphemeralClasspath(additionalClasspath) }.getOrElse {
            return refused("kotlin.ephemeralClasspathInvalid", "Ephemeral JVM classpath evidence is invalid", attestation())
        }
        ephemeralClasspathHash = hashEphemeralClasspath(ephemeralClasspath)
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
        val projectClasspath = resolveClasspath(snapshot, sourceSets.flatMap { it.classpathEntries }.distinct())
            .getOrElse { return KotlinCompilerDiagnosticsResult.Refused(it.asDiagnostic(), attestation()) }
        val stdlib = compilerStdlib() ?: return refused(
            "kotlin.compilerStdlibUnavailable",
            "Kotlin compiler semantics require an explicitly attested matching stdlib runtime",
            attestation(),
        )
        if (compilerAnnotations() == null) return refused(
            "kotlin.compilerRuntimeUnavailable",
            "Kotlin compiler semantics require the qualified annotations runtime",
            attestation(),
        )
        val classpath = (projectClasspath + ephemeralClasspath + listOf(stdlib)).distinct()
        if (classpath.size > MAX_CLASSPATH_ENTRIES + 1) return refused(
            "kotlin.compilerClasspathLimit",
            "Kotlin project classpath exceeds $MAX_CLASSPATH_ENTRIES entries",
            attestation(),
        )

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
            val parsed = parse(execution.output, snapshot, overlay, attestation(execution.provenance))
            if (compiledOutputConsumer != null && parsed is KotlinCompilerDiagnosticsResult.Available &&
                parsed.diagnostics.none { it.severity == Diagnostic.Severity.ERROR }) {
                try {
                    compiledOutputConsumer(outputDirectory)
                } catch (failure: Exception) {
                    throw CompiledOutputConsumerException(failure)
                }
                val outputMutation = overlay.verifySourcesUnchanged()
                if (outputMutation.isNotEmpty()) return KotlinCompilerDiagnosticsResult.Error(
                    outputMutation.first(), attestation(execution.provenance),
                )
            }
            parsed
        } catch (failure: KotlinCompilerExecutionException) {
            error(failure.code, failure.message ?: "Kotlin compiler diagnostics failed", attestation(failure.provenance))
        } catch (_: CompiledOutputConsumerException) {
            error("kotlin.compiledOutputConsumerFailed", "Kotlin compiled-output evidence consumer failed", attestation())
        } catch (_: Exception) {
            error("kotlin.compilerDiagnosticsFailed", "Kotlin compiler diagnostics failed", attestation())
        } finally {
            overlay.close()
        }
    }

    fun analyzeSymbols(snapshot: ProjectSnapshot): KotlinCompilerSymbolsResult = when (val result = analyze(snapshot)) {
        is KotlinCompilerDiagnosticsResult.Available -> {
            val attestation = result.attestation.copy(backend = SYMBOL_BACKEND)
            when {
                result.symbols != null -> KotlinCompilerSymbolsResult.Available(
                    result.symbols, attestation, result.usages, result.externalTypeUsages,
                    result.externalCallableUsages, result.declarations,
                )
                result.symbolFailure?.code == "kotlin.compilerSymbolsInvalid" ->
                    KotlinCompilerSymbolsResult.Error(result.symbolFailure, attestation)
                else -> KotlinCompilerSymbolsResult.Refused(
                    result.symbolFailure ?: diagnostic(
                        "kotlin.symbolsUnavailable", "Kotlin compiler symbols are unavailable for this snapshot",
                    ),
                    attestation,
                )
            }
        }
        is KotlinCompilerDiagnosticsResult.Refused -> KotlinCompilerSymbolsResult.Refused(
            result.reason, result.attestation.copy(backend = SYMBOL_BACKEND),
        )
        is KotlinCompilerDiagnosticsResult.Error -> KotlinCompilerSymbolsResult.Error(
            result.failure, result.attestation.copy(backend = SYMBOL_BACKEND),
        )
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
        val symbols = parseSymbols(payload, snapshot, overlay)
        return KotlinCompilerDiagnosticsResult.Available(
            diagnostics,
            attestation,
            symbols = symbols.getOrNull()?.index,
            usages = symbols.getOrNull()?.usages.orEmpty(),
            externalTypeUsages = symbols.getOrNull()?.externalTypeUsages.orEmpty(),
            externalCallableUsages = symbols.getOrNull()?.externalCallableUsages.orEmpty(),
            declarations = symbols.getOrNull()?.declarations.orEmpty(),
            symbolFailure = symbols.exceptionOrNull()?.asSymbolDiagnostic(),
        )
    }

    private fun parseSymbols(
        payload: JsonObject,
        snapshot: ProjectSnapshot,
        overlay: SemanticWorkspaceOverlay,
    ): Result<ParsedKotlinSymbols> = runCatching {
        if (payload.boolean("symbolsComplete") != true) {
            val code = payload.string("symbolFailure")?.takeIf { it in SYMBOL_REFUSAL_CODES }
                ?: "kotlin.compilerSymbolsInvalid"
            throw SymbolPayloadException(code)
        }
        val encoded = payload["symbols"] as? JsonArray
            ?: throw SymbolPayloadException("kotlin.compilerSymbolsInvalid")
        check(encoded.size <= MAX_SYMBOLS) { "Kotlin compiler symbol limit exceeded" }
        val overlayRoot = overlay.root.toRealPath()
        val sourcePaths = snapshot.files.associateBy { it.path.normalize() }
        val ids = linkedSetOf<SymbolId>()
        val offsetMappers = mutableMapOf<Path, CompilerOffsetMapper>()
        val identityIds = linkedMapOf<String, SymbolId>()
        val declarations = linkedMapOf<SymbolId, KotlinCompilerDeclarationEvidence>()
        val symbols = encoded.map { item ->
            val value = item as? JsonObject ?: error("Kotlin compiler symbol is not an object")
            check(value.keys == SYMBOL_FIELDS) { "Kotlin compiler symbol fields are invalid" }
            val identity = value.string("identity")?.takeIf { it.length in 1..MAX_SYMBOL_IDENTITY_CHARS }
                ?: error("Kotlin compiler symbol identity is invalid")
            val name = value.string("name")?.takeIf {
                it.length in 1..MAX_SYMBOL_NAME_CHARS && JVM_NAME.matches(it)
            } ?: error("Kotlin compiler symbol name is invalid")
            val kind = value.string("kind")?.let { runCatching { Symbol.Kind.valueOf(it) }.getOrNull() }
                ?.takeIf { it in SYMBOL_KINDS } ?: error("Kotlin compiler symbol kind is invalid")
            val owner = value.string("owner")?.takeIf {
                it.length in 1..MAX_SYMBOL_IDENTITY_CHARS && SYMBOL_IDENTITY.matches(it)
            } ?: error("Kotlin compiler symbol owner is invalid")
            val descriptor = value.string("descriptor")?.takeIf { it.length <= MAX_JVM_DESCRIPTOR_CHARS }
                ?: error("Kotlin compiler symbol descriptor is invalid")
            val id = if (kind == Symbol.Kind.FUNCTION) {
                check(JVM_METHOD_DESCRIPTOR.matches(descriptor) && identity == "$owner#$name$descriptor") {
                    "Kotlin compiler callable identity is invalid"
                }
                SymbolId("kotlin-jvm-callable-v1:${sha256("kotlin-jvm-callable-v1\u0000$owner\u0000$name\u0000$descriptor")}")
            } else if (kind == Symbol.Kind.PROPERTY) {
                check(JVM_FIELD_DESCRIPTOR.matches(descriptor) && identity == "$owner#property:$name:$descriptor") {
                    "Kotlin compiler property identity is invalid"
                }
                SymbolId("kotlin-jvm-property-v1:${sha256("kotlin-jvm-property-v1\u0000$owner\u0000$name\u0000$descriptor")}")
            } else if (kind in setOf(Symbol.Kind.PARAMETER, Symbol.Kind.TYPE_PARAMETER)) {
                val methodDescriptor = descriptor.substringBeforeLast('@', "")
                val ordinal = descriptor.substringAfterLast('@', "").toIntOrNull()
                val family = if (kind == Symbol.Kind.PARAMETER) "parameter" else "type-parameter"
                val callableName = identity.substringAfter("#$family:", "").substringBefore('(')
                check(JVM_METHOD_DESCRIPTOR.matches(methodDescriptor) && ordinal != null && ordinal >= 0 &&
                    JVM_NAME.matches(callableName) && identity == "$owner#$family:$callableName$descriptor") {
                    "Kotlin compiler $family identity is invalid"
                }
                SymbolId("kotlin-jvm-$family-v1:${sha256("kotlin-jvm-$family-v1\u0000$owner\u0000$callableName\u0000$descriptor")}")
            } else {
                check(descriptor.isEmpty() && identity == owner && SYMBOL_IDENTITY.matches(identity) &&
                    identity.substringAfterLast('.').substringAfterLast('$') == name) {
                    "Kotlin compiler type identity is invalid"
                }
                SymbolId("kotlin-jvm-type-v1:${sha256("kotlin-jvm-type-v1\u0000$identity")}")
            }
            val visibility = value.string("visibility")?.let {
                runCatching { KotlinDeclarationVisibility.valueOf(it) }.getOrNull()
            } ?: error("Kotlin compiler symbol visibility is invalid")
            val selectionText = value.string("selectionText")?.takeIf { it.length in 1..MAX_SYMBOL_NAME_CHARS }
                ?: error("Kotlin compiler symbol selection text is invalid")
            check(selectionText == name ||
                (kind == Symbol.Kind.OBJECT && name == "Companion" && selectionText == "object")) {
                "Kotlin compiler symbol selection does not match its identity"
            }
            val rawPath = value.string("path") ?: error("Kotlin compiler symbol path is missing")
            val reported = Path.of(rawPath).toAbsolutePath().normalize()
            check(!Files.isSymbolicLink(reported) && Files.isRegularFile(reported, LinkOption.NOFOLLOW_LINKS)) {
                "Kotlin compiler symbol path is unsafe"
            }
            val canonical = reported.toRealPath()
            check(canonical.startsWith(overlayRoot) && canonical != overlayRoot) {
                "Kotlin compiler symbol path escapes overlay"
            }
            val relative = overlayRoot.relativize(canonical).normalize()
            val source = sourcePaths[relative] ?: error("Kotlin compiler symbol source is outside snapshot")
            val compilerStart = value.int("startOffset") ?: error("Kotlin compiler symbol start is invalid")
            val compilerEnd = value.int("endOffset") ?: error("Kotlin compiler symbol end is invalid")
            val offsetMapper = offsetMappers.getOrPut(relative) { CompilerOffsetMapper(source.content) }
            val start = offsetMapper.sourceOffset(compilerStart) ?: error("Kotlin compiler symbol start is invalid")
            val end = offsetMapper.sourceOffset(compilerEnd) ?: error("Kotlin compiler symbol end is invalid")
            check(start >= 0 && end > start && end <= source.content.length &&
                source.content.substring(start, end) == selectionText) {
                "Kotlin compiler symbol range is invalid"
            }
            check(ids.add(id) && identityIds.put(identity, id) == null &&
                declarations.put(id, KotlinCompilerDeclarationEvidence(
                    id = id,
                    visibility = visibility,
                    jvmIdentity = identity,
                    jvmOwner = owner,
                    jvmDescriptor = descriptor,
                )) == null) {
                "Kotlin compiler symbol identity is duplicated"
            }
            Symbol(
                id = id,
                name = name,
                kind = kind,
                location = SourceLocation(relative, SourceRange(position(source.content, start), position(source.content, end))),
                languageId = "kotlin",
            )
        }.sortedBy { it.id.value }
        val index = SymbolIndex(symbols)
        val usages = parseUsages(payload, sourcePaths, overlayRoot, identityIds, offsetMappers)
        ParsedKotlinSymbols(index, usages.internal, usages.externalTypes, usages.externalCallables, declarations.toMap())
    }

    private data class ParsedUsageEvidence(
        val internal: List<KotlinCompilerResolvedUsage>,
        val externalTypes: List<KotlinCompilerExternalTypeUsage>,
        val externalCallables: List<KotlinCompilerExternalCallableUsage>,
    )

    private fun parseUsages(
        payload: JsonObject,
        sourcePaths: Map<Path, org.refactorkit.core.SourceFile>,
        overlayRoot: Path,
        identityIds: Map<String, SymbolId>,
        offsetMappers: MutableMap<Path, CompilerOffsetMapper>,
    ): ParsedUsageEvidence {
        if (payload.boolean("usagesComplete") != true) {
            throw SymbolPayloadException("kotlin.compilerUsageEnvelopeInvalid")
        }
        val encoded = payload["usages"] as? JsonArray
            ?: throw SymbolPayloadException("kotlin.compilerUsageEnvelopeInvalid")
        if (encoded.size > MAX_USAGES) throw SymbolPayloadException("kotlin.compilerUsageEnvelopeInvalid")
        val unique = linkedSetOf<String>()
        val internal = mutableListOf<KotlinCompilerResolvedUsage>()
        val externalTypes = mutableListOf<KotlinCompilerExternalTypeUsage>()
        val externalCallables = mutableListOf<KotlinCompilerExternalCallableUsage>()
        encoded.forEach { item ->
            val value = item as? JsonObject
                ?: throw SymbolPayloadException("kotlin.compilerUsageFieldsInvalid")
            if (value.keys != USAGE_FIELDS) throw SymbolPayloadException("kotlin.compilerUsageFieldsInvalid")
            val targetIdentity = value.string("targetIdentity")?.takeIf {
                it.length in 1..(MAX_SYMBOL_IDENTITY_CHARS + EXTERNAL_JVM_CALLABLE_PREFIX.length + MAX_SYMBOL_NAME_CHARS)
            } ?: throw SymbolPayloadException("kotlin.compilerUsageTargetEncodingInvalid")
            val targetId = identityIds[targetIdentity]
            val externalTypeIdentity = targetIdentity.removePrefix(EXTERNAL_JVM_TYPE_PREFIX).takeIf {
                targetId == null && targetIdentity.startsWith(EXTERNAL_JVM_TYPE_PREFIX) &&
                    it.length in 1..MAX_SYMBOL_IDENTITY_CHARS && SYMBOL_IDENTITY.matches(it)
            }
            val externalCallableIdentity = targetIdentity.removePrefix(EXTERNAL_JVM_CALLABLE_PREFIX).takeIf {
                targetId == null && targetIdentity.startsWith(EXTERNAL_JVM_CALLABLE_PREFIX)
            }?.let { identity ->
                val owner = identity.substringBeforeLast('#', "")
                val name = identity.substringAfterLast('#', "")
                (owner to name).takeIf { SYMBOL_IDENTITY.matches(owner) && JVM_NAME.matches(name) }
            }
            if (targetId == null && externalTypeIdentity == null && externalCallableIdentity == null) {
                throw SymbolPayloadException("kotlin.compilerUsageTargetInvalid")
            }
            val selectionText = value.string("selectionText")?.takeIf {
                it.length in 1..MAX_SYMBOL_NAME_CHARS && JVM_NAME.matches(it)
            } ?: throw SymbolPayloadException("kotlin.compilerUsageSelectionInvalid")
            val rawPath = value.string("path")
                ?: throw SymbolPayloadException("kotlin.compilerUsagePathInvalid")
            val reported = runCatching { Path.of(rawPath).toAbsolutePath().normalize() }.getOrElse {
                throw SymbolPayloadException("kotlin.compilerUsagePathInvalid")
            }
            if (Files.isSymbolicLink(reported) || !Files.isRegularFile(reported, LinkOption.NOFOLLOW_LINKS)) {
                throw SymbolPayloadException("kotlin.compilerUsagePathInvalid")
            }
            val canonical = runCatching { reported.toRealPath() }.getOrElse {
                throw SymbolPayloadException("kotlin.compilerUsagePathInvalid")
            }
            if (!canonical.startsWith(overlayRoot) || canonical == overlayRoot) {
                throw SymbolPayloadException("kotlin.compilerUsagePathInvalid")
            }
            val relative = overlayRoot.relativize(canonical).normalize()
            val source = sourcePaths[relative]
                ?: throw SymbolPayloadException("kotlin.compilerUsagePathInvalid")
            val compilerStart = value.int("startOffset")
                ?: throw SymbolPayloadException("kotlin.compilerUsageOffsetInvalid")
            val compilerEnd = value.int("endOffset")
                ?: throw SymbolPayloadException("kotlin.compilerUsageOffsetInvalid")
            val offsetMapper = offsetMappers.getOrPut(relative) { CompilerOffsetMapper(source.content) }
            val start = offsetMapper.sourceOffset(compilerStart)
                ?: throw SymbolPayloadException("kotlin.compilerUsageOffsetInvalid")
            val end = offsetMapper.sourceOffset(compilerEnd)
                ?: throw SymbolPayloadException("kotlin.compilerUsageOffsetInvalid")
            if (start < 0 || end <= start || end > source.content.length ||
                source.content.substring(start, end) != selectionText) {
                throw SymbolPayloadException("kotlin.compilerUsageRangeInvalid")
            }
            val targetKey = targetId?.value ?: externalTypeIdentity ?: externalCallableIdentity!!.let { "${it.first}#${it.second}" }
            if (!unique.add("$relative\u0000$start\u0000$end\u0000$targetKey")) {
                throw SymbolPayloadException("kotlin.compilerUsageDuplicate")
            }
            val location = SourceLocation(relative, SourceRange(position(source.content, start), position(source.content, end)))
            when {
                targetId != null -> internal += KotlinCompilerResolvedUsage(targetId, location)
                externalTypeIdentity != null -> externalTypes += KotlinCompilerExternalTypeUsage(externalTypeIdentity, location)
                else -> externalCallables += KotlinCompilerExternalCallableUsage(
                    externalCallableIdentity!!.first, externalCallableIdentity.second, location,
                )
            }
        }
        return ParsedUsageEvidence(
            internal.sortedWith(compareBy({ it.location.path.toString() }, { it.location.range.start.line },
                { it.location.range.start.character }, { it.targetId.value })),
            externalTypes.sortedWith(compareBy({ it.location.path.toString() }, { it.location.range.start.line },
                { it.location.range.start.character }, { it.jvmBinaryName })),
            externalCallables.sortedWith(compareBy({ it.location.path.toString() }, { it.location.range.start.line },
                { it.location.range.start.character }, { it.jvmOwner }, { it.callableName })),
        )
    }

    private fun Throwable.asSymbolDiagnostic(): Diagnostic = when (this) {
        is SymbolPayloadException -> diagnostic(
            code,
            when (code) {
                "kotlin.symbolCompilationFailed" -> "Kotlin symbols require a snapshot that compiles successfully"
                "kotlin.symbolIdentityCollision" -> "Kotlin declarations produced a duplicate JVM identity"
                "kotlin.symbolLimitExceeded" -> "Kotlin symbol result exceeded the bounded limit"
                "kotlin.symbolNameLimitExceeded" -> "Kotlin symbol name or identity exceeded the bounded limit"
                "kotlin.symbolBinaryEvidenceMissing" -> "Kotlin declaration lacks matching JVM binary evidence"
                "kotlin.symbolJvmNameUnsupported" -> "Kotlin declaration uses an unsupported JVM name shape"
                "kotlin.symbolLocationUnavailable" -> "Kotlin declaration lacks an exact compiler PSI location"
                "kotlin.symbolDeclarationKindUnsupported" ->
                    "Kotlin symbol indexing accepts bounded JVM declarations only"
                "kotlin.symbolCallableEvidenceMissing" ->
                    "Kotlin function lacks an exact generated JVM method"
                "kotlin.symbolCallableEvidenceAmbiguous" ->
                    "Kotlin overloaded or bridged function name has ambiguous JVM evidence"
                "kotlin.symbolCallableEvidenceLimitExceeded" ->
                    "Kotlin callable-owner method evidence exceeds the bounded limit"
                "kotlin.symbolDescriptorLimitExceeded" ->
                    "Kotlin callable JVM descriptor exceeds the bounded limit"
                "kotlin.usageTargetCollision" -> "Kotlin usage target identity is duplicated"
                "kotlin.usageJvmTargetInvalid" -> "Kotlin usage JVM target is invalid"
                "kotlin.usageFirResolutionFailed" -> "Kotlin FIR usage resolution failed"
                "kotlin.usageTargetLocationUnavailable" -> "Kotlin usage target lacks an exact source location"
                "kotlin.usageTargetMissing" -> "Kotlin usage target is absent from the proven declaration catalogue"
                "kotlin.usageLocationUnavailable" -> "Kotlin usage lacks an exact source range"
                "kotlin.usagePathInvalid" -> "Kotlin usage path is invalid"
                "kotlin.usageLimitExceeded" -> "Kotlin usage result exceeded the bounded limit"
                "kotlin.usageExtractionFailed" -> "Kotlin compiler usage extraction failed"
                "kotlin.compilerUsageTargetInvalid" -> "Kotlin compiler usage target payload is invalid"
                "kotlin.compilerUsagePathInvalid" -> "Kotlin compiler usage path payload is invalid"
                "kotlin.compilerUsageRangeInvalid" -> "Kotlin compiler usage range payload is invalid"
                "kotlin.compilerUsageDuplicate" -> "Kotlin compiler usage payload contains duplicates"
                "kotlin.compilerUsageEnvelopeInvalid" -> "Kotlin compiler usage payload envelope is invalid"
                "kotlin.compilerUsageFieldsInvalid" -> "Kotlin compiler usage payload fields are invalid"
                "kotlin.compilerUsageTargetEncodingInvalid" -> "Kotlin compiler usage target encoding is invalid"
                "kotlin.compilerUsageSelectionInvalid" -> "Kotlin compiler usage selection is invalid"
                "kotlin.compilerUsageOffsetInvalid" -> "Kotlin compiler usage offset is invalid"
                else -> "Kotlin compiler symbol extraction failed"
            },
        )
        else -> diagnostic("kotlin.compilerSymbolsInvalid", "Kotlin compiler symbol payload is invalid")
    }

    private class CompilerOffsetMapper(private val content: String) {
        private val offsets: IntArray? = if ("\r\n" in content) buildOffsets(content) else null

        fun sourceOffset(compilerOffset: Int): Int? {
            if (compilerOffset < 0) return null
            val mapped = offsets ?: return compilerOffset.takeIf { it <= content.length }
            return mapped.getOrNull(compilerOffset)
        }

        private fun buildOffsets(content: String): IntArray {
            var crlfCount = 0
            var index = 0
            while (index + 1 < content.length) {
                if (content[index] == '\r' && content[index + 1] == '\n') {
                    crlfCount++
                    index += 2
                } else index++
            }
            val result = IntArray(content.length - crlfCount + 1)
            var sourceOffset = 0
            var compilerOffset = 0
            while (sourceOffset < content.length) {
                sourceOffset += if (content[sourceOffset] == '\r' && sourceOffset + 1 < content.length &&
                    content[sourceOffset + 1] == '\n') 2 else 1
                compilerOffset++
                result[compilerOffset] = sourceOffset
            }
            return result
        }
    }

    private fun position(content: String, offset: Int): SourcePosition {
        val previousNewline = content.lastIndexOf('\n', offset - 1)
        val line = content.take(offset).count { it == '\n' }
        return SourcePosition(line, offset - previousNewline - 1)
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

    private fun compilerStdlib(): Path? {
        val expected = "kotlin-stdlib-${toolchain.provenance.kotlinVersion}.jar"
        return toolchain.compilerClasspath.singleOrNull { path ->
            path.fileName.toString() == expected && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path)
        }
    }

    private fun compilerAnnotations(): Path? = toolchain.compilerClasspath.singleOrNull { path ->
        path.fileName.toString() == QUALIFIED_ANNOTATIONS_JAR && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(path)
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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

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

    private fun validateEphemeralClasspath(entries: List<Path>): List<Path> {
        require(entries.size <= MAX_EPHEMERAL_CLASSPATH_ENTRIES)
        return entries.map { entry ->
            val normalized = entry.toAbsolutePath().normalize()
            require(!Files.isSymbolicLink(normalized) && (
                Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) ||
                    (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) && normalized.fileName.toString().endsWith(".jar"))
                ))
            normalized.toRealPath()
        }.distinct()
    }

    private fun hashEphemeralClasspath(entries: List<Path>): String {
        if (entries.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        var files = 0
        var bytes = 0L
        entries.sortedBy(Path::toString).forEachIndexed { index, root ->
            if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
                files++
                bytes += Files.size(root)
                require(files <= MAX_EPHEMERAL_CLASS_FILES && bytes <= MAX_EPHEMERAL_CLASS_BYTES)
                digest.update(index.toString().toByteArray(Charsets.UTF_8))
                digest.update(root.fileName.toString().toByteArray(Charsets.UTF_8))
                Files.newInputStream(root).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                return@forEachIndexed
            }
            Files.walk(root).use { stream ->
                stream.filter { it != root }.sorted().forEach { path ->
                    require(!Files.isSymbolicLink(path))
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
                    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.fileName.toString().endsWith(".class"))
                    files++
                    bytes += Files.size(path)
                    require(files <= MAX_EPHEMERAL_CLASS_FILES && bytes <= MAX_EPHEMERAL_CLASS_BYTES)
                    digest.update(index.toString().toByteArray(Charsets.UTF_8))
                    digest.update(root.relativize(path).toString().replace('\\', '/').toByteArray(Charsets.UTF_8))
                    Files.newInputStream(path).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                }
            }
        }
        require(files > 0)
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
    private class SymbolPayloadException(val code: String) : RuntimeException(code)

    companion object {
        const val BACKEND = "kotlin-compiler-diagnostics-k2-v1"
        const val SYMBOL_BACKEND = "kotlin-compiler-jvm-declarations-k2-v1"
        const val REQUEST_TIMEOUT_MILLIS = 30_000L
        const val MAX_OUTPUT_BYTES = 8L * 1024L * 1024L
        const val MAX_STDERR_BYTES = 64 * 1024
        const val COMPILER_HEAP_MIB = 512
        const val MAX_SOURCE_FILES = 96
        const val MAX_CLASSPATH_ENTRIES = 128
        const val MAX_DIAGNOSTICS = 500
        const val MAX_SYMBOLS = 500
        private const val QUALIFIED_ANNOTATIONS_JAR = "annotations-13.0.jar"
        const val MAX_MESSAGE_CHARS = 4_096
        private const val MAX_SYMBOL_NAME_CHARS = 512
        private const val MAX_SYMBOL_IDENTITY_CHARS = 2_048
        private const val MAX_JVM_DESCRIPTOR_CHARS = 1_024
        private const val MAX_USAGES = 2_000
        private const val MAX_EPHEMERAL_CLASSPATH_ENTRIES = 4
        private const val MAX_EPHEMERAL_CLASS_FILES = 10_000
        private const val MAX_EPHEMERAL_CLASS_BYTES = 128L * 1024L * 1024L
        private const val EXTERNAL_JVM_TYPE_PREFIX = "external-jvm-type-v1:"
        private const val EXTERNAL_JVM_CALLABLE_PREFIX = "external-jvm-callable-v1:"
        private const val MAX_XML_BYTES = 6 * 1024 * 1024
        private val JSON = Json { isLenient = false; ignoreUnknownKeys = false }
        private val SEQUENCE = AtomicLong(1)
        private val DIAGNOSTIC_CODE = Regex("^\\[([A-Z][A-Z0-9_]{0,63})]")
        private val JVM_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val SYMBOL_IDENTITY = Regex("[A-Za-z_][A-Za-z0-9_]*(?:[.$][A-Za-z_][A-Za-z0-9_]*)*")
        private val JVM_METHOD_DESCRIPTOR = Regex(
            "\\((?:\\[*(?:[BCDFIJSZ]|L[A-Za-z0-9_$/]+;))*\\)(?:V|\\[*(?:[BCDFIJSZ]|L[A-Za-z0-9_$/]+;))",
        )
        private val JVM_FIELD_DESCRIPTOR = Regex("\\[*(?:[BCDFIJSZ]|L[A-Za-z0-9_$/]+;)")
        private val SYMBOL_FIELDS = setOf(
            "identity", "name", "kind", "path", "owner", "descriptor", "selectionText", "visibility", "startOffset", "endOffset",
        )
        private val USAGE_FIELDS = setOf(
            "path", "targetIdentity", "selectionText", "startOffset", "endOffset",
        )
        private val SYMBOL_KINDS = setOf(
            Symbol.Kind.CLASS,
            Symbol.Kind.OBJECT,
            Symbol.Kind.INTERFACE,
            Symbol.Kind.ENUM,
            Symbol.Kind.ANNOTATION,
            Symbol.Kind.FUNCTION,
            Symbol.Kind.PROPERTY,
            Symbol.Kind.PARAMETER,
            Symbol.Kind.TYPE_PARAMETER,
        )
        private val SYMBOL_REFUSAL_CODES = setOf(
            "kotlin.symbolCompilationFailed",
            "kotlin.symbolIdentityCollision",
            "kotlin.symbolLimitExceeded",
            "kotlin.symbolNameLimitExceeded",
            "kotlin.symbolBinaryEvidenceMissing",
            "kotlin.symbolJvmNameUnsupported",
            "kotlin.symbolLocationUnavailable",
            "kotlin.symbolDeclarationKindUnsupported",
            "kotlin.symbolCallableEvidenceMissing",
            "kotlin.symbolCallableEvidenceAmbiguous",
            "kotlin.symbolCallableEvidenceLimitExceeded",
            "kotlin.symbolDescriptorLimitExceeded",
            "kotlin.symbolExtractionFailed",
            "kotlin.usageTargetCollision",
            "kotlin.usageJvmTargetInvalid",
            "kotlin.usageFirResolutionFailed",
            "kotlin.usageTargetLocationUnavailable",
            "kotlin.usageTargetMissing",
            "kotlin.usageLocationUnavailable",
            "kotlin.usagePathInvalid",
            "kotlin.usageLimitExceeded",
            "kotlin.usageExtractionFailed",
            "kotlin.compilerUsageTargetInvalid",
            "kotlin.compilerUsagePathInvalid",
            "kotlin.compilerUsageRangeInvalid",
            "kotlin.compilerUsageDuplicate",
            "kotlin.compilerUsageEnvelopeInvalid",
            "kotlin.compilerUsageFieldsInvalid",
            "kotlin.compilerUsageTargetEncodingInvalid",
            "kotlin.compilerUsageSelectionInvalid",
            "kotlin.compilerUsageOffsetInvalid",
        )
        private val SUPPORTED_JVM_TARGETS = setOf("1.8", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21")
    }
}
