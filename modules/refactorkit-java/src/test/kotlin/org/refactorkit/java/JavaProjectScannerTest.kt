package org.refactorkit.java

import org.refactorkit.core.ApplyResult
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.WorkspaceEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class JavaProjectScannerTest {
    @Test
    fun detectsHashBoundMavenAndGradleJavaSourceLevels() {
        val cases = listOf(
            Triple("maven-8", "pom.xml", "<project><properties><maven.compiler.source>1.8</maven.compiler.source></properties></project>"),
            Triple("maven-11", "pom.xml", "<project><properties><maven.compiler.release>11</maven.compiler.release></properties></project>"),
            Triple("maven-property-21", "pom.xml", "<project><properties><java.version>21</java.version><maven.compiler.release>\${java.version}</maven.compiler.release></properties></project>"),
            Triple("gradle-17", "build.gradle.kts", "java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }"),
            Triple("gradle-21", "build.gradle", "sourceCompatibility = JavaVersion.VERSION_21"),
            Triple("gradle-25", "build.gradle.kts", "java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }"),
        )
        cases.forEach { (name, descriptor, content) ->
            val root = Files.createTempDirectory("refactorkit-$name")
            Files.createDirectories(root.resolve("src/main/java/example"))
            Files.writeString(root.resolve("src/main/java/example/App.java"), "package example; class App {}\n")
            Files.writeString(root.resolve(descriptor), content)

            val snapshot = JavaProjectScanner().scan(root)

            assertEquals(name.substringAfterLast('-'), snapshot.modules.single().languageSettings["java.sourceLevel"])
            val descriptorEvidence = snapshot.classpathEvidence.single {
                it.path.toString().replace('\\', '/') == descriptor
            }
            assertEquals(ClasspathEvidenceKind.DECLARATION_FILE, descriptorEvidence.kind)
            assertEquals(64, descriptorEvidence.fingerprint.length)
        }
    }

    @Test
    fun defaultsUnconfiguredProjectsToJava8SourceSemantics() {
        val root = Files.createTempDirectory("refactorkit-java-default-level")
        Files.createDirectories(root.resolve("src/main/java/example"))
        Files.writeString(root.resolve("src/main/java/example/App.java"), "package example; class App {}\n")

        val snapshot = JavaProjectScanner().scan(root)

        assertEquals("8", snapshot.modules.single().languageSettings["java.sourceLevel"])
    }

    @Test
    fun scansConventionalJavaSourceRoot() {
        val root = Files.createTempDirectory("refactorkit-java-scan")
        val sourceDir = root.resolve("src/main/java/com/example")
        Files.createDirectories(sourceDir)
        Files.writeString(sourceDir.resolve("UserManager.java"), "package com.example;\n\npublic class UserManager {}\n")

        val snapshot = JavaProjectScanner().scan(root)
        val symbols = JavaLanguageAdapter().buildSymbols(snapshot)

        assertEquals(1, snapshot.files.size)
        assertTrue(symbols.symbols.any { it.id.value == "com.example.UserManager" })
    }

    @Test
    fun discoversConventionalCompiledOutputsAndLocalJars() {
        val root = Files.createTempDirectory("refactorkit-java-classpath-scan")
        Files.createDirectories(root.resolve("src/main/java/com/example"))
        Files.writeString(root.resolve("src/main/java/com/example/App.java"), "package com.example; public class App {}\n")
        Files.createDirectories(root.resolve("target/classes"))
        Files.createDirectories(root.resolve("build/classes/java/main"))
        Files.createDirectories(root.resolve("libs"))
        Files.write(root.resolve("libs/local.jar"), byteArrayOf())
        Files.createDirectories(root.resolve("dependency-cache"))
        Files.writeString(root.resolve("target/classpath.txt"), "# generated dependency classpath\ndependency-cache\nmissing.jar\n")

        val snapshot = JavaProjectScanner().scan(root)
        val module = snapshot.modules.single()
        val classpath = module.classpathEntries.map { it.toString().replace('\\', '/') }.toSet()
        val evidence = snapshot.classpathEvidence.associateBy {
            it.path.toString().replace('\\', '/') to it.kind
        }

        assertTrue("target/classes" in classpath)
        assertTrue("build/classes/java/main" in classpath)
        assertTrue("libs/local.jar" in classpath)
        assertTrue("dependency-cache" in classpath)
        assertTrue("missing.jar" !in classpath)
        assertTrue(("dependency-cache" to ClasspathEvidenceKind.ENTRY) in evidence)
        assertTrue(("target/classpath.txt" to ClasspathEvidenceKind.DECLARATION_FILE) in evidence)
        assertTrue(("libs" to ClasspathEvidenceKind.JAR_DIRECTORY) in evidence)
        assertEquals(
            "missing",
            evidence.getValue("target/test-classes" to ClasspathEvidenceKind.ENTRY).fingerprint,
        )
    }

    @Test
    fun refusesApplyWhenBuildDescriptorChangesAfterScan() {
        val root = Files.createTempDirectory("refactorkit-java-source-level-evidence")
        val source = Path.of("src/main/java/com/example/App.java")
        Files.createDirectories(root.resolve(source).parent)
        Files.writeString(root.resolve(source), "package com.example; public class App {}\n")
        Files.writeString(root.resolve("pom.xml"), "<project><properties><maven.compiler.release>17</maven.compiler.release></properties></project>")
        val snapshot = JavaProjectScanner().scan(root)
        val plan = PatchPlan(
            operation = "sourceLevelEvidence",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "refuse stale source level",
            affectedFiles = setOf(source),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Delete(source))),
        )
        Files.writeString(root.resolve("pom.xml"), "<project><properties><maven.compiler.release>21</maven.compiler.release></properties></project>")

        val result = PatchEngine(root).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "snapshot.classpathChanged" }, result.diagnostics.toString())
    }

    @Test
    fun refusesApplyWhenGeneratedClasspathDeclarationChangesAfterScan() {
        val root = Files.createTempDirectory("refactorkit-java-classpath-evidence")
        val source = Path.of("src/main/java/com/example/App.java")
        Files.createDirectories(root.resolve(source).parent)
        Files.writeString(root.resolve(source), "package com.example; public class App {}\n")
        Files.createDirectories(root.resolve("dependency-cache"))
        Files.createDirectories(root.resolve("other-dependency-cache"))
        Files.createDirectories(root.resolve("target"))
        Files.writeString(root.resolve("target/classpath.txt"), "dependency-cache\n")
        val snapshot = JavaProjectScanner().scan(root)
        val plan = PatchPlan(
            operation = "classpathDeclarationEvidence",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "refuse stale generated classpath",
            affectedFiles = setOf(source),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Delete(source))),
        )
        Files.writeString(root.resolve("target/classpath.txt"), "other-dependency-cache\n")

        val result = PatchEngine(root).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "snapshot.classpathChanged" }, result.diagnostics.toString())
        assertTrue(Files.exists(root.resolve(source)))
    }

    @Test
    fun detectsMultiModuleSourceRoots() {
        val root = Files.createTempDirectory("refactorkit-java-multimodule-scan")
        val apiDir = root.resolve("api/src/main/java/com/example/api")
        val serviceDir = root.resolve("service/src/main/java/com/example/service")
        Files.createDirectories(apiDir)
        Files.createDirectories(serviceDir)
        Files.writeString(apiDir.resolve("UserApi.java"), "package com.example.api;\npublic interface UserApi {}\n")
        Files.writeString(serviceDir.resolve("UserService.java"), "package com.example.service;\npublic class UserService {}\n")

        val snapshot = JavaProjectScanner().scan(root)
        val moduleNames = snapshot.modules.map { it.name }.toSet()
        val symbols = JavaLanguageAdapter().buildSymbols(snapshot).symbols.map { it.id.value }.toSet()

        assertEquals(setOf("api", "service"), moduleNames)
        assertEquals(2, snapshot.files.size)
        assertTrue("com.example.api.UserApi" in symbols)
        assertTrue("com.example.service.UserService" in symbols)
    }
}
