package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExternalWorkspaceEditNormalizerTest {
    @Test
    fun normalizesAbsoluteAndRelativePathsAndPreservesVersions() {
        val fixture = fixture()
        val result = ExternalWorkspaceEditNormalizer().normalize(fixture.snapshot, ExternalWorkspaceEditProposal(
            providerId = "typescript-lsp",
            providerVersion = "5.9.0",
            edits = listOf(
                ExternalFileEditProposal.Modify(
                    fixture.root.resolve("src/main.ts"),
                    listOf(TextEdit(range(0, 6, 0, 9), "bar")),
                    documentVersion = 7,
                ),
                ExternalFileEditProposal.Create(Path.of("src/extra.ts"), "export {}\n"),
            ),
        ))

        val accepted = assertIs<ExternalWorkspaceEditNormalization.Accepted>(result).normalized
        assertEquals(RefactoringEvidence.LANGUAGE_SERVER, accepted.evidence)
        assertEquals(7, accepted.documentVersions[Path.of("src/main.ts")])
        assertEquals(listOf(Path.of("src/main.ts"), Path.of("src/extra.ts")), accepted.workspaceEdit.edits.map(FileEdit::path))
        assertEquals("typescript-lsp", accepted.providerId)
    }

    @Test
    fun refusesOutsideWorkspaceSymlinksAndGeneratedSources() {
        val fixture = fixture()
        val outside = ExternalWorkspaceEditNormalizer().normalize(fixture.snapshot, proposal(
            ExternalFileEditProposal.Modify(fixture.root.parent.resolve("outside.ts"), listOf(TextEdit(range(0, 0, 0, 0), "x"))),
        ))
        assertCode(outside, "externalEdit.pathOutsideWorkspace")

        val generated = ExternalWorkspaceEditNormalizer().normalize(fixture.snapshot, proposal(
            ExternalFileEditProposal.Modify(Path.of("generated/code.ts"), listOf(TextEdit(range(0, 0, 0, 0), "x"))),
        ))
        assertCode(generated, "externalEdit.generatedSource")

        val target = Files.createTempDirectory("refactorkit-external-target")
        val link = fixture.root.resolve("linked")
        runCatching { Files.createSymbolicLink(link, target) }.onSuccess {
            val symlink = ExternalWorkspaceEditNormalizer().normalize(fixture.snapshot, proposal(
                ExternalFileEditProposal.Create(Path.of("linked/new.ts"), "x"),
            ))
            assertCode(symlink, "externalEdit.symlink")
        }
    }

    @Test
    fun refusesOverlapsInvalidCoordinatesAndStructuralConflicts() {
        val fixture = fixture()
        val overlap = ExternalWorkspaceEditNormalizer().normalize(fixture.snapshot, proposal(
            ExternalFileEditProposal.Modify(Path.of("src/main.ts"), listOf(
                TextEdit(range(0, 0, 0, 5), "a"),
                TextEdit(range(0, 3, 0, 8), "b"),
            )),
        ))
        assertCode(overlap, "externalEdit.invalidCoordinates")

        val invalid = ExternalWorkspaceEditNormalizer().normalize(fixture.snapshot, proposal(
            ExternalFileEditProposal.Modify(Path.of("src/main.ts"), listOf(TextEdit(range(99, 0, 99, 1), "x"))),
        ))
        assertCode(invalid, "externalEdit.invalidCoordinates")

        val createConflict = ExternalWorkspaceEditNormalizer().normalize(fixture.snapshot, proposal(
            ExternalFileEditProposal.Create(Path.of("src/main.ts"), "overwrite"),
        ))
        assertCode(createConflict, "externalEdit.invalidCoordinates")
    }

    @Test
    fun enforcesProviderVersionDocumentAndContentLimits() {
        val fixture = fixture()
        assertCode(ExternalWorkspaceEditNormalizer().normalize(fixture.snapshot, ExternalWorkspaceEditProposal(
            "INVALID PROVIDER", null, emptyList(),
        )), "externalEdit.providerInvalid")

        assertCode(ExternalWorkspaceEditNormalizer().normalize(fixture.snapshot, proposal(
            ExternalFileEditProposal.Modify(Path.of("src/main.ts"), emptyList(), documentVersion = -1),
        )), "externalEdit.documentVersionInvalid")

        val limited = ExternalWorkspaceEditNormalizer(ExternalWorkspaceEditLimits(
            maxFileEdits = 1, maxTextEdits = 1, maxReplacementBytes = 2,
        ))
        assertCode(limited.normalize(fixture.snapshot, proposal(
            ExternalFileEditProposal.Modify(Path.of("src/main.ts"), listOf(TextEdit(range(0, 0, 0, 0), "three"))),
        )), "externalEdit.contentLimit")
    }

    private fun proposal(vararg edits: ExternalFileEditProposal) = ExternalWorkspaceEditProposal(
        providerId = "test-lsp", providerVersion = "1", edits = edits.toList(),
    )

    private fun assertCode(result: ExternalWorkspaceEditNormalization, code: String) {
        val refused = assertIs<ExternalWorkspaceEditNormalization.Refused>(result)
        assertTrue(refused.diagnostics.any { it.code == code }, "missing $code in ${refused.diagnostics}")
    }

    private fun range(sl: Int, sc: Int, el: Int, ec: Int) = SourceRange(
        SourcePosition(sl, sc), SourcePosition(el, ec),
    )

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("refactorkit-external-edit")
        val module = Module(
            name = "app", root = Path.of("."), sourceRoots = listOf(Path.of("src")),
            generatedSourceRoots = listOf(Path.of("generated")),
        )
        return Fixture(root, ProjectSnapshot(
            workspace = Workspace(root),
            modules = listOf(module),
            files = listOf(
                SourceFile(Path.of("src/main.ts"), "const foo = 1;\n", "typescript"),
                SourceFile(Path.of("generated/code.ts"), "const generated = 1;\n", "typescript"),
            ),
        ))
    }

    private data class Fixture(val root: Path, val snapshot: ProjectSnapshot)
}
