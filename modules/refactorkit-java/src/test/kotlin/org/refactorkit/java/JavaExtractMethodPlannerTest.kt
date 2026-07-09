package org.refactorkit.java

import org.refactorkit.core.ApplyResult
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.RiskLevel
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaExtractMethodPlannerTest {

    private val planner = JavaExtractMethodPlanner()

    @Test
    fun extractsSimpleStatementsIntoPrivateVoidMethod() {
        val root = tempProject(
            "src/main/java/com/example/App.java" to """
                package com.example;

                public class App {
                    public void run() {
                        System.out.println("a");
                        System.out.println("b");
                    }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, Paths.get("src/main/java/com/example/App.java"), 5, 6, "printMessages")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertEquals(RiskLevel.MEDIUM, plan.riskLevel)
        assertTrue(plan.summary.contains("printMessages"))

        val result = PatchEngine(root).apply(plan, snap.hash)
        assertIs<ApplyResult.Applied>(result)
        val content = root.resolve("src/main/java/com/example/App.java").readText()
        assertTrue(content.contains("printMessages();"), content)
        assertTrue(content.contains("private void printMessages()"), content)
        assertTrue(content.contains("System.out.println(\"a\");"), content)
        assertTrue(content.contains("System.out.println(\"b\");"), content)
    }

    @Test
    fun refusesReturnStatement() {
        val root = tempProject(
            "src/main/java/com/example/App.java" to """
                package com.example;
                public class App {
                    public int run() {
                        return 1;
                    }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, Paths.get("src/main/java/com/example/App.java"), 4, 4, "compute")

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("return"))
    }

    @Test
    fun refusesSelectionUsingParameter() {
        val root = tempProject(
            "src/main/java/com/example/App.java" to """
                package com.example;
                public class App {
                    public void run(String name) {
                        System.out.println(name);
                    }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, Paths.get("src/main/java/com/example/App.java"), 4, 4, "printName")

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("name"), plan.summary)
        assertTrue(plan.summary.contains("Parameter extraction", ignoreCase = true), plan.summary)
    }

    @Test
    fun refusesSelectionUsingPriorLocalVariable() {
        val root = tempProject(
            "src/main/java/com/example/App.java" to """
                package com.example;
                public class App {
                    public void run() {
                        String name = "Ada";
                        System.out.println(name);
                    }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, Paths.get("src/main/java/com/example/App.java"), 5, 5, "printName")

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("name"), plan.summary)
    }

    @Test
    fun refusesDeclaredVariableUsedAfterSelection() {
        val root = tempProject(
            "src/main/java/com/example/App.java" to """
                package com.example;
                public class App {
                    public void run() {
                        String name = "Ada";
                        System.out.println(name);
                    }
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, Paths.get("src/main/java/com/example/App.java"), 4, 4, "createName")

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("used after", ignoreCase = true), plan.summary)
    }

    @Test
    fun refusesExistingMethodName() {
        val root = tempProject(
            "src/main/java/com/example/App.java" to """
                package com.example;
                public class App {
                    public void run() {
                        System.out.println("a");
                    }
                    private void extracted() {}
                }
            """.trimIndent() + "\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, Paths.get("src/main/java/com/example/App.java"), 4, 4, "extracted")

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("already"))
    }

    @Test
    fun refusesInvalidMethodName() {
        val root = tempProject(
            "src/main/java/com/example/App.java" to "package com.example;\npublic class App { void run() {} }\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, Paths.get("src/main/java/com/example/App.java"), 1, 1, "123bad")

        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    private fun tempProject(vararg entries: Pair<String, String>): java.nio.file.Path {
        val root = Files.createTempDirectory("extract-method-test")
        for ((rel, content) in entries) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return root
    }
}
