package org.refactorkit.c

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real clangd integration: launches clangd and resolves a definition. */
class ClangdSemanticIntegrationTest {
    private val clangd = Path.of("/usr/bin/clangd")

    @Test
    fun resolvesDefinitionWithRealClangd() {
        if (!Files.isExecutable(clangd)) return // clangd not installed; integration not run
        val workspace = Files.createTempDirectory("refactorkit-clangd")
        val mainC = workspace.resolve("main.c")
        Files.writeString(mainC, "int compute(int x) { return x + 1; }\nint main(void) { return compute(2); }\n")
        val toolchain = ClangSemanticToolchain(
            clangExecutable = Path.of("/usr/bin/clang"),
            clangdExecutable = clangd,
            clangFormatExecutable = Path.of("/usr/bin/clang-format"),
            provenance = ClangToolchainProvenance(
                clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
                targetTriple = "x86_64-pc-linux-gnu", resourceDir = "/usr/lib/clang/22", evidence = emptyList(),
            ),
        )
        ClangdSemanticClient(toolchain).use { client ->
            client.start(workspace)
            assertTrue(client.didOpen(mainC, Files.readString(mainC)))
            val result = client.definition(mainC, 1, 24) // position of `compute` in `return compute(2)`
            val found = assertIs<CClangdSemanticResult.Found>(result)
            assertTrue(found.definition.name == "compute" || found.definition.name == null)
            assertTrue(found.definition.file.toAbsolutePath().normalize() == mainC.toAbsolutePath().normalize())
        }
    }
}
