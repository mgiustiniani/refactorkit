package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CSignaturePlannerTest {
    @Test
    fun refusesWhenNotStarted() {
        val planner = CSignaturePlanner(toolchain())
        val plan = planner.renameParameter(snapshot(), Path.of("src/main.c"), "x", "y")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesInvalidMapping() {
        val planner = CSignaturePlanner(toolchain())
        val plan = planner.renameParameter(snapshot(), Path.of("src/main.c"), "x", "x")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesMissingParameter() {
        val planner = CSignaturePlanner(toolchain())
        val plan = planner.renameParameter(snapshot(), Path.of("src/main.c"), "zzz", "y")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun resolvesParameterInsideSignatureNotFirstBodyIdentifier() {
        // 'a' also appears in the body; the parameter identity must come from the
        // signature parameter list, not the first same-name identifier.
        val content = "int f(int a, int b) { int a_local = a + 1; return a_local; }\n"
        val snap = ProjectSnapshot(
            workspace = Workspace(Path.of("/workspace")),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("src/main.c"), content, "c")),
        )
        val analysis = CSignaturePlanner(toolchain()).analyzeSignature(snap, Path.of("src/main.c"), "a")
        assertIs<CSignaturePlanner.SignatureAnalysis.Found>(analysis)
        assertEquals(0, analysis.paramIndex)
        assertEquals(2, analysis.paramCount)
    }

    @Test
    fun resolvesParameterAtItsOwnPositionNotEarlierSubstring() {
        // 'x' occurs inside the earlier parameter 'ext'; the seed must bind to the real
        // x parameter (paramIndex 1) at its own declarator position, not the substring
        // inside 'ext'. A naive first-substring indexOf would seed inside 'ext'.
        val content = "int compute(int ext, int x) { return ext + x; }\n"
        val snap = ProjectSnapshot(
            workspace = Workspace(Path.of("/workspace")),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("src/main.c"), content, "c")),
        )
        val analysis = CSignaturePlanner(toolchain()).analyzeSignature(snap, Path.of("src/main.c"), "x")
        assertIs<CSignaturePlanner.SignatureAnalysis.Found>(analysis)
        assertEquals(1, analysis.paramIndex)
        val line = content.lines()[analysis.line]
        assertEquals('x', line[analysis.character])
        assertTrue(!line[analysis.character - 1].isLetterOrDigit(),
            "seed must start a standalone token, not sit inside 'ext'; got line=${line}")
    }

    @Test
    fun refusesVariadicSignature() {
        val content = "int f(int a, ...) { return a; }\n"
        val snap = ProjectSnapshot(
            workspace = Workspace(Path.of("/workspace")),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("src/main.c"), content, "c")),
        )
        val analysis = CSignaturePlanner(toolchain()).analyzeSignature(snap, Path.of("src/main.c"), "a")
        assertIs<CSignaturePlanner.SignatureAnalysis.Refused>(analysis)
        assertTrue(analysis.message.contains("Variadic"))
    }

    @Test
    fun refusesOldStyleSignature() {
        val content = "int f(a, b) int a; int b; { return a + b; }\n"
        val snap = ProjectSnapshot(
            workspace = Workspace(Path.of("/workspace")),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("src/main.c"), content, "c")),
        )
        val analysis = CSignaturePlanner(toolchain()).analyzeSignature(snap, Path.of("src/main.c"), "a")
        assertIs<CSignaturePlanner.SignatureAnalysis.Refused>(analysis)
        assertTrue(analysis.message.contains("Old-style"))
    }

    @Test
    fun refusesParameterAbsentFromSignature() {
        val analysis = CSignaturePlanner(toolchain()).analyzeSignature(snapshot(), Path.of("src/main.c"), "absent")
        assertIs<CSignaturePlanner.SignatureAnalysis.Refused>(analysis)
        assertTrue(analysis.message.contains("not found in the function signature"))
    }

    @Test
    fun refusesAmbiguousParameterAcrossSignatures() {
        // The same parameter name in two function signatures must not silently rename the
        // first match; the target is ambiguous and the operation is refused.
        val content = "int foo(int x) { return x + 1; }\nint bar(int x) { return x - 1; }\n"
        val snap = ProjectSnapshot(
            workspace = Workspace(Path.of("/workspace")),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("src/main.c"), content, "c")),
        )
        val analysis = CSignaturePlanner(toolchain()).analyzeSignature(snap, Path.of("src/main.c"), "x")
        assertIs<CSignaturePlanner.SignatureAnalysis.Refused>(analysis)
        assertTrue(analysis.message.contains("ambiguous"), "expected ambiguity refusal, got: ${analysis.message}")
    }

    private fun snapshot() = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main.c"), "int compute(int x) { return x + 1; }\nint main(void) { return compute(2); }\n", "c")),
    )

    private fun toolchain() = ClangSemanticToolchain(
        clangExecutable = Path.of("/usr/bin/clang"),
        clangdExecutable = Path.of("/usr/bin/clangd"),
        clangFormatExecutable = Path.of("/usr/bin/clang-format"),
        provenance = ClangToolchainProvenance(
            clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
            targetTriple = "x86_64-pc-linux-gnu", resourceDir = "/usr/lib/clang/22", evidence = emptyList(),
        ),
    )
}
