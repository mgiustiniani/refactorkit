package org.refactorkit.java.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.core.*
import org.refactorkit.java.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import kotlin.test.*

class JavaEntryPointsV2Steps {
    private var fixture: EntryPointsV2Fixture? = null
    @Given("a versioned Java25 entrypoint protocol fixture uses {string}")
    fun prepare(shape: String) { fixture = EntryPointsV2Fixture(shape) }
    @When("the native versioned entrypoint protocol is queried")
    fun query() = requireNotNull(fixture).query()
    @Then("the response has exact launch facts and satisfies the closed v2 schema")
    fun facts() = requireNotNull(fixture).facts()
    @Then("versioned queries preserve classic contract inputs and refuse stale authority")
    fun controls() = requireNotNull(fixture).controls()
    @After
    fun close() { fixture?.close(); fixture = null }
}

/** Public protocol invocation, shared by the daemon; stdio/artifact qualification is separate. */
private class EntryPointsV2Fixture(private val shape: String) : AutoCloseable {
    private val root = Files.createTempDirectory("rk-java25-v2-")
    private val sdk = Path.of(requireNotNull(System.getenv("REFACTORKIT_ENTRYPOINT_SDK25_HOME"))).toRealPath()
    private val repository = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
    private val scanner = JavaProjectScanner()
    private val owner = when (shape) {
        in inheritedProfileShapes -> "record"
        "compact" -> "Or\u200Cder"
        "inherited", "inherited-classic", "inherited-cursor" -> "demo.Child"
        "nested-cursor", "no-main-cursor" -> "demo.Outer\$Nested"
        "outer-cursor" -> "demo.Outer"
        "file-multiple" -> "demo.Outer"
        else -> "demo.Main"
    }
    private val path = Path.of(if (shape == "compact" || shape in inheritedProfileShapes) "src/main/java/$owner.java" else "src/main/java/demo/${owner.substringAfterLast('.').substringBefore('$')}.java")
    private val profile = if (shape == "inherited-classic" || shape in inheritedProfileShapes) "inherited-v1" else "java25-v1"
    private val images: Map<Path, String>
    private val sdkImages = listOf("release", "bin/java", "bin/javac").associate { sdk.resolve(it) to sha(sdk.resolve(it)) }
    private lateinit var base: JavaProjectContextRequest
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var params: JsonObject
    private lateinit var response: JsonObject
    private lateinit var contextHash: String
    private var sourceText = ""

    init {
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>25</maven.compiler.release></properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version><configuration><release>25</release></configuration></plugin></plugins></build></project>\n")
        val body = "new java.io.File(\"" + root.resolve("unexpected-execution").toString().replace("\\", "\\\\") + "\").mkdir();"
        val code = when (shape) {
            in inheritedProfileShapes -> {
                write("src/main/java/demo/Child.java", "package demo; class Parent { public static void main(String[] args) { $body } } class Child extends Parent { static class NoMain {} }\n")
                write("src/main/java/demo/AbstractMain.java", "package demo; abstract class AbstractMain { void main() { $body } }\n")
                write("src/main/java/demo/NeedsArgument.java", "package demo; class NeedsArgument { NeedsArgument(int value) {} void main() { $body } }\n")
                write("src/main/java/cla\u200Css.java", "void main() { $body }\n")
                write("src/test/java/record.java", "void main() { throw new AssertionError(\"unselected test source\"); }\n")
                val signature = when {
                    shape.endsWith("static-array") -> "public static void main(String[] args)"
                    shape.endsWith("static-noarg") -> "public static void main()"
                    else -> "void main()"
                }
                "$signature { $body }\n"
            }
            "compact" -> "void main() { $body }\n"
            "instance" -> "package demo; class Main { protected void main(String[] args) { $body } }\n"
            "inherited", "inherited-classic", "inherited-cursor" -> {
                write("src/main/java/demo/Base.java", "package demo; public class Base { public static void main(String[] args) { $body } }\n")
                "package demo; public class Child extends Base { /*cursor*/ }\n"
            }
            "none" -> "package demo; class Main { void helper() {} }\n"
            "nested-cursor" -> "package demo; class Outer { void main() { $body } static class Nested { protected void main(String[] args) { $body } /*cursor*/ } }\n"
            "outer-cursor" -> "package demo; class Outer { protected void main(String[] args) { $body } static class Nested { void main() { $body } } /*cursor*/ }\n"
            "no-main-cursor" -> "package demo; class Outer { protected void main(String[] args) { $body } static class Nested { void helper() {} /*cursor*/ } }\n"
            "bad-cursor" -> "package demo; class Main { protected void main(String[] args) { $body } }\n"
            "surrogate-cursor" -> "package demo; /*\uD83D\uDE00*/ class Main { protected void main(String[] args) { $body } }\n"
            "global-inherited" -> "package demo; class Base { public void main() { $body } } class Main extends Base {}\n"
            "global-abstract" -> "package demo; abstract class Base { public void main() { $body } } class Main extends Base {}\n"
            "global-constructor" -> "package demo; class Main { Main(int value) {} void main() { $body } } class Other { public static void main(String[] args) { $body } }\n"
            "file-multiple" -> "package demo; class Outer { public static void main(String[] args) { $body } class Nested { void main() { $body } } }\n"
            "global-static" -> "package demo; abstract class Main { private Main() {} public static void main(String[] args) { $body } }\n"
            "global-none" -> "package demo; class Main { void helper() {} } class Other { void helper() {} }\n"
            else -> "package demo; class Main { Main(int value) {} void main() { $body } }\n"
        }
        sourceText = code
        write(path.toString(), code)
        images = Files.walk(root).use { it.filter { p -> Files.isRegularFile(p) }.toList().associateWith(::sha) }
    }

    fun query() {
        snapshot = scanner.scan(root)
        val nativeOwner = snapshot.owningJavaBuildSourceRoots(path).single()
        base = JavaProjectContextRequest(snapshot.hash, sdk, providerId = nativeOwner.providerId,
            moduleId = nativeOwner.module.id, sourceSetId = nativeOwner.sourceSet.id,
            requiredFacts = setOf(JavaProjectContextFact.COMPILER_OPTIONS))
        val acquired = JavaEffectiveProjectContextProvider().read(base, { scanner.scan(root) })
        contextHash = assertIs<JavaProjectContextResolution.Available>(acquired, acquired.toString()).view.contextHash
        base = base.copy(expectedContextHash = contextHash)
        params = buildJsonObject {
            put("requestId", "native-v2"); put("kind", "entryPoints"); put("languageId", "java")
            put("entryPointSchemaVersion", "java-entry-points-v2"); put("expectedSnapshotHash", snapshot.hash)
            put("expectedIndexGeneration", 11); put("launchProfile", profile)
            if (!shape.startsWith("global-") && !shape.startsWith("inherited-mixed-")) put("path", ProtocolPath.serialize(path))
            put("scope", buildJsonObject { put("providerId", nativeOwner.providerId); put("moduleId", nativeOwner.module.id); put("sourceSetId", nativeOwner.sourceSet.id) })
            put("javaContext", buildJsonObject { put("sdkHome", sdk.toString()); put("expectedContextHash", contextHash) })
            if (shape.endsWith("-cursor")) {
                val position = when (shape) {
                    "bad-cursor" -> SourcePosition(10000, 0)
                    "surrogate-cursor" -> TextEdits.rangeForOffset(sourceText, sourceText.indexOf("\uD83D\uDE00") + 1, 0).start
                    else -> TextEdits.rangeForOffset(sourceText, sourceText.indexOf("/*cursor*/") + 3, 0).start
                }
                put("position", buildJsonObject { put("line", position.line); put("character", position.character) })
            }
        }
        response = call(params)
    }

    private fun call(p: JsonObject) = JavaEntryPointQueryProtocol.query(p,
        base.copy(expectedSnapshotHash = p.getValue("expectedSnapshotHash").jsonPrimitive.content,
            expectedContextHash = p.getValue("javaContext").jsonObject.getValue("expectedContextHash").jsonPrimitive.content),
        { scanner.scan(root) }, SemanticCancellationToken.NONE, snapshot.hash, 11)

    fun facts() {
        if (shape in inheritedProfileShapes) {
            inheritedProfileFacts(); validateSchema(response); return
        }
        if (shape.startsWith("global-") || shape == "file-multiple") {
            enumerationFacts(); validateSchema(response); return
        }
        if (shape in setOf("constructor", "bad-cursor", "surrogate-cursor")) {
            val code = if (shape == "constructor") "java.entryPoints.instanceCreationIneligible" else "java.entryPoints.positionUnavailable"
            assertEquals("refused", response.text("status")); assertEquals(code, response.getValue("error").jsonObject.text("code"))
        } else {
            assertEquals("ready", response.text("status"), response.toString()); assertEquals("java-entry-points-v2", response.text("schemaVersion"))
            assertEquals(snapshot.hash, response.text("snapshotHash")); assertEquals(contextHash, response.text("contextHash"))
            assertEquals(11, response.getValue("indexGeneration").jsonPrimitive.int); assertEquals(profile, response.text("launchProfile"))
            assertTrue(response.getValue("complete").jsonPrimitive.boolean); assertTrue(response.getValue("scopeAnalysisComplete").jsonPrimitive.boolean)
            val analysis = response.getValue("analysis").jsonObject
            assertTrue(analysis.text("snapshotHash") != snapshot.hash); assertEquals(25, analysis.getValue("sourceLevel").jsonPrimitive.int)
            val launcher = response.getValue("launcher").jsonObject
            assertEquals(sdk.toString(), launcher.text("sdkHome")); assertEquals(sha(sdk.resolve("bin/java")), launcher.text("executableSha256"))
            assertEquals(25, response.getValue("launcherRelease").jsonPrimitive.int)
            val items = response.getValue("candidates").jsonArray
            assertEquals(if (shape in setOf("none", "no-main-cursor")) 0 else 1, items.size)
            if (shape.endsWith("-cursor")) {
                val selection = response.getValue("selection").jsonObject
                assertEquals(owner, selection.text("binaryName")); assertEquals(params["position"], selection["position"])
                assertEquals(ProtocolPath.serialize(path), selection.text("path"))
            }
            if (items.isNotEmpty()) {
                val item = items.single().jsonObject
                assertEquals(owner, item.text("binaryName")); assertEquals(if (shape.startsWith("inherited")) "demo.Base" else owner, item.text("declaringBinaryName"))
                assertEquals(if (shape.startsWith("inherited")) "static" else "instance", item.text("invocation"))
                assertEquals(if (shape == "compact") "()V" else "([Ljava/lang/String;)V", item.text("descriptor"))
                assertEquals(shape.startsWith("inherited"), item.getValue("inherited").jsonPrimitive.boolean)
                assertEquals(ProtocolPath.serialize(path), item.getValue("source").jsonObject.text("path"))
                val origin = item.getValue("declaration").jsonObject
                assertEquals("source", origin.text("kind")); assertEquals(if (shape.startsWith("inherited")) "src/main/java/demo/Base.java" else ProtocolPath.serialize(path), origin.text("path"))
                assertEquals(shape.startsWith("inherited"), item["construction"] == null)
            }
        }
        validateSchema(response)
        println("Versioned native protocol: shape=$shape status=${response.text("status")} schema=${response.text("schemaVersion")}")
    }

    private fun inheritedProfileFacts() {
        assertEquals("ready", response.text("status"), response.toString())
        assertEquals("java-entry-points-v2", response.text("schemaVersion"))
        assertEquals("inherited-v1", response.text("launchProfile"))
        assertEquals(snapshot.hash, response.text("snapshotHash")); assertEquals(contextHash, response.text("contextHash"))
        assertTrue(response.getValue("complete").jsonPrimitive.boolean)
        assertTrue(response.getValue("scopeAnalysisComplete").jsonPrimitive.boolean)
        assertEquals(5, response.getValue("analysis").jsonObject.getValue("analyzedSourceCount").jsonPrimitive.int)
        val expected = (if (shape.startsWith("inherited-mixed-")) listOf("demo.Child", "demo.Parent") else emptyList()) +
            if (shape.endsWith("static-array")) listOf("record") else emptyList()
        val candidates = response.getValue("candidates").jsonArray
        assertEquals(expected, candidates.map { it.jsonObject.text("binaryName") })
        assertEquals(expected.size, response.getValue("totalEligibleCount").jsonPrimitive.int)
        assertEquals(expected.size, response.getValue("returnedCount").jsonPrimitive.int)
        assertEquals(0, response.getValue("ineligibleCount").jsonPrimitive.int)
        assertTrue(response.getValue("ineligibleTypes").jsonArray.isEmpty())
        for (candidate in candidates) {
            val item = candidate.jsonObject
            val name = item.text("binaryName")
            assertEquals(if (name == "record") "record" else "demo.Parent", item.text("declaringBinaryName"))
            assertEquals("static", item.text("invocation")); assertEquals("([Ljava/lang/String;)V", item.text("descriptor"))
            assertEquals(name == "demo.Child", item.getValue("inherited").jsonPrimitive.boolean)
            assertNull(item["construction"])
            val source = item.getValue("source").jsonObject
            assertEquals(if (name == "record") "compact" else "declared", source.text("form"))
            assertEquals(if (name == "record") "src/main/java/record.java" else "src/main/java/demo/Child.java", source.text("path"))
            assertEquals("source", item.getValue("declaration").jsonObject.text("kind"))
        }
    }

    private fun enumerationFacts() {
        assertEquals("ready", response.text("status"), response.toString())
        assertEquals("java-entry-points-v2", response.text("schemaVersion"))
        assertTrue(response.getValue("complete").jsonPrimitive.boolean)
        val expected = when (shape) {
            "global-inherited" -> listOf("demo.Base", "demo.Main")
            "global-constructor" -> listOf("demo.Other")
            "file-multiple" -> listOf("demo.Outer")
            "global-none" -> emptyList()
            else -> listOf("demo.Main")
        }
        val excluded = when (shape) {
            "global-abstract" -> listOf("demo.Base")
            "global-constructor" -> listOf("demo.Main")
            "file-multiple" -> listOf("demo.Outer\$Nested")
            else -> emptyList()
        }
        assertEquals(expected, response.getValue("candidates").jsonArray.map { it.jsonObject.text("binaryName") })
        assertEquals(expected.size, response.getValue("totalEligibleCount").jsonPrimitive.int)
        assertEquals(excluded.size, response.getValue("ineligibleCount").jsonPrimitive.int)
        assertFalse(response.getValue("ineligibleTypesTruncated").jsonPrimitive.boolean)
        val ineligible = response.getValue("ineligibleTypes").jsonArray
        assertEquals(excluded, ineligible.map { it.jsonObject.text("binaryName") })
        for (item in ineligible) assertEquals("java.entryPoints.instanceCreationIneligible", item.jsonObject.text("reason"))
    }

    fun controls() {
        val noVersion = call(JsonObject(params - "entryPointSchemaVersion"))
        assertEquals("java.entryPoints.profileUnqualified", noVersion.getValue("error").jsonObject.text("code"))
        val classic = call(JsonObject((params - "entryPointSchemaVersion") + ("launchProfile" to JsonPrimitive("classic-declared-v1"))))
        if (shape in inheritedProfileShapes) {
            assertEquals("java.entryPoints.analysisUnavailable", classic.getValue("error").jsonObject.text("code"))
        } else if (shape in setOf("bad-cursor", "surrogate-cursor")) {
            assertEquals("java.entryPoints.positionUnavailable", classic.getValue("error").jsonObject.text("code"))
        } else {
            assertEquals("java-entry-points-v1", classic.text("schemaVersion")); assertFalse(classic.getValue("complete").jsonPrimitive.boolean)
        }
        val repeated = call(JsonObject(params + ("requestId" to JsonPrimitive("native-v2-repeat"))))
        assertEquals(response["candidates"], repeated["candidates"]); assertEquals(response["provenanceHash"], repeated["provenanceHash"])
        assertEquals(response["ineligibleTypes"], repeated["ineligibleTypes"])
        val stale = call(JsonObject(params + ("expectedSnapshotHash" to JsonPrimitive("0".repeat(64)))))
        assertEquals("intelligence.snapshotStale", stale.getValue("error").jsonObject.text("code"))
        val oldContext = params.getValue("javaContext").jsonObject + ("expectedContextHash" to JsonPrimitive("0".repeat(64)))
        val staleSdk = call(JsonObject(params + ("javaContext" to JsonObject(oldContext))))
        assertEquals("java.context.contextStale", staleSdk.getValue("error").jsonObject.text("code"))
        assertEquals(images, images.keys.associateWith(::sha)); assertEquals(sdkImages, sdkImages.keys.associateWith(::sha))
        assertFalse(Files.exists(root.resolve("unexpected-execution"))); assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        Files.walk(root).use { assertFalse(it.anyMatch { p -> p.fileName.toString().endsWith(".class") }) }
    }

    private fun validateSchema(value: JsonObject) {
        val directory = Files.createTempDirectory("rk-v2-schema-")
        try {
            val payload = directory.resolve("response.json"); Files.writeString(payload, value.toString())
            val node = Path.of(requireNotNull(System.getenv("REFACTORKIT_SCHEMA_NODE")))
            val ajv = repository.resolve("qualification/protocol-schema-toolchain/node_modules/ajv2020/dist/2020.js")
            val schema = repository.resolve("docs/api-0.2-intelligence-query-schema.json")
            val program = "const fs=require('fs');const Ajv=require(process.argv[1]).default;const schema=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));const data=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));const a=new Ajv({strict:false,allErrors:true,validateSchema:true,coerceTypes:false});const v=a.compile(schema);if(!v(data))throw new Error(JSON.stringify(v.errors));if(v({...data,unrecognized:true}))throw new Error('open response');"
            ExternalSemanticProcessManager(1).use { manager -> manager.launch(SemanticProcessSpec("v2-schema", node,
                listOf("-e", program, ajv.toString(), schema.toString(), payload.toString()), directory)).use { process ->
                process.input.close(); assertTrue(process.awaitExit(20_000)); assertEquals(0, process.exitCode, process.stderrText())
            } }
        } finally { Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun write(path: String, text: String) { val p = root.resolve(path); Files.createDirectories(p.parent); Files.writeString(p, text) }
    override fun close() { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    companion object {
        private val inheritedProfileShapes = setOf("inherited-mixed-instance", "inherited-mixed-static-array", "inherited-mixed-static-noarg",
            "inherited-compact-instance", "inherited-compact-static-array", "inherited-compact-static-noarg")
        private fun sha(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    }
}
