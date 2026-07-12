package org.refactorkit.java

import org.refactorkit.core.BuildModelDiscoveryPolicy
import org.refactorkit.core.BuildModelProvider
import org.refactorkit.core.BuildModelRequest
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.SourceSetKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BuildModelProvidersTest {
    @Test
    fun mavenProviderImplementsSpiAndDiscoversEffectiveReactor() {
        val provider = MavenBuildModelProvider()
        val root = Path.of("../../samples/java-maven-reactor-21").toAbsolutePath().normalize()

        val model = provider.discover(BuildModelRequest(root))

        assertIs<BuildModelProvider>(provider)
        assertEquals("maven-effective-v1", model.providerId)
        assertEquals(BuildModelStatus.AVAILABLE, model.status)
        assertEquals("denied", model.attributes["buildCodeExecution"])
        assertEquals("denied", model.attributes["credentialsAccess"])
        assertTrue(model.modules.any { module ->
            module.sourceSets.any { it.kind == SourceSetKind.MAIN } &&
                module.sourceSets.any { it.kind == SourceSetKind.TEST }
        })
    }

    @Test
    fun gradleProviderRemainsDeclarativeAndNeverExecutesBuildCode() {
        val root = Files.createTempDirectory("refactorkit-gradle-provider")
        Files.createDirectories(root.resolve("src/main/java/example"))
        Files.writeString(root.resolve("src/main/java/example/App.java"), "package example; class App {}\n")
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n")
        val provider = GradleDeclarativeBuildModelProvider()

        val model = provider.discover(BuildModelRequest(
            root,
            BuildModelDiscoveryPolicy(
                buildCodeExecution = BuildModelDiscoveryPolicy.BuildCodeExecution.ALLOW_EXPLICIT,
            ),
        ))

        assertIs<BuildModelProvider>(provider)
        assertEquals("gradle-declarative-v1", model.providerId)
        assertEquals(BuildModelStatus.PARTIAL, model.status)
        assertEquals("denied", model.attributes["buildCodeExecution"])
        assertEquals("declarative-heuristic", model.attributes["strategy"])
        assertTrue(model.diagnostics.any { it.code == "buildModel.partial" })
    }
}
