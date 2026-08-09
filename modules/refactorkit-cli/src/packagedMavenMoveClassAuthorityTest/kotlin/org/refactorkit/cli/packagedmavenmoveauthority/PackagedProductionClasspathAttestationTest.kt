package org.refactorkit.cli.packagedmavenmoveauthority

import org.refactorkit.core.PatchEngine
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.lsp.LspSession
import org.refactorkit.mcp.McpSession
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackagedProductionClasspathAttestationTest {
    @Test
    fun matrixLoadsProductionClassesFromPackageLibAndUsesEmbeddedRuntime() {
        val packageRoot = Path.of(requireNotNull(System.getProperty("refactorkit.packaged.root")))
            .toAbsolutePath().normalize()
        val packageLib = packageRoot.resolve("lib")
        val expectedRuntime = packageRoot.resolve("runtime")
        val actualRuntime = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()

        assertEquals(expectedRuntime, actualRuntime, "Qualification worker must use the packaged Java runtime")
        val productionTypes: List<Class<*>> = listOf(
            PatchEngine::class.java,
            JavaProjectScanner::class.java,
            Class.forName("org.refactorkit.cli.RefactorKitCliKt"),
            DaemonSession::class.java,
            LspSession::class.java,
            McpSession::class.java,
        )
        productionTypes.forEach { type ->
            val source = Path.of(requireNotNull(type.protectionDomain.codeSource).location.toURI())
                .toAbsolutePath().normalize()
            assertTrue(source.startsWith(packageLib), "${type.name} loaded from non-package source $source")
            assertTrue(source.isRegularFile(), "${type.name} package code source is not a regular JAR: $source")
        }
    }
}
