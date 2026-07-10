package org.refactorkit.java

import org.refactorkit.core.PatchStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaSafeDeletePlannerTest {

    private val planner = JavaSafeDeletePlanner(JavaLanguageAdapter())

    @Test
    fun jdtTypeBindingRefusesImportedReference() {
        val root = createProject(
            "src/main/java/com/example/Legacy.java" to """
                package com.example;
                public class Legacy {}
            """.trimIndent(),
            "src/main/java/com/client/App.java" to """
                package com.client;
                import com.example.Legacy;
                public class App {
                    private Legacy legacy = new Legacy();
                }
            """.trimIndent(),
        )

        val plan = planner.preview(JavaProjectScanner().scan(root), "com.example.Legacy")

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("JDT binding evidence"), plan.summary)
        assertTrue(plan.summary.contains("App.java"), plan.summary)
    }

    @Test
    fun jdtTypeBindingDoesNotConfuseSameSimpleType() {
        val root = createProject(
            "src/main/java/com/acme/left/Service.java" to """
                package com.acme.left;
                public class Service {}
            """.trimIndent(),
            "src/main/java/com/acme/right/Service.java" to """
                package com.acme.right;
                public class Service {}
            """.trimIndent(),
            "src/main/java/com/acme/app/App.java" to """
                package com.acme.app;
                import com.acme.right.Service;
                public class App {
                    private Service service = new Service();
                }
            """.trimIndent(),
        )

        val plan = planner.preview(JavaProjectScanner().scan(root), "com.acme.left.Service")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.warnings.any { it.contains("exact JDT type-binding evidence") }, plan.warnings.toString())
        assertEquals(setOf(Path.of("src/main/java/com/acme/left/Service.java")), plan.affectedFiles)
    }

    @Test
    fun safeDeleteWarnsWhenJdtFallsBackToLexicalReferences() {
        val root = createProject(
            "src/main/java/com/example/Legacy.java" to """
                package com.example;
                public class Legacy {}
            """.trimIndent(),
            "src/main/java/com/example/NeedsDependency.java" to """
                package com.example;
                public class NeedsDependency {
                    private MissingDependency dependency;
                }
            """.trimIndent(),
        )

        val plan = planner.preview(JavaProjectScanner().scan(root), "com.example.Legacy")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.warnings.any { it.contains("lexical fallback") }, plan.warnings.toString())
    }

    private fun createProject(vararg files: Pair<String, String>): Path {
        val root = Files.createTempDirectory("rk-safe-delete-test")
        files.forEach { (relative, content) ->
            root.resolve(relative).apply {
                Files.createDirectories(parent)
                writeText(content + "\n")
            }
        }
        return root
    }
}
