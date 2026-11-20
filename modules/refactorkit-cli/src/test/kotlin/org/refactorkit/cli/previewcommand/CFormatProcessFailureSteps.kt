package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.CFormatterStoryHarness
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainProvenance
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.TextEdits
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** C formatter Story adapters share one runner and keep native behavior explicit. */
class CFormatProcessFailureSteps {
    private var sandbox: Path? = null
    private lateinit var workspace: Path
    private lateinit var original: ByteArray
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var formatter: Path
    private lateinit var plan: PatchPlan
    private var selectedToolchain: ClangSemanticToolchain? = null
    private lateinit var sourceCondition: String
    private lateinit var observedFiles: Map<Path, ByteArray>
    private lateinit var observedLinks: Map<Path, Path>
    private var processesReaped = false
    private var formatterStdout: String? = null
    private var formatterExitCode: Int? = null
    private var runtimeCurrencyCurrent = false
    private var formatterRequestCount = 0
    private var processArguments: List<List<String>> = emptyList()
    private var includeOrderScenario = false
    private var snapshotInputScenario = false
    private var styleSnapshotScenario = false
    private var styleSnapshotState: String? = null
    private var styleRangeScenario = false
    private var formatApplyResult: ApplyResult? = null
    private var ambientStylePath: Path? = null
    private var ambientStyleBytes: ByteArray? = null

    @Given("an isolated C formatting workspace with a captured source snapshot")
    fun snapshotInputFixture() {
        val root = Files.createTempDirectory("refactorkit-c-format-snapshot-input-")
        sandbox = root
        workspace = Files.createDirectory(root.resolve("workspace"))
        val content = "int main(void){return 7;}\n"
        original = content.toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("main.c"), original)
        Files.writeString(workspace.resolve(".clang-format"), "BasedOnStyle: LLVM\n")
        snapshot = CProjectScanner().scan(workspace)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        selectedToolchain = discoverToolchain(workspace, reference)
        observedFiles = Files.walk(workspace).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            .toList().associate { workspace.relativize(it) to Files.readAllBytes(it) } }
        observedLinks = emptyMap()
        snapshotInputScenario = true
    }

    @Given("an acquired C formatting snapshot has {string} source evidence")
    fun acquiredSource(condition: String) {
        sourceCondition = condition
        val root = Files.createTempDirectory("refactorkit-c-format-source-"); sandbox = root
        workspace = Files.createDirectory(root.resolve("workspace"))
        val source = workspace.resolve("main.c"); val content = "int main(void){return 7;}\n"
        original = content.toByteArray(Charsets.UTF_8); Files.write(source, original)
        Files.writeString(workspace.resolve(".clang-format"), "BasedOnStyle: LLVM\n")
        snapshot = CProjectScanner().scan(workspace).copy(files = listOf(SourceFile(
            if (condition == "absolute capture") source else Path.of("main.c"), content, "c")))
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val discovered = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspace, reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"), reference.resolve("usr/bin/clang-format"))))
        assertEquals(listOf("22.1.8", "22.1.8", "22.1.8"), listOf(discovered.toolchain.provenance.clangVersion,
            discovered.toolchain.provenance.clangdVersion, discovered.toolchain.provenance.clangFormatVersion))
        selectedToolchain = discovered.toolchain
        when (condition) {
            "relative capture", "absolute capture" -> Unit
            "captured bytes change" -> Files.writeString(source, "int main(void){return 8;}\n")
            "captured file removed" -> Files.delete(source)
            "source link inserted" -> {
                Files.delete(source); val target = workspace.resolve("owned-data.c")
                Files.writeString(target, "int main(void){return 8;}\n"); Files.createSymbolicLink(source, target)
            }
            "source not captured" -> snapshot = snapshot.copy(files = emptyList())
            else -> error("Unexpected acquired source condition: $condition")
        }
        if (condition !in setOf("relative capture", "absolute capture")) {
            // Explicit port witness: plausible idempotent output must not bypass source admission.
            formatter = root.resolve("formatter.sh")
            Files.writeString(formatter, "#!/bin/sh\nprintf launched > '${root.resolve("formatter-ran")}'\nprintf 'int main(void) { return 7; }\\n'\n")
            check(formatter.toFile().setExecutable(true))
            selectedToolchain = discovered.toolchain.copy(clangFormatExecutable = formatter)
        }
        observedFiles = Files.walk(workspace).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            .toList().associate { workspace.relativize(it) to Files.readAllBytes(it) } }
        observedLinks = Files.walk(workspace).use { paths -> paths.filter(Files::isSymbolicLink)
            .toList().associate { workspace.relativize(it) to Files.readSymbolicLink(it) } }
    }

    @Given("an isolated C formatting workspace and a failing formatter producing {string}")
    fun failingFormatter(stdoutKind: String) {
        require(System.getProperty("os.name").startsWith("Linux")) { "This C process qualification targets Linux x86-64 only" }
        require(stdoutKind in setOf("empty", "partial")) { "Unexpected fixture variant: $stdoutKind" }
        val root = Files.createTempDirectory("refactorkit-c-format-failure-")
        sandbox = root
        workspace = Files.createDirectory(root.resolve("workspace"))
        val content = "int main(void) { return 7; }\n"
        original = content.toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("main.c"), original)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        selectedToolchain = discoverToolchain(workspace, reference)
        formatterStdout = if (stdoutKind == "partial") "int main(void) { return 8; }\n" else ""
        formatterExitCode = 23
        snapshot = CProjectScanner().scan(workspace)
    }

    @Given("an isolated C formatting file with order-sensitive includes and sorting style")
    fun sortingStyleFixture() {
        val root = Files.createTempDirectory("refactorkit-c-format-includes-")
        sandbox = root
        workspace = Files.createDirectory(root.resolve("workspace"))
        val content = "#include <z.h>\n#include <a.h>\nint main(void) { return 0; }\n"
        original = content.toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("main.c"), original)
        Files.writeString(workspace.resolve(".clang-format"), "BasedOnStyle: LLVM\nSortIncludes: true\n")
        snapshot = CProjectScanner().scan(workspace)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        selectedToolchain = discoverToolchain(workspace, reference)
        assertEquals(listOf("22.1.8", "22.1.8", "22.1.8"), listOf(
            selectedToolchain!!.provenance.clangVersion,
            selectedToolchain!!.provenance.clangdVersion,
            selectedToolchain!!.provenance.clangFormatVersion,
        ))
        includeOrderScenario = true
    }

    @When("the managed C formatting preview requests clang-format")
    fun previewWithIncludeOrder() = preview()

    @When("the managed C format preview is requested")
    fun preview() {
        if (formatterExitCode != null) {
            val observation = CFormatterStoryHarness().previewResponse(
                assertNotNull(selectedToolchain), snapshot, assertNotNull(formatterStdout), formatterExitCode!!,
            )
            plan = observation.plan
            processesReaped = observation.noManagedProcessRemains
            runtimeCurrencyCurrent = observation.runtimeCurrentBefore && observation.runtimeCurrentAfter
            formatterRequestCount = observation.formatterRequestCount
        } else if (styleRangeScenario) {
            val toolchain = assertNotNull(selectedToolchain)
            val (startLine, endLine) = when (styleSnapshotState) {
                "captured project style and a bounded range" -> "2" to "2"
                "captured project style and two discontiguous ranges",
                "captured project style and 32 discontiguous ranges" -> null to null
                "captured project style and a malformed range list",
                "captured project style and overlapping ranges",
                "captured project style and an out-of-order range list",
                "captured project style and an out-of-file range list",
                "captured project style and a range list above 32 entries" -> null to null
                "captured project style and a partial range" -> "2" to null
                "captured project style and a malformed range" -> "invalid" to "2"
                "captured project style and a reversed range" -> "3" to "2"
                "captured project style and an out-of-file range" -> "2" to "99"
                else -> error("Unexpected bounded-range state: $styleSnapshotState")
            }
            val ranges = when (styleSnapshotState) {
                "captured project style and two discontiguous ranges" -> "2:2,4:4"
                "captured project style and 32 discontiguous ranges" ->
                    (0..62 step 2).joinToString(",") { index -> "$index:$index" }
                "captured project style and a malformed range list" -> "1:1,broken:2"
                "captured project style and overlapping ranges" -> "1:2,2:3"
                "captured project style and an out-of-order range list" -> "4:4,2:2"
                "captured project style and an out-of-file range list" -> "2:2,6:6"
                "captured project style and a range list above 32 entries" ->
                    (0..64 step 2).joinToString(",") { "$it:$it" }
                else -> null
            }
            if (styleSnapshotState == "captured project style and a range list above 32 entries") {
                assertEquals(33, assertNotNull(ranges).split(',').size)
                assertEquals(66, snapshot.files.single().content.count { it == '\n' })
            }
            if (styleSnapshotState == "captured project style and 32 discontiguous ranges") {
                assertEquals((0..62 step 2).map { "$it:$it" }, assertNotNull(ranges).split(','))
                assertEquals(32, ranges.split(',').size)
                assertEquals(66, snapshot.files.single().content.count { it == '\n' })
            }
            val observation = CFormatterStoryHarness().previewNative(toolchain, snapshot, startLine, endLine, ranges)
            plan = observation.plan
            processesReaped = observation.noManagedProcessRemains
            runtimeCurrencyCurrent = observation.runtimeCurrentBefore && observation.runtimeCurrentAfter
            formatterRequestCount = observation.formatterRequestCount
            processArguments = observation.processArguments
        } else if (includeOrderScenario || snapshotInputScenario || styleSnapshotScenario) {
            val observation = CFormatterStoryHarness().previewNative(assertNotNull(selectedToolchain), snapshot)
            plan = observation.plan
            processesReaped = observation.noManagedProcessRemains
            runtimeCurrencyCurrent = observation.runtimeCurrentBefore && observation.runtimeCurrentAfter
            formatterRequestCount = observation.formatterRequestCount
            processArguments = observation.processArguments
        } else {
            val toolchain = selectedToolchain ?: ClangSemanticToolchain(
                clangExecutable = workspace.resolve("unused-clang"),
                clangdExecutable = workspace.resolve("unused-clangd"),
                clangFormatExecutable = formatter,
                provenance = ClangToolchainProvenance(
                    clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
                    targetTriple = "x86_64-pc-linux-gnu", resourceDir = null, evidence = emptyList(),
                ),
            )
            ExternalSemanticProcessManager().use { manager ->
                CRefactoringFacade(toolchain, manager).use { facade ->
                    plan = facade.preview(snapshot, "formatFile", mapOf("file" to "main.c"))
                }
                processesReaped = manager.activeProvenance().isEmpty()
            }
        }
    }

    @Given("a scanned C formatting workspace with {string} style evidence")
    fun styleSnapshotFixture(state: String) {
        require(state in setOf(
            "the captured project style is current",
            "captured project style and a bounded range",
            "captured project style and two discontiguous ranges",
            "captured project style and 32 discontiguous ranges",
            "captured project style and a partial range",
            "captured project style and a malformed range",
            "captured project style and a malformed range list",
            "captured project style and a reversed range",
            "captured project style and an out-of-file range",
            "captured project style and overlapping ranges",
            "captured project style and an out-of-order range list",
            "captured project style and an out-of-file range list",
            "captured project style and a range list above 32 entries",
            "the captured project style changed",
            "the live style file was omitted",
            "the project style is a symbolic link",
            "the workspace has no project style file",
            "both project style filenames are present",
            "the captured project style inherits parent configuration",
            "captured C17 compilation diagnostics",
        )) { "Unexpected style fixture: $state" }
        val root = Files.createTempDirectory("refactorkit-c-format-style-")
        sandbox = root
        workspace = Files.createDirectory(root.resolve("workspace"))
        val rangeContent = "#include <z.h>\n#include <a.h>\nint inside(void){int first=1; int second=2; int third=3; return first+second+third;}\nint outside(void){return 2;}\n"
        val multiRangeContent = "#include <z.h>\n#include <a.h>\nint first(void){int firstValue=1; return firstValue;}\nint between(void){int betweenValue=2; return betweenValue;}\nint second(void){int secondValue=3; return secondValue;}\nint outside(void){return 4;}\n"
        val overLimitContent = buildString {
            repeat(66) { index -> append("int rangeValue$index;\n") }
        }
        val maxRangeContent = buildString {
            repeat(66) { index ->
                if (index < 64 && index % 2 == 0) append("int selected$index=$index;\n")
                else append("int spacer$index = $index;\n")
            }
        }
        val content = when (state) {
            "captured project style and a bounded range" -> rangeContent
            "captured project style and two discontiguous ranges",
            "captured project style and an out-of-order range list",
            "captured project style and an out-of-file range list" -> multiRangeContent
            "captured project style and 32 discontiguous ranges" -> maxRangeContent
            "captured project style and a range list above 32 entries" -> overLimitContent
            else -> "int main(void){\nint value=1;\nreturn value;\n}\n"
        }
        original = content.toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("main.c"), original)
        val stylePath = workspace.resolve(".clang-format")
        when (state) {
            "captured project style and a bounded range" ->
                Files.writeString(stylePath, "BasedOnStyle: LLVM\nIndentWidth: 4\nSortIncludes: true\n")
            "captured project style and two discontiguous ranges" ->
                Files.writeString(stylePath, "BasedOnStyle: LLVM\nIndentWidth: 4\nSortIncludes: true\n")
            "captured project style and 32 discontiguous ranges" ->
                Files.writeString(stylePath, "BasedOnStyle: LLVM\nIndentWidth: 4\nSortIncludes: true\n")
            "captured project style and a malformed range list",
            "captured project style and overlapping ranges",
            "captured project style and an out-of-order range list",
            "captured project style and an out-of-file range list",
            "captured project style and a range list above 32 entries" ->
                Files.writeString(stylePath, "BasedOnStyle: LLVM\nIndentWidth: 4\n")
            "the captured project style is current", "the captured project style changed", "the live style file was omitted" ->
                Files.writeString(stylePath, "BasedOnStyle: LLVM\nIndentWidth: 4\n")
            "the captured project style inherits parent configuration" ->
                Files.writeString(stylePath, "BasedOnStyle: InheritParentConfig\nIndentWidth: 4\n")
            "the project style is a symbolic link" -> {
                val target = root.resolve("external-style.yaml")
                ambientStylePath = target
                ambientStyleBytes = "BasedOnStyle: LLVM\nIndentWidth: 4\n".toByteArray(Charsets.UTF_8)
                Files.write(target, ambientStyleBytes!!)
                Files.write(stylePath, ambientStyleBytes!!)
            }
            "both project style filenames are present" -> {
                Files.writeString(stylePath, "BasedOnStyle: LLVM\nIndentWidth: 4\n")
                Files.writeString(workspace.resolve("_clang-format"), "BasedOnStyle: LLVM\nIndentWidth: 2\n")
            }
            "captured C17 compilation diagnostics" -> {
                Files.writeString(stylePath, "BasedOnStyle: LLVM\n")
                val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
                val clang = reference.resolve("usr/bin/clang-22").toAbsolutePath().normalize()
                val rootPath = workspace.toAbsolutePath().normalize()
                Files.writeString(workspace.resolve("compile_commands.json"),
                    """[{"directory":"$rootPath","file":"main.c","arguments":["$clang","-std=c17","-c","main.c"]}]""")
            }
            "the workspace has no project style file" -> {
                val parentStyle = root.resolve(".clang-format")
                ambientStylePath = parentStyle
                ambientStyleBytes = "BasedOnStyle: LLVM\nIndentWidth: 8\n".toByteArray(Charsets.UTF_8)
                Files.write(parentStyle, ambientStyleBytes!!)
            }
        }
        snapshot = CProjectScanner().scan(workspace)
        when (state) {
            "the project style is a symbolic link" -> {
                assertTrue(snapshot.auxiliaryFiles.any { it.path == Path.of(".clang-format") })
                Files.delete(stylePath)
                Files.createSymbolicLink(stylePath, root.resolve("external-style.yaml"))
            }
            "the captured project style changed" -> Files.writeString(stylePath, "BasedOnStyle: LLVM\nIndentWidth: 2\n")
            "the live style file was omitted" -> snapshot = snapshot.copy(
                auxiliaryFiles = snapshot.auxiliaryFiles.filterNot { it.path == Path.of(".clang-format") },
            )
        }
        styleSnapshotState = state
        styleSnapshotScenario = true
        styleRangeScenario = state in setOf(
            "captured project style and a bounded range",
            "captured project style and two discontiguous ranges",
            "captured project style and 32 discontiguous ranges",
            "captured project style and a partial range",
            "captured project style and a malformed range",
            "captured project style and a malformed range list",
            "captured project style and a reversed range",
            "captured project style and an out-of-file range",
            "captured project style and overlapping ranges",
            "captured project style and an out-of-order range list",
            "captured project style and an out-of-file range list",
            "captured project style and a range list above 32 entries",
        )
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        selectedToolchain = discoverToolchain(workspace, reference)
        observedFiles = Files.walk(workspace).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            .toList().associate { workspace.relativize(it) to Files.readAllBytes(it) } }
        observedLinks = Files.walk(workspace).use { paths -> paths.filter(Files::isSymbolicLink)
            .toList().associate { workspace.relativize(it) to Files.readSymbolicLink(it) } }
    }

    @When("the managed C format preview is applied with captured C diagnostics")
    fun applyFormatWithCapturedDiagnostics() {
        assertEquals("captured C17 compilation diagnostics", styleSnapshotState)
        val toolchain = assertNotNull(selectedToolchain)
        ExternalSemanticProcessManager().use { manager ->
            CRefactoringFacade(toolchain, manager).use { facade ->
                plan = facade.preview(snapshot, "formatFile", mapOf("file" to "main.c"))
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
                assertNotNull(plan.authorityLease, "captured compiler context must bind the preview: ${plan.warnings}")
                assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") }, plan.warnings.toString())
                assertTrue(plan.diagnosticsBefore.none { it.severity == Diagnostic.Severity.ERROR }, plan.diagnosticsBefore.toString())
                assertTrue(plan.diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR }, plan.diagnosticsAfterPreview.toString())
                formatApplyResult = facade.apply(
                    snapshot,
                    plan,
                    ApplyAuthorization.explicit("c-format-managed-apply-story"),
                    facade.diagnosticsGate(snapshot, plan),
                )
            }
            assertTrue(manager.activeProvenance().isEmpty(), "all formatter/compiler processes must be reaped")
        }
    }

    @Then("formatting apply and rollback restore exact source bytes while preserving project inputs")
    fun formattingApplyRollbackRestoresInputs() {
        val applied = assertIs<ApplyResult.Applied>(formatApplyResult)
        val source = workspace.resolve("main.c")
        val committed = Files.readAllBytes(source)
        assertFalse(committed.contentEquals(original), "the qualified formatter must commit a real source edit")
        val committedPaths = Files.walk(workspace).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList()
                .map { workspace.relativize(it) }.toSet()
        }
        assertTrue(committedPaths.containsAll(observedFiles.keys), "source and project inputs must remain present")
        assertTrue((committedPaths - observedFiles.keys).all { it.startsWith(Path.of(".refactorkit")) },
            "only RefactorKit transaction metadata may be added: $committedPaths")
        for (path in listOf(Path.of("compile_commands.json"), Path.of(".clang-format"))) {
            assertContentEquals(observedFiles.getValue(path), Files.readAllBytes(workspace.resolve(path)), path.toString())
        }
        assertFalse(Files.exists(workspace.resolve("main.o")), "the captured build command must never be executed")
        val rolledBack = PatchEngine(workspace).rollback(applied.transaction)
        assertIs<ApplyResult.Applied>(rolledBack)
        assertContentEquals(original, Files.readAllBytes(source), "rollback must restore exact original source bytes")
        for (path in observedFiles.keys.filter { it != Path.of("main.c") }) {
            assertContentEquals(observedFiles.getValue(path), Files.readAllBytes(workspace.resolve(path)), path.toString())
        }
        assertFalse(Files.exists(workspace.resolve("main.o")), "rollback must not run the build command")
    }

    @When("the captured C formatter style changes after a preview before managed Apply")
    fun changeCapturedStyleBeforeManagedApply() {
        assertEquals("captured C17 compilation diagnostics", styleSnapshotState)
        val toolchain = assertNotNull(selectedToolchain)
        ExternalSemanticProcessManager().use { manager ->
            CRefactoringFacade(toolchain, manager).use { facade ->
                plan = facade.preview(snapshot, "formatFile", mapOf("file" to "main.c"))
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
                assertNotNull(plan.authorityLease, "the original captured style must bind Apply authority")
                assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") }, plan.warnings.toString())
                Files.writeString(workspace.resolve(".clang-format"), "BasedOnStyle: LLVM\nIndentWidth: 2\n")
                formatApplyResult = facade.apply(
                    snapshot,
                    plan,
                    ApplyAuthorization.explicit("c-format-style-drift-story"),
                    facade.diagnosticsGate(snapshot, plan),
                )
            }
            assertTrue(manager.activeProvenance().isEmpty(), "all formatter/compiler processes must be reaped")
        }
    }

    @Then("the managed C format Apply refuses without changing source or creating a journal")
    fun staleFormatStyleRefusesWithoutMutation() {
        val refused = assertIs<ApplyResult.Refused>(formatApplyResult)
        assertEquals("authorityLease.evidenceDrift", refused.diagnostics.firstOrNull()?.code, refused.diagnostics.toString())
        assertContentEquals(original, Files.readAllBytes(workspace.resolve("main.c")))
        assertContentEquals(observedFiles.getValue(Path.of("compile_commands.json")),
            Files.readAllBytes(workspace.resolve("compile_commands.json")))
        assertContentEquals(
            "BasedOnStyle: LLVM\nIndentWidth: 2\n".toByteArray(Charsets.UTF_8),
            Files.readAllBytes(workspace.resolve(".clang-format")),
            "the external style change must be preserved",
        )
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")),
            "stale style must refuse before creating a transaction journal")
        assertFalse(Files.exists(workspace.resolve("main.o")), "the captured build command must never be executed")
    }

    @Then("the C formatter style decision is {string}")
    fun styleDecision(expected: String) {
        val state = requireNotNull(styleSnapshotState)
        if (expected == "preview") {
            assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
            assertTrue(plan.requiresUserApproval)
            val proposedEdit = assertNotNull(
                plan.workspaceEdit.edits.singleOrNull(),
                "expected formatter edits; summary=${plan.summary}, refusal=${plan.refusalCode}, warnings=${plan.warnings}, args=$processArguments",
            )
            val edit = assertIs<FileEdit.Modify>(proposedEdit)
            val formatted = TextEdits.apply(snapshot.files.single().content, edit.textEdits)
            when (state) {
                "the captured project style is current" -> {
                    val styleArgument = "-style=file:${workspace.resolve(".clang-format").toAbsolutePath().normalize()}"
                    assertTrue(formatted.contains("    int value = 1;"), "captured four-space indentation must control formatting: $formatted")
                    assertEquals(2, formatterRequestCount)
                    assertTrue(processArguments.all { styleArgument in it }, "both passes must bind the captured config path")
                }
                "captured project style and a bounded range" -> {
                    assertTrue(formatted.contains("    return first + second + third;"), "captured four-space indentation must control the selected range: $formatted")
                    assertTrue(formatted.contains("int outside(void){return 2;}"), "the adjacent unselected function must remain untouched: $formatted")
                    assertTrue(formatted.indexOf("#include <z.h>") < formatted.indexOf("#include <a.h>"), "range formatting must preserve include order: $formatted")
                    assertTrue(plan.warnings.any { it.contains("idempotent=true") }, "the preview must record the formatter idempotence result")
                    assertEquals(2, formatterRequestCount, "formatting and idempotence each run once")
                    assertTrue(processArguments.all { "-lines=3:3" in it }, "both formatter passes must receive the selected line range")
                    val styleArgument = "-style=file:${workspace.resolve(".clang-format").toAbsolutePath().normalize()}"
                    assertTrue(processArguments.all { styleArgument in it }, "both passes must use the captured style")
                    assertEquals(PatchStatus.PREVIEW, plan.status)
                }
                "captured project style and two discontiguous ranges" -> {
                    assertTrue(formatted.contains("    return firstValue;"), "the first selected region must use captured four-space indentation: $formatted")
                    assertTrue(formatted.contains("int between(void){int betweenValue=2; return betweenValue;}"), "the intervening region must remain untouched: $formatted")
                    assertTrue(formatted.contains("    return secondValue;"), "the second selected region must use captured four-space indentation: $formatted")
                    assertTrue(formatted.contains("int outside(void){return 4;}"), "the trailing unselected function must remain untouched: $formatted")
                    assertTrue(formatted.indexOf("#include <z.h>") < formatted.indexOf("#include <a.h>"), "range formatting must preserve include order: $formatted")
                    assertTrue(plan.warnings.any { it.contains("idempotent=true") }, "the preview must record the formatter idempotence result")
                    assertEquals(3, formatterRequestCount, "formatting, range mapping, and idempotence each run once")
                    val requestedLines = listOf("-lines=3:3", "-lines=5:5")
                    assertEquals(requestedLines, processArguments[0].filter { it.startsWith("-lines=") })
                    assertEquals(requestedLines, processArguments[1].filter { it.startsWith("-lines=") })
                    assertTrue("--output-replacements-xml" in processArguments[1], "the mapping pass must report exact formatter replacements")
                    assertEquals(listOf("-lines=3:3", "-lines=8:8"), processArguments[2].filter { it.startsWith("-lines=") },
                        "the idempotence pass must rebase the second range after the first region expands")
                    val styleArgument = "-style=file:${workspace.resolve(".clang-format").toAbsolutePath().normalize()}"
                    assertTrue(processArguments.all { styleArgument in it }, "both passes must use the captured style")
                }
                "captured project style and 32 discontiguous ranges" -> {
                    val expectedRanges = (1..63 step 2).map { "-lines=$it:$it" }
                    assertEquals(3, formatterRequestCount, "formatting, replacement mapping, and idempotence each run once")
                    assertEquals(expectedRanges, processArguments[0].filter { it.startsWith("-lines=") })
                    assertEquals(expectedRanges, processArguments[1].filter { it.startsWith("-lines=") })
                    assertTrue("--output-replacements-xml" in processArguments[1], "the mapping pass must report exact formatter replacements")
                    assertEquals(expectedRanges, processArguments[2].filter { it.startsWith("-lines=") },
                        "the idempotence pass must preserve all 32 line intervals")
                    val styleArgument = "-style=file:${workspace.resolve(".clang-format").toAbsolutePath().normalize()}"
                    assertTrue(processArguments.all { styleArgument in it }, "all formatter passes must use the captured style")
                }
                "the workspace has no project style file" -> {
                    assertTrue(formatted.contains("  int value = 1;"), "explicit LLVM fallback must ignore the parent style: $formatted")
                    assertEquals(2, formatterRequestCount)
                    assertTrue(processArguments.all { "-style=LLVM" in it }, "absence must select explicit LLVM on both passes")
                    assertTrue(processArguments.none { args -> args.any { it.contains(".clang-format") } })
                }
                else -> error("Unexpected successful style fixture: $state")
            }
        } else {
            assertEquals("refused", expected)
            val expectedCode = when (state) {
                "the captured project style changed" -> "clang.formatStyleChanged"
                "the live style file was omitted" -> "clang.formatStyleUncaptured"
                "the project style is a symbolic link" -> "clang.formatStyleUnavailable"
                "both project style filenames are present" -> "clang.formatStyleUnavailable"
                "the captured project style inherits parent configuration" -> "clang.formatStyleUnavailable"
                "captured project style and a partial range",
                "captured project style and a malformed range",
                "captured project style and a malformed range list",
                "captured project style and a reversed range",
                "captured project style and an out-of-file range",
                "captured project style and overlapping ranges",
                "captured project style and an out-of-order range list",
                "captured project style and an out-of-file range list",
                "captured project style and a range list above 32 entries" -> "clang.formatInvalidRange"
                else -> error("Unexpected refused style fixture: $state")
            }
            assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
            assertEquals(expectedCode, plan.refusalCode)
            assertTrue(plan.workspaceEdit.edits.isEmpty())
            assertTrue(plan.affectedFiles.isEmpty())
            assertEquals(0, formatterRequestCount, "style authority must be established before launching clang-format")
        }
        assertTrue(runtimeCurrencyCurrent)
        assertTrue(processesReaped)
    }

    @And("the formatter style check preserves workspace inputs and leaves no transaction journal")
    fun styleInputsPreserved() {
        val current = Files.walk(workspace).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            .toList().associate { workspace.relativize(it) to Files.readAllBytes(it) } }
        assertEquals(observedFiles.keys, current.keys)
        for ((path, bytes) in observedFiles) assertContentEquals(bytes, current[path], path.toString())
        val links = Files.walk(workspace).use { paths -> paths.filter(Files::isSymbolicLink)
            .toList().associate { workspace.relativize(it) to Files.readSymbolicLink(it) } }
        assertEquals(observedLinks, links)
        ambientStylePath?.let { path -> assertContentEquals(ambientStyleBytes, Files.readAllBytes(path)) }
        assertTrue(processesReaped)
        assertFalse(Files.exists(workspace.resolve(".refactorkit")), "a preview must not create a transaction journal")
    }

    @And("the bounded C ranges preview changes only selected regions and remains idempotent")
    fun boundedRangesChangeOnlySelectedRegions() {
        assertTrue(styleRangeScenario, "the bounded-range fixture must be active")
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        val edit = assertIs<FileEdit.Modify>(plan.workspaceEdit.edits.single())
        assertEquals(Path.of("main.c"), edit.path)
        val formatted = TextEdits.apply(snapshot.files.single().content, edit.textEdits)
        val expected = when (styleSnapshotState) {
            "captured project style and two discontiguous ranges" -> {
                assertEquals(3, processArguments.size, "preview, range mapping, and idempotence each invoke clang-format")
                val expectedArguments = listOf("-lines=3:3", "-lines=5:5")
                assertEquals(expectedArguments, processArguments[0].filter { it.startsWith("-lines=") })
                assertEquals(expectedArguments, processArguments[1].filter { it.startsWith("-lines=") })
                assertTrue("--output-replacements-xml" in processArguments[1])
                assertEquals(listOf("-lines=3:3", "-lines=8:8"), processArguments[2].filter { it.startsWith("-lines=") })
                "#include <z.h>\n#include <a.h>\nint first(void) {\n    int firstValue = 1;\n    return firstValue;\n}\nint between(void){int betweenValue=2; return betweenValue;}\nint second(void) {\n    int secondValue = 3;\n    return secondValue;\n}\nint outside(void){return 4;}\n"
            }
            "captured project style and 32 discontiguous ranges" -> {
                assertEquals(3, processArguments.size, "preview, range mapping, and idempotence each invoke clang-format")
                val expectedArguments = (1..63 step 2).map { "-lines=$it:$it" }
                assertEquals(expectedArguments, processArguments[0].filter { it.startsWith("-lines=") })
                assertEquals(expectedArguments, processArguments[1].filter { it.startsWith("-lines=") })
                assertTrue("--output-replacements-xml" in processArguments[1])
                assertEquals(expectedArguments, processArguments[2].filter { it.startsWith("-lines=") })
                buildString {
                    repeat(66) { index ->
                        if (index < 64 && index % 2 == 0) append("int selected$index = $index;\n")
                        else append("int spacer$index = $index;\n")
                    }
                }
            }
            else -> "#include <z.h>\n#include <a.h>\nint inside(void) {\n    int first = 1;\n    int second = 2;\n    int third = 3;\n    return first + second + third;\n}\nint outside(void){return 2;}\n"
        }
        assertEquals(expected, formatted, "Selected C regions may be reformatted while other source and include order are preserved")
        assertTrue(plan.warnings.any { it.contains("idempotent=true") }, "a managed preview requires an idempotent formatter result")
        assertTrue(plan.requiresUserApproval)
    }

    @Then("formatting source disposition is {string} without an unauthorized proposal")
    fun sourceDisposition(expected: String) {
        if (expected == "formatting preview") {
            assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
            val edit = assertIs<FileEdit.Modify>(plan.workspaceEdit.edits.single())
            assertEquals(Path.of("main.c"), edit.path)
            assertEquals("int main(void) { return 7; }\n", TextEdits.apply(snapshot.files.single().content, edit.textEdits))
            assertTrue(plan.requiresUserApproval)
        } else {
            assertEquals("source refused", expected)
            assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
            assertTrue(plan.summary.contains("source", ignoreCase = true), plan.summary)
            assertTrue(plan.workspaceEdit.edits.isEmpty()); assertTrue(plan.affectedFiles.isEmpty())
            assertFalse(Files.exists(requireNotNull(sandbox).resolve("formatter-ran")), "Source preflight must precede formatter execution")
        }
    }

    @Then("formatting source checks preserve the recorded workspace and reap owned processes")
    fun acquiredSourcePreserved() {
        val current = Files.walk(workspace).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            .toList().associate { workspace.relativize(it) to Files.readAllBytes(it) } }
        assertEquals(observedFiles.keys, current.keys)
        for ((path, bytes) in observedFiles) assertContentEquals(bytes, current[path], path.toString())
        val links = Files.walk(workspace).use { paths -> paths.filter(Files::isSymbolicLink)
            .toList().associate { workspace.relativize(it) to Files.readSymbolicLink(it) } }
        assertEquals(observedLinks, links)
        assertTrue(processesReaped); assertFalse(Files.exists(workspace.resolve(".refactorkit")))
    }

    @Then("the preview is refused for the formatter failure with no edit")
    fun refusedWithoutEdit() {
        assertEquals(PatchStatus.REFUSED, plan.status, "failed formatter stdout must not become a plan")
        assertTrue(plan.summary.contains("clang-format failed"), plan.summary)
        assertTrue(plan.workspaceEdit.edits.isEmpty(), "refusal must have no edits")
        assertTrue(plan.affectedFiles.isEmpty(), "refusal must affect no files")
        assertEquals(1, formatterRequestCount, "the bounded response seam must receive exactly one formatter request")
        assertTrue(runtimeCurrencyCurrent, "native runtime currency must hold around the bounded response seam")
        assertTrue(processesReaped)
    }

    @Then("the C workspace bytes and transaction journal remain unchanged")
    fun noMutation() {
        assertContentEquals(original, Files.readAllBytes(workspace.resolve("main.c")))
        assertFalse(Files.exists(workspace.resolve(".refactorkit")), "preview must not create a WAL or transaction")
    }

    @Then("the formatter receives the no-include-sort option and no include edit is proposed")
    fun includesPreserved() {
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertTrue(plan.workspaceEdit.edits.isEmpty(), "formatFile must not organize includes")
        assertEquals(1, formatterRequestCount, "unchanged output should require exactly one native formatting pass")
        assertTrue(runtimeCurrencyCurrent, "native runtime currency must hold around actual formatting")
        assertTrue(processesReaped)
        assertTrue(processArguments.single().contains("-sort-includes=false"), "clang-format must receive the include-order guard")
    }

    @Then("clang-format receives the acquired source and idempotence output through standard input")
    fun snapshotInputIsFormattedAndIdempotent() {
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertEquals(2, formatterRequestCount, "changed output must be checked by a second formatter pass")
        assertTrue(runtimeCurrencyCurrent, "native formatter runtime must remain current")
        assertTrue(processesReaped)
        val edit = assertIs<FileEdit.Modify>(plan.workspaceEdit.edits.single())
        val formatted = TextEdits.apply(snapshot.files.single().content, edit.textEdits)
        assertTrue(formatted.isNotBlank(), "a nonempty acquired source must yield nonempty formatter output")
        assertTrue(formatted != snapshot.files.single().content, "the native formatter must consume the acquired source")
    }

    @Then("clang-format uses the source path only as assumed filename without creating scratch files")
    fun formatterUsesAssumedFilenameWithoutScratch() {
        val source = workspace.resolve("main.c").toAbsolutePath().normalize().toString()
        assertEquals(2, processArguments.size)
        assertTrue(processArguments.all { "--assume-filename=$source" in it }, "both passes must retain the original source identity")
        assertTrue(processArguments.all { arguments ->
            arguments.filterNot { it.startsWith("--assume-filename=") }.all { it.startsWith("-") }
        }, "formatter must receive no live or temporary file operand: $processArguments")
        assertTrue(processArguments.flatten().none { "refactorkit-clang-format-" in it }, "formatter must not receive a scratch path")
        val current = Files.walk(workspace).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            .toList().associate { workspace.relativize(it) to Files.readAllBytes(it) } }
        assertEquals(observedFiles.keys, current.keys)
        for ((path, bytes) in observedFiles) assertContentEquals(bytes, current[path], path.toString())
        assertFalse(Files.exists(workspace.resolve(".refactorkit")), "a read-only format preview must not create a transaction journal")
    }

    @Then("the formatted C source bytes and transaction journal remain unchanged")
    fun formattedFileUnchanged() = noMutation()

    private fun discoverToolchain(root: Path, reference: Path): ClangSemanticToolchain =
        assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            root,
            reference.resolve("usr/bin/clang-22"),
            reference.resolve("usr/bin/clangd"),
            reference.resolve("usr/bin/clang-format"),
        ))).toolchain

    @After
    fun cleanup() {
        sandbox?.let { dir ->
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
