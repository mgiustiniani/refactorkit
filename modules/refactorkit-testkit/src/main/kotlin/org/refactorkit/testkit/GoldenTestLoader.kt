package org.refactorkit.testkit

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Discovers and loads [GoldenTestCase] instances from a golden test data directory.
 *
 * Expected layout:
 * ```
 * testdata/golden/
 *   <case-name>/
 *     before/          ← source tree before the operation
 *     after/           ← source tree after the operation (omitted for REFUSED cases)
 *     request.json     ← operation to execute
 *     expected-plan.json
 * ```
 */
object GoldenTestLoader {

    private val DEFAULT_GOLDEN_DIR: Path = run {
        // When running tests from the project root (workingDir = rootProject.projectDir)
        val relative = Paths.get("testdata/golden")
        if (relative.exists()) relative
        else Paths.get("../../testdata/golden").toAbsolutePath().normalize()
    }

    /**
     * Discover all golden test cases in [goldenDir].
     * Subdirectories without a `request.json` are skipped.
     */
    fun discover(goldenDir: Path = DEFAULT_GOLDEN_DIR): List<GoldenTestCase> {
        if (!goldenDir.exists()) return emptyList()
        return goldenDir.toFile()
            .listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?.map { dir -> load(dir.toPath()) }
            ?.filter { it.requestFile.exists() }
            ?: emptyList()
    }

    /**
     * Load a single [GoldenTestCase] from [caseDir].
     * The [GoldenTestCase.afterDir] may not exist for cases that expect a REFUSED plan.
     */
    fun load(caseDir: Path): GoldenTestCase = GoldenTestCase(
        name = caseDir.fileName.toString(),
        beforeDir = caseDir.resolve("before"),
        afterDir = caseDir.resolve("after"),
        requestFile = caseDir.resolve("request.json"),
        expectedPlanFile = caseDir.resolve("expected-plan.json"),
    )

    /**
     * Load a named case from [goldenDir].
     * Throws if the case directory does not contain a `request.json`.
     */
    fun loadNamed(name: String, goldenDir: Path = DEFAULT_GOLDEN_DIR): GoldenTestCase {
        val caseDir = goldenDir.resolve(name)
        require(caseDir.resolve("request.json").exists()) {
            "Golden test case '$name' not found or missing request.json in $goldenDir"
        }
        return load(caseDir)
    }
}
