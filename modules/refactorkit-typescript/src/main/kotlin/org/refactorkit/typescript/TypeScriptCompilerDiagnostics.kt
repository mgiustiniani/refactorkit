package org.refactorkit.typescript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.core.SemanticProcessSpec
import org.refactorkit.core.SemanticWorkspaceOverlay
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.treesitter.ExternalSemanticDiagnostics
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Exact, request-correlated diagnostics from the hash-bound TypeScript compiler API. */
internal class TypeScriptCompilerDiagnostics(
    private val toolchain: TypeScriptSemanticToolchain,
    private val projectModel: TypeScriptProjectModel,
) {
    fun analyze(snapshot: ProjectSnapshot, auxiliaryFiles: List<SourceFile>): ExternalSemanticDiagnostics {
        if (projectModel.projects.isEmpty() || projectModel.projects.size > MAX_PROJECTS) {
            return unavailable("typescript.compilerDiagnosticsProjectLimit", "Exact compiler diagnostics require 1..$MAX_PROJECTS projects")
        }
        if (!Files.isRegularFile(toolchain.typeScriptCompilerEntrypoint)) {
            return unavailable("typescript.compilerDiagnosticsUnavailable", "Hash-bound TypeScript compiler API entrypoint is unavailable")
        }
        val paths = snapshot.files.associateBy { it.path.normalize() }.toMutableMap()
        auxiliaryFiles.forEach { paths.putIfAbsent(it.path.normalize(), it) }
        val semanticSnapshot = snapshot.copy(files = paths.values.sortedBy { it.path.toString() })
        val overlay = runCatching { SemanticWorkspaceOverlay.create(semanticSnapshot) }.getOrElse {
            return unavailable("typescript.compilerDiagnosticsOverlayFailed", it.message ?: "Compiler diagnostics overlay failed")
        }
        val bridgeDirectory = Files.createTempDirectory("refactorkit-ts-diagnostics-")
        val bridge = bridgeDirectory.resolve("typescript-diagnostics-bridge.cjs")
        return try {
            val bytes = javaClass.getResourceAsStream(BRIDGE_RESOURCE)?.use { it.readNBytes(MAX_BRIDGE_BYTES + 1) }
                ?: return unavailable("typescript.compilerDiagnosticsBridgeMissing", "Compiler diagnostics bridge resource is missing")
            if (bytes.size > MAX_BRIDGE_BYTES) return unavailable(
                "typescript.compilerDiagnosticsBridgeInvalid", "Compiler diagnostics bridge exceeds its size limit",
            )
            Files.write(bridge, bytes)
            val configs = projectModel.projects.map { it.configPath.normalize().toString().replace('\\', '/') }.sorted()
            val arguments = listOf(
                "--max-old-space-size=$COMPILER_HEAP_MIB",
                bridge.toString(),
                toolchain.typeScriptCompilerEntrypoint.toAbsolutePath().normalize().toString(),
                overlay.root.toString(),
                snapshot.hash,
            ) + configs
            val output = ExternalSemanticProcessManager(maxProcesses = 1).use { manager ->
                val process = manager.launch(SemanticProcessSpec(
                    id = "typescript-compiler-${SEQUENCE.getAndIncrement()}",
                    executable = toolchain.nodeExecutable,
                    arguments = arguments,
                    workingDirectory = overlay.root,
                    limits = SemanticProcessLimits(
                        maxStdoutBytes = MAX_OUTPUT_BYTES,
                        maxStderrBytes = MAX_STDERR_BYTES,
                        gracefulShutdownMillis = 1_000,
                    ),
                ))
                val reader = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "refactorkit-ts-compiler-output").apply { isDaemon = true }
                }
                try {
                    val future = reader.submit<String> { process.output.bufferedReader(Charsets.UTF_8).readText() }
                    if (!process.awaitExit(TIMEOUT_MILLIS)) {
                        process.cancel()
                        return unavailable("typescript.compilerDiagnosticsTimeout", "Exact compiler diagnostics timed out")
                    }
                    val text = future.get(2, TimeUnit.SECONDS)
                    if (process.exitCode != 0) {
                        return unavailable(
                            "typescript.compilerDiagnosticsFailed",
                            "Exact compiler diagnostics process failed: ${process.stderrText().take(MAX_STDERR_MESSAGE)}",
                        )
                    }
                    text
                } finally {
                    reader.shutdownNow()
                    process.close()
                }
            }
            val mutations = overlay.verifySourcesUnchanged()
            if (mutations.isNotEmpty()) return ExternalSemanticDiagnostics.Unavailable(mutations.first())
            parse(output, snapshot)
        } catch (failure: Exception) {
            unavailable("typescript.compilerDiagnosticsFailed", failure.message ?: "Exact compiler diagnostics failed")
        } finally {
            overlay.close()
            runCatching { Files.deleteIfExists(bridge) }
            runCatching { Files.deleteIfExists(bridgeDirectory) }
        }
    }

    private fun parse(output: String, snapshot: ProjectSnapshot): ExternalSemanticDiagnostics {
        val root = runCatching { JSON.parseToJsonElement(output).jsonObject }.getOrNull()
            ?: return unavailable("typescript.compilerDiagnosticsInvalid", "Compiler diagnostics returned invalid JSON")
        if (root.int("schema") != 1 || root.boolean("complete") != true) {
            val failure = root.string("failure")?.take(MAX_DIAGNOSTIC_MESSAGE)
            return unavailable("typescript.compilerDiagnosticsIncomplete", failure ?: "Compiler diagnostics were incomplete")
        }
        if (root.string("snapshotHash") != snapshot.hash) return unavailable(
            "typescript.compilerDiagnosticsSnapshotMismatch", "Compiler diagnostics did not attest the requested snapshot",
        )
        val diagnostics = (root["diagnostics"] as? JsonArray)
            ?: return unavailable("typescript.compilerDiagnosticsInvalid", "Compiler diagnostics payload is missing")
        if (diagnostics.size > MAX_DIAGNOSTICS) return unavailable(
            "typescript.compilerDiagnosticsLimit", "Compiler diagnostics exceed $MAX_DIAGNOSTICS entries",
        )
        val parsed = diagnostics.mapNotNull { element ->
            val value = element as? JsonObject ?: return@mapNotNull null
            val message = value.string("message")?.takeIf(String::isNotBlank)?.take(MAX_DIAGNOSTIC_MESSAGE)
                ?: return@mapNotNull null
            val category = value.int("category") ?: 3
            val location = value.string("file")?.let { raw ->
                val path = runCatching { Path.of(raw).normalize() }.getOrNull()
                    ?.takeIf { !it.isAbsolute && !it.startsWith("..") } ?: return@let null
                val line = value.int("line") ?: return@let null
                val character = value.int("character") ?: return@let null
                val endLine = value.int("endLine") ?: line
                val endCharacter = value.int("endCharacter") ?: character
                if (minOf(line, character, endLine, endCharacter) < 0 || endLine < line ||
                    endLine == line && endCharacter < character) return@let null
                SourceLocation(path, SourceRange(SourcePosition(line, character), SourcePosition(endLine, endCharacter)))
            }
            Diagnostic(
                message = message,
                severity = when (category) {
                    1 -> Diagnostic.Severity.ERROR
                    0 -> Diagnostic.Severity.WARNING
                    else -> Diagnostic.Severity.INFO
                },
                location = location,
                code = value.string("code")?.take(64),
                evidence = DiagnosticEvidence.COMPILER,
                category = DiagnosticCategory.TYPE_RESOLUTION,
            )
        }
        if (parsed.size != diagnostics.size) return unavailable(
            "typescript.compilerDiagnosticsInvalid", "Compiler diagnostics contain malformed entries",
        )
        return ExternalSemanticDiagnostics.Available(parsed)
    }

    private fun unavailable(code: String, message: String) = ExternalSemanticDiagnostics.Unavailable(Diagnostic(
        message = message,
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.COMPILER,
        category = DiagnosticCategory.SAFETY,
    ))

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)?.intOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)?.booleanOrNull

    companion object {
        private val JSON = Json { isLenient = false; ignoreUnknownKeys = false }
        private val SEQUENCE = AtomicLong(1)
        private const val BRIDGE_RESOURCE = "/org/refactorkit/typescript/typescript-diagnostics-bridge.cjs"
        private const val MAX_BRIDGE_BYTES = 64 * 1024
        private const val MAX_PROJECTS = 64
        private const val MAX_DIAGNOSTICS = 500
        private const val MAX_DIAGNOSTIC_MESSAGE = 4_096
        private const val MAX_STDERR_MESSAGE = 1_024
        private const val MAX_OUTPUT_BYTES = 8L * 1024L * 1024L
        private const val MAX_STDERR_BYTES = 64 * 1024
        private const val TIMEOUT_MILLIS = 30_000L
        private const val COMPILER_HEAP_MIB = 512
    }
}
