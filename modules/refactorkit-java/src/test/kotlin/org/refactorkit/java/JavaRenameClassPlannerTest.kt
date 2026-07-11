package org.refactorkit.java

import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ApplyResult
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaRenameClassPlannerTest {

    private val adapter = JavaLanguageAdapter()
    private val planner = JavaRenameClassPlanner(adapter)

    @Test
    fun previewRenamesClassAndFile() {
        val root = createTempProject(
            "src/main/java/com/example/UserManager.java" to """
                package com.example;
                public class UserManager {
                    public UserManager() {}
                }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.UserManager", "AccountManager")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.summary.contains("AccountManager"))
        // Rename edit should be present
        val renames = plan.workspaceEdit.edits.filterIsInstance<org.refactorkit.core.FileEdit.Rename>()
        assertEquals(1, renames.size)
        assertTrue(renames[0].newPath.fileName.toString() == "AccountManager.java")
    }

    @Test
    fun appliedRenameUpdatesFileContent() {
        val root = createTempProject(
            "src/main/java/com/example/UserManager.java" to """
                package com.example;
                public class UserManager {
                    public UserManager() {}
                    public String name() { return "UserManager"; }
                }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.UserManager", "AccountManager")
        assertEquals(PatchStatus.PREVIEW, plan.status)

        val engine = PatchEngine(root)
        val result = engine.apply(plan, snap.hash)
        assertIs<ApplyResult.Applied>(result)

        val newFile = root.resolve("src/main/java/com/example/AccountManager.java")
        assertTrue(newFile.toFile().exists(), "AccountManager.java should exist")
        val content = newFile.readText()
        assertTrue(content.contains("class AccountManager"), "class name updated")
        assertTrue(content.contains("public AccountManager()"), "constructor updated")
        // String literal should NOT be renamed
        assertTrue(content.contains("\"UserManager\""), "string literal preserved")
    }

    @Test
    fun jdtRenameDoesNotChangeSameSimpleTypeInAnotherPackage() {
        val root = createTempProject(
            "src/main/java/com/acme/left/Service.java" to """
                package com.acme.left;
                public class Service {
                    public Service() {}
                }
            """.trimIndent(),
            "src/main/java/com/acme/right/Service.java" to """
                package com.acme.right;
                public class Service {}
            """.trimIndent(),
            "src/main/java/com/acme/app/LeftClient.java" to """
                package com.acme.app;
                import com.acme.left.Service;
                class LeftClient { Service service = new Service(); }
            """.trimIndent(),
            "src/main/java/com/acme/app/RightClient.java" to """
                package com.acme.app;
                import com.acme.right.Service;
                class RightClient { Service service = new Service(); }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.acme.left.Service", "Worker")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.warnings.any { it.contains("JDT type binding selected") }, plan.warnings.toString())
        val result = PatchEngine(root).apply(plan, snap.hash)
        assertIs<ApplyResult.Applied>(result)

        val leftClient = root.resolve("src/main/java/com/acme/app/LeftClient.java").readText()
        val rightClient = root.resolve("src/main/java/com/acme/app/RightClient.java").readText()
        assertTrue(leftClient.contains("import com.acme.left.Worker;"), leftClient)
        assertTrue(leftClient.contains("Worker service = new Worker()"), leftClient)
        assertTrue(rightClient.contains("import com.acme.right.Service;"), rightClient)
        assertTrue(rightClient.contains("Service service = new Service()"), rightClient)
    }

    @Test
    fun renameRefusesExistingTargetTypeAndFile() {
        val root = createTempProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
            "src/main/java/com/example/AccountManager.java" to "package com.example;\npublic class AccountManager {}\n",
        )

        val plan = planner.preview(JavaProjectScanner().scan(root), "com.example.UserManager", "AccountManager")

        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("already exists"), plan.summary)
    }

    @Test
    fun renameWarnsWhenJdtFallsBackToLexicalEdits() {
        val root = createTempProject(
            "src/main/java/com/example/UserManager.java" to """
                package com.example;
                public class UserManager {
                    private MissingDependency dependency;
                }
            """.trimIndent(),
        )

        val plan = planner.preview(JavaProjectScanner().scan(root), "com.example.UserManager", "AccountManager")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.warnings.any { it.contains("lexical fallback") }, plan.warnings.toString())
    }

    @Test
    fun refusedForUnknownSymbol() {
        val root = createTempProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.DoesNotExist", "Bar")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusedForInvalidTargetName() {
        val root = createTempProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.Foo", "123Invalid")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun updatesImportInReferencingFile() {
        val root = createTempProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
            "src/main/java/com/other/Client.java" to """
                package com.other;
                import com.example.UserManager;
                public class Client {
                    UserManager manager = new UserManager();
                }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val plan = planner.preview(snap, "com.example.UserManager", "AccountManager")
        assertEquals(PatchStatus.PREVIEW, plan.status)

        val engine = PatchEngine(root)
        val result = engine.apply(plan, snap.hash)
        assertIs<ApplyResult.Applied>(result)

        val clientContent = root.resolve("src/main/java/com/other/Client.java").readText()
        assertTrue(clientContent.contains("import com.example.AccountManager;"), "import updated")
        assertTrue(clientContent.contains("AccountManager manager"), "simple name updated")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun createTempProject(vararg entries: Pair<String, String>): java.nio.file.Path {
        val root = Files.createTempDirectory("refactorkit-rename-test")
        for ((rel, content) in entries) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return root
    }
}
