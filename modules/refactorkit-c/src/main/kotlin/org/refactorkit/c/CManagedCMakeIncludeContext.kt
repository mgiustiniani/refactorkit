package org.refactorkit.c

import kotlinx.serialization.json.*
import org.refactorkit.core.*
import java.nio.file.Path
import java.security.MessageDigest

internal sealed interface CMakeIncludePreview {
    data class Available(val plan: PatchPlan) : CMakeIncludePreview
    data object OutsideScope : CMakeIncludePreview
    data class Unavailable(val diagnostic: Diagnostic) : CMakeIncludePreview
}

/** Owned application proof for literal include-bearing CMake moves. Project commands remain data. */
internal class CManagedCMakeIncludeContext(private val toolchain: ClangSemanticToolchain,
    processes: ExternalSemanticProcessManager, private val diagnostics: CCompilerDiagnostics) {
    private val currency = CBoundedTranslationUnitFactsService(toolchain, processes, "c.cmakeInclude")
    private val prefix = CManagedRenamePrefixContext(toolchain, processes)
    private data class Commands(val database: SourceFile, val raw: JsonArray, val standards: Map<Path, String>,
        val includes: Map<Path, List<Path>>)
    private data class Model(val plan: PatchPlan, val staged: ProjectSnapshot, val commands: Commands,
        val roots: CMakeIncludeRootFacts, val graph: CQuotedIncludeFacts, val moves: Map<Path, Path>, val from: Path,
        val to: Path, val prefixLease: OperationAuthorityLease?, val forwarding: List<Path>)

    fun preview(before: ProjectSnapshot, raw: PatchPlan, from: Path, to: Path,
        mapping: Map<String, String> = emptyMap(), forwarding: List<Path> = emptyList()): CMakeIncludePreview = try {
        CMakeIncludePreview.Available(authorize(before, model(before, raw.operation, from, to, raw.id, mapping, forwarding)))
    } catch (_: OutsideScope) { CMakeIncludePreview.OutsideScope }
      catch (failure: Exception) { CMakeIncludePreview.Unavailable(Diagnostic(
          "Include-aware move authority unavailable: ${failure.message}", Diagnostic.Severity.WARNING,
          code = "c.cmakeInclude.authorityUnavailable", evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY)) }

    fun gate(before: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate = try {
        val lease = requireNotNull(plan.authorityLease)
        require(lease.kind == LEASE_KIND && lease.operation == plan.operation && lease.snapshotHash == before.hash)
        val from = Path.of(requireNotNull(lease.attributes["c.cmakeInclude.from"]))
        val to = Path.of(requireNotNull(lease.attributes["c.cmakeInclude.to"]))
        val mapping = lease.attributes["c.cmakeInclude.prefixMapping"]?.let(prefix::decodeMapping).orEmpty()
        val forwarding = lease.attributes["c.cmakeInclude.forwardHeaders"]?.split(',')?.map(Path::of).orEmpty()
        val model = model(before, plan.operation, from, to, plan.id, mapping, forwarding)
        val verified = authorize(before, model)
        require(verified.workspaceEdit == plan.workspaceEdit && verified.authorityLease == lease) {
            "Retained include graph, target roots, compilation model or edit identity differs"
        }
        DiagnosticsGate.enabled(GATE_ID) { candidate ->
            require(candidate.workspace.root == before.workspace.root && candidate.copy().hash == candidate.hash &&
                candidate.hash in setOf(before.hash, model.staged.hash)) { "Candidate differs from approved before/staged images" }
            val live = if (runCatching { currency.requireCurrent(before) }.isSuccess) before else candidate
            verify(live)
            val observed = evaluate(candidate, model, candidate.hash == before.hash)
            verify(live)
            observed
        }
    } catch (failure: Exception) { DiagnosticsGate.enabled(GATE_ID) { error("c.cmakeInclude.authorityInvalid: ${failure.message}") } }

    private fun model(before: ProjectSnapshot, operation: String, from: Path, to: Path, id: PlanId,
        mapping: Map<String, String>, forwarding: List<Path>): Model {
        require(operation in setOf("moveSource", "relocateComponent") && from != to)
        require(mapping.isEmpty() || operation == "relocateComponent")
        require(listOf(from, to).all { !it.isAbsolute && it == it.normalize() && !it.startsWith("..") && it.toString().isNotEmpty() })
        verify(before)
        val moves = if (operation == "moveSource") mapOf(from to to) else before.files.filter { it.path.startsWith(from) }
            .associate { it.path to to.resolve(from.relativize(it.path)).normalize() }
        require(moves.isNotEmpty() && moves.values.toSet().size == moves.size && moves.all { (old, next) ->
            before.files.any { it.path == old } && before.files.none { it.path == next } &&
                old.fileName.toString().substringAfterLast('.') == next.fileName.toString().substringAfterLast('.')
        }) { "Unique captured source/header paths are required" }
        require(before.files.none { it.path.fileName.toString().endsWith(".generated.c") || it.path.fileName.toString().endsWith(".generated.h") })
        val binding = binding(before, operation, moves, from, to)
        if (binding.includeRoots.isEmpty()) throw OutsideScope()
        require(CHeaderFieldSourcePolicy.check(before.files.associate { it.path to it.content }) == CHeaderFieldSourceAdmission.Admitted)
        val commands = commands(before)
        val renames = moves.entries.sortedBy { it.key.toString() }.map { FileEdit.Rename(it.key, it.value) }
        val manifestEdit = FileEdit.Modify(Path.of("CMakeLists.txt"), binding.edits)
        val structural = WorkspaceEditSimulator.apply(before, WorkspaceEdit(renames + manifestEdit))
        val inverse = moves.entries.associate { it.value to it.key }
        val stagedBinding = binding(structural, operation, inverse, to, from)
        require(binding.target == stagedBinding.target && moves.all { (old, next) ->
            binding.sourceOwners[old] == stagedBinding.sourceOwners[next]
        }) { "Literal moved target ownership changed" }
        val units = commands.standards.keys
        require(stagedBinding.sourceOwners.keys.filter { it.toString().endsWith(".c") }.toSet() == units.map { moves[it] ?: it }.toSet())
        val rootMoves = if (operation == "relocateComponent") binding.includeRoots.map { it.path }.distinct()
            .filter { it.startsWith(from) }.associateWith { to.resolve(from.relativize(it)).normalize() } else emptyMap()
        val roots = (CMakeIncludeRootTransition.analyze(units, commands.includes,
            binding.sourceOwners.filterKeys { it in units }, binding.includeRoots, stagedBinding.includeRoots,
            moves.filterKeys { it in units }, rootMoves) as? CMakeIncludeRootReadout.Found)?.facts
            ?: error("Captured ordered compiler flags do not consume the exact literal target-root transition")
        val graph = (CQuotedIncludeClosure.analyze(before.files.associate { it.path to it.content }, roots.before,
            moves, roots.after) as? CQuotedIncludeReadout.Found)?.facts
            ?: error("Complete captured literal include graph cannot be preserved")
        require(moves.keys.filter { it.toString().endsWith(".h") }.all { file -> graph.before.any { it.target == file } }) {
            "Moved headers need actual captured consumers"
        }
        val includeEdits = graph.edits.groupBy { it.file }.entries.sortedBy { it.key.toString() }
            .map { FileEdit.Modify(it.key, it.value.map { edit -> edit.edit }) }
        val nextDatabase = rewriteDatabase(before, commands, moves, roots)
        val databaseEdits = if (nextDatabase == commands.database.content) emptyList() else listOf(FileEdit.Modify(commands.database.path,
            listOf(TextEdit(SourceRange(SourcePosition(0, 0), TextEdits.positionForOffset(commands.database.content, commands.database.content.length)), nextDatabase))))
        val prefixPreview = mapping.takeIf { it.isNotEmpty() }?.let {
            prefix.previewForRelocation(before, it.toSortedMap(), moves.keys).also { rename ->
                require(rename.status == PatchStatus.PREVIEW && rename.snapshotHash == before.hash &&
                    rename.evidence == RefactoringEvidence.COMPILER_PROVEN && rename.authorityLease != null) {
                    rename.summary
                }
            }
        }
        // Both plans use ranges from the same captured image. Move the paths first,
        // then normalize all original-coordinate text edits into one edit per file.
        val prefixEdits = prefixPreview?.workspaceEdit?.edits.orEmpty().map { edit ->
            require(edit is FileEdit.Modify) { "Component API mappings must produce only text edits" }
            val path = edit.path.normalize()
            edit.copy(path = moves[path] ?: path)
        }
        require(forwarding.isEmpty() || operation == "relocateComponent")
        require(forwarding.distinct().size == forwarding.size) { "Forwarding paths must be unique" }
        val forwardHeaders = forwarding.sortedBy(Path::toString)
        val forwardEdits = forwardHeaders.map { old ->
            require(old.toString().endsWith(".h") && old in moves) { "Forwarding requires an explicitly moved header" }
            val target = moves.getValue(old)
            val relative = (old.parent ?: Path.of("")).relativize(target)
            val spelling = relative.toString().replace('\\', '/')
            require(spelling.matches(Regex("[A-Za-z_0-9./-]{1,512}")) &&
                (old.parent ?: Path.of("")).resolve(relative).normalize() == target) {
                "Forwarding requires a safe literal spelling resolving to the moved header"
            }
            FileEdit.Create(old, "#include \"$spelling\"\n", overwrite = false)
        }
        val edit = WorkspaceEditSimulator.normalize(WorkspaceEdit(
            renames + includeEdits + prefixEdits + manifestEdit + databaseEdits + forwardEdits,
        ))
        val staged = WorkspaceEditSimulator.apply(before, edit)
        val plan = PatchPlan(id = id, operation = operation, snapshotHash = before.hash, confidence = 1.0,
            requiresUserApproval = true, summary = "Move ${moves.size} C source/header paths with captured target, include and compilation bindings" +
                (if (prefixPreview == null) "" else " and rename ${mapping.size} compiler-bound API symbol(s)") +
                (if (forwardHeaders.isEmpty()) "" else " and retain ${forwardHeaders.size} requested forwarding header(s)"),
            affectedFiles = edit.affectedFiles(), workspaceEdit = edit, riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.COMPILER_PROVEN,
            warnings = listOf("Captured local consumers are covered; external PUBLIC usage and ABI consumers are not inferred") +
                prefixPreview?.warnings.orEmpty())
        return Model(plan, staged, commands, roots, graph, moves, from, to, prefixPreview?.authorityLease, forwardHeaders)
    }

    private fun authorize(before: ProjectSnapshot, model: Model): PatchPlan {
        val old = evaluate(before, model, true)
        val next = evaluate(model.staged, model, false)
        verify(before)
        val binding = listOf(before.hash, model.staged.hash, model.from.toString(), model.to.toString()) +
            model.commands.standards.entries.sortedBy { it.key.toString() }.flatMap { listOf(it.key.toString(), it.value) } +
            model.roots.before.entries.flatMap { listOf(it.key.toString()) + it.value.map(Path::toString) } +
            model.roots.after.entries.flatMap { listOf(it.key.toString()) + it.value.map(Path::toString) } +
            model.roots.owners.entries.flatMap { listOf(it.key.toString(), it.value.toString()) } +
            model.graph.before.map { it.toString() } + model.graph.after.map { it.toString() }
        val lease = OperationAuthorityLease(kind = LEASE_KIND, operation = model.plan.operation, snapshotHash = before.hash,
            evidenceHash = model.staged.hash, workspaceEditSha256 = WorkspaceEditIdentity.sha256(model.plan.workspaceEdit),
            requiredFileEvidence = listOf(OperationAuthorityFileEvidence("c.cmakeInclude.compilationDatabase", model.commands.database.path,
                sha(model.commands.database.content.toByteArray(Charsets.UTF_8)))), attributes = mapOf(
                "c.cmakeInclude.from" to model.from.toString(), "c.cmakeInclude.to" to model.to.toString(),
                "c.cmakeInclude.translationUnitCount" to model.commands.standards.size.toString(),
                "c.cmakeInclude.includeEditCount" to model.graph.edits.size.toString(),
                "c.cmakeInclude.sourcePolicy" to "quoted-iso-v1", "c.cmakeInclude.bindingSha256" to sha(binding.joinToString("") {
                    "${it.toByteArray(Charsets.UTF_8).size}:$it" }.toByteArray(Charsets.UTF_8)),
                "c.nativeRuntimeIdentity" to requireNotNull(toolchain.provenance.nativeRuntimeIdentity),
                "c.toolchainIdentity" to toolchain.provenance.toolchainIdentity()) +
                model.prefixLease?.let {
                    mapOf(
                        "c.cmakeInclude.prefixMapping" to it.attributes.getValue("c.mapping"),
                        "c.cmakeInclude.prefixEditSha256" to requireNotNull(it.workspaceEditSha256),
                        "c.cmakeInclude.prefixStagedSha256" to it.evidenceHash,
                        "c.cmakeInclude.prefixClangdSha256" to it.attributes.getValue("c.clangdSha256"),
                    )
                }.orEmpty() + model.forwarding.takeIf { it.isNotEmpty() }?.let {
                    mapOf("c.cmakeInclude.forwardHeaders" to it.joinToString(","))
                }.orEmpty())
        return model.plan.copy(authorityLease = lease, diagnosticsBefore = old, diagnosticsAfterPreview = next)
    }

    private fun evaluate(candidate: ProjectSnapshot, model: Model, before: Boolean): List<Diagnostic> {
        require(candidate.hash == candidate.copy().hash)
        val results = model.commands.standards.entries.sortedBy { it.key.toString() }.flatMap { (old, standard) ->
            val file = if (before) old else model.moves[old] ?: old
            val roots = if (before) model.roots.before.getValue(file) else model.roots.after.getValue(file)
            syntaxDiagnostics(candidate, file, standard, roots)
        }
        if (before) return results
        return results + model.forwarding.flatMap { header ->
            // Compile the original include path too, using each distinct captured consumer configuration.
            val configurations = model.graph.before.filter { it.target == header }.map { observation ->
                val unit = observation.translationUnit
                model.commands.standards.getValue(unit) to model.roots.before.getValue(unit)
            }.distinct()
            require(configurations.isNotEmpty()) { "Forwarding needs a captured consumer configuration" }
            configurations.flatMap { (standard, roots) ->
                syntaxDiagnostics(candidate, header, standard, roots)
            }
        }
    }

    private fun syntaxDiagnostics(candidate: ProjectSnapshot, file: Path, standard: String, roots: List<Path>): List<Diagnostic> {
        val flags = listOf("-std=$standard", "-pedantic-errors") +
            roots.flatMap { listOf("-I", it.toString().ifEmpty { "." }) }
        val result = diagnostics.analyze(candidate, file, flags) as? CDiagnosticsResult.Available
            ?: error("Exact native current/staged syntax diagnostics are unavailable")
        require(result.processProvenance?.executableSha256 == toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256 &&
            result.diagnostics.none { it.severity == Diagnostic.Severity.ERROR }) { "Native current/staged context has compiler errors" }
        return result.diagnostics
    }

    private fun binding(snapshot: ProjectSnapshot, operation: String, moves: Map<Path, Path>, from: Path, to: Path): CMakeBindingDecision.Bound =
        SemanticWorkspaceOverlay.create(snapshot, maxFiles = 64, maxBytes = 16 * 1024 * 1024).use { overlay ->
            val captured = snapshot.copy(workspace = Workspace(overlay.root))
            val result = if (operation == "relocateComponent") CMakeLiteralBindings.relocate(captured, moves, from, to)
                else moves.entries.single().let { CMakeLiteralBindings.move(captured, it.key, it.value) }
            require(overlay.verifySourcesUnchanged().isEmpty())
            result as? CMakeBindingDecision.Bound ?: error("A unique literal C-only target/root binding is unavailable")
        }

    private fun commands(before: ProjectSnapshot): Commands {
        val root = before.workspace.root.toAbsolutePath().normalize()
        val database = before.auxiliaryFiles.filter { it.languageId == "c-compilation-database" }.single()
        require(database.path == Path.of("compile_commands.json") && database.content.length <= 1_048_576)
        var depth = 0; var quoted = false; var escaped = false
        database.content.forEach { c ->
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '[', '{' -> { depth++; require(depth <= 3) }; ']', '}' -> { depth--; require(depth >= 0) } }
        }
        require(depth == 0 && !quoted)
        val raw = Json.parseToJsonElement(database.content).jsonArray
        require(raw.size in 1..16 && raw.all { it.jsonObject.keys == setOf("directory", "file", "arguments") })
        val parsed = CCompilationDatabaseParser(CCompilationDatabasePolicy(requireArgumentsArray = true))
            .parse(root, database.content.toByteArray(Charsets.UTF_8)) as? CCompilationDatabaseDiscovery.Available
            ?: error("Explicit captured compiler arguments are unavailable")
        val units = before.files.filter { it.path.toString().endsWith(".c") }.map { root.resolve(it.path) }.toSet()
        require(parsed.database.units.size == units.size && parsed.database.units.map { it.file }.toSet() == units)
        val standards = linkedMapOf<Path, String>(); val includes = linkedMapOf<Path, List<Path>>()
        parsed.database.units.sortedBy { it.file.toString() }.forEach { unit ->
            require(unit.directory == root && unit.output == null && unit.target == null && unit.sysroot == null &&
                Path.of(unit.arguments.first()).toAbsolutePath().normalize() == toolchain.clangExecutable.toAbsolutePath().normalize())
            var standard: String? = null; var sources = 0; var compile = 0; var index = 1
            val roots = mutableListOf<Path>()
            while (index < unit.arguments.size) {
                val arg = unit.arguments[index]
                when {
                    arg in setOf("-std=c11", "-std=c17") -> { require(standard == null); standard = arg.substringAfter('=') }
                    arg == "-c" -> compile++
                    arg == "-I" || arg.startsWith("-I") && arg.length > 2 -> {
                        val name = if (arg == "-I") unit.arguments[++index] else arg.substring(2)
                        val path = root.resolve(name).normalize(); require(path.startsWith(root)); roots += listOf(root.relativize(path))
                    }
                    root.resolve(arg).normalize() == unit.file -> sources++
                    else -> error("Unqualified compiler flag; project commands are never executed")
                }
                index++
            }
            require(standard != null && sources == 1 && compile == 1)
            val source = root.relativize(unit.file)
            standards[source] = requireNotNull(standard); includes[source] = roots.toList()
        }
        return Commands(database, raw, standards, includes)
    }

    private fun rewriteDatabase(before: ProjectSnapshot, commands: Commands, moves: Map<Path, Path>, roots: CMakeIncludeRootFacts): String {
        val root = before.workspace.root.toAbsolutePath().normalize()
        fun spelling(old: String, path: Path): String = when {
            root.resolve(old).normalize() == root.resolve(path).normalize() -> old
            Path.of(old).isAbsolute -> root.resolve(path).toString()
            else -> path.toString().ifEmpty { "." }
        }
        val next = JsonArray(commands.raw.map { row ->
            val unit = row.jsonObject; val oldText = unit.getValue("file").jsonPrimitive.content
            val old = root.relativize(root.resolve(oldText).normalize()); val source = moves[old] ?: old
            val original = unit.getValue("arguments").jsonArray.map { it.jsonPrimitive.content }
            val arguments = original.toMutableList(); var index = 1; var include = 0
            while (index < arguments.size) {
                val arg = arguments[index]
                when {
                    arg == "-I" -> { index++; arguments[index] = spelling(original[index], roots.after.getValue(source)[include++]) }
                    arg.startsWith("-I") && arg.length > 2 -> arguments[index] = "-I" + spelling(arg.substring(2), roots.after.getValue(source)[include++])
                    root.resolve(arg).normalize() == root.resolve(old) -> arguments[index] = spelling(arg, source)
                }
                index++
            }
            JsonObject(unit + ("file" to JsonPrimitive(spelling(oldText, source))) +
                ("arguments" to JsonArray(arguments.map(::JsonPrimitive))))
        })
        return if (next == commands.raw) commands.database.content else next.toString()
    }

    private fun verify(snapshot: ProjectSnapshot) {
        currency.requireCurrent(snapshot)
        require(!CMakeManifestCapture.hasUncapturedCMakeAuthority(snapshot)) { "Uncaptured nested CMake authority" }
        require(toolchain.provenance.clangVersion == "22.1.8" && CCompilationSnapshotCapture.compilerIsCurrent(toolchain))
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class OutsideScope : RuntimeException()
    companion object {
        const val LEASE_KIND = "c.cmake-include-relocation-v1"
        private const val GATE_ID = "clang-cmake-includes-v1"
    }
}
