package org.refactorkit.c

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Collections

/** A deliberately narrow, non-executing root CMakeLists.txt authority. Not a CMake interpreter. */
internal sealed interface CMakeBindingDecision {
    data object NoManifest : CMakeBindingDecision
    data class Refused(val reason: String) : CMakeBindingDecision
    data class Bound(
        val edits: List<TextEdit>,
        val target: String,
        val targetKind: String,
        val libraryType: String?,
        val sourceOwners: Map<Path, List<CMakeLiteralTargetOwner>>,
        val includeRoots: List<CMakeLiteralIncludeRoot>,
    ) : CMakeBindingDecision
}

/** One literal source occurrence; duplicates remain visible until managed validation. */
internal data class CMakeLiteralTargetOwner(val target: String, val targetKind: String, val libraryType: String?)

/** Literal compiler-flag origins for every target, not only the moved owner. */
internal enum class CMakeIncludeVisibility { PRIVATE, PUBLIC }
internal data class CMakeLiteralIncludeRoot(val target: String, val path: Path,
    val visibility: CMakeIncludeVisibility = CMakeIncludeVisibility.PRIVATE)

internal object CMakeLiteralBindings {
    private val manifestPath = Path.of("CMakeLists.txt")
    private val identifier = Regex("[A-Za-z_][A-Za-z_0-9.+-]*")
    private val sourceSuffixes = setOf("c", "h")
    private val visibilities = setOf("PRIVATE", "PUBLIC", "INTERFACE")

    fun move(snapshot: ProjectSnapshot, oldPath: Path, newPath: Path): CMakeBindingDecision =
        rewrite(snapshot, mapOf(oldPath to newPath), null)

    fun relocate(snapshot: ProjectSnapshot, moved: Map<Path, Path>, oldDir: Path, newDir: Path): CMakeBindingDecision =
        rewrite(snapshot, moved, oldDir to newDir)

    private fun rewrite(
        snapshot: ProjectSnapshot,
        moved: Map<Path, Path>,
        relocatedDirectory: Pair<Path, Path>?,
    ): CMakeBindingDecision {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        if (moved.keys.any { old ->
                generateSequence(old.normalize().parent) { it.parent }
                    .filter { !it.isAbsolute && !it.startsWith("..") && it.toString().isNotEmpty() }
                    .any { Files.exists(root.resolve(it).resolve(manifestPath), LinkOption.NOFOLLOW_LINKS) }
            }) return CMakeBindingDecision.Refused("Nested CMakeLists.txt is not captured by root-only CMake authority")
        val candidates = snapshot.trackedFiles.filter { it.path.normalize() == manifestPath }
        if (candidates.isEmpty()) {
            return if (Files.exists(root.resolve(manifestPath), LinkOption.NOFOLLOW_LINKS)) {
                CMakeBindingDecision.Refused("Root CMakeLists.txt exists but is not captured in the snapshot")
            } else CMakeBindingDecision.NoManifest
        }
        if (candidates.size != 1 || candidates.single().content.length > 512 * 1024 ||
            Files.isSymbolicLink(root.resolve(manifestPath))) {
            return CMakeBindingDecision.Refused("Root CMakeLists.txt has ambiguous or unsafe provenance")
        }
        if (moved.isEmpty() || moved.any { (oldPath, newPath) ->
                oldPath.fileName.toString().substringAfterLast('.', "") !in sourceSuffixes ||
                    newPath.fileName.toString().substringAfterLast('.', "") !in sourceSuffixes
            }) return CMakeBindingDecision.Refused("CMake binding for this language is not qualified")
        val content = candidates.single().content
        val commands = try {
            tokenize(content)
        } catch (failure: UnsupportedCMake) {
            return CMakeBindingDecision.Refused(failure.message ?: "Unrecognized CMake syntax")
        }
        val targets = mutableSetOf<String>()
        val targetKinds = mutableMapOf<String, String>()
        val targetLibraryTypes = mutableMapOf<String, String>()
        val sources = mutableMapOf<Path, MutableList<Pair<String, Token>>>()
        val includeRoots = mutableListOf<ParsedIncludeRoot>()
        var projectSeen = false
        var minimumSeen = false
        try {
            for (command in commands) {
                val tokens = command.tokens
                when (command.name.lowercase()) {
                    "cmake_minimum_required" -> {
                        requireCmake(!minimumSeen && tokens.size == 2 && tokens[0].value.equals("VERSION", true) &&
                            tokens[1].value.matches(Regex("[0-9]+(\\.[0-9]+){1,2}")), "Unsupported minimum CMake version expression")
                        minimumSeen = true
                    }
                    "project" -> {
                        requireCmake(!projectSeen && tokens.size == 3 && tokens[0].value.matches(identifier) &&
                            tokens[1].value.equals("LANGUAGES", true) && tokens[2].value == "C",
                            "Project language/configuration is not a literal C-only binding")
                        projectSeen = true
                    }
                    "add_library", "add_executable" -> {
                        requireCmake(tokens.size >= 2 && tokens[0].value.matches(identifier), "Missing literal CMake target/source")
                        val target = tokens[0].value
                        requireCmake(targets.add(target), "Duplicate target or ambiguous build owner")
                        targetKinds[target] = command.name.lowercase()
                        if (command.name.equals("add_library", true) && tokens.size >= 2 && tokens[1].value.uppercase() in setOf("STATIC", "SHARED", "MODULE", "OBJECT", "INTERFACE_LIBRARY")) {
                            targetLibraryTypes[target] = tokens[1].value.uppercase()
                        }
                        val fileTokens = if (command.name.equals("add_library", true) &&
                            tokens[1].value in setOf("STATIC", "SHARED")) tokens.drop(2) else tokens.drop(1)
                        requireCmake(fileTokens.isNotEmpty(), "CMake target has no literal source")
                        fileTokens.forEach { source -> recordSource(snapshot, target, source, sources) }
                    }
                    "target_sources" -> {
                        requireCmake(tokens.size >= 3 && tokens[0].value in targets, "Unknown CMake target_sources owner")
                        var visibility = ""
                        tokens.drop(1).forEach { token ->
                            if (token.value in visibilities) visibility = token.value
                            else {
                                requireCmake(visibility == "PRIVATE", "Unqualified or exported CMake target source")
                                recordSource(snapshot, tokens[0].value, token, sources)
                            }
                        }
                    }
                    "target_include_directories" -> {
                        requireCmake(tokens.size >= 3 && tokens[0].value in targets, "Unknown include-directory target")
                        var visibility = ""
                        tokens.drop(1).forEach { token ->
                            if (token.value in visibilities) visibility = token.value
                            else {
                                requireCmake(visibility in setOf("PRIVATE", "PUBLIC"), "Unqualified or interface-only include directory")
                                val path = literalPath(token)
                                requireCmake(Files.isDirectory(root.resolve(path), LinkOption.NOFOLLOW_LINKS),
                                    "Include directory is missing or not a regular workspace directory")
                                includeRoots += ParsedIncludeRoot(tokens[0].value, path, token, CMakeIncludeVisibility.valueOf(visibility))
                            }
                        }
                    }
                    else -> throw UnsupportedCMake("Unsupported CMake command: ${command.name}")
                }
            }
            requireCmake(minimumSeen && projectSeen && targets.isNotEmpty(), "Incomplete root CMake project authority")
            val edits = mutableListOf<TextEdit>()
            val boundTargets = mutableSetOf<String>()
            for ((oldPath, newPath) in moved) {
                val owners = sources[oldPath.normalize()].orEmpty()
                requireCmake(owners.size <= 1, "Ambiguous or duplicate CMake target source binding")
                val includeOwners = includeRoots.filter { (_, dir, _) -> oldPath.normalize().startsWith(dir) }
                    .map { it.target }.distinct()
                if (oldPath.fileName.toString().endsWith(".c")) {
                    requireCmake(owners.size == 1, "Moved C source does not have exactly one literal target binding")
                } else {
                    requireCmake(owners.size == 1 || (relocatedDirectory != null && includeOwners.size == 1),
                        "Moved header has no qualified literal target/include binding")
                }
                boundTargets += owners.map { it.first } + includeOwners
                owners.forEach { (_, token) ->
                    edits += TextEdit(SourceRange(position(content, token.start), position(content, token.end)),
                        newPath.normalize().toString().replace('\\', '/'))
                }
            }
            requireCmake(boundTargets.size == 1, "Moved component has multiple or unbound CMake targets")
            if (relocatedDirectory != null) {
                val (oldDir, newDir) = relocatedDirectory
                includeRoots.forEach { (target, path, token) ->
                    if (path.startsWith(oldDir)) {
                        requireCmake(target in boundTargets, "Include root belongs to a different build target")
                        val replacement = newDir.resolve(oldDir.relativize(path)).normalize()
                            .toString().replace('\\', '/')
                        edits += TextEdit(SourceRange(position(content, token.start), position(content, token.end)), replacement)
                    } else if (moved.any { (old, new) -> old.startsWith(path) && !new.startsWith(path) }) {
                        throw UnsupportedCMake("Relocation escapes an unchanged include root")
                    }
                }
            } else if (moved.any { (old, new) -> old.toString().endsWith(".h") &&
                    includeRoots.any { (_, dir, _) -> old.startsWith(dir) && !new.startsWith(dir) }
                }) {
                throw UnsupportedCMake("Header move escapes a qualified include root")
            }
            requireCmake(edits.isNotEmpty(), "No literal CMake target or include binding changed")
            val bt = boundTargets.single()
            val sourceOwners = Collections.unmodifiableMap(sources.mapValues { (_, occurrences) ->
                Collections.unmodifiableList(occurrences.map { (target, _) ->
                    CMakeLiteralTargetOwner(target, targetKinds.getValue(target), targetLibraryTypes[target])
                })
            })
            val literalIncludeRoots = Collections.unmodifiableList(includeRoots.map {
                CMakeLiteralIncludeRoot(it.target, it.path, it.visibility)
            })
            return CMakeBindingDecision.Bound(edits, bt, targetKinds.getValue(bt), targetLibraryTypes[bt], sourceOwners, literalIncludeRoots)
        } catch (failure: UnsupportedCMake) {
            return CMakeBindingDecision.Refused(failure.message ?: "Unproven CMake target binding")
        }
    }

    private data class ParsedIncludeRoot(val target: String, val path: Path, val token: Token,
        val visibility: CMakeIncludeVisibility)

    private fun recordSource(
        snapshot: ProjectSnapshot,
        target: String,
        token: Token,
        sources: MutableMap<Path, MutableList<Pair<String, Token>>>,
    ) {
        val path = literalPath(token)
        requireCmake(path.fileName.toString().substringAfterLast('.', "") in sourceSuffixes,
            "CMake target has an unsupported or mixed-language input")
        requireCmake(snapshot.files.any { it.path.normalize() == path && it.languageId == "c" },
            "CMake target source is not captured as workspace C input")
        sources.getOrPut(path) { mutableListOf() } += target to token
    }

    private fun literalPath(token: Token): Path {
        val value = token.value
        requireCmake(value.isNotBlank() && !value.startsWith('/') && !value.contains('\\') &&
            !value.contains('$') && !value.contains(';') && !value.contains(':') && !value.contains('[') &&
            !value.contains(']') && !value.contains('<') && !value.contains('>') && !value.contains('{') &&
            !value.contains('}'), "CMake path is not a supported literal")
        val path = runCatching { Path.of(value) }.getOrNull()
        requireCmake(path != null && !path.isAbsolute && path.none { it.toString() == ".." },
            "CMake path escapes or aliases the workspace")
        return path!!.normalize()
    }

    private fun position(text: String, offset: Int): SourcePosition {
        val before = text.substring(0, offset)
        return SourcePosition(before.count { it == '\n' }, before.substringAfterLast('\n').length)
    }

    private fun tokenize(content: String): List<Command> {
        val result = mutableListOf<Command>()
        var i = 0
        fun skipWhitespaceAndComments() {
            while (i < content.length) {
                if (content[i].isWhitespace()) i++
                else if (content[i] == '#') {
                    while (i < content.length && content[i] != '\n') i++
                } else break
            }
        }
        while (true) {
            skipWhitespaceAndComments()
            if (i == content.length) return result
            val start = i
            while (i < content.length && (content[i].isLetterOrDigit() || content[i] == '_')) i++
            requireCmake(start != i && content.substring(start, i).matches(identifier), "CMake command is not a literal identifier")
            val name = content.substring(start, i)
            skipWhitespaceAndComments()
            requireCmake(i < content.length && content[i] == '(', "CMake command has no literal argument list")
            i++
            val tokens = mutableListOf<Token>()
            while (true) {
                skipWhitespaceAndComments()
                requireCmake(i < content.length, "Unclosed CMake command")
                if (content[i] == ')') { i++; break }
                val quoted = content[i] == '"'
                if (quoted) i++
                val tokenStart = i
                while (i < content.length && if (quoted) content[i] != '"' else !content[i].isWhitespace() && content[i] != ')' && content[i] != '#') {
                    requireCmake(content[i] !in "\\$;[]{}()", "CMake escapes, variables, lists and nested expressions are unsupported")
                    i++
                }
                requireCmake(i > tokenStart, "Empty CMake argument")
                val tokenEnd = i
                if (quoted) {
                    requireCmake(i < content.length && content[i] == '"', "Unclosed CMake string")
                    i++
                    requireCmake(i == content.length || content[i].isWhitespace() || content[i] == ')' || content[i] == '#',
                        "Adjacent CMake token segments are unsupported")
                }
                tokens += Token(content.substring(tokenStart, tokenEnd), tokenStart, tokenEnd)
            }
            result += Command(name, tokens)
        }
    }

    private fun requireCmake(condition: Boolean, reason: String) {
        if (!condition) throw UnsupportedCMake(reason)
    }

    private data class Token(val value: String, val start: Int, val end: Int)
    private data class Command(val name: String, val tokens: List<Token>)
    private class UnsupportedCMake(message: String) : IllegalArgumentException(message)
}
