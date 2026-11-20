package org.refactorkit.jvm.renamemethod

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameMemberPlanner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JavaKotlinMemberRenameClosureSteps {
    private lateinit var root: Path
    private lateinit var inputs: Map<String, String>
    private lateinit var selector: String
    private lateinit var member: String
    private lateinit var plan: PatchPlan
    private var preview: JsonObject? = null
    private var wireRefusal: JsonRpcException? = null
    private val javaPath = "src/main/java/fixture/Main.java"

    @Given("a Java member rename fixture has {string} consumer scope")
    fun prepare(scope: String) {
        val field = scope == "unsigned field with Kotlin"
        val declaration = when (scope) {
            "private with Kotlin" -> "private static String fromJava() { return \"java\"; }"
            "public with Kotlin", "Java only" -> "public static String fromJava() { return \"java\"; }"
            "package-private with Kotlin" -> "static String fromJava() { return \"java\"; }"
            "unsigned field with Kotlin" -> "public static String value = \"java\";"
            else -> error(scope)
        }
        member = if (field) "value" else "fromJava"
        selector = if (field) "fixture.Main#value" else "fixture.Main#fromJava()"
        val javaUse = if (field) member else "$member()"
        val kotlinUse = if (scope == "private with Kotlin") "\"unrelated\"" else "Main.$javaUse"
        inputs = buildMap {
            put("pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>mixed</artifactId><version>1</version>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                </project>
            """.trimIndent())
            put(javaPath, """
                package fixture;
                public final class Main {
                    $declaration
                    public static String caller() { return $javaUse; }
                }
            """.trimIndent())
            if (scope != "Java only") put("src/main/kotlin/fixture/Greeter.kt", """
                package fixture
                object Greeter {
                    @JvmStatic fun message(): String = $kotlinUse
                }
            """.trimIndent())
        }
        root = Files.createTempDirectory("rk-mixed-member-rename-")
        for ((file, content) in inputs) {
            val target = root.resolve(file)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
    }

    @When("the caller requests the exact Java member preview")
    fun preview() {
        val snapshot = JavaProjectScanner().scan(root)
        assertEquals(inputs.keys.any { it.endsWith(".kt") }, snapshot.files.any { it.languageId == "kotlin" })
        plan = JavaRenameMemberPlanner(JavaLanguageAdapter()).preview(snapshot, selector, "renamed")
        DaemonSession().use { daemon ->
            val opened = daemon.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
            try {
                preview = daemon.dispatch("refactor.preview", buildJsonObject {
                    put("operation", "renameMember")
                    put("languageId", "java")
                    put("symbol", selector)
                    put("expectedSnapshotHash", opened.getValue("snapshotHash"))
                    put("arguments", buildJsonObject { put("newName", "renamed") })
                }).jsonObject
            } catch (failure: JsonRpcException) {
                wireRefusal = failure
            }
        }
    }

    @Then("the member rename returns {string} with preserved saved files")
    fun verify(outcome: String) {
        val refusalCode = "java.renameMember.kotlinConsumerClosureUnavailable"
        when (outcome) {
            "Kotlin closure refused" -> {
                assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
                assertEquals(refusalCode, plan.refusalCode)
                assertFalse(plan.managedWriteEligible)
                assertFalse(plan.requiresUserApproval)
                assertTrue(plan.workspaceEdit.edits.isEmpty() && plan.affectedFiles.isEmpty())
                assertNull(preview)
                val failure = assertNotNull(wireRefusal)
                assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, failure.code)
                assertEquals(refusalCode, failure.data?.jsonObject?.getValue("refusalCode")?.jsonPrimitive?.content)
            }
            "exact Java preview" -> {
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
                assertEquals(RefactoringEvidence.JDT_BINDING, plan.evidence)
                assertTrue(plan.managedWriteEligible && plan.requiresUserApproval)
                assertEquals(setOf(Path.of(javaPath)), plan.affectedFiles)
                val staged = WorkspaceEditSimulator.apply(JavaProjectScanner().scan(root), plan.workspaceEdit)
                assertEquals(inputs.getValue(javaPath).replace(member, "renamed"),
                    staged.files.single { it.path == Path.of(javaPath) }.content)
                assertNull(wireRefusal)
                assertEquals("PREVIEW", assertNotNull(preview).getValue("status").jsonPrimitive.content)
            }
            else -> error(outcome)
        }
        for ((name, original) in inputs) assertEquals(original, Files.readString(root.resolve(name)), name)
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    @After
    fun close() {
        if (::root.isInitialized) root.toFile().deleteRecursively()
    }
}
