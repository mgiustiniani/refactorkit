package org.refactorkit.java

import org.refactorkit.core.Diagnostic
import java.nio.file.Files
import java.nio.file.Path
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
