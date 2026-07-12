package org.refactorkit.java

import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.SourceSetKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleDeclarativeModelAcceptanceTest {
    @Test
    fun integrationAndGeneratedSetsAreModeledWithoutExecutingGradle() {
        val root = Files.createTempDirectory("refactorkit-gradle-declarative")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"\ninclude(\":api\", \":app\")\n")
        module(root, "api", "plugins { java }\njava { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }\n")
        module(root, "app", """
            plugins { java }
            java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
            sourceSets.create("integrationTest")
            sourceSets["integrationTest"].java.srcDir("src/integration/java")
            sourceSets["integrationTest"].java.srcDir("build/generated/integrationTest")
            dependencies {
                implementation(project(":api"))
                integrationTestImplementation(project(":api"))
            }
            file("executed-marker").writeText("must-not-run")
        """.trimIndent())
        source(root, "api/src/main/java/fixture/api/Api.java", "package fixture.api; public record Api(String value) {}\n")
        source(root, "app/src/main/java/fixture/app/App.java", "package fixture.app; import fixture.api.Api; public class App { Api api = new Api(\"ok\"); }\n")
        source(root, "app/src/integration/java/fixture/app/AppIntegration.java", "package fixture.app; import fixture.api.Api; class AppIntegration { Api api = new Api(\"it\"); }\n")
        source(root, "app/build/generated/integrationTest/fixture/app/GeneratedFixture.java", "package fixture.app; import fixture.api.Api; final class GeneratedFixture { Api api; }\n")

        val snapshot = JavaProjectScanner().scan(root)
        val model = snapshot.buildModels.single { it.providerId == GradleDeclarativeModelBuilder.PROVIDER_ID }
        val app = model.modules.single { it.id == "app" }
        val integration = app.sourceSets.single { it.id == "integrationTest" }

        assertEquals(BuildModelStatus.PARTIAL, model.status)
        assertEquals(SourceSetKind.INTEGRATION_TEST, integration.kind)
        assertTrue(Path.of("app/src/integration/java") in integration.sourceRoots)
        assertTrue(Path.of("app/build/generated/integrationTest") in integration.generatedSourceRoots)
        assertTrue(integration.moduleDependencies.any { it.targetModuleId == "api" })
        assertTrue(snapshot.files.any { it.path == Path.of("app/src/integration/java/fixture/app/AppIntegration.java") })
        assertTrue(snapshot.files.any { it.path == Path.of("app/build/generated/integrationTest/fixture/app/GeneratedFixture.java") })
        assertFalse(Files.exists(root.resolve("app/executed-marker")), "Gradle script must never execute")
        val errors = JavaLanguageAdapter().diagnostics(snapshot).filter { it.severity == Diagnostic.Severity.ERROR }
        assertTrue(errors.isEmpty(), errors.toString())
    }

    @Test
    fun sourceRootEscapeProducesExecutionRefusedWithoutScanningOutside() {
        val parent = Files.createTempDirectory("refactorkit-gradle-boundary")
        val root = parent.resolve("workspace")
        Files.createDirectories(root)
        module(root, "app", """
            plugins { java }
            sourceSets.create("integrationTest")
            sourceSets["integrationTest"].java.srcDir("../../outside")
        """.trimIndent())
        source(parent, "outside/Outside.java", "public class Outside {}\n")

        val snapshot = JavaProjectScanner().scan(root)
        val model = snapshot.buildModels.single { it.providerId == GradleDeclarativeModelBuilder.PROVIDER_ID }

        assertEquals(BuildModelStatus.EXECUTION_REFUSED, model.status)
        assertTrue(model.diagnostics.any { it.code == "buildModel.executionRefused" })
        assertFalse(snapshot.files.any { it.path.toString().contains("Outside.java") })
        assertFalse(model.diagnostics.any { it.message.contains(parent.toString()) })
    }

    private fun module(root: Path, name: String, build: String) {
        val module = root.resolve(name)
        Files.createDirectories(module)
        Files.writeString(module.resolve("build.gradle.kts"), build)
    }

    private fun source(root: Path, path: String, content: String) {
        val target = root.resolve(path)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }
}
