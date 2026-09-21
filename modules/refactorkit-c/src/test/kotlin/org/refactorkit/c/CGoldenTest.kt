package org.refactorkit.c

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden fixtures for the deterministic C refactorings (no external toolchain).
 *
 * Each case lives under `testdata/golden/c-<name>/` with `before/`, `after/`,
 * `request.json` and `expected-plan.json`. The request/expected fixtures are
 * decoded with the real JSON parser (no ad-hoc regex), the plan status is
 * compared, and the plan is applied to the `before/` tree and checked against
 * the independent `after/` image — so a golden can never pass on `PatchStatus`
 * alone while the produced edits corrupt the source.
 */
class CGoldenTest {
    @Test
    fun goldenOrganizeIncludes() {
        val case = goldenCase("c-organize-includes")
        val snap = snapshotFrom(case.beforeDir)
        val args = requestFrom(case.requestFile).arguments
        val plan = COrganizeIncludesPlanner().preview(snap, Path(args.getValue("file")))
        assertPlan(case, snap, plan)
    }

    @Test
    fun goldenExtract() {
        val case = goldenCase("c-extract")
        val snap = snapshotFrom(case.beforeDir)
        val args = requestFrom(case.requestFile).arguments
        val plan = CExtractPlanner().preview(
            snap,
            Path(args.getValue("file")),
            SourcePosition(args.getValue("startLine").toInt(), args.getValue("startChar").toInt())
                .let { start ->
                    SourceRange(start, SourcePosition(args.getValue("endLine").toInt(), args.getValue("endChar").toInt()))
                },
            args.getValue("tempName"),
        )
        assertPlan(case, snap, plan)
    }

    @Test
    fun goldenInline() {
        val case = goldenCase("c-inline")
        val snap = snapshotFrom(case.beforeDir)
        val args = requestFrom(case.requestFile).arguments
        val plan = CInlinePlanner().preview(snap, Path(args.getValue("file")), args.getValue("symbol"))
        assertPlan(case, snap, plan)
    }

    @Test
    fun goldenRelocate() {
        val case = goldenCase("c-relocate")
        val snap = snapshotFrom(case.beforeDir)
        val args = requestFrom(case.requestFile).arguments
        val plan = CRelocateComponentPlanner().preview(
            snap, Path(args.getValue("componentDir")), Path(args.getValue("newDir")),
        )
        assertPlan(case, snap, plan)
    }

    /**
     * Asserts the plan status matches `expected-plan.json` and, when an `after/`
     * image exists, that applying the plan to `before/` reproduces every after
     * file exactly.
     */
    private fun assertPlan(case: GoldenCase, snap: ProjectSnapshot, plan: PatchPlan) {
        assertEquals(expectedStatus(case.expectedPlanFile), plan.status, "${case.name} status")
        val afterDir = case.dir.resolve("after")
        if (!Files.isDirectory(afterDir)) return
        val applied = WorkspaceEditSimulator.apply(snap, plan.workspaceEdit)
        val expected = collectFiles(afterDir)
        assertEquals(expected.size, applied.files.size, "${case.name} after-file count")
        for ((rel, expectedContent) in expected) {
            val actual = applied.files.firstOrNull { it.path.toString().replace('\\', '/') == rel }
            assertTrue(actual != null, "${case.name} missing after file: $rel")
            assertEquals(expectedContent.replace("\r\n", "\n"), actual.content.replace("\r\n", "\n"), "${case.name} after $rel")
        }
    }

    private data class GoldenCase(val dir: Path, val beforeDir: Path, val requestFile: Path, val expectedPlanFile: Path, val name: String)

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
        return GoldenCase(dir, before, request, expected, name)
    }

    private fun snapshotFrom(beforeDir: Path): ProjectSnapshot {
        val files = Files.walk(beforeDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { it }
                .toList()
        }
        val sources = files.map { path ->
            val rel = beforeDir.relativize(path).toString().replace('\\', '/')
            SourceFile(Path.of(rel), Files.readString(path), "c")
        }
        return ProjectSnapshot(Workspace(beforeDir.toAbsolutePath().normalize()), emptyList(), sources)
    }

    private fun collectFiles(dir: Path): Map<String, String> {
        val files = Files.walk(dir).use { stream -> stream.filter { Files.isRegularFile(it) }.map { it }.toList() }
        return files.associate { p ->
            dir.relativize(p).toString().replace('\\', '/') to Files.readString(p)
        }
    }

    private data class GoldenRequest(val operation: String, val arguments: Map<String, String>)

    private val json = Json

    private fun requestFrom(path: Path): GoldenRequest {
        val obj = json.parseToJsonElement(Files.readString(path)).jsonObject
        val operation = obj["operation"]!!.jsonPrimitive.content
        val arguments = obj["arguments"]!!.jsonObject.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        return GoldenRequest(operation, arguments)
    }

    private fun expectedStatus(path: Path): PatchStatus {
        val obj: JsonObject = json.parseToJsonElement(Files.readString(path)).jsonObject
        val status = obj["status"]?.jsonPrimitive?.content ?: error("missing status in $path")
        return PatchStatus.valueOf(status.uppercase())
    }
}
