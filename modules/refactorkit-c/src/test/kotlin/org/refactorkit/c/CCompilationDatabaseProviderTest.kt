package org.refactorkit.c

import org.refactorkit.core.BuildModelRequest
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModelDiscoveryPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CCompilationDatabaseProviderTest {
    @Test
    fun discoversAvailableModelFromCompilationDatabase() {
        val fixture = fixture()
        fixture.workspace.resolve("compile_commands.json").writeText("""
            [
              {
                "directory": "${fixture.workspace}",
                "arguments": ["clang", "-std=c17", "-c", "src/main.c", "-o", "out/main.o"],
                "file": "src/main.c",
                "output": "out/main.o"
              }
            ]
        """.trimIndent())

        val model = provider().discover(BuildModelRequest(
            workspaceRoot = fixture.workspace,
            policy = BuildModelDiscoveryPolicy(
                networkAccess = BuildModelDiscoveryPolicy.NetworkAccess.DENY,
                buildCodeExecution = BuildModelDiscoveryPolicy.BuildCodeExecution.DENY,
                credentialsAccess = BuildModelDiscoveryPolicy.CredentialsAccess.DENY,
            ),
        ))

        assertEquals(BuildModelStatus.AVAILABLE, model.status)
        assertEquals("c-compilation-database-v1", model.providerId)
        val module = model.modules.single()
        assertTrue(module.id.startsWith("c:"))
        assertTrue(module.sourceSets.single().languageFacets.single().languageId == "c")
        assertEquals("clang", module.sourceSets.single().languageFacets.single().compilerId)
        assertEquals("c17", module.sourceSets.single().languageFacets.single().sourceVersion)
        assertEquals("denied", model.attributes["buildCodeExecution"])
        assertEquals("denied", model.attributes["networkAccess"])
        assertEquals("denied", model.attributes["credentialsAccess"])
    }

    @Test
    fun refusesWhenCompilationDatabaseMissingOrInvalid() {
        val fixture = fixture()
        val missing = provider().discover(BuildModelRequest(fixture.workspace))
        assertEquals(BuildModelStatus.UNAVAILABLE, missing.status)
        assertTrue(missing.diagnostics.any { it.code == "c.compilationDatabaseMissing" })

        fixture.workspace.resolve("compile_commands.json").writeText("not json")
        val invalid = provider().discover(BuildModelRequest(fixture.workspace))
        assertEquals(BuildModelStatus.UNAVAILABLE, invalid.status)
        assertTrue(invalid.diagnostics.any { it.code == "c.compilationDatabaseInvalid" })
    }

    @Test
    fun groupsUnitsByWorkingDirectoryIntoModules() {
        val fixture = fixture()
        val dirA = fixture.workspace.resolve("a")
        val dirB = fixture.workspace.resolve("b")
        dirA.toFile().mkdirs()
        dirB.toFile().mkdirs()
        fixture.workspace.resolve("compile_commands.json").writeText("""
            [
              { "directory": "$dirA", "arguments": ["clang", "-c", "src/a.c"], "file": "src/a.c" },
              { "directory": "$dirB", "arguments": ["clang", "-c", "src/b.c"], "file": "src/b.c" }
            ]
        """.trimIndent())

        val model = provider().discover(BuildModelRequest(fixture.workspace))
        assertEquals(2, model.modules.size)
        assertEquals(2, model.attributes["unitCount"]?.toInt())
    }

    @Test
    fun conflictingStandardWithinModuleIsPartial() {
        val fixture = fixture()
        fixture.workspace.resolve("compile_commands.json").writeText("""
            [
              { "directory": "${fixture.workspace}", "arguments": ["clang", "-std=c17", "-c", "src/a.c"], "file": "src/a.c" },
              { "directory": "${fixture.workspace}", "arguments": ["clang", "-std=c99", "-c", "src/b.c"], "file": "src/b.c" }
            ]
        """.trimIndent())

        val model = provider().discover(BuildModelRequest(fixture.workspace))
        assertEquals(BuildModelStatus.PARTIAL, model.status)
        assertTrue(model.diagnostics.any { it.code == "c.compilationConfigurationConflict" })
    }

    @Test
    fun incompleteConfigurationIsPartialAndDeclaresCapabilities() {
        val fixture = fixture()
        fixture.workspace.resolve("compile_commands.json").writeText("""
            [
              { "directory": "${fixture.workspace}", "arguments": ["clang", "-c", "src/a.c"], "file": "src/a.c" }
            ]
        """.trimIndent())

        val model = provider().discover(BuildModelRequest(fixture.workspace))
        assertEquals(BuildModelStatus.PARTIAL, model.status)
        assertTrue(model.diagnostics.any { it.code == "c.compilationConfigurationIncomplete" })
        assertEquals("denied", model.attributes["perUnitFlags"])
        assertEquals("declared", model.attributes["standard"])
        assertEquals("declared", model.attributes["includes"])
    }

    private fun provider() = CCompilationDatabaseProvider()
    private fun fixture(): Fixture {
        val workspace = Files.createTempDirectory("refactorkit-c-provider")
        workspace.resolve("src").toFile().mkdirs()
        return Fixture(workspace)
    }
    private data class Fixture(val workspace: Path)
}
