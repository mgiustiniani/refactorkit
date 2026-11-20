package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.ArrayList
import java.util.Collections

private class CCompilationArgumentFailure(val detail: String) : RuntimeException()

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
class CCompilationUnit(
    val file: Path,
    val directory: Path,
    arguments: List<String>,
    val output: String?,
    val standard: String?,
    defines: List<String>,
    includeDirectories: List<Path>,
    val target: String?,
    val sysroot: Path?,
) {
    val arguments: List<String> = immutableCCompilationList(arguments)
    val defines: List<String> = immutableCCompilationList(defines)
    val includeDirectories: List<Path> = immutableCCompilationList(includeDirectories)

    init {
        require(this.arguments.size <= 512) { "compilation unit arguments exceed the bounded limit" }
        require(this.defines.size <= 512) { "compilation unit defines exceed the bounded limit" }
        require(this.includeDirectories.size <= 512) { "compilation unit include directories exceed the bounded limit" }
    }

    operator fun component1(): Path = file
    operator fun component2(): Path = directory
    operator fun component3(): List<String> = arguments
    operator fun component4(): String? = output
    operator fun component5(): String? = standard
    operator fun component6(): List<String> = defines
    operator fun component7(): List<Path> = includeDirectories
    operator fun component8(): String? = target
    operator fun component9(): Path? = sysroot

    fun copy(
        file: Path = this.file,
        directory: Path = this.directory,
        arguments: List<String> = this.arguments,
        output: String? = this.output,
        standard: String? = this.standard,
        defines: List<String> = this.defines,
        includeDirectories: List<Path> = this.includeDirectories,
        target: String? = this.target,
        sysroot: Path? = this.sysroot,
    ) = CCompilationUnit(file, directory, arguments, output, standard, defines, includeDirectories, target, sysroot)

    override fun equals(other: Any?): Boolean = other === this ||
        other is CCompilationUnit && file == other.file && directory == other.directory &&
        arguments == other.arguments && output == other.output && standard == other.standard &&
        defines == other.defines && includeDirectories == other.includeDirectories &&
        target == other.target && sysroot == other.sysroot

    override fun hashCode(): Int {
        var result = file.hashCode()
        result = 31 * result + directory.hashCode()
        result = 31 * result + arguments.hashCode()
        result = 31 * result + (output?.hashCode() ?: 0)
        result = 31 * result + (standard?.hashCode() ?: 0)
        result = 31 * result + defines.hashCode()
        result = 31 * result + includeDirectories.hashCode()
        result = 31 * result + (target?.hashCode() ?: 0)
        result = 31 * result + (sysroot?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "CCompilationUnit(file=$file, directory=$directory, arguments=$arguments, output=$output, standard=$standard, defines=$defines, includeDirectories=$includeDirectories, target=$target, sysroot=$sysroot)"
}

class CCompilationDatabase(
    val workspaceRoot: Path,
    units: List<CCompilationUnit>,
) {
    val units: List<CCompilationUnit> = immutableCCompilationList(units)

    init {
        require(this.units.map(CCompilationUnit::file).distinct().size == this.units.size) {
            "compilation database contains duplicate translation units"
        }
    }

    operator fun component1(): Path = workspaceRoot
    operator fun component2(): List<CCompilationUnit> = units

    fun copy(workspaceRoot: Path = this.workspaceRoot, units: List<CCompilationUnit> = this.units) =
        CCompilationDatabase(workspaceRoot, units)

    override fun equals(other: Any?): Boolean = other === this ||
        other is CCompilationDatabase && workspaceRoot == other.workspaceRoot && units == other.units

    override fun hashCode(): Int = 31 * workspaceRoot.hashCode() + units.hashCode()

    override fun toString(): String = "CCompilationDatabase(workspaceRoot=$workspaceRoot, units=$units)"
}

private fun <T> immutableCCompilationList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

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
    fun parse(workspaceRoot: Path, bytes: ByteArray): CCompilationDatabaseDiscovery = try {
        parseRecords(workspaceRoot, bytes)
    } catch (_: InvalidPathException) {
        CCompilationDatabaseDiscovery.Refused(listOf(refusal(
            "c.compilationDatabaseInvalid", "compile_commands.json contains an invalid path",
        )))
    } catch (failure: CCompilationArgumentFailure) {
        CCompilationDatabaseDiscovery.Refused(listOf(refusal("c.compilationDatabaseInvalid", failure.detail)))
    }

    private fun parseRecords(workspaceRoot: Path, bytes: ByteArray): CCompilationDatabaseDiscovery {
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
            val invalidField = listOf("file", "directory", "command", "output").firstOrNull { key ->
                objectValue.containsKey(key) && (objectValue[key] as? JsonPrimitive)?.isString != true
            }
            if (invalidField != null) {
                diagnostics += refusal("c.compilationDatabaseInvalid", "unit $index '$invalidField' must be a string")
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
            val directory = resolveWithin(workspace, directoryText, "directory", index, diagnostics)
            if (directory == null) continue
            val file = resolveWithin(workspace, fileText, "file", index, diagnostics, baseDirectory = directory)
            if (file == null) continue
            if (!seenFiles.add(file)) {
                diagnostics += refusal("c.duplicateTranslationUnit", "translation unit '${fileText}' appears more than once")
                continue
            }
            val arguments = parseArguments(objectValue, index, diagnostics)
            if (arguments == null) continue
            val output = objectValue.string("output")
            val options = optionValues(arguments)
            val standard = options.firstOrNull { it.first == "-std" }?.second?.takeIf(String::isNotBlank)
            val defines = findFlags(options, "-D")
            val includeDirs = findIncludePaths(options, directory)
            val target = options.firstOrNull { it.first in setOf("-target", "--target") }?.second?.takeIf(String::isNotBlank)
            val sysroot = options.firstOrNull { it.first in setOf("--sysroot", "-isysroot") }
                ?.second?.let { resolveInclude(it, directory) }
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
        if (objectValue.containsKey("arguments") && array == null) {
            diagnostics += refusal("c.compilationDatabaseInvalid", "unit $index 'arguments' must be an array of strings")
            return null
        }
        if (array != null) {
            if (array.isEmpty()) {
                diagnostics += refusal("c.compilationDatabaseInvalid", "unit $index has empty 'arguments'")
                return null
            }
            if (array.size > policy.maxArgumentsPerUnit) {
                diagnostics += refusal("c.compilationDatabaseLimit", "unit $index arguments exceed ${policy.maxArgumentsPerUnit}")
                return null
            }
            if (array.any { it !is JsonPrimitive || !it.isString }) {
                diagnostics += refusal("c.compilationDatabaseInvalid", "unit $index 'arguments' must contain only strings")
                return null
            }
            val strings = array.map { (it as JsonPrimitive).content }
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

    /** Consume known operand-taking options before classifying values that resemble flags. */
    internal fun optionValues(arguments: List<String>): List<Pair<String, String>> {
        val separate = setOf("-D", "-U", "-I", "-iquote", "-isystem", "-o", "-x",
            "-target", "--target", "--sysroot", "-isysroot")
        val joined = listOf("-isystem", "-iquote", "-isysroot", "-D", "-U", "-I")
        val assigned = listOf("-std=", "-target=", "--target=", "--sysroot=")
        val result = mutableListOf<Pair<String, String>>()
        var index = 1
        while (index < arguments.size) {
            val argument = arguments[index]
            if (argument == "--target" || argument.startsWith("-target=")) {
                throw CCompilationArgumentFailure("The qualified compiler requires -target <triple> or --target=<triple>")
            }
            if (argument == "-I-") throw CCompilationArgumentFailure("The -I- include separator is not supported by the qualified compiler")
            if (argument.startsWith("-isystem-after")) throw CCompilationArgumentFailure("The -isystem-after mode is not admitted for the qualified C context")
            if (argument in separate) {
                val operand = arguments.getOrNull(index + 1)
                    ?: throw CCompilationArgumentFailure("Compiler option '$argument' requires an operand")
                result += argument to operand
                index += 2
            } else {
                val assignment = assigned.firstOrNull { argument.startsWith(it) }
                if (assignment != null) {
                    result += assignment.removeSuffix("=") to argument.substring(assignment.length)
                } else {
                    joined.firstOrNull { argument.startsWith(it) && argument.length > it.length }?.let { prefix ->
                        result += prefix to argument.substring(prefix.length)
                    }
                }
                index++
            }
        }
        return result
    }

    private fun findFlags(options: List<Pair<String, String>>, prefix: String): List<String> =
        options.filter { it.first == prefix }.map { it.second }.filter(String::isNotBlank)

    private fun findIncludePaths(options: List<Pair<String, String>>, workspace: Path): List<Path> =
        options.filter { it.first in setOf("-I", "-iquote", "-isystem") }
            .mapNotNull { resolveInclude(it.second, workspace) }.distinct()

    private fun resolveInclude(raw: String, workspace: Path): Path? {
        val text = raw
        if (text.isEmpty()) return null
        val path = Path.of(text)
        return if (path.isAbsolute) path.normalize() else workspace.resolve(path).normalize()
    }

    private fun resolveWithin(
        workspace: Path,
        raw: String,
        label: String,
        index: Int,
        diagnostics: MutableList<Diagnostic>,
        baseDirectory: Path = workspace,
    ): Path? {
        val text = raw
        if (text.isEmpty()) {
            diagnostics += refusal("c.compilationDatabaseInvalid", "unit $index $label is empty")
            return null
        }
        val path = Path.of(text)
        val normalized = if (path.isAbsolute) path.normalize() else baseDirectory.resolve(path).normalize()
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
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            diagnostics += refusal("c.compilationDatabaseInvalid", "compile_commands.json is not valid UTF-8")
            return null
        }
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

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
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
