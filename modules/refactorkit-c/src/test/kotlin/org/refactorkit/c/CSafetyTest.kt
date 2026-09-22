package org.refactorkit.c

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ManagedRollbackExecutor
import org.refactorkit.core.ManagedRollbackOutcome
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RollbackLookupVisibility
import org.refactorkit.core.RollbackMode
import org.refactorkit.core.RollbackPreflightDecision
import org.refactorkit.core.RollbackPreflightGuard
import org.refactorkit.core.SourceFile
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Resource/hostile-input and crash-rollback checks for the bounded C planners. */
class CSafetyTest {
    @Test
    fun refusesOversizedSymbol() {
        val snap = snapshot("static int add(int a, int b) { return a + b; }\nint main(void) { return add(2, 3); }\n")
        val plan = CInlinePlanner().preview(snap, Path.of("main.c"), "a".repeat(600))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesOversizedMapping() {
        val snap = snapshot("int a(void) { return 1; }\n")
        val mapping = buildMap {
            repeat(20_000) { i -> put("sym$i", "new$i") }
        }
        val plan = CRenamePrefixPlanner(toolchain()).preview(snap, mapping)
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun applyThenRollbackRestores() {
        val workspace = Files.createTempDirectory("refactorkit-c-safety")
        val mainC = workspace.resolve("main.c")
        val content = "#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n"
        Files.writeString(mainC, content)
        val snap = ProjectSnapshot(Workspace(workspace), emptyList(), listOf(SourceFile(Path.of("main.c"), content, "c")))
        val plan = COrganizeIncludesPlanner().preview(snap, Path.of("main.c"))
        assertEquals(PatchStatus.PREVIEW, plan.status)

        val applied = PatchEngine(workspace).apply(plan, snap, ApplyAuthorization.explicit("c-safety"), DiagnosticsGate.disabled("c-safety"))
        val transaction = (applied as? ApplyResult.Applied)?.transaction
        assertTrue(transaction != null, "apply should produce a transaction")
        val afterContent = Files.readString(mainC)
        assertTrue(afterContent != content, "file should change after apply")

        val log = TransactionLog(workspace.resolve(".refactorkit/transactions"))
        val outcome = ManagedRollbackExecutor(log, PatchEngine(workspace)).execute(
            transaction.id.value,
            RollbackLookupVisibility.JOURNAL_RECORD,
            RollbackMode.NORMAL,
            RollbackPreflightGuard<Nothing> { RollbackPreflightDecision.Allow },
        )
        assertTrue(outcome is ManagedRollbackOutcome.RolledBack, "rollback should succeed")
        assertEquals(content, Files.readString(mainC), "file should be restored after rollback")
    }

    @Test
    fun refusesApplyWithoutApprovalWritesNoJournalAndKeepsWorkspace() {
        val workspace = Files.createTempDirectory("refactorkit-c-prewal")
        val mainC = workspace.resolve("main.c")
        val content = "#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n"
        Files.writeString(mainC, content)
        val snap = ProjectSnapshot(
            Workspace(workspace), emptyList(), listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        val plan = COrganizeIncludesPlanner().preview(snap, Path.of("main.c"))
        assertEquals(PatchStatus.PREVIEW, plan.status)

        val result = PatchEngine(workspace).apply(
            plan, snap, ApplyAuthorization.missing("c-safety"), DiagnosticsGate.disabled("c-safety"),
        )
        assertTrue(result is ApplyResult.Refused, "apply without approval must be refused")
        // Pre-WAL refusal: no journal record is written and no workspace byte changes.
        val log = TransactionLog(workspace.resolve(".refactorkit/transactions"))
        assertTrue(log.listRecords().isEmpty(), "pre-WAL refusal must not write a journal record")
        assertEquals(content, Files.readString(mainC), "pre-WAL refusal must not touch the workspace")
    }

    @Test
    fun detectsRollbackConflictAfterExternalMutation() {
        val workspace = Files.createTempDirectory("refactorkit-c-conflict")
        val mainC = workspace.resolve("main.c")
        val content = "#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n"
        Files.writeString(mainC, content)
        val snap = ProjectSnapshot(
            Workspace(workspace), emptyList(), listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        val plan = COrganizeIncludesPlanner().preview(snap, Path.of("main.c"))
        val engine = PatchEngine(workspace)
        val applied = engine.apply(
            plan, snap, ApplyAuthorization.explicit("c-safety"), DiagnosticsGate.disabled("c-safety"),
        )
        val transaction = (applied as? ApplyResult.Applied)?.transaction
        assertTrue(transaction != null, "apply should succeed")

        // A consumer edits the file after apply; the post-image no longer matches.
        val externalMutation = "// edited by hand\n" + Files.readString(mainC)
        Files.writeString(mainC, externalMutation)

        val log = TransactionLog(workspace.resolve(".refactorkit/transactions"))
        val outcome = ManagedRollbackExecutor(log, engine).execute(
            transaction.id.value,
            RollbackLookupVisibility.JOURNAL_RECORD,
            RollbackMode.NORMAL,
            RollbackPreflightGuard<Nothing> { RollbackPreflightDecision.Allow },
        )
        // A real conflict is refused, not a nominal restore that overwrites the edit.
        assertTrue(outcome is ManagedRollbackOutcome.Refused, "rollback must detect the post-image conflict")
        assertNotEquals(content, Files.readString(mainC), "conflicting rollback must not silently restore stale bytes")
        assertEquals(externalMutation, Files.readString(mainC), "the external mutation must be preserved after a refused rollback")
    }

    private fun snapshot(content: String) = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("main.c"), content, "c")),
    )

    private fun toolchain() = ClangSemanticToolchain(
        clangExecutable = Path.of("/usr/bin/clang"),
        clangdExecutable = Path.of("/usr/bin/clangd"),
        clangFormatExecutable = Path.of("/usr/bin/clang-format"),
        provenance = ClangToolchainProvenance(
            clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
            targetTriple = "x86_64-pc-linux-gnu", resourceDir = "/usr/lib/clang/22", evidence = emptyList(),
        ),
    )
}
