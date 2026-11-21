package org.refactorkit.jvm.changesignature

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonObject
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

class JavaAddParameterThisMethodReferenceSteps {
    private val sourcePath = "src/main/java/fixture/Calculator.java"
    private lateinit var scratch: Path
    private lateinit var project: Path
    private lateinit var source: Path
    private lateinit var original: ByteArray
    private lateinit var session: DaemonSession
    private lateinit var snapshot: String
    private lateinit var context: JsonObject
    private lateinit var selection: JsonObject
    private lateinit var request: JsonObject
    private var migrate = true

    @Given("a Java Add Parameter fixture uses {string}")
    fun openFixture(reference: String) {
        scratch = Files.createTempDirectory("rk-add-parameter-this")
        project = Files.createDirectory(scratch.resolve("project"))
        Files.writeString(project.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
            <artifactId>bound-reference</artifactId><version>1</version>
            <properties><maven.compiler.release>17</maven.compiler.release></properties></project>
        """.trimIndent())
        source = project.resolve(sourcePath)
        Files.createDirectories(source.parent)
        val mixed = reference == "mixed receivers and an overload"
        val receiver = if (reference == "an expressive receiver") "peer()" else "this"
        val overload = if (mixed) "private int twice(long x) { return 99 * (int) x; }" else ""
        val alternate = if (mixed) "IntUnaryOperator another = peer()::twice;" else ""
        val extra = if (mixed) " + another.applyAsInt(x)" else ""
        migrate = reference != "this without explicit migration"
        original = """
            package fixture;
            import java.util.function.IntUnaryOperator;
            public final class Calculator {
              private int twice(int x) { return x * 2; }
              private Calculator peer() { return this; }
              $overload
              public int answer(int x) {
                IntUnaryOperator ref = $receiver::twice;
                $alternate
                return twice(x) + ref.applyAsInt(x)$extra;
              }
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

    @When("I preview a parameter addition from the saved method cursor")
    fun buildPreviewRequest() {
        val lines = original.decodeToString().lines()
        val line = lines.indexOfFirst { "twice(int x)" in it }
        val result = rpc("intelligence.query", buildJsonObject {
            put("requestId", "add-parameter-this"); put("kind", "mutationSelection"); put("languageId", "java")
            put("path", sourcePath); put("expectedSnapshotHash", snapshot); put("javaContext", context)
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
            put("position", buildJsonObject { put("line", line); put("character", lines[line].indexOf("twice") + 1) })
        })
        assertEquals("ready", result.getValue("status").jsonPrimitive.content, result.toString())
        selection = result.getValue("selection").jsonObject
        assertEquals("fixture.Calculator#twice(int)", selection.getValue("symbolId").jsonPrimitive.content)
        request = buildJsonObject {
            put("operation", "changeSignature.addParameter"); put("languageId", "java"); put("expectedSnapshotHash", snapshot)
            put("javaContext", context); put("javaSelection", selection)
            put("arguments", buildJsonObject {
                put("type", "int"); put("name", "extra"); put("default", "0")
                if (migrate) put("migrateFunctionalReferences", true)
            })
        }
    }

    @Then("the bound reference migration {string} with exact workspace evidence")
    fun verify(outcome: String) {
        val capability = rpc("server.capabilities", JsonObject(emptyMap())).getValue("javaContextRefactoring")
            .jsonObject.getValue("operationCapabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "changeSignature.addParameter" }
        val advertised = capability["features"]?.jsonObject?.get("thisMethodReferenceMigration")?.jsonPrimitive?.content == "true"
        if (outcome != "applies and rolls back") {
            val refusal = assertFailsWith<JsonRpcException> { rpc("refactor.preview", request) }
            assertEquals(-32001, refusal.code, refusal.message)
            if (outcome == "requires explicit migration") {
                assertTrue("reference" in refusal.message.lowercase(), refusal.message)
            } else if (advertised) {
                assertEquals("java.changeSignature.functionalReferenceUnsupported",
                    refusal.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content, refusal.message)
            }
            assertUnchanged()
            return
        }
        checkEvaluation("original")
        val plan = rpc("refactor.preview", request)
        assertEquals("PREVIEW", plan.getValue("status").jsonPrimitive.content)
        assertEquals("JDT_BINDING", plan.getValue("evidence").jsonPrimitive.content)
        assertEquals(selection.getValue("selectorHash"), plan.getValue("selectionHash"))
        assertTrue(advertised, "The Add Parameter capability must expose thisMethodReferenceMigration")
        assertUnchanged()
        val applied = rpc("refactor.apply", buildJsonObject {
            put("planId", plan.getValue("planId")); put("expectedSnapshotHash", snapshot); put("javaContext", context)
        })
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        assertEquals(selection.getValue("selectorHash"), applied.getValue("appliedSelectionHash"))
        val changed = Files.readString(source)
        assertTrue("private int twice(int x, int extra)" in changed, changed)
        assertTrue("IntUnaryOperator ref = (arg0) -> this.twice(arg0, 0);" in changed, changed)
        assertTrue("return twice(x, 0) + ref.applyAsInt(x);" in changed, changed)
        checkEvaluation("applied")
        val rollback = rpc("patch.rollback", buildJsonObject {
            put("transactionId", applied.getValue("transactionId")); put("expectedSnapshotHash", applied.getValue("snapshotHash"))
            put("javaContext", context)
        })
        assertEquals("rolledBack", rollback.getValue("status").jsonPrimitive.content)
        assertEquals(snapshot, rollback.getValue("snapshotHash").jsonPrimitive.content)
        assertContentEquals(original, Files.readAllBytes(source))
        checkEvaluation("rolled-back")
    }

    private fun assertUnchanged() {
        assertContentEquals(original, Files.readAllBytes(source))
        assertFalse(Files.exists(project.resolve(".refactorkit/transactions")))
    }

    private fun checkEvaluation(stage: String) {
        val classes = Files.createDirectory(scratch.resolve(stage))
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        assertEquals(0, compiler.run(null, null, null, "--release", "17", "-d", classes.toString(), source.toString()))
        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            val owner = loader.loadClass("fixture.Calculator")
            val calculator = owner.getConstructor().newInstance()
            assertEquals(16, owner.getMethod("answer", Int::class.javaPrimitiveType).invoke(calculator, 4))
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
