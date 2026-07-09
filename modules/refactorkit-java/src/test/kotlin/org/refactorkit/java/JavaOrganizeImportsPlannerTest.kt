package org.refactorkit.java

import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ApplyResult
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaOrganizeImportsPlannerTest {

    private val planner = JavaOrganizeImportsPlanner()

    @Test
    fun sortsAndDeduplicatesImports() {
        val content = """
            package com.example;
            import com.other.Bar;
            import java.util.List;
            import com.other.Bar;
            import org.slf4j.Logger;
            public class Foo {}
        """.trimIndent()

        val root = Files.createTempDirectory("refactorkit-imports-test")
        val file = root.resolve("src/main/java/com/example/Foo.java")
        Files.createDirectories(file.parent)
        file.writeText(content)

        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, listOf(Paths.get("src/main/java/com/example/Foo.java")))
        assertEquals(PatchStatus.PREVIEW, plan.status)

        val engine = PatchEngine(root)
        val result = engine.apply(plan, snap.hash)
        assertIs<ApplyResult.Applied>(result)

        val updated = file.readText()
        val importLines = updated.lines().filter { it.startsWith("import ") }

        // No duplicate Bar import
        assertEquals(1, importLines.count { it.contains("com.other.Bar") })
        // java.* before org.*
        val javaIdx = importLines.indexOfFirst { it.contains("java.util.List") }
        val orgIdx = importLines.indexOfFirst { it.contains("org.slf4j") }
        assertTrue(javaIdx < orgIdx, "java.* should come before org.*")
    }

    @Test
    fun removesSamePackageImport() {
        val content = """
            package com.example;
            import com.example.Other;
            import java.util.Map;
            public class Foo {}
        """.trimIndent()

        val root = Files.createTempDirectory("refactorkit-imports-samepkg")
        val file = root.resolve("src/main/java/com/example/Foo.java")
        Files.createDirectories(file.parent)
        file.writeText(content)

        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, listOf(Paths.get("src/main/java/com/example/Foo.java")))

        val engine = PatchEngine(root)
        engine.apply(plan, snap.hash)

        val updated = file.readText()
        assertTrue(!updated.contains("import com.example.Other"), "same-package import removed")
        assertTrue(updated.contains("import java.util.Map"), "other imports preserved")
    }
}
