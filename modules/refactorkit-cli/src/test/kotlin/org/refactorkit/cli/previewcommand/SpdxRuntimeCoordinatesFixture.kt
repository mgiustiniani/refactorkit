package org.refactorkit.cli.previewcommand

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

internal class SpdxRuntimeCoordinatesFixture(private val condition: String) : AutoCloseable {
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
    private val directory = Files.createTempDirectory("rk-spdx-coordinates-")
    private val archive = directory.resolve("runtime.zip")
    private val raw = directory.resolve("upstream.json")
    private val manifest = directory.resolve("components.json")
    private val assembled = directory.resolve("assembled.json")
    private val receipt = directory.resolve("receipt.json")
    private val documentedJar = jar("detected")
    private val missingJar = jar("omitted")
    private val detectedPath = "lib/example-1.jar"
    private val omittedPath = "lib/omitted-2.jar"
    private val rawPackages = mutableListOf(
        detected("SPDXRef-Example", "example", hash(documentedJar, "SHA-1")),
        detected("SPDXRef-Nested", "nested", hash(jar("nested"), "SHA-1")),
    )
    private var images = emptyMap<Path, String>()
    private var stderr = ""
    private var exit = -1

    init {
        if (condition == "ambiguous upstream package") {
            rawPackages.add(JsonObject(rawPackages.first() + ("SPDXID" to JsonPrimitive("SPDXRef-Duplicate"))))
        }
        Files.writeString(raw, buildJsonObject {
            put("spdxVersion", "SPDX-2.3")
            put("SPDXID", "SPDXRef-DOCUMENT")
            put("dataLicense", "CC0-1.0")
            put("name", "Runtime coordinates fixture")
            put("documentNamespace", "https://example.invalid/runtime-coordinates")
            putJsonObject("creationInfo") {
                put("created", "2026-11-19T00:00:00Z")
                putJsonArray("creators") { add("Tool: authored fixture") }
            }
            put("packages", JsonArray(rawPackages))
        }.toString())
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.add("refactorkit/$detectedPath", documentedJar)
            zip.add("refactorkit/$omittedPath", missingJar)
        }
        Files.writeString(manifest, buildJsonObject {
            put("kind", "refactorkit.resolved-runtime-components/v1")
            put("sourceVersion", "0.8.0")
            put("configuration", ":modules:refactorkit-cli:runtimeClasspath")
            putJsonArray("components") {
                add(component(detectedPath, "example", documentedJar))
                if (condition != "component missing from manifest") add(component(omittedPath, "omitted", missingJar))
            }
        }.toString())
        images = images()
    }

    fun execute() {
        invoke("assemble", "--spdx", raw.toString(), "--output", assembled.toString())
        if (exit != 0) return
        if (condition == "normalized metadata changed") {
            val text = Files.readString(assembled)
            assertContains(text, "pkg:maven/org.example/example@1")
            Files.writeString(assembled, text.replace("pkg:maven/org.example/example@1", "pkg:maven/fabricated/example@1"))
        }
        invoke("verify", "--spdx", assembled.toString(), "--upstream-spdx", raw.toString(), "--receipt", receipt.toString())
    }

    fun verify(expected: String) {
        if (expected != "verified runtime coordinates") {
            assertEquals(1, exit, stderr)
            assertEquals(expected, Json.parseToJsonElement(stderr).jsonObject.getValue("code").jsonPrimitive.content)
            assertFalse(Files.exists(receipt))
            assertEquals(condition == "normalized metadata changed", Files.exists(assembled))
            return
        }
        assertEquals(0, exit, stderr)
        val proof = read(receipt)
        val runtime = proof.getValue("runtimeComponents").jsonObject
        assertEquals(2, runtime.getValue("componentCount").jsonPrimitive.int)
        assertEquals(hash(Files.readAllBytes(manifest)), runtime.getValue("manifestSha256").jsonPrimitive.content)
        assertEquals("PENDING", proof.getValue("licenseReview").jsonPrimitive.content)
        assertFalse(proof.getValue("wholeReleaseQualified").jsonPrimitive.boolean)
        val packages = read(assembled).getValue("packages").jsonArray.map { it.jsonObject }
        val original = rawPackages.first()
        val updated = packages.single { it.getValue("SPDXID").jsonPrimitive.content == "SPDXRef-Example" }
        assertEquals(original - "externalRefs", updated - "externalRefs")
        assertEquals("pkg:maven/org.example/example@1", purl(updated))
        assertTrue(packages.contains(rawPackages.last()))
        val omitted = packages.single { it.getValue("name").jsonPrimitive.content == "omitted" }
        assertEquals("pkg:maven/org.example/omitted@2", purl(omitted))
        assertEquals("NOASSERTION", omitted.getValue("licenseDeclared").jsonPrimitive.content)
        val checksums = omitted.getValue("checksums").jsonArray.associate { item ->
            val checksum = item.jsonObject
            checksum.getValue("algorithm").jsonPrimitive.content to checksum.getValue("checksumValue").jsonPrimitive.content
        }
        assertEquals(mapOf("SHA256" to hash(missingJar), "SHA1" to hash(missingJar, "SHA-1")), checksums)
    }

    fun preserve() = assertEquals(images, images())

    private fun images() = listOf(archive, raw, manifest).associateWith { hash(Files.readAllBytes(it)) }

    private fun detected(identity: String, name: String, digest: String) = buildJsonObject {
        put("SPDXID", identity)
        put("name", name)
        put("versionInfo", "1")
        put("sourceInfo", "acquired package info from installed java archive: /$detectedPath")
        put("downloadLocation", "NOASSERTION")
        put("filesAnalyzed", false)
        put("licenseDeclared", "Apache-2.0")
        putJsonArray("checksums") {
            add(buildJsonObject { put("algorithm", "SHA1"); put("checksumValue", digest) })
        }
        putJsonArray("externalRefs") {
            add(buildJsonObject {
                put("referenceCategory", "PACKAGE-MANAGER")
                put("referenceType", "purl")
                put("referenceLocator", "pkg:maven/detected/$name@1")
            })
        }
    }

    private fun component(path: String, name: String, bytes: ByteArray) = buildJsonObject {
        put("path", path)
        put("sha256", if (condition == "component checksum mismatch") "0".repeat(64) else hash(bytes))
        put("sha1", hash(bytes, "SHA-1"))
        putJsonObject("coordinates") {
            put("kind", "module")
            put("group", if (condition == "malformed coordinates") "org/invalid" else "org.example")
            put("artifact", name)
            put("version", if (name == "example") "1" else "2")
        }
    }

    private fun invoke(mode: String, vararg arguments: String) {
        val log = directory.resolve("$mode.log")
        val command = listOf("python3", root.resolve("scripts/qualify-release-spdx.py").toString(), mode,
            "--archive", archive.toString(), "--version", "0.8.0",
            "--node", requireNotNull(System.getenv("REFACTORKIT_SCHEMA_NODE")),
            "--ajv-module", requireNotNull(System.getenv("REFACTORKIT_SCHEMA_AJV_MODULE")),
            "--runtime-components", manifest.toString()) + arguments
        val process = ProcessBuilder(command).redirectError(log.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            error("SPDX coordinate qualification timed out")
        }
        exit = process.exitValue()
        stderr = Files.readString(log).trim()
    }

    private fun purl(p: JsonObject) = p.getValue("externalRefs").jsonArray.single()
        .jsonObject.getValue("referenceLocator").jsonPrimitive.content

    private fun read(path: Path) = Json.parseToJsonElement(Files.readString(path)).jsonObject
    private fun hash(bytes: ByteArray, algorithm: String = "SHA-256") = MessageDigest.getInstance(algorithm)
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun jar(text: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.add("fixture.txt", text.toByteArray()) }
        return bytes.toByteArray()
    }

    private fun ZipOutputStream.add(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.timeLocal = LocalDateTime.of(1980, 2, 1, 0, 0)
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    override fun close() {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
