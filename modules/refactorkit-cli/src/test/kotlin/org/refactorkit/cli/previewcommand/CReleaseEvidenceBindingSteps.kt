package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Base64
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Scenario-scoped adapter for operational metadata binding; no native acceptance is fabricated. */
class CReleaseEvidenceBindingSteps {
    private var fixture: ReleaseBindingFixture? = null
    @Given("a v0.8 release evidence binding fixture has {string}")
    fun prepare(case: String) { fixture = ReleaseBindingFixture(case) }
    @When("the release binding captures source then finalizes the native evidence")
    fun execute() = requireNotNull(fixture).execute()
    @Then("the v0.8 release binding reports {string}")
    fun outcome(expected: String) = requireNotNull(fixture).outcome(expected)
    @Then("release binding preserves inputs and writes only the requested complete receipt")
    fun invariants() = requireNotNull(fixture).invariants()
    @After
    fun close() { fixture?.close(); fixture = null }
}

private class ReleaseBindingFixture(private val condition: String) : AutoCloseable {
    private val owned = Files.createTempDirectory("rk-release-binding-")
    private val root = Files.createDirectory(owned.resolve("source"))
    private val repository = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
    private val script = repository.resolve("scripts/bind-v080-release-evidence.py")
    private val reference = Path.of(requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
    private val tools = listOf("usr/bin/clang-22", "usr/bin/clangd", "usr/bin/clang-format", "usr/lib/libclang-cpp.so.22.1")
    private val referenceImages = tools.associateWith { sha(reference.resolve(it)) }
    private val toolchain: Path
    private val start = root.resolve(".artifacts/start.json")
    private val output = root.resolve(".artifacts/bound.json")
    private val archive = root.resolve(".artifacts/refactorkit-runtime-0.8.0-linux-x86_64.zip")
    private val checksum = root.resolve(".artifacts/runtime.sha256")
    private val spdx = root.resolve(".artifacts/runtime.spdx.json")
    private val upstreamSpdx = root.resolve(".artifacts/upstream.spdx.json")
    private val spdxReceipt = root.resolve(".artifacts/spdx-qualification.json")
    private val buildLog = root.resolve(".artifacts/build.log")
    private val cucumberReport = "modules/refactorkit-cli/build/reports/cucumber/binding.json"
    private val referenceProducer = Path.of(requireNotNull(System.getenv("REFACTORKIT_SYFT_QUALIFICATION_TOOLCHAIN")))
    private val producerInputs = listOf("syft", "LICENSE").associateWith { sha(referenceProducer.resolve(it)) }
    private val selectedProducer = if (condition == "producer executable changes after capture") {
        val copy = root.resolve(".artifacts/producer"); Files.createDirectories(copy)
        for (name in producerInputs.keys) Files.copy(referenceProducer.resolve(name), copy.resolve(name), StandardCopyOption.COPY_ATTRIBUTES)
        copy.resolve("syft")
    } else referenceProducer.resolve("syft")
    private val producerReceipt = root.resolve(".artifacts/sbom-producer.json")
    private val resolvedComponents = condition.startsWith("resolved component ")
    private val componentManifest = root.resolve(".artifacts/runtime-components.json")
    private var exit = -1
    private var stdout = ""
    private var stderr = ""
    private var inputs = emptyMap<String, String>()

    init {
        write(".gitignore", ".artifacts/\n.gradle/\nbuild/\nmodules/**/build/\n")
        write("build.gradle.kts", "allprojects { version = \"0.8.0\" }\n")
        write("modules/refactorkit-core/src/main/kotlin/org/refactorkit/core/RefactorKitVersion.kt",
            "object RefactorKitVersion { const val VERSION = \"0.8.0\"; const val API_VERSION = \"0.2\" }\n")
        write("Tracked.md", "Authored metadata fixture, not an executable qualification.\n")
        write("features/c-fixture.feature", "Feature: Metadata binding fixture\n  Scenario: Recorded metadata\n    Given metadata fixture evidence\n")
        for (name in listOf("bind-v080-release-evidence.py", "verify-runtime-archive.py", "qualify-release-spdx.py", "qualify-sbom-producer.py")) {
            write("scripts/$name", Files.readString(repository.resolve("scripts/$name")))
        }
        for (name in listOf("spdx-schema.json", "provenance.json")) {
            write("qualification/spdx-2.3/$name", Files.readString(repository.resolve("qualification/spdx-2.3/$name")))
        }
        val node = Path.of(requireNotNull(System.getenv("REFACTORKIT_SCHEMA_NODE"))).toRealPath()
        val ajv = Path.of(requireNotNull(System.getenv("REFACTORKIT_SCHEMA_AJV_MODULE"))).toRealPath()
        write(".artifacts/profile.json", buildJsonObject {
            put("status", "VERIFIED_FIXTURE_INPUTS")
            put("selectors", buildJsonObject { put("REFACTORKIT_SCHEMA_NODE", node.toString()); put("REFACTORKIT_SCHEMA_AJV_MODULE", ajv.toString()) })
            put("artifacts", buildJsonObject {
                put("node", buildJsonObject { put("version", "v22.18.0"); put("sha256", sha(node)) })
                put("ajv", buildJsonObject { put("version", "6.15.0"); put("packageSha256", sha(ajv.resolve("package.json"))) })
                put("fixture", buildJsonObject { put("nativeQualification", false) })
            })
        }.toString())
        toolchain = if (condition == "toolchain changes") {
            val clone = root.resolve(".artifacts/toolchain")
            val paths = tools.map(reference::resolve) + listOf(reference.resolve("usr/include/llvm/Support/LICENSE.TXT")) +
                Files.walk(reference.resolve("usr/lib/clang/22")).use { it.filter { path -> Files.isRegularFile(path) }.toList() }
            for (path in paths) {
                val target = clone.resolve(reference.relativize(path)); Files.createDirectories(target.parent)
                Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
            }
            clone
        } else reference
        if (resolvedComponents) prepareComponentBuild()
        command("git-init", listOf("git", "init", "--quiet"))
        command("git-add", listOf("git", "add", "."))
        command("git-commit", listOf("git", "-c", "user.name=BindingFixture", "-c", "user.email=binding@example.invalid", "commit", "--quiet", "-m", "authored binding fixture"))
        val binary = ByteArray(64).apply {
            this[0] = 0x7f; this[1] = 'E'.code.toByte(); this[2] = 'L'.code.toByte(); this[3] = 'F'.code.toByte()
            this[4] = 2; this[5] = 1; this[18] = 62
        }
        val members = mutableMapOf(
            "runtime/bin/java" to binary,
            "runtime/release" to "JAVA_VERSION=\"21\"\nMODULES=\"java.base java.compiler java.logging java.xml jdk.unsupported jdk.zipfs\"\n".toByteArray(),
            "bin/refactorkit" to "#!/bin/sh\nexit 99\n".toByteArray(),
            "bin/refactorkit-daemon" to "#!/bin/sh\nexit 99\n".toByteArray(),
            "bin/refactorkit-mcp" to "#!/bin/sh\nexit 99\n".toByteArray(),
            "lib/refactorkit-core-0.8.0.jar" to metadataJar(),
        )
        if (resolvedComponents) {
            val packageComponents = root.resolve(".artifacts/package-components.json")
            captureComponents(packageComponents)
            for (module in listOf("refactorkit-cli", "refactorkit-core")) {
                val jarName = "$module-0.8.0.jar"
                if (condition != "resolved component archive drift" || module != "refactorkit-core") {
                    members["lib/$jarName"] = Files.readAllBytes(root.resolve("modules/$module/build/libs/$jarName"))
                }
            }
        }
        val data = root.resolve(".artifacts/members.json")
        Files.writeString(data, buildJsonObject { members.forEach { (name, bytes) -> put(name, Base64.getEncoder().encodeToString(bytes)) } }.toString())
        val creator = "import sys,json,zipfile,stat,base64; z=zipfile.ZipFile(sys.argv[2],'w'); data=json.load(open(sys.argv[1]));\nfor name,value in data.items():\n i=zipfile.ZipInfo('refactorkit/'+name,(1980,2,1,0,0,0)); i.external_attr=(stat.S_IFREG|(0o755 if name.startswith('bin/') or name=='runtime/bin/java' else 0o644))<<16; z.writestr(i,base64.b64decode(value))\nz.close()\n"
        command("archive-fixture", listOf("python3", "-c", creator, data.toString(), archive.toString()))
        Files.writeString(checksum, sha(archive) + "  " + archive.fileName + "\n")
        Files.writeString(upstreamSpdx, """{"spdxVersion":"SPDX-2.3","SPDXID":"SPDXRef-DOCUMENT","dataLicense":"CC0-1.0","name":"Authored release binding fixture","documentNamespace":"https://example.invalid/binding-fixture","creationInfo":{"created":"2026-11-16T00:00:00Z","creators":["Tool: authored metadata fixture"]},"packages":[{"SPDXID":"SPDXRef-Fixture","name":"metadata fixture","downloadLocation":"NOASSERTION","filesAnalyzed":false}],"documentDescribes":["SPDXRef-Fixture"]}""")
    }

    fun execute() {
        val revision = command("git-head", listOf("git", "rev-parse", "HEAD")).trim()
        if (condition == "producer selection comes only from a receipt") {
            val untrusted = root.resolve(".artifacts/untrusted-producer")
            Files.writeString(untrusted, "#!/bin/sh\nmkdir \"${root.resolve("unexpected-execution")}\"\nexit 1\n")
            assertTrue(untrusted.toFile().setExecutable(true))
            Files.writeString(producerReceipt, buildJsonObject { put("executablePath", untrusted.toString()) }.toString())
        }
        invoke("begin", listOf("begin", "--root", root.toString(), "--revision", revision, "--tag", "v0.8.0",
            "--toolchain", toolchain.toString(), "--profile", root.resolve(".artifacts/profile.json").toString(), "--output", start.toString()) +
            if (condition == "producer selection comes only from a receipt") emptyList() else
                listOf("--sbom-producer", selectedProducer.toString()))
        if (exit != 0) { inputs = images(); return }
        seedReports()
        if (resolvedComponents) {
            captureComponents(componentManifest)
            if (condition == "resolved component forged GAV") {
                val text = Files.readString(componentManifest)
                assertContains(text, "org.refactorkit.fixture")
                Files.writeString(componentManifest, text.replace("org.refactorkit.fixture", "org.forged.fixture"))
            }
        }
        val payload = root.resolve(".artifacts/payload/refactorkit")
        java.util.zip.ZipFile(archive.toFile()).use { zip ->
            for (entry in zip.entries().asSequence()) {
                assertTrue(entry.name.startsWith("refactorkit/") && !entry.name.contains(".."))
                val path = payload.resolve(entry.name.removePrefix("refactorkit/"))
                Files.createDirectories(path.parent)
                zip.getInputStream(entry).use { input -> Files.copy(input, path) }
            }
        }
        Files.delete(upstreamSpdx)
        command("actual-producer", listOf("python3", repository.resolve("scripts/qualify-sbom-producer.py").toString(), "generate",
            "--producer", selectedProducer.toString(), "--archive", archive.toString(), "--runtime-root", payload.toString(),
            "--output", upstreamSpdx.toString(), "--receipt", producerReceipt.toString()))
        val qualifyComponents = resolvedComponents && condition != "resolved component archive drift"
        val tools = listOf("--archive", archive.toString(), "--version", "0.8.0",
            "--node", requireNotNull(System.getenv("REFACTORKIT_SCHEMA_NODE")),
            "--ajv-module", requireNotNull(System.getenv("REFACTORKIT_SCHEMA_AJV_MODULE"))) +
            if (qualifyComponents) listOf("--runtime-components", componentManifest.toString()) else emptyList()
        val qualifier = repository.resolve("scripts/qualify-release-spdx.py").toString()
        command("assemble-spdx", listOf("python3", qualifier, "assemble") + tools +
            listOf("--spdx", upstreamSpdx.toString(), "--output", spdx.toString()))
        command("verify-spdx", listOf("python3", qualifier, "verify") + tools +
            listOf("--spdx", spdx.toString(), "--upstream-spdx", upstreamSpdx.toString(), "--receipt", spdxReceipt.toString()))
        when (condition) {
            "resolved component stale input" -> Files.setLastModifiedTime(componentManifest, FileTime.fromMillis(1))
            "resolved component source drift" -> write("scripts/runtime-component-manifest.gradle", "// Drift after capture.\n")
            "producer executable changes after capture" -> RandomAccessFile(selectedProducer.toFile(), "rw").use {
                it.seek(it.length() - 1); val old = it.read(); it.seek(it.length() - 1); it.write(old xor 1)
            }
            "producer execution receipt disappears" -> Files.delete(producerReceipt)
            "producer execution receipt is stale" -> Files.setLastModifiedTime(producerReceipt, FileTime.fromMillis(1))
            "producer receipt rewrites raw SBOM identity" -> {
                val value = Json.parseToJsonElement(Files.readString(producerReceipt)).jsonObject
                Files.writeString(producerReceipt, JsonObject(value + ("upstreamSpdxSha256" to JsonPrimitive("0".repeat(64)))).toString())
            }
            "source changes after start" -> write("Tracked.md", "Controlled source drift before finalization.\n")
            "toolchain changes" -> RandomAccessFile(toolchain.resolve("usr/bin/clang-22").toFile(), "rw").use {
                it.seek(it.length() - 1); val old = it.read(); it.seek(it.length() - 1); it.write(old xor 1)
            }
            "stale native reports" -> Files.setLastModifiedTime(root.resolve("modules/refactorkit-c/build/test-results/test/TEST-binding.xml"), FileTime.fromMillis(1))
            "a native test fails" -> write("modules/refactorkit-c/build/test-results/test/TEST-binding.xml", "<testsuite tests=\"1\" failures=\"1\" errors=\"0\" skipped=\"0\"><testcase name=\"metadata\"><failure/></testcase></testsuite>")
            "asset checksum mismatch" -> Files.writeString(checksum, "0".repeat(64) + "  " + archive.fileName + "\n")
            "Cucumber report at the native report byte limit", "Cucumber report above the native report byte limit" -> {
                val report = root.resolve(cucumberReport)
                val limit = 32 * 1024 * 1024
                val targetSize = if (condition == "Cucumber report above the native report byte limit") limit + 1 else limit
                Files.writeString(report, " ".repeat(targetSize - Files.size(report).toInt()), StandardOpenOption.APPEND)
                assertEquals(targetSize.toLong(), Files.size(report))
            }
            "native evidence disappears" -> Files.delete(root.resolve("modules/refactorkit-cli/build/reports/c-qualification/test/compiler-runtime/record.json"))
            "SPDX is incomplete" -> Files.writeString(spdx, """{"spdxVersion":"SPDX-2.3","packages":[]}""")
            "SPDX qualification receipt disappears" -> Files.delete(spdxReceipt)
            "SPDX qualification receipt is stale" -> Files.setLastModifiedTime(spdxReceipt, FileTime.fromMillis(1))
            "SPDX schema is forged after validation" -> {
                val value = Json.parseToJsonElement(Files.readString(spdx)).jsonObject
                Files.writeString(spdx, JsonObject(value + ("creationInfo" to JsonObject(value.getValue("creationInfo").jsonObject - "created"))).toString())
            }
            "SPDX inventory is forged" -> {
                val value = Json.parseToJsonElement(Files.readString(spdx)).jsonObject
                val removed = value.getValue("files").jsonArray.last().jsonObject.getValue("SPDXID").jsonPrimitive.content
                val forged = JsonObject(value + mapOf(
                    "files" to JsonArray(value.getValue("files").jsonArray.filter { it.jsonObject.getValue("SPDXID").jsonPrimitive.content != removed }),
                    "packages" to JsonArray(value.getValue("packages").jsonArray.map {
                        val pkg = it.jsonObject
                        if ("hasFiles" in pkg) JsonObject(pkg + ("hasFiles" to JsonArray(pkg.getValue("hasFiles").jsonArray.filter { it.jsonPrimitive.content != removed }))) else pkg
                    }),
                    "relationships" to JsonArray(value.getValue("relationships").jsonArray.filter { it.jsonObject.getValue("relatedSpdxElement").jsonPrimitive.content != removed })))
                Files.writeString(spdx, forged.toString())
            }
            "SPDX receipt supplies an executable" -> {
                val untrusted = root.resolve(".artifacts/untrusted-node")
                Files.writeString(untrusted, "#!/bin/sh\nmkdir \"${root.resolve("unexpected-execution")}\"\nexit 1\n")
                assertTrue(untrusted.toFile().setExecutable(true))
                val value = Json.parseToJsonElement(Files.readString(spdxReceipt)).jsonObject
                val tools = value.getValue("tools").jsonObject
                val node = tools.getValue("node").jsonObject
                Files.writeString(spdxReceipt, JsonObject(value + ("tools" to JsonObject(tools + ("node" to JsonObject(node + ("path" to JsonPrimitive(untrusted.toString()))))))).toString())
            }
            "a captured component is rewritten" -> {
                val value = Json.parseToJsonElement(Files.readString(spdx)).jsonObject
                Files.writeString(spdx, JsonObject(value + ("packages" to JsonArray(value.getValue("packages").jsonArray.mapIndexed { index, item ->
                    if (index == 0) JsonObject(item.jsonObject + ("name" to JsonPrimitive("rewritten component provenance"))) else item
                }))).toString())
                val receipt = Json.parseToJsonElement(Files.readString(spdxReceipt)).jsonObject
                Files.writeString(spdxReceipt, JsonObject(receipt + ("spdxSha256" to JsonPrimitive(sha(spdx)))).toString())
            }
            "fixture profile changes" -> write(".artifacts/profile.json", """{"status":"changed after capture"}""")
            "a C scenario is missing" -> write(cucumberReport, """[{"uri":"classpath:c-fixture.feature","elements":[]}]""")
            "output already exists" -> Files.writeString(output, "Pre-existing caller-owned output must survive.\n")
            "archive contains traversal" -> {
                command("unsafe-archive", listOf("python3", "-c", "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a'); z.writestr('refactorkit/../escape.txt','metadata traversal sentinel'); z.close()", archive.toString()))
                Files.writeString(checksum, sha(archive) + "  " + archive.fileName + "\n")
            }
        }
        inputs = images()
        val componentSelection = if (resolvedComponents && condition != "resolved component absent input")
            listOf("--runtime-components", componentManifest.toString()) else emptyList()
        invoke("finish", listOf("finish", "--root", root.toString(), "--start", start.toString(), "--archive", archive.toString(),
            "--checksum", checksum.toString(), "--spdx", spdx.toString(), "--spdx-receipt", spdxReceipt.toString(),
            "--upstream-spdx", upstreamSpdx.toString(),
            "--sbom-producer-receipt", producerReceipt.toString(),
            "--build-log", buildLog.toString(), "--output", output.toString()) + componentSelection)
    }

    private fun prepareComponentBuild() {
        write("settings.gradle.kts", """rootProject.name = "binding-fixture"
            |include(":modules:refactorkit-core", ":modules:refactorkit-cli")
            |""".trimMargin())
        Files.writeString(root.resolve("build.gradle.kts"),
            "allprojects { version = \"0.8.0\"; group = \"org.refactorkit.fixture\" }\n")
        write("modules/refactorkit-core/build.gradle.kts", "plugins { java }\n")
        write("modules/refactorkit-cli/build.gradle.kts", """
            plugins { java }
            dependencies { implementation(project(":modules:refactorkit-core")) }
            apply(from = rootProject.file("scripts/runtime-component-manifest.gradle"))
        """.trimIndent())
        for (name in listOf("gradlew", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
            "scripts/qualify-runtime-components.py", "scripts/runtime-component-manifest.gradle")) {
            val target = root.resolve(name)
            Files.createDirectories(target.parent)
            Files.copy(repository.resolve(name), target, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    private fun captureComponents(output: Path) {
        val log = owned.resolve(output.fileName.toString() + ".log")
        val process = ProcessBuilder(root.resolve("gradlew").toString(), "--offline", "--no-daemon",
            "--no-watch-fs", "--max-workers=2", "--console=plain", ":modules:refactorkit-cli:writeRuntimeComponentManifest",
            "-Prefactorkit.runtime.components.output=$output")
            .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(100, TimeUnit.SECONDS)) {
            process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
            error("Gradle fixture capture timed out")
        }
        assertEquals(0, process.exitValue(), Files.readString(log))
    }

    private fun seedReports() {
        for (module in listOf("refactorkit-c", "refactorkit-cli", "refactorkit-lsp", "refactorkit-testkit")) {
            write("modules/$module/build/test-results/test/TEST-binding.xml", "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase name=\"metadata fixture\"/></testsuite>\n")
        }
        write(cucumberReport, """[{"uri":"classpath:c-fixture.feature","elements":[{"type":"scenario","name":"Recorded metadata","steps":[{"result":{"status":"passed"}}]}]}]""")
        for (area in listOf("compiler-runtime", "formatter-runtime", "runtime-discovery", "runtime-manifest")) {
            write("modules/refactorkit-cli/build/reports/c-qualification/test/$area/record.json", """{"boundary":"authored metadata, not native acceptance","outcome":"binding fixture","nativeRuntimeIdentity":"${"a".repeat(64)}"}""")
        }
        Files.writeString(buildLog, "> Task :modules:refactorkit-c:test\n> Task :modules:refactorkit-cli:test\n> Task :modules:refactorkit-lsp:test\n> Task :goldenTest\nBUILD SUCCESSFUL in 1s\n")
    }

    fun outcome(expected: String) {
        if (expected == "bound evidence") {
            assertEquals(0, exit, stderr); val receipt = Json.parseToJsonElement(Files.readString(output)).jsonObject
            assertEquals("BOUND_EVIDENCE", receipt.getValue("status").jsonPrimitive.content)
            assertEquals(sha(archive), receipt.getValue("asset").jsonObject.getValue("sha256").jsonPrimitive.content)
            assertEquals(sha(spdx), receipt.getValue("spdx").jsonObject.getValue("sha256").jsonPrimitive.content)
            if (condition == "pinned producer and captured payload") {
                val producer = receipt.getValue("spdx").jsonObject.getValue("producer").jsonObject
                assertEquals(sha(selectedProducer), producer.getValue("executableSha256").jsonPrimitive.content)
                assertEquals(sha(producerReceipt), producer.getValue("receiptSha256").jsonPrimitive.content)
            }
            val spdxBinding = receipt.getValue("spdx").jsonObject
            assertEquals("FULL_SCHEMA_AND_EXACT_FILE_INVENTORY_NOT_LICENSE_REVIEW", spdxBinding.getValue("validation").jsonPrimitive.content)
            assertEquals(sha(spdxReceipt), spdxBinding.getValue("qualificationSha256").jsonPrimitive.content)
            assertEquals(if (resolvedComponents) 7 else 6, spdxBinding.getValue("fileCount").jsonPrimitive.int)
            if (resolvedComponents) {
                val components = spdxBinding.getValue("runtimeComponents").jsonObject
                assertEquals("SOURCE_OWNED_GRADLE_REPLAY", components.getValue("basis").jsonPrimitive.content)
                assertEquals(sha(componentManifest), components.getValue("manifestSha256").jsonPrimitive.content)
                assertEquals(2, components.getValue("componentCount").jsonPrimitive.int)
            }
            assertEquals(command("expected-head", listOf("git", "rev-parse", "HEAD")).trim(), receipt.getValue("sourceCommit").jsonPrimitive.content)
            assertEquals(command("expected-tree", listOf("git", "rev-parse", "HEAD^{tree}")).trim(), receipt.getValue("sourceTree").jsonPrimitive.content)
            assertEquals(sha(root.resolve("Tracked.md")), receipt.getValue("sourceSha256").jsonObject.getValue("Tracked.md").jsonPrimitive.content)
            assertEquals(sha(root.resolve(".artifacts/profile.json")), receipt.getValue("fixtureProfile").jsonObject.getValue("sha256").jsonPrimitive.content)
            val boundTools = receipt.getValue("toolchain").jsonObject.getValue("files").jsonObject
            for ((name, image) in referenceImages) assertEquals(image, boundTools.getValue(name).jsonPrimitive.content)
            val members = Json.parseToJsonElement(Files.readString(root.resolve(".artifacts/members.json"))).jsonObject
            val javaBytes = Base64.getDecoder().decode(members.getValue("runtime/bin/java").jsonPrimitive.content)
            assertEquals(shaBytes(javaBytes), receipt.getValue("runtimeMembersSha256").jsonObject.getValue("refactorkit/runtime/bin/java").jsonPrimitive.content)
            assertEquals(1, receipt.getValue("cScenarioCoverage").jsonObject.getValue("c-fixture.feature").jsonPrimitive.int)
            val reportHashes = receipt.getValue("reportSha256").jsonObject
            assertEquals(sha(root.resolve(cucumberReport)), reportHashes.getValue(cucumberReport).jsonPrimitive.content)
            assertFalse(receipt.getValue("wholeReleaseQualified").jsonPrimitive.boolean)
            assertEquals(4, receipt.getValue("nativeEvidence").jsonObject.size)
        } else {
            assertEquals(1, exit, stdout); val refusal = Json.parseToJsonElement(stderr.trim()).jsonObject
            assertEquals(expected, refusal.getValue("code").jsonPrimitive.content, stderr)
            if (condition == "output already exists") {
                assertEquals("Pre-existing caller-owned output must survive.\n", Files.readString(output))
            } else assertFalse(Files.exists(output), "A refused binding must not write its output")
        }
    }

    fun invariants() {
        assertEquals(inputs, images()); assertEquals(referenceImages, tools.associateWith { sha(reference.resolve(it)) })
        assertEquals(producerInputs, producerInputs.keys.associateWith { sha(referenceProducer.resolve(it)) })
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertFalse(Files.exists(root.resolve("unexpected-execution")))
        assertFalse(Files.exists(root.resolve("escape.txt")))
        assertFalse(Files.exists(owned.resolve("escape.txt")))
        val workflow = Files.readString(repository.resolve(".github/workflows/release.yml"))
        val historical = workflow.split(Regex("(?m)(?=^      - name:)"))
            .filter { Regex("(?m)^        if: github.ref_name == 'v0\\.7\\.0'$").containsMatchIn(it) }
            .joinToString("\n") { it.trimEnd('\r', '\n') }
        assertEquals("4fe51e349db839680f1390180812632d425d8dc9f76e4eb4bc791e9abaad40e9", shaBytes(historical.toByteArray()), "Historical v0.7 guarded blocks changed")
        val capture = workflow.indexOf("- name: Capture exact v0.8 source and native qualification inputs")
        val build = workflow.indexOf("- name: Clean v0.8 build and complete standard regression")
        val sbom = workflow.indexOf("- name: Generate platform SPDX SBOM")
        val bind = workflow.indexOf("- name: Bind v0.8 source, native reports, toolchain, runtime and SPDX")
        val attest = workflow.indexOf("- name: Attest platform runtime provenance")
        assertTrue(capture >= 0 && capture < build && build < sbom && sbom < bind && bind < attest)
        assertTrue(workflow.substring(capture, build).contains("scripts/bind-v080-release-evidence.py begin"))
        assertTrue(workflow.substring(capture, build).contains("if: startsWith(github.ref_name, 'v0.8.')"))
        assertTrue(workflow.substring(build, sbom).contains("clean build goldenTest --no-daemon --no-build-cache"))
        assertTrue(workflow.substring(bind, attest).contains("scripts/bind-v080-release-evidence.py finish"))
        assertTrue(workflow.substring(bind, attest).contains("if: startsWith(github.ref_name, 'v0.8.')"))
        assertTrue(workflow.substring(bind, attest).contains("always() && startsWith(github.ref_name, 'v0.8.')"))
    }

    private fun images() = Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it) && !it.startsWith(root.resolve(".git")) && it != output }
        .filter { !resolvedComponents || root.relativize(it).none { part -> part.toString() in setOf(".gradle", "build") } }
        .toList().associate { root.relativize(it).toString() to sha(it) } }
    private fun invoke(label: String, arguments: List<String>) {
        val out = owned.resolve("$label.stdout"); val err = owned.resolve("$label.stderr")
        val process = ProcessBuilder(listOf("python3", script.toString()) + arguments).directory(root.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start()
        if (!process.waitFor(90, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); error("Binding process timed out") }
        exit = process.exitValue(); stdout = Files.readString(out); stderr = Files.readString(err)
    }
    private fun command(label: String, arguments: List<String>): String {
        val safe = if (arguments.first() == "git") listOf("git", "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgSign=false", "-c", "core.fsmonitor=false") + arguments.drop(1) else arguments
        val log = owned.resolve("$label.log"); val process = ProcessBuilder(safe).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); error("Fixture command timed out") }
        assertEquals(0, process.exitValue(), Files.readString(log)); return Files.readString(log)
    }
    private fun write(relative: String, text: String) { val path = root.resolve(relative); Files.createDirectories(path.parent); Files.writeString(path, text) }
    override fun close() { Files.walk(owned).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    companion object {
        private fun metadataJar(): ByteArray {
            val bytes = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(bytes).use { zip ->
                val entries = mapOf(
                    "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n\n",
                    "META-INF/maven/org.refactorkit/refactorkit-core/pom.properties" to
                        "groupId=org.refactorkit\nartifactId=refactorkit-core\nversion=0.8.0\n",
                )
                for ((name, content) in entries) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
                }
            }
            return bytes.toByteArray()
        }
        private fun shaBytes(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun sha(path: Path): String = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(1024 * 1024)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        digest.digest().joinToString("") { "%02x".format(it) }
    } }
}

/** Scenario-scoped transport; archive/SPDX policy lives in the release service. */
class CSpdxInventoryQualificationSteps {
    private var fixture: SpdxInventoryFixture? = null
    @Given("a release SPDX inventory fixture has {string}")
    fun prepare(condition: String) { fixture = SpdxInventoryFixture(condition) }
    @When("the release tooling assembles SPDX inventory and verifies the captured archive")
    fun execute() = requireNotNull(fixture).execute()
    @Then("the SPDX inventory qualification reports {string}")
    fun result(expected: String) = requireNotNull(fixture).verify(expected)
    @Then("SPDX qualification preserves every input and emits no refused validation receipt")
    fun invariants() = requireNotNull(fixture).invariants()
    @After fun close() { fixture?.close(); fixture = null }
}

private class SpdxInventoryFixture(private val condition: String) : AutoCloseable {
    private val owned = Files.createTempDirectory("rk-spdx-inventory-")
    private val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
    private val script = repository.resolve("scripts/qualify-release-spdx.py")
    private val archive = owned.resolve("refactorkit-runtime-0.8.0-linux-x86_64.zip")
    private val raw = owned.resolve("upstream.spdx.json")
    private val complete = owned.resolve("complete.spdx.json")
    private val receipt = owned.resolve("qualification.json")
    private val node = requireNotNull(System.getenv("REFACTORKIT_SCHEMA_NODE"))
    private val ajv = requireNotNull(System.getenv("REFACTORKIT_SCHEMA_AJV_MODULE"))
    private val members = linkedMapOf("refactorkit/bin/refactorkit" to "Authored metadata; never executed.\n",
        "refactorkit/runtime/release" to "JAVA_VERSION=\"21\"\n",
        "refactorkit/lib/refactorkit-core-0.8.0.jar" to "Authored placeholder, not a native JAR.\n")
    private val fixedInputs = listOf(script, repository.resolve("qualification/spdx-2.3/spdx-schema.json"),
        repository.resolve("qualification/spdx-2.3/provenance.json")).associateWith(::hash)
    private var inputs = emptyMap<Path, String>()
    private var exit = -1
    private var stderr = ""
    private var invocation = 0

    init {
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            members.forEach { (name, text) ->
                val entry = java.util.zip.ZipEntry(name)
                entry.timeLocal = java.time.LocalDateTime.of(1980, 2, 1, 0, 0)
                zip.putNextEntry(entry); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        Files.writeString(raw, """{"spdxVersion":"SPDX-2.3","SPDXID":"SPDXRef-DOCUMENT","dataLicense":"CC0-1.0","name":"Authored SPDX fixture","documentNamespace":"https://example.invalid/fixture","creationInfo":{"created":"2026-11-16T00:00:00Z","creators":["Tool: authored fixture"]},"documentDescribes":["SPDXRef-Fixture"],"packages":[{"SPDXID":"SPDXRef-Fixture","name":"metadata fixture","downloadLocation":"NOASSERTION","filesAnalyzed":false}]}""")
    }
    fun execute() {
        invoke("assemble", listOf("--spdx", raw.toString(), "--output", complete.toString()))
        if (exit != 0) { inputs = images(); return }
        var doc = Json.parseToJsonElement(Files.readString(complete)).jsonObject
        when (condition) {
            "required creation is missing" -> doc = JsonObject(doc + ("creationInfo" to JsonObject(doc.getValue("creationInfo").jsonObject - "created")))
            "an archive file is omitted" -> {
                val omitted = doc.getValue("files").jsonArray.last().jsonObject.getValue("SPDXID").jsonPrimitive.content
                doc = JsonObject(doc + mapOf(
                    "files" to JsonArray(doc.getValue("files").jsonArray.filter { it.jsonObject.getValue("SPDXID").jsonPrimitive.content != omitted }),
                    "packages" to JsonArray(doc.getValue("packages").jsonArray.map { value ->
                        val pkg = value.jsonObject
                        if ("hasFiles" in pkg) JsonObject(pkg + ("hasFiles" to JsonArray(pkg.getValue("hasFiles").jsonArray.filter { it.jsonPrimitive.content != omitted }))) else pkg
                    }),
                    "relationships" to JsonArray(doc.getValue("relationships").jsonArray.filter { it.jsonObject.getValue("relatedSpdxElement").jsonPrimitive.content != omitted })))
            }
            "a file checksum differs" -> doc = JsonObject(doc + ("files" to JsonArray(doc.getValue("files").jsonArray.mapIndexed { index, value ->
                val file=value.jsonObject
                if (index != 0) file else JsonObject(file + ("checksums" to JsonArray(file.getValue("checksums").jsonArray.map {
                    val checksum=it.jsonObject
                    if (checksum.getValue("algorithm").jsonPrimitive.content != "SHA256") checksum else JsonObject(checksum + ("checksumValue" to JsonPrimitive("0".repeat(64))))
                })))
            })))
            "a relationship dangles" -> doc = JsonObject(doc + ("relationships" to JsonArray(doc.getValue("relationships").jsonArray + buildJsonObject {
                put("spdxElementId", "SPDXRef-DOCUMENT"); put("relatedSpdxElement", "SPDXRef-Unavailable"); put("relationshipType", "DESCRIBES")
            })))
            "an element identity repeats" -> doc = JsonObject(doc + ("files" to JsonArray(doc.getValue("files").jsonArray + doc.getValue("files").jsonArray.first())))
        }
        Files.writeString(complete, doc.toString()); inputs = images()
        invoke("verify", listOf("--spdx", complete.toString(), "--upstream-spdx", raw.toString(), "--receipt", receipt.toString()))
    }
    fun verify(expected: String) {
        if (expected != "verified schema and file inventory") {
            assertEquals(1, exit, stderr)
            assertEquals(expected, Json.parseToJsonElement(stderr.trim()).jsonObject.getValue("code").jsonPrimitive.content)
            assertFalse(Files.exists(receipt), "Refused qualification must not emit its receipt")
            return
        }
        assertEquals(0, exit, stderr)
        val qualification = Json.parseToJsonElement(Files.readString(receipt)).jsonObject
        assertEquals("VERIFIED_SPDX_SCHEMA_AND_FILE_INVENTORY", qualification.getValue("status").jsonPrimitive.content)
        assertEquals(hash(archive), qualification.getValue("assetSha256").jsonPrimitive.content)
        assertEquals(hash(complete), qualification.getValue("spdxSha256").jsonPrimitive.content)
        val actual = qualification.getValue("runtimeMembersSha256").jsonObject
        assertEquals(members.keys, actual.keys)
        for ((name, text) in members) assertEquals(hashBytes(text.toByteArray()), actual.getValue(name).jsonPrimitive.content)
        assertFalse(qualification.getValue("wholeReleaseQualified").jsonPrimitive.boolean)
        assertEquals("PENDING", qualification.getValue("licenseReview").jsonPrimitive.content)
        val doc = Json.parseToJsonElement(Files.readString(complete)).jsonObject
        assertTrue(doc.getValue("packages").jsonArray.any { it.jsonObject.getValue("SPDXID").jsonPrimitive.content == "SPDXRef-Fixture" }, "Original component metadata must survive")
        verifyActualWorkflowStep()
    }
    private fun verifyActualWorkflowStep() {
        val optionsClass=Class.forName("org.yaml.snakeyaml.LoaderOptions")
        val options=optionsClass.getConstructor().newInstance()
        optionsClass.getMethod("setAllowDuplicateKeys", Boolean::class.javaPrimitiveType).invoke(options,false)
        val safe=Class.forName("org.yaml.snakeyaml.constructor.SafeConstructor").getConstructor(optionsClass).newInstance(options)
        val yamlClass=Class.forName("org.yaml.snakeyaml.Yaml")
        val parser=yamlClass.getConstructor(Class.forName("org.yaml.snakeyaml.constructor.BaseConstructor")).newInstance(safe)
        val workflow=yamlClass.getMethod("load",String::class.java).invoke(parser,Files.readString(repository.resolve(".github/workflows/release.yml"))) as Map<*, *>
        val jobs=workflow["jobs"] as Map<*, *>; val build=jobs["build-runtime"] as Map<*, *>
        val step=(build["steps"] as List<*>).map { it as Map<*, *> }
            .single { it["name"] == "Complete and verify v0.8 SPDX schema and archive file inventory" }
        assertEquals("startsWith(github.ref_name, 'v0.8.')",step["if"])
        val workspace=owned.resolve("workflow"); Files.createDirectories(workspace)
        for (name in listOf("scripts/qualify-release-spdx.py", "scripts/verify-runtime-archive.py",
            "qualification/spdx-2.3/spdx-schema.json", "qualification/spdx-2.3/provenance.json")) {
            val target=workspace.resolve(name);Files.createDirectories(target.parent);Files.copy(repository.resolve(name),target)
        }
        Files.copy(archive,workspace.resolve(archive.fileName))
        val upstream=workspace.resolve(".refactorkit/runs/v080-release-ci/upstream.spdx.json")
        Files.createDirectories(upstream.parent);Files.copy(raw,upstream)
        val runtimeJar = members.getValue("refactorkit/lib/refactorkit-core-0.8.0.jar").toByteArray()
        val componentManifest = upstream.resolveSibling("runtime-components.json")
        Files.writeString(componentManifest, buildJsonObject {
            put("kind", "refactorkit.resolved-runtime-components/v1")
            put("sourceVersion", "0.8.0")
            put("configuration", ":modules:refactorkit-cli:runtimeClasspath")
            putJsonArray("components") {
                add(buildJsonObject {
                    put("path", "lib/refactorkit-core-0.8.0.jar")
                    put("sha256", hashBytes(runtimeJar))
                    put("sha1", MessageDigest.getInstance("SHA-1").digest(runtimeJar).joinToString("") { "%02x".format(it) })
                    putJsonObject("coordinates") {
                        put("kind", "project")
                        put("group", "org.refactorkit")
                        put("artifact", "refactorkit-core")
                        put("version", "0.8.0")
                        put("projectPath", ":modules:refactorkit-core")
                    }
                })
            }
        }.toString())
        val log=owned.resolve("workflow.log")
        val process=ProcessBuilder("bash","-e","-u","-o","pipefail","-c",step["run"].toString())
            .directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).apply {
                environment()["GITHUB_REF_NAME"]="v0.8.0"
                environment()["REFACTORKIT_SCHEMA_NODE"]=node;environment()["REFACTORKIT_SCHEMA_AJV_MODULE"]=ajv
            }.start()
        if (!process.waitFor(45,TimeUnit.SECONDS)) { process.destroyForcibly();process.waitFor();error("Workflow SPDX step timed out") }
        assertEquals(0,process.exitValue(),Files.readString(log))
        val proof=Json.parseToJsonElement(Files.readString(workspace.resolve(".refactorkit/runs/v080-release-ci/spdx-qualification.json"))).jsonObject
        assertEquals(hash(archive),proof.getValue("assetSha256").jsonPrimitive.content)
        assertEquals(members.size,proof.getValue("fileCount").jsonPrimitive.int)
        assertEquals(hash(componentManifest),
            proof.getValue("runtimeComponents").jsonObject.getValue("manifestSha256").jsonPrimitive.content)
    }
    fun invariants() {
        assertEquals(inputs, images()); assertEquals(fixedInputs, fixedInputs.keys.associateWith(::hash))
        assertFalse(Files.exists(owned.resolve(".refactorkit/transactions")))
    }
    private fun invoke(mode: String, arguments: List<String>) {
        val out=owned.resolve("command-${invocation}.out"); val err=owned.resolve("command-${invocation++}.err")
        val command=listOf("python3", script.toString(), mode, "--archive", archive.toString(), "--version", "0.8.0", "--node", node, "--ajv-module", ajv) + arguments
        val process=ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile()).start()
        if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); error("SPDX service timed out") }
        exit=process.exitValue(); stderr=Files.readString(err)
    }
    private fun images() = listOf(archive, raw, complete).filter(Files::isRegularFile).associateWith(::hash)
    private fun hash(path: Path) = Files.newInputStream(path).use { input ->
        val digest=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(65536)
        while(true) { val count=input.read(buffer); if(count < 0) break; digest.update(buffer,0,count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun hashBytes(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    override fun close() { owned.toFile().deleteRecursively() }
}
