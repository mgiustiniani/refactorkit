package org.refactorkit.daemon

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.kotlin.KotlinSemanticToolchain
import org.refactorkit.kotlin.KotlinToolchainDiscovery
import org.refactorkit.kotlin.KotlinToolchainProvenance
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KotlinDaemonIntegrationTest {
    @Test
    fun kotlinLifecycleAttachesQualifiedModelAndReturnsCorrelatedDiagnosticsEnvelope() {
        val root = Files.createTempDirectory("refactorkit-kotlin-daemon")
        root.resolve("pom.xml").writeText("""
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId><artifactId>app</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId><version>2.0.21</version>
                <configuration><jvmTarget>21</jvmTarget><jdkToolchain><version>21</version></jdkToolchain></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent())
        root.resolve("src/main/kotlin/fixture").createDirectories()
        root.resolve("src/main/kotlin/fixture/App.kt").writeText("package fixture\nclass App\n")
        val toolchain = KotlinSemanticToolchain(
            jdkHome = Path.of("/qualified/jdk"),
            javaExecutable = Path.of("/qualified/jdk/bin/java"),
            compilerJar = Path.of("/qualified/compiler.jar"),
            compilerClasspath = emptyList(),
            provenance = KotlinToolchainProvenance(
                javaVersion = "21",
                kotlinVersion = "2.0.21",
                compilerDistributionVersion = "2.0.21-release-482",
                projectionHash = "a".repeat(64),
                evidence = emptyList(),
            ),
        )
        val session = DaemonSession(
            kotlinToolchainDiscovery = { _, _ -> KotlinToolchainDiscovery.Available(toolchain) },
            semanticLeaseFactory = { "semantic-00000000-0000-4000-8000-000000000007" },
        )
        session.dispatch("project.open", params("root" to root.toString()))
        val started = session.dispatch("kotlin.semantic.start", buildJsonObject {
            put("jdkHome", "/qualified/jdk")
            put("compilerJar", "/qualified/compiler.jar")
            put("compilerClasspath", buildJsonArray { })
        }).jsonObject
        assertEquals("started", started.getValue("status").jsonPrimitive.content)
        assertEquals("kotlin-compiler-diagnostics-k2-v1", started.getValue("backend").jsonPrimitive.content)

        val stale = session.dispatch("kotlin.diagnostics", buildJsonObject {
            put("requestId", "kotlin-stale")
            put("expectedSnapshotHash", started.getValue("snapshotHash").jsonPrimitive.content)
            put("semanticLease", "semantic-00000000-0000-4000-8000-000000000099")
        }).jsonObject
        assertEquals("refused", stale.getValue("status").jsonPrimitive.content)
        assertEquals("kotlin.diagnosticsSessionStale", stale.getValue("failure").jsonObject
            .getValue("code").jsonPrimitive.content)

        assertFailsWith<org.refactorkit.core.JsonRpcException> {
            session.dispatch("kotlin.definition", buildJsonObject {
                put("requestId", "kotlin-invalid-definition")
                put("expectedSnapshotHash", started.getValue("snapshotHash").jsonPrimitive.content)
                put("semanticLease", started.getValue("semanticLease").jsonPrimitive.content)
                put("symbol", "not-an-opaque-id")
            })
        }

        val staleSymbols = session.dispatch("kotlin.symbols", buildJsonObject {
            put("requestId", "kotlin-symbols-stale")
            put("expectedSnapshotHash", started.getValue("snapshotHash").jsonPrimitive.content)
            put("semanticLease", "semantic-00000000-0000-4000-8000-000000000099")
        }).jsonObject
        assertEquals("refused", staleSymbols.getValue("status").jsonPrimitive.content)
        assertEquals("kotlin.symbolsSessionStale", staleSymbols.getValue("failure").jsonObject
            .getValue("code").jsonPrimitive.content)
        assertTrue(staleSymbols.getValue("symbols").jsonArray.isEmpty())

        val staleCallableDefinition = session.dispatch("kotlin.definition", buildJsonObject {
            put("requestId", "kotlin-callable-stale")
            put("expectedSnapshotHash", started.getValue("snapshotHash").jsonPrimitive.content)
            put("semanticLease", "semantic-00000000-0000-4000-8000-000000000099")
            put("symbol", "kotlin-jvm-callable-v1:${"a".repeat(64)}")
        }).jsonObject
        assertEquals("refused", staleCallableDefinition.getValue("status").jsonPrimitive.content)
        assertEquals("kotlin.symbolsSessionStale", staleCallableDefinition.getValue("failure").jsonObject
            .getValue("code").jsonPrimitive.content)

        val failed = session.dispatch("kotlin.diagnostics", buildJsonObject {
            put("requestId", "kotlin-worker")
            put("expectedSnapshotHash", started.getValue("snapshotHash").jsonPrimitive.content)
            put("semanticLease", started.getValue("semanticLease").jsonPrimitive.content)
        }).jsonObject
        assertEquals("refused", failed.getValue("status").jsonPrimitive.content)
        assertEquals("kotlin.compilerStdlibUnavailable", failed.getValue("failure").jsonObject
            .getValue("code").jsonPrimitive.content)
        assertTrue(failed.getValue("diagnostics").jsonArray.isEmpty())

        val stopped = session.dispatch("kotlin.semantic.stop", null).jsonObject
        assertTrue(stopped.getValue("stopped").jsonPrimitive.content.toBoolean())
        session.close()
    }

    private fun params(vararg values: Pair<String, String>): JsonObject = buildJsonObject {
        values.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
    }
}
