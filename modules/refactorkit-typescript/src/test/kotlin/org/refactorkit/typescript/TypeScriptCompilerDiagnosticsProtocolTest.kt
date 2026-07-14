package org.refactorkit.typescript

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticProcessProvenance
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import org.refactorkit.treesitter.ExternalSemanticDiagnostics
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TypeScriptCompilerDiagnosticsProtocolTest {
    @Test
    fun exactUtf16StartAndEndSurviveCompilerPayloadParsingWithoutFallbackFabrication() {
        val root = Files.createTempDirectory("refactorkit-ts-compiler-range")
        root.resolve("tsconfig.json").writeText("{}")
        val source = SourceFile(Path.of("src/value.ts"), "😀 missing", "typescript")
        val snapshot = ProjectSnapshot(Workspace(root), emptyList(), listOf(source))
        val diagnostics = TypeScriptCompilerDiagnostics(toolchain(root), TypeScriptProjectModelBuilder().build(root))
        val provenance = SemanticProcessProvenance(
            "typescript-compiler-test", root.resolve("node"), "a".repeat(64), "b".repeat(64), root, 42, Instant.EPOCH,
        )

        val exact = diagnostics.parseForProtocolTest(
            """{"schema":1,"complete":true,"snapshotHash":"${snapshot.hash}","diagnostics":[{"code":"TS2304","category":1,"message":"missing","file":"src/value.ts","line":0,"character":3,"endLine":0,"endCharacter":10}]}""",
            snapshot,
            provenance,
        ) as ExternalSemanticDiagnostics.Available
        val range = exact.diagnostics.single().location!!.range
        assertEquals(3, range.start.character)
        assertEquals(10, range.end.character)
        assertEquals(provenance, exact.processProvenance)

        val noLocation = diagnostics.parseForProtocolTest(
            """{"schema":1,"complete":true,"snapshotHash":"${snapshot.hash}","diagnostics":[{"code":"TS18003","category":1,"message":"no inputs","file":null,"line":null,"character":null,"endLine":null,"endCharacter":null}]}""",
            snapshot,
            provenance,
        ) as ExternalSemanticDiagnostics.Available
        assertNull(noLocation.diagnostics.single().location)

        val incompleteLocation = diagnostics.parseForProtocolTest(
            """{"schema":1,"complete":true,"snapshotHash":"${snapshot.hash}","diagnostics":[{"code":"TS2304","category":1,"message":"missing","file":"src/value.ts","line":0,"character":3,"endLine":0}]}""",
            snapshot,
            provenance,
        )
        assertIs<ExternalSemanticDiagnostics.Unavailable>(incompleteLocation)
        assertEquals("typescript.compilerDiagnosticsInvalid", incompleteLocation.diagnostic.code)
    }

    private fun toolchain(root: Path): TypeScriptSemanticToolchain {
        val node = root.resolve("node").also { it.writeText("node") }
        val server = root.resolve("server.mjs").also { it.writeText("server") }
        val tsserver = root.resolve("tsserver.js").also { it.writeText("tsserver") }
        val compiler = root.resolve("typescript.js").also { it.writeText("compiler") }
        return TypeScriptSemanticToolchain(
            node,
            server,
            tsserver,
            listOf(node.toString(), server.toString()),
            TypeScriptToolchainProvenance(
                nodeVersion = "22.0.0",
                languageServerVersion = "4.3.3",
                typeScriptVersion = "5.7.2",
                evidence = listOf(ToolchainFileEvidence(
                    "typescript-compiler-entrypoint", compiler, "c".repeat(64), Files.size(compiler),
                )),
            ),
            compiler,
        )
    }
}
