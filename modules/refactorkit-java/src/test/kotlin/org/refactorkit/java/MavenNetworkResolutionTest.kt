package org.refactorkit.java

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MavenNetworkResolutionTest {
    @Test
    fun anonymousOptInRequiresValidSha256BeforePublishingArtifact() {
        val root = project()
        val repository = Files.createTempDirectory("refactorkit-network-m2")
        val jar = "verified-jar".toByteArray()
        val pom = "<project><modelVersion>4.0.0</modelVersion><groupId>fixture.remote</groupId><artifactId>api</artifactId><version>1</version></project>".toByteArray()
        val transport = FakeTransport(mapOf(
            central("fixture/remote/api/1/api-1.jar") to jar,
            central("fixture/remote/api/1/api-1.jar.sha256") to sha256(jar).toByteArray(),
            central("fixture/remote/api/1/api-1.pom") to pom,
            central("fixture/remote/api/1/api-1.pom.sha256") to sha256(pom).toByteArray(),
        ))

        val model = MavenEffectiveReactorBuilder(
            localRepository = repository,
            allowNetwork = true,
            artifactTransport = transport,
        ).build(root, listOf(root.resolve("pom.xml")))

        val module = model.modules.getValue(root)
        val installed = repository.resolve("fixture/remote/api/1/api-1.jar")
        assertTrue(installed in module.mainArtifacts)
        assertTrue(module.missingArtifacts.isEmpty(), module.missingArtifacts.toString())
        assertEquals(jar.toList(), Files.readAllBytes(installed).toList())
        assertTrue(transport.requested.all { it.scheme == "https" && it.host == "repo.maven.apache.org" })
    }

    @Test
    fun checksumMismatchLeavesNoArtifactOrTemporaryFiles() {
        val root = project()
        val repository = Files.createTempDirectory("refactorkit-network-bad-m2")
        val jar = "tampered-jar".toByteArray()
        val transport = FakeTransport(mapOf(
            central("fixture/remote/api/1/api-1.jar") to jar,
            central("fixture/remote/api/1/api-1.jar.sha256") to "0".repeat(64).toByteArray(),
        ))

        val model = MavenEffectiveReactorBuilder(
            localRepository = repository,
            allowNetwork = true,
            artifactTransport = transport,
        ).build(root, listOf(root.resolve("pom.xml")))

        val module = model.modules.getValue(root)
        val artifactDirectory = repository.resolve("fixture/remote/api/1")
        assertTrue(module.missingArtifacts.any { it.contains("fixture.remote:api:1") })
        assertFalse(Files.exists(artifactDirectory.resolve("api-1.jar")))
        if (Files.exists(artifactDirectory)) {
            Files.list(artifactDirectory).use { stream ->
                assertFalse(stream.anyMatch { it.fileName.toString().contains("refactorkit-") })
            }
        }
    }

    @Test
    fun offlineDefaultNeverInvokesTransport() {
        val root = project()
        val repository = Files.createTempDirectory("refactorkit-network-offline-m2")
        val transport = FakeTransport(emptyMap())

        val model = MavenEffectiveReactorBuilder(
            localRepository = repository,
            allowNetwork = false,
            artifactTransport = transport,
        ).build(root, listOf(root.resolve("pom.xml")))

        assertTrue(model.modules.getValue(root).missingArtifacts.isNotEmpty())
        assertTrue(transport.requested.isEmpty())
    }

    private fun project(): Path {
        val root = Files.createTempDirectory("refactorkit-network-project")
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>app</artifactId><version>1</version>
              <dependencies><dependency><groupId>fixture.remote</groupId><artifactId>api</artifactId><version>1</version></dependency></dependencies>
            </project>
        """.trimIndent())
        Files.createDirectories(root.resolve("src/main/java"))
        return root
    }

    private class FakeTransport(private val responses: Map<URI, ByteArray>) : MavenArtifactTransport {
        val requested = mutableListOf<URI>()

        override fun download(uri: URI, target: Path, maxBytes: Long) {
            requested += uri
            val bytes = responses[uri] ?: error("Unexpected request: $uri")
            require(bytes.size <= maxBytes)
            Files.write(target, bytes)
        }
    }

    companion object {
        private fun central(path: String) = URI("https://repo.maven.apache.org/maven2/$path")

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
