package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.*

internal fun optionalNativeJavaSdkHome(variable: String): Path {
    val configured = System.getenv(variable)?.takeIf(String::isNotBlank)
    org.junit.jupiter.api.Assumptions.assumeTrue(configured != null, "Set $variable to enable this native qualification")
    return Path.of(requireNotNull(configured)).toRealPath()
}

class JavaSdk27NativeApiSteps {
    private val fixture = JavaSdk27NativeApiFixture()
    private var schemaFixture: JavaSdk27SchemaFixture? = null

    @Given("a native SDK{int} context selects public protocol {string}")
    fun schema(sdkRelease: Int, version: String) { schemaFixture = JavaSdk27SchemaFixture(version, sdkRelease = sdkRelease) }

    @Given("a Maven source26 context selects public protocol {string}")
    fun sourceBound(version: String) { schemaFixture = JavaSdk27SchemaFixture(version, 26) }

    @Then("legacy source26 is refused while versioned metadata retains the declared source level")
    fun verifySourceBound() = requireNotNull(schemaFixture).verifySourceBound()

    @When("the native daemon projects that versioned SDK{int} context")
    fun projectSchema(sdkRelease: Int) = requireNotNull(schemaFixture).project(sdkRelease)

    @Then("the API27 profile satisfies its unchanged Draft 2020-12 context schema and retained bounds")
    fun verifySchema() = requireNotNull(schemaFixture).verify()

    @Then("the native API26 context reports {string} within its unchanged Draft 2020-12 schema")
    fun verifySdk26(outcome: String) = requireNotNull(schemaFixture).verify(outcome)

    @Given("a native SDK27 Java compiler context fixture uses {string}")
    fun capture(mode: String) = fixture.capture(mode)

    @When("its saved sources and closed editor drafts are diagnosed with the acquired context")
    fun query() = fixture.query()

    @Then("SDK27 compiler authority reports {string} without promoting source27 grammar")
    fun verify(outcome: String) = fixture.verify(outcome)

    @Then("SDK{int} context queries preserve project and SDK bytes and retain stale authority guards")
    fun intact(sdkRelease: Int) { schemaFixture?.verifyInputs(sdkRelease) ?: fixture.verifyInputs() }

    @After
    fun close() { schemaFixture?.close(); fixture.close() }
}

/** Scenario-owned schema fixture; the validator is explicitly configured test infrastructure. */
private class JavaSdk27SchemaFixture(private val version: String, private val sourceLevel: Int = 17,
    private val sdkRelease: Int = 27) : AutoCloseable {
    private val sdk = optionalNativeJavaSdkHome(
        if (sdkRelease == 26) "REFACTORKIT_JDK26_HOME" else "REFACTORKIT_SDK27_HOME",
    )
    private val owned = Files.createTempDirectory("rk-sdk27-context-schema-")
    private val root = owned.resolve("workspace").also(Files::createDirectories)
    private val node = System.getenv("REFACTORKIT_SCHEMA_NODE") ?: "node"
    private val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
    private val ajv = Path.of(System.getenv("REFACTORKIT_SCHEMA_AJV2020_MODULE")
        ?: repository.resolve("qualification/protocol-schema-toolchain/node_modules/ajv2020").toString()).toRealPath()
    private val validatorImages = listOf(ajv.resolve("package.json"), ajv.resolve("dist/2020.js"),
        repository.resolve("docs/api-0.2-project-context-$version-schema.json")).associateWith(::sha)
    private val images: Map<Path, String>
    private val sdkImages: Map<Path, String>
    private var native: NativeJavaDaemonRpcSession? = null
    private lateinit var context: JsonObject
    private lateinit var params: JsonObject
    private lateinit var method: String

    init {
        require(sdkRelease in setOf(26, 27))
        require(version in setOf("v2", "v3", "v4", "v5", "v6", "v7"))
        val pom = root.resolve("pom.xml")
        Files.writeString(pom, "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>schema</artifactId><version>1</version><properties><maven.compiler.source>$sourceLevel</maven.compiler.source><maven.compiler.target>$sourceLevel</maven.compiler.target></properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin></plugins></build></project>\n")
        val source = root.resolve("src/main/java/demo/ApiUse.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, "package demo; class ApiUse { java.lang.classfile.ClassFile api = java.lang.classfile.ClassFile.of(); }\n")
        images = listOf(pom, source).associateWith(::sha)
        sdkImages = listOf("release", "lib/ct.sym", "lib/modules", "lib/jrt-fs.jar", "lib/src.zip").associate { sdk.resolve(it) to sha(sdk.resolve(it)) }
    }

    fun project(expectedSdkRelease: Int) {
        assertEquals(sdkRelease, expectedSdkRelease)
        val active = NativeJavaDaemonRpcSession(root).also { native = it }
        val local = owned.resolve("repository").also(Files::createDirectories)
        val opened = active.dispatch("project.open", buildJsonObject {
            put("root", root.toString()); put("mavenRepository", local.toString()); put("resolveDependencies", false)
        })
        val summary = active.dispatch("project.summary", buildJsonObject {})
        val module = summary.getValue("buildModels").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "maven-effective-v1" }
            .getValue("modules").jsonArray.single().jsonObject.getValue("id")
        method = if (version in setOf("v2", "v3")) "project.context" else "project.context.$version"
        params = buildJsonObject {
            put("expectedSnapshotHash", opened.getValue("snapshotHash")); put("sdkHome", sdk.toString())
            if (version != "v2") { put("providerId", "maven-effective-v1"); put("moduleId", module); put("sourceSetId", "main") }
        }
        context = active.dispatch(method, params)
    }

    fun verify(outcome: String = "available") {
        require(outcome in setOf("available", "legacy API refused"))
        assertEquals(if (outcome == "available") "available" else "refused", context.getValue("status").jsonPrimitive.content, context.toString())
        assertEquals("java-project-context-$version", context.getValue("schemaVersion").jsonPrimitive.content)
        if (outcome == "legacy API refused") {
            assertEquals("java.platform.compilerApiUnqualified", context.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(JsonNull, context.getValue("contextHash"))
            assertTrue(context.getValue("sdk").jsonArray.isEmpty()); assertTrue(context.getValue("modules").jsonArray.isEmpty())
        } else {
            assertTrue(context.getValue("sdk").jsonArray.isNotEmpty())
            assertTrue(context.getValue("sdk").jsonArray.all { it.jsonObject.getValue("sourceRelease").jsonPrimitive.int == sdkRelease })
            assertTrue(context.getValue("modules").jsonArray.flatMap { it.jsonObject.getValue("sourceSets").jsonArray }
                .all { it.jsonObject.getValue("sourceLevel").jsonPrimitive.int == 17 })
        }
        val response = owned.resolve("response.json").also { Files.writeString(it, context.toString()) }
        val script = owned.resolve("validate.cjs").also { Files.writeString(it, validator) }
        val log = owned.resolve("schema.log")
        val process = ProcessBuilder(node, script.toString(), ajv.toString(),
            repository.resolve("docs/api-0.2-project-context-$version-schema.json").toString(), response.toString(), version)
            .redirectErrorStream(true).redirectOutput(log.toFile()).apply {
                environment().remove("NODE_OPTIONS"); environment().remove("NODE_PATH")
            }.start()
        if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Schema validator timed out") }
        assertEquals(0, process.exitValue(), Files.readString(log))
        assertTrue(Files.readString(log).contains("PASS: unchanged Draft2020-12 schemas"),
            "The public schema must be validated without a Draft7 projection: " + Files.readString(log))
    }

    fun verifyInputs(expectedSdkRelease: Int) {
        assertEquals(sdkRelease, expectedSdkRelease)
        val active = requireNotNull(native)
        val refused = context.getValue("status").jsonPrimitive.content == "refused"
        val key = if (refused) "expectedSnapshotHash" else "expectedContextHash"
        val stale = active.dispatch(method, JsonObject(params + (key to JsonPrimitive("0".repeat(64)))))
        assertEquals(if (refused) "java.context.snapshotStale" else "java.context.contextStale",
            stale.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(images, images.keys.associateWith(::sha)); assertEquals(sdkImages, sdkImages.keys.associateWith(::sha))
        assertEquals(validatorImages, validatorImages.keys.associateWith(::sha))
        assertFalse(Files.exists(root.resolve("target"))); assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    fun verifySourceBound() {
        assertEquals(26, sourceLevel)
        if (version in setOf("v2", "v3", "v4", "v5")) {
            assertEquals("refused", context.getValue("status").jsonPrimitive.content, context.toString())
            assertEquals("java.context.sourceLevelUnsupported", context.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
            assertTrue(context.getValue("sdk").jsonArray.isEmpty()); assertTrue(context.getValue("modules").jsonArray.isEmpty())
        } else {
            assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
            assertTrue(context.getValue("modules").jsonArray.flatMap { it.jsonObject.getValue("sourceSets").jsonArray }
                .all { it.jsonObject.getValue("sourceLevel").jsonPrimitive.int == 26 }, "Source26 must not be relabeled as25")
            val analysis = context.getValue("semanticAnalysis").jsonObject
            assertEquals(if (version == "v6") "unqualified" else "available", analysis.getValue("availability").jsonPrimitive.content)
            if (version == "v6") assertEquals("java.context.sourceAnalysisUnqualified", analysis.getValue("refusalCode").jsonPrimitive.content)
        }
    }

    private fun sha(path: Path): String = Files.newInputStream(path).use { stream ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val size = stream.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun close() { native?.close(); Files.walk(owned).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }

    companion object {
        private val validator = """
const fs=require('fs'),assert=require('assert'),crypto=require('crypto');
const Ajv2020=require(process.argv[2]+'/dist/2020').default;
assert.strictEqual(require(process.argv[2]+'/package.json').version,'8.20.0','Pinned Ajv2020 is required');
const schemaBytes=fs.readFileSync(process.argv[3],'utf8'),schema=JSON.parse(schemaBytes);
const context=JSON.parse(fs.readFileSync(process.argv[4],'utf8')),originalContext=JSON.stringify(context);
const dialect='https://json-schema.org/draft/2020-12/schema';
assert.strictEqual(schema[String.fromCharCode(36)+'schema'],dialect,'Validate the original public dialect');
const ajv=new Ajv2020({allErrors:true,strictSchema:true,strictTypes:false,
 coerceTypes:false,useDefaults:false,removeAdditional:false});
const valid=ajv.compile(schema);
assert(valid(context),JSON.stringify(valid.errors));
function copy(){return JSON.parse(JSON.stringify(context));}
let unknown=copy();unknown.unknownContextField=true;assert(!valid(unknown),'Closed context must reject unknown fields');
if(context.status==='available'){
 let future=copy();future.sdk[0].sourceRelease=28;assert(!valid(future),'Future API28 must not be admitted');
 if(['v2','v3','v4','v5'].includes(process.argv[5])){
  let unqualified=copy();unqualified.sdk[0].sourceRelease=26;assert(!valid(unqualified),'Legacy native API26 remains unqualified');
  let grammar=copy();grammar.modules[0].sourceSets[0].sourceLevel=27;assert(!valid(grammar),'API27 cannot widen legacy source grammar');
 }
}else{
 assert.strictEqual(context.status,'refused');assert.strictEqual(context.sdk.length,0);
 let forged=copy();forged.status='available';assert(!valid(forged),'Refused authority cannot masquerade as available');
}
const keywordControl={type:'object',properties:{tuple:{type:'array',prefixItems:[{const:'head'}],items:false},gate:{}},
 dependentRequired:{gate:['tuple']},unevaluatedProperties:false};
keywordControl[String.fromCharCode(36)+'schema']=dialect;
const control=ajv.compile(keywordControl);
assert(control({gate:true,tuple:['head']}));
assert(!control({gate:true}),'Draft2020 dependentRequired must remain effective');
assert(!control({tuple:['wrong']}),'Draft2020 prefixItems must remain effective');
assert(!control({tuple:['head','extra']}),'Draft2020 items must reject extra tuple entries');
assert(!control({tuple:['head'],extra:true}),'Draft2020 unevaluatedProperties must remain effective');
assert.strictEqual(JSON.stringify(context),originalContext,'Validation must not coerce native context data');
assert.strictEqual(fs.readFileSync(process.argv[3],'utf8'),schemaBytes,'Public schema bytes must remain unchanged');
console.log('PASS: unchanged Draft2020-12 schemas; Ajv 8.20.0; '+process.argv[5]+'; SHA256 '+
 crypto.createHash('sha256').update(schemaBytes).digest('hex')+'; native bounds and modern keyword controls');
""".trimIndent()
    }
}

private class JavaSdk27NativeApiFixture : AutoCloseable {
    private lateinit var owned: Path
    private lateinit var root: Path
    private lateinit var sdk: Path
    private lateinit var native: NativeJavaDaemonRpcSession
    private lateinit var mode: String
    private lateinit var provider: String
    private var gradleExecutable: Path? = null
    private var gradleSha: String? = null
    private lateinit var snapshot: JsonElement
    private lateinit var context: JsonObject
    private lateinit var inputs: Map<String, String>
    private lateinit var sdkInputs: Map<String, String>
    private lateinit var saved: JsonObject
    private var savedDiagnostics: JsonArray? = null
    private lateinit var overlay: JsonObject
    private var broken: JsonObject? = null
    private var stale: JsonObject? = null
    private var oracleExit = -1
    private val main = "src/main/java/demo/Runner.java"
    private val helper = "src/main/java/demo/Helper.java"
    private val savedMain = "package demo; import java.lang.classfile.ClassFile; class Runner { ClassFile api = Helper.oldMessage(); }\n"
    private val savedHelper = "package demo; import java.lang.classfile.ClassFile; class Helper { static ClassFile oldMessage() { return ClassFile.of(); } }\n"
    private val draftMain = "package demo; /* 💡 */ import java.lang.classfile.ClassFile; class Runner { ClassFile api = Helper.newMessage(); }\n"
    private val draftHelper = "package demo; import java.lang.classfile.ClassFile; class Helper { static ClassFile newMessage() { return ClassFile.of(); } }\n"

    fun capture(mode: String) {
        provider = when {
            mode.startsWith("Gradle ") -> "gradle"
            mode.startsWith("declared ") -> "declared"
            else -> "maven"
        }
        this.mode = mode.removePrefix("Gradle ").removePrefix("declared ")
        require(this.mode in setOf("source target8", "source target17", "source target25", "release17", "custom system", "source target27"))
        require(provider != "declared" || this.mode == "release17")
        require(provider != "gradle" || this.mode != "custom system")
        sdk = optionalNativeJavaSdkHome("REFACTORKIT_SDK27_HOME")
        assertTrue(Files.readString(sdk.resolve("release")).contains("JAVA_VERSION=\"27.0.1.0.2\""), "Exact qualified SDK27 is required")
        owned = Files.createTempDirectory("rk-sdk27-native-api-")
        root = owned.resolve("workspace").also(Files::createDirectories)
        val level = level()
        val properties = if (this.mode == "release17") "<maven.compiler.release>17</maven.compiler.release>"
            else "<maven.compiler.source>$level</maven.compiler.source><maven.compiler.target>$level</maven.compiler.target>"
        val configuration = if (this.mode == "custom system")
            "<configuration><compilerArgs><arg>--system</arg><arg>none</arg></compilerArgs></configuration>" else ""
        when (provider) {
            "gradle" -> configureGradle()
            "declared" -> configureDeclaration()
            else -> write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>app</artifactId><version>1</version><properties>$properties</properties><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version>$configuration</plugin></plugins></build></project>\n")
        }
        write(main, savedMain); write(helper, savedHelper)
        inputs = images(root)
        sdkInputs = sdkImages()
        val repository = owned.resolve("repository").also(Files::createDirectories)
        native = NativeJavaDaemonRpcSession(root)
        snapshot = native.dispatch("project.open", buildJsonObject {
            put("root", root.toString()); put("mavenRepository", repository.toString()); put("resolveDependencies", false)
        }).getValue("snapshotHash")
        if (provider == "gradle") {
            val sync = native.dispatch("project.sync", buildJsonObject {
                put("buildSystem", "gradle"); put("expectedSnapshotHash", snapshot); put("sdkHome", sdk.toString())
                put("gradleRuntimeHome", System.getProperty("java.home")); put("gradleExecutable", requireNotNull(gradleExecutable).toString())
                put("gradleUserHome", root.resolve(".refactorkit/gradle-home").toString()); put("allowBuildExecution", true)
            })
            assertEquals("synchronized", sync.getValue("status").jsonPrimitive.content, sync.toString())
            assertEquals("9.8.1", sync.getValue("gradleVersion").jsonPrimitive.content)
            snapshot = sync.getValue("snapshotHash")
        }
        val metadata = this.mode == "source target27"
        val providerId = if (provider == "gradle") "gradle-effective-v1" else "maven-effective-v1"
        val module = if (metadata) native.dispatch("project.summary", buildJsonObject {}).getValue("buildModels").jsonArray
            .map { it.jsonObject }.single { it.getValue("providerId").jsonPrimitive.content == providerId }
            .getValue("modules").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content else null
        context = native.dispatch(if (metadata) "project.context.v7" else "project.context", buildJsonObject {
            put("expectedSnapshotHash", snapshot); put("sdkHome", sdk.toString())
            if (metadata) { put("providerId", providerId); put("moduleId", requireNotNull(module)); put("sourceSetId", "main") }
        })
        if (this.mode != "custom system") compileOracle()
    }

    fun query() {
        if (context.getValue("status").jsonPrimitive.content != "available") return
        if (mode == "source target27") {
            saved = native.dispatch("intelligence.query", JsonObject(params("saved-snapshot", 0) + mapOf(
                "kind" to JsonPrimitive("definition"), "position" to buildJsonObject {
                    put("line", 0); put("character", savedMain.indexOf("Runner") + 1)
                })))
        } else {
            val reply = native.raw("diagnostics", buildJsonObject {
                put("languageId", "java"); put("jdkHome", sdk.toString()); put("verbose", true)
            }, JsonPrimitive("sdk27-saved-diagnostics"))
            assertTrue(reply["error"] == null || reply["error"] == JsonNull, reply.toString())
            savedDiagnostics = reply.getValue("result").jsonArray
        }
        overlay = native.dispatch("intelligence.query", params("immutable-editor-overlay", 7))
        if (mode.startsWith("source target") && mode != "source target27") {
            broken = native.dispatch("intelligence.query", params("immutable-editor-overlay", 8, true))
        }
        if (mode != "source target27") {
            val good = params("immutable-editor-overlay", 9)
            val selection = good.getValue("javaContext").jsonObject
            stale = native.dispatch("intelligence.query", JsonObject(good + ("javaContext" to
                JsonObject(selection + ("expectedContextHash" to JsonPrimitive("0".repeat(64)))))))
            val fresh = native.dispatch("intelligence.query", good)
            assertEquals("ready", fresh.getValue("status").jsonPrimitive.content, fresh.toString())
        }
    }

    fun verify(expected: String) {
        if (expected == "java.platform.compilerApiUnqualified") {
            assertEquals("refused", context.getValue("status").jsonPrimitive.content, context.toString())
            assertEquals(expected, context.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
            assertTrue(context.getValue("sdk").jsonArray.isEmpty())
            return
        }
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        val authorities = context.getValue("sdk").jsonArray.map { it.jsonObject }
        assertEquals(setOf(if (mode == "release17") 17 else 27), authorities.map { it.getValue("sourceRelease").jsonPrimitive.int }.toSet())
        assertTrue(authorities.all { it.getValue("platformRelease").jsonPrimitive.int == 27 })
        assertEquals(if (mode == "release17") 1 else 0, oracleExit)
        if (expected == "java.context.sourceAnalysisUnqualified") {
            assertEquals("unqualified", context.getValue("semanticAnalysis").jsonObject.getValue("availability").jsonPrimitive.content)
            for (result in listOf(saved, overlay)) {
                assertEquals("refused", result.getValue("status").jsonPrimitive.content, result.toString())
                assertEquals(expected, result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertNull(result["complete"]); assertNull(result["items"])
            }
            return
        }
        val sourceSets = context.getValue("modules").jsonArray.flatMap { it.jsonObject.getValue("sourceSets").jsonArray }.map { it.jsonObject }
        assertTrue(sourceSets.all { it.getValue("sourceLevel").jsonPrimitive.int == level() })
        assertTrue(sourceSets.all { it.getValue("compilerOptionsAvailability").jsonPrimitive.content == "complete" })
        val savedErrors = requireNotNull(savedDiagnostics)
        if (mode == "release17") {
            assertTrue(savedErrors.isNotEmpty(), "Saved release17 diagnostics must reject newer APIs")
            assertTrue(savedErrors.all { it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR" })
        } else assertTrue(savedErrors.isEmpty(), savedErrors.toString())
        for (result in listOf(overlay)) {
            assertEquals("ready", result.getValue("status").jsonPrimitive.content, result.toString())
            assertEquals(true, result.getValue("complete").jsonPrimitive.boolean)
            assertEquals("compiler-errors", result.getValue("coverage").jsonPrimitive.content)
            assertEquals(context.getValue("contextHash"), result.getValue("contextHash"))
            assertEquals(JsonArray(authorities.map { it.getValue("identitySha256") }), result.getValue("platformIdentities"))
            assertEquals(2, result.getValue("analyzedSourceCount").jsonPrimitive.int)
            val diagnostics = result.getValue("items").jsonArray
            assertEquals(diagnostics.size, result.getValue("total").jsonPrimitive.int)
            if (mode == "release17") {
                assertNotEquals(0, oracleExit)
                assertTrue(diagnostics.isNotEmpty(), "Historical release17 must not see newer native SDK APIs")
                assertTrue(diagnostics.all { it.jsonObject.getValue("severity").jsonPrimitive.content == "error" })
            } else assertTrue(diagnostics.isEmpty(), result.toString())
        }
        if (expected == "native SDK27 compiler diagnostics") {
            val result = requireNotNull(broken)
            assertEquals("ready", result.getValue("status").jsonPrimitive.content, result.toString())
            assertEquals(true, result.getValue("complete").jsonPrimitive.boolean)
            val errors = result.getValue("items").jsonArray.map { it.jsonObject }
            assertEquals(1, errors.size, result.toString())
            assertEquals(helper, errors.single().getValue("location").jsonObject.getValue("path").jsonPrimitive.content)
            assertTrue(errors.single().getValue("message").jsonPrimitive.content.contains("doesNotExist"), errors.toString())
        }
        val documents = overlay.getValue("sourceAuthority").jsonObject.getValue("documents").jsonArray.map { it.jsonObject }
        assertEquals(setOf(main, helper), documents.map { it.getValue("path").jsonPrimitive.content }.toSet())
        assertTrue(documents.all { it.getValue("version").jsonPrimitive.int == 7 })
    }

    fun verifyInputs() {
        if (mode !in setOf("custom system", "source target27")) {
            val refused = requireNotNull(stale)
            assertEquals("refused", refused.getValue("status").jsonPrimitive.content, refused.toString())
            assertEquals("java.context.contextStale", refused.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertNull(refused["complete"]); assertNull(refused["items"])
        }
        val staleSnapshot = native.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", "0".repeat(64)); put("sdkHome", sdk.toString())
        })
        assertEquals("java.context.snapshotStale", staleSnapshot.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(inputs, images(root)); assertEquals(sdkInputs, sdkImages())
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertFalse(Files.exists(root.resolve("target")))
        assertFalse(Files.exists(root.resolve("build/classes")))
        gradleExecutable?.let {
            assertEquals(gradleSha, sha(it))
            assertEquals("1", Files.readString(root.resolve(".refactorkit/probe/evaluations.txt")).trim())
            assertEquals(":refactorkitEffectiveContext_v1", Files.readString(root.resolve(".refactorkit/probe/tasks.txt")))
        }
    }

    private fun configureGradle() {
        gradleExecutable = Path.of(System.getenv("REFACTORKIT_GRADLE_9_8_1_EXECUTABLE")
            ?: "/tmp/ide-gradle-9.8.1/gradle-9.8.1/bin/gradle").toRealPath()
        assertTrue(Files.isExecutable(requireNotNull(gradleExecutable)))
        gradleSha = sha(requireNotNull(gradleExecutable))
        write("settings.gradle", "rootProject.name='sdk27-context'\n")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx256m\n")
        val release = if (mode == "release17") "options.release = 17" else ""
        write("build.gradle", """plugins { id 'java' }
java { toolchain { languageVersion = JavaLanguageVersion.of(27) } }
tasks.withType(JavaCompile).configureEach { sourceCompatibility = '${level()}'; targetCompatibility = '${level()}'; $release }
def receipt = file('.refactorkit/probe/evaluations.txt')
receipt.parentFile.mkdirs()
receipt.text = ((receipt.exists() ? receipt.text.trim().toInteger() : 0) + 1).toString()
gradle.taskGraph.whenReady { graph ->
 if (graph.allTasks.any { it instanceof JavaCompile }) throw new GradleException('Project compilation is forbidden')
 file('.refactorkit/probe/tasks.txt').text = graph.allTasks.collect { it.path }.join('\n')
}
""")
    }

    private fun configureDeclaration() = write(".refactorkit/java-project.json", buildJsonObject {
        put("schemaVersion", "java-declared-project-v1"); put("sdkHome", sdk.toString())
        put("modules", buildJsonArray { add(buildJsonObject {
            put("id", "app"); put("root", "."); put("sourceSets", buildJsonArray { add(buildJsonObject {
                put("id", "main"); put("kind", "main"); put("release", 17); put("proc", "none")
                put("sourceRoots", buildJsonArray { add("src/main/java") })
                for (name in listOf("generatedSourceRoots", "outputDirectories", "compileClasspath", "runtimeClasspath")) put(name, buildJsonArray {})
            }) })
        }) })
    }.toString() + "\n")

    private fun compileOracle() {
        val directory = owned.resolve("oracle").also(Files::createDirectories)
        val source = directory.resolve("Runner.java")
        Files.writeString(source, "package demo; import java.lang.classfile.ClassFile; class Runner { ClassFile api = ClassFile.of(); }\n")
        val output = directory.resolve("compiler.log")
        val options = if (mode == "release17") listOf("--release", "17") else listOf("-source", level().toString(), "-target", level().toString())
        val process = ProcessBuilder(listOf(sdk.resolve("bin/javac").toString(), "-proc:none") + options +
            listOf("-d", directory.resolve("classes").toString(), source.toString()))
            .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); error("First-party fixture compiler timed out")
        }
        oracleExit = process.exitValue()
        assertEquals(if (mode == "release17") 1 else 0, oracleExit, Files.readString(output))
        if (oracleExit == 0) {
            val bytes = Files.readAllBytes(directory.resolve("classes/demo/Runner.class"))
            assertEquals(44 + level(), (bytes[6].toInt() and 255) * 256 + (bytes[7].toInt() and 255))
        }
    }

    private fun params(kind: String, version: Int, bad: Boolean = false) = buildJsonObject {
        put("requestId", "sdk27-native-api"); put("kind", "diagnostics"); put("languageId", "java"); put("path", main)
        put("limit", 500); put("expectedSnapshotHash", snapshot)
        put("javaContext", buildJsonObject {
            put("sdkHome", sdk.toString()); put("expectedContextHash", context.getValue("contextHash"))
            put("contextSchemaVersion", context.getValue("schemaVersion"))
        })
        put("sourceAuthority", buildJsonObject {
            put("kind", kind)
            if (kind == "immutable-editor-overlay") put("documents", buildJsonArray {
                for ((path, text) in listOf(main to draftMain, helper to
                    if (bad) draftHelper.replace("ClassFile.of()", "ClassFile.doesNotExist()") else draftHelper)) add(buildJsonObject {
                    put("path", path); put("version", version); put("content", text)
                })
            })
        })
    }

    private fun level() = when (mode) { "source target8" -> 8; "source target25" -> 25; "source target27" -> 27; else -> 17 }
    private fun write(path: String, text: String) { val p = root.resolve(path); Files.createDirectories(p.parent); Files.writeString(p, text) }
    private fun sha(path: Path): String = Files.newInputStream(path).use { stream ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun images(directory: Path) = Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it) &&
        (provider != "gradle" || directory.relativize(it).first().toString() !in setOf(".gradle", ".refactorkit")) }.toList().associate {
        directory.relativize(it).toString() to sha(it)
    } }
    private fun sdkImages() = listOf("release", "lib/ct.sym", "lib/modules", "lib/jrt-fs.jar", "lib/src.zip").associateWith { sha(sdk.resolve(it)) }

    override fun close() {
        if (::native.isInitialized) native.close()
        if (::owned.isInitialized) Files.walk(owned).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
