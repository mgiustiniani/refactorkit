package org.refactorkit.java

import org.refactorkit.core.Module
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaImportTargetResolverTest {
    private val resolver = JavaImportTargetResolver()

    @Test
    fun resolvesMainDirectoryModuleSourceRootAndExactPackage() {
        val root = project("module-a/src/main/java/com/example/util")
        val target = assertIs<JavaImportTargetResolution.Resolved>(
            resolver.resolve(JavaProjectScanner().scan(root), "module-a/src/main/java/com/example/util"),
        ).target
        assertEquals("module-a", target.moduleName)
        assertEquals("module-a/src/main/java", target.sourceRoot.slashes())
        assertEquals(JavaSourceSet.MAIN, target.sourceSet)
        assertEquals("com.example.util", target.packageName)
    }

    @Test
    fun buildModelOwnershipResolvesTargetWithoutCompatibilityRoots() {
        val root = project("module-a/src/main/java/com/example/util")
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

        val target = assertIs<JavaImportTargetResolution.Resolved>(
            resolver.resolve(compatibilityStripped, "module-a/src/main/java/com/example/util"),
        ).target

        assertEquals("module-a", target.moduleName)
        assertEquals(JavaSourceSet.MAIN, target.sourceSet)
        assertEquals("com.example.util", target.packageName)
    }

    @Test
    fun resolvesDefaultPackageAndTestSourceSet() {
        val root = project("src/test/java")
        val resolution = resolver.resolve(JavaProjectScanner().scan(root), "src/test/java")
        val target = assertIs<JavaImportTargetResolution.Resolved>(resolution, resolution.toString()).target
        assertEquals("", target.packageName)
        assertEquals(JavaSourceSet.TEST, target.sourceSet)
    }

    @Test
    fun samePackageInMultipleModulesIsResolvedByDirectoryOwnership() {
        val root = project("api/src/main/java/com/shared", "service/src/main/java/com/shared")
        val target = assertIs<JavaImportTargetResolution.Resolved>(
            resolver.resolve(JavaProjectScanner().scan(root), "service/src/main/java/com/shared"),
        ).target
        assertEquals("service", target.moduleName)
        assertEquals("com.shared", target.packageName)
    }

    @Test
    fun refusesOverlappingSourceRoots() {
        val root = project("src/main/java/com/example")
        val snapshot = ProjectSnapshot(
            Workspace(root),
            listOf(
                Module("outer", root, listOf(Path.of("src/main/java"))),
                Module("inner", root, listOf(Path.of("src/main/java/com"))),
            ),
            emptyList(),
            setOf("java"),
        )
        assertRefusal(snapshot, "src/main/java/com/example", "targetDirectory.ambiguousSourceRoot")
    }

    @Test
    fun refusesOutsideSourceRootMissingFileAbsoluteTraversalAndWindowsAbsolute() {
        val root = project("src/main/java", "docs")
        root.resolve("not-directory").writeText("x")
        val snapshot = JavaProjectScanner().scan(root)
        assertRefusal(snapshot, "docs", "targetDirectory.outsideSourceRoot")
        assertRefusal(snapshot, "missing", "targetDirectory.missing")
        assertRefusal(snapshot, "not-directory", "targetDirectory.notDirectory")
        assertRefusal(snapshot, root.resolve("src/main/java").toString(), "targetDirectory.absolute")
        assertRefusal(snapshot, "src/main/../main/java", "targetDirectory.traversal")
        assertRefusal(snapshot, "src\\main\\..\\outside", "targetDirectory.traversal")
        assertRefusal(snapshot, "C:\\workspace\\src\\main\\java", "targetDirectory.absolute")
    }

    @Test
    fun refusesPackageAndModuleMismatchAndNonConformingDirectory() {
        val root = project("module/src/main/java/com/example", "module/src/main/java/not-valid")
        val snapshot = JavaProjectScanner().scan(root)
        assertRefusal(snapshot, "module/src/main/java/com/example", "targetDirectory.packageMismatch", "other.package")
        val moduleMismatch = resolver.resolve(snapshot, "module/src/main/java/com/example", requestedModule = "other")
        assertEquals("targetDirectory.moduleMismatch", assertIs<JavaImportTargetResolution.Refused>(moduleMismatch).refusal.code)
        assertRefusal(snapshot, "module/src/main/java/not-valid", "targetDirectory.nonConformingPackage")
    }

    @Test
    fun refusesGeneratedSourceRootExplicitly() {
        val root = project("module/src/generated/java/com/example")
        val snapshot = ProjectSnapshot(
            Workspace(root),
            listOf(Module("module", root.resolve("module"), listOf(Path.of("module/src/generated/java")))),
            emptyList(),
            setOf("java"),
        )
        assertRefusal(snapshot, "module/src/generated/java/com/example", "targetDirectory.generatedSource")
    }

    @Test
    fun refusesSymlinkEscapeWithoutExposingSource() {
        val root = project("src/main/java")
        val outside = Files.createTempDirectory("rk-import-target-outside")
        val link = root.resolve("src/main/java/escape")
        val created = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        if (!created) return
        val refusal = assertIs<JavaImportTargetResolution.Refused>(
            resolver.resolve(JavaProjectScanner().scan(root), "src/main/java/escape"),
        ).refusal
        assertEquals("targetDirectory.symlinkEscape", refusal.code)
        assertTrue(!refusal.message.contains("public class Secret"))
    }

    @Test
    fun refusesInternalSymlinkAndSymlinkSourceRoot() {
        val root = project("src/main/java/com/real")
        val internalLink = root.resolve("src/main/java/com/linked")
        if (runCatching { Files.createSymbolicLink(internalLink, root.resolve("src/main/java/com/real")) }.isSuccess) {
            assertRefusal(
                JavaProjectScanner().scan(root),
                "src/main/java/com/linked",
                "targetDirectory.symbolicLink",
            )
        }

        val sourceRootTarget = root.resolve("real-source/com/example")
        Files.createDirectories(sourceRootTarget)
        val sourceRootLink = root.resolve("linked-source")
        if (runCatching { Files.createSymbolicLink(sourceRootLink, root.resolve("real-source")) }.isSuccess) {
            val snapshot = ProjectSnapshot(
                Workspace(root),
                listOf(Module("linked", root, listOf(Path.of("linked-source")))),
                emptyList(),
                setOf("java"),
            )
            assertRefusal(snapshot, "linked-source/com/example", "targetDirectory.symbolicLink")
        }
    }

    @Test
    fun refusesDuplicateCanonicalSourceRoots() {
        val root = project("src/main/java/com/example")
        val snapshot = ProjectSnapshot(
            Workspace(root),
            listOf(Module(
                "duplicate",
                root,
                listOf(Path.of("src/main/java"), Path.of("src/main/./java")),
            )),
            emptyList(),
            setOf("java"),
        )
        assertRefusal(snapshot, "src/main/java/com/example", "targetDirectory.ambiguousSourceRoot")
    }

    private fun assertRefusal(
        snapshot: ProjectSnapshot,
        directory: String,
        code: String,
        requestedPackage: String? = null,
    ) {
        val refusal = assertIs<JavaImportTargetResolution.Refused>(
            resolver.resolve(snapshot, directory, requestedPackage),
        ).refusal
        assertEquals(code, refusal.code)
        assertTrue(refusal.nextAction.isNotBlank())
    }

    private fun project(vararg directories: String): Path {
        val root = Files.createTempDirectory("rk-import-target")
        directories.forEach { Files.createDirectories(root.resolve(it)) }
        return root
    }

    private fun Path.slashes() = toString().replace('\\', '/')
}
