package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OperationAuthorityLeaseTest {
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
        val plan = PatchPlan(
            operation = "moveClass",
            snapshotHash = snapshot.hash,
            confidence = 0.94,
            summary = "leased edit",
            affectedFiles = setOf(sourcePath),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(
                sourcePath,
                listOf(TextEdit(
                    SourceRange(SourcePosition(0, 6), SourcePosition(0, 7)),
                    "B",
                )),
            ))),
            evidence = RefactoringEvidence.JDT_BINDING,
            authorityLease = OperationAuthorityLease(
                kind = "java.maven.moveClass.targetScoped.v1",
                operation = "moveClass",
                snapshotHash = snapshot.hash,
                evidenceHash = "0".repeat(64),
                requiredClasspathEvidence = listOf(requiredAbsence),
            ),
        )

        val result = assertIs<ApplyResult.Refused>(PatchEngine(root).apply(plan, snapshot))

        assertTrue(result.diagnostics.any { it.code == "authorityLease.evidenceMissing" }, result.toString())
        assertEquals(source, Files.readString(root.resolve(sourcePath)))
        assertTrue(Files.notExists(root.resolve(".refactorkit/transactions")))
    }
}
