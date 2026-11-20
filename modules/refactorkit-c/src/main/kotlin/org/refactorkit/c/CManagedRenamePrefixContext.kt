package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.OperationAuthorityFileEvidence
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Retains a compiler-bound C API-family rename through managed Apply and rollback verification. */
internal class CManagedRenamePrefixContext(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager,
) {
    private val diagnostics = CCompilerDiagnostics(toolchain, processManager)

    /** Refuses unsupported captured compiler inputs before the LSP starts clangd. */
    fun preflight(snapshot: ProjectSnapshot, mapping: Map<String, String>): PatchPlan? {
        val plan = PatchPlan(
            operation = OPERATION,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "Managed C API prefix rename preflight",
            affectedFiles = emptySet(),
            workspaceEdit = WorkspaceEdit(),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.COMPILER_PROVEN,
        )
        return try {
            validateMapping(mapping)
            requireManagedPrefixSnapshot(snapshot, plan)
            compilationDatabase(snapshot)
            currentRuntimeIdentity()
            null
        } catch (failure: Exception) {
            refusedPreview(plan, failure.message ?: "Managed prefix-rename authority is unavailable")
        }
    }

    fun preview(before: ProjectSnapshot, plan: PatchPlan, mapping: Map<String, String>): PatchPlan {
        if (plan.status != PatchStatus.PREVIEW) return plan
        return try {
            validateMapping(mapping)
            requireManagedPrefixSnapshot(before, plan)
            val database = compilationDatabase(before)
            val runtimeIdentity = currentRuntimeIdentity()
            val baselineDiagnostics = evaluateCompilation(before, database)
            val staged = WorkspaceEditSimulator.apply(before, plan.workspaceEdit)
            val stagedDiagnostics = evaluateCompilation(staged, database)
            val editIdentity = WorkspaceEditIdentity.sha256(plan.workspaceEdit)
            val lease = OperationAuthorityLease(
                kind = LEASE_KIND,
                operation = OPERATION,
                snapshotHash = before.hash,
                evidenceHash = staged.hash,
                workspaceEditSha256 = editIdentity,
                requiredFileEvidence = listOf(OperationAuthorityFileEvidence(
                    kind = "c.renamePrefix.compilationDatabase",
                    path = database.path,
                    expectedContentSha256 = database.sha256,
                    attributes = mapOf("c.renamePrefix.compilationDatabase" to database.path.toString()),
                )),
                attributes = mapOf(
                    "c.mapping" to encodeMapping(mapping),
                    "c.compilationDatabasePath" to database.path.toString(),
                    "c.compilationDatabaseSha256" to database.sha256,
                    "c.nativeRuntimeIdentity" to runtimeIdentity,
                    "c.toolchainIdentity" to toolchain.provenance.toolchainIdentity(),
                    "c.clangdSha256" to toolchain.provenance.evidence.single { it.role == "clangd-executable" }.sha256,
                ),
            )
            plan.copy(
                diagnosticsBefore = baselineDiagnostics,
                diagnosticsAfterPreview = stagedDiagnostics,
                authorityLease = lease,
            )
        } catch (failure: Exception) {
            refusedPreview(plan, failure.message ?: "Managed prefix-rename authority is unavailable")
        }
    }

    fun gate(before: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate {
        val lease = plan.authorityLease
        val mapping = try {
            require(plan.operation == OPERATION && plan.status == PatchStatus.PREVIEW && plan.requiresUserApproval &&
                plan.evidence == RefactoringEvidence.COMPILER_PROVEN && plan.snapshotHash == before.hash &&
                before.copy().hash == before.hash && lease?.kind == LEASE_KIND && lease.operation == OPERATION &&
                lease.snapshotHash == before.hash && lease.workspaceEditSha256 == WorkspaceEditIdentity.sha256(plan.workspaceEdit)) {
                "c.renamePrefix.authorityInvalid: preview, explicit approval and exact snapshot-bound edit are required"
            }
            decodeMapping(requireNotNull(lease.attributes["c.mapping"]))
        } catch (failure: Exception) {
            return refusedGate(failure.message ?: "c.renamePrefix.authorityInvalid: malformed authority lease")
        }
        val capturedDatabase = try {
            requireManagedPrefixSnapshot(before, plan)
            compilationDatabase(before)
        } catch (failure: Exception) {
            return refusedGate(failure.message ?: "c.renamePrefix.authorityInvalid: captured compiler configuration is unavailable")
        }
        val runtimeIdentity = currentRuntimeIdentityOrNull()
            ?: return refusedGate("c.renamePrefix.nativeRuntimeChanged: configured Clang runtime is not current")
        if (lease.attributes["c.nativeRuntimeIdentity"] != runtimeIdentity ||
            lease.attributes["c.toolchainIdentity"] != toolchain.provenance.toolchainIdentity() ||
            lease.attributes["c.clangdSha256"] != toolchain.provenance.evidence.singleOrNull { it.role == "clangd-executable" }?.sha256 ||
            lease.attributes["c.compilationDatabasePath"] != capturedDatabase.path.toString() ||
            lease.attributes["c.compilationDatabaseSha256"] != capturedDatabase.sha256) {
            return refusedGate("c.renamePrefix.authorityChanged: compiler, compilation database or mapping differs from the preview")
        }
        val staged = try { WorkspaceEditSimulator.apply(before, plan.workspaceEdit) }
        catch (failure: Exception) { return refusedGate("c.renamePrefix.authorityInvalid: cannot reconstruct staged image: ${failure.message}") }
        if (staged.hash != lease.evidenceHash) {
            return refusedGate("c.renamePrefix.authorityInvalid: staged image differs from the approved preview")
        }
        val rebuilt = try { semanticPreview(before, mapping) }
        catch (failure: Exception) { return refusedGate("c.renamePrefix.semanticUnavailable: ${failure.message}") }
        if (rebuilt.status != PatchStatus.PREVIEW || rebuilt.workspaceEdit != plan.workspaceEdit) {
            return refusedGate("c.renamePrefix.authorityChanged: compiler-bound occurrence family no longer reproduces the preview")
        }

        val root = before.workspace.root.toAbsolutePath().normalize()
        return DiagnosticsGate.enabled(GATE_ID) { candidate ->
            require(candidate.workspace.root.toAbsolutePath().normalize() == root && candidate.copy().hash == candidate.hash) {
                "c.renamePrefix.authorityInvalid: candidate workspace or immutable content identity differs"
            }
            requireQualifiedConsumers(candidate)
            require(currentRuntimeIdentityOrNull() == runtimeIdentity) {
                "c.renamePrefix.nativeRuntimeChanged: retained native toolchain changed during managed transaction"
            }
            val currentDatabase = compilationDatabase(candidate)
            require(currentDatabase == capturedDatabase) {
                "c.renamePrefix.compilationDatabaseChanged: compilation database differs from the approved preview"
            }
            when (candidate.hash) {
                before.hash, lease.evidenceHash -> evaluateCompilation(candidate, capturedDatabase)
                else -> error("c.renamePrefix.authorityInvalid: candidate differs from both approved source images")
            }
        }
    }

    fun previewForRelocation(before: ProjectSnapshot, mapping: Map<String, String>, movedPaths: Set<Path>): PatchPlan {
        preflight(before, mapping)?.let { return it }
        require(mapping.values.none { target ->
            before.files.any { source ->
                CTargetNameCollisionPolicy.hasPotentialConflict(source.languageId, source.content, target)
            }
        }) { "A component API rename target already occurs in the captured C sources" }
        val definitions = movedPaths.mapTo(mutableSetOf()) { path ->
            before.workspace.root.resolve(path).toAbsolutePath().normalize()
        }
        return preview(before, semanticPreview(before, mapping, definitions), mapping)
    }

    private fun semanticPreview(snapshot: ProjectSnapshot, mapping: Map<String, String>,
        definitionPaths: Set<Path>? = null): PatchPlan =
        CRenamePrefixPlanner(toolchain, processManager).use { planner ->
            planner.start(snapshot)
            planner.preview(snapshot, mapping, definitionPaths)
        }

    private fun requireManagedPrefixSnapshot(snapshot: ProjectSnapshot, plan: PatchPlan) {
        require(plan.operation == OPERATION && plan.status == PatchStatus.PREVIEW && plan.requiresUserApproval &&
            plan.snapshotHash == snapshot.hash && plan.evidence == RefactoringEvidence.COMPILER_PROVEN) {
            "c.renamePrefix.authorityUnavailable: only compiler-proven, snapshot-bound C API previews are admitted"
        }
        requireQualifiedConsumers(snapshot)
        require(snapshot.files.isNotEmpty() && snapshot.files.all { source ->
            !source.path.isAbsolute && !source.path.normalize().startsWith("..") &&
                source.languageId in C_FAMILY_LANGUAGES
        }) {
            "c.renamePrefix.authorityUnavailable: managed API rename requires a captured C-family source snapshot"
        }
    }

    private fun requireQualifiedConsumers(snapshot: ProjectSnapshot) {
        val mixedLanguages = setOf("cpp", "objective-c")
        require(snapshot.files.none { it.languageId in mixedLanguages } &&
            CSourceSnapshotReader(CProjectScanPolicy()).capture(snapshot.workspace.root.toAbsolutePath().normalize())
                .sources.none { it.languageId in mixedLanguages }) {
            "c.renamePrefix.unsupportedConsumer: C++ and Objective-C consumers require separately qualified compiler and consumer contexts"
        }
    }

    private fun compilationDatabase(snapshot: ProjectSnapshot): CompilationDatabaseIdentity {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        require(isWorkspaceDirectoryWithoutSymlinks(root, root)) {
            "c.renamePrefix.compilationContextUnavailable: workspace root is not a non-symlink directory"
        }
        val relativePaths = listOf(Path.of("compile_commands.json"), Path.of("build/compile_commands.json"))
        val sourceFiles = snapshot.auxiliaryFiles.filter { it.path.normalize() in relativePaths }
        require(sourceFiles.size == 1 && sourceFiles.single().languageId == "c-compilation-database") {
            "c.renamePrefix.compilationContextUnavailable: exactly one captured compilation database is required"
        }
        val selected = sourceFiles.single()
        val relative = selected.path.normalize()
        val alternate = relativePaths.single { it != relative }
        val selectedPath = root.resolve(relative)
        if (relative == Path.of("build/compile_commands.json")) {
            require(isWorkspaceDirectoryWithoutSymlinks(root, root.resolve("build"))) {
                "c.renamePrefix.compilationContextUnavailable: build directory is absent or indirect"
            }
        }
        val alternatePath = root.resolve(alternate)
        require(!Files.exists(alternatePath, LinkOption.NOFOLLOW_LINKS)) {
            "c.renamePrefix.compilationContextUnavailable: multiple compilation databases require explicit selection"
        }
        require(Files.isRegularFile(selectedPath, LinkOption.NOFOLLOW_LINKS)) {
            "c.renamePrefix.compilationDatabaseChanged: selected compilation database is missing or indirect"
        }
        val bytes = Files.newInputStream(selectedPath, LinkOption.NOFOLLOW_LINKS).use {
            it.readNBytes(MAX_DATABASE_BYTES + 1)
        }
        require(bytes.size in 1..MAX_DATABASE_BYTES && bytes.contentEquals(selected.content.toByteArray(Charsets.UTF_8))) {
            "c.renamePrefix.compilationDatabaseChanged: live compilation database differs from the captured snapshot"
        }
        val discovery = CCompilationDatabaseParser(CCompilationDatabasePolicy(requireArgumentsArray = true)).parse(root, bytes)
        val database = (discovery as? CCompilationDatabaseDiscovery.Available)?.database
            ?: error("c.renamePrefix.compilationContextUnavailable: ${
                (discovery as CCompilationDatabaseDiscovery.Refused).diagnostics.joinToString("; ") { it.message }
            }")
        val translationUnits = snapshot.files.filter { source ->
            source.languageId == "c" && source.path.fileName.toString().substringAfterLast('.', "").lowercase() == "c"
        }.map { root.resolve(it.path.normalize()).normalize() }.toSet()
        require(translationUnits.isNotEmpty() && database.units.map { it.file }.toSet() == translationUnits &&
            database.units.all { it.standard in setOf("c11", "c17") }) {
            "c.renamePrefix.compilationContextUnavailable: explicit C11/C17 commands must cover every captured C translation unit exactly once"
        }
        val contexts = database.units.sortedBy { it.file.toString() }.associate { unit ->
            unit.file to sanitizeArguments(unit.arguments, unit.file, unit.directory, root)
        }
        return CompilationDatabaseIdentity(relative, sha256(bytes), contexts)
    }

    private fun sanitizeArguments(arguments: List<String>, source: Path, directory: Path, root: Path): List<String> {
        val compiler = toolchain.clangExecutable.toAbsolutePath().normalize()
        require(arguments.isNotEmpty() && runCatching { Path.of(arguments.first()).toAbsolutePath().normalize() }.getOrNull() == compiler) {
            "c.renamePrefix.compilationCommandUnsupported: command compiler differs from the selected Clang executable"
        }
        val flags = mutableListOf<String>()
        var standard: String? = null
        var compileCount = 0
        var sourceCount = 0
        var index = 1
        val macro = Regex("[A-Za-z_][A-Za-z_0-9]*(=([A-Za-z_][A-Za-z_0-9]*|[0-9]+))?")
        while (index < arguments.size) {
            val argument = arguments[index]
            when {
                argument.startsWith("-std=") -> {
                    require(standard == null && argument in setOf("-std=c11", "-std=c17")) {
                        "c.renamePrefix.compilationCommandUnsupported: exactly one C11/C17 dialect is required"
                    }
                    standard = argument
                }
                argument == "-c" -> compileCount++
                argument == "-o" -> {
                    require(arguments.getOrNull(index + 1) != null) {
                        "c.renamePrefix.compilationCommandUnsupported: output flag has no operand"
                    }
                    index++
                }
                argument == "-D" || argument == "-U" -> {
                    val value = arguments.getOrNull(index + 1)
                    require(value != null && (if (argument == "-D") macro.matches(value) else C_IDENTIFIER.matches(value))) {
                        "c.renamePrefix.compilationCommandUnsupported: unsafe macro operand"
                    }
                    flags += argument + value
                    index++
                }
                argument.startsWith("-D") && macro.matches(argument.substring(2)) -> flags += argument
                argument.startsWith("-U") && C_IDENTIFIER.matches(argument.substring(2)) -> flags += argument
                argument in setOf("-I", "-iquote", "-isystem") -> {
                    val value = arguments.getOrNull(index + 1)
                        ?: error("c.renamePrefix.compilationCommandUnsupported: include flag has no operand")
                    flags += argument
                    flags += overlayRelativeInclude(value, directory, root)
                    index++
                }
                argument.startsWith("-I") && argument.length > 2 -> {
                    flags += "-I" + overlayRelativeInclude(argument.substring(2), directory, root)
                }
                argument in setOf("-Wall", "-Wextra", "-Werror", "-pedantic", "-pedantic-errors") -> flags += argument
                else -> {
                    val path = runCatching { Path.of(argument) }.getOrNull()
                    val resolved = path?.let { if (it.isAbsolute) it.normalize() else directory.resolve(it).normalize() }
                    if (resolved == source) sourceCount++
                    else error("c.renamePrefix.compilationCommandUnsupported: unexpected compiler argument; build commands are data and are never executed")
                }
            }
            index++
        }
        require(standard != null && compileCount == 1 && sourceCount == 1 && flags.size <= MAX_COMPILER_FLAGS) {
            "c.renamePrefix.compilationCommandUnsupported: one explicit C11/C17 source command and bounded safe flags are required"
        }
        return listOf(standard!!) + flags
    }

    private fun overlayRelativeInclude(raw: String, directory: Path, root: Path): String {
        val requested = runCatching { Path.of(raw) }.getOrElse {
            error("c.renamePrefix.compilationCommandUnsupported: invalid include directory")
        }
        val absolute = (if (requested.isAbsolute) requested else directory.resolve(requested)).normalize()
        require(isWorkspaceDirectoryWithoutSymlinks(root, absolute)) {
            "c.renamePrefix.compilationCommandUnsupported: include directory must be a workspace directory without symlink components"
        }
        val relative = root.relativize(absolute).normalize()
        return relative.takeIf { it.toString().isNotEmpty() }?.toString() ?: "."
    }

    private fun isWorkspaceDirectoryWithoutSymlinks(root: Path, target: Path): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedTarget = target.toAbsolutePath().normalize()
        if (!normalizedTarget.startsWith(normalizedRoot) || !hasNoSymlinkComponents(normalizedRoot) ||
            !Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) return false
        var current = normalizedRoot
        for (component in normalizedRoot.relativize(normalizedTarget)) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) return false
        }
        return true
    }

    private fun hasNoSymlinkComponents(path: Path): Boolean {
        if (!path.isAbsolute) return false
        var current = path.root ?: return false
        for (component in path) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) return false
        }
        return true
    }

    private fun evaluateCompilation(snapshot: ProjectSnapshot, database: CompilationDatabaseIdentity): List<Diagnostic> {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val expectedCompiler = toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256
        return database.contexts.entries.sortedBy { it.key.toString() }.flatMap { (file, flags) ->
            val relative = root.relativize(file)
            val result = diagnostics.analyze(snapshot, relative, flags)
            val available = result as? CDiagnosticsResult.Available ?: run {
                val unavailable = (result as CDiagnosticsResult.Unavailable).diagnostic
                error("${unavailable.code}: ${unavailable.message}")
            }
            require(available.processProvenance?.executableSha256 == expectedCompiler) {
                "c.renamePrefix.compilerChanged: syntax diagnostics did not retain the selected Clang executable"
            }
            val errors = available.diagnostics.filter { it.severity == Diagnostic.Severity.ERROR }
            require(errors.isEmpty()) {
                "c.renamePrefix.compilerDiagnostics: ${file.fileName} has ${errors.size} compiler error(s): ${errors.joinToString("; ") { it.message }}"
            }
            available.diagnostics
        }
    }

    private fun currentRuntimeIdentity(): String = currentRuntimeIdentityOrNull()
        ?: error("c.renamePrefix.nativeRuntimeUnavailable: complete current Clang runtime identity is required")

    private fun currentRuntimeIdentityOrNull(): String? {
        val identity = toolchain.provenance.nativeRuntimeIdentity ?: return null
        if ((toolchain.runtime as? ClangToolchainRuntimeCaptureResult.Captured)?.facts?.identity != identity ||
            !CCompilationSnapshotCapture.compilerIsCurrent(toolchain)) return null
        return identity
    }

    private fun refusedPreview(plan: PatchPlan, message: String): PatchPlan {
        val code = when {
            message.startsWith("c.renamePrefix.") -> message.substringBefore(':')
            else -> "c.renamePrefix.authorityUnavailable"
        }
        val diagnostic = Diagnostic(
            message = message,
            severity = Diagnostic.Severity.ERROR,
            code = code,
            evidence = DiagnosticEvidence.STRUCTURAL,
            category = DiagnosticCategory.SAFETY,
        )
        return plan.copy(
            status = PatchStatus.REFUSED,
            confidence = 0.0,
            requiresUserApproval = false,
            summary = "Managed C API prefix rename refused: $message",
            affectedFiles = emptySet(),
            workspaceEdit = WorkspaceEdit(),
            authorityLease = null,
            diagnosticsAfterPreview = listOf(diagnostic),
            warnings = plan.warnings + message,
            refusalCode = code,
        )
    }

    private fun refusedGate(message: String) = DiagnosticsGate.enabled(GATE_ID) {
        error(message)
    }

    private fun validateMapping(mapping: Map<String, String>) {
        require(mapping.size in 1..MAX_SYMBOLS && mapping.all { (oldName, newName) ->
            C_IDENTIFIER.matches(oldName) && C_IDENTIFIER.matches(newName) && oldName != newName
        }) { "c.renamePrefix.invalidMapping: bounded C identifier mappings are required" }
        require(mapping.values.distinct().size == mapping.size && mapping.keys.intersect(mapping.values.toSet()).isEmpty()) {
            "c.renamePrefix.nonInvertibleMapping: managed mappings require distinct, non-overlapping source and destination names"
        }
    }

    private fun encodeMapping(mapping: Map<String, String>): String = mapping.toSortedMap()
        .entries.joinToString(";") { (oldName, newName) -> "$oldName=$newName" }

    fun decodeMapping(encoded: String): Map<String, String> {
        require(encoded.length in 1..MAX_MAPPING_BYTES) { "c.renamePrefix.authorityInvalid: mapping payload exceeds its bound" }
        val mapping = linkedMapOf<String, String>()
        encoded.split(';').forEach { pair ->
            val parts = pair.split('=')
            require(parts.size == 2 && C_IDENTIFIER.matches(parts[0]) && C_IDENTIFIER.matches(parts[1]) &&
                mapping.put(parts[0], parts[1]) == null) {
                "c.renamePrefix.authorityInvalid: mapping payload is malformed"
            }
        }
        validateMapping(mapping)
        require(encodeMapping(mapping) == encoded) { "c.renamePrefix.authorityInvalid: mapping payload is not canonical" }
        return mapping
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private data class CompilationDatabaseIdentity(
        val path: Path,
        val sha256: String,
        val contexts: Map<Path, List<String>>,
    )

    private companion object {
        const val OPERATION = "renameCSymbolPrefix"
        const val LEASE_KIND = "c.rename-prefix-v1"
        const val GATE_ID = "clang-prefix-rename-v1"
        const val MAX_DATABASE_BYTES = 8 * 1024 * 1024
        const val MAX_SYMBOLS = 256
        const val MAX_MAPPING_BYTES = 32 * 1024
        const val MAX_COMPILER_FLAGS = 512
        val C_FAMILY_LANGUAGES = setOf("c", "cpp", "objective-c")
        val C_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
    }
}
