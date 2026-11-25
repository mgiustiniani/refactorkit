package org.refactorkit.cli.previewcommand

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import kotlinx.serialization.json.*
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class JavaQueryContextAuthoritySteps {
    private val testJdkHome: String by lazy { Path.of(System.getProperty("java.home")).toRealPath().toString() }
    private lateinit var root: Path
    private var session: DaemonSession? = null
    private lateinit var snapshotHash: String
    private lateinit var context: JsonObject
    private lateinit var baseline: Map<String, String>
    private lateinit var result: JsonObject
    private lateinit var variant: String
    private var queryDaemon: JavaQueryDaemonRpcSession? = null
    private lateinit var sequenceResults: List<JsonObject>

    @Given("a saved Java query fixture with an explicitly acquired SDK context")
    fun fixture() {
        root = Files.createTempDirectory("rk-java-query-context-")
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>pkg</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>8</maven.compiler.release></properties></project>\n")
        val file = root.resolve("src/main/java/pkg/Runner.java")
        Files.createDirectories(file.parent)
        Files.writeString(file, "package pkg; public class Runner {\n  java.util.List<String> values;\n  void run() { values.clear(); }\n}\n")
        session = DaemonSession()
        val opened = session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
        snapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", snapshotHash); put("sdkHome", testJdkHome)
        }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content)
        baseline = contents()
    }

    @Given("a saved Java query fixture with an explicitly acquired v5 SDK context")
    fun v5Fixture() {
        fixture()
        val module = context.getValue("modules").jsonArray.single().jsonObject
        context = session!!.dispatch("project.context.v5", buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", testJdkHome)
            put("providerId", "maven-effective-v1")
            put("moduleId", module.getValue("id").jsonPrimitive.content)
            put("sourceSetId", "main")
        }).jsonObject
        assertEquals("java-project-context-v5", context.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
    }

    @Given("a two-module Maven Java reactor has acquired project context over daemon stdio")
    fun twoModuleReactorOverStdio() {
        createTwoModuleReactor()
        queryDaemon = NativeJavaDaemonRpcSession(root)
        acquireReactorContext()
    }

    @Given("a two-module Maven Java reactor has acquired project context over packaged daemon stdio")
    fun twoModuleReactorOverPackagedStdio() {
        check(!System.getProperty("refactorkit.gradle.composite.package.root").isNullOrBlank()) {
            "Packaged query-context scenario requires a packaged runtime root"
        }
        createTwoModuleReactor()
        queryDaemon = PackagedJavaQueryDaemonRpcSession()
        acquireReactorContext()
    }

    private fun createTwoModuleReactor() {
        root = Files.createTempDirectory("rk-java-query-context-reactor-")
        val parent = """<project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>
            <properties><maven.compiler.release>8</maven.compiler.release></properties><modules><module>contracts</module><module>app</module></modules></project>"""
        write("pom.xml", "$parent\n")
        write("contracts/pom.xml", """<project><modelVersion>4.0.0</modelVersion><parent><groupId>sample</groupId><artifactId>reactor</artifactId><version>1</version></parent><artifactId>contracts</artifactId></project>
""")
        write("app/pom.xml", """<project><modelVersion>4.0.0</modelVersion><parent><groupId>sample</groupId><artifactId>reactor</artifactId><version>1</version></parent><artifactId>app</artifactId><dependencies><dependency><groupId>sample</groupId><artifactId>contracts</artifactId><version>1</version></dependency></dependencies></project>
""")
        write("contracts/src/main/java/sample/api/Service.java", """package sample.api;
public interface Service { String execute(); }
""")
        write("app/src/main/java/sample/app/DefaultService.java", """package sample.app;
import sample.api.Service;
public final class DefaultService implements Service {
  public String execute() { return "ok"; }
}
""")
        write("app/src/main/java/sample/app/Caller.java", """package sample.app;
import sample.api.Service;
final class Caller {
  Service service;
}
""")
        baseline = contents()
    }

    private fun acquireReactorContext() {
        val daemon = requireNotNull(queryDaemon)
        val opened = daemon.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
        snapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        context = daemon.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", Path.of(System.getProperty("java.home")).toRealPath().toString())
        })
        assertEquals("java-project-context-v2", context.getValue("schemaVersion").jsonPrimitive.content, context.toString())
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        assertEquals(2, context.getValue("modules").jsonArray.size, context.toString())
    }

    @When("the IDE requests definition and implementations through the same daemon session")
    fun querySequenceOverStdio() {
        val sdkHome = Path.of(System.getProperty("java.home")).toRealPath().toString()
        fun query(kind: String, path: String, line: Int, character: Int, requestId: String): JsonObject =
            requireNotNull(queryDaemon).dispatch("intelligence.query", buildJsonObject {
                put("requestId", requestId)
                put("kind", kind)
                put("languageId", "java")
                put("expectedSnapshotHash", snapshotHash)
                put("path", path)
                put("position", buildJsonObject { put("line", line); put("character", character) })
                put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
                put("javaContext", buildJsonObject {
                    put("sdkHome", sdkHome)
                    put("expectedContextHash", context.getValue("contextHash").jsonPrimitive.content)
                    put("contextSchemaVersion", context.getValue("schemaVersion").jsonPrimitive.content)
                })
            })
        sequenceResults = listOf(
            query("definition", "app/src/main/java/sample/app/Caller.java", 3, 2, "reactor-definition"),
            query("implementations", "contracts/src/main/java/sample/api/Service.java", 1, 17, "reactor-implementations"),
        )
    }

    @Then("both Java queries reply with the acquired context and expected source declarations")
    fun verifySequenceReplies() {
        assertEquals(2, sequenceResults.size)
        val definition = sequenceResults[0]
        assertEquals("ready", definition.getValue("status").jsonPrimitive.content, definition.toString())
        assertEquals(context.getValue("contextHash"), definition.getValue("contextHash"))
        assertEquals(context.getValue("schemaVersion"), definition.getValue("contextSchemaVersion"))
        val platformIdentities = context.getValue("sdk").jsonArray.map {
            it.jsonObject.getValue("identitySha256").jsonPrimitive.content
        }
        assertEquals(platformIdentities, definition.getValue("platformIdentities").jsonArray.map {
            it.jsonPrimitive.content
        })
        val target = definition.getValue("locations").jsonArray.single().jsonObject
        assertEquals("contracts/src/main/java/sample/api/Service.java",
            target.getValue("path").jsonPrimitive.content)

        val implementations = sequenceResults[1]
        assertEquals("ready", implementations.getValue("status").jsonPrimitive.content, implementations.toString())
        assertEquals(context.getValue("contextHash"), implementations.getValue("contextHash"))
        assertEquals(context.getValue("schemaVersion"), implementations.getValue("contextSchemaVersion"))
        assertEquals(platformIdentities, implementations.getValue("platformIdentities").jsonArray.map {
            it.jsonPrimitive.content
        })
        assertEquals("type", implementations.getValue("targetKind").jsonPrimitive.content)
        assertEquals(listOf("sample.app.DefaultService"), implementations.getValue("items").jsonArray.map {
            it.jsonObject.getValue("symbol").jsonObject.getValue("id").jsonPrimitive.content
        })
    }

    @When("the IDE requests Java {string} with {string} context authority")
    fun query(kind: String, authority: String) {
        variant = authority
        if (authority == "build drift") {
            Files.writeString(root.resolve("pom.xml"), Files.readString(root.resolve("pom.xml")).replace("<version>1</version>", "<version>2</version>"))
            baseline = contents()
        }
        result = session!!.dispatch("intelligence.query", buildJsonObject {
            put("requestId", "sdk-context-query"); put("kind", kind); put("languageId", "java")
            put("expectedSnapshotHash", snapshotHash); put("path", "src/main/java/pkg/Runner.java")
            put("position", buildJsonObject { put("line", 2); put("character", 15) })
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
            put("javaContext", buildJsonObject {
                val sdkHome = if (authority == "other SDK") {
                    requireNotNull(System.getenv("REFACTORKIT_ENTRYPOINT_SDK25_HOME")) {
                        "REFACTORKIT_ENTRYPOINT_SDK25_HOME is required for SDK mismatch qualification"
                    }
                } else {
                    testJdkHome
                }
                put("sdkHome", sdkHome)
                put("expectedContextHash", context.getValue("contextHash"))
                put("contextSchemaVersion", context.getValue("schemaVersion").jsonPrimitive.content)
            })
        }).jsonObject
    }

    @Then("the Java query reports {string} using the acquired SDK evidence")
    fun outcome(expected: String) {
        if (expected == "ready") {
            assertEquals(expected, result.getValue("status").jsonPrimitive.content)
            assertEquals(context.getValue("contextHash"), result["contextHash"])
            assertEquals(context.getValue("schemaVersion"), result.getValue("contextSchemaVersion"))
            val identities = context.getValue("sdk").jsonArray.map { it.jsonObject.getValue("identitySha256") }
            assertEquals(identities, result.getValue("platformIdentities").jsonArray.toList())
            assertEquals("saved-snapshot", result["sourceAuthority"]?.jsonObject?.get("kind")?.jsonPrimitive?.content ?: "saved-snapshot")
        } else {
            assertEquals("refused", result.getValue("status").jsonPrimitive.content)
            assertEquals(expected, result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertNull(result["contextHash"])
        }
    }

    @Then("the Java query preserves all saved source and build bytes")
    fun readOnly() { assertEquals(baseline, contents()) }

    private fun contents(): Map<String, String> = Files.walk(root).use { paths -> paths.filter {
        Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.fileName.toString() == "pom.xml")
    }.toList().associate { root.relativize(it).toString() to Files.readString(it) } }

    @After
    fun close() {
        queryDaemon?.close()
        session?.close()
        if (::root.isInitialized) Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun write(relative: String, text: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }
}

internal interface JavaQueryDaemonRpcSession : AutoCloseable {
    fun dispatch(method: String, params: JsonObject): JsonObject
}

private class PackagedJavaQueryDaemonRpcSession : JavaQueryDaemonRpcSession {
    private val delegate = newGradleContextRpcSession()

    override fun dispatch(method: String, params: JsonObject): JsonObject =
        delegate.dispatch(method, params).jsonObject

    override fun close() = delegate.close()
}

internal data class NativeJavaQueryReply(val message: JsonObject, val frameBytes: Int)

internal class NativeJavaDaemonRpcSession(workspace: Path, engineFallbackHome: Path? = null) : JavaQueryDaemonRpcSession {
    private data class OutputLine(val value: String?)

    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicLong()
    private val outputLines = LinkedBlockingQueue<OutputLine>()
    private val stderrFile = Files.createTempFile("rk-java-query-daemon-", ".stderr")
    private val process: Process
    private val writer: java.io.BufferedWriter
    private val stdoutReader: Thread

    init {
        val classpath = requireNotNull(System.getProperty("refactorkit.lsp.test.classpath")) {
            "CLI test task must provide the source-built daemon runtime classpath"
        }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val candidate = System.getenv("REFACTORKIT_ENTRYPOINT_CANDIDATE_ROOT")?.takeIf { it.isNotBlank() }?.let { Path.of(it).toRealPath() }
        val command = if (candidate == null) listOf(java) + listOfNotNull(engineFallbackHome?.let { "-Drefactorkit.java.platform.home=$it" }) + listOf("-cp", classpath, "org.refactorkit.daemon.RefactorKitDaemonKt") else {
            val launcher = candidate.resolve("bin/refactorkit-daemon")
            check(Files.isRegularFile(launcher) && Files.isExecutable(launcher)) { "Configured isolated daemon candidate is unavailable" }
            listOf(launcher.toString())
        }
        process = ProcessBuilder(command)
            .directory(workspace.toFile())
            .redirectError(stderrFile.toFile())
            .start()
        writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
        stdoutReader = Thread({
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { outputLines.put(OutputLine(it)) }
                }
            }
            outputLines.offer(OutputLine(null))
        }, "refactorkit-java-query-daemon-stdout").apply {
            isDaemon = true
            start()
        }
    }

    var lastFrameBytes: Int = 0
        private set

    override fun dispatch(method: String, params: JsonObject): JsonObject {
        val response = raw(method, params, JsonPrimitive(ids.incrementAndGet()))
        check(response["error"] == null || response["error"] == JsonNull) { "Daemon returned an RPC error for " + method + ": " + response }
        return requireNotNull(response["result"] as? JsonObject) { "Daemon omitted object result for " + method }
    }

    fun raw(method: String, params: JsonObject, id: JsonElement, expectedId: JsonElement? = id): JsonObject {
        write(method, params, id); writer.flush()
        val reply = read()
        if (expectedId != null) check(reply.message["id"] == expectedId) { "Mismatched daemon response ID" }
        lastFrameBytes = reply.frameBytes
        return reply.message
    }

    fun batch(requests: List<Pair<String, JsonObject>>): Pair<List<JsonElement>, List<NativeJavaQueryReply>> {
        val expected = requests.map { JsonPrimitive(ids.incrementAndGet()) }
        requests.zip(expected).forEach { (request, id) -> write(request.first, request.second, id) }
        writer.flush()
        val replies = requests.map { read() }
        check(replies.map { it.message["id"] }.toSet() == expected.toSet()) { "Missing/duplicate/unexpected batch response ID" }
        check(replies.map { it.message["id"] }.distinct().size == replies.size) { "Duplicate batch response" }
        return expected to replies
    }

    private fun write(method: String, params: JsonObject, id: JsonElement) {
        val request = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }
        writer.write(json.encodeToString(JsonObject.serializer(), request)); writer.newLine()
    }

    private fun read(): NativeJavaQueryReply {
        val line = outputLines.poll(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)?.value
            ?: error("Daemon closed stdio without replying; stderr: " + stderrText())
        val response = json.parseToJsonElement(line).jsonObject
        check(response["jsonrpc"]?.jsonPrimitive?.content == "2.0") { "Invalid daemon JSON-RPC response" }
        // The native daemon uses println; include its actual local line separator.
        val bytes = (line + System.lineSeparator()).toByteArray(Charsets.UTF_8).size
        check(bytes <= org.refactorkit.core.ProtocolLimits.MAX_DAEMON_RESPONSE_BYTES + 2) { "Unbounded generic daemon frame" }
        return NativeJavaQueryReply(response, bytes)
    }

    override fun close() {
        runCatching { writer.close() }
        if (!process.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            val stderr = stderrText()
            Files.deleteIfExists(stderrFile)
            error("Source-built daemon did not stop after stdin EOF; stderr: $stderr")
        }
        stdoutReader.join(2_000)
        check(!stdoutReader.isAlive) { "Daemon stdout reader remained live after process exit" }
        val residual = mutableListOf<OutputLine>()
        outputLines.drainTo(residual)
        check(residual.none { it.value != null }) { "Unexpected late or unconsumed daemon reply" }
        val exitCode = process.exitValue()
        val stderr = stderrText()
        Files.deleteIfExists(stderrFile)
        check(exitCode == 0) { "Source-built daemon exited $exitCode; stderr: $stderr" }
        println("NATIVE_JAVA_QUERY_SESSION_CLOSED cleanExit=true emptyReplies=true")
    }

    private fun stderrText(): String = Files.readString(stderrFile, Charsets.UTF_8).takeLast(4_096)

    private companion object {
        const val RESPONSE_TIMEOUT_SECONDS = 120L
        const val SHUTDOWN_TIMEOUT_SECONDS = 20L
    }
}

class JavaNativeSourceNavigationSteps {
    private val fixture = JavaNativeSourceNavigationFixture()
    @Given("a native Java source navigation fixture uses {string}")
    fun capture(condition: String) = fixture.capture(condition)
    @When("the native daemon resolves source metadata declaration and content under the acquired schema")
    fun resolve() = fixture.resolve()
    @Then("source navigation reports {string} with exact binary source and API authority")
    fun verify(outcome: String) = fixture.verify(outcome)
    @Then("source navigation preserves all captured project SDK and dependency inputs")
    fun intact() = fixture.verifyIntact()
    @After
    fun close() = fixture.close()
}

private class JavaNativeSourceNavigationFixture : AutoCloseable {
    private lateinit var root: Path
    private lateinit var project: Path
    private lateinit var native: NativeJavaDaemonRpcSession
    private lateinit var condition: String
    private lateinit var sdk: Path
    private lateinit var snapshot: String
    private lateinit var context: JsonObject
    private lateinit var request: JsonObject
    private lateinit var lookup: JsonObject
    private var declaration: JsonObject? = null
    private var read: JsonObject? = null
    private var historical: JsonObject? = null
    private lateinit var baseline: Map<String, String>
    private lateinit var sdkBaseline: Map<String, String>
    private var sourcePath = "src/main/java/demo/Runner.java"
    private var prefix = "java.platformSource"
    private val dependencySource = "package fixture; public class Library { public String message() { return java.util.List.of(\"fixture\").get(0); } }\n"

    fun capture(condition: String) {
        require(condition in setOf("SDK21 platform source8", "SDK25 platform source8", "SDK21 platform release8", "SDK21 mixed API profiles", "SDK21 dependency source8", "SDK25 dependency source8"))
        this.condition = condition
        sdk = Path.of(if (condition.startsWith("SDK25")) requireNotNull(System.getenv("REFACTORKIT_ENTRYPOINT_SDK25_HOME"))
            else System.getProperty("java.home")).toRealPath()
        root = Files.createTempDirectory("rk-sdk-source-profile-"); project = root.resolve("project"); Files.createDirectories(project)
        val sharedRoot = condition in setOf("SDK21 platform source8", "SDK21 dependency source8")
        if (sharedRoot) sourcePath = "Runner.java"
        val dependency = "dependency" in condition
        if (dependency) { prefix = "java.dependencySource"; prepareDependency() }
        val modern = condition == "SDK25 platform source8"
        val consumer = when {
            dependency -> "package demo; class Runner { String value = new fixture.Library().message(); }\n"
            modern -> "package demo; class Runner { java.util.List<String> values = java.util.List.of(\"api\"); }\n"
            else -> "package demo; class Runner { String value; }\n"
        }
        fun pom(id: String, release: Boolean) = "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>$id</artifactId><version>1</version><properties>" +
            (if (release) "<maven.compiler.release>8</maven.compiler.release>" else "<maven.compiler.source>8</maven.compiler.source><maven.compiler.target>8</maven.compiler.target>") +
            "</properties>" + (if (dependency) "<dependencies><dependency><groupId>fixture</groupId><artifactId>library</artifactId><version>1</version></dependency></dependencies>" else "") +
            "<build>" + (if (sharedRoot) "<sourceDirectory>.</sourceDirectory>" else "") + "<plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin></plugins></build></project>\n"
        fun write(path: String, text: String) { val file = project.resolve(path); Files.createDirectories(file.parent); Files.writeString(file, text) }
        if (condition == "SDK21 mixed API profiles") {
            write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging><properties><maven.compiler.release>8</maven.compiler.release></properties><modules><module>restricted</module><module>native-api</module></modules></project>\n")
            write("restricted/pom.xml", pom("restricted", true)); write("restricted/$sourcePath", consumer)
            write("native-api/pom.xml", pom("native-api", false)); sourcePath = "native-api/$sourcePath"; write(sourcePath, consumer)
        } else { write("pom.xml", pom("app", condition == "SDK21 platform release8")); write(sourcePath, consumer) }
        if (sharedRoot) {
            write("tsconfig.json", "{\"compilerOptions\":{\"allowJs\":true,\"rootDir\":\".\"},\"include\":[\"web/*.js\"]}\n")
            write("web/report.js", "export const report = 1;\n")
        }
        native = NativeJavaDaemonRpcSession(project)
        snapshot = native.dispatch("project.open", buildJsonObject {
            put("root", project.toString()); if (dependency) put("mavenRepository", root.resolve("repository").toString())
        }).getValue("snapshotHash").jsonPrimitive.content
        context = acquire()
        assertEquals("java-project-context-v2", context.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        request = selection(sourcePath, if (condition == "SDK21 platform release8") 8 else sdkRelease())
        baseline = inventory(root)
        sdkBaseline = sdkImages()
    }

    private fun sdkRelease() = if (condition.startsWith("SDK25")) 25 else 21
    private fun acquire() = native.dispatch("project.context", buildJsonObject {
        put("expectedSnapshotHash", snapshot); put("sdkHome", sdk.toString())
    })
    private fun selection(path: String, profile: Int): JsonObject {
        val text = Files.readString(project.resolve(path))
        val token = when { prefix == "java.dependencySource" -> "message()"; condition == "SDK25 platform source8" -> "of(\"api\")"; else -> "String value" }
        val index = text.indexOf(token); assertTrue(index >= 0)
        val point = org.refactorkit.core.TextEdits.rangeForOffset(text, index + 1, 0).start
        return buildJsonObject {
            put("expectedSnapshotHash", snapshot); put("expectedContextHash", context.getValue("contextHash")); put("sdkHome", sdk.toString())
            put("path", path); put("position", buildJsonObject { put("line", point.line); put("character", point.character) })
            if (prefix == "java.platformSource") {
                put("expectedPlatformIdentitySha256", context.getValue("sdk").jsonArray.map { it.jsonObject }
                    .single { it.getValue("sourceRelease").jsonPrimitive.int == profile }.getValue("identitySha256"))
                put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
            }
        }
    }
    fun resolve() {
        lookup = native.dispatch("$prefix.lookup", request)
        if (lookup.getValue("status").jsonPrimitive.content == "available") {
            val guarded = JsonObject(request + ("expectedAssociationHash" to lookup.getValue("association").jsonObject.getValue("associationHash")))
            declaration = native.dispatch("$prefix.declaration", guarded)
            read = native.dispatch("$prefix.read", guarded)
            if (condition == "SDK21 mixed API profiles") historical = native.dispatch("$prefix.lookup", selection("restricted/src/main/java/demo/Runner.java", 8))
        }
    }
    fun verify(outcome: String) {
        if (outcome == "historical source unavailable") { historical(lookup); return }
        assertEquals("available", lookup.getValue("status").jsonPrimitive.content, lookup.toString())
        val declared = requireNotNull(declaration); val contentReply = requireNotNull(read)
        assertEquals("available", declared.getValue("status").jsonPrimitive.content, declared.toString())
        assertEquals("available", contentReply.getValue("status").jsonPrimitive.content, contentReply.toString())
        val association = lookup.getValue("association").jsonObject
        assertEquals(association, declared.getValue("association")); assertEquals(association, contentReply.getValue("association"))
        assertEquals(true, association.getValue("readOnly").jsonPrimitive.boolean)
        val content = contentReply.getValue("content").jsonPrimitive.content
        assertEquals(sha(content.toByteArray()), association.getValue("sourceSha256").jsonPrimitive.content)
        val identity = association.getValue("jvmIdentity").jsonObject
        val name = when (outcome) { "exact modern platform method" -> "of"; "exact dependency method" -> "message"; else -> "String" }
        assertEquals(if (name == "String") "java.lang.String" else if (name == "of") "java.util.List" else "fixture.Library",
            identity.getValue("ownerBinaryName").jsonPrimitive.content)
        val range = declared.getValue("declaration").jsonObject.getValue("nameRange").jsonObject
        fun offset(key: String): Int { val point = range.getValue(key).jsonObject; return org.refactorkit.core.TextEdits.offsetOf(content,
            org.refactorkit.core.SourcePosition(point.getValue("line").jsonPrimitive.int, point.getValue("character").jsonPrimitive.int)) }
        assertEquals(name, content.substring(offset("start"), offset("end")))
        if (prefix == "java.platformSource") {
            assertEquals(sdkRelease(), association.getValue("effectiveRelease").jsonPrimitive.int)
            assertEquals(sdkRelease(), association.getValue("platformRelease").jsonPrimitive.int)
            assertEquals(request.getValue("expectedPlatformIdentitySha256"), association.getValue("platformIdentitySha256"))
            assertEquals("java.base", association.getValue("platformModule").jsonPrimitive.content)
            val binary = association.getValue("binaryOrigin").jsonObject
            assertEquals("CURRENT_JRT_IMAGE", binary.getValue("kind").jsonPrimitive.content)
            assertEquals(sha(sdk.resolve("lib/modules")), binary.getValue("moduleImageSha256").jsonPrimitive.content)
        } else { assertEquals(dependencySource, content); assertEquals("()Ljava/lang/String;", identity.getValue("descriptor").jsonPrimitive.content) }
        if (outcome == "isolated native and release APIs") historical(requireNotNull(historical))
        val refreshed = acquire()
        assertEquals(snapshot, refreshed.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals(context.getValue("contextHash"), refreshed.getValue("contextHash"))
        println("JAVA_NATIVE_SOURCE_PROFILE " + buildJsonObject { put("condition", condition); put("sourceLevel", 8); put("apiProfile", sdkRelease()); put("schema", "java-project-context-v2") })
    }
    private fun historical(reply: JsonObject) {
        assertEquals("unavailable", reply.getValue("status").jsonPrimitive.content, reply.toString())
        assertEquals("java.platformSources.releaseSourceUnqualified", reply.getValue("reason").jsonObject.getValue("code").jsonPrimitive.content)
        assertFalse("association" in reply); assertFalse("declaration" in reply); assertFalse("content" in reply)
    }
    private fun prepareDependency() {
        val work = root.resolve("fixture-build"); Files.createDirectories(work)
        val source = work.resolve("Library.java"); Files.writeString(source, dependencySource)
        val classes = work.resolve("classes"); val log = work.resolve("compiler.log")
        val process = ProcessBuilder(sdk.resolve("bin/javac").toString(), "-proc:none", "-source", "8", "-target", "8", "-d", classes.toString(), source.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); error("Fixture compiler did not terminate") }
        assertEquals(0, process.exitValue(), Files.readString(log))
        val artifact = root.resolve("repository/fixture/library/1"); Files.createDirectories(artifact)
        Files.writeString(artifact.resolve("library-1.pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>library</artifactId><version>1</version></project>\n")
        fun jar(file: Path, entry: String, bytes: ByteArray) {
            java.util.jar.JarOutputStream(Files.newOutputStream(file)).use { output ->
                output.putNextEntry(java.util.jar.JarEntry(entry).apply { time = 0 }); output.write(bytes); output.closeEntry()
            }
        }
        jar(artifact.resolve("library-1.jar"), "fixture/Library.class", Files.readAllBytes(classes.resolve("fixture/Library.class")))
        jar(artifact.resolve("library-1-sources.jar"), "fixture/Library.java", dependencySource.toByteArray())
    }
    private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(path: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun inventory(path: Path) = Files.walk(path).use { files -> files.filter(Files::isRegularFile).toList().associate { path.relativize(it).toString() to sha(it) } }
    private fun sdkImages() = listOf("release", "lib/ct.sym", "lib/modules", "lib/jrt-fs.jar", "lib/src.zip").associateWith { sha(sdk.resolve(it)) }
    fun verifyIntact() { assertEquals(baseline, inventory(root)); assertEquals(sdkBaseline, sdkImages()); assertFalse(Files.exists(project.resolve(".refactorkit/transactions"))) }
    override fun close() { if (::native.isInitialized) native.close(); if (::root.isInitialized) root.toFile().deleteRecursively() }
}

class JavaEditorCompilerDiagnosticsSteps {
    private val fixture = JavaEditorCompilerDiagnosticsFixture()
    @Given("a native Java editor compiler diagnostics fixture uses {string}")
    fun capture(condition: String) = fixture.capture(condition)
    @When("the daemon diagnoses the immutable Java drafts with their acquired SDK context")
    fun diagnose() = fixture.diagnose()
    @Then("Java editor diagnostics report {string} with exact frame and compiler authority")
    fun verify(outcome: String) = fixture.verify(outcome)
    @Then("Java editor diagnostics preserve captured project and SDK bytes without execution")
    fun intact() = fixture.verifyIntact()
    @After fun close() = fixture.close()
}

private class JavaEditorCompilerDiagnosticsFixture : AutoCloseable {
    private lateinit var root: Path
    private lateinit var native: NativeJavaDaemonRpcSession
    private lateinit var condition: String
    private lateinit var sdk: Path
    private lateinit var snapshot: String
    private lateinit var context: JsonObject
    private lateinit var params: JsonObject
    private lateinit var response: JsonObject
    private lateinit var capabilities: JsonObject
    private lateinit var baseline: Map<String, String>
    private lateinit var sdkBaseline: Map<String, String>
    private var first: JsonObject? = null
    private var cancelled: JsonObject? = null
    private var afterCancel: JsonObject? = null
    private var path = "src/main/java/demo/Runner.java"
    private var companionPath = "src/main/java/demo/Helper.java"
    private var draft = "package demo; /* 💡 draft */ class Runner { String value = java.util.List.of(Helper.newMessage()).get(0); }\n"
    private var companionDraft = "package demo; class Helper { static String newMessage() { return \"draft\"; } }\n"
    private val languageRelease: String get() = if (condition.startsWith("SDK26")) "26" else "25"
    private fun nativeProfile(): Boolean = condition.startsWith("SDK25 compact") || condition.startsWith("SDK25 native") ||
        condition.startsWith("SDK26 compact") || condition.startsWith("SDK26 native") || condition in setOf("SDK25 explicit record illegal", "SDK26 explicit record illegal")

    fun capture(condition: String) {
        require(condition in setOf("SDK25 release8 drafts", "SDK21 source8 drafts", "SDK25 source8 drafts", "obsolete draft version", "substituted SDK context", "queued cancellation",
            "SDK25 compact record clean", "SDK25 compact var clean", "SDK25 compact yield clean",
            "SDK25 compact record missing value", "SDK25 explicit record illegal", "SDK25 compact underscore illegal",
            "SDK25 native source-target25", "SDK25 native main-test isolation", "SDK25 native independent modules",
            "SDK25 native obsolete draft version", "SDK25 native stale context", "SDK25 native queued cancellation",
            "SDK26 compact record clean", "SDK26 native source-target26", "SDK26 compact record missing value",
            "SDK26 explicit record illegal", "SDK26 native removed platform API"))
        this.condition = condition
        sdk = Path.of(when {
            condition.startsWith("SDK26") -> optionalNativeJavaSdkHome("REFACTORKIT_JDK26_HOME").toString()
            condition.startsWith("SDK25") -> requireNotNull(System.getenv("REFACTORKIT_ENTRYPOINT_SDK25_HOME"))
            else -> System.getProperty("java.home")
        }).toRealPath()
        root = Files.createTempDirectory("rk-java-editor-compiler-")
        fun write(relative: String, text: String) { val file = root.resolve(relative); Files.createDirectories(file.parent); Files.writeString(file, text) }
        val native25 = nativeProfile()
        if (native25) {
            val name = when {
                condition.contains("underscore") -> "_"
                condition.contains(" var ") -> "var"
                condition.contains(" yield ") -> "yield"
                else -> "record"
            }
            path = "src/main/java/$name.java"
            draft = "/* 💡 closed */ void main(){int compactWarning=1;int value=demo.Helper.newMessage();System.out.println(value);}" + "\n"
            if (condition.contains("missing value")) draft = draft.replace("println(value)", "println(missingCompactValue)")
            if (condition.contains("explicit")) draft = "class record { int value=demo.Helper.newMessage(); }\n"
            if (condition.contains("removed platform API")) draft = "/* 💡 selected SDK */ void main(){ java.applet.Applet value = null; }\n"
            companionDraft = "package demo; public class Helper { public static int newMessage(){return 25;} }\n"
        }
        val properties = if (condition.contains("native source-target")) "<maven.compiler.source>$languageRelease</maven.compiler.source><maven.compiler.target>$languageRelease</maven.compiler.target>" else if (native25) "<maven.compiler.release>$languageRelease</maven.compiler.release>" else if (condition == "SDK25 release8 drafts") "<maven.compiler.release>8</maven.compiler.release>" else
            "<maven.compiler.source>8</maven.compiler.source><maven.compiler.target>8</maven.compiler.target>"
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>app</artifactId><version>1</version><properties>$properties</properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin></plugins></build></project>\n")
        if (condition == "SDK25 native main-test isolation") {
            write("src/test/java/demo/Helper.java", "package demo; public class Helper { public static String isolatedTest(){return \"test\";} }\n")
        }
        if (condition == "SDK25 native independent modules") {
            path = "one/$path"; companionPath = "one/$companionPath"
            write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>app</artifactId><version>1</version><packaging>pom</packaging><properties>$properties</properties><modules><module>one</module><module>two</module></modules><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin></plugins></build></project>\n")
            for (module in listOf("one", "two")) write("$module/pom.xml", "<project><modelVersion>4.0.0</modelVersion><parent><groupId>demo</groupId><artifactId>app</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent><artifactId>$module</artifactId></project>\n")
            write("two/src/main/java/demo/Helper.java", "package demo; public class Helper { public static int isolatedModule(){return 2;} }\n")
        }
        write(path, if (native25) "void main() { System.out.println(0); }\n" else "package demo; class Runner { String value = Helper.oldMessage(); }\n")
        write(companionPath, "package demo; class Helper { static String oldMessage() { return \"saved\"; } }\n")
        if (native25) write(companionPath, "package demo; public class Helper { public static int oldMessage(){return 0;} }\n")
        native = NativeJavaDaemonRpcSession(root)
        snapshot = native.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).getValue("snapshotHash").jsonPrimitive.content
        context = acquireContext()
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        assertEquals(if (languageRelease == "26") "java-project-context-v7" else "java-project-context-v2", context.getValue("schemaVersion").jsonPrimitive.content)
        val generation = native.dispatch("index.status", buildJsonObject {}).getValue("generation")
        params = buildJsonObject {
            put("requestId", "editor-compiler-story"); put("kind", "diagnostics"); put("languageId", "java"); put("priority", "interactive")
            put("expectedSnapshotHash", snapshot); put("expectedIndexGeneration", generation); put("path", path); put("limit", 500)
            put("javaContext", buildJsonObject { put("sdkHome", sdk.toString()); put("expectedContextHash", context.getValue("contextHash")); put("contextSchemaVersion", context.getValue("schemaVersion")) })
            put("sourceAuthority", frame(7))
        }
        capabilities = native.dispatch("server.capabilities", buildJsonObject {})
        baseline = inventory(root)
        sdkBaseline = sdkImages()
    }
    private fun acquireContext(): JsonObject {
        val scope = if (languageRelease == "26") {
            val summary = native.dispatch("project.summary", buildJsonObject {})
            summary.getValue("buildModels").jsonArray.map { it.jsonObject }
                .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
                .getValue("modules").jsonArray.single().jsonObject.getValue("id")
        } else null
        return native.dispatch(if (scope == null) "project.context" else "project.context.v7", buildJsonObject {
            put("expectedSnapshotHash", snapshot); put("sdkHome", sdk.toString())
            if (scope != null) { put("providerId", "maven-effective-v1"); put("moduleId", scope); put("sourceSetId", "main") }
        })
    }
    private fun frame(version: Long) = buildJsonObject {
        put("kind", "immutable-editor-overlay")
        put("documents", buildJsonArray {
            listOf(path to draft, companionPath to companionDraft).forEach { (name, text) -> add(buildJsonObject {
                put("path", name); put("version", version); put("content", text)
            }) }
        })
    }
    fun diagnose() {
        when (condition) {
            "obsolete draft version", "SDK25 native obsolete draft version" -> {
                first = native.dispatch("intelligence.query", params)
                response = native.dispatch("intelligence.query", JsonObject(params + ("sourceAuthority" to frame(6))))
            }
            "SDK25 native stale context" -> {
                val selected = params.getValue("javaContext").jsonObject
                response = native.dispatch("intelligence.query", JsonObject(params + ("javaContext" to JsonObject(selected + ("expectedContextHash" to JsonPrimitive("0".repeat(64)))))))
                first = native.dispatch("intelligence.query", params)
            }
            "substituted SDK context" -> {
                val selected = params.getValue("javaContext").jsonObject
                val other = Path.of(requireNotNull(System.getenv("REFACTORKIT_ENTRYPOINT_SDK25_HOME"))).toRealPath()
                response = native.dispatch("intelligence.query", JsonObject(params + ("javaContext" to JsonObject(selected + ("sdkHome" to JsonPrimitive(other.toString()))))))
                first = native.dispatch("intelligence.query", params)
            }
            "queued cancellation", "SDK25 native queued cancellation" -> {
                val module = context.getValue("modules").jsonArray.first().jsonObject.getValue("id")
                val blocker = buildJsonObject { put("expectedSnapshotHash", snapshot); put("sdkHome", sdk.toString()); put("providerId", "maven-effective-v1"); put("moduleId", module); put("sourceSetId", "main") }
                val (ids, replies) = native.batch(listOf("project.context.v7" to blocker, "intelligence.query" to params,
                    "intelligence.cancel" to buildJsonObject { put("requestId", "editor-compiler-story") }))
                val query = replies.single { it.message["id"] == ids[1] }
                val blocked = replies.single { it.message["id"] == ids[0] }
                assertEquals("available", blocked.message.getValue("result").jsonObject.getValue("status").jsonPrimitive.content)
                assertTrue(replies.indexOf(query) < replies.indexOf(blocked), "Cancellation must finish while the preceding control still owns the query queue")
                response = query.message
                cancelled = replies.single { it.message["id"] == ids[2] }.message.getValue("result").jsonObject
                afterCancel = native.dispatch("intelligence.cancel", buildJsonObject { put("requestId", "editor-compiler-story") })
                first = native.dispatch("intelligence.query", params)
            }
            else -> response = native.dispatch("intelligence.query", params)
        }
    }
    fun verify(outcome: String) {
        if (outcome == "intelligence.cancelled") {
            assertEquals(org.refactorkit.core.JsonRpcErrorCodes.REQUEST_CANCELLED, response.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
            assertTrue(response["result"] == null || response["result"] == JsonNull)
            assertEquals("cancelled", requireNotNull(cancelled).getValue("status").jsonPrimitive.content)
            assertEquals("not-found", requireNotNull(afterCancel).getValue("status").jsonPrimitive.content)
            ready(requireNotNull(first), false)
        } else if (outcome.startsWith("java.")) {
            assertEquals("refused", response.getValue("status").jsonPrimitive.content, response.toString())
            assertEquals(outcome, response.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertFalse("items" in response); assertFalse("complete" in response)
            ready(requireNotNull(first), false)
        } else {
            ready(response, outcome == "restricted draft API error")
            if (nativeProfile()) nativeOracle(outcome)
        }
        if (outcome != "intelligence.cancelled") {
            assertEquals("editor-compiler-story", response.getValue("requestId").jsonPrimitive.content)
            assertEquals("diagnostics", response.getValue("kind").jsonPrimitive.content)
            assertEquals(snapshot, response.getValue("snapshotHash").jsonPrimitive.content)
        }
        val flags = capabilities.getValue("methods").jsonArray.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "intelligence.query" }.getValue("features").jsonObject
        assertEquals(true, flags.getValue("javaImmutableEditorOverlayDiagnostics").jsonPrimitive.boolean)
        val refreshed = acquireContext()
        assertEquals(context.getValue("contextHash"), refreshed.getValue("contextHash"))
        println("JAVA_NATIVE_EDITOR_DIAGNOSTICS condition=$condition sdk=$sdk outcome=$outcome")
    }
    private fun ready(reply: JsonObject, restricted: Boolean) {
        assertEquals("ready", reply.getValue("status").jsonPrimitive.content, reply.toString())
        assertEquals(true, reply.getValue("complete").jsonPrimitive.boolean)
        assertEquals("compiler-errors", reply.getValue("coverage").jsonPrimitive.content)
        val native25 = nativeProfile()
        assertEquals(if (native25) "java-sdk-compiler-diagnostics-v1" else "java-jdt-bindings-v1", reply.getValue("providerId").jsonPrimitive.content)
        if (native25) assertEquals("jdk$languageRelease-javac-compiler-errors-v1", reply.getValue("backend").jsonPrimitive.content)
        assertEquals("compiler", reply.getValue("evidence").jsonPrimitive.content)
        assertEquals(context.getValue("contextHash"), reply.getValue("contextHash"))
        assertEquals(context.getValue("schemaVersion"), reply.getValue("contextSchemaVersion"))
        assertEquals(context.getValue("sdk").jsonArray.map { it.jsonObject.getValue("identitySha256") }, reply.getValue("platformIdentities").jsonArray.toList())
        assertNotEquals(snapshot, reply.getValue("providerSnapshotHash").jsonPrimitive.content)
        assertTrue(reply.getValue("provenanceHash").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        assertEquals(if (condition in setOf("SDK25 native main-test isolation", "SDK25 native independent modules")) 3 else 2, reply.getValue("analyzedSourceCount").jsonPrimitive.int)
        val authority = reply.getValue("sourceAuthority").jsonObject
        assertEquals(snapshot, authority.getValue("baseSnapshotHash").jsonPrimitive.content)
        assertTrue(authority.getValue("overlayHash").jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")))
        val documents = authority.getValue("documents").jsonArray.map { it.jsonObject }.associateBy { it.getValue("path").jsonPrimitive.content }
        assertEquals(setOf(path, companionPath), documents.keys)
        for ((name, text) in listOf(path to draft, companionPath to companionDraft)) {
            val document = documents.getValue(name); assertEquals(7L, document.getValue("version").jsonPrimitive.long)
            assertEquals(sha(text.toByteArray()), document.getValue("contentSha256").jsonPrimitive.content)
        }
        val items = reply.getValue("items").jsonArray.map { it.jsonObject }
        val nativeError = native25 && (condition.contains("illegal") || condition.contains("missing value") || condition.contains("removed platform API"))
        assertEquals(if (restricted || nativeError) 1 else 0, items.size, reply.toString())
        if (nativeError) {
            val error = items.single()
            assertTrue(error.getValue("code").jsonPrimitive.content.startsWith("java.javac.compiler.err."), error.toString())
            assertEquals("error", error.getValue("severity").jsonPrimitive.content)
            val location = error.getValue("location").jsonObject
            assertEquals(path, location.getValue("path").jsonPrimitive.content)
            val range = location.getValue("range").jsonObject
            fun offset(key: String): Int { val point=range.getValue(key).jsonObject; return org.refactorkit.core.TextEdits.offsetOf(draft, org.refactorkit.core.SourcePosition(point.getValue("line").jsonPrimitive.int, point.getValue("character").jsonPrimitive.int)) }
            val selected = draft.substring(offset("start"), offset("end"))
            if (condition.contains("missing value")) assertEquals("missingCompactValue", selected)
            else if (condition.contains("removed platform API")) assertTrue(selected.contains("java.applet"), selected)
            else if (condition.contains("underscore")) assertEquals("java.javac.compiler.err.bad.file.name", error.getValue("code").jsonPrimitive.content)
            else assertTrue(error.getValue("code").jsonPrimitive.content.contains("restricted"), error.toString())
        }
        assertEquals(items.size, reply.getValue("total").jsonPrimitive.int)
        if (native25) println("JAVA25_EDITOR_DIAGNOSTICS_RESPONSE=" + reply.toString())
        if (restricted) {
            val error = items.single(); assertEquals("java.jdt.typeResolution", error.getValue("code").jsonPrimitive.content)
            assertEquals("error", error.getValue("severity").jsonPrimitive.content)
            val location = error.getValue("location").jsonObject; assertEquals(path, location.getValue("path").jsonPrimitive.content)
            val range = location.getValue("range").jsonObject
            fun offset(key: String): Int { val point = range.getValue(key).jsonObject; return org.refactorkit.core.TextEdits.offsetOf(draft, org.refactorkit.core.SourcePosition(point.getValue("line").jsonPrimitive.int, point.getValue("character").jsonPrimitive.int)) }
            assertTrue(draft.substring(offset("start"), offset("end")).contains("of"))
            assertTrue(error.getValue("message").jsonPrimitive.content.contains("of")); assertFalse(error.getValue("message").jsonPrimitive.content.contains("newMessage"))
        }
    }
    private fun nativeOracle(outcome: String) {
        val oracle = Files.createTempDirectory("rk-sdk25-diagnostic-oracle-")
        try {
            for ((name, text) in listOf(path to draft, companionPath to companionDraft)) {
                val source=oracle.resolve(name); Files.createDirectories(source.parent); Files.writeString(source,text)
            }
            val output=oracle.resolve("classes"); Files.createDirectories(output)
            val log=oracle.resolve("javac.log")
            val languageFlags = if (condition.contains("native source-target")) listOf("-source", languageRelease, "-target", languageRelease) else listOf("--release", languageRelease)
            val process=ProcessBuilder(listOf(sdk.resolve("bin/javac").toString(), "-proc:none", "-XDrawDiagnostics") + languageFlags +
                listOf("-encoding", "UTF-8", "-d", output.toString(), oracle.resolve(path).toString(), oracle.resolve(companionPath).toString()))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly(); process.waitFor(); error("SDK25 oracle timed out")
            }
            val diagnostics = Files.readString(log)
            assertEquals(outcome != "clean native diagnostics", process.exitValue() != 0, diagnostics)
            for (item in response.getValue("items").jsonArray) {
                assertTrue(diagnostics.contains(item.jsonObject.getValue("code").jsonPrimitive.content.removePrefix("java.javac.")), diagnostics)
            }
            val repeated = native.dispatch("intelligence.query", params)
            assertEquals(response, repeated, "Repeated acquisition must retain the same semantic provenance")
            println("JAVA25_EDITOR_DIAGNOSTICS_RESPONSE=" + response.toString())
        } finally { oracle.toFile().deleteRecursively() }
    }
    private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(file: Path): String { val digest = java.security.MessageDigest.getInstance("SHA-256"); Files.newInputStream(file).use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }; return digest.digest().joinToString("") { "%02x".format(it) } }
    private fun inventory(folder: Path) = Files.walk(folder).use { files -> files.filter(Files::isRegularFile).toList().associate { folder.relativize(it).toString() to sha(it) } }
    private fun sdkImages() = listOf("release", "bin/java", "bin/javac", "lib/ct.sym", "lib/modules", "lib/jrt-fs.jar", "lib/src.zip").associateWith { sha(sdk.resolve(it)) }
    fun verifyIntact() { assertEquals(baseline, inventory(root)); assertEquals(sdkBaseline, sdkImages()); assertFalse(Files.exists(root.resolve(".refactorkit/transactions"))) }
    override fun close() { if (::native.isInitialized) native.close(); if (::root.isInitialized) root.toFile().deleteRecursively() }
}

class JavaNativeTypeCollisionDiagnosticsSteps {
    private val fixture = JavaNativeTypeCollisionDiagnosticsFixture()
    @Given("a native Java type collision fixture uses {string}")
    fun capture(condition: String) = fixture.capture(condition)
    @When("the native saved and editor diagnostic routes evaluate the captured Java type declarations")
    fun diagnose() = fixture.diagnose()
    @Then("Java type diagnostics report {string} without merging compilation scopes")
    fun verify(outcome: String) = fixture.verify(outcome)
    @Then("Java type collision checks preserve all captured source build and SDK inputs")
    fun intact() = fixture.verifyIntact()
    @After fun close() = fixture.close()
}

private class JavaNativeTypeCollisionDiagnosticsFixture : AutoCloseable {
    private lateinit var root: Path
    private lateinit var native: NativeJavaDaemonRpcSession
    private lateinit var sdk: Path
    private lateinit var condition: String
    private lateinit var snapshot: String
    private lateinit var context: JsonObject
    private lateinit var editor: JsonObject
    private lateinit var savedBefore: JsonArray
    private lateinit var savedAfter: JsonArray
    private lateinit var baseline: Map<String, String>
    private lateinit var sdkBaseline: Map<String, String>
    private lateinit var mainPath: String
    private lateinit var otherPath: String
    private lateinit var frame: Map<String, String>
    private val duplicate = "package demo; class Duplicate {}\n"

    fun capture(condition: String) {
        require(condition in setOf("source plus saved declaration", "two draft declarations", "main and test binary names", "independent module names", "distinct enclosing binaries", "flat Java and JavaScript"))
        this.condition = condition; sdk = Path.of(System.getProperty("java.home")).toRealPath()
        root = Files.createTempDirectory("rk-native-type-collision-")
        val flat = condition == "flat Java and JavaScript"
        mainPath = if (flat) "Runner.java" else "src/main/java/demo/Runner.java"
        otherPath = if (flat) "Helper.java" else "src/main/java/demo/Helper.java"
        var main = duplicate; var other = duplicate
        val properties = "<properties><maven.compiler.source>8</maven.compiler.source><maven.compiler.target>8</maven.compiler.target></properties>"
        val plugin = "<plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin></plugins>"
        val header = "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>app</artifactId><version>1</version>"
        fun write(path: String, content: String) { val file = root.resolve(path); Files.createDirectories(file.parent); Files.writeString(file, content) }
        if (condition == "independent module names") {
            mainPath = "one/src/main/java/demo/Duplicate.java"; otherPath = "two/src/main/java/demo/Duplicate.java"
            write("pom.xml", "$header<packaging>pom</packaging>$properties<modules><module>one</module><module>two</module></modules><build>$plugin</build></project>\n")
            for (module in listOf("one", "two")) write("$module/pom.xml", "<project><modelVersion>4.0.0</modelVersion><parent><groupId>demo</groupId><artifactId>app</artifactId><version>1</version><relativePath>../pom.xml</relativePath></parent><artifactId>$module</artifactId></project>\n")
        } else write("pom.xml", "$header$properties<build>" + (if (flat) "<sourceDirectory>.</sourceDirectory>" else "") + "$plugin</build></project>\n")
        when (condition) {
            "two draft declarations" -> { main = "package demo; class Runner {}\n"; other = "package demo; class Helper {}\n" }
            "main and test binary names" -> { mainPath = "src/main/java/demo/Duplicate.java"; otherPath = "src/test/java/demo/Duplicate.java" }
            "distinct enclosing binaries" -> { mainPath = "src/main/java/demo/A.java"; otherPath = "src/main/java/demo/B.java"; main = "package demo; class A { static class Nested {} }\n"; other = "package demo; class B { static class Nested {} }\n" }
        }
        write(mainPath, main); write(otherPath, other)
        if (flat) { write("tsconfig.json", "{\"compilerOptions\":{\"allowJs\":true,\"rootDir\":\".\"},\"include\":[\"web/*.js\"]}\n"); write("web/report.js", "export const report = 1;\n") }
        fun draft(text: String) = text.replaceFirst("class ", "/* 💡 captured */ class ")
        frame = if (condition == "two draft declarations") linkedMapOf(mainPath to draft(duplicate), otherPath to duplicate)
            else linkedMapOf(mainPath to draft(main))
        native = NativeJavaDaemonRpcSession(root)
        snapshot = native.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).getValue("snapshotHash").jsonPrimitive.content
        context = native.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", snapshot); put("sdkHome", sdk.toString()) })
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        baseline = inventory(root); sdkBaseline = sdkImages()
    }
    private fun saved(id: Int): JsonArray {
        val reply = native.raw("diagnostics", buildJsonObject { put("languageId", "java"); put("jdkHome", sdk.toString()) }, JsonPrimitive(id))
        assertTrue(reply["error"] == null || reply["error"] == JsonNull, reply.toString())
        return reply.getValue("result").jsonArray
    }
    fun diagnose() {
        savedBefore = saved(9910)
        editor = native.dispatch("intelligence.query", buildJsonObject {
            put("requestId", "native-type-collision"); put("kind", "diagnostics"); put("languageId", "java"); put("path", mainPath)
            put("expectedSnapshotHash", snapshot); put("limit", 500)
            put("javaContext", buildJsonObject { put("sdkHome", sdk.toString()); put("expectedContextHash", context.getValue("contextHash")); put("contextSchemaVersion", context.getValue("schemaVersion")) })
            put("sourceAuthority", buildJsonObject { put("kind", "immutable-editor-overlay"); put("documents", buildJsonArray {
                frame.forEach { (path, text) -> add(buildJsonObject { put("path", path); put("version", 11); put("content", text) }) }
            }) })
        })
        savedAfter = saved(9911)
    }
    fun verify(outcome: String) {
        val collision = outcome == "two exact type errors"
        val savedCount = if (collision && condition != "two draft declarations") 2 else 0
        assertEquals(savedBefore, savedAfter, "Editor diagnostics must not change saved-file diagnostics")
        val errors = savedBefore.map { it.jsonObject }.filter { it.getValue("severity").jsonPrimitive.content == "ERROR" }
        assertEquals(savedCount, errors.size, savedBefore.toString())
        if (savedCount > 0) {
            assertEquals(setOf(mainPath, otherPath), errors.map { it.getValue("file").jsonPrimitive.content }.toSet())
            errors.forEach { assertEquals("java.jdt.typeResolution", it.getValue("code").jsonPrimitive.content); assertEquals("COMPILER", it.getValue("evidence").jsonPrimitive.content) }
        }
        assertEquals("ready", editor.getValue("status").jsonPrimitive.content, editor.toString())
        assertEquals("native-type-collision", editor.getValue("requestId").jsonPrimitive.content)
        assertEquals(true, editor.getValue("complete").jsonPrimitive.boolean)
        assertEquals(snapshot, editor.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals(context.getValue("contextHash"), editor.getValue("contextHash"))
        assertEquals(context.getValue("schemaVersion"), editor.getValue("contextSchemaVersion"))
        assertEquals(2, editor.getValue("analyzedSourceCount").jsonPrimitive.int)
        val documents = editor.getValue("sourceAuthority").jsonObject.getValue("documents").jsonArray.map { it.jsonObject }.associateBy { it.getValue("path").jsonPrimitive.content }
        assertEquals(frame.keys, documents.keys)
        frame.forEach { (path, text) -> assertEquals(11, documents.getValue(path).getValue("version").jsonPrimitive.int); assertEquals(sha(text.toByteArray()), documents.getValue(path).getValue("contentSha256").jsonPrimitive.content) }
        val items = editor.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(if (collision) 2 else 0, items.size, editor.toString())
        assertEquals(items.size, editor.getValue("total").jsonPrimitive.int)
        if (collision) {
            assertEquals(setOf(mainPath, otherPath), items.map { it.getValue("location").jsonObject.getValue("path").jsonPrimitive.content }.toSet())
            for (item in items) {
                assertEquals("java.jdt.typeResolution", item.getValue("code").jsonPrimitive.content)
                assertEquals("error", item.getValue("severity").jsonPrimitive.content)
                assertTrue(item.getValue("message").jsonPrimitive.content.contains("demo.Duplicate"))
                val location = item.getValue("location").jsonObject; val path = location.getValue("path").jsonPrimitive.content
                val text = frame[path] ?: Files.readString(root.resolve(path)); val range = location.getValue("range").jsonObject
                fun offset(key: String): Int { val point = range.getValue(key).jsonObject; return org.refactorkit.core.TextEdits.offsetOf(text, org.refactorkit.core.SourcePosition(point.getValue("line").jsonPrimitive.int, point.getValue("character").jsonPrimitive.int)) }
                assertEquals("Duplicate", text.substring(offset("start"), offset("end")))
            }
        }
        println("JAVA_NATIVE_TYPE_COLLISION condition=$condition savedErrors=$savedCount editorErrors=${items.size} capturedDrafts=${frame.size} analyzedSources=2")
    }
    private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(file: Path): String { val digest = java.security.MessageDigest.getInstance("SHA-256"); Files.newInputStream(file).use { input -> val bytes = ByteArray(65536); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }; return digest.digest().joinToString("") { "%02x".format(it) } }
    private fun inventory(path: Path) = Files.walk(path).use { files -> files.filter(Files::isRegularFile).toList().associate { path.relativize(it).toString() to sha(it) } }
    private fun sdkImages() = listOf("release", "lib/ct.sym", "lib/modules", "lib/jrt-fs.jar", "lib/src.zip").associateWith { sha(sdk.resolve(it)) }
    fun verifyIntact() { assertEquals(baseline, inventory(root)); assertEquals(sdkBaseline, sdkImages()); assertFalse(Files.exists(root.resolve(".refactorkit/transactions"))) }
    override fun close() { if (::native.isInitialized) native.close(); if (::root.isInitialized) root.toFile().deleteRecursively() }
}

class JavaCompilerDiagnosticsSteps {
    private val fixture = JavaCompilerDiagnosticsFixture()
    @Given("a Java compiler API diagnostics fixture uses {string}")
    fun capture(condition: String) = fixture.capture(condition)
    @When("the native daemon and CLI evaluate Java diagnostics with the selected SDK")
    fun evaluate() = fixture.evaluate()
    @Then("both Java diagnostics routes report {string} for the compiler API boundary")
    fun verify(outcome: String) = fixture.verify(outcome)
    @Then("Java diagnostics preserve source build and report inputs without execution")
    fun intact() = fixture.verifyIntact()
    @After
    fun close() = fixture.close()
}

private class JavaCompilerDiagnosticsFixture : AutoCloseable {
    private lateinit var root: Path
    private lateinit var native: NativeJavaDaemonRpcSession
    private lateinit var sdk: Path
    private lateinit var condition: String
    private lateinit var images: Map<String, String>
    private lateinit var diagnostics: JsonArray
    private lateinit var cli: Pair<Int, String>
    private var nativeModule: JsonArray? = null
    private var restrictedModule: JsonArray? = null
    private var nativeCli: Pair<Int, String>? = null
    private var restrictedCli: Pair<Int, String>? = null

    fun capture(condition: String) {
        require(condition in setOf("SDK21 source-target8", "SDK25 source-target8", "SDK21 release8", "SDK21 mixed modes", "embedded SDK fallback", "custom system"))
        this.condition = condition
        sdk = Path.of(if (condition.startsWith("SDK25")) requireNotNull(System.getenv("REFACTORKIT_ENTRYPOINT_SDK25_HOME"))
            else System.getProperty("java.home")).toRealPath()
        root = Files.createTempDirectory("rk-native-java-diagnostics-")
        val source = "package demo; public class Runner { public static void main(String[] args) { java.util.List.of(\"api\"); throw new AssertionError(\"must not execute\"); } }\n"
        fun pom(id: String, release: Boolean): String {
            val properties = if (release) "<maven.compiler.release>8</maven.compiler.release>"
                else "<maven.compiler.source>8</maven.compiler.source><maven.compiler.target>8</maven.compiler.target>"
            val config = if (condition == "custom system") "<configuration><compilerArgs><arg>--system</arg><arg>none</arg></compilerArgs></configuration>" else ""
            return "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>$id</artifactId><version>1</version><properties>$properties</properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version>$config</plugin></plugins></build></project>\n"
        }
        images = if (condition == "SDK21 mixed modes") linkedMapOf(
            "pom.xml" to "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging><properties><maven.compiler.release>8</maven.compiler.release></properties><modules><module>restricted</module><module>native-api</module></modules></project>\n",
            "restricted/pom.xml" to pom("restricted", true), "restricted/src/main/java/demo/Runner.java" to source,
            "native-api/pom.xml" to pom("native-api", false), "native-api/src/main/java/demo/Runner.java" to source,
        ) else linkedMapOf("pom.xml" to pom("app", condition == "SDK21 release8"), "src/main/java/demo/Runner.java" to source)
        images = images + (".ide/java/test-results/previous/report.js" to "export const previous = true;\n")
        if (condition == "SDK21 mixed modes") images = images + mapOf(
            "native-api/tsconfig.json" to "{\"compilerOptions\":{\"allowJs\":true},\"include\":[\"report.js\"]}\n",
            "native-api/report.js" to "export const report = 1;\n",
        )
        images.forEach { (path, text) -> root.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, text) } }
        native = NativeJavaDaemonRpcSession(root, sdk)
        native.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
    }

    private val diagnosticsIds = AtomicLong(10000)
    private fun evaluateDaemon(module: String? = null): JsonArray {
        val envelope = native.raw("diagnostics", buildJsonObject {
        put("languageId", "java")
        if (condition != "embedded SDK fallback") put("jdkHome", sdk.toString())
        module?.let { put("module", it) }
        }, JsonPrimitive(diagnosticsIds.incrementAndGet()))
        assertTrue(envelope["error"] == null || envelope["error"] == JsonNull, envelope.toString())
        return envelope.getValue("result").jsonArray
    }

    private fun evaluateCli(module: String? = null): Pair<Int, String> {
        val candidate = System.getenv("REFACTORKIT_ENTRYPOINT_CANDIDATE_ROOT")?.takeIf(String::isNotBlank)?.let(Path::of)
        val command = if (candidate == null) listOf(Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-Drefactorkit.java.platform.home=$sdk", "-cp", requireNotNull(System.getProperty("refactorkit.lsp.test.classpath")),
            "org.refactorkit.cli.RefactorKitCliKt") else listOf(candidate.resolve("bin/refactorkit").toString())
        val args = buildList {
            addAll(command); add("diagnostics"); add(root.toString())
            if (condition != "embedded SDK fallback") addAll(listOf("--jdk-home", sdk.toString()))
            module?.let { addAll(listOf("--module", it)) }
        }
        val output = Files.createTempFile("rk-native-cli-diagnostics-", ".log")
        return try {
            val process = ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); error("CLI diagnostics did not terminate")
            }
            process.exitValue() to Files.readString(output)
        } finally { Files.deleteIfExists(output) }
    }

    fun evaluate() {
        diagnostics = evaluateDaemon(); cli = evaluateCli()
        if (condition == "SDK21 mixed modes") {
            nativeModule = evaluateDaemon("native-api"); restrictedModule = evaluateDaemon("restricted")
            nativeCli = evaluateCli("native-api"); restrictedCli = evaluateCli("restricted")
        }
    }

    fun verify(outcome: String) {
        when (outcome) {
            "clean compiler diagnostics" -> { assertTrue(diagnostics.isEmpty(), diagnostics.toString()); cleanCli(cli) }
            "restricted API error", "isolated module API error" -> {
                restricted(diagnostics); errorCli(cli, "java.jdt.typeResolution")
                if (outcome == "isolated module API error") {
                    assertTrue(diagnostics.all { it.jsonObject.getValue("file").jsonPrimitive.content.startsWith("restricted/") })
                    assertTrue(requireNotNull(nativeModule).isEmpty()); restricted(requireNotNull(restrictedModule))
                    cleanCli(requireNotNull(nativeCli)); errorCli(requireNotNull(restrictedCli), "java.jdt.typeResolution")
                }
            }
            "explicit project SDK required", "unqualified API environment" -> {
                val code = if (outcome == "explicit project SDK required") "java.platform.explicitJdkRequired" else "java.platform.compilerApiUnqualified"
                assertEquals(1, diagnostics.size, diagnostics.toString())
                assertEquals(code, diagnostics.single().jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals("STRUCTURAL", diagnostics.single().jsonObject.getValue("evidence").jsonPrimitive.content)
                errorCli(cli, code); assertFalse(cli.second.contains("java.jdt.typeResolution"), cli.second)
            }
            else -> error("Unknown diagnostics expectation")
        }
        println("JAVA_NATIVE_DIAGNOSTICS " + buildJsonObject {
            put("condition", condition); put("daemonDiagnostics", diagnostics.size); put("cliExitCode", cli.first)
            put("selectedSdkHome", sdk.toString()); put("explicitProjectSdk", condition != "embedded SDK fallback")
        })
    }

    private fun restricted(rows: JsonArray) {
        assertEquals(1, rows.size, rows.toString())
        val row = rows.single().jsonObject
        assertEquals("java.jdt.typeResolution", row.getValue("code").jsonPrimitive.content)
        assertEquals("COMPILER", row.getValue("evidence").jsonPrimitive.content)
        assertTrue(row.getValue("message").jsonPrimitive.content.contains("of("), row.toString())
    }
    private fun cleanCli(result: Pair<Int, String>) { assertEquals(0, result.first, result.second); assertTrue(result.second.contains("No diagnostics."), result.second) }
    private fun errorCli(result: Pair<Int, String>, code: String) { assertEquals(1, result.first, result.second); assertTrue(result.second.contains("[$code]"), result.second) }
    fun verifyIntact() {
        val actual = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList()
            .associate { root.relativize(it).toString() to Files.readString(it) } }
        assertEquals(images, actual); assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }
    override fun close() { if (::native.isInitialized) native.close(); if (::root.isInitialized) root.toFile().deleteRecursively() }
}

/** Entry-point Story glue shares this file's existing native query infrastructure. */
class JavaEntryPointDiscoverySteps {
    private val fixture = JavaEntryPointDiscoveryFixture()
    private var apiFixture: JavaCompilerApiFixture? = null
    private var mixedFixture: JavaMixedInventoryFixture? = null
    @Given("a provider-owned Java entry point fixture requests {string} with its selected SDK")
    fun capture(condition: String) {
        if (condition.startsWith("wire mixed inventory ")) mixedFixture = JavaMixedInventoryFixture().also { it.capture(condition) }
        else if (condition.startsWith("wire API ")) apiFixture = JavaCompilerApiFixture().also { it.capture(condition) }
        else fixture.capture(condition)
    }
    @When("the daemon discovers Java entry points for the exact module and source set")
    fun discover() { mixedFixture?.discover() ?: apiFixture?.discover() ?: fixture.discover() }
    @Then("Java entry point discovery reports {string} with its native launch and coverage facts")
    fun result(expected: String) { mixedFixture?.verify(expected) ?: apiFixture?.verify(expected) ?: fixture.verify(expected) }
    @Then("Java entry point discovery preserves all inputs without building or executing application code")
    fun intact() { mixedFixture?.verifyIntact() ?: apiFixture?.verifyIntact() ?: fixture.verifyIntact() }
    @After
    fun close() { mixedFixture?.close(); apiFixture?.close(); fixture.close() }
}

/** Java projection must not erase unrelated inventory or inherit its completeness. */
private class JavaMixedInventoryFixture : AutoCloseable {
    private lateinit var root: Path
    private lateinit var native: NativeJavaDaemonRpcSession
    private lateinit var sdk: String
    private lateinit var condition: String
    private lateinit var images: Map<String, String>
    private lateinit var snapshot: String
    private lateinit var context: JsonObject
    private lateinit var reply: JsonObject
    private lateinit var summary: JsonObject
    private var primaryPath = "code/demo/Main.java"
    private var inventoryPath = ".ide/java/test-results/run-1/report.js"

    fun capture(condition: String) {
        this.condition = condition
        root = Files.createTempDirectory("rk-java-mixed-inventory-")
        sdk = Path.of(System.getProperty("java.home")).toRealPath().toString()
        val flat = condition == "wire mixed inventory flat TypeScript"
        if (flat) primaryPath = "Main.java"
        if (condition == "wire mixed inventory real JavaScript") inventoryPath = "web/client.js"
        if (flat) inventoryPath = "web/client.ts"
        val descriptor = buildJsonObject {
            put("schemaVersion", "java-declared-project-v1"); put("sdkHome", sdk)
            put("modules", buildJsonArray { add(buildJsonObject {
                put("id", "app"); put("root", "."); put("sourceSets", buildJsonArray { add(buildJsonObject {
                    put("id", "main"); put("kind", "main")
                    put("sourceRoots", buildJsonArray { add(if (condition == "wire mixed inventory invalid Java") "../escape" else if (flat) "." else "code") })
                    put("generatedSourceRoots", buildJsonArray {}); put("outputDirectories", buildJsonArray { add("products/classes") })
                    put("compileClasspath", buildJsonArray {}); put("runtimeClasspath", buildJsonArray { add("products/classes") })
                    put("release", 17); put("proc", "none")
                }) })
            }) })
        }
        images = linkedMapOf(
            ".refactorkit/java-project.json" to descriptor.toString(),
            primaryPath to "package demo; public class Main { public static void main(String[] args) { throw new AssertionError(\"never run\"); } }\n",
            inventoryPath to "export const report = 1;\n",
        )
        if (flat) images = images + ("tsconfig.json" to "{\"compilerOptions\":{\"rootDir\":\".\"},\"include\":[\"web/*.ts\"]}\n")
        if (condition == "wire mixed inventory broken tsconfig") images = images + ("tsconfig.json" to "{ \"compilerOptions\": [ broken }\n")
        images.forEach { (path, text) -> root.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, text) } }
        native = NativeJavaDaemonRpcSession(root)
        snapshot = native.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .getValue("snapshotHash").jsonPrimitive.content
        summary = native.dispatch("project.summary", buildJsonObject {})
        context = native.dispatch("project.context", params(false))
    }

    private fun params(scoped: Boolean) = buildJsonObject {
        put("expectedSnapshotHash", snapshot); put("sdkHome", sdk)
        if (scoped) { put("providerId", "java-declared-v1"); put("moduleId", "app"); put("sourceSetId", "main") }
    }

    fun discover() {
        if (condition == "wire mixed inventory invalid Java") { reply = native.dispatch("project.context", params(false)); return }
        if (condition == "wire mixed inventory stale report") {
            images = images + (inventoryPath to (images.getValue(inventoryPath) + "export const changed = 2;\n"))
            Files.writeString(root.resolve(inventoryPath), images.getValue(inventoryPath))
            reply = native.dispatch("project.context", params(false)); return
        }
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        assertEquals(snapshot, context.getValue("snapshotHash").jsonPrimitive.content)
        context = native.dispatch("project.context.v7", params(true))
        reply = native.dispatch("intelligence.query", buildJsonObject {
            put("requestId", "mixed-java-inventory"); put("languageId", "java"); put("kind", "entryPoints")
            put("expectedSnapshotHash", snapshot); put("launchProfile", "classic-declared-v1"); put("path", primaryPath)
            put("scope", buildJsonObject { put("providerId", "java-declared-v1"); put("moduleId", "app"); put("sourceSetId", "main") })
            put("javaContext", buildJsonObject {
                put("sdkHome", sdk); put("expectedContextHash", context.getValue("contextHash")); put("contextSchemaVersion", "java-project-context-v7")
            })
        })
    }

    fun verify(expected: String) {
        val models = summary.getValue("buildModels").jsonArray.map { it.jsonObject }
        assertTrue(models.any { it.getValue("providerId").jsonPrimitive.content == "java-declared-v1" })
        val other = models.single { it.getValue("providerId").jsonPrimitive.content == "typescript-config-declarative-v1" }
        assertEquals(if (condition == "wire mixed inventory flat TypeScript") "available" else "unavailable",
            other.getValue("status").jsonPrimitive.content, summary.toString())
        if (expected.startsWith("java.")) {
            assertEquals("refused", reply.getValue("status").jsonPrimitive.content, reply.toString())
            assertEquals(expected, reply.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content); return
        }
        assertEquals("available", context.getValue("status").jsonPrimitive.content)
        assertEquals(listOf("java-declared-v1"), context.getValue("providers").jsonArray.map { it.jsonObject.getValue("providerId").jsonPrimitive.content })
        assertEquals(listOf("app"), context.getValue("modules").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertNotEquals(JsonNull, context.getValue("analysis"))
        assertEquals(snapshot, context.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals("ready", reply.getValue("status").jsonPrimitive.content, reply.toString())
        assertEquals(false, reply.getValue("complete").jsonPrimitive.boolean)
        assertEquals(true, reply.getValue("scopeAnalysisComplete").jsonPrimitive.boolean)
        assertEquals("demo.Main", reply.getValue("candidates").jsonArray.single().jsonObject.getValue("binaryName").jsonPrimitive.content)
        val same = native.dispatch("project.summary", buildJsonObject {})
        assertEquals(summary, same, "Java projection must not rewrite the shared workspace model")
    }

    fun verifyIntact() {
        val actual = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList()
            .associate { root.relativize(it).toString() to Files.readString(it) } }
        assertEquals(images, actual); assertFalse(Files.exists(root.resolve("products")))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }
    override fun close() { if (::native.isInitialized) native.close(); if (::root.isInitialized) root.toFile().deleteRecursively() }
}

/** Same native transport, but modern API references make platform selection observable. */
private class JavaCompilerApiFixture : AutoCloseable {
    private lateinit var root: Path
    private lateinit var native: NativeJavaDaemonRpcSession
    private lateinit var images: Map<String, String>
    private lateinit var condition: String
    private lateinit var sdk: Path
    private lateinit var snapshot: String
    private lateinit var context: JsonObject
    private lateinit var result: JsonObject
    private lateinit var module: String
    private var restrictedModule: String? = null
    private var restrictedResult: JsonObject? = null

    fun capture(condition: String) {
        this.condition = condition
        root = Files.createTempDirectory("rk-java-compiler-api-")
        sdk = Path.of(if ("SDK25" in condition) requireNotNull(System.getenv("REFACTORKIT_ENTRYPOINT_SDK25_HOME"))
            else System.getProperty("java.home")).toRealPath()
        val source = "package demo; public class Runner { public static void main(String[] args) { java.util.List.of(\"api\"); throw new AssertionError(\"must not run\"); } }\n"
        fun pom(id: String, release: Boolean): String {
            val properties = if (release) "<maven.compiler.release>8</maven.compiler.release>"
                else "<maven.compiler.source>8</maven.compiler.source><maven.compiler.target>8</maven.compiler.target>"
            val config = if (condition == "wire API custom system")
                "<configuration><compilerArgs><arg>--system</arg><arg>none</arg></compilerArgs></configuration>" else ""
            return "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>$id</artifactId><version>1</version><properties>$properties</properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version>$config</plugin></plugins></build></project>\n"
        }
        images = if (condition == "wire API SDK21 mixed modes") linkedMapOf(
            "pom.xml" to "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging><properties><maven.compiler.release>8</maven.compiler.release></properties><modules><module>restricted</module><module>native-api</module></modules></project>\n",
            "restricted/pom.xml" to pom("restricted", true), "restricted/src/main/java/demo/Runner.java" to source,
            "native-api/pom.xml" to pom("native-api", false), "native-api/src/main/java/demo/Runner.java" to source,
        ) else linkedMapOf("pom.xml" to pom("app", condition == "wire API SDK21 release8"),
            "src/main/java/demo/Runner.java" to source)
        images.forEach { (path, text) -> root.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, text) } }
        native = NativeJavaDaemonRpcSession(root)
        snapshot = native.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .getValue("snapshotHash").jsonPrimitive.content
        context = native.dispatch("project.context", contextParams())
        if (context.getValue("status").jsonPrimitive.content == "refused") return
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        val modules = context.getValue("modules").jsonArray.map { it.jsonObject }
        module = (if (modules.size == 1) modules.single() else modules.single {
            it.getValue("name").jsonPrimitive.content == "native-api"
        }).getValue("id").jsonPrimitive.content
        restrictedModule = modules.singleOrNull { it.getValue("name").jsonPrimitive.content == "restricted" }
            ?.getValue("id")?.jsonPrimitive?.content
        context = native.dispatch("project.context.v7", contextParams())
    }

    private fun contextParams() = buildJsonObject {
        put("expectedSnapshotHash", snapshot)
        if (condition != "wire API missing explicit SDK") put("sdkHome", sdk.toString())
        if (::module.isInitialized) {
            put("providerId", "maven-effective-v1"); put("moduleId", module); put("sourceSetId", "main")
        }
    }

    private fun query(selected: String) = native.dispatch("intelligence.query", buildJsonObject {
        put("requestId", "compiler-api-story"); put("languageId", "java"); put("kind", "entryPoints")
        put("expectedSnapshotHash", snapshot); put("launchProfile", "classic-declared-v1")
        put("scope", buildJsonObject { put("providerId", "maven-effective-v1"); put("moduleId", selected); put("sourceSetId", "main") })
        put("javaContext", buildJsonObject {
            put("sdkHome", sdk.toString()); put("expectedContextHash", context.getValue("contextHash"))
            put("contextSchemaVersion", "java-project-context-v7")
        })
    })

    fun discover() {
        if (condition in setOf("wire API missing explicit SDK", "wire API custom system")) {
            result = native.dispatch(if (::module.isInitialized) "project.context.v7" else "project.context", contextParams()); return
        }
        result = query(module)
        restrictedResult = restrictedModule?.let(::query)
    }

    fun verify(expected: String) {
        if (expected.startsWith("java.context.") || expected.startsWith("java.platform.")) {
            assertEquals("refused", result.getValue("status").jsonPrimitive.content, result.toString())
            assertEquals(expected, result.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
            assertTrue(result["sdk"] == null || result["sdk"]?.jsonArray?.isEmpty() == true)
            assertTrue(result["analysis"] == null || result["analysis"] == JsonNull); return
        }
        if (expected == "java.entryPoints.analysisUnavailable") { verifyRestricted(result); return }
        assertEquals("ready", result.getValue("status").jsonPrimitive.content, result.toString())
        assertEquals(false, result.getValue("complete").jsonPrimitive.boolean)
        assertEquals(true, result.getValue("scopeAnalysisComplete").jsonPrimitive.boolean)
        val candidate = result.getValue("candidates").jsonArray.single().jsonObject
        assertEquals("demo.Runner", candidate.getValue("binaryName").jsonPrimitive.content)
        assertEquals("([Ljava/lang/String;)V", candidate.getValue("descriptor").jsonPrimitive.content)
        assertEquals(8, candidate.getValue("sourceLevel").jsonPrimitive.int)
        assertEquals(8, candidate.getValue("targetLevel").jsonPrimitive.int)
        val sdkRelease = if ("SDK25" in condition) 25 else 21
        assertEquals(sdkRelease, result.getValue("launcherRelease").jsonPrimitive.int)
        val profiles = context.getValue("sdk").jsonArray.map { it.jsonObject }
        val nativeProfile = profiles.single { it.getValue("sourceRelease").jsonPrimitive.int == sdkRelease }
        assertEquals(sdkRelease, nativeProfile.getValue("platformRelease").jsonPrimitive.int)
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sdk.resolve("release"))).joinToString("") { "%02x".format(it) },
            nativeProfile.getValue("releaseMetadataSha256").jsonPrimitive.content)
        if (expected == "isolated SDK API candidates") {
            assertEquals(setOf(8, 21), profiles.map { it.getValue("sourceRelease").jsonPrimitive.int }.toSet())
            assertEquals(2, profiles.map { it.getValue("identitySha256") }.distinct().size)
            verifyRestricted(requireNotNull(restrictedResult))
            assertEquals("ready", query(module).getValue("status").jsonPrimitive.content, "Restricted query must not poison native API scope")
        }
    }

    private fun verifyRestricted(reply: JsonObject) {
        assertEquals("refused", reply.getValue("status").jsonPrimitive.content, reply.toString())
        assertEquals("java.entryPoints.analysisUnavailable", reply.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertFalse("candidates" in reply)
    }

    fun verifyIntact() {
        val actual = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList()
            .associate { root.relativize(it).toString() to Files.readString(it) } }
        assertEquals(images, actual, "No build, processor, application execution or workspace writes")
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    override fun close() { if (::native.isInitialized) native.close(); if (::root.isInitialized) root.toFile().deleteRecursively() }
}

private class JavaEntryPointDiscoveryFixture : AutoCloseable {
    private var root: Path? = null
    private var session: DaemonSession? = null
    private var wire: NativeJavaDaemonRpcSession? = null
    private var wireEnvelope: JsonObject? = null
    private var wireFrameBytes = 0
    private var queueOrderVerified = false
    private lateinit var condition: String
    private lateinit var snapshotHash: String
    private lateinit var context: JsonObject
    private lateinit var moduleId: String
    private lateinit var images: Map<String, String>
    private lateinit var response: JsonObject
    private lateinit var selectedSdkHome: Path
    private var expectedSourceLevel = 8
    private var expectedLauncherRelease = 0
    private var requestedPosition: JsonObject? = null
    private var providerId = "maven-effective-v1"
    private var primaryPath = "src/main/java/demo/Runner.java"
    private var nestedPath = "src/main/java/demo/Container.java"
    private var binaryImages: Map<String, ByteArray> = emptyMap()
    private var expectedBinaryPrefix = "demo."

    fun capture(condition: String) {
        require(condition in setOf("classic scope", "nested varargs path", "limit one", "stale context", "cancelled", "Java25 profile", "wire classic scope", "wire RPC ID boundary", "wire oversized RPC ID", "wire queued cancellation", "wire invalid limit", "wire Java25 profile", "wire SDK21 release8", "wire SDK21 source-target8", "wire SDK25 release25", "wire SDK25 source-target25", "wire SDK25 release8", "wire incomplete compiler facts", "wire cursor primary body", "wire cursor secondary body", "wire cursor nested body", "wire cursor dormant inner type", "wire cursor outside type", "wire cursor wrong source set", "wire plain flat release17", "wire plain conventional release17", "wire plain custom classpath25", "wire plain missing compiler facts", "wire plain stale manifest", "wire plain unsafe source root"))
        this.condition = condition
        val sdk25 = condition.startsWith("wire SDK25 ") || condition == "wire plain custom classpath25"
        selectedSdkHome = if (sdk25) Path.of(requireNotNull(
            System.getenv("REFACTORKIT_ENTRYPOINT_SDK25_HOME")) { "An actual SDK25 is required for the native entry-point matrix" }).toRealPath()
            else Path.of(System.getProperty("java.home")).toRealPath()
        expectedLauncherRelease = if (sdk25) 25 else System.getProperty("java.specification.version").toInt()
        if (condition.startsWith("wire SDK21 ")) assertEquals(21, expectedLauncherRelease)
        expectedSourceLevel = when {
            condition.endsWith("25") -> 25
            condition.startsWith("wire plain ") && condition != "wire plain missing compiler facts" -> 17
            else -> 8
        }
        val compilerProperties = if ("source-target" in condition)
            "<maven.compiler.source>$expectedSourceLevel</maven.compiler.source><maven.compiler.target>$expectedSourceLevel</maven.compiler.target>"
            else "<maven.compiler.release>$expectedSourceLevel</maven.compiler.release>"
        val compilerVersion = if (condition == "wire incomplete compiler facts") "" else "<version>3.13.0</version>"
        val workspace = Files.createTempDirectory("rk-java-entry-points-").also { root = it }
        images = linkedMapOf(
            "pom.xml" to """<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>entrypoints</artifactId><version>1</version><properties>$compilerProperties</properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>$compilerVersion</plugin></plugins></build></project>
""",
            "src/main/java/demo/Runner.java" to """package demo;
public class Runner {
  public static void main(String[] args) { throw new AssertionError("do not execute application"); }
}
class Secondary {
  public static void main(String[] args) { throw new AssertionError("do not execute secondary"); }
}
""",
            "src/main/java/demo/Container.java" to """package demo;
public class Container {
  public static class Nested {
    public static void main(String... args) { throw new AssertionError("do not execute nested"); }
  }
}
class Decoy {
  public static int main(String[] args) { return 1; }
}
""",
            "src/test/java/demo/Runner.java" to """package demo;
public class Runner {
  public static void main(String[] args) { throw new AssertionError("do not execute test source"); }
}
""",
        )
        if (condition.startsWith("wire cursor ")) {
            val path = "src/main/java/demo/Runner.java"
            images = images + (path to images.getValue(path)
                .replace("do not execute application", "\uD83D\uDCA1 do not execute application")
                .replace("\n}\nclass Secondary", "\n  static class Dormant { void waitForInput() { /* dormant cursor */ } }\n}\nclass Secondary"))
        }
        if (condition.startsWith("wire plain ")) configurePlain()
        images.forEach { (relative, text) ->
            val path = workspace.resolve(relative)
            Files.createDirectories(path.parent); Files.writeString(path, text)
        }
        binaryImages.forEach { (relative, bytes) ->
            val path = workspace.resolve(relative); Files.createDirectories(path.parent); Files.write(path, bytes)
        }
        if (condition.startsWith("wire ")) wire = NativeJavaDaemonRpcSession(workspace) else session = DaemonSession()
        snapshotHash = rpc("project.open", buildJsonObject { put("root", workspace.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val initial = rpc("project.context", buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", selectedSdkHome.toString())
        }).jsonObject
        if (condition == "wire plain unsafe source root") {
            assertEquals("refused", initial.getValue("status").jsonPrimitive.content, initial.toString())
            assertEquals("java.context.declaredProjectInvalid", initial.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
            context = initial; moduleId = "app"; return
        }
        assertEquals(if (condition == "wire plain missing compiler facts") "partial" else "available",
            initial.getValue("status").jsonPrimitive.content, initial.toString())
        moduleId = initial.getValue("modules").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
        context = rpc("project.context.v7", buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", selectedSdkHome.toString())
            put("providerId", providerId); put("moduleId", moduleId); put("sourceSetId", "main")
        }).jsonObject
        assertEquals(if (condition == "wire plain missing compiler facts") "partial" else "available",
            context.getValue("status").jsonPrimitive.content, context.toString())
        val selectedSet = context.getValue("modules").jsonArray.single().jsonObject.getValue("sourceSets").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == "main" }
        assertEquals(when (condition) { "wire incomplete compiler facts" -> "partial"; "wire plain missing compiler facts" -> "unavailable"; else -> "complete" },
            selectedSet.getValue("compilerOptionsAvailability").jsonPrimitive.content)
        if (providerId == "java-declared-v1") {
            context.getValue("modules").jsonArray.single().jsonObject.getValue("sourceSets").jsonArray.forEach {
                val set = it.jsonObject
                val declared = set.getValue("sourceRoots").jsonArray
                val expectedRoots = if (condition == "wire plain custom classpath25" && set.getValue("id").jsonPrimitive.content == "test")
                    JsonArray(declared + JsonPrimitive("code/app")) else declared
                assertEquals(expectedRoots, set.getValue("analysisSourceRoots").jsonArray,
                    "Only selected roots and owners of explicitly declared compile outputs may be visible")
            }
            val options = selectedSet.getValue("compilerOptions").jsonArray.map { it.jsonObject }
            assertEquals(expectedSourceLevel.toString(), options.single { it.getValue("name").jsonPrimitive.content == "release" }.getValue("value").jsonPrimitive.content)
            assertEquals("none", options.single { it.getValue("name").jsonPrimitive.content == "proc" }.getValue("value").jsonPrimitive.content)
            assertTrue(selectedSet.getValue("sourceRootEvidence").jsonArray.all {
                it.jsonObject.getValue("providerId").jsonPrimitive.content == providerId
            })
            if (condition == "wire plain custom classpath25") {
                val expected = listOf("lib/fixture-dependency.jar", "lib/classes")
                assertEquals(expected, selectedSet.getValue("compileClasspath").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content })
                val evidence = context.getValue("buildEvidence").jsonArray.map { it.jsonObject }
                expected.forEach { path ->
                    val receipt = evidence.single { it.getValue("path").jsonPrimitive.content == path && it.getValue("kind").jsonPrimitive.content == "entry" }
                    assertEquals(org.refactorkit.core.ClasspathEvidence.capture(workspace, Path.of(path), org.refactorkit.core.ClasspathEvidenceKind.ENTRY).fingerprint,
                        receipt.getValue("fingerprint").jsonPrimitive.content)
                }
            }
        }
        if ("source-target" in condition) {
            val options = selectedSet.getValue("compilerOptions").jsonArray.map { it.jsonObject }
            for (name in listOf("source", "target")) assertEquals(expectedSourceLevel.toString(),
                options.single { it.getValue("name").jsonPrimitive.content == name }.getValue("value").jsonPrimitive.content)
            assertFalse(options.any { it.getValue("name").jsonPrimitive.content == "release" })
        }
    }

    private fun configurePlain() {
        images = images - "pom.xml"
        providerId = if (condition == "wire plain missing compiler facts") "java-conventional-v1" else "java-declared-v1"
        if (condition == "wire plain missing compiler facts") return
        val namespace = if (condition == "wire plain custom classpath25") "build" else "demo"
        expectedBinaryPrefix = "$namespace."
        val originalPrimary = images.getValue(primaryPath).replace("package demo;", "package $namespace;")
        val originalNested = images.getValue(nestedPath).replace("package demo;", "package $namespace;")
        val test = images.getValue("src/test/java/demo/Runner.java").replace("package demo;", "package $namespace;")
        val sourceRoot = when (condition) {
            "wire plain flat release17" -> "."
            "wire plain custom classpath25" -> "code/app"
            else -> "src/main/java"
        }
        primaryPath = if (sourceRoot == ".") "Runner.java" else "$sourceRoot/$namespace/Runner.java"
        nestedPath = if (sourceRoot == ".") "Container.java" else "$sourceRoot/$namespace/Container.java"
        images = linkedMapOf(primaryPath to originalPrimary, nestedPath to originalNested)
        val flat = condition == "wire plain flat release17"
        if (flat) {
            expectedBinaryPrefix = ""
            images = images.mapValues { (_, text) -> text.removePrefix("package demo;\n") }
        }
        if (!flat) images = images + ("src/test/java/$namespace/Runner.java" to test)
        val dependency = if (condition == "wire plain custom classpath25") "lib/fixture-dependency.jar" else null
        if (dependency != null) {
            val temp = Files.createTempDirectory("rk-plain-dependency-")
            try {
                val source = temp.resolve("Token.java")
                Files.writeString(source, "package support; public final class Token { public static String value() { return \"dependency\"; } }")
                val other = temp.resolve("Other.java")
                Files.writeString(other, "package support; public final class Other { public static String value() { return \"directory\"; } }")
                val output = temp.resolve("classes"); Files.createDirectory(output)
                assertEquals(0, requireNotNull(javax.tools.ToolProvider.getSystemJavaCompiler()).run(null, null, null,
                    "--release", "8", "-proc:none", "-d", output.toString(), source.toString(), other.toString()))
                val bytes = java.io.ByteArrayOutputStream()
                java.util.jar.JarOutputStream(bytes).use { jar ->
                    jar.putNextEntry(java.util.jar.JarEntry("support/Token.class")); jar.write(Files.readAllBytes(output.resolve("support/Token.class"))); jar.closeEntry()
                }
                binaryImages = mapOf(dependency to bytes.toByteArray(), "lib/classes/support/Other.class" to Files.readAllBytes(output.resolve("support/Other.class")))
                images = images + (primaryPath to originalPrimary.replace("throw new AssertionError(\"do not execute application\")", "support.Token.value(); support.Other.value(); throw new AssertionError(\"do not execute application\")"))
                images = images + ("code/app/$namespace/Support.java" to "package $namespace; public final class Support { public static String message() { return \"source dependency\"; } }\n")
                val testPath = "src/test/java/$namespace/Runner.java"
                images = images + (testPath to images.getValue(testPath).replace("throw new AssertionError", "$namespace.Support.message(); throw new AssertionError"))
            } finally { Files.walk(temp).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
        fun set(id: String, kind: String, roots: List<String>, output: String) = buildJsonObject {
            put("id", id); put("kind", kind); put("release", expectedSourceLevel); put("proc", "none")
            put("sourceRoots", JsonArray(roots.map(::JsonPrimitive))); put("generatedSourceRoots", JsonArray(emptyList()))
            put("outputDirectories", JsonArray(listOf(JsonPrimitive(output))))
            val dependencies = (if (dependency != null && id == "test") listOf(".ide/java/classes") else emptyList()) +
                (if (dependency == null) emptyList() else listOf(dependency, "lib/classes"))
            put("compileClasspath", JsonArray(dependencies.map(::JsonPrimitive)))
            put("runtimeClasspath", JsonArray((listOf(output) + dependencies).map(::JsonPrimitive)))
        }
        val descriptor = buildJsonObject {
            put("schemaVersion", "java-declared-project-v1"); put("sdkHome", selectedSdkHome.toString())
            put("modules", buildJsonArray { add(buildJsonObject {
                put("id", "app"); put("root", ".")
                put("sourceSets", buildJsonArray {
                    add(set("main", "main", listOf(if (condition == "wire plain unsafe source root") "../escape" else sourceRoot), ".ide/java/classes"))
                    if (!flat) add(set("test", "test", listOf("src/test/java"), ".ide/java/test-classes"))
                })
            }) })
        }
        images = images + (".refactorkit/java-project.json" to descriptor.toString())
    }

    private fun rpc(method: String, params: JsonObject): JsonObject =
        wire?.dispatch(method, params) ?: requireNotNull(session).dispatch(method, params).jsonObject

    fun discover() {
        if (condition == "wire plain stale manifest") {
            val path = ".refactorkit/java-project.json"; images = images + (path to (images.getValue(path) + "\n"))
            Files.writeString(requireNotNull(root).resolve(path), images.getValue(path))
        }
        val params = buildJsonObject {
            put("requestId", "entry-points-story"); put("kind", "entryPoints"); put("languageId", "java")
            put("expectedSnapshotHash", snapshotHash)
            put("scope", buildJsonObject {
                put("providerId", providerId); put("moduleId", moduleId); put("sourceSetId", "main")
            })
            put("javaContext", buildJsonObject {
                put("sdkHome", selectedSdkHome.toString())
                put("expectedContextHash", if (condition in setOf("stale context", "wire plain unsafe source root")) "0".repeat(64) else context.getValue("contextHash").jsonPrimitive.content)
                put("contextSchemaVersion", "java-project-context-v7")
            })
            put("launchProfile", if (condition.removePrefix("wire ") == "Java25 profile") "java25-v1" else "classic-declared-v1")
            if (condition == "nested varargs path") put("path", "src/main/java/demo/Container.java")
            if (condition == "limit one") put("limit", 1)
            if (condition == "wire invalid limit") put("limit", 201)
            if (condition.startsWith("wire cursor ")) {
                val path = when (condition) {
                    "wire cursor nested body" -> "src/main/java/demo/Container.java"
                    "wire cursor wrong source set" -> "src/test/java/demo/Runner.java"
                    else -> "src/main/java/demo/Runner.java"
                }
                val anchor = when (condition) {
                    "wire cursor primary body" -> "do not execute application"
                    "wire cursor secondary body" -> "do not execute secondary"
                    "wire cursor nested body" -> "do not execute nested"
                    "wire cursor dormant inner type" -> "dormant cursor"
                    "wire cursor wrong source set" -> "do not execute test source"
                    else -> "package demo"
                }
                val text = images.getValue(path); val offset = text.indexOf(anchor)
                assertTrue(offset >= 0)
                val point = org.refactorkit.core.TextEdits.rangeForOffset(text, offset, 1).start
                requestedPosition = buildJsonObject { put("line", point.line); put("character", point.character) }
                put("path", path); put("position", requireNotNull(requestedPosition))
            }
        }
        val native = wire
        if (native == null) {
            response = requireNotNull(session).dispatch("intelligence.query", params,
                org.refactorkit.core.SemanticCancellationToken { condition == "cancelled" }).jsonObject
        } else when (condition) {
            "wire RPC ID boundary" -> {
                val id = JsonPrimitive("r".repeat(1022))
                assertEquals(1024, id.toString().toByteArray(Charsets.UTF_8).size)
                wireEnvelope = native.raw("intelligence.query", params, id)
                wireFrameBytes = native.lastFrameBytes
                response = requireNotNull(wireEnvelope).getValue("result").jsonObject
            }
            "wire oversized RPC ID" -> {
                val id = JsonPrimitive("\uD83D\uDCA1".repeat(256))
                assertEquals(1026, id.toString().toByteArray(Charsets.UTF_8).size)
                wireEnvelope = native.raw("intelligence.query", params, id, expectedId = null)
                wireFrameBytes = native.lastFrameBytes
            }
            "wire invalid limit" -> {
                wireEnvelope = native.raw("intelligence.query", params, JsonPrimitive(301))
                wireFrameBytes = native.lastFrameBytes
                val cancelled = native.dispatch("intelligence.cancel", buildJsonObject { put("requestId", "entry-points-story") })
                assertEquals("not-found", cancelled.getValue("status").jsonPrimitive.content)
                response = native.dispatch("intelligence.query", JsonObject(params + ("limit" to JsonPrimitive(100))))
                assertEquals("ready", response.getValue("status").jsonPrimitive.content)
            }
            "wire queued cancellation" -> {
                val blocker = buildJsonObject {
                    put("expectedSnapshotHash", snapshotHash)
                    put("sdkHome", selectedSdkHome.toString())
                    put("providerId", "maven-effective-v1"); put("moduleId", moduleId); put("sourceSetId", "main")
                }
                val (ids, replies) = native.batch(listOf("project.context.v7" to blocker, "intelligence.query" to params,
                    "intelligence.cancel" to buildJsonObject { put("requestId", "entry-points-story") }))
                val blocked = replies.single { it.message["id"] == ids[0] }
                val query = replies.single { it.message["id"] == ids[1] }
                val cancel = replies.single { it.message["id"] == ids[2] }
                assertEquals("available", blocked.message.getValue("result").jsonObject.getValue("status").jsonPrimitive.content)
                assertEquals("cancelled", cancel.message.getValue("result").jsonObject.getValue("status").jsonPrimitive.content)
                assertTrue(replies.indexOf(query) < replies.indexOf(blocked), "Must observe cancellation while the control request still blocks the query")
                queueOrderVerified = true; wireEnvelope = query.message; wireFrameBytes = query.frameBytes
                assertEquals("not-found", native.dispatch("intelligence.cancel", buildJsonObject {
                    put("requestId", "entry-points-story")
                }).getValue("status").jsonPrimitive.content)
                response = native.dispatch("intelligence.query", params)
                assertEquals("ready", response.getValue("status").jsonPrimitive.content)
            }
            else -> { response = native.dispatch("intelligence.query", params); wireFrameBytes = native.lastFrameBytes }
        }
    }

    fun verify(expected: String) {
        if (wire != null) {
            assertTrue(wireFrameBytes in 1..1_048_576, "Entire UTF-8 NDJSON frame exceeds its budget")
            when (expected) {
                "invalid RPC ID refused" -> {
                    val envelope = requireNotNull(wireEnvelope)
                    assertEquals(JsonNull, envelope.getValue("id"))
                    assertEquals(org.refactorkit.core.JsonRpcErrorCodes.INVALID_REQUEST, envelope.getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
                    assertEquals("not-found", rpc("intelligence.cancel", buildJsonObject { put("requestId", "entry-points-story") })
                        .getValue("status").jsonPrimitive.content)
                    return
                }
                "invalid limit refused" -> {
                    assertEquals(org.refactorkit.core.JsonRpcErrorCodes.INVALID_PARAMS,
                        requireNotNull(wireEnvelope).getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
                    return
                }
                "cancelled without registration leak" -> {
                    assertTrue(queueOrderVerified)
                    assertEquals(org.refactorkit.core.JsonRpcErrorCodes.REQUEST_CANCELLED,
                        requireNotNull(wireEnvelope).getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
                    return
                }
            }
            if (expected in setOf("declared classic candidates", "bounded RPC ID response")) {
                val capability = rpc("server.capabilities", buildJsonObject {})
                    .getValue("methods").jsonArray.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "intelligence.query" }
                val features = capability.getValue("features").jsonObject
                for (flag in listOf("javaEntryPointsClassicDeclared", "javaEntryPointsBoundedFrame", "javaEntryPointsCancellation"))
                    assertEquals(JsonPrimitive(true), features[flag], flag)
                for (flag in listOf("javaEntryPointsInherited", "javaEntryPointsJava25"))
                    assertEquals(JsonPrimitive(false), features[flag], flag)
            }
        }
        if (expected.startsWith("java.") || expected == "intelligence.cancelled") {
            assertEquals("refused", response.getValue("status").jsonPrimitive.content, response.toString())
            assertEquals(expected, response.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertFalse("candidates" in response)
            return
        }
        assertEquals("ready", response.getValue("status").jsonPrimitive.content, response.toString())
        assertEquals("java-entry-points-v1", response.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("entryPoints", response.getValue("kind").jsonPrimitive.content)
        assertEquals("entry-points-story", response.getValue("requestId").jsonPrimitive.content)
        assertEquals(snapshotHash, response.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals(context.getValue("contextHash"), response.getValue("contextHash"))
        assertEquals("java-project-context-v7", response.getValue("contextSchemaVersion").jsonPrimitive.content)
        assertEquals(false, response.getValue("complete").jsonPrimitive.boolean)
        assertEquals(true, response.getValue("scopeAnalysisComplete").jsonPrimitive.boolean)
        assertEquals(expectedLauncherRelease, response.getValue("launcherRelease").jsonPrimitive.int)
        val platforms = response.getValue("platformIdentities").jsonArray
        assertTrue(platforms.isNotEmpty())
        assertTrue(platforms.all { it.jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")) })
        val scope = response.getValue("scope").jsonObject
        assertEquals(providerId, scope.getValue("providerId").jsonPrimitive.content)
        assertEquals(moduleId, scope.getValue("moduleId").jsonPrimitive.content)
        assertEquals("main", scope.getValue("sourceSetId").jsonPrimitive.content)
        val coverage = response.getValue("coverage").jsonObject
        assertEquals(listOf("classic-declared-v1"), coverage.getValue("qualifiedProfiles").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(coverage.getValue("unqualifiedProfiles").jsonArray.isNotEmpty())

        val nested = expectedBinaryPrefix + "Container" + '$' + "Nested"
        if (expected.startsWith("selected ")) {
            val selection = response.getValue("selection").jsonObject
            assertEquals("enclosing-type", selection.getValue("mode").jsonPrimitive.content)
            assertEquals(requireNotNull(requestedPosition), selection.getValue("position").jsonObject)
            assertEquals(when (expected) {
                "selected primary main" -> "demo.Runner"
                "selected secondary main" -> "demo.Secondary"
                "selected nested main" -> nested
                "selected type has no declared main" -> "demo.Runner" + '$' + "Dormant"
                else -> error("Unknown enclosing selection")
            }, selection.getValue("binaryName").jsonPrimitive.content)
            val path = selection.getValue("path").jsonPrimitive.content
            val range = selection.getValue("range").jsonObject
            fun point(value: JsonObject) = org.refactorkit.core.SourcePosition(value.getValue("line").jsonPrimitive.int, value.getValue("character").jsonPrimitive.int)
            assertTrue(point(range.getValue("start").jsonObject) <= point(requireNotNull(requestedPosition)))
            assertTrue(point(requireNotNull(requestedPosition)) < point(range.getValue("end").jsonObject))
            val text = images.getValue(path)
            assertTrue(org.refactorkit.core.TextEdits.offsetOf(text, point(range.getValue("end").jsonObject)) <= text.length)
            val flags = rpc("server.capabilities", buildJsonObject {}).getValue("methods").jsonArray.map { it.jsonObject }
                .single { it.getValue("name").jsonPrimitive.content == "intelligence.query" }.getValue("features").jsonObject
            assertEquals(JsonPrimitive(true), flags["javaEntryPointsEnclosingType"])
        } else assertFalse("selection" in response)
        val candidates = response.getValue("candidates").jsonArray.map { it.jsonObject }
        val names = candidates.map { it.getValue("binaryName").jsonPrimitive.content }
        val expectedNames = when (expected) {
            "declared classic candidates", "bounded RPC ID response" -> listOf(nested, expectedBinaryPrefix + "Runner", expectedBinaryPrefix + "Secondary")
            "one native nested candidate", "honest truncation" -> listOf(nested)
            "selected primary main" -> listOf("demo.Runner")
            "selected secondary main" -> listOf("demo.Secondary")
            "selected nested main" -> listOf(nested)
            "selected type has no declared main" -> emptyList()
            else -> error("Unknown entry-point expectation")
        }
        assertEquals(expectedNames, names)
        assertEquals(candidates.size, response.getValue("returnedCount").jsonPrimitive.int)
        assertEquals(when {
            expected == "selected type has no declared main" -> 0
            expected.startsWith("selected ") || expected == "one native nested candidate" -> 1
            else -> 3
        }, response.getValue("totalEligibleCount").jsonPrimitive.int)
        assertEquals(expected == "honest truncation", response.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(candidates.size, candidates.map { it.getValue("entryPointId") }.distinct().size)
        candidates.forEach { candidate ->
            assertTrue(candidate.getValue("entryPointId").jsonPrimitive.content.matches(Regex("java-entry:[0-9a-f]{64}")))
            assertEquals(candidate.getValue("binaryName"), candidate.getValue("declaringBinaryName"))
            assertEquals("([Ljava/lang/String;)V", candidate.getValue("descriptor").jsonPrimitive.content)
            assertEquals("static", candidate.getValue("invocation").jsonPrimitive.content)
            assertEquals(false, candidate.getValue("inherited").jsonPrimitive.boolean)
            assertEquals(false, candidate.getValue("generated").jsonPrimitive.boolean)
            assertEquals(expectedSourceLevel, candidate.getValue("sourceLevel").jsonPrimitive.int)
            assertEquals(expectedSourceLevel, candidate.getValue("targetLevel").jsonPrimitive.int)
            val source = candidate.getValue("source").jsonObject
            val path = source.getValue("path").jsonPrimitive.content
            assertEquals(if (candidate.getValue("binaryName").jsonPrimitive.content == nested)
                nestedPath else primaryPath, path)
            val range = source.getValue("range").jsonObject
            val start = range.getValue("start").jsonObject; val end = range.getValue("end").jsonObject
            assertEquals(start.getValue("line"), end.getValue("line"))
            assertEquals("main", images.getValue(path).lines()[start.getValue("line").jsonPrimitive.int]
                .substring(start.getValue("character").jsonPrimitive.int, end.getValue("character").jsonPrimitive.int))
        }
        assertTrue(response.toString().toByteArray(Charsets.UTF_8).size <= 1_048_576 - 4_096)
    }

    fun verifyIntact() {
        val workspace = requireNotNull(root)
        val files = Files.walk(workspace).use { paths ->
            paths.filter(Files::isRegularFile).toList().filter { workspace.relativize(it).toString() !in binaryImages }
                .associate { workspace.relativize(it).toString() to Files.readString(it) }
        }
        assertEquals(images, files)
        binaryImages.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(workspace.resolve(path))) }
        if (wire != null) println("ENTRY_POINT_NATIVE_WIRE " + buildJsonObject {
            put("condition", condition); put("frameUtf8Bytes", wireFrameBytes); put("queueOrderVerified", queueOrderVerified)
            put("selectedSdkHome", selectedSdkHome.toString()); put("expectedSourceLevel", expectedSourceLevel)
            put("expectedLauncherRelease", expectedLauncherRelease)
        })
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        Files.walk(workspace).use { paths -> assertFalse(paths.anyMatch {
            it.fileName.toString().endsWith(".class") && workspace.relativize(it).toString() !in binaryImages
        }) }
    }

    override fun close() {
        wire?.close()
        session?.close()
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}
