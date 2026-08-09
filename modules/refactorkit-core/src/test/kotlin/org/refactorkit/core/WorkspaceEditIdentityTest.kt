package org.refactorkit.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkspaceEditIdentityTest {
    private val zero = SourcePosition(0, 0)
    private val one = SourcePosition(0, 1)

    @Test
    fun identityCanonicalizesPathsAndConsecutiveModifyEntries() {
        val split = WorkspaceEdit(listOf(
            FileEdit.Modify(Path.of("src/./A.java"), listOf(TextEdit(SourceRange(zero, zero), "a"))),
            FileEdit.Modify(Path.of("src/A.java"), listOf(TextEdit(SourceRange(one, one), "b"))),
        ))
        val combined = WorkspaceEdit(listOf(
            FileEdit.Modify(Path.of("src/A.java"), listOf(
                TextEdit(SourceRange(zero, zero), "a"),
                TextEdit(SourceRange(one, one), "b"),
            )),
        ))

        val identity = WorkspaceEditIdentity.sha256(split)

        assertEquals(identity, WorkspaceEditIdentity.sha256(combined))
        assertTrue(Regex("[a-f0-9]{64}").matches(identity))
    }

    @Test
    fun identityBindsFileOrderTypeRangeTextAndOverwritePolicy() {
        val modify = FileEdit.Modify(
            Path.of("src/A.java"),
            listOf(TextEdit(SourceRange(zero, one), "B")),
        )
        val rename = FileEdit.Rename(Path.of("src/A.java"), Path.of("src/B.java"))
        val baseline = WorkspaceEditIdentity.sha256(WorkspaceEdit(listOf(modify, rename)))

        assertNotEquals(baseline, WorkspaceEditIdentity.sha256(WorkspaceEdit(listOf(rename, modify))))
        assertNotEquals(
            baseline,
            WorkspaceEditIdentity.sha256(WorkspaceEdit(listOf(
                modify.copy(textEdits = listOf(TextEdit(SourceRange(zero, zero), "B"))),
                rename,
            ))),
        )
        assertNotEquals(
            baseline,
            WorkspaceEditIdentity.sha256(WorkspaceEdit(listOf(
                modify.copy(textEdits = listOf(TextEdit(SourceRange(zero, one), "C"))),
                rename,
            ))),
        )
        assertNotEquals(
            WorkspaceEditIdentity.sha256(WorkspaceEdit(listOf(FileEdit.Delete(Path.of("src/A.java"))))),
            WorkspaceEditIdentity.sha256(WorkspaceEdit(listOf(FileEdit.Create(Path.of("src/A.java"), "")))),
        )
        assertNotEquals(
            WorkspaceEditIdentity.sha256(WorkspaceEdit(listOf(FileEdit.Create(Path.of("src/A.java"), "x")))),
            WorkspaceEditIdentity.sha256(WorkspaceEdit(listOf(FileEdit.Create(Path.of("src/A.java"), "x", overwrite = true)))),
        )
    }
}
