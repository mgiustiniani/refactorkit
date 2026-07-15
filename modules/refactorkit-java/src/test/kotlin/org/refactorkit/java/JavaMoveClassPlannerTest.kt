package org.refactorkit.java

import org.refactorkit.core.ApplyResult
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaMoveClassPlannerTest {

    private val planner = JavaMoveClassPlanner(JavaLanguageAdapter())

    @Test
    fun jdtMoveScopesImportsToBindingMatchedFiles() {
        val root = createProject(
            "src/main/java/com/old/Service.java" to """
                package com.old;
                public class Service {}
            """.trimIndent(),
            "src/main/java/com/old/Used.java" to """
                package com.old;
                class Used { Service service = new Service(); }
            """.trimIndent(),
            "src/main/java/com/old/Unused.java" to """
                package com.old;
                class Unused { String value; }
            """.trimIndent(),
            "src/main/java/com/target/TargetClient.java" to """
                package com.target;
                import com.old.Service;
                class TargetClient { Service service; }
            """.trimIndent(),
            "src/main/java/com/other/Service.java" to """
                package com.other;
                public class Service {}
            """.trimIndent(),
            "src/main/java/com/client/OtherClient.java" to """
                package com.client;
                import com.other.Service;
                class OtherClient { Service service = new Service(); }
            """.trimIndent(),
        )
        val snapshot = JavaProjectScanner().scan(root)

        val plan = planner.preview(snapshot, "com.old.Service", "com.target")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.warnings.any { it.contains("JDT type binding selected") }, plan.warnings.toString())
        val result = PatchEngine(root).apply(plan, snapshot)
        assertIs<ApplyResult.Applied>(result, result.toString())

        assertFalse(Files.exists(root.resolve("src/main/java/com/old/Service.java")))
        assertTrue(Files.exists(root.resolve("src/main/java/com/target/Service.java")))
        assertTrue(root.resolve("src/main/java/com/target/Service.java").readText().contains("package com.target;"))
        val used = root.resolve("src/main/java/com/old/Used.java").readText()
        assertTrue(used.contains("import com.target.Service;"), used)
        val unused = root.resolve("src/main/java/com/old/Unused.java").readText()
        assertFalse(unused.contains("import com.target.Service;"), unused)
        val targetClient = root.resolve("src/main/java/com/target/TargetClient.java").readText()
        assertFalse(targetClient.contains("import com.old.Service;"), targetClient)
        val otherClient = root.resolve("src/main/java/com/client/OtherClient.java").readText()
        assertTrue(otherClient.contains("import com.other.Service;"), otherClient)
    }

    @Test
    fun moveRefusesInvalidPackageAndExistingTarget() {
        val root = createProject(
            "src/main/java/com/old/Service.java" to "package com.old;\npublic class Service {}",
            "src/main/java/com/target/Service.java" to "package com.target;\npublic class Service {}",
        )
        val snapshot = JavaProjectScanner().scan(root)

        assertEquals(PatchStatus.REFUSED, planner.preview(snapshot, "com.old.Service", "bad-package").status)
        val conflict = planner.preview(snapshot, "com.old.Service", "com.target")
        assertEquals(PatchStatus.REFUSED, conflict.status)
        assertTrue(conflict.summary.contains("already exists"), conflict.summary)
    }

    @Test
    fun lexicalFallbackLeavesUnrelatedSamePackageFilesByteIdenticalAndJavaWellFormed() {
        val unrelated = "package com.old;\nclass Unrelated { String value; }\n"
        val root = createProject(
            "src/main/java/com/old/Service.java" to "package com.old;\npublic class Service { MissingDependency dependency; }",
            "src/main/java/com/old/Unrelated.java" to unrelated.trimEnd(),
            "src/main/java/com/client/Client.java" to
                "package com.client;\nimport com.old.Service;\nclass Client { Service value; }",
        )
        val snapshot = JavaProjectScanner().scan(root)
        val plan = planner.preview(snapshot, "com.old.Service", "com.target")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
        assertEquals(unrelated, staged.files.single { it.path == Path.of("src/main/java/com/old/Unrelated.java") }.content)
        assertTrue(staged.files.filter { it.languageId == "java" }.none { it.content.contains(";import ") })
    }

    @Test
    fun lexicalFallbackRefusesUnprovenSamePackageSimpleNameReferences() {
        val root = createProject(
            "src/main/java/com/old/Service.java" to "package com.old;\npublic class Service { MissingDependency dependency; }",
            "src/main/java/com/old/Used.java" to "package com.old;\nclass Used { Service value; }",
        )
        val plan = planner.preview(JavaProjectScanner().scan(root), "com.old.Service", "com.target")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("java.moveClass.lexicalScopeUnsafe", plan.refusalCode)
    }

    @Test
    fun moveWarnsWhenJdtFallsBackToLexicalScoping() {
        val root = createProject(
            "src/main/java/com/old/Service.java" to """
                package com.old;
                public class Service { MissingDependency dependency; }
            """.trimIndent(),
        )

        val plan = planner.preview(JavaProjectScanner().scan(root), "com.old.Service", "com.target")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.warnings.any { it.contains("lexical file scoping") }, plan.warnings.toString())
    }

    private fun createProject(vararg files: Pair<String, String>): Path {
        val root = Files.createTempDirectory("rk-move-class-test")
        files.forEach { (relative, content) ->
            root.resolve(relative).apply {
                Files.createDirectories(parent)
                writeText(content + "\n")
            }
        }
        return root
    }
}
