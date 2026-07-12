package org.refactorkit.java

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MavenReactorAnalysisAcceptanceTest {
    @Test
    fun effectiveJava21ReactorHasCleanPerSourceSetDiagnosticsAndCrossModuleBindings() {
        val root = Files.createTempDirectory("refactorkit-reactor-21")
        val repository = Files.createTempDirectory("refactorkit-m2")
        installTestApiAndBom(repository)
        createReactor(root, missingDependency = false)

        val snapshot = JavaProjectScanner(localMavenRepository = repository).scan(root)
        val modules = snapshot.modules.associateBy { it.name }
        assertEquals(setOf("domain", "application", "infrastructure", "acceptance-tests"), modules.keys)
        assertTrue(modules.values.all { it.languageSettings["java.sourceLevel"] == "21" })
        assertEquals(listOf("domain"), modules.getValue("application").mainDependencies)
        assertEquals(listOf("application"), modules.getValue("infrastructure").mainDependencies)
        val acceptance = modules.getValue("acceptance-tests")
        val testApi = acceptance.testClasspathEntries.single { it.fileName.toString() == "test-api-1.jar" }
        assertFalse(testApi in acceptance.mainClasspathEntries)
        assertTrue(testApi in acceptance.testClasspathEntries)
        assertTrue(acceptance.generatedTestSourceRoots.any { it.toString().replace('\\', '/').contains("generated-test-sources/test-annotations") })
        assertTrue(snapshot.classpathEvidence.any { it.kind == ClasspathEvidenceKind.IMPORTED_BOM })
        assertTrue(snapshot.classpathEvidence.any { it.kind == ClasspathEvidenceKind.LOCAL_REPOSITORY_ARTIFACT && it.path == testApi })
        val buildModel = snapshot.buildModels.single()
        assertEquals("maven-effective-v1", buildModel.providerId)
        assertEquals(BuildModelStatus.AVAILABLE, buildModel.status)
        assertEquals("maven", buildModel.attributes["ecosystem"])
        assertEquals("embedded-effective-model", buildModel.attributes["strategy"])
        val acceptanceBuildModule = buildModel.modules.single { it.id == "acceptance-tests" }
        val mainSourceSet = acceptanceBuildModule.sourceSets.single { it.kind == SourceSetKind.MAIN }
        val testSourceSet = acceptanceBuildModule.sourceSets.single { it.kind == SourceSetKind.TEST }
        assertFalse(testApi in mainSourceSet.classpathEntries)
        assertTrue(testApi in testSourceSet.classpathEntries)
        assertTrue(testSourceSet.generatedSourceRoots.any {
            it.toString().replace('\\', '/').contains("generated-test-sources/test-annotations")
        })

        val adapter = JavaLanguageAdapter()
        val diagnostics = adapter.diagnostics(snapshot).filter { it.severity == Diagnostic.Severity.ERROR }
        assertTrue(diagnostics.isEmpty(), diagnostics.toString())
        val symbols = adapter.buildSymbols(snapshot)
        assertTrue(symbols.symbols.any { it.id.value == "fixture.domain.DomainValue" })
        val definition = adapter.findSymbol(snapshot, org.refactorkit.core.SymbolId("fixture.domain.DomainValue"))
        assertEquals(Path.of("domain/src/main/java/fixture/domain/DomainValue.java"), definition?.location?.path)
        val references = adapter.findReferences(org.refactorkit.core.SymbolId("fixture.domain.DomainValue"))
        assertTrue(references.any { it.location.path.toString().replace('\\', '/').startsWith("application/") })

        val compatibilityStripped = snapshot.copy(modules = snapshot.modules.map { module ->
            module.copy(
                mainSourceRoots = emptyList(),
                testSourceRoots = emptyList(),
                generatedSourceRoots = emptyList(),
                generatedTestSourceRoots = emptyList(),
                mainClasspathEntries = emptyList(),
                testClasspathEntries = emptyList(),
                mainDependencies = emptyList(),
                testDependencies = emptyList(),
            )
        })
        val modelBackedDiagnostics = adapter.diagnostics(compatibilityStripped)
            .filter { it.severity == Diagnostic.Severity.ERROR }
        assertTrue(modelBackedDiagnostics.isEmpty(), modelBackedDiagnostics.toString())
        val modelBackedAnalysis = JdtJavaSemanticAnalyzer().analyze(compatibilityStripped)
        assertTrue(modelBackedAnalysis.symbols.any { it.qualifiedName == "fixture.domain.DomainValue" })
        assertTrue(modelBackedAnalysis.references.any { reference ->
            reference.symbolQualifiedName == "fixture.domain.DomainValue" &&
                reference.path.toString().replace('\\', '/').startsWith("application/")
        })
    }

    @Test
    fun importedBomAndLocalArtifactDriftRefuseApplyUnderSnapshotGate() {
        listOf("bom", "artifact").forEach { changedInput ->
            val root = Files.createTempDirectory("refactorkit-reactor-drift-$changedInput")
            val repository = Files.createTempDirectory("refactorkit-m2-drift-$changedInput")
            installTestApiAndBom(repository)
            createReactor(root, missingDependency = false)
            val snapshot = JavaProjectScanner(localMavenRepository = repository).scan(root)
            val source = Path.of("domain/src/main/java/fixture/domain/DomainValue.java")
            val plan = PatchPlan(
                operation = "mavenEvidenceDrift",
                snapshotHash = snapshot.hash,
                confidence = 1.0,
                summary = "prove Maven input drift",
                affectedFiles = setOf(source),
                workspaceEdit = WorkspaceEdit(listOf(FileEdit.Delete(source))),
            )
            val changed = if (changedInput == "bom") repository.resolve("fixture/external/fixture-bom/1/fixture-bom-1.pom")
                else repository.resolve("fixture/external/test-api/1/test-api-1.jar")
            Files.write(changed, Files.readAllBytes(changed) + byteArrayOf(0))

            val result = PatchEngine(root).apply(
                plan, snapshot, ApplyAuthorization.explicit("maven-drift-test"),
                DiagnosticsGate.enabled("java-jdt", JavaLanguageAdapter()::diagnostics),
            )
            assertTrue(result is ApplyResult.Refused)
            assertTrue((result as ApplyResult.Refused).diagnostics.any { it.code == "snapshot.classpathChanged" })
            assertTrue(root.resolve(source).exists())
        }
    }

    @Test
    fun classifierTestJarAndSystemPathRemainScopeCorrectAndHashBound() {
        val root = Files.createTempDirectory("refactorkit-maven-variants")
        val repository = Files.createTempDirectory("refactorkit-maven-variants-m2")
        val normalJar = repository.resolve("fixture/external/variant-api/1/variant-api-1.jar")
        val classifierJar = repository.resolve("fixture/external/variant-api/1/variant-api-1-linux.jar")
        val testJar = repository.resolve("fixture/external/test-fixtures/1/test-fixtures-1-tests.jar")
        val systemJar = root.resolve("system-libs/system-api.jar")
        compileJar(normalJar, "fixture/external/NormalOnly.java", "package fixture.external; public @interface NormalOnly {}\n")
        compileJar(classifierJar, "fixture/external/LinuxOnly.java", "package fixture.external; public @interface LinuxOnly {}\n")
        compileJar(testJar, "fixture/external/FixtureOnly.java", "package fixture.external; public @interface FixtureOnly {}\n")
        compileJar(systemJar, "fixture/system/SystemOnly.java", "package fixture.system; public @interface SystemOnly {}\n")
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>variants</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <dependencies>
                <dependency><groupId>fixture.external</groupId><artifactId>variant-api</artifactId><version>1</version></dependency>
                <dependency><groupId>fixture.external</groupId><artifactId>variant-api</artifactId><version>1</version><classifier>linux</classifier></dependency>
                <dependency><groupId>fixture.external</groupId><artifactId>test-fixtures</artifactId><version>1</version><type>test-jar</type><scope>test</scope></dependency>
                <dependency><groupId>fixture.system</groupId><artifactId>system-api</artifactId><version>1</version><scope>system</scope><systemPath>${systemJar.toAbsolutePath()}</systemPath></dependency>
              </dependencies>
            </project>
        """.trimIndent())
        val main = root.resolve("src/main/java/fixture/VariantUse.java")
        Files.createDirectories(main.parent)
        Files.writeString(main, "package fixture; import fixture.external.LinuxOnly; import fixture.external.NormalOnly; import fixture.system.SystemOnly; @NormalOnly @LinuxOnly @SystemOnly public class VariantUse {}\n")
        val test = root.resolve("src/test/java/fixture/VariantTest.java")
        Files.createDirectories(test.parent)
        Files.writeString(test, "package fixture; import fixture.external.FixtureOnly; @FixtureOnly class VariantTest {}\n")

        val snapshot = JavaProjectScanner(localMavenRepository = repository).scan(root)
        val module = snapshot.modules.single()

        assertTrue(normalJar in module.mainClasspathEntries)
        assertTrue(classifierJar in module.mainClasspathEntries)
        assertTrue(systemJar in module.mainClasspathEntries)
        assertFalse(testJar in module.mainClasspathEntries)
        assertTrue(testJar in module.testClasspathEntries)
        assertTrue(snapshot.classpathEvidence.any {
            it.kind == ClasspathEvidenceKind.SYSTEM_PATH_ARTIFACT && it.path == systemJar
        })
        assertEquals(BuildModelStatus.AVAILABLE, snapshot.buildModels.single().status)
        val diagnostics = JavaLanguageAdapter().diagnostics(snapshot)
            .filter { it.severity == Diagnostic.Severity.ERROR }
        assertTrue(diagnostics.isEmpty(), diagnostics.toString())

        val source = Path.of("src/main/java/fixture/VariantUse.java")
        val plan = PatchPlan(
            operation = "systemPathEvidenceDrift",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "prove systemPath artifact drift",
            affectedFiles = setOf(source),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Delete(source))),
        )
        Files.write(systemJar, byteArrayOf(0), java.nio.file.StandardOpenOption.APPEND)
        val result = PatchEngine(root).apply(
            plan,
            snapshot,
            ApplyAuthorization.explicit("system-path-drift-test"),
            DiagnosticsGate.enabled("java-jdt", JavaLanguageAdapter()::diagnostics),
        )
        assertTrue(result is ApplyResult.Refused)
        val changed = (result as ApplyResult.Refused).diagnostics.single { it.code == "snapshot.classpathChanged" }
        assertFalse(changed.message.contains(root.toString()), changed.message)
        assertFalse(changed.message.contains(systemJar.toString()), changed.message)
        assertTrue(root.resolve(source).exists())
    }

    @Test
    fun oneMissingDirectArtifactProducesOneTypedRootDiagnosticWithoutJdtCascade() {
        val root = Files.createTempDirectory("refactorkit-reactor-missing")
        val repository = Files.createTempDirectory("refactorkit-missing-m2")
        installTestApiAndBom(repository)
        createReactor(root, missingDependency = true)

        val snapshot = JavaProjectScanner(localMavenRepository = repository).scan(root)
        val diagnostics = JavaLanguageAdapter().diagnostics(snapshot)
            .filter { it.severity == Diagnostic.Severity.ERROR }

        assertEquals(BuildModelStatus.OFFLINE_MISSING, snapshot.buildModels.single().status)
        assertTrue(snapshot.buildModels.single().diagnostics.any { it.code == "classpath.offlineMissing" })
        assertEquals(1, diagnostics.size, diagnostics.toString())
        assertEquals("classpath.unavailable", diagnostics.single().code)
        assertTrue(diagnostics.single().message.contains("acceptance-tests"))
        assertFalse(repository.resolve("fixture/external/missing-api/1/missing-api-1.jar").exists(), "offline default must not download")
        val verbose = JavaLanguageAdapter().diagnostics(
            JavaProjectScanner(localMavenRepository = repository).scan(root), verbose = true,
        )
        assertTrue(verbose.any { it.code == "java.jdt.typeResolution" })
    }

    private fun createReactor(root: Path, missingDependency: Boolean) {
        val textBlock = "\"\"\""
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>
              <properties><java.version>21</java.version><compiler.release>${'$'}{java.version}</compiler.release><fixture.bom.version>1</fixture.bom.version></properties>
              <dependencyManagement><dependencies>
                <dependency><groupId>fixture.external</groupId><artifactId>fixture-bom</artifactId><version>${'$'}{fixture.bom.version}</version><type>pom</type><scope>import</scope></dependency>
              </dependencies></dependencyManagement>
              <profiles><profile><id>defaults</id><activation><activeByDefault>true</activeByDefault></activation><properties><maven.compiler.release>${'$'}{compiler.release}</maven.compiler.release></properties></profile></profiles>
              <modules><module>domain</module><module>application</module><module>infrastructure</module><module>acceptance-tests</module></modules>
            </project>
        """.trimIndent())
        child(root, "domain", "", "src/main/java/fixture/domain/DomainValue.java", """
            package fixture.domain;
            public record DomainValue(String value) {
                public enum Kind { PRIMARY, SECONDARY }
                public String display() {
                    String prefix = $textBlock
                            value:
                            $textBlock;
                    return switch (value) { case "" -> prefix; default -> prefix + value; };
                }
            }
        """.trimIndent())
        child(root, "application", dependency("domain"), "src/main/java/fixture/application/ApplicationService.java", """
            package fixture.application;
            import fixture.domain.DomainValue;
            public class ApplicationService {
                public String apply(DomainValue value) { return switch (value) { case DomainValue(String text) -> text; }; }
            }
        """.trimIndent())
        child(root, "infrastructure", dependency("application"), "src/main/java/fixture/infrastructure/InfrastructureAdapter.java", """
            package fixture.infrastructure;
            import fixture.application.ApplicationService;
            public class InfrastructureAdapter { public ApplicationService service() { return new ApplicationService(); } }
        """.trimIndent())
        val external = if (missingDependency) "missing-api" else "test-api"
        child(root, "acceptance-tests", """
            <dependencies>
              <dependency><groupId>fixture</groupId><artifactId>domain</artifactId><version>${'$'}{project.version}</version><scope>test</scope></dependency>
              <dependency><groupId>fixture</groupId><artifactId>application</artifactId><version>${'$'}{project.version}</version><scope>test</scope></dependency>
              <dependency><groupId>fixture</groupId><artifactId>infrastructure</artifactId><version>${'$'}{project.version}</version><scope>test</scope></dependency>
              <dependency><groupId>fixture.external</groupId><artifactId>$external</artifactId>${if (missingDependency) "<version>1</version>" else ""}<scope>test</scope></dependency>
            </dependencies>
        """.trimIndent(), "src/test/java/fixture/acceptance/ReactorAcceptance.java", """
            package fixture.acceptance;
            import fixture.application.ApplicationService;
            import fixture.domain.DomainValue;
            import fixture.external.TestOnly;
            import fixture.infrastructure.InfrastructureAdapter;
            @TestOnly public class ReactorAcceptance {
                Object exercise() { return new InfrastructureAdapter().service().apply(new DomainValue("ok")); }
            }
        """.trimIndent())
        if (!missingDependency) {
            val generated = root.resolve("acceptance-tests/target/generated-test-sources/test-annotations/fixture/acceptance/GeneratedTestSupport.java")
            Files.createDirectories(generated.parent)
            Files.writeString(generated, "package fixture.acceptance; import fixture.external.TestOnly; @TestOnly public final class GeneratedTestSupport {}\n")
        }
    }

    private fun dependency(artifact: String): String = """
        <dependencies><dependency><groupId>fixture</groupId><artifactId>$artifact</artifactId><version>${'$'}{project.version}</version></dependency></dependencies>
    """.trimIndent()

    private fun child(root: Path, name: String, pomExtra: String, sourcePath: String, source: String) {
        val module = root.resolve(name)
        Files.createDirectories(module)
        Files.writeString(module.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>fixture</groupId><artifactId>reactor</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent>
              <artifactId>$name</artifactId>$pomExtra
            </project>
        """.trimIndent())
        val target = module.resolve(sourcePath)
        Files.createDirectories(target.parent)
        Files.writeString(target, source)
    }

    private fun compileJar(jarPath: Path, sourcePath: String, content: String) {
        val sourceDir = Files.createTempDirectory("refactorkit-variant-src")
        val source = sourceDir.resolve(sourcePath)
        val classes = sourceDir.resolve("classes")
        Files.createDirectories(source.parent)
        Files.createDirectories(classes)
        Files.writeString(source, content)
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        Files.createDirectories(requireNotNull(jarPath.parent))
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            Files.walk(classes).use { stream ->
                stream.filter(Files::isRegularFile).forEach { classFile ->
                    jar.putNextEntry(JarEntry(classes.relativize(classFile).toString().replace('\\', '/')))
                    Files.copy(classFile, jar)
                    jar.closeEntry()
                }
            }
        }
    }

    private fun installTestApiAndBom(repository: Path) {
        val sourceDir = Files.createTempDirectory("refactorkit-test-api-src")
        val source = sourceDir.resolve("fixture/external/TestOnly.java")
        val classes = sourceDir.resolve("classes")
        Files.createDirectories(source.parent)
        Files.createDirectories(classes)
        Files.writeString(source, "package fixture.external; public @interface TestOnly {}\n")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        val artifactDir = repository.resolve("fixture/external/test-api/1")
        Files.createDirectories(artifactDir)
        JarOutputStream(Files.newOutputStream(artifactDir.resolve("test-api-1.jar"))).use { jar ->
            val classFile = classes.resolve("fixture/external/TestOnly.class")
            jar.putNextEntry(JarEntry("fixture/external/TestOnly.class"))
            Files.copy(classFile, jar)
            jar.closeEntry()
        }
        Files.writeString(artifactDir.resolve("test-api-1.pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>fixture.external</groupId><artifactId>test-api</artifactId><version>1</version></project>")
        val bomDir = repository.resolve("fixture/external/fixture-bom/1")
        Files.createDirectories(bomDir)
        Files.writeString(bomDir.resolve("fixture-bom-1.pom"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.external</groupId><artifactId>fixture-bom</artifactId><version>1</version><packaging>pom</packaging>
              <dependencyManagement><dependencies><dependency><groupId>fixture.external</groupId><artifactId>test-api</artifactId><version>1</version></dependency></dependencies></dependencyManagement>
            </project>
        """.trimIndent())
    }
}
