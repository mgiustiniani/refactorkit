package org.refactorkit.c

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClangdSemanticClientTest {
    @Test
    fun refusesDefinitionWhenNotStarted() {
        val client = ClangdSemanticClient(toolchain())
        val result = client.definition(Path.of("src/main.c"), 0, 0)
        val refused = assertIs<CClangdSemanticResult.Refused>(result)
        assertTrue(refused.diagnostics.any { it.code == "clangd.notRunning" })
    }

    @Test
    fun reportsUnavailableReferencesWhenNotStarted() {
        val client = ClangdSemanticClient(toolchain())
        val result = client.references(Path.of("src/main.c"), 0, 0)
        val unavailable = assertIs<CReferenceResult.Unavailable>(result)
        assertTrue(unavailable.diagnostics.any { it.code == "clangd.notRunning" })
    }

    @Test
    fun parsesDefinitionLocation() {
        val client = ClangdSemanticClient(toolchain())
        val location = client.parseLocation("""{"uri":"file:///workspace/src/main.c","range":{"start":{"line":3,"character":10},"end":{"line":3,"character":20}},"name":"compute"}""")
        val loc = kotlin.test.assertNotNull(location)
        assertEquals(Path.of("/workspace/src/main.c"), loc.file)
        assertEquals(3, loc.startLine)
        assertEquals(10, loc.startCharacter)
        assertEquals(20, loc.endCharacter)
        assertEquals("compute", loc.name)
    }

    @Test
    fun parsesReferencesLocations() {
        val client = ClangdSemanticClient(toolchain())
        val refs = client.parseLocations("""[
            {"uri":"file:///workspace/src/main.c","range":{"start":{"line":3,"character":10},"end":{"line":3,"character":20}}},
            {"uri":"file:///workspace/src/util.c","range":{"start":{"line":7,"character":0},"end":{"line":7,"character":7}}}
        ]""")
        assertEquals(2, refs.size)
        assertEquals(Path.of("/workspace/src/util.c"), refs[1].file)
        assertEquals(7, refs[1].startLine)
    }

    private fun toolchain() = ClangSemanticToolchain(
        clangExecutable = Path.of("/usr/bin/clang"),
        clangdExecutable = Path.of("/usr/bin/clangd"),
        clangFormatExecutable = Path.of("/usr/bin/clang-format"),
        provenance = ClangToolchainProvenance(
            clangVersion = "22.1.8",
            clangdVersion = "22.1.8",
            clangFormatVersion = "22.1.8",
            targetTriple = "x86_64-pc-linux-gnu",
            resourceDir = "/usr/lib/clang/22",
            evidence = emptyList(),
        ),
    )
}
