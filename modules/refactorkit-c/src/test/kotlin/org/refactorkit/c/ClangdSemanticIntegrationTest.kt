package org.refactorkit.c

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real clangd integration: launches clangd and resolves a definition. */
class ClangdSemanticIntegrationTest {
    @Test
    fun resolvesDefinitionWithRealClangd() {
        val workspace = Files.createTempDirectory("refactorkit-clangd")
        val mainC = workspace.resolve("main.c")
        Files.writeString(mainC, "int compute(int x) { return x + 1; }\nint main(void) { return compute(2); }\n")
        val configured = System.getProperty("refactorkit.c.qualificationToolchain")?.let(Path::of)
        val bin = configured?.resolve("usr/bin") ?: Path.of("/usr/bin")
        val request = ClangToolchainRequest(
            workspaceRoot = workspace,
            clangExecutable = configured?.resolve("usr/bin/clang-22") ?: bin.resolve("clang"),
            clangdExecutable = configured?.resolve("usr/bin/clangd") ?: bin.resolve("clangd"),
            clangFormatExecutable = configured?.resolve("usr/bin/clang-format") ?: bin.resolve("clang-format"),
        )
        if (!Files.isExecutable(request.clangdExecutable)) return // clangd not installed; integration not run
        val toolchain = when (val discovery = ClangToolchainDiscoverer().discover(request)) {
            is ClangToolchainDiscovery.Available -> discovery.toolchain
            is ClangToolchainDiscovery.Refused -> if (configured == null) return else
                error("Configured native Clang authority was refused: ${discovery.diagnostics}")
        }
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
