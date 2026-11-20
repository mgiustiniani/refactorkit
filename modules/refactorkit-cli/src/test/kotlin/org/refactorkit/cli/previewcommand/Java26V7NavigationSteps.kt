package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.TextEdits
import org.refactorkit.core.TextEdit
import org.refactorkit.core.SourceRange
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.TransactionId
import org.refactorkit.core.JournalState
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

class Java26V7NavigationSteps {
    private val fixture = Java26V7NavigationFixture()

    @Given("a saved Maven Java 26 v7 navigation workspace with the verified SDK")
    fun capture() = fixture.capture()

    @Given("a synchronized Gradle Java 26 v7 navigation workspace with the verified SDK")
    fun captureGradle() = fixture.captureGradle()

    @When("the IDE requests Java 26 v7 {string} with the acquired context")
    fun query(kind: String) = fixture.query(kind)

    @Then("the Java 26 v7 navigation result retains exact source bindings and SDK authority")
    fun bound() = fixture.assertBound()

    @Then("the Java 26 v7 navigation leaves source build and SDK inputs unchanged")
    fun intact() = fixture.assertIntact()

    @Given("a saved Maven Java 26 v7 diagnostics workspace with {string}")
    fun captureDiagnostics(condition: String) = fixture.captureDiagnostics(condition)

    @Given("a synchronized Gradle Java 26 v7 diagnostics workspace with {string}")
    fun captureGradleDiagnostics(condition: String) = fixture.captureDiagnostics(condition, gradle = true)

    @When("the IDE requests diagnostics using the selected Java 26 SDK")
    fun diagnostics() = fixture.diagnostics()

    @Then("the Java 26 diagnostics match the captured platform API boundary")
    fun diagnosticBoundary() = fixture.assertDiagnostics()

    @When("the IDE requests a managed Java 26 v7 class rename with {string}")
    fun rename(authority: String) = fixture.rename(authority)

    @Then("the Java 26 v7 class rename reports {string} with exact transaction images")
    fun renamed(expected: String) = fixture.assertRename(expected)

    @Then("the Java 26 v7 managed rename preserves input identities and the expected journal")
    fun renameInputs() = fixture.assertIntact(journalExpected = fixture.renameCompleted())

    @Given("a verified SDK26 {string} editor workspace selects {string} context for release {string}")
    fun captureEditor(provider: String, schema: String, release: String) = fixture.captureEditor(provider, schema, release.toInt())

    @When("the IDE attempts its SDK26 editor rename with the acquired context")
    fun editorPreview() = fixture.editorPreview()

    @Then("the SDK26 editor workflow reports {string} with exact context authority")
    fun editorResult(expected: String) = fixture.assertEditorWorkflow(expected)

    @Then("the SDK26 editor preview preserves saved inputs and SDK authority")
    fun editorIntact() = fixture.assertEditorIntact()

    @After
    fun cleanup() = fixture.close()
}

/** The fixture owns source and runtime observations; protocol glue only delegates. */
private class Java26V7NavigationFixture : AutoCloseable {
    private var root: Path? = null
    private var session: GradleContextRpcSession? = null
    private var gradleUserHome: Path? = null
    private var gradleExecutable: Path? = null
    private var providerId = "maven-effective-v1"
    private lateinit var sdk: Path
    private lateinit var sdkBefore: Map<String, String>
    private lateinit var originals: Map<String, String>
    private lateinit var snapshot: String
    private lateinit var moduleId: String
    private lateinit var context: JsonObject
    private lateinit var result: JsonObject
    private lateinit var kind: String
    private lateinit var diagnosticCondition: String
    private lateinit var diagnosticRows: List<JsonObject>
    private lateinit var renameCase: String
    private var renameRefusal: JsonRpcException? = null
    private var renameObserved = ""
    private lateinit var editorSchema: String
    private var editorRelease = 26
    private lateinit var editorResult: JsonObject
    private var editorMutationRequested = false
    private val helper = "src/main/java/fixture/Helper.java"
    private val caller = "src/main/java/fixture/Use.java"

    fun capture(additionalSources: Map<String, String> = emptyMap(), release: Int = 26) {
        require(release in setOf(17, 26))
        val configuredSdk = System.getenv("REFACTORKIT_JDK26_HOME")?.takeIf(String::isNotBlank)
        assumeTrue(configuredSdk != null, "Set REFACTORKIT_JDK26_HOME to run optional native SDK26 qualification")
        sdk = Path.of(assertNotNull(configuredSdk,
            "JAVA26_TOOLCHAIN_UNAVAILABLE: verified SDK26 is required; do not count a skipped case as qualification")).toRealPath()
        assertTrue(Files.readString(sdk.resolve("release")).contains("JAVA_VERSION=\"26."), "Expected an actual Java 26 SDK")
        sdkBefore = sdkInputs()
        val workspace = Files.createTempDirectory("refactorkit-java26-v7-navigation-").also { root = it }
        originals = linkedMapOf(
            "pom.xml" to "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>java26</artifactId><version>1</version><properties><maven.compiler.release>$release</maven.compiler.release></properties></project>\n",
            helper to "package fixture;\npublic final class Helper {\n  public int sum(int left, int right) { return left + right; }\n}\n",
            "src/main/java/fixture/Other.java" to "package fixture;\npublic final class Other {\n  public int sum(int left, int right) { return 99; }\n}\n",
            caller to "package fixture;\npublic final class Use {\n  public int run(Helper helper) { return helper.sum(1, 2) + new Other().sum(3, 4); }\n}\n",
        )
        originals = originals + additionalSources
        originals.forEach { (path, text) ->
            val file = workspace.resolve(path); Files.createDirectories(file.parent); Files.writeString(file, text)
        }
        session = newGradleContextRpcSession()
        val opened = requireNotNull(session).dispatch("project.open", buildJsonObject { put("root", workspace.toString()) }).jsonObject
        snapshot = opened.getValue("snapshotHash").jsonPrimitive.content
        moduleId = moduleIdFromSummary()
        context = requireNotNull(session).dispatch("project.context.v7", contextRequest()).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        assertEquals("java-project-context-v7", context.getValue("schemaVersion").jsonPrimitive.content)
    }

    fun captureEditor(provider: String, schema: String, release: Int) {
        require(provider in setOf("Maven", "Gradle") && schema in setOf("v2", "v3", "v7"))
        editorSchema = "java-project-context-$schema"; editorRelease = release
        if (provider == "Gradle") captureGradle(release = release) else capture(release = release)
        context = requireNotNull(session).dispatch(editorContextMethod(), editorContextRequest()).jsonObject
        assertTrue(context.getValue("status").jsonPrimitive.content in setOf("available", "refused"), context.toString())
        assertEquals(editorSchema, context.getValue("schemaVersion").jsonPrimitive.content)
    }

    private fun editorContextMethod(): String = when (editorSchema) {
        "java-project-context-v2" -> "project.context"
        "java-project-context-v3" -> "project.context.aggregate"
        "java-project-context-v7" -> "project.context.v7"
        else -> error("Unqualified editor context schema")
    }

    private fun editorContextRequest(): JsonObject = if (editorSchema == "java-project-context-v7") contextRequest()
        else buildJsonObject { put("expectedSnapshotHash", snapshot); put("sdkHome", sdk.toString()) }

    private fun editorDocuments(): Map<String, Int> = originals.keys.filter { it.endsWith(".java") }
        .sorted().mapIndexed { index, path -> path to (41 + index) }.toMap()

    fun editorPreview() {
        // This consumer cannot request mutation authority from a refused context.
        if (context.getValue("status").jsonPrimitive.content == "refused") return
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        editorMutationRequested = true
        val authority = buildJsonObject {
            put("kind", "immutable-editor-overlay")
            put("documents", JsonArray(editorDocuments().map { (path, version) -> buildJsonObject {
                put("path", path); put("version", version); put("content", originals.getValue(path))
            } }))
        }
        val javaContext = buildJsonObject {
            put("sdkHome", sdk.toString()); put("expectedContextHash", context.getValue("contextHash"))
            put("contextSchemaVersion", editorSchema)
        }
        val position = TextEdits.positionForOffset(originals.getValue(helper), originals.getValue(helper).indexOf("Helper"))
        val selection = requireNotNull(session).dispatch("intelligence.query", buildJsonObject {
            put("requestId", "sdk26-editor-schema"); put("kind", "mutationSelection"); put("languageId", "java")
            put("path", helper); put("expectedSnapshotHash", snapshot); put("javaContext", javaContext)
            put("sourceAuthority", authority)
            put("position", buildJsonObject { put("line", position.line); put("character", position.character) })
        }).jsonObject
        assertEquals("ready", selection.getValue("status").jsonPrimitive.content, selection.toString())
        assertEquals(editorSchema, selection.getValue("contextSchemaVersion").jsonPrimitive.content)
        editorResult = requireNotNull(session).dispatch("refactor.editor.preview", buildJsonObject {
            put("languageId", "java"); put("operation", "renameClass"); put("expectedSnapshotHash", snapshot)
            put("javaContext", javaContext); put("sourceAuthority", authority)
            put("javaEditorSelection", selection.getValue("selection")); put("editorMutationMode", "durable-editor-owned")
            put("arguments", buildJsonObject { put("newName", "Calculator") })
        }).jsonObject
    }

    fun assertEditorWorkflow(expected: String) {
        when (expected) {
            "semantic preview" -> {
                assertEquals("available", context.getValue("status").jsonPrimitive.content)
                assertTrue(editorMutationRequested)
                assertEditorPreview()
            }
            "source26 refused" -> {
                assertEquals("refused", context.getValue("status").jsonPrimitive.content, context.toString())
                assertEquals("java.context.sourceLevelUnsupported",
                    context.getValue("refusal").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals(JsonNull, context.getValue("contextHash"))
                assertTrue(context.getValue("sdk").jsonArray.isEmpty())
                assertFalse(editorMutationRequested)
                assertFalse(::editorResult.isInitialized)
            }
            else -> error("Unqualified SDK26 editor workflow outcome: $expected")
        }
    }

    private fun assertEditorPreview() {
        val preview = editorResult
        assertEquals("preview", preview.getValue("status").jsonPrimitive.content)
        assertEquals("JDT_BINDING", preview.getValue("evidence").jsonPrimitive.content)
        assertEquals(context.getValue("contextHash"), preview.getValue("contextHash"))
        assertEquals(editorSchema, preview.getValue("contextSchemaVersion").jsonPrimitive.content,
            "The editor response must attest the context actually acquired, including v3/v7")
        assertEquals("editor", preview.getValue("editOwnership").jsonPrimitive.content)
        assertEquals("true", preview.getValue("applyAvailable").jsonPrimitive.content)
        assertTrue(preview.getValue("diagnosticsBefore").jsonArray.isEmpty())
        assertTrue(preview.getValue("diagnosticsAfterPreview").jsonArray.isEmpty())
        assertEquals(context.getValue("sdk").jsonArray.map { it.jsonObject.getValue("identitySha256") },
            preview.getValue("platformIdentities").jsonArray.toList())
        context.getValue("sdk").jsonArray.forEach { row ->
            val platform = row.jsonObject
            assertEquals(editorRelease, platform.getValue("sourceRelease").jsonPrimitive.content.toInt())
            assertEquals(26, platform.getValue("platformRelease").jsonPrimitive.content.toInt())
            assertEquals(sdk, Path.of(platform.getValue("home").jsonPrimitive.content).toRealPath())
        }
        val workspace = requireNotNull(root)
        fun relative(uri: String): String {
            val path = Path.of(URI.create(uri)).normalize()
            assertTrue(path.startsWith(workspace))
            return workspace.relativize(path).toString().replace('\\', '/')
        }
        val observed = originals.filterKeys { it.endsWith(".java") }.toMutableMap()
        var renames = 0
        preview.getValue("workspaceEdit").jsonObject.getValue("documentChanges").jsonArray.forEach { entry ->
            val change = entry.jsonObject
            if (change["kind"]?.jsonPrimitive?.content == "rename") {
                val from = relative(change.getValue("oldUri").jsonPrimitive.content)
                val to = relative(change.getValue("newUri").jsonPrimitive.content)
                assertEquals(helper, from); assertEquals("src/main/java/fixture/Calculator.java", to)
                assertFalse(observed.containsKey(to)); observed[to] = assertNotNull(observed.remove(from)); renames++
            } else {
                val document = change.getValue("textDocument").jsonObject
                val path = relative(document.getValue("uri").jsonPrimitive.content)
                assertEquals(editorDocuments().getValue(path), document.getValue("version").jsonPrimitive.content.toInt())
                fun point(value: JsonObject) = SourcePosition(value.getValue("line").jsonPrimitive.content.toInt(), value.getValue("character").jsonPrimitive.content.toInt())
                val edits = change.getValue("edits").jsonArray.map { row ->
                    val edit = row.jsonObject; val range = edit.getValue("range").jsonObject
                    TextEdit(SourceRange(point(range.getValue("start").jsonObject), point(range.getValue("end").jsonObject)), edit.getValue("newText").jsonPrimitive.content)
                }
                observed[path] = TextEdits.apply(observed.getValue(path), edits)
            }
        }
        assertEquals(1, renames)
        val golden = originals.filterKeys { it.endsWith(".java") && it != helper && it != caller } + mapOf(
            "src/main/java/fixture/Calculator.java" to "package fixture;\npublic final class Calculator {\n  public int sum(int left, int right) { return left + right; }\n}\n",
            caller to "package fixture;\npublic final class Use {\n  public int run(Calculator helper) { return helper.sum(1, 2) + new Other().sum(3, 4); }\n}\n",
        )
        assertEquals(golden, observed)
    }

    fun assertEditorIntact() {
        val workspace = requireNotNull(root)
        originals.forEach { (path, text) -> assertEquals(text, Files.readString(workspace.resolve(path)), path) }
        assertEquals(sdkBefore, sdkInputs())
        val current = requireNotNull(session).dispatch("project.summary", buildJsonObject {}).jsonObject
        assertEquals(snapshot, current.getValue("snapshotHash").jsonPrimitive.content)
        val retained = requireNotNull(session).dispatch(editorContextMethod(), editorContextRequest()).jsonObject
        assertEquals(context.getValue("status"), retained.getValue("status"), retained.toString())
        assertEquals(context.getValue("schemaVersion"), retained.getValue("schemaVersion"))
        assertEquals(context.getValue("contextHash"), retained.getValue("contextHash"))
        assertEquals(context["refusal"], retained["refusal"])
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        val transactions = requireNotNull(session).dispatch("refactor.editor.transactions", buildJsonObject {}).jsonObject
        assertTrue(transactions.getValue("transactions").jsonArray.isEmpty(), transactions.toString())
    }

    fun query(requested: String) {
        require(requested in setOf("definition", "references")); kind = requested
        val path = if (kind == "definition") caller else helper
        val text = originals.getValue(path)
        val position = TextEdits.positionForOffset(text, text.indexOf("sum("))
        result = requireNotNull(session).dispatch("intelligence.query", buildJsonObject {
            put("requestId", "java26-v7-$kind"); put("kind", kind); put("languageId", "java")
            put("expectedSnapshotHash", snapshot); put("path", path); put("includeDeclaration", false)
            put("position", buildJsonObject { put("line", position.line); put("character", position.character) })
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
            put("javaContext", buildJsonObject {
                put("sdkHome", sdk.toString()); put("expectedContextHash", context.getValue("contextHash"))
                put("contextSchemaVersion", "java-project-context-v7")
            })
        }).jsonObject
    }

    fun captureGradle(additionalSources: Map<String, String> = emptyMap(), release: Int = 26) {
        capture(additionalSources, release)
        requireNotNull(session).close()
        val workspace = requireNotNull(root)
        Files.delete(workspace.resolve("pom.xml"))
        val buildInputs = linkedMapOf(
            "settings.gradle" to "rootProject.name='java26-v7-gate'\n",
            "build.gradle" to "apply plugin: 'java'\njava { toolchain { languageVersion = JavaLanguageVersion.of(26) } }\ntasks.withType(JavaCompile).configureEach { options.release = $release }\n",
            "gradle.properties" to "org.gradle.jvmargs=-Xmx384m\norg.gradle.java.installations.auto-detect=false\norg.gradle.java.installations.auto-download=false\norg.gradle.java.installations.paths=$sdk\n",
        )
        originals = originals.filterKeys { it != "pom.xml" } + buildInputs
        buildInputs.forEach { (path, text) -> Files.writeString(workspace.resolve(path), text) }
        providerId = "gradle-effective-v1"
        val executable = Path.of(assertNotNull(System.getenv("REFACTORKIT_GRADLE_EXECUTABLE"),
            "Supply the verified cached Gradle distribution explicitly for this native gate")).toRealPath()
        assertTrue(Files.isExecutable(executable))
        gradleExecutable = executable
        val toolingHome = Files.createTempDirectory("refactorkit-java26-v7-gradle-home-").also { gradleUserHome = it }
        session = newGradleContextRpcSession()
        val opened = requireNotNull(session).dispatch("project.open", buildJsonObject { put("root", workspace.toString()) }).jsonObject
        val synced = requireNotNull(session).dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle"); put("expectedSnapshotHash", opened.getValue("snapshotHash"))
            put("sdkHome", sdk.toString()); put("gradleExecutable", executable.toString())
            put("gradleUserHome", toolingHome.toString()); put("allowBuildExecution", true)
        }).jsonObject
        assertEquals("synchronized", synced.getValue("status").jsonPrimitive.content, synced.toString())
        snapshot = synced.getValue("snapshotHash").jsonPrimitive.content
        moduleId = moduleIdFromSummary()
        context = requireNotNull(session).dispatch("project.context.v7", contextRequest()).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
    }

    fun captureDiagnostics(condition: String, gradle: Boolean = false) {
        require(condition in setOf("valid source", "removed SDK API"))
        diagnosticCondition = condition
        val additionalSources = if (condition == "removed SDK API") mapOf(
            "src/main/java/fixture/Api26Probe.java" to "package fixture;\npublic final class Api26Probe { java.applet.Applet value; }\n",
        ) else emptyMap()
        if (gradle) captureGradle(additionalSources) else capture(additionalSources)
    }

    fun diagnostics() {
        diagnosticRows = requireNotNull(session).dispatch("diagnostics", buildJsonObject {
            put("languageId", "java"); put("jdkHome", sdk.toString())
        }).jsonArray.map { it.jsonObject }
    }

    fun assertDiagnostics() {
        val platform = context.getValue("sdk").jsonArray.single().jsonObject
        assertEquals(26, platform.getValue("sourceRelease").jsonPrimitive.content.toInt())
        assertEquals(26, platform.getValue("platformRelease").jsonPrimitive.content.toInt())
        assertEquals(sdk, Path.of(platform.getValue("home").jsonPrimitive.content).toRealPath())
        assertEquals(sdkBefore.getValue("release"), platform.getValue("releaseMetadataSha256").jsonPrimitive.content)
        assertEquals(sdkBefore.getValue("lib/ct.sym"), platform.getValue("signatureArchiveSha256").jsonPrimitive.content)
        val errors = diagnosticRows.filter { it.getValue("severity").jsonPrimitive.content == "ERROR" }
        if (diagnosticCondition == "valid source") {
            assertTrue(errors.isEmpty(), diagnosticRows.toString())
        } else {
            assertTrue(errors.isNotEmpty(), "SDK26 must not resolve the API present only in the host25 control")
            assertTrue(errors.all { it.getValue("file").jsonPrimitive.content == "src/main/java/fixture/Api26Probe.java" }, errors.toString())
            assertTrue(errors.any { it.getValue("message").jsonPrimitive.content.contains("applet", ignoreCase = true) }, errors.toString())
            assertTrue(errors.all { it.getValue("evidence").jsonPrimitive.content == "COMPILER" }, errors.toString())
            assertTrue(errors.none { it.getValue("code").jsonPrimitive.content.contains("Unavailable", ignoreCase = true) ||
                it.getValue("code").jsonPrimitive.content.contains("Unsupported", ignoreCase = true) }, errors.toString())
        }
    }

    fun rename(authority: String) {
        require(authority in setOf("matching", "missing context", "other SDK", "source changed", "target exists"))
        renameCase = authority
        val workspace = requireNotNull(root)
        diagnostics()
        assertTrue(diagnosticRows.none { it.getValue("severity").jsonPrimitive.content == "ERROR" }, diagnosticRows.toString())
        val preview: JsonObject
        try {
            preview = requireNotNull(session).dispatch("refactor.preview", buildJsonObject {
                put("operation", "renameClass"); put("languageId", "java"); put("symbol", "fixture.Helper")
                put("expectedSnapshotHash", snapshot); put("javaContext", mutationAuthority())
                put("arguments", buildJsonObject { put("newName", if (authority == "target exists") "Other" else "Calculator") })
            }).jsonObject
        } catch (refused: JsonRpcException) {
            renameRefusal = refused; renameObserved = "preview refused"; return
        }
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
        assertEquals(context.getValue("contextHash"), preview.getValue("contextHash"))
        assertTrue(preview.getValue("renderedDiff").jsonPrimitive.content.contains("Calculator"), preview.toString())
        originals.forEach { (path, text) -> assertEquals(text, Files.readString(workspace.resolve(path)), path) }
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        if (authority == "source changed") Files.writeString(workspace.resolve(caller), originals.getValue(caller) + "// changed after preview\n")
        val applied: JsonObject
        try {
            applied = requireNotNull(session).dispatch("refactor.apply", buildJsonObject {
                put("planId", preview.getValue("planId")); put("expectedSnapshotHash", snapshot)
                if (authority != "missing context") {
                    val home = if (authority == "other SDK") optionalNativeJavaSdkHome("REFACTORKIT_JDK27_HOME") else sdk
                    put("javaContext", mutationAuthority(home))
                }
            }).jsonObject
        } catch (refused: JsonRpcException) {
            renameRefusal = refused
            renameObserved = refused.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content ?: "rpc:${refused.code}"
            return
        }
        assertEquals("matching", authority, "A mismatched mutation request must not apply")
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content, applied.toString())
        assertEquals(context.getValue("contextHash"), applied.getValue("appliedContextHash"))
        assertFalse(Files.exists(workspace.resolve(helper)))
        assertEquals("package fixture;\npublic final class Calculator {\n  public int sum(int left, int right) { return left + right; }\n}\n",
            Files.readString(workspace.resolve("src/main/java/fixture/Calculator.java")))
        assertEquals("package fixture;\npublic final class Use {\n  public int run(Calculator helper) { return helper.sum(1, 2) + new Other().sum(3, 4); }\n}\n",
            Files.readString(workspace.resolve(caller)))
        originals.filterKeys { it != helper && it != caller }.forEach { (path, text) ->
            assertEquals(text, Files.readString(workspace.resolve(path)), path)
        }
        diagnostics()
        assertTrue(diagnosticRows.none { it.getValue("severity").jsonPrimitive.content == "ERROR" }, diagnosticRows.toString())
        val transaction = applied.getValue("transactionId").jsonPrimitive.content
        val rolled = requireNotNull(session).dispatch("patch.rollback", buildJsonObject {
            put("transactionId", transaction); put("expectedSnapshotHash", applied.getValue("snapshotHash"))
            put("javaContext", mutationAuthority())
        }).jsonObject
        assertEquals("rolledBack", rolled.getValue("status").jsonPrimitive.content, rolled.toString())
        assertEquals(snapshot, rolled.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals(context.getValue("contextHash"), rolled.getValue("rolledBackContextHash"))
        assertEquals(JournalState.ROLLED_BACK, assertNotNull(TransactionLog(workspace.resolve(".refactorkit/transactions"))
            .loadRecord(TransactionId(transaction))).state)
        assertFalse(Files.exists(workspace.resolve("src/main/java/fixture/Calculator.java")))
        renameObserved = "applied and rolled back"
    }

    fun assertRename(expected: String) {
        val workspace = requireNotNull(root)
        if (expected == "target collision refused") {
            val refused = assertNotNull(renameRefusal)
            assertEquals("target exists", renameCase)
            assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, refused.code)
            assertTrue(refused.message.orEmpty().contains("exist", ignoreCase = true) ||
                refused.message.orEmpty().contains("conflict", ignoreCase = true), refused.message)
        } else {
            assertEquals(expected, renameObserved, renameRefusal?.message)
            if (expected == "applied and rolled back") assertNull(renameRefusal)
        }
        if (renameRefusal != null) assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        if (renameCase == "source changed") {
            assertEquals(originals.getValue(caller) + "// changed after preview\n", Files.readString(workspace.resolve(caller)))
            // Restore only this fixture's injected drift after proving the engine preserved it.
            Files.writeString(workspace.resolve(caller), originals.getValue(caller))
        }
        originals.forEach { (path, text) -> assertEquals(text, Files.readString(workspace.resolve(path)), path) }
    }

    private fun mutationAuthority(home: Path = sdk): JsonObject = buildJsonObject {
        put("sdkHome", home.toString()); put("expectedContextHash", context.getValue("contextHash"))
        put("contextSchemaVersion", "java-project-context-v7")
    }

    fun assertBound() {
        assertEquals("ready", result.getValue("status").jsonPrimitive.content, result.toString())
        assertEquals(snapshot, result.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals(context.getValue("contextHash"), result.getValue("contextHash"))
        assertEquals("java-project-context-v7", result.getValue("contextSchemaVersion").jsonPrimitive.content)
        val platforms = context.getValue("sdk").jsonArray.map { it.jsonObject }
        assertEquals(setOf(26), platforms.map { it.getValue("sourceRelease").jsonPrimitive.content.toInt() }.toSet())
        platforms.forEach { platform ->
            assertEquals(26, platform.getValue("platformRelease").jsonPrimitive.content.toInt())
            assertEquals(sdk, Path.of(platform.getValue("home").jsonPrimitive.content).toRealPath())
            assertEquals(sdkBefore.getValue("release"), platform.getValue("releaseMetadataSha256").jsonPrimitive.content)
            assertEquals(sdkBefore.getValue("lib/ct.sym"), platform.getValue("signatureArchiveSha256").jsonPrimitive.content)
        }
        assertEquals(platforms.map { it.getValue("identitySha256") }, result.getValue("platformIdentities").jsonArray.toList())
        val selected = requireNotNull(session).dispatch("project.context.v7", contextRequest(true)).jsonObject
        assertEquals("available", selected.getValue("status").jsonPrimitive.content, selected.toString())
        val association = selected.getValue("selectedSource").jsonObject
        assertEquals(helper, association.getValue("path").jsonPrimitive.content)
        assertEquals("fixture.Helper", association.getValue("symbol").jsonPrimitive.content)
        assertEquals("JDT_BINDING", association.getValue("evidence").jsonPrimitive.content)
        assertEquals(providerId, association.getValue("providerId").jsonPrimitive.content)
        assertTrue(association.getValue("bindingKey").jsonPrimitive.content.isNotBlank())
        if (kind == "definition") {
            val target = result.getValue("locations").jsonArray.single().jsonObject
            assertEquals(helper, target.getValue("path").jsonPrimitive.content)
            assertEquals(2, target.getValue("range").jsonObject.getValue("start").jsonObject.getValue("line").jsonPrimitive.content.toInt())
        } else {
            val reference = result.getValue("references").jsonArray.single().jsonObject
            assertEquals(caller, reference.getValue("path").jsonPrimitive.content)
            val start = reference.getValue("range").jsonObject.getValue("start").jsonObject
            assertEquals(2, start.getValue("line").jsonPrimitive.content.toInt())
            val line = originals.getValue(caller).lines()[2]
            assertEquals(line.indexOf("sum("), start.getValue("character").jsonPrimitive.content.toInt(),
                "The same-spelled call on Other must not be a Helper reference")
        }
    }

    fun renameCompleted(): Boolean = renameObserved == "applied and rolled back"

    fun assertIntact(journalExpected: Boolean = false) {
        val workspace = requireNotNull(root)
        originals.forEach { (path, text) -> assertEquals(text, Files.readString(workspace.resolve(path)), path) }
        assertEquals(sdkBefore, sdkInputs())
        val current = requireNotNull(session).dispatch("project.summary", buildJsonObject {}).jsonObject
        assertEquals(snapshot, current.getValue("snapshotHash").jsonPrimitive.content)
        val retainedContext = requireNotNull(session).dispatch("project.context.v7", contextRequest()).jsonObject
        assertEquals("available", retainedContext.getValue("status").jsonPrimitive.content, retainedContext.toString())
        assertEquals(context.getValue("contextHash"), retainedContext.getValue("contextHash"))
        assertEquals(journalExpected, Files.exists(workspace.resolve(".refactorkit/transactions")))
    }

    private fun moduleIdFromSummary(): String {
        val summary = requireNotNull(session).dispatch("project.summary", buildJsonObject {}).jsonObject
        assertFalse(summary.getValue("buildModelsTruncated").jsonPrimitive.boolean)
        val model = summary.getValue("buildModels").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == providerId }
        assertFalse(model.getValue("truncated").jsonPrimitive.boolean)
        return model.getValue("modules").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
    }

    private fun contextRequest(selected: Boolean = false): JsonObject = buildJsonObject {
        put("expectedSnapshotHash", snapshot); put("sdkHome", sdk.toString())
        put("providerId", providerId); put("moduleId", moduleId); put("sourceSetId", "main")
        if (selected) { put("sourceRelativePath", "fixture/Helper.java"); put("symbol", "fixture.Helper") }
    }

    private fun sdkInputs(): Map<String, String> = listOf("release", "lib/ct.sym", "lib/modules").associateWith { relative ->
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(sdk.resolve(relative)).use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun close() {
        session?.close()
        gradleUserHome?.let { home ->
            val stop = ProcessBuilder(requireNotNull(gradleExecutable).toString(), "--stop", "--gradle-user-home", home.toString())
                .redirectErrorStream(true).redirectOutput(home.resolve("stop.log").toFile())
            stop.environment()["JAVA_HOME"] = System.getProperty("java.home")
            listOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").forEach { stop.environment().remove(it) }
            val process = stop.start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS)
                error("Owned Gradle qualification daemon did not stop")
            }
            assertEquals(0, process.exitValue(), Files.readString(home.resolve("stop.log")))
            Files.walk(home).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}
