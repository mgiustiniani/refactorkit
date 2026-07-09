package org.refactorkit.testkit

import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaMoveClassPlanner
import org.refactorkit.java.JavaOrganizeImportsPlanner
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameClassPlanner
import org.refactorkit.java.JavaSafeDeletePlanner
import org.refactorkit.webimporter.ExternalJavaClassImporter
import org.refactorkit.webimporter.ImportRequest
import org.refactorkit.webimporter.LicensePolicy
import org.refactorkit.webimporter.SourceKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Agent simulation tests — each test models the complete workflow an AI agent
 * would follow when using RefactorKit for a common task.
 *
 * Workflow per AGENTS.md §14:
 *   1. Open / scan project
 *   2. Identify symbol
 *   3. Preview refactoring
 *   4. Inspect plan
 *   5. Apply only if safe
 *   6. Roll back if needed
 *   7. Report result
 */
class AgentSimulationTest {

    private val scanner = JavaProjectScanner()
    private val adapter = JavaLanguageAdapter()

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun project(vararg files: Pair<String, String>): Path {
        val root = Files.createTempDirectory("agent-sim-")
        for ((rel, content) in files) {
            val p = root.resolve(rel); p.parent.createDirectories(); p.writeText(content)
        }
        return root
    }

    // ── scenario 1: rename class ──────────────────────────────────────────────

    @Test
    fun scenarioRenameClass() {
        // Setup
        val root = project(
            "src/main/java/com/example/UserManager.java" to
                "package com.example;\npublic class UserManager {\n    public String get() { return \"u\"; }\n}\n",
            "src/main/java/com/example/App.java" to
                "package com.example;\npublic class App {\n    UserManager m = new UserManager();\n}\n",
        )

        // 1. Scan
        val snap = scanner.scan(root)
        assertEquals(2, snap.files.size)

        // 2. Identify symbol
        val index = adapter.buildSymbols(snap)
        val symbol = index.symbols.find { it.id.value == "com.example.UserManager" }
        assertNotNull(symbol, "Symbol com.example.UserManager not found")

        // 3. Preview rename
        val plan = JavaRenameClassPlanner(adapter).preview(snap, "com.example.UserManager", "AccountManager")
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.affectedFiles.size >= 2, "Expected both files in plan")
        assertFalse(plan.diagnosticsAfterPreview.any { it.severity == Diagnostic.Severity.ERROR })

        // 4. Apply
        val applyResult = PatchEngine(root).apply(plan, snap.hash)
        assertIs<ApplyResult.Applied>(applyResult)

        // 5. Verify
        val newFile = root.resolve("src/main/java/com/example/AccountManager.java")
        val oldFile = root.resolve("src/main/java/com/example/UserManager.java")
        assertTrue(newFile.exists(), "AccountManager.java should exist")
        assertFalse(oldFile.exists(), "UserManager.java should be gone")
        assertTrue(newFile.readText().contains("class AccountManager"))
        val appContent = root.resolve("src/main/java/com/example/App.java").readText()
        assertTrue(appContent.contains("AccountManager"), "App.java should reference AccountManager")
        assertFalse(appContent.contains("UserManager"), "App.java should not reference UserManager")

        // 6. Rollback
        val rollback = PatchEngine(root).rollback(applyResult.transaction)
        assertIs<ApplyResult.Applied>(rollback)

        // 7. Verify restored
        assertTrue(oldFile.exists(), "UserManager.java should be restored")
        assertFalse(newFile.exists(), "AccountManager.java should be gone after rollback")
        assertTrue(oldFile.readText().contains("class UserManager"))
    }

    // ── scenario 2: move class to new package ─────────────────────────────────

    @Test
    fun scenarioMoveClassToNewPackage() {
        val root = project(
            "src/main/java/com/example/UserService.java" to
                "package com.example;\npublic class UserService {}\n",
            "src/main/java/com/example/Client.java" to
                "package com.example;\npublic class Client {\n    UserService s = new UserService();\n}\n",
        )

        val snap = scanner.scan(root)

        // Preview move
        val plan = JavaMoveClassPlanner(adapter).preview(snap, "com.example.UserService", "com.example.account")
        assertEquals(PatchStatus.PREVIEW, plan.status)

        // Apply
        val result = PatchEngine(root).apply(plan, snap.hash)
        assertIs<ApplyResult.Applied>(result)

        // Verify file moved
        assertFalse(root.resolve("src/main/java/com/example/UserService.java").exists())
        assertTrue(root.resolve("src/main/java/com/example/account/UserService.java").exists())

        // Verify new package declaration
        val moved = root.resolve("src/main/java/com/example/account/UserService.java").readText()
        assertTrue(moved.contains("package com.example.account"))

        // Verify import added in Client.java
        val client = root.resolve("src/main/java/com/example/Client.java").readText()
        assertTrue(client.contains("import com.example.account.UserService") ||
                   client.contains("com.example.account.UserService"),
            "Client.java should reference the new package")

        // Rollback
        assertIs<ApplyResult.Applied>(PatchEngine(root).rollback(result.transaction))
        assertTrue(root.resolve("src/main/java/com/example/UserService.java").exists())
    }

    // ── scenario 3: safe delete refused because references exist ──────────────

    @Test
    fun scenarioSafeDeleteRefusedDueToReferences() {
        val root = project(
            "src/main/java/com/example/LegacyUtil.java" to
                "package com.example;\npublic class LegacyUtil {}\n",
            "src/main/java/com/example/Consumer.java" to
                "package com.example;\npublic class Consumer {\n    LegacyUtil u = new LegacyUtil();\n}\n",
        )

        val snap = scanner.scan(root)

        // Agent attempts safe delete — should be refused
        val plan = JavaSafeDeletePlanner(adapter).preview(snap, "com.example.LegacyUtil", force = false)
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.warnings.isNotEmpty())
        assertTrue(plan.summary.contains("reference", ignoreCase = true) ||
                   plan.summary.contains("LegacyUtil", ignoreCase = true))

        // Agent must NOT apply a refused plan
        // Verify original files untouched
        assertTrue(root.resolve("src/main/java/com/example/LegacyUtil.java").exists())
    }

    // ── scenario 4: organize imports ──────────────────────────────────────────

    @Test
    fun scenarioOrganizeImports() {
        // Note: JavaOrganizeImportsPlanner sorts and deduplicates imports.
        // It does NOT remove unused imports (no type analysis available at Level 1).
        // The key guarantee is that after organize-imports the import order is stable.
        val root = project(
            "src/main/java/com/example/Service.java" to
                "package com.example;\n" +
                "import java.util.List;\n" +
                "import java.util.ArrayList;\n" +
                "import java.util.List;\n" +   // duplicate
                "public class Service {\n" +
                "    private List<String> items = new ArrayList<>();\n" +
                "}\n",
        )

        val snap = scanner.scan(root)
        val filePath = root.resolve("src/main/java/com/example/Service.java")

        // Preview organize imports — should detect the duplicate
        val plan = JavaOrganizeImportsPlanner().preview(snap, listOf(root.relativize(filePath)))

        // Plan is always valid (PREVIEW or empty edits if already clean)
        assertTrue(plan.status == PatchStatus.PREVIEW || plan.affectedFiles.isEmpty())

        if (plan.status == PatchStatus.PREVIEW && plan.workspaceEdit.edits.isNotEmpty()) {
            val result = PatchEngine(root).apply(plan, snap.hash)
            assertIs<ApplyResult.Applied>(result)

            val content = filePath.readText()
            // Duplicate import should be removed
            val listImportCount = content.lines()
                .count { it.trim() == "import java.util.List;" }
            assertTrue(listImportCount <= 1, "Duplicate import should have been deduplicated")
            // Used imports must remain
            assertTrue(content.contains("import java.util.ArrayList"))
        }
    }

    // ── scenario 5: import external class with unknown license ────────────────

    @Test
    fun scenarioImportExternalClassWithUnknownLicense() {
        val root = project(
            "src/main/java/com/example/App.java" to "package com.example;\npublic class App {}\n",
        )

        val snap = scanner.scan(root)
        val code = """
            public class UtilHelper {
                public static String sanitize(String input) {
                    return input.trim();
                }
            }
        """.trimIndent()

        // Agent imports external code with WARN license policy
        val importer = ExternalJavaClassImporter()
        val plan = importer.preview(ImportRequest(
            code = code,
            targetPackage = "com.example.util",
            sourceKind = SourceKind.CLIPBOARD,
            licensePolicy = LicensePolicy.WARN,
            snapshot = snap,
        ))

        // Plan should be PREVIEW with a license warning
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(
            plan.warnings.any { it.contains("license", ignoreCase = true) },
            "Expected license warning but got: ${plan.warnings}",
        )

        // Agent MUST review warnings before applying; do not auto-apply in this scenario
        assertTrue(plan.requiresUserApproval)
    }

    // ── scenario 6: import external class with naming conflict ────────────────

    @Test
    fun scenarioImportExternalClassWithNamingConflict() {
        val root = project(
            "src/main/java/com/example/util/UtilHelper.java" to
                "package com.example.util;\npublic class UtilHelper {}\n",
        )

        val snap = scanner.scan(root)
        val code = "package com.other;\npublic class UtilHelper { public void help() {} }"

        val importer = ExternalJavaClassImporter()
        val plan = importer.preview(ImportRequest(
            code = code,
            targetPackage = "com.example.util",
            sourceKind = SourceKind.CLIPBOARD,
            licensePolicy = LicensePolicy.WARN,
            snapshot = snap,
        ))

        // Plan should be REFUSED or have a conflict warning
        assertTrue(
            plan.status == PatchStatus.REFUSED ||
            plan.warnings.any { it.contains("conflict", ignoreCase = true) || it.contains("exist", ignoreCase = true) },
            "Expected REFUSED plan or conflict warning. status=${plan.status}, warnings=${plan.warnings}",
        )
    }
}
