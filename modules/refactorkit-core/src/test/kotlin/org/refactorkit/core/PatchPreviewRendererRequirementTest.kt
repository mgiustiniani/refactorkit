package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class PatchPreviewRendererRequirementTest {
    @Test
    fun rendersModifyAfterRenameAgainstTheStagedFileImage() {
        val root = Files.createTempDirectory("rk-preview-staged")
        val oldPath = Path.of("src/main/java/com/old/Large.java")
        val newPath = Path.of("src/main/java/com/newpkg/Large.java")
        val before = (1..500).joinToString("\n", postfix = "\n") { "// line $it" }
        val after = before.replace("// line 478", "package com.newpkg;")
        root.resolve(oldPath).apply { Files.createDirectories(parent); writeText(before) }
        val edit = WorkspaceEdit(listOf(
            FileEdit.Rename(oldPath, newPath),
            FileEdit.Modify(newPath, listOf(TextEdit(
                SourceRange(SourcePosition(0, 0), TextEdits.positionForOffset(before, before.length)), after,
            ))),
        ))
        val plan = PatchPlan(
            operation = "requirement.preview-staged-offsets",
            snapshotHash = "0".repeat(64),
            summary = "render staged offsets",
            confidence = 1.0,
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
        )

        val rendered = PatchPreviewRenderer(root).render(plan)

        assertTrue(rendered.contains("package com.newpkg;"))
        assertTrue(rendered.contains(
            "rename ${ProtocolPath.serialize(oldPath)} -> ${ProtocolPath.serialize(newPath)}",
        ))
    }
}
