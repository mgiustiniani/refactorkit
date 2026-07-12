package org.refactorkit.java

import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaMoveSourceRootPlannerTest {
    @Test
    fun previewsAppliesAndRollsBackByteIdenticalWholeRootRenames() {
        val root = reactorFixture()
        val scanner = JavaProjectScanner()
        val adapter = JavaLanguageAdapter()
        val before = scanner.scan(root)
        val originalTree = treeHashes(root)

        val plan = JavaMoveSourceRootPlanner(adapter).preview(before, FROM, TO)

        assertEquals(PatchStatus.PREVIEW, plan.status, "${plan.refusalCode}: ${plan.summary}")
        assertEquals(RefactoringEvidence.STRUCTURAL, plan.evidence)
        assertEquals(3, plan.workspaceEdit.edits.size)
        assertTrue(plan.workspaceEdit.edits.all { it is FileEdit.Rename })
        assertFalse(root.resolve(TO).exists(), "preview must not create destination directories")
        val staged = WorkspaceEditSimulator.apply(before, plan.workspaceEdit)
        plan.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>().forEach { rename ->
            assertEquals(before.files.single { it.path == rename.path }.content, staged.files.single { it.path == rename.newPath }.content)
        }
        assertTrue(
            plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR },
            plan.diagnosticsAfterPreview.toString(),
        )

        val applied = assertIs<ApplyResult.Applied>(PatchEngine(root).apply(
            plan, before, org.refactorkit.core.ApplyAuthorization.explicit("move-source-root-test"),
            DiagnosticsGate.enabled("java-jdt", adapter::diagnostics),
        ))
        assertFalse(root.resolve(FROM).resolve("example/shared/SharedValue.java").exists())
        assertTrue(root.resolve(TO).resolve("example/shared/SharedValue.java").exists())
        assertEquals("package example.shared;\npublic record SharedValue(String value) {}\n".toByteArray().toList(), root.resolve(TO).resolve("example/shared/SharedValue.java").readBytes().toList())
        assertTrue(adapter.diagnostics(scanner.scan(root)).none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })

        assertIs<ApplyResult.Applied>(PatchEngine(root).rollback(applied.transaction))
        assertEquals(originalTree, treeHashes(root).filterKeys { !it.startsWith(".refactorkit/") })
        assertFalse(root.resolve(TO).exists(), "rollback must remove transaction-created destination directories")
    }

    @Test
    fun buildModelOwnershipRemainsAuthoritativeWithoutCompatibilityRoots() {
        val root = reactorFixture()
        val snapshot = JavaProjectScanner().scan(root)
        val compatibilityStripped = snapshot.copy(modules = snapshot.modules.map { module ->
            module.copy(
                sourceRoots = emptyList(),
                mainSourceRoots = emptyList(),
                testSourceRoots = emptyList(),
                generatedSourceRoots = emptyList(),
                generatedTestSourceRoots = emptyList(),
            )
        })

        val plan = JavaMoveSourceRootPlanner().preview(compatibilityStripped, FROM, TO)

        assertEquals(PatchStatus.PREVIEW, plan.status, "${plan.refusalCode}: ${plan.summary}")
        assertEquals(3, plan.workspaceEdit.edits.size)
    }

    @Test
    fun languageAdapterExposesMoveSourceRootContract() {
        val root = reactorFixture()
        val adapter = JavaLanguageAdapter()
        val snapshot = JavaProjectScanner().scan(root)

        val plan = adapter.applyRefactoring(RefactoringRequest(
            operation = "moveSourceRoot",
            arguments = mapOf("from" to FROM.toString(), "to" to TO.toString()),
            snapshot = snapshot,
        ))

        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertTrue(adapter.availableRefactorings(org.refactorkit.core.CodeSelection(
            org.refactorkit.core.SourceLocation(
                Path.of("source/src/main/java/example/shared/SharedValue.java"),
                org.refactorkit.core.SourceRange(org.refactorkit.core.SourcePosition(0, 0), org.refactorkit.core.SourcePosition(0, 0)),
            ),
        )).any { it.id == "moveSourceRoot" })
    }

    @Test
    fun snapshotBoundPomDriftRefusesBeforeMove() {
        val root = reactorFixture()
        val scanner = JavaProjectScanner()
        val snapshot = scanner.scan(root)
        val plan = JavaMoveSourceRootPlanner().preview(snapshot, FROM, TO)
        assertEquals(PatchStatus.PREVIEW, plan.status)
        Files.writeString(root.resolve("destination/pom.xml"), Files.readString(root.resolve("destination/pom.xml")) + "\n")

        val result = PatchEngine(root).apply(
            plan, snapshot, org.refactorkit.core.ApplyAuthorization.explicit("move-source-root-test"),
            DiagnosticsGate.enabled("java-jdt", JavaLanguageAdapter()::diagnostics),
        )

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "snapshot.classpathChanged" })
        assertTrue(root.resolve(FROM).resolve("example/shared/SharedValue.java").exists())
    }

    @Test
    fun refusesUnsafeRootsPackagesDuplicatesAndCollisionsWithTypedCodes() {
        fun refusal(mutator: (Path) -> Unit = {}, from: Path = FROM, to: Path = TO): String? {
            val root = reactorFixture()
            mutator(root)
            return JavaMoveSourceRootPlanner().preview(JavaProjectScanner().scan(root), from, to).refusalCode
        }
        assertEquals("sourceRoot.overlap", refusal(to = FROM.resolve("nested")))
        assertEquals("sourceRoot.destinationUnrecognized", refusal(to = Path.of("unknown/src/main/java")))
        assertEquals("sourceRoot.packageMismatch", refusal({ root ->
            Files.writeString(root.resolve(FROM).resolve("example/shared/SharedValue.java"), "package wrong; public record SharedValue(String value) {}\n")
        }))
        assertEquals("sourceRoot.destinationCollision", refusal({ root ->
            val collision = root.resolve(TO).resolve("example/shared/sharedvalue.java")
            Files.createDirectories(collision.parent)
            Files.writeString(collision, "package example.shared; class other {}\n")
        }))
        assertEquals("sourceRoot.duplicateType", refusal({ root ->
            val duplicate = root.resolve("destination/src/test/java/example/shared/SharedValue.java")
            Files.createDirectories(duplicate.parent)
            Files.writeString(duplicate, "package example.shared; public record SharedValue(String value) {}\n")
        }))
    }

    @Test
    fun refusesGeneratedAndSymlinkRoots() {
        val root = reactorFixture()
        val generated = root.resolve("source/target/generated-sources/annotations/example/Generated.java")
        Files.createDirectories(generated.parent)
        Files.writeString(generated, "package example; public class Generated {}\n")
        val generatedPlan = JavaMoveSourceRootPlanner().preview(
            JavaProjectScanner().scan(root), Path.of("source/target/generated-sources/annotations"), TO,
        )
        assertEquals("sourceRoot.generated", generatedPlan.refusalCode)

        val generatedDestinationRoot = reactorFixture()
        val generatedDestination = generatedDestinationRoot.resolve("destination/target/generated-sources/annotations/example/Generated.java")
        Files.createDirectories(generatedDestination.parent)
        Files.writeString(generatedDestination, "package example; public class Generated {}\n")
        val generatedDestinationPlan = JavaMoveSourceRootPlanner().preview(
            JavaProjectScanner().scan(generatedDestinationRoot), FROM,
            Path.of("destination/target/generated-sources/annotations"),
        )
        assertEquals("sourceRoot.generated", generatedDestinationPlan.refusalCode)

        val symlinkRoot = reactorFixture()
        val link = symlinkRoot.resolve("destination/src")
        if (runCatching { Files.createSymbolicLink(link, symlinkRoot.resolve("source/src")); true }.getOrDefault(false)) {
            val plan = JavaMoveSourceRootPlanner().preview(JavaProjectScanner().scan(symlinkRoot), FROM, TO)
            assertEquals("sourceRoot.symlinkEscape", plan.refusalCode)
        }
    }

    private fun reactorFixture(): Path {
        val root = Files.createTempDirectory("refactorkit-move-source-root")
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging>
              <properties><java.version>21</java.version><maven.compiler.release>${'$'}{java.version}</maven.compiler.release></properties>
              <modules><module>source</module><module>destination</module></modules>
            </project>
        """.trimIndent())
        childPom(root, "source")
        childPom(root, "destination")
        val source = root.resolve(FROM)
        Files.createDirectories(source.resolve("example/shared"))
        Files.writeString(source.resolve("example/shared/SharedValue.java"), "package example.shared;\npublic record SharedValue(String value) {}\n")
        Files.writeString(source.resolve("example/shared/package-info.java"), "@Deprecated\npackage example.shared;\n")
        Files.writeString(source.resolve("module-info.java"), "module example.shared.module { exports example.shared; }\n")
        return root
    }

    private fun childPom(root: Path, module: String) {
        Files.createDirectories(root.resolve(module))
        Files.writeString(root.resolve("$module/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>example</groupId><artifactId>root</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent>
              <artifactId>$module</artifactId>
            </project>
        """.trimIndent())
    }

    private fun treeHashes(root: Path): Map<String, String> = Files.walk(root).use { stream ->
        stream.filter(Files::isRegularFile).filter { !root.relativize(it).toString().replace('\\', '/').startsWith(".refactorkit/") }
            .toList().associate { path ->
                root.relativize(path).toString().replace('\\', '/') to MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
            }
    }

    companion object {
        private val FROM = Path.of("source/src/main/java")
        private val TO = Path.of("destination/src/main/java")
    }
}
