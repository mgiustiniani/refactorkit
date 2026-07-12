package org.refactorkit.java

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaGeneratedSourcePolicyTest {
    @Test
    fun detectsGeneratedPathsAnnotationsAndHeaders() {
        assertNotNull(JavaGeneratedSourcePolicy.reason(javaFile(
            Path.of("target/generated-sources/example/Generated.java"),
            "package example; class Generated {}",
        )))
        assertNotNull(JavaGeneratedSourcePolicy.reason(javaFile(
            Path.of("src/main/java/example/Annotated.java"),
            "package example; @javax.annotation.processing.Generated(\"tool\") class Annotated {}",
        )))
        assertNotNull(JavaGeneratedSourcePolicy.reason(javaFile(
            Path.of("src/main/java/example/Header.java"),
            "// AUTO-GENERATED - DO NOT EDIT\npackage example; class Header {}",
        )))
        assertEquals(null, JavaGeneratedSourcePolicy.reason(javaFile(
            Path.of("src/main/java/example/UserCode.java"),
            "package example; class UserCode {}",
        )))
    }

    @Test
    fun allStableFileRewritersRefuseGeneratedDeclarations() {
        val root = Files.createTempDirectory("refactorkit-generated-policy")
        val path = Path.of("target/generated-sources/example/Generated.java")
        val file = javaFile(
            path,
            """
                package example;
                import java.util.List;
                import java.util.ArrayList;
                public class Generated {
                    void work() {
                        System.out.println("generated");
                    }
                }
            """.trimIndent() + "\n",
        )
        val snapshot = ProjectSnapshot(
            workspace = Workspace(root),
            modules = emptyList(),
            files = listOf(file),
            sourceExtensions = setOf("java"),
        )
        val adapter = JavaLanguageAdapter()
        val plans = listOf(
            JavaRenameClassPlanner(adapter).preview(snapshot, "example.Generated", "Renamed"),
            JavaMoveClassPlanner(adapter).preview(snapshot, "example.Generated", "other"),
            JavaRenameMemberPlanner(adapter).preview(snapshot, "example.Generated#work()", "renamedWork"),
            JavaSafeDeletePlanner(adapter).preview(snapshot, "example.Generated", force = true),
            JavaExtractMethodPlanner().preview(snapshot, path, 6, 6, "extracted"),
            JavaOrganizeImportsPlanner().previewSingleFile(snapshot, path),
        )

        plans.forEach { plan ->
            assertEquals(PatchStatus.REFUSED, plan.status, "${plan.operation}: ${plan.summary}")
            assertTrue(plan.summary.contains("Generated source", ignoreCase = true), plan.summary)
            assertTrue(plan.workspaceEdit.edits.isEmpty())
        }
    }

    private fun javaFile(path: Path, content: String): SourceFile = SourceFile(path, content, "java")
}
