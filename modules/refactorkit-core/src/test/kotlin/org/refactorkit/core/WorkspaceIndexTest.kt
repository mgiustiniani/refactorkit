package org.refactorkit.core

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceIndexTest {
    private val root = Paths.get("/workspace")
    private val javaPath = Paths.get("src/main/java/example/UserService.java")
    private val typeScriptPath = Paths.get("src/app.ts")

    @Test
    fun `indexes every recognized source and searches bounded provider symbols`() {
        val snapshot = snapshot()
        val session = WorkspaceIndexSession()
        val opened = session.open(snapshot)
        assertEquals(2, opened.sources.size)
        assertTrue(opened.sources.all { it.documentMode == SemanticDocumentMode.SAVED_SNAPSHOT })
        assertTrue(opened.sources.all { it.documentVersion == null })
        assertEquals(0, opened.symbolCount)

        val indexed = session.contribute(contribution(snapshot))
        val matches = indexed.searchSymbols("user", languageId = "java")

        assertEquals(1, matches.size)
        assertEquals("example.UserService", matches.single().symbol.id.value)
        assertEquals(SemanticEvidenceKind.LEXICAL, matches.single().evidence)
        assertEquals(snapshot.hash, indexed.snapshotHash)
    }

    @Test
    fun `binds source entries to build provider module and source set ownership`() {
        val model = BuildModel(
            providerId = "fixture-v1",
            status = BuildModelStatus.AVAILABLE,
            modules = listOf(BuildModule(
                id = "app",
                name = "app",
                root = root,
                sourceSets = listOf(BuildSourceSet(
                    id = "main",
                    kind = SourceSetKind.MAIN,
                    sourceRoots = listOf(Paths.get("src/main/java")),
                )),
            )),
        )
        val indexed = WorkspaceIndex.create(snapshot().copy(buildModels = listOf(model)))
        val java = indexed.sources.single { it.path == javaPath }
        assertEquals(
            WorkspaceIndexSourceOwnership("fixture-v1", "app", "main", false),
            java.ownerships.single(),
        )
    }

    @Test
    fun `refuses stale contributions and symbols outside the snapshot`() {
        val snapshot = snapshot()
        val session = WorkspaceIndexSession()
        session.open(snapshot)

        assertFailsWith<IllegalArgumentException> {
            session.contribute(contribution(snapshot).copy(snapshotHash = "0".repeat(64)))
        }
        assertFailsWith<IllegalStateException> {
            session.contribute(contribution(snapshot).copy(symbols = listOf(
                symbol(Paths.get("src/main/java/example/Missing.java")),
            )))
        }
    }

    @Test
    fun `reconciliation invalidates only providers for changed languages`() {
        val first = snapshot()
        val session = WorkspaceIndexSession()
        session.open(first)
        session.contribute(contribution(first))

        val second = snapshot(typeScript = "export const answer = 43\n")
        val reconciled = requireNotNull(session.snapshot()).reconcile(second)
        assertTrue(typeScriptPath in reconciled.changes.modified)
        assertTrue(reconciled.invalidatedProviders.isEmpty())
        assertEquals(1, reconciled.index.symbolCount)

        val third = snapshot(java = "package example; public class AccountService {}\n", typeScript = "export const answer = 43\n")
        val invalidated = reconciled.index.reconcile(third)
        assertEquals(setOf("java-source-declarations-v1"), invalidated.invalidatedProviders)
        assertEquals(0, invalidated.index.symbolCount)
    }

    private fun snapshot(
        java: String = "package example; public class UserService {}\n",
        typeScript: String = "export const answer = 42\n",
    ) = ProjectSnapshot(
        workspace = Workspace(root),
        modules = emptyList(),
        files = listOf(
            SourceFile(javaPath, java, "java"),
            SourceFile(typeScriptPath, typeScript, "typescript"),
        ),
    )

    private fun contribution(snapshot: ProjectSnapshot) = WorkspaceSymbolContribution(
        providerId = "java-source-declarations-v1",
        languageId = "java",
        backend = "java-source-declarations-v1",
        evidence = SemanticEvidenceKind.LEXICAL,
        completeness = WorkspaceIndexCompleteness.DECLARATIONS,
        snapshotHash = snapshot.hash,
        symbols = listOf(symbol(javaPath)),
    )

    private fun symbol(path: java.nio.file.Path) = Symbol(
        SymbolId("example.UserService"),
        "UserService",
        Symbol.Kind.CLASS,
        SourceLocation(path, SourceRange(SourcePosition(0, 30), SourcePosition(0, 41))),
        "java",
    )
}
