package org.refactorkit.java

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** The caller-selected Maven runtime's metadata artifact and independently expected digest. */
data class MavenRuntimeSelection(val coreJar: Path, val sha256: String) {
    init {
        require(coreJar.isAbsolute) { "mavenRuntime.coreJar must be an absolute path" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "mavenRuntime.sha256 must be a lowercase SHA-256 digest" }
    }
}

internal data class MavenLifecycleCompilerBinding(
    val coreJar: Path,
    val compilerVersion: String,
    val origin: String,
) {
    companion object {
        private const val CORE_BYTES = 2 * 1024 * 1024
        private const val BINDINGS_BYTES = 128 * 1024
        private val mavenVersion = Regex("3\\.9\\.[0-9]{1,3}")
        private val qualifiedCompilerVersions = setOf("3.13.0", "3.15.0")

        fun read(selection: MavenRuntimeSelection): MavenLifecycleCompilerBinding? = runCatching {
            val jar = selection.coreJar.normalize()
            if (sha256(jar) != selection.sha256) return null
            val binding = ZipFile(jar.toFile()).use { archive ->
                val properties = archive.metadata("META-INF/maven/org.apache.maven/maven-core/pom.properties", 4096)
                    ?.let { bytes -> Properties().apply { load(ByteArrayInputStream(bytes)) } } ?: return null
                if (properties.getProperty("groupId") != "org.apache.maven" ||
                    properties.getProperty("artifactId") != "maven-core") return null
                val version = properties.getProperty("version")?.takeIf(mavenVersion::matches) ?: return null
                if (jar.fileName.toString() != "maven-core-$version.jar") return null
                val descriptor = archive.metadata("META-INF/plexus/default-bindings.xml", BINDINGS_BYTES) ?: return null
                val compiler = defaultJarCompiler(descriptor) ?: return null
                MavenLifecycleCompilerBinding(jar, compiler, "maven-core:$version:jar-default:sha256=${selection.sha256}")
            }
            binding.takeIf { sha256(jar) == selection.sha256 }
        }.getOrNull()

        private fun sha256(jar: Path): String? {
            if (jar.toRealPath() != jar || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) ||
                Files.size(jar) !in 1L..CORE_BYTES.toLong()) return null
            val bytes = Files.newInputStream(jar, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(CORE_BYTES + 1) }
            if (bytes.isEmpty() || bytes.size > CORE_BYTES) return null
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }

        private fun ZipFile.metadata(path: String, limit: Int): ByteArray? {
            val entry = entries().asSequence().filter { it.name == path }.singleOrNull() ?: return null
            if (entry.isDirectory || entry.size !in 1L..limit.toLong()) return null
            return getInputStream(entry).use { it.readNBytes(limit + 1) }
                .takeIf { it.size.toLong() == entry.size }
        }

        private fun defaultJarCompiler(descriptor: ByteArray): String? {
            val root = parseMavenMetadataDocument(descriptor)
            if (root.tagName != "component-set") return null
            val component = root.child("components")?.children("component")?.singleOrNull {
                it.child("role")?.textContent?.trim() == "org.apache.maven.lifecycle.mapping.LifecycleMapping" &&
                    it.child("role-hint")?.textContent?.trim() == "jar"
            } ?: return null
            if (component.child("implementation")?.textContent?.trim() !=
                "org.apache.maven.lifecycle.mapping.DefaultLifecycleMapping") return null
            val lifecycle = component.child("configuration")?.child("lifecycles")?.children("lifecycle")
                ?.singleOrNull { it.child("id")?.textContent?.trim() == "default" } ?: return null
            val phases = lifecycle.child("phases") ?: return null
            fun compilerVersion(phase: String, goal: String): String? {
                val coordinate = phases.child(phase)?.textContent?.trim()?.split(':') ?: return null
                if (coordinate.size != 4 || coordinate[0] != "org.apache.maven.plugins" ||
                    coordinate[1] != "maven-compiler-plugin" || coordinate[3] != goal) return null
                return coordinate[2].takeIf { it in qualifiedCompilerVersions }
            }
            val compile = compilerVersion("compile", "compile") ?: return null
            return compile.takeIf { it == compilerVersion("test-compile", "testCompile") }
        }

        private fun Element.child(name: String): Element? = children(name).singleOrNull()

        private fun Element.children(name: String): List<Element> = (0 until childNodes.length)
            .mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == name }
    }
}

internal fun parseMavenMetadataDocument(bytes: ByteArray): Element {
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
    return builder.parse(ByteArrayInputStream(bytes)).documentElement
}
