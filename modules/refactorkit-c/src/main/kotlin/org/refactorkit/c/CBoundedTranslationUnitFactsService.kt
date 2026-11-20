package org.refactorkit.c

import kotlinx.serialization.json.*
import org.refactorkit.core.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import java.util.EnumSet

internal class CBoundedTranslationUnitFailure(val diagnostic: Diagnostic) : IllegalStateException(diagnostic.message)
private fun boundedFailure(code: String, message: String): Nothing = throw CBoundedTranslationUnitFailure(Diagnostic(
    message, Diagnostic.Severity.ERROR, code = code, evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY,
))
private fun boundedRequire(value: Boolean, code: String, message: String) { if (!value) boundedFailure(code, message) }
private fun boundedSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun boundedFingerprint(values: List<String>) = boundedSha(values.joinToString("") {
    "${it.toByteArray(Charsets.UTF_8).size}:$it"
}.toByteArray(Charsets.UTF_8))

internal class CBoundedTranslationUnitCompilationContext(val databasePath: Path, val databaseSha256: String,
    flags: Map<Path, List<String>>) {
    val flags: Map<Path, List<String>> = Collections.unmodifiableMap(flags.mapValues { (_, value) ->
        Collections.unmodifiableList(ArrayList(value))
    })
    val identity: String = boundedFingerprint(listOf(databasePath.toString(), databaseSha256) +
        this.flags.entries.sortedBy { it.key.toString() }.flatMap { (tu, args) -> listOf(tu.toString()) + args })
}
internal class CBoundedTranslationUnitAsts(asts: Map<Path, JsonObject>, sources: Map<Path, String>, logicalPaths: Map<Path, Path>,
    readOnlySources: Map<Path, String> = emptyMap()) {
    val asts: Map<Path, JsonObject> = Collections.unmodifiableMap(LinkedHashMap(asts))
    val sources: Map<Path, String> = Collections.unmodifiableMap(LinkedHashMap(sources))
    val logicalPaths: Map<Path, Path> = Collections.unmodifiableMap(LinkedHashMap(logicalPaths))
    val readOnlySources: Map<Path, String> = Collections.unmodifiableMap(LinkedHashMap(readOnlySources))
}
internal sealed interface CBoundedTranslationUnitCompilerResult {
    data class Available(val snapshotHash: String, val facts: CBoundedTranslationUnitAsts,
        val context: CBoundedTranslationUnitCompilationContext) : CBoundedTranslationUnitCompilerResult
    data class Unavailable(val diagnostic: Diagnostic) : CBoundedTranslationUnitCompilerResult
}

/** Read-only acquisition and input ownership; pure binding rules are delegated to the domain. */
internal class CBoundedTranslationUnitFactsService(private val toolchain: ClangSemanticToolchain,
    private val processes: ExternalSemanticProcessManager, private val diagnosticPrefix: String) {
    fun analyze(live: ProjectSnapshot, candidate: ProjectSnapshot): CBoundedTranslationUnitCompilerResult = try {
        boundedRequire(live.workspace.root == candidate.workspace.root && live.files.map { it.path to it.languageId } ==
            candidate.files.map { it.path to it.languageId } && candidate.copy(files = live.files).hash == live.hash,
            "$diagnosticPrefix.previewBoundaryInvalid", "Compiler analysis may change captured source contents only")
        val root = live.workspace.root.toAbsolutePath().normalize()
        when (val admission = CHeaderFieldSourcePolicy.check(candidate.files.associate { it.path to it.content })) {
            CHeaderFieldSourceAdmission.Admitted -> Unit
            is CHeaderFieldSourceAdmission.Refused -> boundedFailure("$diagnosticPrefix.sourceUnsupported", admission.reason.name)
        }
        requireCurrent(live)
        boundedRequire(toolchain.provenance.clangVersion == "22.1.8" && CCompilationSnapshotCapture.compilerIsCurrent(toolchain),
            "$diagnosticPrefix.compilerUnavailable", "Current retained Clang 22.1.8 and its complete runtime/resources are required")
        val context = context(live)
        requireIncludeClosure(candidate, context)
        CClangdLaunchContext.capture(root, toolchain.clangExecutable, toolchain.readOnlySdkInputs.requests).use { launch ->
            boundedRequire(launch.isCurrent(), "$diagnosticPrefix.snapshotChanged", "Private compilation context changed")
            SemanticWorkspaceOverlay.create(candidate, maxFiles = 64, maxBytes = 16 * 1024 * 1024).use { overlay ->
                val owned = candidate.files.associate { source ->
                    requireNotNull(overlay.toOverlayPath(root.resolve(source.path))) to source.content
                }
                val trees = linkedMapOf<Path, JsonObject>()
                var astBytes = 0L
                context.flags.entries.sortedBy { it.key.toString() }.forEach { (tu, flags) ->
                    val arguments = remapIncludes(flags, overlay.root) + requireNotNull(overlay.toOverlayPath(root.resolve(tu))).toString()
                    requireCurrent(live)
                    boundedRequire(launch.isCurrent() && CCompilationSnapshotCapture.compilerIsCurrent(toolchain),
                        "$diagnosticPrefix.snapshotChanged", "Native compilation inputs changed before analysis")
                    val captured = ClangFunctionAstBridge.read(toolchain, arguments, overlay.root, processes)
                    boundedRequire(captured.provenance.executableSha256 == toolchain.provenance.evidence.single {
                        it.role == "clang-executable"
                    }.sha256, "$diagnosticPrefix.compilerUnavailable", "AST producer differs from the captured compiler")
                    astBytes += captured.stdoutBytes
                    boundedRequire(astBytes <= 32 * 1024 * 1024, "$diagnosticPrefix.inputLimit", "Combined AST budget exceeded")
                    trees[requireNotNull(overlay.toOverlayPath(root.resolve(tu)))] = captured.ast
                }
                requireCurrent(live)
                boundedRequire(launch.isCurrent() && CCompilationSnapshotCapture.compilerIsCurrent(toolchain) &&
                    overlay.verifySourcesUnchanged().isEmpty(), "$diagnosticPrefix.snapshotChanged",
                    "Captured workspace, overlay or compiler inputs changed during analysis")
                val logicalPaths = owned.keys.associateWith { path ->
                    root.relativize(requireNotNull(overlay.toWorkspacePath(path)))
                }
                CBoundedTranslationUnitCompilerResult.Available(candidate.hash,
                    CBoundedTranslationUnitAsts(trees, owned, logicalPaths, toolchain.readOnlySdkInputs.sources), context)
            }
        }
    } catch (failure: CBoundedTranslationUnitFailure) { CBoundedTranslationUnitCompilerResult.Unavailable(failure.diagnostic) }
      catch (_: Exception) { CBoundedTranslationUnitCompilerResult.Unavailable(Diagnostic("Complete native translation-unit facts are unavailable",
          Diagnostic.Severity.ERROR, code = "$diagnosticPrefix.compilerUnavailable", evidence = DiagnosticEvidence.COMPILER,
          category = DiagnosticCategory.SAFETY)) }

    /** A bounded inventory catches uncaptured consumers and symlinks; no project command is executed. */
    fun requireCurrent(snapshot: ProjectSnapshot) {
        val code = "$diagnosticPrefix.snapshotChanged"
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        boundedRequire(snapshot.copy().hash == snapshot.hash && snapshot.files.isNotEmpty() && snapshot.files.size <= 32 &&
            snapshot.trackedFiles.size <= 64 && snapshot.files.all { it.languageId == "c" &&
                (it.path.toString().endsWith(".c") || it.path.toString().endsWith(".h")) }, code,
            "A bounded complete C source/header snapshot is required")
        var component = requireNotNull(root.root)
        for (part in root) {
            component = component.resolve(part)
            boundedRequire(Files.isDirectory(component, LinkOption.NOFOLLOW_LINKS), code, "Workspace is absent or follows a symlink")
        }
        val expected = snapshot.files.map { it.path.normalize() }.toSet()
        val observed = mutableSetOf<Path>()
        var visits = 0
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption::class.java), 33, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                boundedRequire(++visits <= 16384, code, "Workspace inventory limit exceeded")
                if (dir != root && dir.fileName.toString() in setOf(".git", ".refactorkit")) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                boundedRequire(++visits <= 16384 && !attrs.isDirectory, code, "Workspace inventory depth or entry limit exceeded")
                boundedRequire(!attrs.isSymbolicLink, code, "Unqualified workspace symlink")
                val extension = file.fileName.toString().substringAfterLast('.', "").lowercase()
                if (extension in setOf("c", "h", "cc", "cpp", "cxx", "hh", "hpp", "hxx", "m", "mm")) {
                    boundedRequire(!attrs.isSymbolicLink && attrs.isRegularFile && extension in setOf("c", "h"), code,
                        "Unowned or unsupported C-family consumer")
                    observed.add(root.relativize(file))
                }
                return FileVisitResult.CONTINUE
            }
        })
        boundedRequire(observed == expected, code, "Captured C consumers differ from the live workspace inventory")
        snapshot.trackedFiles.forEach { source ->
            val rel = source.path
            boundedRequire(!rel.isAbsolute && rel == rel.normalize() && !rel.startsWith(".."), code, "Unowned tracked input")
            var parent = root
            for (part in rel) {
                parent = parent.resolve(part)
                boundedRequire(!Files.isSymbolicLink(parent), code, "Tracked input follows a symlink")
            }
            val bytes = source.content.toByteArray(Charsets.UTF_8)
            boundedRequire(bytes.size <= 8 * 1024 * 1024 && Files.isRegularFile(parent, LinkOption.NOFOLLOW_LINKS), code, "Tracked input unavailable")
            val live = Files.newInputStream(parent, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(bytes.size + 1) }
            boundedRequire(live.contentEquals(bytes), code, "Source or configuration differs from its captured bytes")
        }
        val databasePaths = listOf(Path.of("compile_commands.json"), Path.of("build/compile_commands.json"))
        databasePaths.forEach { path ->
            val present = Files.exists(root.resolve(path), LinkOption.NOFOLLOW_LINKS)
            boundedRequire(present == snapshot.auxiliaryFiles.any { it.path == path }, code, "Uncaptured or missing compilation database")
        }
    }

    private fun context(snapshot: ProjectSnapshot): CBoundedTranslationUnitCompilationContext {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val db = snapshot.auxiliaryFiles.filter { it.languageId == "c-compilation-database" }.singleOrNull()
            ?: boundedFailure("$diagnosticPrefix.compilationContextUnavailable", "Exactly one captured compilation database is required")
        val parsed = CCompilationDatabaseParser(CCompilationDatabasePolicy(requireArgumentsArray = true))
            .parse(root, db.content.toByteArray(Charsets.UTF_8)) as? CCompilationDatabaseDiscovery.Available
            ?: boundedFailure("$diagnosticPrefix.compilationContextUnavailable", "Explicit compilation arguments are unavailable")
        val expected = snapshot.files.filter { it.path.toString().endsWith(".c") }.map { root.resolve(it.path) }.toSet()
        boundedRequire(expected.isNotEmpty() && expected.size <= 16 && parsed.database.units.map { it.file }.toSet() == expected &&
            parsed.database.units.size == expected.size, "$diagnosticPrefix.incompleteTUs", "Every captured C translation unit needs exactly one compiler context")
        boundedRequire(parsed.database.units.all { unit -> unit.standard in setOf("c11", "c17") &&
            Path.of(unit.arguments.first()).toAbsolutePath().normalize() == toolchain.clangExecutable.toAbsolutePath().normalize() },
            "$diagnosticPrefix.compilationContextUnavailable", "Only explicit selected-compiler C11/C17 contexts are admitted")
        CClangdLaunchContext.capture(root, toolchain.clangExecutable, toolchain.readOnlySdkInputs.requests).use { context ->
            val units = Json.parseToJsonElement(Files.readString(context.root.resolve("compile_commands.json"))).jsonArray
            val flags = units.associate { raw ->
                val unit = raw.jsonObject
                val file = Path.of(unit.getValue("file").jsonPrimitive.content)
                val args = unit.getValue("arguments").jsonArray.map { it.jsonPrimitive.content }
                boundedRequire(args.last() == file.toString(), "$diagnosticPrefix.compilationContextUnavailable", "Normalized source context differs")
                val normalized = mutableListOf<String>()
                var index = 1
                while (index < args.lastIndex) {
                    val argument = args[index]
                    when {
                        argument in INCLUDE_FLAGS || argument == "--sysroot" -> {
                            val include = Path.of(args[++index]).normalize()
                            val sdkRoot = argument == "--sysroot" && toolchain.readOnlySdkInputs.requests.any { it.root == include }
                            boundedRequire(include.startsWith(root) || sdkRoot, "$diagnosticPrefix.includeUnavailable", "Compiler search directory escapes captured workspace and configured SDK roots")
                            normalized += argument
                            normalized += if (sdkRoot) include.toString() else root.relativize(include).toString().ifEmpty { "." }
                        }
                        argument.startsWith("-D") -> {
                            boundedRequire(argument.substring(2).matches(Regex("[A-Za-z_][A-Za-z_0-9]*(=([A-Za-z_][A-Za-z_0-9]*|[0-9]+))?")),
                                "$diagnosticPrefix.compilationContextUnavailable", "Unsupported macro replacement")
                            normalized += argument
                        }
                        else -> normalized += argument
                    }
                    index++
                }
                root.relativize(file) to normalized.toList()
            }
            boundedRequire(context.isCurrent(), "$diagnosticPrefix.snapshotChanged", "Captured normalized compiler configuration changed")
            return CBoundedTranslationUnitCompilationContext(db.path, boundedSha(db.content.toByteArray(Charsets.UTF_8)), flags)
        }
    }

    private fun requireIncludeClosure(snapshot: ProjectSnapshot, context: CBoundedTranslationUnitCompilationContext) {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val known = snapshot.files.map { root.resolve(it.path) }.toSet() + toolchain.readOnlySdkInputs.sources.keys
        val admittedRoots = listOf(root) + toolchain.readOnlySdkInputs.requests.map { it.root }
        context.flags.values.forEach { flags ->
            val directories = flags.indices.filter { flags[it] in INCLUDE_FLAGS }.map { root.resolve(flags[it + 1]).normalize() } +
                flags.indices.filter { flags[it] == "--sysroot" }.map { root.resolve(flags[it + 1]).resolve("usr/include").normalize() }
            snapshot.files.forEach { source -> source.content.lineSequence().forEach { line ->
                val match = INCLUDE.matchEntire(line) ?: return@forEach
                val name = Path.of(match.groupValues[1])
                boundedRequire(!name.isAbsolute, "$diagnosticPrefix.includeUnavailable", "Absolute source include is unowned")
                val candidates = (listOf(root.resolve(source.path).parent) + directories).map { it.resolve(name).normalize() }.distinct()
                boundedRequire(candidates.all { candidate -> admittedRoots.any { candidate.startsWith(it) } },
                    "$diagnosticPrefix.includeUnavailable", "Source include can escape workspace and configured SDK roots")
                val existing = candidates.filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
                boundedRequire(existing.isNotEmpty() && existing.all { it in known && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) },
                    "$diagnosticPrefix.includeUnavailable", "Every resolvable include candidate must be a captured source/header or readonly SDK input")
            } }
        }
    }
    private fun remapIncludes(flags: List<String>, overlayRoot: Path): List<String> = flags.mapIndexed { index, value ->
        if (index > 0 && (flags[index - 1] in INCLUDE_FLAGS || flags[index - 1] == "--sysroot"))
            overlayRoot.resolve(value).normalize().toString() else value
    }
    companion object {
        private val INCLUDE_FLAGS = setOf("-I", "-iquote", "-isystem")
        private val INCLUDE = Regex("\\s*#\\s*include\\s+\"([A-Za-z_0-9./-]+)\"\\s*")
    }
}

