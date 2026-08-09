package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OperationAuthorityLeaseTest {
    @Test
    fun underLockApplyRejectsWorkspaceEditAddedAfterSemanticPreview() {
        val fixture = semanticLeaseFixture("edit-mismatch")
        val widenedEdit = WorkspaceEdit(listOf(FileEdit.Modify(
            fixture.sourcePath,
            listOf(TextEdit(
                SourceRange(SourcePosition(0, 6), SourcePosition(0, 7)),
                "C",
            )),
        )))

        val result = PatchEngine(fixture.root).apply(
            fixture.plan.copy(workspaceEdit = widenedEdit),
            fixture.snapshot,
        )

        val refused = assertIs<ApplyResult.Refused>(result)
        assertEquals("authorityLease.workspaceEditMismatch", refused.diagnostics.first().code)
        assertEquals(fixture.source, Files.readString(fixture.root.resolve(fixture.sourcePath)))
        assertTrue(Files.notExists(fixture.root.resolve(".refactorkit/transactions")))
    }

    @Test
    fun underLockApplyRejectsTruncatedSemanticEvidence() {
        val fixture = semanticLeaseFixture(
            "truncated",
            OperationAuthorityEvidenceCompleteness.TRUNCATED,
        )

        val result = PatchEngine(fixture.root).apply(fixture.plan, fixture.snapshot)

        val refused = assertIs<ApplyResult.Refused>(result)
        assertEquals("authorityLease.evidenceIncomplete", refused.diagnostics.first().code)
        assertEquals(fixture.source, Files.readString(fixture.root.resolve(fixture.sourcePath)))
        assertTrue(Files.notExists(fixture.root.resolve(".refactorkit/transactions")))
    }

    @Test
    fun underLockApplyRequiresEveryLeasedClasspathPresenceOrAbsenceRecord() {
        val root = Files.createTempDirectory("refactorkit-operation-authority-")
        val sourcePath = Path.of("src/A.java")
        val source = "class A {}\n"
        Files.createDirectories(root.resolve(sourcePath).parent)
        Files.writeString(root.resolve(sourcePath), source)
        val snapshot = ProjectSnapshot(
            workspace = Workspace(root),
            modules = listOf(Module("app", root, sourceRoots = listOf(Path.of("src")))),
            files = listOf(SourceFile(sourcePath, source, "java")),
        )
        val requiredAbsence = ClasspathEvidence(
            Path.of("fixture-libs/missing.jar"),
            ClasspathEvidenceKind.SYSTEM_PATH_ARTIFACT,
            "missing",
        )
        val edit = WorkspaceEdit(listOf(FileEdit.Modify(
            sourcePath,
            listOf(TextEdit(
                SourceRange(SourcePosition(0, 6), SourcePosition(0, 7)),
                "B",
            )),
        )))
        val plan = PatchPlan(
            operation = "moveClass",
            snapshotHash = snapshot.hash,
            confidence = 0.94,
            summary = "leased edit",
            affectedFiles = setOf(sourcePath),
            workspaceEdit = edit,
            evidence = RefactoringEvidence.JDT_BINDING,
            authorityLease = OperationAuthorityLease(
                kind = "java.maven.moveClass.targetScoped.v1",
                operation = "moveClass",
                snapshotHash = snapshot.hash,
                evidenceHash = "0".repeat(64),
                workspaceEditSha256 = WorkspaceEditIdentity.sha256(edit),
                requiredClasspathEvidence = listOf(requiredAbsence),
            ),
        )

        val result = assertIs<ApplyResult.Refused>(PatchEngine(root).apply(plan, snapshot))

        assertTrue(result.diagnostics.any { it.code == "authorityLease.evidenceMissing" }, result.toString())
        assertEquals(source, Files.readString(root.resolve(sourcePath)))
        assertTrue(Files.notExists(root.resolve(".refactorkit/transactions")))
    }

    private fun semanticLeaseFixture(
        suffix: String,
        evidenceCompleteness: OperationAuthorityEvidenceCompleteness =
            OperationAuthorityEvidenceCompleteness.COMPLETE,
    ): SemanticLeaseFixture {
        val root = Files.createTempDirectory("refactorkit-operation-authority-$suffix-")
        val sourcePath = Path.of("src/A.java")
        val source = "class A {}\n"
        Files.createDirectories(root.resolve(sourcePath).parent)
        Files.writeString(root.resolve(sourcePath), source)
        val snapshot = ProjectSnapshot(
            workspace = Workspace(root),
            modules = listOf(Module("app", root, sourceRoots = listOf(Path.of("src")))),
            files = listOf(SourceFile(sourcePath, source, "java")),
        )
        val edit = WorkspaceEdit(listOf(FileEdit.Modify(
            sourcePath,
            listOf(TextEdit(
                SourceRange(SourcePosition(0, 6), SourcePosition(0, 7)),
                "B",
            )),
        )))
        val plan = PatchPlan(
            operation = "moveClass",
            snapshotHash = snapshot.hash,
            confidence = 0.94,
            summary = "semantic lease",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
            evidence = RefactoringEvidence.JDT_BINDING,
            authorityLease = OperationAuthorityLease(
                kind = "java.maven.moveClass.targetScoped.v1",
                operation = "moveClass",
                snapshotHash = snapshot.hash,
                evidenceHash = "0".repeat(64),
                evidenceCompleteness = evidenceCompleteness,
                workspaceEditSha256 = WorkspaceEditIdentity.sha256(edit),
            ),
        )
        return SemanticLeaseFixture(root, sourcePath, source, snapshot, plan)
    }

    private data class SemanticLeaseFixture(
        val root: Path,
        val sourcePath: Path,
        val source: String,
        val snapshot: ProjectSnapshot,
        val plan: PatchPlan,
    )
}
