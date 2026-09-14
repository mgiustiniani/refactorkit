package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden fixtures for the deterministic C refactorings (no external toolchain).
 * Each case lives under `testdata/golden/c-<name>/` with `before/`, `request.json`
 * and `expected-plan.json`; the plan status/summary is compared.
 */
class CGoldenTest {
    @Test
    fun goldenOrganizeIncludes() {
        val case = goldenCase("c-organize-includes")
        val snap = snapshotFrom(case.beforeDir)
        val request = requestFrom(case.requestFile)
        val plan = COrganizeIncludesPlanner().preview(snap, Path.of(request.arguments["file"] ?: "main.c"))
        assertEquals(expectedStatus(case.expectedPlanFile), plan.status)
    }

    @Test
    fun goldenExtract() {
        val case = goldenCase("c-extract")
        val snap = snapshotFrom(case.beforeDir)
        val request = requestFrom(case.requestFile)
        val plan = CExtractPlanner().preview(snap, Path.of(request.arguments["file"] ?: "main.c"), rangeFrom(request), request.arguments["tempName"] ?: "tmp")
        assertEquals(expectedStatus(case.expectedPlanFile), plan.status)
    }

    @Test
    fun goldenInline() {
        val case = goldenCase("c-inline")
        val snap = snapshotFrom(case.beforeDir)
        val request = requestFrom(case.requestFile)
        val plan = CInlinePlanner().preview(snap, Path.of(request.arguments["file"] ?: "main.c"), request.arguments["symbol"] ?: error("inline needs symbol"))
        assertEquals(expectedStatus(case.expectedPlanFile), plan.status)
    }

    @Test
    fun goldenRelocate() {
        val case = goldenCase("c-relocate")
        val snap = snapshotFrom(case.beforeDir)
        val request = requestFrom(case.requestFile)
        val plan = CRelocateComponentPlanner().preview(snap, Path.of(request.arguments["componentDir"] ?: "src/component"), Path.of(request.arguments["newDir"] ?: "src/other"))
        assertEquals(expectedStatus(case.expectedPlanFile), plan.status)
    }

    private data class GoldenCase(val beforeDir: Path, val requestFile: Path, val expectedPlanFile: Path)

    private fun goldenRoot(): Path {
        val relative = Path.of("testdata/golden")
        if (Files.isDirectory(relative)) return relative.toAbsolutePath().normalize()
        return Path.of("../../testdata/golden").toAbsolutePath().normalize()
    }

    private fun goldenCase(name: String): GoldenCase {
        val dir = goldenRoot().resolve(name)
        val before = dir.resolve("before")
        val request = dir.resolve("request.json")
        val expected = dir.resolve("expected-plan.json")
        assertTrue(Files.isDirectory(before), "golden before dir missing: $before")
        assertTrue(Files.exists(request), "golden request missing: $request")
        assertTrue(Files.exists(expected), "golden expected-plan missing: $expected")
        return GoldenCase(before, request, expected)
    }

    private fun snapshotFrom(beforeDir: Path): ProjectSnapshot {
        val files = Files.walk(beforeDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { it }
                .toList()
        }
        val sources = files.map { path ->
            val rel = beforeDir.relativize(path).toString().replace('\\', '/')
            val lang = if (rel.endsWith(".h")) "c" else "c"
            SourceFile(Path.of(rel), Files.readString(path), lang)
        }
        return ProjectSnapshot(Workspace(beforeDir.toAbsolutePath().normalize()), emptyList(), sources)
    }

    private fun requestFrom(path: Path): GoldenRequest = GoldenRequest(
        operation = jsonField(path, "operation"),
        arguments = jsonArguments(path),
    )

    private data class GoldenRequest(val operation: String, val arguments: Map<String, String>)

    private fun jsonField(path: Path, name: String): String {
        val text = Files.readString(path)
        val regex = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(text)?.groupValues?.get(1) ?: error("missing $name in $path")
    }

    private fun jsonArguments(path: Path): Map<String, String> {
        val text = Files.readString(path)
        val result = mutableMapOf<String, String>()
        val regex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
        for (m in regex.findAll(text)) {
            if (m.groupValues[1] != "operation") result[m.groupValues[1]] = m.groupValues[2]
        }
        return result
    }

    private fun expectedStatus(path: Path): PatchStatus {
        val text = Files.readString(path)
        val status = Regex("\"status\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
            ?: error("missing status in $path")
        return PatchStatus.valueOf(status.uppercase())
    }

    private fun rangeFrom(request: GoldenRequest): org.refactorkit.core.SourceRange =
        org.refactorkit.core.SourceRange(
            org.refactorkit.core.SourcePosition(
                request.arguments["startLine"]?.toInt() ?: 0,
                request.arguments["startChar"]?.toInt() ?: 0,
            ),
            org.refactorkit.core.SourcePosition(
                request.arguments["endLine"]?.toInt() ?: 0,
                request.arguments["endChar"]?.toInt() ?: 0,
            ),
        )
}
