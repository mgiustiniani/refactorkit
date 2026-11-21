package org.refactorkit.jvm.inlinemethodrefusal

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcException
import org.refactorkit.daemon.DaemonSession
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaExtractDelegateSteps {
    private val sourcePath = "src/main/java/fixture/Calculator.java"
    private lateinit var scratch: Path
    private lateinit var project: Path
    private lateinit var source: Path
    private lateinit var original: ByteArray
    private lateinit var session: DaemonSession
    private lateinit var snapshot: String
    private lateinit var context: JsonObject
    private lateinit var selection: JsonObject

    @Given("the Java delegate fixture has {string}")
    fun openFixture(variant: String) {
        scratch = Files.createTempDirectory("rk-extract-delegate")
        project = Files.createDirectory(scratch.resolve("project"))
        Files.writeString(project.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
            <artifactId>extract-delegate</artifactId><version>1</version>
            <properties><maven.compiler.release>8</maven.compiler.release></properties></project>
        """.trimIndent())
        source = project.resolve(sourcePath)
        Files.createDirectories(source.parent)
        val initializer = if (variant == "a computed constant") "seed(2)" else "2"
        val adjustment = when (variant) {
            "an unselected owner method" -> "seed(n) + OFFSET"
            "an unresolved owner call" -> "missing(n) + OFFSET"
            else -> "n + OFFSET"
        }
        val extra = when (variant) {
            "an owner initializer use" -> "private static final int INITIAL = compute(0);"
            "an existing delegate name" -> "private static final class Calculation {}"
            else -> "private static int seed(int n) { return n; }"
        }
        original = """
            package fixture;
            public final class Calculator {
              private static final int OFFSET = $initializer;
              private static int adjust(int n) { return $adjustment; }
              private static int compute(int n) { return adjust(n) * 3; }
              public static int answer(int n) { return compute(n); }
              $extra
            }
        """.trimIndent().plus("\n").toByteArray()
        Files.write(source, original)
        session = DaemonSession()
        snapshot = rpc("project.open", buildJsonObject { put("root", project.toString()) })
            .getValue("snapshotHash").jsonPrimitive.content
        val sdk = Path.of(System.getProperty("java.home")).toRealPath().toString()
        val resolved = rpc("project.context", buildJsonObject { put("expectedSnapshotHash", snapshot); put("sdkHome", sdk) })
        assertEquals("available", resolved.getValue("status").jsonPrimitive.content, resolved.toString())
        context = buildJsonObject { put("sdkHome", sdk); put("expectedContextHash", resolved.getValue("contextHash")) }
    }

    @When("I request Extract Delegate from the saved Java method cursor")
    fun selectMethod() {
        val lines = original.decodeToString().lines()
        val line = lines.indexOfFirst { "int compute(" in it }
        val result = rpc("intelligence.query", buildJsonObject {
            put("requestId", "extract-delegate"); put("kind", "mutationSelection"); put("languageId", "java")
            put("path", sourcePath); put("expectedSnapshotHash", snapshot); put("javaContext", context)
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
            put("position", buildJsonObject { put("line", line); put("character", lines[line].indexOf("compute") + 1) })
        })
        assertEquals("ready", result.getValue("status").jsonPrimitive.content, result.toString())
        selection = result.getValue("selection").jsonObject
        assertEquals("fixture.Calculator#compute(int)", selection.getValue("symbolId").jsonPrimitive.content)
    }

    @Then("the delegate extraction {string} with exact workspace evidence")
    fun verify(outcome: String) {
        val authority = rpc("server.capabilities", JsonObject(emptyMap())).getValue("javaContextRefactoring")
            .jsonObject.getValue("operationCapabilities").jsonArray.map { it.jsonObject }
            .singleOrNull { it.getValue("operation").jsonPrimitive.content == "extractDelegate" }
        assertEquals("managed-stable", authority?.get("mutationAuthority")?.jsonPrimitive?.content,
            "Java project context omits managed extractDelegate")
        val request = buildJsonObject {
            put("operation", "extractDelegate"); put("languageId", "java"); put("expectedSnapshotHash", snapshot)
            put("javaContext", context); put("javaSelection", selection)
            put("arguments", buildJsonObject {
                put("typeName", "Calculation")
                put("members", buildJsonArray { listOf("OFFSET", "adjust", "compute").forEach { add(JsonPrimitive(it)) } })
            })
        }
        if (outcome != "applies and rolls back") {
            val refusal = assertFailsWith<JsonRpcException> { rpc("refactor.preview", request) }
            assertEquals("java.extractDelegate.$outcome", refusal.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content,
                refusal.message)
            assertUnchanged()
            return
        }
        checkEvaluation("original", false)
        val plan = rpc("refactor.preview", request)
        assertEquals("PREVIEW", plan.getValue("status").jsonPrimitive.content)
        assertEquals("JDT_BINDING", plan.getValue("evidence").jsonPrimitive.content)
        assertEquals(selection.getValue("selectorHash"), plan.getValue("selectionHash"))
        assertUnchanged()
        val applied = rpc("refactor.apply", buildJsonObject {
            put("planId", plan.getValue("planId")); put("expectedSnapshotHash", snapshot); put("javaContext", context)
        })
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        assertTrue("public static int answer(int n) { return Calculation.compute(n); }" in Files.readString(source))
        checkEvaluation("applied", true)
        val rollback = rpc("patch.rollback", buildJsonObject {
            put("transactionId", applied.getValue("transactionId")); put("expectedSnapshotHash", applied.getValue("snapshotHash"))
            put("javaContext", context)
        })
        assertEquals("rolledBack", rollback.getValue("status").jsonPrimitive.content)
        assertEquals(snapshot, rollback.getValue("snapshotHash").jsonPrimitive.content)
        assertContentEquals(original, Files.readAllBytes(source))
        checkEvaluation("rolled-back", false)
    }

    private fun assertUnchanged() {
        assertContentEquals(original, Files.readAllBytes(source))
        assertFalse(Files.exists(project.resolve(".refactorkit/transactions")))
    }

    private fun checkEvaluation(stage: String, extracted: Boolean) {
        val classes = Files.createDirectory(scratch.resolve(stage))
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "--release", "8", "-d", classes.toString(), source.toString()))
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            val owner = loader.loadClass("fixture.Calculator")
            assertEquals(18, owner.getMethod("answer", Int::class.javaPrimitiveType).invoke(null, 4))
            val delegate = owner.declaredClasses.singleOrNull { it.simpleName == "Calculation" }
            assertEquals(extracted, delegate != null)
            val group = delegate ?: owner
            assertEquals(Int::class.javaPrimitiveType, group.getDeclaredField("OFFSET").type)
            for (member in listOf("adjust", "compute")) {
                assertEquals(Int::class.javaPrimitiveType, group.getDeclaredMethod(member, Int::class.javaPrimitiveType).returnType)
                if (extracted) assertFailsWith<NoSuchMethodException> { owner.getDeclaredMethod(member, Int::class.javaPrimitiveType) }
            }
            if (extracted) assertFailsWith<NoSuchFieldException> { owner.getDeclaredField("OFFSET") }
        }
    }

    private fun rpc(method: String, params: JsonObject) = session.dispatch(method, params).jsonObject

    @After
    fun close() {
        if (::session.isInitialized) session.close()
        if (::scratch.isInitialized) Files.walk(scratch).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
