package org.refactorkit.java

import org.refactorkit.core.Diagnostic
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaAuthoritativeDiagnosticsAcceptanceTest {
    @Test
    fun `release 21 resolves Java SE modules and transitive reactor sources`() {
        val root = reactor(21)
        val diagnostics = JavaLanguageAdapter().authoritativeDiagnostics(
            JavaProjectScanner(localMavenRepository = Files.createTempDirectory("empty-m2")).scan(root),
            Path.of(System.getProperty("java.home")),
        )

        assertTrue(diagnostics.none { it.severity == Diagnostic.Severity.ERROR }, diagnostics.toString())
    }

    @Test
    fun `release 8 rejects newer APIs without fabricating Java 8 or reactor failures`() {
        val root = reactor(8)
        val snapshot = JavaProjectScanner(localMavenRepository = Files.createTempDirectory("empty-m2")).scan(root)
        val platformHome = Path.of(System.getProperty("java.home"))
        val authority = (JavaReleasePlatformAuthorityResolver().resolve(platformHome, 8) as
            JavaReleasePlatformResolution.Available).authority
        val direct = (JdtJavaSemanticAnalyzer().analyzeAuthoritatively(snapshot, mapOf(8 to authority)) as
            JdtAuthoritativeJavaAnalysisResolution.Available).analysis
        assertTrue(direct.warnings.any { it.message.contains("HttpClient") }, direct.warnings.toString())
        val adapter = JavaLanguageAdapter()
        val overlay = adapter.analyzeAuthoritativeDiagnosticsOverlay(snapshot, mapOf(8 to authority))
        val overlayWarnings = (overlay as JdtAuthoritativeJavaAnalysisResolution.Available).analysis.warnings
        assertTrue(overlayWarnings.any { it.message.contains("HttpClient") }, overlayWarnings.toString())
        val diagnostics = adapter.authoritativeDiagnostics(snapshot, platformHome)

        assertTrue(diagnostics.any { it.message.contains("HttpClient") }, diagnostics.toString())
        assertTrue(diagnostics.any { it.message.contains("isBlank") }, diagnostics.toString())
        assertTrue(diagnostics.none { it.message.contains("ReactorApi cannot be resolved") }, diagnostics.toString())
        assertTrue(diagnostics.none { it.message.contains("Connection cannot be resolved") }, diagnostics.toString())
    }

    @Test
    fun `test source sees runtime dependency exported through reactor module`() {
        val root = Files.createTempDirectory("refactorkit-runtime-export-reactor")
        val repository = Files.createTempDirectory("refactorkit-runtime-export-m2")
        installRuntimeApi(repository)
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>runtime-reactor</artifactId><version>1</version><packaging>pom</packaging>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <modules><module>runtime-owner</module><module>acceptance</module></modules>
            </project>
        """.trimIndent())
        Files.createDirectories(root.resolve("runtime-owner/src/main/java/fixture/owner"))
        Files.writeString(root.resolve("runtime-owner/src/main/java/fixture/owner/RuntimeOwner.java"),
            "package fixture.owner; public final class RuntimeOwner {}\n")
        Files.writeString(root.resolve("runtime-owner/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId><artifactId>runtime-reactor</artifactId><version>1</version></parent><artifactId>runtime-owner</artifactId>
              <dependencies><dependency><groupId>fixture.external</groupId><artifactId>runtime-api</artifactId><version>1</version><scope>runtime</scope></dependency></dependencies>
            </project>
        """.trimIndent())
        Files.createDirectories(root.resolve("acceptance/src/test/java/fixture/acceptance"))
        Files.writeString(root.resolve("acceptance/src/test/java/fixture/acceptance/RuntimeAcceptance.java"),
            "package fixture.acceptance; import fixture.runtime.RuntimeApi; class RuntimeAcceptance { RuntimeApi api; }\n")
        Files.writeString(root.resolve("acceptance/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId><artifactId>runtime-reactor</artifactId><version>1</version></parent><artifactId>acceptance</artifactId>
              <dependencies><dependency><groupId>fixture</groupId><artifactId>runtime-owner</artifactId><version>1</version><scope>test</scope></dependency></dependencies>
            </project>
        """.trimIndent())

        val snapshot = JavaProjectScanner(localMavenRepository = repository).scan(root)
        val ownerMain = snapshot.buildModels.single().modules.single { it.id == "runtime-owner" }
            .sourceSets.single { it.id == "main" }
        assertTrue(ownerMain.classpathEntries.none { it.fileName.toString() == "runtime-api-1.jar" })
        assertTrue(ownerMain.runtimeClasspathEntries.any { it.fileName.toString() == "runtime-api-1.jar" })
        val diagnostics = JavaLanguageAdapter().authoritativeDiagnostics(
            snapshot,
            Path.of(System.getProperty("java.home")),
        )
        assertTrue(diagnostics.none { it.severity == Diagnostic.Severity.ERROR }, diagnostics.toString())
    }

    @Test
    fun `module selector filters only after full reactor authoritative analysis`() {
        val root = reactor(8)
        val snapshot = JavaProjectScanner(localMavenRepository = Files.createTempDirectory("empty-m2")).scan(root)
        val adapter = JavaLanguageAdapter()
        val diagnostics = adapter.authoritativeDiagnostics(snapshot, Path.of(System.getProperty("java.home")))

        assertTrue(adapter.filterDiagnosticsForModule(snapshot, diagnostics, "api").isEmpty())
        val infrastructure = adapter.filterDiagnosticsForModule(snapshot, diagnostics, "infrastructure")
        assertTrue(infrastructure.any { it.message.contains("HttpClient") }, infrastructure.toString())
        assertTrue(infrastructure.none { it.message.contains("ReactorApi cannot be resolved") }, infrastructure.toString())
    }

    @Test
    fun `missing explicit platform emits one root per affected source set without derivatives`() {
        val root = reactor(21)
        val diagnostics = JavaLanguageAdapter().authoritativeDiagnostics(
            JavaProjectScanner(localMavenRepository = Files.createTempDirectory("empty-m2")).scan(root),
            null,
        )

        assertEquals(2, diagnostics.count { it.code == "java.platform.authorityUnavailable" }, diagnostics.toString())
        assertTrue(diagnostics.none { it.code == "java.jdt.typeResolution" }, diagnostics.toString())
    }

    private fun installRuntimeApi(repository: Path) {
        val directory = repository.resolve("fixture/external/runtime-api/1")
        val sourceRoot = Files.createTempDirectory("refactorkit-runtime-api-source")
        val source = sourceRoot.resolve("fixture/runtime/RuntimeApi.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, "package fixture.runtime; public interface RuntimeApi {}\n")
        val classes = Files.createTempDirectory("refactorkit-runtime-api-classes")
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(), source.toString()))
        Files.createDirectories(directory)
        JarOutputStream(Files.newOutputStream(directory.resolve("runtime-api-1.jar"))).use { jar ->
            val compiled = classes.resolve("fixture/runtime/RuntimeApi.class")
            jar.putNextEntry(JarEntry("fixture/runtime/RuntimeApi.class"))
            jar.write(Files.readAllBytes(compiled))
            jar.closeEntry()
        }
        Files.writeString(directory.resolve("runtime-api-1.pom"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture.external</groupId><artifactId>runtime-api</artifactId><version>1</version></project>
        """.trimIndent())
    }

    private fun reactor(release: Int): Path {
        val root = Files.createTempDirectory("refactorkit-authoritative-reactor-$release")
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>
              <properties><maven.compiler.release>$release</maven.compiler.release></properties>
              <modules><module>api</module><module>infrastructure</module></modules>
            </project>
        """.trimIndent())
        val api = root.resolve("api/src/main/java/fixture/api/ReactorApi.java")
        Files.createDirectories(api.parent)
        Files.writeString(api, "package fixture.api; public interface ReactorApi { String value(); }\n")
        Files.writeString(root.resolve("api/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId><artifactId>reactor</artifactId><version>1</version></parent><artifactId>api</artifactId></project>
        """.trimIndent())
        val infrastructure = root.resolve("infrastructure/src/main/java/fixture/infra/RepositoryAdapter.java")
        Files.createDirectories(infrastructure.parent)
        Files.writeString(infrastructure, """
            package fixture.infra;
            import fixture.api.ReactorApi;
            import java.sql.Connection;
            import java.net.http.HttpClient;
            import com.sun.net.httpserver.HttpServer;
            public final class RepositoryAdapter {
              ReactorApi api; Connection connection; HttpClient client = HttpClient.newHttpClient(); HttpServer server;
              boolean blank(String value) { return value.isBlank(); }
            }
        """.trimIndent())
        Files.writeString(root.resolve("infrastructure/pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId><artifactId>reactor</artifactId><version>1</version></parent><artifactId>infrastructure</artifactId>
              <dependencies><dependency><groupId>fixture</groupId><artifactId>api</artifactId><version>1</version></dependency></dependencies>
            </project>
        """.trimIndent())
        return root
    }
}
