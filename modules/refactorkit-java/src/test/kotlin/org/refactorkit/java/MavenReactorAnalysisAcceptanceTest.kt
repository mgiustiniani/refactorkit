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
    fun nearestDependencyMediationAndNonTransitiveScopesMatchMavenCompileVisibility() {
        val root = Files.createTempDirectory("refactorkit-maven-mediation")
        val repository = Files.createTempDirectory("refactorkit-maven-mediation-m2")
        installArtifact(repository, "fixture.dep", "common", "1", "fixture/dep/CommonApi.java",
            "package fixture.dep; public class CommonApi { public void oldVersion() {} }\n")
        installArtifact(repository, "fixture.dep", "common", "2", "fixture/dep/CommonApi.java",
            "package fixture.dep; public class CommonApi { public void selected() {} }\n")
        installArtifact(repository, "fixture.dep", "bridge", "1", "fixture/dep/Bridge.java",
            "package fixture.dep; public class Bridge {}\n", """
                <dependency><groupId>fixture.dep</groupId><artifactId>common</artifactId><version>1</version></dependency>
            """.trimIndent())
        installArtifact(repository, "fixture.dep", "optional-child", "1", "fixture/dep/OptionalChild.java",
            "package fixture.dep; public class OptionalChild {}\n")
        installArtifact(repository, "fixture.dep", "optional-parent", "1", "fixture/dep/OptionalParent.java",
            "package fixture.dep; public class OptionalParent {}\n", """
                <dependency><groupId>fixture.dep</groupId><artifactId>optional-child</artifactId><version>1</version><optional>true</optional></dependency>
            """.trimIndent())
        installArtifact(repository, "fixture.dep", "excluded-child", "1", "fixture/dep/ExcludedChild.java",
            "package fixture.dep; public class ExcludedChild {}\n")
        installArtifact(repository, "fixture.dep", "excluded-parent", "1", "fixture/dep/ExcludedParent.java",
            "package fixture.dep; public class ExcludedParent {}\n", """
                <dependency><groupId>fixture.dep</groupId><artifactId>excluded-child</artifactId><version>1</version></dependency>
            """.trimIndent())
        installArtifact(repository, "fixture.dep", "provided-child", "1", "fixture/dep/ProvidedChild.java",
            "package fixture.dep; public class ProvidedChild {}\n")
        installArtifact(repository, "fixture.dep", "provided-parent", "1", "fixture/dep/ProvidedParent.java",
            "package fixture.dep; public class ProvidedParent {}\n", """
                <dependency><groupId>fixture.dep</groupId><artifactId>provided-child</artifactId><version>1</version></dependency>
            """.trimIndent())
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>mediation</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <dependencies>
                <dependency><groupId>fixture.dep</groupId><artifactId>bridge</artifactId><version>1</version></dependency>
                <dependency><groupId>fixture.dep</groupId><artifactId>common</artifactId><version>2</version></dependency>
                <dependency><groupId>fixture.dep</groupId><artifactId>optional-parent</artifactId><version>1</version></dependency>
                <dependency><groupId>fixture.dep</groupId><artifactId>excluded-parent</artifactId><version>1</version><exclusions><exclusion><groupId>fixture.dep</groupId><artifactId>excluded-child</artifactId></exclusion></exclusions></dependency>
                <dependency><groupId>fixture.dep</groupId><artifactId>provided-parent</artifactId><version>1</version><scope>provided</scope></dependency>
              </dependencies>
            </project>
        """.trimIndent())
        val source = root.resolve("src/main/java/fixture/Mediation.java")
        Files.createDirectories(source.parent)
        Files.writeString(source,
            "package fixture; import fixture.dep.CommonApi; class Mediation { void run() { new CommonApi().selected(); } }\n")

        val snapshot = JavaProjectScanner(localMavenRepository = repository).scan(root)
        val classpathNames = snapshot.modules.single().mainClasspathEntries.map { it.fileName.toString() }
        assertTrue("common-2.jar" in classpathNames, classpathNames.toString())
        assertTrue("common-1.jar" !in classpathNames, classpathNames.toString())
        assertTrue("optional-child-1.jar" !in classpathNames, classpathNames.toString())
        assertTrue("excluded-child-1.jar" !in classpathNames, classpathNames.toString())
        assertTrue("provided-child-1.jar" !in classpathNames, classpathNames.toString())
        val diagnostics = JavaLanguageAdapter().authoritativeDiagnostics(
            snapshot,
            Path.of(System.getProperty("java.home")),
        )
        assertTrue(diagnostics.none { it.severity == Diagnostic.Severity.ERROR }, diagnostics.toString())
    }

    @Test
    fun missingTestArtifactSuppressesOnlyTestDerivativesAndPreservesMainErrors() {
        val root = Files.createTempDirectory("refactorkit-source-set-availability")
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>availability</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <dependencies><dependency><groupId>fixture.missing</groupId><artifactId>test-api</artifactId><version>1</version><scope>test</scope></dependency></dependencies>
            </project>
        """.trimIndent())
        val main = root.resolve("src/main/java/fixture/MainFailure.java")
        Files.createDirectories(main.parent)
        Files.writeString(main, "package fixture; class MainFailure { MissingMain value; }\n")
        val test = root.resolve("src/test/java/fixture/TestUnavailable.java")
        Files.createDirectories(test.parent)
        Files.writeString(test, "package fixture; import fixture.missing.TestApi; class TestUnavailable { TestApi value; }\n")

        val snapshot = JavaProjectScanner(localMavenRepository = Files.createTempDirectory("empty-m2")).scan(root)
        val diagnostics = JavaLanguageAdapter().authoritativeDiagnostics(
            snapshot,
            Path.of(System.getProperty("java.home")),
        )

        assertEquals(1, diagnostics.count { it.code == "classpath.unavailable" }, diagnostics.toString())
        assertTrue(
            diagnostics.single { it.code == "classpath.unavailable" }.message.contains(":test'"),
            diagnostics.toString(),
        )
        assertTrue(diagnostics.any { it.message.contains("MissingMain") }, diagnostics.toString())
        assertTrue(diagnostics.none { it.message.contains("TestApi") }, diagnostics.toString())
    }

    @Test
    fun unavailableMainEnvironmentPropagatesOnlyThroughDependentSourceSets() {
        val root = Files.createTempDirectory("refactorkit-transitive-availability")
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>availability-reactor</artifactId><version>1</version><packaging>pom</packaging>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <modules><module>broken</module><module>downstream</module><module>independent</module></modules>
            </project>
        """.trimIndent())
        fun modulePom(name: String, dependencies: String = "") = """
            <project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId><artifactId>availability-reactor</artifactId><version>1</version></parent><artifactId>$name</artifactId>$dependencies</project>
        """.trimIndent()
        Files.createDirectories(root.resolve("broken/src/main/java/fixture/broken"))
        Files.writeString(root.resolve("broken/src/main/java/fixture/broken/BrokenApi.java"),
            "package fixture.broken; public class BrokenApi { MissingExternal value; }\n")
        Files.writeString(root.resolve("broken/pom.xml"), modulePom("broken", """
            <dependencies><dependency><groupId>fixture.missing</groupId><artifactId>compile-api</artifactId><version>1</version></dependency></dependencies>
        """.trimIndent()))
        Files.createDirectories(root.resolve("downstream/src/main/java/fixture/downstream"))
        Files.writeString(root.resolve("downstream/src/main/java/fixture/downstream/Downstream.java"),
            "package fixture.downstream; import fixture.broken.BrokenApi; class Downstream { BrokenApi api; }\n")
        Files.writeString(root.resolve("downstream/pom.xml"), modulePom("downstream", """
            <dependencies><dependency><groupId>fixture</groupId><artifactId>broken</artifactId><version>1</version></dependency></dependencies>
        """.trimIndent()))
        Files.createDirectories(root.resolve("independent/src/main/java/fixture/independent"))
        Files.writeString(root.resolve("independent/src/main/java/fixture/independent/Independent.java"),
            "package fixture.independent; class Independent { GenuineMainError value; }\n")
        Files.writeString(root.resolve("independent/pom.xml"), modulePom("independent"))

        val snapshot = JavaProjectScanner(localMavenRepository = Files.createTempDirectory("empty-m2")).scan(root)
        val diagnostics = JavaLanguageAdapter().authoritativeDiagnostics(
            snapshot,
            Path.of(System.getProperty("java.home")),
        )

        val roots = diagnostics.filter { it.code == "classpath.unavailable" }
        assertEquals(2, roots.size, diagnostics.toString())
        assertTrue(roots.any { it.message.contains("'broken:main'") }, roots.toString())
        assertTrue(roots.any { it.message.contains("'downstream:main'") }, roots.toString())
        assertTrue(diagnostics.none { it.message.contains("MissingExternal") }, diagnostics.toString())
        assertTrue(diagnostics.any { it.message.contains("GenuineMainError") }, diagnostics.toString())
    }

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
        val authoritativeDiagnostics = adapter.authoritativeDiagnostics(
            snapshot,
            Path.of(System.getProperty("java.home")),
        ).filter { it.severity == Diagnostic.Severity.ERROR }
        assertTrue(authoritativeDiagnostics.isEmpty(), authoritativeDiagnostics.toString())
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
                mainRuntimeClasspathEntries = emptyList(),
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
    fun activeProfileBuildHelperRootsAreModeledWithoutExecutingPlugins() {
        val root = Files.createTempDirectory("refactorkit-maven-custom-roots")
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>custom-roots</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <profiles>
                <profile><id>default-custom-roots</id><activation><activeByDefault>true</activeByDefault></activation><build><plugins>
                  <plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><executions>
                    <execution><id>main</id><goals><goal>add-source</goal></goals><configuration><sources><source>src/feature/java</source></sources></configuration></execution>
                    <execution><id>test</id><goals><goal>add-test-source</goal></goals><configuration><sources><source>src/verification/java</source></sources></configuration></execution>
                  </executions></plugin>
                </plugins></build></profile>
                <profile><id>explicit-custom</id><build><plugins>
                  <plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><executions>
                    <execution><goals><goal>add-source</goal></goals><configuration><sources><source>src/explicit/java</source></sources></configuration></execution>
                  </executions></plugin>
                </plugins></build></profile>
                <profile><id>inactive</id><activation><property><name>never.active</name></property></activation><build><plugins>
                  <plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><executions>
                    <execution><goals><goal>add-source</goal></goals><configuration><sources><source>src/inactive/java</source></sources></configuration></execution>
                  </executions></plugin>
                </plugins></build></profile>
              </profiles>
            </project>
        """.trimIndent())
        val main = root.resolve("src/feature/java/fixture/Feature.java")
        Files.createDirectories(main.parent)
        Files.writeString(main, "package fixture; public record Feature(String value) {}\n")
        val test = root.resolve("src/verification/java/fixture/FeatureTest.java")
        Files.createDirectories(test.parent)
        Files.writeString(test, "package fixture; class FeatureTest { Feature value = new Feature(\"ok\"); }\n")
        val explicit = root.resolve("src/explicit/java/fixture/Explicit.java")
        Files.createDirectories(explicit.parent)
        Files.writeString(explicit, "package fixture; public class Explicit {}\n")
        val inactive = root.resolve("src/inactive/java/fixture/Inactive.java")
        Files.createDirectories(inactive.parent)
        Files.writeString(inactive, "package fixture; class Inactive { this is not Java }\n")

        val snapshot = JavaProjectScanner(localMavenRepository = Files.createTempDirectory("empty-m2")).scan(root)
        val module = snapshot.modules.single()

        assertTrue(Path.of("src/feature/java") in module.mainSourceRoots)
        assertTrue(Path.of("src/verification/java") in module.testSourceRoots)
        assertFalse(Path.of("src/explicit/java") in module.sourceRoots)
        assertFalse(Path.of("src/inactive/java") in module.sourceRoots)
        assertFalse(snapshot.files.any { it.path.startsWith(Path.of("src/inactive/java")) })
        val model = snapshot.buildModels.single()
        assertEquals(BuildModelStatus.AVAILABLE, model.status)
        assertTrue(model.modules.single().sourceSets.single { it.kind == SourceSetKind.MAIN }
            .sourceRoots.contains(Path.of("src/feature/java")))
        assertTrue(model.modules.single().sourceSets.single { it.kind == SourceSetKind.TEST }
            .sourceRoots.contains(Path.of("src/verification/java")))
        val diagnostics = JavaLanguageAdapter().diagnostics(snapshot)
            .filter { it.severity == Diagnostic.Severity.ERROR }
        assertTrue(diagnostics.isEmpty(), diagnostics.toString())

        val explicitSnapshot = JavaProjectScanner(
            localMavenRepository = Files.createTempDirectory("empty-explicit-m2"),
            activeMavenProfiles = setOf("explicit-custom"),
        ).scan(root)
        val explicitModule = explicitSnapshot.modules.single()
        assertTrue(Path.of("src/explicit/java") in explicitModule.mainSourceRoots)
        assertFalse(
            Path.of("src/feature/java") in explicitModule.sourceRoots,
            "explicit activation disables the activeByDefault profile",
        )
        assertEquals("explicit-custom", explicitSnapshot.buildModels.single().attributes["activeProfiles"])
        assertFalse(snapshot.hash == explicitSnapshot.hash)
    }

    @Test
    fun sourceRootsOutsideWorkspaceAreRejectedWithoutPathDisclosure() {
        val parent = Files.createTempDirectory("refactorkit-maven-root-boundary")
        val root = parent.resolve("workspace")
        val outside = parent.resolve("outside/fixture/Outside.java")
        Files.createDirectories(root)
        Files.createDirectories(outside.parent)
        Files.writeString(outside, "package fixture; public class Outside {}\n")
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>unsafe-root</artifactId><version>1</version>
              <build><sourceDirectory>${'$'}{project.basedir}/../outside</sourceDirectory></build>
            </project>
        """.trimIndent())

        val snapshot = JavaProjectScanner(localMavenRepository = Files.createTempDirectory("empty-m2")).scan(root)

        assertEquals(BuildModelStatus.UNAVAILABLE, snapshot.buildModels.single().status)
        assertFalse(snapshot.files.any { it.path.toString().contains("Outside.java") })
        val diagnostic = snapshot.buildModels.single().diagnostics.single { it.code == "buildModel.unavailable" }
        assertFalse(diagnostic.message.contains(parent.toString()), diagnostic.message)
        assertTrue(diagnostic.message.contains("source root declaration"), diagnostic.message)
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

    private fun installArtifact(
        repository: Path,
        group: String,
        artifact: String,
        version: String,
        sourcePath: String,
        source: String,
        dependencies: String = "",
    ) {
        val directory = group.split('.').fold(repository, Path::resolve).resolve("$artifact/$version")
        compileJar(directory.resolve("$artifact-$version.jar"), sourcePath, source)
        Files.writeString(directory.resolve("$artifact-$version.pom"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version>
              <dependencies>$dependencies</dependencies>
            </project>
        """.trimIndent())
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
