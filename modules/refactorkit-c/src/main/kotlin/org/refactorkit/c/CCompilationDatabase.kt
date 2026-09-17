package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Bounded policy for compiling a `compile_commands.json` database. */
data class CCompilationDatabasePolicy(
    val maxDatabaseBytes: Long = DEFAULT_MAX_DATABASE_BYTES,
    val maxUnits: Int = DEFAULT_MAX_UNITS,
    val maxArgumentsPerUnit: Int = DEFAULT_MAX_ARGUMENTS_PER_UNIT,
    val requireArgumentsArray: Boolean = false,
    val allowUnsafeCompilerFlags: Boolean = false,
) {
    init {
        require(maxDatabaseBytes in 1..DEFAULT_MAX_DATABASE_BYTES) { "database size bound is outside the safe range" }
        require(maxUnits in 1..DEFAULT_MAX_UNITS) { "unit count bound is outside the safe range" }
        require(maxArgumentsPerUnit in 1..DEFAULT_MAX_ARGUMENTS_PER_UNIT) { "argument count bound is outside the safe range" }
    }

    companion object {
        const val DEFAULT_MAX_DATABASE_BYTES = 8L * 1024L * 1024L
        const val DEFAULT_MAX_UNITS = 4_096
        const val DEFAULT_MAX_ARGUMENTS_PER_UNIT = 512

        /**
         * Compiler flags that load, inject or replace code/toolchain state. They are
         * untrusted data captured from `compile_commands.json` and must never reach a
         * semantic process unless the caller explicitly opts in.
         */
        val UNSAFE_COMPILER_FLAGS = listOf(
            "-Xclang", "-Xassembler", "-Xlinker", "-cc1", "-cc1as",
            "-fplugin", "-plugin", "-load", "-mllvm",
            "-include", "-imacros", "-include-pch",
            "-B", "--prefix", "-specs",
        )
    }
}

/** One translation unit captured from the compilation database. Commands are untrusted data, never executed. */
data class CCompilationUnit(
    val file: Path,
    val directory: Path,
    val arguments: List<String>,
    val output: String?,
    val standard: String?,
    val defines: List<String>,
    val includeDirectories: List<Path>,
    val target: String?,
    val sysroot: Path?,
) {
    init {
        require(arguments.size <= 512) { "compilation unit arguments exceed the bounded limit" }
        require(defines.size <= 512) { "compilation unit defines exceed the bounded limit" }
        require(includeDirectories.size <= 512) { "compilation unit include directories exceed the bounded limit" }
    }
}

data class CCompilationDatabase(
    val workspaceRoot: Path,
    val units: List<CCompilationUnit>,
) {
    init {
        require(units.map(CCompilationUnit::file).distinct().size == units.size) {
            "compilation database contains duplicate translation units"
        }
    }
}

sealed interface CCompilationDatabaseDiscovery {
    data class Available(val database: CCompilationDatabase) : CCompilationDatabaseDiscovery
    data class Refused(val diagnostics: List<Diagnostic>) : CCompilationDatabaseDiscovery
}

/**
 * Parses a `compile_commands.json` database into a bounded, snapshot-bound model.
 * Commands are treated as untrusted data: they are parsed for flags only and
 * never executed. Shell operators, response files and query-driver forms are
 * refused unless explicitly admitted.
 */
class CCompilationDatabaseParser(
    private val policy: CCompilationDatabasePolicy = CCompilationDatabasePolicy(),
) {
    fun parse(workspaceRoot: Path, bytes: ByteArray): CCompilationDatabaseDiscovery {
        val diagnostics = mutableListOf<Diagnostic>()
        val workspace = requireDirectory(workspaceRoot, "workspace", diagnostics)
            ?: return CCompilationDatabaseDiscovery.Refused(diagnostics)
        if (bytes.size.toLong() > policy.maxDatabaseBytes) {
            diagnostics += refusal(
                "c.compilationDatabaseLimit",
                "compile_commands.json exceeds ${policy.maxDatabaseBytes} bytes",
            )
            return CCompilationDatabaseDiscovery.Refused(diagnostics)
        }
        val json = decode(bytes, diagnostics)
            ?: return CCompilationDatabaseDiscovery.Refused(diagnostics)
        val root = json as? JsonArray
        if (root == null) {
            diagnostics += refusal("c.compilationDatabaseInvalid", "compile_commands.json root must be an array")
            return CCompilationDatabaseDiscovery.Refused(diagnostics)
        }
        if (root.isEmpty()) {
            diagnostics += refusal("c.compilationDatabaseEmpty", "compile_commands.json contains no translation units")
            return CCompilationDatabaseDiscovery.Refused(diagnostics)
        }
        if (root.size > policy.maxUnits) {
            diagnostics += refusal("c.compilationDatabaseLimit", "compile_commands.json exceeds ${policy.maxUnits} units")
            return CCompilationDatabaseDiscovery.Refused(diagnostics)
        }

        val units = mutableListOf<CCompilationUnit>()
        val seenFiles = mutableSetOf<Path>()
        for ((index, element) in root.withIndex()) {
            val objectValue = element as? JsonObject
            if (objectValue == null) {
                diagnostics += refusal("c.compilationDatabaseInvalid", "unit $index must be an object")
                continue
            }
            val fileText = objectValue.string("file")
            if (fileText == null) {
                diagnostics += refusal("c.compilationDatabaseInvalid", "unit $index is missing 'file'")
                continue
            }
            val directoryText = objectValue.string("directory")
            if (directoryText == null) {
                diagnostics += refusal("c.compilationDatabaseInvalid", "unit $index is missing 'directory'")
                continue
            }
            val file = resolveWithin(workspace, fileText, "file", index, diagnostics)
            if (file == null) continue
            val directory = resolveWithin(workspace, directoryText, "directory", index, diagnostics)
            if (directory == null) continue
            if (!seenFiles.add(file)) {
                diagnostics += refusal("c.duplicateTranslationUnit", "translation unit '${fileText}' appears more than once")
                continue
            }
            val arguments = parseArguments(objectValue, index, diagnostics)
            if (arguments == null) continue
            val output = objectValue.string("output")
            val standard = findFlag(arguments, "-std=")
            val defines = findFlags(arguments, "-D")
            val includeDirs = findIncludePaths(arguments, workspace)
            val target = findTarget(arguments)
            val sysroot = findSysroot(arguments, workspace)
            units += CCompilationUnit(
                file = file,
                directory = directory,
                arguments = arguments,
                output = output,
                standard = standard,
                defines = defines,
                includeDirectories = includeDirs,
                target = target,
                sysroot = sysroot,
            )
        }
        if (diagnostics.isNotEmpty()) return CCompilationDatabaseDiscovery.Refused(diagnostics)
        if (units.isEmpty()) {
            diagnostics += refusal("c.compilationDatabaseEmpty", "no valid translation units were captured")
            return CCompilationDatabaseDiscovery.Refused(diagnostics)
        }
        return CCompilationDatabaseDiscovery.Available(CCompilationDatabase(workspace, units))
    }

    private fun parseArguments(objectValue: JsonObject, index: Int, diagnostics: MutableList<Diagnostic>): List<String>? {
        val array = objectValue.array("arguments")
        if (array != null) {
            if (array.isEmpty()) {
                diagnostics += refusal("c.compilationDatabaseInvalid", "unit $index has empty 'arguments'")
                return null
            }
            if (array.size > policy.maxArgumentsPerUnit) {
                diagnostics += refusal("c.compilationDatabaseLimit", "unit $index arguments exceed ${policy.maxArgumentsPerUnit}")
                return null
            }
            val strings = array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (strings.isEmpty()) {
                diagnostics += refusal("c.compilationDatabaseInvalid", "unit $index 'arguments' are not strings")
                return null
            }
            val unsafe = unsafeCompilerFlag(strings)
            if (unsafe != null) {
                diagnostics += refusal("c.unsafeCompilerFlag", "unit $index captures unsafe compiler flag '$unsafe'")
                return null
            }
            return strings
        }
        if (policy.requireArgumentsArray) {
            diagnostics += refusal("c.compilationDatabaseInvalid", "unit $index uses 'command' but 'arguments' is required")
            return null
        }
        val command = objectValue.string("command")
        if (command == null) {
            diagnostics += refusal("c.compilationDatabaseInvalid", "unit $index has neither 'arguments' nor 'command'")
            return null
        }
        return tokenize(command, index, diagnostics)
    }

    private fun tokenize(command: String, index: Int, diagnostics: MutableList<Diagnostic>): List<String>? {
        if (command.length > 64 * 1024) {
            diagnostics += refusal("c.compilationDatabaseLimit", "unit $index command exceeds the bounded length")
            return null
        }
        val operators = SHELL_OPERATORS.firstOrNull(command::contains)
        if (operators != null) {
            diagnostics += refusal("c.commandShellOperator", "unit $index command contains shell operator '$operators'")
            return null
        }
        val tokens = tokenizeQuoted(command)
        if (tokens.isEmpty()) {
            diagnostics += refusal("c.compilationDatabaseInvalid", "unit $index command produced no tokens")
            return null
        }
        if (tokens.size > policy.maxArgumentsPerUnit) {
            diagnostics += refusal("c.compilationDatabaseLimit", "unit $index command tokens exceed ${policy.maxArgumentsPerUnit}")
            return null
        }
        val unsafe = unsafeCompilerFlag(tokens)
        if (unsafe != null) {
            diagnostics += refusal("c.unsafeCompilerFlag", "unit $index captures unsafe compiler flag '$unsafe'")
            return null
        }
        return tokens
    }

    /**
     * Rejects compiler flags that load, inject or replace code or toolchain state.
     * Captured commands are untrusted data: unsafe flags never reach the model unless
     * the caller explicitly opts in via [CCompilationDatabasePolicy.allowUnsafeCompilerFlags].
     */
    private fun unsafeCompilerFlag(arguments: List<String>): String? {
        if (policy.allowUnsafeCompilerFlags) return null
        return arguments.firstOrNull { argument ->
            CCompilationDatabasePolicy.UNSAFE_COMPILER_FLAGS.any { prefix ->
                argument == prefix || argument.startsWith("$prefix=") || argument.startsWith("$prefix:")
            }
        }
    }

    private fun findFlag(arguments: List<String>, prefix: String): String? {
        val value = arguments.firstOrNull { it.startsWith(prefix) }?.substring(prefix.length)
        return value?.takeIf(String::isNotBlank)
    }

    private fun findTarget(arguments: List<String>): String? {
        var i = 0
        while (i < arguments.size) {
            val arg = arguments[i]
            when {
                arg.startsWith("-target=") && arg.length > 8 -> return arg.substring(8).takeIf(String::isNotBlank)
                arg.startsWith("--target=") && arg.length > 9 -> return arg.substring(9).takeIf(String::isNotBlank)
                arg == "-target" && i + 1 < arguments.size -> return arguments[i + 1].takeIf(String::isNotBlank)
                arg == "--target" && i + 1 < arguments.size -> return arguments[i + 1].takeIf(String::isNotBlank)
            }
            i++
        }
        return null
    }

    private fun findFlags(arguments: List<String>, prefix: String): List<String> {
        return arguments.filter { it.startsWith(prefix) }
            .map { it.substring(prefix.length) }
            .filter(String::isNotBlank)
            .take(512)
    }

    private fun findIncludePaths(arguments: List<String>, workspace: Path): List<Path> {
        val result = mutableListOf<Path>()
        var i = 0
        while (i < arguments.size) {
            val arg = arguments[i]
            when {
                arg.startsWith("-I") && arg.length > 2 -> {
                    resolveInclude(arg.substring(2), workspace)?.let(result::add)
                }
                arg == "-I" && i + 1 < arguments.size -> {
                    resolveInclude(arguments[i + 1], workspace)?.let(result::add)
                    i++
                }
                arg.startsWith("-isystem") && arg.length > 8 -> {
                    resolveInclude(arg.substring(8), workspace)?.let(result::add)
                }
                arg == "-isystem" && i + 1 < arguments.size -> {
                    resolveInclude(arguments[i + 1], workspace)?.let(result::add)
                    i++
                }
            }
            i++
        }
        return result.distinct().take(512)
    }

    private fun findSysroot(arguments: List<String>, workspace: Path): Path? {
        var i = 0
        while (i < arguments.size) {
            val arg = arguments[i]
            when {
                arg.startsWith("--sysroot=") && arg.length > 10 -> {
                    return resolveInclude(arg.substring(10), workspace)
                }
                arg == "--sysroot" && i + 1 < arguments.size -> {
                    return resolveInclude(arguments[i + 1], workspace)
                }
                arg.startsWith("-isysroot") && arg.length > 9 -> {
                    return resolveInclude(arg.substring(9), workspace)
                }
                arg == "-isysroot" && i + 1 < arguments.size -> {
                    return resolveInclude(arguments[i + 1], workspace)
                }
            }
            i++
        }
        return null
    }

    private fun resolveInclude(raw: String, workspace: Path): Path? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val path = Path.of(text)
        return if (path.isAbsolute) path.normalize() else workspace.resolve(path).normalize()
    }

    private fun resolveWithin(workspace: Path, raw: String, label: String, index: Int, diagnostics: MutableList<Diagnostic>): Path? {
        val text = raw.trim()
        if (text.isEmpty()) {
            diagnostics += refusal("c.compilationDatabaseInvalid", "unit $index $label is empty")
            return null
        }
        val path = Path.of(text)
        val normalized = if (path.isAbsolute) path.normalize() else workspace.resolve(path).normalize()
        if (!normalized.startsWith(workspace)) {
            diagnostics += refusal("c.pathOutsideWorkspace", "unit $index $label resolves outside the workspace")
            return null
        }
        return normalized
    }

    private fun requireDirectory(path: Path, label: String, diagnostics: MutableList<Diagnostic>): Path? {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            diagnostics += refusal("c.pathInvalid", "$label must be an existing non-symlink directory")
            return null
        }
        return normalized
    }

    private fun decode(bytes: ByteArray, diagnostics: MutableList<Diagnostic>): JsonElement? {
        val text = bytes.toString(Charsets.UTF_8)
        return runCatching {
            Json.parseToJsonElement(text)
        }.getOrElse {
            diagnostics += refusal("c.compilationDatabaseInvalid", it.message ?: "compile_commands.json is not valid JSON")
            null
        }
    }

    private fun refusal(code: String, message: String) = Diagnostic(
        message = message,
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.PROJECT_STRUCTURE,
    )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private companion object {
        val SHELL_OPERATORS = listOf("|", "&&", "||", ";", ">", "<", "`", "\$( ", "&>", "2>")
    }
}

/** Splits a shell command into tokens, honoring single/double quotes. Bounded and non-executing. */
internal fun tokenizeQuoted(command: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    for (ch in command) {
        when {
            escaped -> { current.append(ch); escaped = false }
            ch == '\\' && quote != '\'' -> escaped = true
            quote != null && ch == quote -> quote = null
            quote != null -> current.append(ch)
            ch == '\'' || ch == '"' -> quote = ch
            ch.isWhitespace() -> {
                if (current.isNotEmpty()) { tokens += current.toString(); current.clear() }
            }
            else -> current.append(ch)
        }
    }
    if (current.isNotEmpty()) tokens += current.toString()
    return tokens
}
