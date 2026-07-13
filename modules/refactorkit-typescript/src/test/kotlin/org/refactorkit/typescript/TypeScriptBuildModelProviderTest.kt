package org.refactorkit.typescript

import org.refactorkit.core.BuildModelDiscoveryPolicy
import org.refactorkit.core.BuildModelRequest
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.Workspace
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TypeScriptBuildModelProviderTest {
    @Test
    fun projectsConfigGraphIntoLanguageNeutralBuildModel() {
        val root = Files.createTempDirectory("refactorkit-ts-build-model")
        root.resolve("app").createDirectories().resolve("tsconfig.json").writeText(
            """{"compilerOptions":{"rootDir":"src","outDir":"dist","allowJs":true},"references":[{"path":"../lib"}]}""",
        )
        root.resolve("lib").createDirectories().resolve("tsconfig.json").writeText(
            """{"compilerOptions":{"rootDir":"src","composite":true}}""",
        )

        val model = TypeScriptBuildModelProvider().discover(BuildModelRequest(root))

        assertEquals(BuildModelStatus.AVAILABLE, model.status)
        assertEquals("typescript-config-declarative-v1", model.providerId)
        assertEquals(listOf("typescript:app/tsconfig.json", "typescript:lib/tsconfig.json"), model.modules.map { it.id })
        val app = model.modules.first()
        assertEquals("app/src", app.sourceSets.single().sourceRoots.single().toString().replace('\\', '/'))
        assertEquals("app/dist", app.sourceSets.single().outputDirectories.single().toString().replace('\\', '/'))
        assertEquals("typescript:lib/tsconfig.json", app.sourceSets.single().moduleDependencies.single().targetModuleId)
        assertEquals("denied", model.attributes["networkAccess"])
        assertEquals("denied", model.attributes["credentialsAccess"])
        assertEquals("denied", model.attributes["buildCodeExecution"])
        assertEquals(64, model.attributes["projectionHash"]!!.length)
    }

    @Test
    fun policyAllowancesDoNotEnableNetworkCredentialsOrBuildExecution() {
        val root = Files.createTempDirectory("refactorkit-ts-build-model")
        root.resolve("tsconfig.json").writeText("{}")
        val model = TypeScriptBuildModelProvider().discover(BuildModelRequest(
            root,
            BuildModelDiscoveryPolicy(
                networkAccess = BuildModelDiscoveryPolicy.NetworkAccess.ALLOW_ANONYMOUS,
                buildCodeExecution = BuildModelDiscoveryPolicy.BuildCodeExecution.ALLOW_EXPLICIT,
                credentialsAccess = BuildModelDiscoveryPolicy.CredentialsAccess.ALLOW_EXPLICIT_REDACTED,
            ),
        ))
        assertEquals(BuildModelStatus.AVAILABLE, model.status)
        assertEquals("denied", model.attributes["networkAccess"])
        assertEquals("denied", model.attributes["credentialsAccess"])
        assertEquals("denied", model.attributes["buildCodeExecution"])
    }

    @Test
    fun integrationHashBindsBuildModelIntoProjectSnapshot() {
        val root = Files.createTempDirectory("refactorkit-ts-build-model")
        val config = root.resolve("tsconfig.json")
        config.writeText("{}")
        val base = ProjectSnapshot(Workspace(root), modules = emptyList(), files = emptyList())
        val first = TypeScriptBuildModelIntegration.attach(base)
        config.writeText("""{"compilerOptions":{"allowJs":true}}""")
        val second = TypeScriptBuildModelIntegration.attach(base)
        assertNotEquals(first.hash, second.hash)
        assertEquals(listOf("typescript-config-declarative-v1"), second.buildModels.map { it.providerId })
    }

    @Test
    fun projectionHashTracksConfigEvidenceAndFailuresAreTyped() {
        val root = Files.createTempDirectory("refactorkit-ts-build-model")
        val config = root.resolve("tsconfig.json")
        config.writeText("{}")
        val provider = TypeScriptBuildModelProvider()
        val first = provider.discover(BuildModelRequest(root))
        config.writeText("""{"compilerOptions":{"checkJs":true}}""")
        val second = provider.discover(BuildModelRequest(root))
        assertNotEquals(first.attributes["projectionHash"], second.attributes["projectionHash"])

        config.writeText("""{"extends":"@tsconfig/node22"}""")
        val refused = provider.discover(BuildModelRequest(root))
        assertEquals(BuildModelStatus.UNAVAILABLE, refused.status)
        assertEquals(listOf("typescript.extendsUnsupported"), refused.diagnostics.map { it.code })
    }
}
