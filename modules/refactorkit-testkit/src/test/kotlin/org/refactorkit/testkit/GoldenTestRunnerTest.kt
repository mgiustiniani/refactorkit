package org.refactorkit.testkit

import org.refactorkit.core.PatchStatus
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoldenTestRunnerTest {

    private val runner = GoldenTestRunner()
    private val goldenDir = Paths.get("testdata/golden")

    // ── loader ────────────────────────────────────────────────────────────────

    @Test
    fun discoverFindsKnownCases() {
        val cases = GoldenTestLoader.discover(goldenDir)
        val names = cases.map { it.name }.toSet()
        assertTrue("rename-class-user-manager" in names, "Expected rename-class-user-manager in $names")
        assertTrue("move-class-simple"          in names, "Expected move-class-simple in $names")
        assertTrue("safe-delete-refused"        in names, "Expected safe-delete-refused in $names")
        assertTrue("rename-class-with-references" in names, "Expected rename-class-with-references in $names")
    }

    @Test
    fun loadNamedLoadsCorrectly() {
        val tc = GoldenTestLoader.loadNamed("rename-class-user-manager", goldenDir)
        assertEquals("rename-class-user-manager", tc.name)
        assertTrue(tc.requestFile.toFile().exists())
        assertTrue(tc.beforeDir.toFile().exists())
        assertTrue(tc.afterDir.toFile().exists())
    }

    // ── rename-class-user-manager ─────────────────────────────────────────────

    @Test
    fun renameClassUserManagerPasses() {
        val tc = GoldenTestLoader.loadNamed("rename-class-user-manager", goldenDir)
        val result = runner.run(tc)
        assertTrue(result.passed, "Golden test failed:\n${result.errors.joinToString("\n")}")
        assertEquals(PatchStatus.PREVIEW, result.plan?.status)
    }

    // ── move-class-simple ─────────────────────────────────────────────────────

    @Test
    fun moveClassSimplePasses() {
        val tc = GoldenTestLoader.loadNamed("move-class-simple", goldenDir)
        val result = runner.run(tc)
        assertTrue(result.passed, "Golden test failed:\n${result.errors.joinToString("\n")}")
        assertEquals(PatchStatus.PREVIEW, result.plan?.status)
    }

    // ── safe-delete-refused ───────────────────────────────────────────────────

    @Test
    fun safeDeleteRefusedPasses() {
        val tc = GoldenTestLoader.loadNamed("safe-delete-refused", goldenDir)
        val result = runner.run(tc)
        assertTrue(result.planValid, "Plan validation failed:\n${result.planErrors.joinToString("\n")}")
        assertEquals(PatchStatus.REFUSED, result.plan?.status)
        assertTrue(result.plan?.warnings?.isNotEmpty() == true)
    }

    // ── rename-class-with-references ──────────────────────────────────────────

    @Test
    fun renameClassWithReferencesPlanIsValid() {
        val tc = GoldenTestLoader.loadNamed("rename-class-with-references", goldenDir)
        val result = runner.run(tc)
        // No after/ dir — only plan is validated
        assertTrue(result.planValid, "Plan validation failed:\n${result.planErrors.joinToString("\n")}")
        assertEquals(PatchStatus.PREVIEW, result.plan?.status)
        assertTrue(
            (result.plan?.affectedFiles?.size ?: 0) >= 2,
            "Expected at least 2 affected files, got ${result.plan?.affectedFiles?.size}",
        )
        val summary = result.plan?.summary ?: ""
        assertTrue(summary.contains("AccountManager", ignoreCase = true), "Summary should mention AccountManager: $summary")
    }

    // ── run all discovered cases ──────────────────────────────────────────────

    @Test
    fun allDiscoveredCasesHaveValidRequests() {
        val cases = GoldenTestLoader.discover(goldenDir)
        assertTrue(cases.isNotEmpty(), "No golden cases discovered in $goldenDir")
        cases.forEach { tc ->
            val request = GoldenJson.parseRequest(tc.requestFile.readText())
            assertTrue(request.operation.isNotBlank(), "Case '${tc.name}' has blank operation")
        }
    }
}
