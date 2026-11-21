package org.refactorkit.jvm.renamefield

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
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KotlinJvmManagedJpqlRenameSteps {
    private lateinit var workspace: Path
    private lateinit var parent: Path
    private lateinit var snapshot: String
    private lateinit var context: JsonObject
    private lateinit var plan: JsonObject
    private lateinit var original: Map<Path, String>
    private val daemon = DaemonSession()

    @Given("a Maven JPQL field-rename fixture with {string}")
    fun workspaceWithMapping(input: String) {
        parent = Files.createTempDirectory("rk-managed-jpql-").toRealPath()
        workspace = Files.createDirectory(parent.resolve("workspace"))
        val mapping = if (input == "implicit column mapping") "" else "@Column(name = \"display_name\")"
        val entity = """
            |package example.jpql;
            |import jakarta.persistence.Access;
            |import jakarta.persistence.AccessType;
            |import jakarta.persistence.Column;
            |import jakarta.persistence.Entity;
            |import jakarta.persistence.Id;
            |import jakarta.persistence.Table;
            |@Entity
            |@Access(AccessType.FIELD)
            |@Table(name = "users")
            |public class User {
            |    @Id private Long id;
            |    $mapping
            |    private String amount;
            |    protected User() {}
            |}
        """.trimMargin() + "\n"
        val extra = when (input) {
            "derived query method" -> """List<User> findByAmount(String amount);"""
            "computed query value" -> """@Query("select " + "u.amount from User u") List<String> computed();"""
            else -> ""
        }
        val caller = """
            |package example.jpql;
            |import java.util.List;
            |import org.springframework.data.jpa.repository.JpaRepository;
            |import org.springframework.data.jpa.repository.Query;
            |public interface UserRepository extends JpaRepository<User, Long> {
            |    @Query("select u.amount from User u where u.amount is not null")
            |    List<String> readAmounts();
            |    $extra
            |}
        """.trimMargin() + "\n"
        write(workspace.resolve(ENTITY), entity)
        write(workspace.resolve(REPOSITORY), caller)
        if (input == "external query resource") {
            write(workspace.resolve("src/main/resources/META-INF/jpa-named-queries.properties"),
                "User.external=select u.amount from User u\n")
        }
        val apiJar = createFixtureJar("persistence", mapOf(
            "Entity" to "public @interface Entity { String name() default \"\"; }",
            "Access" to "public @interface Access { AccessType value(); }",
            "AccessType" to "public enum AccessType { FIELD, PROPERTY }",
            "Column" to "public @interface Column { String name() default \"\"; }",
            "Id" to "public @interface Id {}",
            "Table" to "public @interface Table { String name() default \"\"; }",
        ), "jakarta.persistence")
        val repositoryJar = createFixtureJar("spring-data", mapOf(
            "Query" to "public @interface Query { String value(); boolean nativeQuery() default false; }",
            "JpaRepository" to "public interface JpaRepository<T, ID> {}",
        ), "org.springframework.data.jpa.repository")
        write(workspace.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>example.jpql</groupId><artifactId>jpql-refactoring-fixture</artifactId><version>1</version>
              <dependencies>
                <dependency><groupId>jakarta.persistence</groupId><artifactId>jakarta.persistence-api</artifactId><version>3.1.0</version><scope>system</scope><systemPath>$apiJar</systemPath></dependency>
                <dependency><groupId>org.springframework.data</groupId><artifactId>spring-data-jpa</artifactId><version>3.5.0</version><scope>system</scope><systemPath>$repositoryJar</systemPath></dependency>
              </dependencies>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version>
              <configuration><release>21</release><proc>none</proc></configuration></plugin></plugins></build>
            </project>
        """.trimIndent())
        original = projectImage()
        compileSources(apiJar, repositoryJar, "before")
    }

    @When("the IDE previews the saved entity field rename with Maven SDK authority")
    fun previewRename() {
        snapshot = call("project.open", buildJsonObject { put("root", workspace.toString()) })
            .getValue("snapshotHash").jsonPrimitive.content
        val sdk = System.getProperty("java.home")
        val readout = call("project.context", buildJsonObject {
            put("expectedSnapshotHash", snapshot)
            put("sdkHome", sdk)
        })
        assertEquals("available", readout.getValue("status").jsonPrimitive.content, readout.toString())
        context = buildJsonObject {
            put("sdkHome", sdk)
            put("expectedContextHash", readout.getValue("contextHash").jsonPrimitive.content)
            put("contextSchemaVersion", readout.getValue("schemaVersion").jsonPrimitive.content)
        }
        val lines = original.getValue(ENTITY).lines()
        val line = lines.indexOfFirst { "private String amount;" in it }
        val selected = call("intelligence.query", buildJsonObject {
            put("languageId", "java")
            put("requestId", "selected-jpql")
            put("kind", "mutationSelection")
            put("path", ENTITY.toString().replace('\\', '/'))
            put("position", buildJsonObject { put("line", line); put("character", lines[line].indexOf("amount")) })
            put("expectedSnapshotHash", snapshot)
            put("javaContext", context)
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
        })
        assertEquals("ready", selected.getValue("status").jsonPrimitive.content, selected.toString())
        plan = call("refactor.preview", buildJsonObject {
            put("languageId", "java")
            put("operation", "renameMember")
            put("expectedSnapshotHash", snapshot)
            put("javaContext", context)
            put("javaSelection", selected.getValue("selection"))
            put("arguments", buildJsonObject { put("newName", "displayName") })
        })
        assertEquals(original, projectImage())
    }

    @Then("the JPQL field rename has {string} with exact query edits and no unowned writes")
    fun verifyLifecycle(result: String) {
        val analysis = plan.getValue("referenceAnalysis").jsonObject
        assertEquals(2, analysis.getValue("references").jsonArray.size)
        val apply = buildJsonObject {
            put("planId", plan.getValue("planId"))
            put("expectedSnapshotHash", snapshot)
            put("javaContext", context)
        }
        if (result == "review-only") {
            assertEquals("INCOMPLETE", analysis.getValue("completeness").jsonPrimitive.content)
            assertEquals("none", analysis.getValue("managedWriteAuthority").jsonPrimitive.content)
            assertEquals("INELIGIBLE", plan.getValue("managedWriteEligibility").jsonPrimitive.content)
            val refusal = assertFailsWith<JsonRpcException> { call("refactor.apply", apply) }
            assertEquals(JsonRpcErrorCodes.PLAN_VALIDATION_FAILED, refusal.code)
            assertEquals("referenceAnalysis.incomplete", refusal.data?.jsonObject?.getValue("refusalCode")?.jsonPrimitive?.content)
            assertEquals(original, projectImage())
            assertTrue(!Files.exists(workspace.resolve(".refactorkit/transactions")))
            return
        }

        assertEquals("COMPLETE", analysis.getValue("completeness").jsonPrimitive.content, analysis.toString())
        assertEquals("true", analysis.getValue("completeProject").jsonPrimitive.content)
        assertEquals("managed-stable", analysis.getValue("managedWriteAuthority").jsonPrimitive.content)
        val applied = call("refactor.apply", apply)
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content, applied.toString())
        assertEquals(original + mapOf(
            ENTITY to original.getValue(ENTITY).replace("String amount;", "String displayName;"),
            REPOSITORY to original.getValue(REPOSITORY).replace("u.amount", "u.displayName"),
        ), projectImage())
        compileSources(parent.resolve("persistence.jar"), parent.resolve("spring-data.jar"), "after")
        val rollback = call("patch.rollback", buildJsonObject {
            put("transactionId", applied.getValue("transactionId"))
            put("expectedSnapshotHash", applied.getValue("snapshotHash"))
            put("javaContext", context)
        })
        assertEquals("rolledBack", rollback.getValue("status").jsonPrimitive.content)
        assertEquals(snapshot, rollback.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals(original, projectImage())
    }

    @After
    fun close() {
        daemon.close()
        if (::parent.isInitialized) Files.walk(parent).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun call(method: String, values: JsonObject): JsonObject = daemon.dispatch(method, values).jsonObject

    private fun projectImage(): Map<Path, String> = Files.walk(workspace).use { stream ->
        stream.filter(Files::isRegularFile).toList().map { workspace.relativize(it) }
            .filterNot { it.startsWith(Path.of(".refactorkit")) }
            .associateWith { Files.readString(workspace.resolve(it)) }
    }

    private fun compileSources(persistence: Path, springData: Path, phase: String) {
        val output = Files.createDirectory(parent.resolve(phase))
        val result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "21", "-proc:none", "-classpath", listOf(persistence, springData).joinToString(java.io.File.pathSeparator),
            "-d", output.toString(), workspace.resolve(ENTITY).toString(), workspace.resolve(REPOSITORY).toString())
        assertEquals(0, result)
    }

    private fun createFixtureJar(name: String, types: Map<String, String>, pkg: String): Path {
        val javaRoot = parent.resolve("$name-sources")
        val classes = Files.createDirectory(parent.resolve("$name-classes"))
        val sources = types.map { (type, declaration) ->
            javaRoot.resolve("$type.java").also { write(it, "package $pkg; $declaration\n") }.toString()
        }
        val args = listOf("--release", "21", "-proc:none", "-d", classes.toString()) + sources
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *args.toTypedArray()))
        val jar = parent.resolve("$name.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            Files.walk(classes).use { walk ->
                walk.filter(Files::isRegularFile).forEach { file ->
                    out.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                    Files.copy(file, out)
                    out.closeEntry()
                }
            }
        }
        return jar
    }

    private fun write(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private companion object {
        val ENTITY: Path = Path.of("src/main/java/example/jpql/User.java")
        val REPOSITORY: Path = Path.of("src/main/java/example/jpql/UserRepository.java")
    }
}
