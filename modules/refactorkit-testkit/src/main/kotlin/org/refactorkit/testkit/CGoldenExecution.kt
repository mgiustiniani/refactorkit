package org.refactorkit.testkit

import org.refactorkit.c.CCompilationDatabaseDiscovery
import org.refactorkit.c.CCompilationDatabaseParser
import org.refactorkit.c.CCompilationDatabasePolicy
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.JournalState
import org.refactorkit.core.OwnedSourceInventoryReader
import org.refactorkit.core.OwnedWorkspaceTextReadout
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.SourceCapturePolicy
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Explicit executable selectors, never inferred from fixture commands or global PATH. */
data class CGoldenToolchainPaths(val clang: Path, val clangd: Path, val clangFormat: Path)

/** Recorded only after native managed Apply, independent images and exact rollback succeed. */
data class CGoldenQualification(
    val compilerVersion: String,
    val toolchainIdentity: String,
    val nativeRuntimeIdentity: String,
    val authorityKind: String,
    val snapshotBefore: String,
    val snapshotAfter: String,
    val restoredSnapshot: String,
    val transactionId: String,
    val originalFilesSha256: Map<String, String>,
    val appliedFilesSha256: Map<String, String>,
) {
    val rollbackVerified: Boolean get() = restoredSnapshot == snapshotBefore
}

private class CGoldenFailure(val code: String, message: String) : RuntimeException(message)

/** Native C fixture adapter. Compiler commands remain data consumed by the C authority. */
internal class CGoldenExecution(private val selected: CGoldenToolchainPaths?) {
    fun run(
        testCase: GoldenTestCase,
        request: GoldenRequest,
        workDir: Path,
        validate: (PatchPlan, GoldenExpectedPlan) -> List<String>,
    ): GoldenTestResult {
        var plan: PatchPlan? = null
        var before = emptyMap<Path, String>()
        try {
            before = captureTextFiles(testCase.beforeDir)
            val materialized = materialize(before, workDir)
            materialized.forEach { (path, text) ->
                val target = workDir.resolve(path)
                Files.createDirectories(target.parent)
                Files.writeString(target, text)
            }
            val paths = selected ?: fail("c.golden.toolchainUnavailable", "Explicit --clang, --clangd and --clang-format selectors are required")
            val databases = DATABASES.filter { it in materialized }
            if (databases.size != 1) fail("c.golden.compilationContextUnavailable", "Exactly one fixture compilation database is required")
            val database = CCompilationDatabaseParser(CCompilationDatabasePolicy(requireArgumentsArray = true))
                .parse(workDir, materialized.getValue(databases.single()).toByteArray(Charsets.UTF_8))
            if (database !is CCompilationDatabaseDiscovery.Available || database.database.units.isEmpty() ||
                database.database.units.any { it.standard !in setOf("c11", "c17") }) {
                fail("c.golden.compilationContextUnavailable", "Fixture must declare bounded C11/C17 argument-array contexts: $database")
            }
            val toolchain = when (val discovery = ClangToolchainDiscoverer().discover(
                ClangToolchainRequest(workDir, paths.clang, paths.clangd, paths.clangFormat),
            )) {
                is ClangToolchainDiscovery.Available -> discovery.toolchain
                else -> fail("c.golden.toolchainUnavailable", "Selected Clang toolchain is unavailable: $discovery")
            }
            val snapshot = CProjectScanner().scan(workDir)
            val operation = operation(request)
            CRefactoringFacade(toolchain).use { facade ->
                val preview = facade.previewForManagedApply(snapshot, operation, arguments(request))
                plan = preview
                val expectedPlan = GoldenJson.parseExpectedPlan(readInput(testCase.expectedPlanFile))
                val errors = validate(preview, expectedPlan).toMutableList()
                if (preview.status != PatchStatus.PREVIEW) {
                    if (!expectedPlan.status.equals("refused", ignoreCase = true)) {
                        errors += "Native C golden refused [${preview.refusalCode}]: ${preview.summary}"
                    }
                    if (captureTextFiles(workDir, generatedMetadata = true) != materialized) errors += "Refused C preview changed fixture inputs"
                    if (Files.isDirectory(testCase.afterDir)) {
                        errors += compare(materialize(captureTextFiles(testCase.afterDir), workDir), materialized)
                    }
                    verifyFixtureCurrent(testCase, before)
                    return GoldenTestResult(testCase.name, preview, planErrors = errors)
                }
                if (preview.evidence == RefactoringEvidence.LEXICAL_FALLBACK || preview.authorityLease == null) {
                    fail("c.golden.authorityUnavailable", "C golden Apply requires retained native authority")
                }
                if (errors.isNotEmpty()) return GoldenTestResult(testCase.name, preview, planErrors = errors)
                val expectedFiles = materialize(captureTextFiles(testCase.afterDir), workDir)
                val staged = WorkspaceEditSimulator.apply(snapshot, preview.workspaceEdit)
                val stagedFiles = staged.trackedFiles.associate { it.path to it.content }
                if (materialized.keys != snapshot.trackedFiles.map { it.path }.toSet()) {
                    fail("c.golden.fixtureScopeUnavailable", "Every fixture input must belong to the captured C source/context model")
                }
                val previewErrors = compare(expectedFiles, stagedFiles)
                if (previewErrors.isNotEmpty()) return GoldenTestResult(testCase.name, preview, afterErrors = previewErrors)
                val applied = facade.apply(snapshot, preview, ApplyAuthorization.explicit("golden-testkit-c"),
                    DiagnosticsGate.disabled("caller-cannot-disable-native-authority"))
                if (applied !is ApplyResult.Applied) fail("c.golden.applyRefused", "Native C Apply refused: $applied")
                val actual = captureTextFiles(workDir, generatedMetadata = true)
                val afterErrors = compare(expectedFiles, actual)
                val afterHash = CProjectScanner().scan(workDir).hash
                val rollback = PatchEngine(workDir).rollback(applied.transaction)
                if (rollback !is ApplyResult.Applied) fail("c.golden.rollbackUnavailable", "Native C rollback failed: $rollback")
                val restored = captureTextFiles(workDir, generatedMetadata = true)
                val restoredHash = CProjectScanner().scan(workDir).hash
                if (restored != materialized || restoredHash != snapshot.hash) fail("c.golden.rollbackMismatch", "C fixture snapshot was not restored exactly")
                val journal = TransactionLog(workDir.resolve(".refactorkit/transactions")).listRecords().singleOrNull()
                if (journal?.state != JournalState.ROLLED_BACK || journal.transaction.id != applied.transaction.id ||
                    journal.transaction.approval.surface != "golden-testkit-c") fail("c.golden.journalMismatch", "C approval/rollback journal is unavailable")
                verifyFixtureCurrent(testCase, before)
                if (afterErrors.isNotEmpty()) return GoldenTestResult(testCase.name, preview, afterErrors = afterErrors)
                val evidence = CGoldenQualification(toolchain.provenance.clangVersion,
                    toolchain.provenance.toolchainIdentity(), requireNotNull(toolchain.provenance.nativeRuntimeIdentity),
                    requireNotNull(preview.authorityLease).kind, snapshot.hash, afterHash, restoredHash,
                    applied.transaction.id.value, hashes(materialized), hashes(actual))
                return GoldenTestResult(testCase.name, preview, afterErrors = afterErrors, cQualification = evidence)
            }
        } catch (failure: Exception) {
            val code = (failure as? CGoldenFailure)?.code ?: "c.golden.inputUnavailable"
            val message = failure.message ?: "Native C golden authority is unavailable"
            val refused = plan ?: refused(request, workDir, code, message)
            return GoldenTestResult(testCase.name, refused, planErrors = listOf("Native C golden refused [$code]: $message"))
        }
    }

    private fun arguments(request: GoldenRequest): Map<String, String> {
        val result = request.arguments.toMutableMap()
        result.remove("languageId")
        if (operation(request) == "renamePrefix") {
            val raw = result.remove("mapping") ?: fail("c.golden.invalidMapping", "Prefix golden request requires explicit mapping old=new pairs")
            for (entry in raw.split(',')) {
                val parts = entry.split('=')
                if (parts.size != 2 || parts.any { it.isBlank() } || result.put("rename.${parts[0]}", parts[1]) != null)
                    fail("c.golden.invalidMapping", "Prefix mapping contains malformed or duplicate source entries")
            }
        }
        return result
    }

    private fun materialize(files: Map<Path, String>, root: Path): Map<Path, String> = files.mapValues { (path, text) ->
        if (path !in DATABASES || selected == null) text else text
            .replace("__GOLDEN_WORKSPACE__", jsonContent(root.toAbsolutePath().normalize().toString()))
            .replace("__GOLDEN_CLANG__", jsonContent(selected.clang.toAbsolutePath().normalize().toString()))
    }

    private fun captureTextFiles(root: Path, generatedMetadata: Boolean = false): Map<Path, String> {
        val ignored = if (generatedMetadata) setOf(".refactorkit", ".git") else emptySet()
        val inventory = OwnedSourceInventoryReader(emptyMap(), SourceCapturePolicy(maxEntries = 25_000, maxDepth = 64), ignored)
            .capture(root.toAbsolutePath().normalize())
        if (!generatedMetadata && inventory.directories.any { it.nameCount > 0 && it.getName(0).toString() in setOf(".git", ".refactorkit") })
            fail("c.golden.fixtureScopeUnavailable", "Golden source fixture cannot supply transaction or Git metadata")
        if (inventory.regularPaths.size > 4096) fail("c.golden.fixtureLimit", "Fixture file count exceeds 4096")
        var bytes = 0L
        val normalizedRoot = root.toAbsolutePath().normalize()
        return inventory.regularPaths.map { absolute -> normalizedRoot.relativize(absolute) }.associateWith { relative ->
            val content = when (val read = OwnedSourceInventoryReader.readWorkspaceTextInput(root, relative,
                if (relative in DATABASES) 8 * 1024 * 1024 else 2 * 1024 * 1024)) {
                is OwnedWorkspaceTextReadout.Captured -> read.content
                OwnedWorkspaceTextReadout.Absent -> fail("c.golden.fixtureChanged", "Fixture file disappeared: $relative")
            }
            bytes += content.toByteArray(Charsets.UTF_8).size
            if (bytes > 64L * 1024 * 1024) fail("c.golden.fixtureLimit", "Fixture bytes exceed 64 MiB")
            content
        }
    }

    private fun verifyFixtureCurrent(testCase: GoldenTestCase, before: Map<Path, String>) {
        if (captureTextFiles(testCase.beforeDir) != before) fail("c.golden.fixtureChanged", "Authored source fixture changed during qualification")
    }

    private fun refused(request: GoldenRequest, root: Path, code: String, message: String): PatchPlan = PatchPlan(
        operation = if (operation(request) == "renamePrefix") "renameCSymbolPrefix" else operation(request),
        status = PatchStatus.REFUSED,
        snapshotHash = runCatching { CProjectScanner().scan(root).hash }.getOrElse { ProjectSnapshot(Workspace(root), emptyList(), emptyList()).hash },
        confidence = 0.0, requiresUserApproval = false, summary = message, affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(), warnings = listOf("$code: $message"), refusalCode = code,
    )

    private fun compare(expected: Map<Path, String>, actual: Map<Path, String>): List<String> = buildList {
        expected.forEach { (path, text) -> if (actual[path] != text) add("C golden content mismatch or missing file: $path") }
        (actual.keys - expected.keys).forEach { add("C golden unexpected file: $it") }
    }

    private fun hashes(files: Map<Path, String>): Map<String, String> = files.entries.sortedBy { it.key.toString() }.associate { (path, text) ->
        path.toString() to MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun jsonContent(text: String): String = buildString {
        text.forEach { c -> when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
        } }
    }

    companion object {
        private val DATABASES = setOf(Path.of("compile_commands.json"), Path.of("build/compile_commands.json"))
        private val C_OPERATIONS = setOf("renamePrefix", "renameCSymbolPrefix", "extractExpression", "inlineFunction", "organizeIncludes", "relocateComponent")
        fun handles(request: GoldenRequest): Boolean = request.operation in C_OPERATIONS ||
            request.operation.startsWith("c.") || request.arguments["languageId"] == "c"
        private fun operation(request: GoldenRequest): String = request.operation.removePrefix("c.").let {
            if (it == "renameCSymbolPrefix") "renamePrefix" else it
        }
        private fun fail(code: String, message: String): Nothing = throw CGoldenFailure(code, message)
        fun readInput(path: Path): String = when (val read = OwnedSourceInventoryReader.readWorkspaceTextInput(
            path.toAbsolutePath().normalize().parent, path.fileName, 8 * 1024 * 1024)) {
            is OwnedWorkspaceTextReadout.Captured -> read.content
            OwnedWorkspaceTextReadout.Absent -> fail("c.golden.fixtureUnavailable", "Missing fixture input: ${path.fileName}")
        }
    }
}
