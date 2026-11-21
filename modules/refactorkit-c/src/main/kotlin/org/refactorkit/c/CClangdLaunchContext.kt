package org.refactorkit.c

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.OwnedSourceCaptureException
import org.refactorkit.core.OwnedSourceInventoryReader
import org.refactorkit.core.OwnedWorkspaceTextReadout
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import java.security.MessageDigest

/** A structured operational admission failure; project commands are never executed. */
class CClangdConfigurationException(val diagnostic: Diagnostic) : IllegalArgumentException(
    "${diagnostic.code}: ${diagnostic.message}",
)

/** Engine-owned launch material for a bounded, explicitly admitted clangd configuration. */
internal class CClangdLaunchContext private constructor(
    val root: Path,
    private val workspace: Path,
    private val databaseInputs: Map<Path, String?>,
    private val sysroots: List<CNativeSysrootContext>,
) : AutoCloseable {
    /** Compare captured byte identities and absence assertions without executing project commands. */
    fun isCurrent(): Boolean = runCatching {
        databaseInputs.all { (path, expected) ->
            readDatabase(workspace, path)?.let(::hash) == expected
        } && sysroots.all { it.isCurrent() }
    }.getOrDefault(false)

    fun readOnlySdkProvenance(): List<CReadOnlySdkProvenance> = sysroots.mapNotNull { it.sdkProvenance() }
    fun sdkRenameDecision(selected: Path, definition: Path?): CReadOnlySdkRename = CReadOnlySdkPolicy.rename(
        workspace, if (selected.isAbsolute) selected else workspace.resolve(selected), definition, sysroots.mapNotNull { it.sdkRequest })
    fun sdkEditDecision(files: List<Path>): CReadOnlySdkRename = CReadOnlySdkPolicy.edits(workspace, files, sysroots.mapNotNull { it.sdkRequest })

    override fun close() {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    companion object {
        fun capture(workspace: Path, compiler: Path, readOnlySdks: List<CReadOnlySdkRequest> = emptyList()): CClangdLaunchContext {
            when (CReadOnlySdkPolicy.scope(workspace, readOnlySdks)) {
                CReadOnlySdkScope.ADMITTED -> Unit
                CReadOnlySdkScope.INVALID -> refuse("Explicit SDK scope unavailable: SDK_SCOPE_INVALID")
                CReadOnlySdkScope.INPUT_LIMIT -> refuse("Explicit SDK scope unavailable: INPUT_LIMIT")
            }
            val database = admitDatabase(workspace, compiler, readOnlySdks)
            val root = Files.createTempDirectory("refactorkit-clangd-launch-")
            val context = CClangdLaunchContext(root, workspace, database.inputs, database.sysroots)
            try {
                Files.writeString(root.resolve("compile_commands.json"), database.json.toString())
                if (!context.isCurrent()) refuse("Compilation configuration changed during capture")
                return context
            } catch (failure: Exception) {
                runCatching { context.close() }
                refuse("Cannot materialize private clangd configuration: ${failure.message}")
            }
        }

        private data class CapturedDatabase(val json: JsonArray, val inputs: Map<Path, String?>, val sysroots: List<CNativeSysrootContext> = emptyList())

        private fun admitDatabase(workspace: Path, compiler: Path, readOnlySdks: List<CReadOnlySdkRequest>): CapturedDatabase {
            if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) refuse("Workspace is not a non-symlink directory")
            val paths = listOf(workspace.resolve("compile_commands.json"), workspace.resolve("build/compile_commands.json"))
            val captured = paths.associateWith { readDatabase(workspace, it) }
            val candidates = captured.filterValues { it != null }
            if (candidates.size > 1) refuse("Multiple compilation databases require explicit configuration selection")
            val bytes = candidates.values.singleOrNull()
                ?: return CapturedDatabase(JsonArray(emptyList()), paths.associateWith { null })
            val inputs = captured.mapValues { (_, value) -> value?.let(::hash) }
            val parsed = CCompilationDatabaseParser(CCompilationDatabasePolicy(requireArgumentsArray = true)).parse(workspace, bytes)
            val database = when (parsed) {
                is CCompilationDatabaseDiscovery.Available -> parsed.database
                is CCompilationDatabaseDiscovery.Refused -> refuse(parsed.diagnostics.joinToString("; ") { "${it.code}: ${it.message}" })
            }
            when (val target = CNativeTargetPolicy.admit(database.units)) {
                is CNativeTargetAdmission.Refused -> refuse("Native target context unavailable: ${target.reason.name}")
                CNativeTargetAdmission.ImplicitDefault, CNativeTargetAdmission.PerTranslationUnit,
                is CNativeTargetAdmission.Explicit -> Unit
            }
            val sysroots = when (val selected = CNativeSysrootPolicy.select(workspace, database.units, readOnlySdks)) {
                CNativeSysrootAdmission.ImplicitDefault -> emptyList()
                is CNativeSysrootAdmission.Refused -> refuse("Native sysroot context unavailable: ${selected.reason.name}")
                is CNativeSysrootAdmission.Owned -> listOf(selected.root)
                is CNativeSysrootAdmission.PerTranslationUnit -> selected.roots
            }.map { sysroot ->
                try {
                    val sdk = readOnlySdks.singleOrNull { it.root == sysroot }
                    if (sdk == null) CNativeSysrootContext.capture(workspace, sysroot) else CNativeSysrootContext.captureReadOnly(sdk)
                }
                catch (failure: CNativeSysrootCaptureException) { refuse("Owned sysroot unavailable: ${failure.reason.name}") }
            }
            val raw = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonArray
            if (raw.any { value ->
                val unit = value as? JsonObject
                val args = unit?.get("arguments") as? JsonArray
                args == null || args.any { it !is JsonPrimitive || !it.isString } ||
                    listOf("file", "directory").any { key -> (unit?.get(key) as? JsonPrimitive)?.isString != true }
            }) refuse("Compilation database paths and arguments must be strings")
            val admitted = JsonArray(database.units.map { unit ->
                requireOwnedPath(workspace, unit.directory, directory = true)
                requireOwnedPath(workspace, unit.file, directory = false)
                if (!unit.file.fileName.toString().endsWith(".c")) refuse("Only explicit C translation units are admitted")
                val arguments = admitArguments(workspace, unit, compiler, sysroots)
                buildJsonObject {
                    put("directory", unit.directory.toString())
                    put("file", unit.file.toString())
                    put("arguments", JsonArray(arguments.map(::JsonPrimitive)))
                }
            })
            return CapturedDatabase(admitted, inputs, sysroots)
        }

        private fun readDatabase(workspace: Path, file: Path): ByteArray? = try {
            when (val input = OwnedSourceInventoryReader.readWorkspaceTextInput(workspace,
                workspace.relativize(file), CCompilationDatabasePolicy.DEFAULT_MAX_DATABASE_BYTES.toInt())) {
                OwnedWorkspaceTextReadout.Absent -> null
                is OwnedWorkspaceTextReadout.Captured -> input.content.toByteArray(Charsets.UTF_8)
            }
        } catch (failure: OwnedSourceCaptureException) {
            refuse("Cannot capture compilation database: ${failure.code.name}")
        }

        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        private fun admitArguments(workspace: Path, unit: CCompilationUnit, compiler: Path, sysroots: List<CNativeSysrootContext>): List<String> {
            val raw = unit.arguments
            if (raw.any { it.isBlank() || it.length > 4_096 || it.any(Char::isISOControl) }) refuse("Invalid compilation argument")
            val admitted = mutableListOf(compiler.toAbsolutePath().normalize().toString())
            var sourceCount = 0
            var standardCount = 0
            var index = 1
            fun operand(): String = raw.getOrNull(++index) ?: refuse("Missing compiler flag operand")
            fun include(flag: String, text: String) {
                val path = runCatching { unit.directory.resolve(text).normalize() }.getOrElse { refuse("Invalid include path") }
                requireOwnedPath(workspace, path, directory = true)
                admitted += flag
                admitted += path.toString()
            }
            fun systemRoot(text: String) {
                val path = runCatching { unit.directory.resolve(text).normalize() }.getOrElse { refuse("Invalid sysroot path") }
                if (sysroots.none { it.root == path }) refuse("Sysroot differs from captured owned input")
                admitted += "--sysroot"; admitted += path.toString()
            }
            while (index < raw.size) {
                val argument = raw[index]
                when {
                    argument in setOf("-std=c11", "-std=c17") -> {
                        standardCount++
                        admitted += argument
                    }
                    argument == "--sysroot" -> systemRoot(operand())
                    argument.startsWith("--sysroot=") -> systemRoot(argument.substringAfter('='))
                    argument == "-target" -> admitted += "--target=" + operand()
                    argument.startsWith("--target=") -> admitted += argument
                    argument in setOf("-Wall", "-Wextra", "-Werror", "-pedantic", "-pedantic-errors") -> admitted += argument
                    argument == "-c" -> Unit
                    argument == "-o" -> { operand(); Unit }
                    argument == "-x" -> {
                        if (operand() != "c") refuse("Only C language mode is admitted")
                        admitted += listOf("-x", "c")
                    }
                    argument == "-D" || argument == "-U" -> {
                        val value = operand()
                        if (!macroOperand(value, argument)) refuse("Unsupported macro operand")
                        admitted += argument + value
                    }
                    argument.startsWith("-D") || argument.startsWith("-U") -> {
                        if (!macroOperand(argument.substring(2), argument.take(2))) refuse("Unsupported macro operand")
                        admitted += argument
                    }
                    argument in setOf("-I", "-iquote", "-isystem") -> include(argument, operand())
                    argument.startsWith("-I") -> include("-I", argument.substring(2))
                    argument.startsWith("-iquote") -> include("-iquote", argument.substring(7))
                    argument.startsWith("-isystem") -> include("-isystem", argument.substring(8))
                    !argument.startsWith("-") && !argument.startsWith("@") -> {
                        val source = runCatching { unit.directory.resolve(argument).normalize() }.getOrElse { refuse("Invalid source argument") }
                        if (source != unit.file) refuse("Unowned compiler input '$argument'")
                        sourceCount++
                    }
                    else -> refuse("Unsupported compiler flag '$argument'")
                }
                index++
            }
            if (standardCount != 1) refuse("Exactly one explicit C11/C17 dialect is required")
            if (sourceCount != 1) refuse("Exactly one captured translation-unit argument is required")
            admitted += unit.file.toString()
            if (admitted.size > CCompilationDatabasePolicy.DEFAULT_MAX_ARGUMENTS_PER_UNIT) refuse("Admitted compiler argument limit exceeded")
            return admitted
        }

        private fun macroOperand(value: String, flag: String): Boolean =
            if (flag == "-U") IDENTIFIER.matches(value) else IDENTIFIER.matches(value.substringBefore('='))

        private fun requireOwnedPath(workspace: Path, path: Path, directory: Boolean) {
            if (!path.startsWith(workspace)) refuse("Configuration input escapes the workspace")
            var current = workspace
            val relative = workspace.relativize(path)
            relative.forEachIndexed { index, component ->
                current = current.resolve(component)
                val valid = if (index < relative.nameCount - 1 || directory) {
                    Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                } else Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)
                if (!valid) refuse("Configuration input is absent, non-regular or follows a symlink")
            }
        }

        private fun refuse(message: String): Nothing = throw CClangdConfigurationException(Diagnostic(
            message = message.take(2_048), severity = Diagnostic.Severity.ERROR,
            code = "c.clangdConfigurationRefused", evidence = DiagnosticEvidence.STRUCTURAL,
            category = DiagnosticCategory.SAFETY,
        ))

        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
