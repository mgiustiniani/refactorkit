package org.refactorkit.jvm.inlinemethodrefusal

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

class JavaIntroduceParameterObjectSteps {
    private val sourcePath = "src/main/java/fixture/Calculator.java"
    private lateinit var fixture: Path
    private lateinit var session: DaemonSession
    private lateinit var original: ByteArray
    private lateinit var hash: String
    private lateinit var authority: JsonObject
    private lateinit var selection: JsonObject

    @Given("a saved Java 8 Maven fixture with side effects in each of four call arguments")
    fun openFixture() {
        fixture = Files.createTempDirectory("rk-java-parameter-object")
        val project = Files.createDirectory(fixture.resolve("project"))
        Files.writeString(project.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
            <artifactId>parameter-object</artifactId><version>1</version>
            <properties><maven.compiler.release>8</maven.compiler.release></properties></project>
        """.trimIndent())
        val file = project.resolve(sourcePath)
        Files.createDirectories(file.parent)
        original = """
            package fixture;
            public final class Calculator {
              private static int ticks;
              private static int digit(int value) {
                ticks = ticks * 10 + value;
                return value;
              }
              private static int evaluate(int prefix, int left, int right, int suffix) {
                return prefix + left * 10 + right + suffix;
              }
              public static int result() {
                int value = evaluate(digit(1), digit(2), digit(3), digit(4));
                return value * 10000 + ticks;
              }
            }
        """.trimIndent().plus("\n").toByteArray()
        Files.write(file, original)
        session = DaemonSession()
        hash = rpc("project.open", buildJsonObject { put("root", project.toString()) }).getValue("snapshotHash").jsonPrimitive.content
        val sdk = Path.of(System.getProperty("java.home")).toRealPath().toString()
        val context = rpc("project.context", buildJsonObject { put("expectedSnapshotHash", hash); put("sdkHome", sdk) })
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        authority = buildJsonObject { put("sdkHome", sdk); put("expectedContextHash", context.getValue("contextHash")) }
        checkEvaluation(file, "original")
    }

    @When("the daemon selects the private Java method at its saved cursor")
    fun selectMethod() {
        val lines = original.decodeToString().lines()
        val line = lines.indexOfFirst { "int evaluate(" in it }
        val result = rpc("intelligence.query", buildJsonObject {
            put("requestId", "java-parameter-object"); put("kind", "mutationSelection"); put("languageId", "java")
            put("path", sourcePath); put("expectedSnapshotHash", hash); put("javaContext", authority)
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
            put("position", buildJsonObject { put("line", line); put("character", lines[line].indexOf("evaluate") + 1) })
        })
        assertEquals("ready", result.getValue("status").jsonPrimitive.content, result.toString())
        selection = result.getValue("selection").jsonObject
        assertEquals("fixture.Calculator#evaluate(int,int,int,int)", selection.getValue("symbolId").jsonPrimitive.content)
    }

    @Then("the parameter object rewrite preserves argument evaluation through managed apply and exact rollback")
    fun checkRewrite() {
        val file = fixture.resolve("project").resolve(sourcePath)
        val catalogue = rpc("server.capabilities", JsonObject(emptyMap())).getValue("javaContextRefactoring").jsonObject
        val capability = catalogue.getValue("operationCapabilities").jsonArray.map { it.jsonObject }
            .singleOrNull { it.getValue("operation").jsonPrimitive.content == "introduceParameterObject" }
        assertEquals("managed-stable", capability?.get("mutationAuthority")?.jsonPrimitive?.content,
            "Java project context omits managed introduceParameterObject")
        assertEquals("java.introduceParameterObject.parameterGroupUnsupported", refusal(previewRequest("right", "left")))
        assertEquals("java.introduceParameterObject.parameterGroupUnsupported", refusal(previewRequest("left", "suffix")))
        assertFalse(Files.exists(fixture.resolve("project/.refactorkit/transactions")))
        val plan = rpc("refactor.preview", previewRequest("left", "right"))
        assertEquals("PREVIEW", plan.getValue("status").jsonPrimitive.content)
        assertEquals("JDT_BINDING", plan.getValue("evidence").jsonPrimitive.content)
        assertEquals(selection.getValue("selectorHash"), plan.getValue("selectionHash"))
        assertContentEquals(original, Files.readAllBytes(file))
        val applied = rpc("refactor.apply", buildJsonObject {
            put("planId", plan.getValue("planId")); put("expectedSnapshotHash", hash); put("javaContext", authority)
        })
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        val changed = Files.readString(file)
        assertTrue("evaluate(digit(1), new Arguments(digit(2), digit(3)), digit(4))" in changed, changed)
        assertTrue("private static final class Arguments" in changed, changed)
        checkEvaluation(file, "changed")
        val rollback = rpc("patch.rollback", buildJsonObject {
            put("transactionId", applied.getValue("transactionId")); put("expectedSnapshotHash", applied.getValue("snapshotHash"))
            put("javaContext", authority)
        })
        assertEquals("rolledBack", rollback.getValue("status").jsonPrimitive.content)
        assertEquals(hash, rollback.getValue("snapshotHash").jsonPrimitive.content)
        assertContentEquals(original, Files.readAllBytes(file))
        checkEvaluation(file, "reverted")
    }

    private fun checkEvaluation(file: Path, stage: String) {
        val classes = Files.createDirectory(fixture.resolve(stage))
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Java 8 compatibility needs a JDK compiler" }
        assertEquals(0, compiler.run(null, null, null, "--release", "8", "-d", classes.toString(), file.toString()))
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { isolated ->
            assertEquals(281234, isolated.loadClass("fixture.Calculator").getMethod("result").invoke(null))
        }
    }

    private fun previewRequest(first: String, second: String) = buildJsonObject {
        put("operation", "introduceParameterObject"); put("languageId", "java"); put("expectedSnapshotHash", hash)
        put("javaContext", authority); put("javaSelection", selection)
        put("arguments", buildJsonObject {
            put("typeName", "Arguments"); put("parameters", JsonArray(listOf(JsonPrimitive(first), JsonPrimitive(second))))
        })
    }

    private fun refusal(request: JsonObject): String? =
        assertFailsWith<JsonRpcException> { rpc("refactor.preview", request) }.data
            ?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content

    private fun rpc(method: String, params: JsonObject) = session.dispatch(method, params).jsonObject

    @After
    fun close() {
        if (::session.isInitialized) session.close()
        if (::fixture.isInitialized) Files.walk(fixture).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
