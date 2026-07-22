package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkspaceEditSimulatorTest {
    @Test
    fun usesOneOriginalCoordinateSpaceForSeparateModifyEntries() {
        val root = Files.createTempDirectory("refactorkit-simulator")
        val path = Path.of("Example.java")
        val snapshot = ProjectSnapshot(
            Workspace(root),
            emptyList(),
            listOf(SourceFile(path, "alpha beta gamma\n", "java")),
        )
        val edit = WorkspaceEdit(listOf(
            FileEdit.Modify(path, listOf(TextEdit(TextEdits.rangeForOffset("alpha beta gamma\n", 0, 5), "a"))),
            FileEdit.Modify(path, listOf(TextEdit(TextEdits.rangeForOffset("alpha beta gamma\n", 11, 5), "g"))),
        ))

        val staged = WorkspaceEditSimulator.apply(snapshot, edit)

        assertEquals("a beta g\n", staged.files.single().content)
        assertEquals("alpha beta gamma\n", snapshot.files.single().content)
    }

    @Test
    fun stagesAuxiliaryWorkspaceFilesWithoutAddingThemToLanguageSourceScope() {
        val root = Files.createTempDirectory("refactorkit-simulator-auxiliary")
        val javaPath = Path.of("src/main/java/Example.java")
        val pomPath = Path.of("pom.xml")
        val java = "class Example {}\n"
        val pom = "<project><artifactId>old</artifactId><!-- keep --></project>\n"
        val snapshot = ProjectSnapshot(
            workspace = Workspace(root),
            modules = emptyList(),
            files = listOf(SourceFile(javaPath, java, "java")),
            sourceExtensions = setOf("java"),
            auxiliaryFiles = listOf(SourceFile(pomPath, pom, "maven-pom")),
        )
        val edit = WorkspaceEdit(listOf(
            FileEdit.Rename(javaPath, Path.of("destination/src/main/java/Example.java")),
            FileEdit.Modify(
                pomPath,
                listOf(TextEdit(TextEdits.rangeForOffset(pom, pom.indexOf("old"), 3), "new")),
            ),
        ))

        val staged = WorkspaceEditSimulator.apply(snapshot, edit)

        assertEquals(setOf("java"), staged.sourceExtensions)
        assertEquals(1, staged.files.size)
        assertEquals("<project><artifactId>new</artifactId><!-- keep --></project>\n", staged.auxiliaryFiles.single().content)
        assertEquals("maven-pom", staged.auxiliaryFiles.single().languageId)
        assertEquals(2, staged.trackedFiles.size)
        kotlin.test.assertNotEquals(snapshot.hash, staged.hash)
    }

    @Test
    fun rejectsUnsafeOrOverlappingAuxiliaryWorkspacePaths() {
        val root = Files.createTempDirectory("refactorkit-simulator-auxiliary-invalid")
        val source = SourceFile(Path.of("pom.xml"), "source", "xml")
        assertFailsWith<IllegalArgumentException> {
            ProjectSnapshot(
                Workspace(root),
                emptyList(),
                listOf(source),
                auxiliaryFiles = listOf(SourceFile(Path.of("pom.xml"), "auxiliary", "maven-pom")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectSnapshot(
                Workspace(root),
                emptyList(),
                emptyList(),
                auxiliaryFiles = listOf(SourceFile(Path.of("../pom.xml"), "escape", "maven-pom")),
            )
        }
    }

    @Test
    fun appliesStructuralStepsToAnEvolvingSnapshot() {
        val root = Files.createTempDirectory("refactorkit-simulator")
        val source = Path.of("Old.java")
        val target = Path.of("new/New.java")
        val snapshot = ProjectSnapshot(
            Workspace(root),
            emptyList(),
            listOf(SourceFile(source, "class Old {}\n", "java")),
        )

        val renamed = WorkspaceEditSimulator.apply(
            snapshot,
            WorkspaceEdit(listOf(FileEdit.Rename(source, target))),
        )
        val modified = WorkspaceEditSimulator.apply(
            renamed,
            WorkspaceEdit(listOf(FileEdit.Modify(
                target,
                listOf(TextEdit(TextEdits.rangeForOffset("class Old {}\n", 6, 3), "New")),
            ))),
        )

        assertEquals(target, modified.files.single().path)
        assertEquals("class New {}\n", modified.files.single().content)
    }

    @Test
    fun rejectsCrossEntryOverlapsBeforeFilesystemApply() {
        val root = Files.createTempDirectory("refactorkit-simulator")
        val path = Path.of("Example.java")
        val content = "abcdef\n"
        val snapshot = ProjectSnapshot(
            Workspace(root),
            emptyList(),
            listOf(SourceFile(path, content, "java")),
        )

        assertFailsWith<IllegalArgumentException> {
            WorkspaceEditSimulator.apply(
                snapshot,
                WorkspaceEdit(listOf(
                    FileEdit.Modify(path, listOf(TextEdit(TextEdits.rangeForOffset(content, 1, 3), "x"))),
                    FileEdit.Modify(path, listOf(TextEdit(TextEdits.rangeForOffset(content, 2, 3), "y"))),
                )),
            )
        }
    }
}
