package org.refactorkit.java

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaFormatFilePlannerTest {
    @Test
    fun formatsCompilationUnitAndIsIdempotent() {
        val root = project("package example;\npublic class Example{public void run(){System.out.println(\"ok\");}}\n")
        val path = Path.of("src/main/java/example/Example.java")
        val snapshot = JavaProjectScanner().scan(root)

        val plan = JavaFormatFilePlanner().preview(snapshot, path)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertEquals(setOf(path), plan.affectedFiles)
        val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
        val formatted = staged.files.single().content
        assertTrue(formatted.contains("public class Example {"), formatted)
        assertTrue(formatted.contains("public void run() {"), formatted)
        assertTrue(formatted.contains("System.out.println(\"ok\");"), formatted)

        val second = JavaFormatFilePlanner().preview(staged, path)
        assertEquals(PatchStatus.PREVIEW, second.status)
        assertTrue(second.affectedFiles.isEmpty(), second.summary)
        assertTrue(second.workspaceEdit.edits.isEmpty())
    }

    @Test
    fun formatterUsesBuildSourceSetLevelWithoutCompatibilityOwnership() {
        val root = project("package example;\npublic record Example(String value){}\n")
        root.resolve("pom.xml").writeText(
            "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId>" +
                "<artifactId>example</artifactId><version>1</version>" +
                "<properties><maven.compiler.release>21</maven.compiler.release></properties></project>",
        )
        val path = Path.of("src/main/java/example/Example.java")
        val snapshot = JavaProjectScanner().scan(root)
        val compatibilityStripped = snapshot.copy(modules = snapshot.modules.map { module ->
            module.copy(sourceRoots = emptyList(), mainSourceRoots = emptyList(), testSourceRoots = emptyList())
        })

        val plan = JavaFormatFilePlanner().preview(compatibilityStripped, path)

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        val formatted = WorkspaceEditSimulator.apply(compatibilityStripped, plan.workspaceEdit).files.single().content
        assertTrue(formatted.contains("record Example(String value) {"), formatted)
    }

    @Test
    fun honorsHashBoundEclipseProjectPreferences() {
        val root = project("package example;\npublic class Example{void run(){int x=1;}}\n")
        val settings = root.resolve(".settings/org.eclipse.jdt.core.prefs")
        Files.createDirectories(settings.parent)
        settings.writeText(
            "org.eclipse.jdt.core.formatter.tabulation.char=space\n" +
                "org.eclipse.jdt.core.formatter.tabulation.size=2\n" +
                "org.eclipse.jdt.core.formatter.indentation.size=2\n",
        )
        val path = Path.of("src/main/java/example/Example.java")
        val snapshot = JavaProjectScanner().scan(root)

        val plan = JavaFormatFilePlanner().preview(snapshot, path)
        val formatted = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files.single().content

        assertTrue(formatted.lines().any { it == "  void run() {" }, formatted)
        assertTrue(plan.warnings.any { it.contains(".settings/org.eclipse.jdt.core.prefs") })
        assertTrue(snapshot.classpathEvidence.any {
            it.path.toString().replace('\\', '/').endsWith(".settings/org.eclipse.jdt.core.prefs")
        })
    }

    @Test
    fun preservesBomAndCrLf() {
        val root = project("\uFEFFpackage example;\r\npublic class Example{void run(){}}\r\n")
        val path = Path.of("src/main/java/example/Example.java")
        val snapshot = JavaProjectScanner().scan(root)

        val plan = JavaFormatFilePlanner().preview(snapshot, path)
        val formatted = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files.single().content

        assertTrue(formatted.startsWith('\uFEFF'))
        assertTrue(formatted.contains("\r\n"))
        assertTrue(!formatted.replace("\r\n", "").contains('\n'))
    }

    @Test
    fun refusesGeneratedAndSyntacticallyInvalidSources() {
        val generatedRoot = project("// GENERATED - DO NOT EDIT\npackage example;\npublic class Example{}\n")
        val path = Path.of("src/main/java/example/Example.java")
        val generated = JavaFormatFilePlanner().preview(JavaProjectScanner().scan(generatedRoot), path)
        assertEquals(PatchStatus.REFUSED, generated.status)

        val invalidRoot = project("package example;\npublic class Example { void run( }\n")
        val invalid = JavaFormatFilePlanner().preview(JavaProjectScanner().scan(invalidRoot), path)
        assertEquals(PatchStatus.REFUSED, invalid.status)
        assertTrue(invalid.diagnosticsBefore.isNotEmpty())
    }

    private fun project(content: String): Path {
        val root = Files.createTempDirectory("refactorkit-format-test")
        val file = root.resolve("src/main/java/example/Example.java")
        Files.createDirectories(file.parent)
        file.writeText(content)
        return root
    }
}
