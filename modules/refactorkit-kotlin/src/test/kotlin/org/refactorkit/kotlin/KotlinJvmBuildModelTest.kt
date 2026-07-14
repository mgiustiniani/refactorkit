package org.refactorkit.kotlin

import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.Workspace
import org.refactorkit.java.JavaProjectScanner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KotlinJvmBuildModelTest {
    @Test
    fun projectsMavenKotlinJvmOwnershipTargetsAndToolchainEvidenceIntoSnapshot() {
        val root = Files.createTempDirectory("refactorkit-kotlin-maven-model")
        root.resolve("pom.xml").writeText("""
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId><artifactId>mixed</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins><plugin>
                <groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId><version>2.0.21</version>
                <configuration><jvmTarget>21</jvmTarget><jdkToolchain><version>21</version></jdkToolchain></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent())
        source(root, "src/main/java/fixture/JavaApi.java", "package fixture; public class JavaApi {}\n")
        source(root, "src/main/kotlin/fixture/KotlinApi.kt", "package fixture\nclass KotlinApi(val java: JavaApi)\n")

        val base = JavaProjectScanner().scan(root)
        val attached = KotlinJvmBuildModelIntegration.attach(base, toolchain("a".repeat(64)))
        val model = attached.buildModels.single { it.providerId == KotlinJvmBuildModelProjector.PROVIDER_ID }
        val main = model.modules.single().sourceSets.single { it.kind == SourceSetKind.MAIN }

        assertEquals(BuildModelStatus.AVAILABLE, model.status)
        assertEquals(listOf(Path.of("src/main/kotlin")), main.sourceRoots)
        assertEquals("21", main.attributes["kotlin.jvmTarget"])
        assertEquals("21", main.attributes["kotlin.targetJdk"])
        assertEquals("21", main.attributes["analysisJdk"])
        assertTrue(Path.of("target/classes") in main.outputDirectories)
        assertEquals("a".repeat(64), model.attributes["toolchainProjectionHash"])
        assertEquals(64, model.attributes.getValue("projectionHash").length)
        assertNotEquals(base.hash, attached.hash)
        assertTrue(attached.files.any { it.path.endsWith("KotlinApi.kt") && it.languageId == "kotlin" })
        assertTrue(model.attributes.values.none { it.contains(root.toString()) })
    }

    @Test
    fun projectsGradleKotlinRootsWithoutExecutingBuildAndRetainsPartialEvidence() {
        val root = Files.createTempDirectory("refactorkit-kotlin-gradle-model")
        root.resolve("build.gradle.kts").writeText("""
            plugins { kotlin("jvm") version "2.0.21" }
            kotlin { jvmToolchain(21) }
            kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
            sourceSets.create("integrationTest")
            sourceSets["integrationTest"].kotlin.srcDir("src/integration/kotlin")
            file("executed-marker").writeText("must-not-run")
        """.trimIndent())
        source(root, "src/main/kotlin/fixture/Main.kt", "package fixture\nclass Main\n")
        source(root, "src/test/kotlin/fixture/MainTest.kt", "package fixture\nclass MainTest\n")
        source(root, "build/generated/ksp/main/kotlin/fixture/Generated.kt", "package fixture\nclass Generated\n")
        source(root, "src/integration/kotlin/fixture/Integration.kt", "package fixture\nclass Integration\n")

        val base = JavaProjectScanner().scan(root)
        val attached = KotlinJvmBuildModelIntegration.attach(base, toolchain("b".repeat(64)))
        val model = attached.buildModels.single { it.providerId == KotlinJvmBuildModelProjector.PROVIDER_ID }
        val sets = model.modules.single().sourceSets.associateBy { it.id }

        assertEquals(BuildModelStatus.PARTIAL, model.status)
        assertEquals("21", sets.getValue("main").attributes["kotlin.jvmTarget"])
        assertEquals("21", sets.getValue("main").attributes["kotlin.targetJdk"])
        assertEquals(SourceSetKind.INTEGRATION_TEST, sets.getValue("integrationTest").kind)
        assertEquals(listOf(Path.of("src/integration/kotlin")), sets.getValue("integrationTest").sourceRoots)
        assertTrue(sets.getValue("main").outputDirectories.contains(Path.of("build/classes/kotlin/main")))
        assertTrue(Path.of("build/generated/ksp/main/kotlin") in sets.getValue("main").generatedSourceRoots)
        assertEquals("refused", sets.getValue("main").attributes["generatedMutation"])
        assertTrue(model.diagnostics.any { it.code == "kotlin.baseModelPartial" })
        assertFalse(Files.exists(root.resolve("executed-marker")), "Gradle build code must never execute")
    }

    @Test
    fun projectionHashTracksToolchainAndScriptsRemainExcluded() {
        val root = Files.createTempDirectory("refactorkit-kotlin-projection-hash")
        root.resolve("build.gradle.kts").writeText("""
            plugins { kotlin("jvm") }
            kotlin { jvmToolchain(21) }
            kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
        """.trimIndent())
        source(root, "src/main/kotlin/fixture/Main.kt", "package fixture\nclass Main\n")
        source(root, "src/main/kotlin/fixture/setup.kts", "println(\"not executed\")\n")
        val base = JavaProjectScanner().scan(root)

        val firstToolchain = toolchain("c".repeat(64))
        val first = KotlinJvmBuildModelIntegration.attach(base, firstToolchain)
        val repeated = KotlinJvmBuildModelIntegration.attach(first, firstToolchain)
        val second = KotlinJvmBuildModelIntegration.attach(base, toolchain("d".repeat(64)))
        val firstModel = first.buildModels.single { it.providerId == KotlinJvmBuildModelProjector.PROVIDER_ID }
        val secondModel = second.buildModels.single { it.providerId == KotlinJvmBuildModelProjector.PROVIDER_ID }

        assertEquals(first.hash, repeated.hash)
        assertNotEquals(firstModel.attributes["projectionHash"], secondModel.attributes["projectionHash"])
        assertTrue(firstModel.diagnostics.any { it.code == "kotlin.scriptSemanticsUnsupported" })
        assertTrue(first.files.any { it.path.fileName.toString().endsWith(".kts") })
        assertTrue(firstModel.modules.flatMap { it.sourceSets }
            .all { it.attributes["scriptSemantics"] == "refused" })
    }

    @Test
    fun preservesJavaOnlyReactorDependencyAsGraphNode() {
        val root = Files.createTempDirectory("refactorkit-kotlin-mixed-reactor")
        root.resolve("pom.xml").writeText("""
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version>
              <packaging>pom</packaging><modules><module>api</module><module>app</module></modules>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
            </project>
        """.trimIndent())
        childPom(root, "api", "")
        childPom(root, "app", """
            <dependencies><dependency><groupId>fixture</groupId><artifactId>api</artifactId><version>1</version></dependency></dependencies>
            <build><plugins><plugin><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId>
              <version>2.0.21</version><configuration><jvmTarget>21</jvmTarget></configuration>
            </plugin></plugins></build>
        """.trimIndent())
        source(root, "api/src/main/java/fixture/Api.java", "package fixture; public class Api {}\n")
        source(root, "app/src/main/kotlin/fixture/App.kt", "package fixture\nclass App(val api: Api)\n")

        val model = KotlinJvmBuildModelProjector().project(JavaProjectScanner().scan(root), toolchain("f".repeat(64)))
        val api = model.modules.single { it.name == "api" }
        val app = model.modules.single { it.name == "app" }

        assertTrue(api.sourceSets.isEmpty(), "Java-only dependency remains a graph node without fake Kotlin roots")
        assertEquals(api.id, app.sourceSets.single { it.kind == SourceSetKind.MAIN }
            .moduleDependencies.single().targetModuleId)
    }

    @Test
    fun conventionalKotlinDoesNotFabricatePluginOrTargetEvidence() {
        val root = Files.createTempDirectory("refactorkit-kotlin-conventional-model")
        source(root, "src/main/kotlin/fixture/Conventional.kt", "package fixture\nclass Conventional\n")

        val model = KotlinJvmBuildModelProjector().project(JavaProjectScanner().scan(root), toolchain("0".repeat(64)))
        val sourceSet = model.modules.single().sourceSets.single { it.kind == SourceSetKind.MAIN }

        assertEquals(BuildModelStatus.PARTIAL, model.status)
        assertFalse("kotlin.jvmTarget" in sourceSet.attributes)
        assertFalse("kotlin.targetJdk" in sourceSet.attributes)
        assertTrue(model.diagnostics.any { it.code == "kotlin.pluginModelPartial" })
        assertTrue(model.diagnostics.any { it.code == "kotlin.jvmTargetUnavailable" })
        assertTrue(model.diagnostics.any { it.code == "kotlin.targetJdkUnavailable" })
    }

    @Test
    fun projectionLimitsFailClosedWithoutPartialModules() {
        val root = Files.createTempDirectory("refactorkit-kotlin-model-limit")
        val sourceSets = (0..ProtocolLimits.MAX_BUILD_SOURCE_SETS_PER_MODULE).map { index ->
            BuildSourceSet("set-$index", SourceSetKind.CUSTOM, sourceRoots = listOf(Path.of("src/$index/kotlin")))
        }
        val snapshot = ProjectSnapshot(
            workspace = Workspace(root),
            modules = emptyList(),
            files = emptyList(),
            buildModels = listOf(BuildModel(
                providerId = "maven-effective-v1",
                status = BuildModelStatus.AVAILABLE,
                modules = listOf(BuildModule("module", "module", root, sourceSets)),
                attributes = mapOf("ecosystem" to "maven"),
            )),
        )

        val model = KotlinJvmBuildModelProjector().project(snapshot, toolchain("1".repeat(64)))

        assertEquals(BuildModelStatus.EXECUTION_REFUSED, model.status)
        assertTrue(model.modules.isEmpty())
        assertEquals(64, model.attributes.getValue("projectionHash").length)
        assertTrue(model.diagnostics.any { it.code == "kotlin.buildSourceSetLimit" })
    }

    @Test
    fun unsupportedKotlinPlatformFailsClosed() {
        val root = Files.createTempDirectory("refactorkit-kotlin-platform-model")
        root.resolve("build.gradle.kts").writeText("plugins { kotlin(\"multiplatform\") }\n")
        source(root, "src/main/kotlin/fixture/Common.kt", "package fixture\nclass Common\n")

        val base = JavaProjectScanner().scan(root)
        val model = KotlinJvmBuildModelProjector().project(base, toolchain("e".repeat(64)))

        assertEquals(BuildModelStatus.EXECUTION_REFUSED, model.status)
        assertTrue(model.diagnostics.any { it.code == "kotlin.platformUnsupported" })
    }

    private fun toolchain(projectionHash: String) = KotlinSemanticToolchain(
        jdkHome = Path.of("/qualified/jdk"),
        javaExecutable = Path.of("/qualified/jdk/bin/java"),
        compilerJar = Path.of("/qualified/kotlin-compiler.jar"),
        compilerClasspath = emptyList(),
        provenance = KotlinToolchainProvenance(
            javaVersion = "21",
            kotlinVersion = "2.0.21",
            compilerDistributionVersion = "2.0.21-release-482",
            projectionHash = projectionHash,
            evidence = emptyList(),
        ),
    )

    private fun childPom(root: Path, module: String, body: String) {
        val directory = root.resolve(module).createDirectories()
        directory.resolve("pom.xml").writeText("""
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent>
              <artifactId>$module</artifactId>$body
            </project>
        """.trimIndent())
    }

    private fun source(root: Path, relative: String, content: String) {
        val file = root.resolve(relative)
        file.parent.createDirectories()
        file.writeText(content)
    }
}
