package org.refactorkit.java

import java.nio.file.Path

internal enum class GradleSettingsDsl { GROOVY, KOTLIN }

internal data class GradleSettingsModuleEditProposal(
    val dsl: GradleSettingsDsl,
    val projectPath: String,
    val projectDirectory: Path,
    val buildScriptPath: Path,
    val insertionOffset: Int,
    val insertedText: String,
)

internal data class GradleSettingsModuleRenameProposal(
    val dsl: GradleSettingsDsl,
    val oldProjectPath: String,
    val newProjectPath: String,
    val physicalProjectDirectory: Path,
    val pathLiteralStart: Int,
    val pathLiteralEnd: Int,
    val insertionOffset: Int,
    val insertedText: String,
)

internal sealed interface GradleSettingsModuleEditResult {
    data class Supported(val proposal: GradleSettingsModuleEditProposal) : GradleSettingsModuleEditResult
    data class Refused(val code: String) : GradleSettingsModuleEditResult
}

internal sealed interface GradleSettingsModuleRenameResult {
    data class Supported(val proposal: GradleSettingsModuleRenameProposal) : GradleSettingsModuleRenameResult
    data class Refused(val code: String) : GradleSettingsModuleRenameResult
}

/** Builds one literal Gradle include append without evaluating or rewriting settings DSL. */
internal object GradleSettingsModuleEdit {
    private val safeSegment = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
    private val windowsDeviceName = Regex("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")

    fun propose(
        settingsFileName: String,
        settingsContent: String,
        projectPath: String,
    ): GradleSettingsModuleEditResult {
        val dsl = when (settingsFileName) {
            "settings.gradle" -> GradleSettingsDsl.GROOVY
            "settings.gradle.kts" -> GradleSettingsDsl.KOTLIN
            else -> return GradleSettingsModuleEditResult.Refused(
                "java.createGradleModule.settingsFileUnsupported",
            )
        }
        val segments = projectPathSegments(projectPath)
            ?: return GradleSettingsModuleEditResult.Refused("java.createGradleModule.invalidProjectPath")
        return when (inspect(settingsContent)) {
            SettingsInspection.UnsupportedSyntax -> GradleSettingsModuleEditResult.Refused(
                "java.createGradleModule.settingsSyntaxUnsupported",
            )
            SettingsInspection.ProjectDirectoryMapping -> GradleSettingsModuleEditResult.Refused(
                "java.createGradleModule.projectDirectoryMappingUnsupported",
            )
            SettingsInspection.Supported -> {
                val separator = if (settingsContent.isNotEmpty() &&
                    settingsContent.last() != '\n' && settingsContent.last() != '\r'
                ) lineEnding(settingsContent) else ""
                val include = when (dsl) {
                    GradleSettingsDsl.KOTLIN -> "include(\"$projectPath\")"
                    GradleSettingsDsl.GROOVY -> "include '$projectPath'"
                }
                val projectDirectory = segments.fold(Path.of(""), Path::resolve)
                val buildScriptName = when (dsl) {
                    GradleSettingsDsl.KOTLIN -> "build.gradle.kts"
                    GradleSettingsDsl.GROOVY -> "build.gradle"
                }
                GradleSettingsModuleEditResult.Supported(
                    GradleSettingsModuleEditProposal(
                        dsl = dsl,
                        projectPath = projectPath,
                        projectDirectory = projectDirectory,
                        buildScriptPath = projectDirectory.resolve(buildScriptName),
                        insertionOffset = settingsContent.length,
                        insertedText = "$separator$include${lineEnding(settingsContent)}",
                    ),
                )
            }
        }
    }

    /**
     * Renames one top-level literal include while pinning its existing physical directory.
     * Literal lists and separate include calls retain peer identities. Existing `projectDir`
     * mappings and conditional or dynamic forms refuse to preserve source ownership.
     */
    fun proposeRename(
        settingsFileName: String,
        settingsContent: String,
        oldProjectPath: String,
        newProjectPath: String,
    ): GradleSettingsModuleRenameResult {
        val dsl = when (settingsFileName) {
            "settings.gradle" -> GradleSettingsDsl.GROOVY
            "settings.gradle.kts" -> GradleSettingsDsl.KOTLIN
            else -> return GradleSettingsModuleRenameResult.Refused(
                "java.renameGradleModule.settingsFileUnsupported",
            )
        }
        val oldSegments = projectPathSegments(oldProjectPath)
            ?: return GradleSettingsModuleRenameResult.Refused("java.renameGradleModule.invalidProjectPath")
        if (projectPathSegments(newProjectPath) == null || oldProjectPath == newProjectPath) {
            return GradleSettingsModuleRenameResult.Refused("java.renameGradleModule.invalidProjectPath")
        }
        when (inspect(settingsContent)) {
            SettingsInspection.UnsupportedSyntax -> return GradleSettingsModuleRenameResult.Refused(
                "java.renameGradleModule.settingsSyntaxUnsupported",
            )
            SettingsInspection.ProjectDirectoryMapping -> return GradleSettingsModuleRenameResult.Refused(
                "java.renameGradleModule.projectDirectoryMappingUnsupported",
            )
            SettingsInspection.Supported -> Unit
        }
        val pathRange = literalIncludePathRange(settingsContent, dsl, oldProjectPath, newProjectPath)
            ?: return GradleSettingsModuleRenameResult.Refused("java.renameGradleModule.settingsIncludeUnsupported")
        val mapping = when (dsl) {
            GradleSettingsDsl.KOTLIN -> "project(\"$newProjectPath\").projectDir = file(\"${oldSegments.joinToString("/")}\")"
            GradleSettingsDsl.GROOVY -> "project('$newProjectPath').projectDir = file('${oldSegments.joinToString("/")}')"
        }
        val separator = if (settingsContent.isNotEmpty() &&
            settingsContent.last() != '\n' && settingsContent.last() != '\r'
        ) lineEnding(settingsContent) else ""
        return GradleSettingsModuleRenameResult.Supported(
            GradleSettingsModuleRenameProposal(
                dsl = dsl,
                oldProjectPath = oldProjectPath,
                newProjectPath = newProjectPath,
                physicalProjectDirectory = oldSegments.fold(Path.of(""), Path::resolve),
                pathLiteralStart = pathRange.first,
                pathLiteralEnd = pathRange.last + 1,
                insertionOffset = settingsContent.length,
                insertedText = "$separator$mapping${lineEnding(settingsContent)}",
            ),
        )
    }

    private fun projectPathSegments(value: String): List<String>? {
        if (!value.startsWith(':') || value.length < 2 || value.length > 2_048) return null
        val segments = value.drop(1).split(':')
        if (segments.size > 32 || segments.any { segment ->
                !safeSegment.matches(segment) || segment.endsWith('.') || segment in setOf(".", "..") ||
                    windowsDeviceName.matches(segment)
            }
        ) return null
        return segments
    }

    /** Selects exactly one canonical project identity among unconditional literal include calls. */
    private fun literalIncludePathRange(
        source: String, dsl: GradleSettingsDsl, expectedPath: String, newProjectPath: String,
    ): IntRange? {
        fun trivia(from: Int, newlines: Boolean): Int? {
            var at = from
            while (at < source.length) {
                when {
                    source[at] == '\n' || source[at] == '\r' -> if (newlines) at++ else return at
                    source[at].isWhitespace() -> at++
                    source.startsWith("//", at) -> {
                        while (at < source.length && source[at] != '\n' && source[at] != '\r') at++
                    }
                    source.startsWith("/*", at) -> {
                        var depth = 1
                        at += 2
                        while (at < source.length && depth > 0) {
                            when {
                                source.startsWith("/*", at) -> { depth++; at += 2 }
                                source.startsWith("*/", at) -> { depth--; at += 2 }
                                else -> at++
                            }
                        }
                        if (depth != 0) return null
                    }
                    else -> return at
                }
            }
            return at
        }
        fun arguments(from: Int): Pair<Int, List<Pair<String, IntRange>>>? {
            var at = trivia(from, false) ?: return null
            val parenthesized = source.getOrNull(at) == '('
            if (!parenthesized && dsl == GradleSettingsDsl.KOTLIN) return null
            if (parenthesized) at++
            at = trivia(at, parenthesized) ?: return null
            val paths = mutableListOf<Pair<String, IntRange>>()
            while (true) {
                val quote = source.getOrNull(at) ?: return null
                if (quote !in setOf('\'', '"') || dsl == GradleSettingsDsl.KOTLIN && quote != '"' ||
                    source.startsWith("$quote$quote$quote", at)
                ) return null
                val begin = ++at
                while (at < source.length && source[at] != quote) {
                    if (source[at] in setOf('\\', '$', '\n', '\r')) return null
                    at++
                }
                if (at == source.length || at == begin) return null
                val literal = source.substring(begin, at)
                val canonical = if (literal.startsWith(':')) literal else ":$literal"
                if (projectPathSegments(canonical) == null) return null
                paths += canonical to (begin until at)
                at = trivia(at + 1, parenthesized) ?: return null
                if (source.getOrNull(at) == ',') {
                    at = trivia(at + 1, true) ?: return null
                    continue
                }
                if (parenthesized) {
                    if (source.getOrNull(at) != ')') return null
                    at = trivia(at + 1, false) ?: return null
                }
                if (at < source.length && source[at] !in setOf('\n', '\r', ';')) return null
                return at to paths
            }
        }
        val delimiters = ArrayDeque<Char>()
        val included = mutableSetOf<String>()
        var index = 0
        var blockCommentDepth = 0
        var lineComment = false
        var result: IntRange? = null
        while (index < source.length) {
            val current = source[index]
            if (lineComment) {
                if (current == '\n' || current == '\r') lineComment = false
                index++
                continue
            }
            if (blockCommentDepth > 0) {
                if (source.startsWith("/*", index)) { blockCommentDepth++; index += 2 }
                else if (source.startsWith("*/", index)) { blockCommentDepth--; index += 2 }
                else index++
                continue
            }
            if (source.startsWith("//", index)) { lineComment = true; index += 2; continue }
            if (source.startsWith("/*", index)) { blockCommentDepth = 1; index += 2; continue }
            if (current == '\'' || current == '"') {
                val quote = current
                val triple = source.startsWith("$quote$quote$quote", index)
                index += if (triple) 3 else 1
                var closed = false
                while (index < source.length) {
                    if (triple && source.startsWith("$quote$quote$quote", index)) { index += 3; closed = true; break }
                    if (!triple && source[index] == quote) { index++; closed = true; break }
                    if (source[index] == '\\') {
                        if (index + 1 >= source.length) return null
                        index += 2
                    } else index++
                }
                if (!closed) return null
                continue
            }
            if (current.isJavaIdentifierStart()) {
                val start = index++
                while (index < source.length && source[index].isJavaIdentifierPart()) index++
                val identifier = source.substring(start, index)
                if (delimiters.isEmpty() && identifier in setOf("if", "else", "for", "while", "do", "when", "try")) return null
                if (identifier == "include") {
                    if (delimiters.isNotEmpty()) return null
                    val boundary = maxOf(source.lastIndexOf('\n', start - 1), source.lastIndexOf(';', start - 1))
                    if (source.substring(boundary + 1, start).any { !it.isWhitespace() }) return null
                    val (end, paths) = arguments(index) ?: return null
                    for ((path, range) in paths) {
                        if (!included.add(path) || path == newProjectPath) return null
                        if (path == expectedPath) result = range
                    }
                    index = end
                }
                continue
            }
            when (current) {
                '(', '{', '[' -> delimiters.addLast(when (current) { '(' -> ')'; '{' -> '}'; else -> ']' })
                ')', '}', ']' -> if (delimiters.removeLastOrNull() != current) return null
            }
            index++
        }
        return result.takeIf { blockCommentDepth == 0 && delimiters.isEmpty() }
    }

    private sealed interface SettingsInspection {
        data object Supported : SettingsInspection
        data object ProjectDirectoryMapping : SettingsInspection
        data object UnsupportedSyntax : SettingsInspection
    }

    /**
     * Confirms that a new top-level statement can be appended without landing inside a string,
     * comment or nested block. Existing Gradle code is left byte-for-byte untouched.
     */
    private fun inspect(source: String): SettingsInspection {
        val closers = ArrayDeque<Char>()
        var index = 0
        var blockCommentDepth = 0
        var lineComment = false
        var projectDirectoryMapping = false

        while (index < source.length) {
            val current = source[index]
            if (lineComment) {
                if (current == '\n' || current == '\r') lineComment = false
                index++
                continue
            }
            if (blockCommentDepth > 0) {
                if (source.startsWith("/*", index)) {
                    blockCommentDepth++
                    index += 2
                } else if (source.startsWith("*/", index)) {
                    blockCommentDepth--
                    index += 2
                } else index++
                continue
            }
            if (source.startsWith("//", index)) {
                lineComment = true
                index += 2
                continue
            }
            if (source.startsWith("/*", index)) {
                blockCommentDepth = 1
                index += 2
                continue
            }
            if (current == '\u0060') return SettingsInspection.UnsupportedSyntax
            if (current == '\'' || current == '"') {
                val quote = current
                val triple = source.startsWith("$quote$quote$quote", index)
                val width = if (triple) 3 else 1
                index += width
                var closed = false
                while (index < source.length) {
                    if (triple && source.startsWith("$quote$quote$quote", index)) {
                        index += 3
                        closed = true
                        break
                    }
                    if (!triple && source[index] == quote) {
                        index++
                        closed = true
                        break
                    }
                    if (source[index] == '\\') {
                        if (index + 1 >= source.length) return SettingsInspection.UnsupportedSyntax
                        index += 2
                    } else index++
                }
                if (!closed) return SettingsInspection.UnsupportedSyntax
                continue
            }
            if (current.isJavaIdentifierStart()) {
                val start = index
                index++
                while (index < source.length && source[index].isJavaIdentifierPart()) index++
                if (source.substring(start, index) == "projectDir") projectDirectoryMapping = true
                continue
            }
            when (current) {
                '(', '{', '[' -> closers.addLast(when (current) {
                    '(' -> ')'
                    '{' -> '}'
                    else -> ']'
                })
                ')', '}', ']' -> if (closers.removeLastOrNull() != current) {
                    return SettingsInspection.UnsupportedSyntax
                }
            }
            index++
        }
        if (blockCommentDepth != 0 || closers.isNotEmpty()) return SettingsInspection.UnsupportedSyntax
        return if (projectDirectoryMapping) SettingsInspection.ProjectDirectoryMapping
        else SettingsInspection.Supported
    }

    private fun lineEnding(source: String): String = if ("\r\n" in source) "\r\n" else "\n"
}
