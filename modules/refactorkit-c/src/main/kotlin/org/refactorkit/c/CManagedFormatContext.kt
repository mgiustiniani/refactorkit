package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.OperationAuthorityFileEvidence
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Exact, non-executing compiler context retained for bounded C Apply operations. */
internal class CManagedFormatContext(
    private val toolchain: ClangSemanticToolchain,
    private val diagnostics: CCompilerDiagnostics,
    private val managedOperation: String = "formatFile",
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
    private val managedSymbol: String? = null,
) {
    private val isIncludeOrder get() = managedOperation == "organizeIncludes"
    private val isSafeDelete get() = managedOperation == "safeDelete"
    private val codePrefix get() = when {
        isIncludeOrder -> "c.includeOrder"
        isSafeDelete -> "c.safeDelete"
        else -> "c.format"
    }
    private val managedName get() = when {
        isIncludeOrder -> "include organization"
        isSafeDelete -> "safe delete"
        else -> "formatting"
    }

    fun preview(snapshot: ProjectSnapshot, plan: PatchPlan): PatchPlan {
        val captured = try {
            capture(snapshot, plan)
        } catch (failure: Exception) {
            if (isIncludeOrder) {
                val refusalCode = "${codePrefix}CompilationContextUnavailable"
                val reason = failure.message ?: "$managedName compiler context unavailable"
                val diagnostic = Diagnostic(
                    message = "$refusalCode: $reason",
                    severity = Diagnostic.Severity.ERROR,
                    code = refusalCode,
                    evidence = DiagnosticEvidence.STRUCTURAL,
                    category = DiagnosticCategory.SAFETY,
                )
                return plan.copy(
                    status = PatchStatus.REFUSED,
                    confidence = 0.0,
                    requiresUserApproval = false,
                    summary = diagnostic.message,
                    affectedFiles = emptySet(),
                    workspaceEdit = WorkspaceEdit(),
                    diagnosticsAfterPreview = listOf(diagnostic),
                    authorityLease = null,
                    refusalCode = refusalCode,
                    warnings = plan.warnings + "c.previewDiagnosticsUnavailable: $reason",
                )
            }
            return plan.copy(
                authorityLease = null,
                warnings = plan.warnings + "c.previewDiagnosticsUnavailable: ${failure.message ?: "$managedName compiler context unavailable"}",
            )
        }
        return try {
            val before = captured.evaluate(snapshot)
            val staged = captured.evaluate(WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit))
            val introducedErrors = introducedErrors(before, staged)
            if (introducedErrors.isNotEmpty()) {
                val diagnostic = Diagnostic(
                    "Captured C diagnostics found ${introducedErrors.size} new error(s) after $managedName",
                    Diagnostic.Severity.ERROR,
                    code = "${codePrefix}IntroducedDiagnostics",
                )
                return plan.copy(
                    status = PatchStatus.REFUSED,
                    confidence = 0.0,
                    requiresUserApproval = false,
                    summary = diagnostic.message,
                    affectedFiles = emptySet(),
                    workspaceEdit = WorkspaceEdit(),
                    diagnosticsBefore = before,
                    diagnosticsAfterPreview = staged,
                    authorityLease = null,
                    warnings = plan.warnings + diagnostic.message,
                    refusalCode = diagnostic.code,
                )
            }
            plan.copy(
                diagnosticsBefore = before,
                diagnosticsAfterPreview = staged,
                authorityLease = captured.lease,
                warnings = plan.warnings + "c.previewDiagnosticsVerified: exact captured C compiler evaluated all ${captured.contexts.size} translation units before $managedName approval",
            )
        } catch (failure: Exception) {
            plan.copy(
                authorityLease = null,
                warnings = plan.warnings + "c.previewDiagnosticsUnavailable: ${failure.message ?: "$managedName preview diagnostics unavailable"}",
            )
        }
    }

    fun gate(snapshot: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate {
        val gateId = when {
            isIncludeOrder -> INCLUDE_ORDER_GATE_ID
            isSafeDelete -> SAFE_DELETE_GATE_ID
            else -> FORMAT_GATE_ID
        }
        val captured = try {
            capture(snapshot, plan)
        } catch (failure: Exception) {
            return DiagnosticsGate.enabled(gateId) {
                error("${codePrefix}CompilationContextUnavailable: ${failure.message ?: "$managedName compiler context cannot be captured"}")
            }
        }
        if (plan.authorityLease != captured.lease) {
            return DiagnosticsGate.enabled(gateId) {
                error("${codePrefix}AuthorityChanged: compiler, compilation database or staged edit differs from the approved preview")
            }
        }
        return DiagnosticsGate.enabled(gateId) { candidate -> captured.evaluate(candidate) }
    }

    private fun capture(snapshot: ProjectSnapshot, plan: PatchPlan): Captured {
        require(plan.operation == managedOperation && plan.status == PatchStatus.PREVIEW &&
            plan.requiresUserApproval && plan.snapshotHash == snapshot.hash) {
            "${codePrefix}CompilationContextUnavailable: only a snapshot-bound approved $managedName preview is admitted"
        }
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        require(snapshot.files.isNotEmpty() && snapshot.files.all { file ->
            val path = file.path.normalize()
            val name = path.fileName?.toString().orEmpty()
            file.languageId == "c" && !path.isAbsolute && !path.startsWith("..") &&
                (name.endsWith(".c") || name.endsWith(".h"))
        }) {
            "${codePrefix}CompilationContextUnavailable: managed $managedName diagnostics require a captured C-only source/header snapshot"
        }
        val modifications = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>()
        require(plan.workspaceEdit.edits.size == 1 && modifications.size == 1) {
            "${codePrefix}CompilationContextUnavailable: $managedName must modify exactly one captured source"
        }
        val source = modifications.single().path.normalize()
        require(!source.isAbsolute && !source.startsWith("..") && source.fileName.toString().endsWith(".c") &&
            snapshot.files.count { it.path.normalize() == source && it.languageId == "c" } == 1) {
            "${codePrefix}CompilationContextUnavailable: managed edit must target exactly one captured C translation unit"
        }
        if (isIncludeOrder) {
            val regenerated = COrganizeIncludesPlanner().preview(snapshot, source)
            require(regenerated.status == PatchStatus.PREVIEW && regenerated.workspaceEdit == plan.workspaceEdit) {
                "${codePrefix}PlanChanged: edit is not the exact admitted include-order preview for this snapshot"
            }
        } else if (isSafeDelete) {
            val symbol = requireNotNull(managedSymbol?.takeIf { it.matches(SAFE_DELETE_SYMBOL) }) {
                "c.safeDeleteCompilationContextUnavailable: exact safe-delete symbol is not retained"
            }
            val regenerated = CSafeDeletePlanner(toolchain, processManager).use { planner ->
                planner.start(snapshot)
                planner.preview(snapshot, symbol)
            }
            require(regenerated.status == PatchStatus.PREVIEW && regenerated.workspaceEdit == plan.workspaceEdit) {
                "c.safeDeletePlanChanged: semantic safe-delete evidence no longer reproduces the approved edit"
            }
        } else {
            require(snapshot.files.all { file ->
                !Regex("(?m)^\\s*#").containsMatchIn(file.content)
            }) {
                "${codePrefix}CompilationContextUnavailable: preprocessor directives require a separately qualified captured-header context"
            }
        }
        val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
        val originalAuxiliary = snapshot.auxiliaryFiles.associateBy { it.path.normalize() }
        require(staged.files.size == snapshot.files.size && staged.files.all { candidate ->
            val original = snapshot.files.singleOrNull { it.path.normalize() == candidate.path.normalize() }
            original != null && (candidate.path.normalize() == source || original.content == candidate.content)
        } && staged.auxiliaryFiles.size == originalAuxiliary.size && staged.auxiliaryFiles.all { candidate ->
            originalAuxiliary[candidate.path.normalize()] == candidate
        }) {
            "${codePrefix}CompilationContextUnavailable: staged edit changes content outside the selected C source"
        }

        val dbRelative = Path.of("compile_commands.json")
        require(!Files.exists(root.resolve("build/compile_commands.json"), LinkOption.NOFOLLOW_LINKS)) {
            "${codePrefix}CompilationContextUnavailable: only one root compilation database is admitted"
        }
        val databaseFile = snapshot.auxiliaryFiles.singleOrNull {
            it.path.normalize() == dbRelative
        } ?: error("${codePrefix}CompilationContextUnavailable: root compile_commands.json is not captured")
        val databasePath = root.resolve(dbRelative)
        val databaseBytes = databaseFile.content.toByteArray(Charsets.UTF_8)
        require(databaseBytes.size in 1..MAX_DATABASE_BYTES && readBounded(databasePath, MAX_DATABASE_BYTES)?.contentEquals(databaseBytes) == true) {
            "${codePrefix}CompilationDatabaseChanged: captured root compile_commands.json is missing, indirect or changed"
        }
        val discovery = CCompilationDatabaseParser(CCompilationDatabasePolicy(
            maxDatabaseBytes = MAX_DATABASE_BYTES.toLong(),
            maxUnits = MAX_MANAGED_TUS,
            requireArgumentsArray = true,
        )).parse(root, databaseBytes)
        val database = (discovery as? CCompilationDatabaseDiscovery.Available)?.database
            ?: error("${codePrefix}CompilationContextUnavailable: ${((discovery as? CCompilationDatabaseDiscovery.Refused)?.diagnostics).orEmpty().joinToString { it.code.orEmpty() }}")
        val translationUnits = snapshot.files.filter { it.path.fileName.toString().endsWith(".c") }
            .map { root.resolve(it.path.normalize()).normalize() }.toSet()
        if (isIncludeOrder) {
            require(translationUnits.size in 1..MAX_MANAGED_TUS && database.units.size == translationUnits.size) {
                "${codePrefix}CompilationContextUnavailable: include ordering requires every captured C translation unit, up to $MAX_MANAGED_TUS"
            }
        }
        require(translationUnits.isNotEmpty() && database.units.size == translationUnits.size &&
            database.units.map { it.file }.toSet() == translationUnits) {
            "${codePrefix}CompilationContextUnavailable: compile database must cover every captured C translation unit exactly once"
        }
        require(database.units.all { unit -> unit.directory == root && unit.output == null &&
            unit.includeDirectories.isEmpty() && unit.target == null && unit.sysroot == null }) {
            "${codePrefix}CompilationContextUnavailable: only root-directory literal commands without output, include, target or sysroot flags are admitted"
        }
        val clangPath = toolchain.clangExecutable.toAbsolutePath().normalize()
        val clangIdentity = toolchain.provenance.evidence.singleOrNull { it.role == "clang-executable" }
        val runtime = toolchain.runtime as? ClangToolchainRuntimeCaptureResult.Captured
        val runtimeIdentity = toolchain.provenance.nativeRuntimeIdentity
        require(toolchain.provenance.clangVersion == "22.1.8" &&
            clangIdentity != null && clangIdentity.path.toAbsolutePath().normalize() == clangPath &&
            clangIdentity.sha256 == hashFile(clangPath) && runtime != null &&
            runtime.facts.identity == runtimeIdentity &&
            ClangToolchainRuntimeCapture().checkCurrent(toolchain) == ClangToolchainRuntimeCurrency.Current) {
            "${codePrefix}CompilerChanged: configured Clang 22.1.8 runtime identity is unavailable or changed"
        }

        if (isIncludeOrder) {
            require(database.units.all { unit ->
                val defineNames = unit.defines.map { it.substringBefore('=') }
                unit.standard in INCLUDE_ORDER_STANDARDS && unit.defines.size <= MAX_DEFINES &&
                    unit.defines.all(INCLUDE_ORDER_DEFINE::matches) && defineNames.distinct().size == defineNames.size
            }) {
                "${codePrefix}CompilationContextUnavailable: include ordering requires C11/C17 commands with distinct literal numeric macro definitions only"
            }
        }

        val sourceAbsolute = root.resolve(source).normalize()
        val style = when {
            isIncludeOrder -> StyleCapture(null, sha256("include-order-no-format-style".toByteArray(Charsets.UTF_8)), null)
            isSafeDelete -> StyleCapture(null, sha256("safe-delete-no-format-style".toByteArray(Charsets.UTF_8)), null)
            else -> captureStyle(snapshot, sourceAbsolute)
        }
        val contexts = database.units.sortedBy { it.file.toString() }.associate { unit ->
            unit.file to sanitize(unit.arguments, unit.file, root, clangPath)
        }
        if (isIncludeOrder) {
            val expectedContexts = database.units.associate { unit ->
                unit.file to (listOf("-std=${unit.standard}") + unit.defines.map { "-D$it" })
            }
            require(contexts == expectedContexts) {
                "${codePrefix}CompilationContextUnavailable: include ordering admits only each translation unit's exact captured C11/C17 standard and literal macro flags"
            }
        }
        val databaseHash = sha256(databaseBytes)
        val stagedHash = staged.hash
        val editHash = WorkspaceEditIdentity.sha256(plan.workspaceEdit)
        val evidenceHash = sha256(listOf(
            policyId, snapshot.hash, stagedHash, editHash, databaseHash, clangIdentity.sha256,
            runtimeIdentity.orEmpty(), source.toString(), style.identityHash, contexts.size.toString(),
            managedSymbol.orEmpty(),
        ).joinToString("|").toByteArray(Charsets.UTF_8))
        val requiredFiles = buildList {
            add(OperationAuthorityFileEvidence(
                "${leaseAttributePrefix}CompilationDatabase", dbRelative, databaseHash,
            ))
            style.fileEvidence?.let(::add)
        }
        val lease = OperationAuthorityLease(
            kind = when {
                isIncludeOrder -> INCLUDE_ORDER_LEASE_KIND
                isSafeDelete -> SAFE_DELETE_LEASE_KIND
                else -> FORMAT_LEASE_KIND
            },
            operation = plan.operation,
            snapshotHash = snapshot.hash,
            evidenceHash = evidenceHash,
            workspaceEditSha256 = editHash,
            requiredFileEvidence = requiredFiles,
            attributes = mapOf(
                "${leaseAttributePrefix}Source" to source.toString(),
                "${leaseAttributePrefix}StagedSnapshotSha256" to stagedHash,
                "${leaseAttributePrefix}CompilationDatabaseSha256" to databaseHash,
                "${leaseAttributePrefix}CompilerSha256" to clangIdentity.sha256,
                "${leaseAttributePrefix}NativeRuntimeIdentity" to runtimeIdentity.orEmpty(),
                "${leaseAttributePrefix}StyleIdentitySha256" to style.identityHash,
                "${leaseAttributePrefix}TranslationUnitCount" to contexts.size.toString(),
                "${leaseAttributePrefix}DiagnosticsPolicy" to policyId,
                *if (isSafeDelete) arrayOf("c.safeDelete.Symbol" to requireNotNull(managedSymbol)) else emptyArray(),
            ),
        )
        return Captured(
            root, snapshot.hash, stagedHash, source, databasePath, databaseHash,
            clangPath, clangIdentity.sha256, runtimeIdentity.orEmpty(), style, contexts, lease,
        )
    }

    private fun sanitize(arguments: List<String>, source: Path, root: Path, clang: Path): List<String> {
        require(arguments.isNotEmpty() && runCatching { Path.of(arguments[0]).toAbsolutePath().normalize() }
            .getOrNull() == clang) {
            "${codePrefix}CompilationCommandUnsupported: command compiler differs from configured Clang"
        }
        val flags = mutableListOf<String>()
        var standard: String? = null
        var compileCount = 0
        var sourceCount = 0
        var index = 1
        val define = Regex("[A-Za-z_][A-Za-z_0-9]*(=([A-Za-z_][A-Za-z_0-9]*|[0-9]+))?")
        while (index < arguments.size) {
            val argument = arguments[index]
            when {
                argument == "-std=c11" || argument == "-std=c17" -> {
                    require(standard == null) { "${codePrefix}CompilationCommandUnsupported: conflicting C standards" }
                    standard = argument
                }
                argument == "-c" -> compileCount++
                argument == "-D" -> {
                    val value = arguments.getOrNull(++index)
                    require(value != null && value.matches(define)) {
                        "${codePrefix}CompilationCommandUnsupported: unsupported macro definition"
                    }
                    flags += "-D$value"
                }
                argument.startsWith("-D") && argument.substring(2).matches(define) -> flags += argument
                else -> {
                    val path = runCatching { Path.of(argument) }.getOrNull()
                    val resolved = path?.let { if (it.isAbsolute) it.normalize() else root.resolve(it).normalize() }
                    if (resolved == source) sourceCount++
                    else error("${codePrefix}CompilationCommandUnsupported: unexpected compiler argument; build commands are data and are never executed")
                }
            }
            index++
        }
        require(standard != null && compileCount == 1 && sourceCount == 1 && flags.size <= MAX_DEFINES) {
            "${codePrefix}CompilationCommandUnsupported: one explicit C11/C17 source command and bounded defines are required"
        }
        return listOf(standard!!) + flags
    }

    private fun captureStyle(snapshot: ProjectSnapshot, source: Path): StyleCapture {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val captured = snapshot.auxiliaryFiles.filter { it.languageId == CFormattingStyleSnapshot.LANGUAGE_ID }
            .associateBy { it.path.normalize() }
        var directory: Path? = source.parent
        while (directory != null && directory.startsWith(root)) {
            val candidates = listOf(directory.resolve(".clang-format"), directory.resolve("_clang-format"))
            val present = candidates.filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            require(present.size <= 1) {
                "c.formatStyleUnavailable: both style filenames are present in the nearest style directory"
            }
            present.singleOrNull()?.let { path ->
                val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                require(attributes.isRegularFile && !attributes.isSymbolicLink) {
                    "c.formatStyleUnavailable: style evidence must be a direct regular file"
                }
                val relative = root.relativize(path).normalize()
                val file = captured[relative]
                    ?: error("c.formatStyleUncaptured: nearest live style file is absent from the snapshot")
                val expected = file.content.toByteArray(Charsets.UTF_8)
                val observed = readBounded(path, CFormattingStyleSnapshot.MAX_STYLE_FILE_BYTES)
                require(expected.size <= CFormattingStyleSnapshot.MAX_STYLE_FILE_BYTES &&
                    observed != null && observed.contentEquals(expected)) {
                    "c.formatStyleChanged: captured nearest style file is missing or changed"
                }
                require(!Regex("(?im)^\\s*(?:BasedOnStyle\\s*:\\s*['\"]?InheritParentConfig|InheritParentConfig\\s*:\\s*true)(?:\\s|$)").containsMatchIn(file.content)) {
                    "c.formatStyleUnavailable: parent style inheritance is outside the captured workspace"
                }
                val digest = sha256(expected)
                return StyleCapture(
                    path = relative,
                    identityHash = sha256((relative.toString() + "|" + digest).toByteArray(Charsets.UTF_8)),
                    fileEvidence = OperationAuthorityFileEvidence("c.formatStyle", relative, digest),
                )
            }
            if (directory == root) break
            directory = directory.parent
        }
        return StyleCapture(
            path = null,
            identityHash = sha256("explicit-LLVM".toByteArray(Charsets.UTF_8)),
            fileEvidence = null,
        )
    }

    private inner class Captured(
        private val root: Path,
        private val beforeHash: String,
        private val stagedHash: String,
        private val source: Path,
        private val databasePath: Path,
        private val databaseHash: String,
        private val clangPath: Path,
        private val clangHash: String,
        private val runtimeIdentity: String,
        private val style: StyleCapture,
        val contexts: Map<Path, List<String>>,
        val lease: OperationAuthorityLease,
    ) {
        fun evaluate(candidate: ProjectSnapshot): List<Diagnostic> {
            require(candidate.workspace.root.toAbsolutePath().normalize() == root &&
                candidate.hash in setOf(beforeHash, stagedHash)) {
                "${codePrefix}AuthorityChanged: diagnostics candidate differs from approved before/staged images"
            }
            verifyInputs(candidate)
            val evaluated = contexts.flatMap { (absolute, flags) ->
                val relative = root.relativize(absolute)
                val result = diagnostics.analyze(candidate, relative, flags)
                val available = result as? CDiagnosticsResult.Available ?: run {
                    val unavailable = (result as CDiagnosticsResult.Unavailable).diagnostic
                    error("${unavailable.code}: ${unavailable.message}")
                }
                require(available.processProvenance?.executableSha256 == clangHash) {
                    "${codePrefix}CompilerChanged: syntax diagnostics did not retain the approved compiler hash"
                }
                available.diagnostics
            }
            verifyInputs(candidate)
            return evaluated
        }

        private fun verifyInputs(candidate: ProjectSnapshot) {
            require(Files.isRegularFile(databasePath, LinkOption.NOFOLLOW_LINKS) &&
                readBounded(databasePath, MAX_DATABASE_BYTES)?.let(::sha256) == databaseHash) {
                "${codePrefix}CompilationDatabaseChanged: root compilation database changed after preview"
            }
            require(!Files.exists(root.resolve("build/compile_commands.json"), LinkOption.NOFOLLOW_LINKS)) {
                "${codePrefix}CompilationDatabaseChanged: alternate build compilation database appeared after preview"
            }
            require(toolchain.provenance.nativeRuntimeIdentity == runtimeIdentity &&
                (toolchain.runtime as? ClangToolchainRuntimeCaptureResult.Captured)?.facts?.identity == runtimeIdentity &&
                ClangToolchainRuntimeCapture().checkCurrent(toolchain) == ClangToolchainRuntimeCurrency.Current &&
                hashFile(clangPath) == clangHash) {
                "${codePrefix}CompilerChanged: retained compiler/runtime identity changed after preview"
            }
            if (!isIncludeOrder && !isSafeDelete) {
                val observedStyle = captureStyle(candidate, root.resolve(source))
                require(observedStyle == style) {
                    "c.formatStyleChanged: nearest style evidence differs from the approved preview"
                }
            }
        }
    }

    private data class StyleCapture(
        val path: Path?,
        val identityHash: String,
        val fileEvidence: OperationAuthorityFileEvidence?,
    )

    private fun readBounded(path: Path, maxBytes: Int): ByteArray? = runCatching {
        val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() > maxBytes) return@runCatching null
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maxBytes + 1) }
        bytes.takeIf { it.size <= maxBytes }
    }.getOrNull()

    private fun hashFile(path: Path): String = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun introducedErrors(before: List<Diagnostic>, after: List<Diagnostic>): List<Diagnostic> {
        val remaining = before.filter { it.severity == Diagnostic.Severity.ERROR }.toMutableList()
        return after.filter { diagnostic ->
            if (diagnostic.severity != Diagnostic.Severity.ERROR) return@filter false
            val existing = remaining.indexOf(diagnostic)
            if (existing >= 0) {
                remaining.removeAt(existing)
                false
            } else true
        }
    }

    companion object {
        private const val FORMAT_GATE_ID = "clang-format-captured-build-v1"
        private const val INCLUDE_ORDER_GATE_ID = "clang-include-order-captured-build-v2"
        private const val SAFE_DELETE_GATE_ID = "clang-safe-delete-captured-build-v1"
        private const val FORMAT_LEASE_KIND = "c.format-managed-v1"
        private const val INCLUDE_ORDER_LEASE_KIND = "c.include-order-managed-v1"
        private const val SAFE_DELETE_LEASE_KIND = "c.safe-delete-managed-v1"
        private const val FORMAT_POLICY = "root-compdb-c11-c17-literal-flags-no-preprocessor-v1"
        private const val INCLUDE_ORDER_POLICY = "root-compdb-c11-c17-distinct-numeric-defines-v3"
        private const val MAX_MANAGED_TUS = 8
        private val INCLUDE_ORDER_STANDARDS = setOf("c11", "c17")
        private const val MAX_DATABASE_BYTES = 8 * 1024 * 1024
        private const val MAX_DEFINES = 32
        private val INCLUDE_ORDER_DEFINE = Regex("[A-Za-z][A-Za-z_0-9]*(?:=[0-9]+)?")
        private val SAFE_DELETE_SYMBOL = Regex("[A-Za-z_][A-Za-z_0-9]*")
    }

    private val policyId get() = when {
        isIncludeOrder -> INCLUDE_ORDER_POLICY
        isSafeDelete -> "root-compdb-c11-c17-literal-flags-no-preprocessor-safe-delete-v1"
        else -> FORMAT_POLICY
    }
    private val leaseAttributePrefix get() = when {
        isIncludeOrder -> "c.includeOrder"
        isSafeDelete -> "c.safeDelete"
        else -> "c.format"
    }
}

/** Exact captured C11/C17 diagnostics authority for admitted include reordering. */
internal class CManagedIncludeOrderContext(
    toolchain: ClangSemanticToolchain,
    diagnostics: CCompilerDiagnostics,
) {
    private val delegate = CManagedFormatContext(toolchain, diagnostics, managedOperation = "organizeIncludes")

    fun preview(snapshot: ProjectSnapshot, plan: PatchPlan): PatchPlan = delegate.preview(snapshot, plan)

    fun gate(snapshot: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate = delegate.gate(snapshot, plan)
}
