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
    private lateinit var input: String
    private val daemon = DaemonSession()
    private val staticNamedQuery get() = input.startsWith("static Jakarta named query")

    @Given("a Maven JPQL field-rename fixture with {string}")
    fun workspaceWithMapping(input: String) {
        this.input = input
        parent = Files.createTempDirectory("rk-managed-jpql-").toRealPath()
        workspace = Files.createDirectory(parent.resolve("workspace"))
        val mapping = if (input == "implicit column mapping") "" else "@Column(name = \"display_name\")"
        val namedQuery = when {
            staticNamedQuery -> STATIC_NAMED_QUERY
            input == "computed Jakarta named query" -> COMPUTED_NAMED_QUERY
            else -> null
        }
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
        """.trimMargin().let { source ->
            if (namedQuery == null) source else source
                .replace("import jakarta.persistence.Id;\n", "import jakarta.persistence.Id;\nimport jakarta.persistence.NamedQuery;\n")
                .replace("@Entity\n", "@Entity\n$namedQuery\n")
        } + "\n"
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
        if (input == "external query resource" || input == "static Jakarta named query with opaque resource") {
            write(workspace.resolve("src/main/resources/META-INF/jpa-named-queries.properties"),
                "User.external=select u.amount from User u\n")
        }
        val apiJar = createFixtureJar("persistence", mapOf(
            "Entity" to "public @interface Entity { String name() default \"\"; }",
            "Access" to "public @interface Access { AccessType value(); }",
            "AccessType" to "public enum AccessType { FIELD, PROPERTY }",
            "Column" to "public @interface Column { String name() default \"\"; }",
            "Id" to "public @interface Id {}",
            "NamedQuery" to "public @interface NamedQuery { String name(); String query(); }",
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
        val compiled = compileSources(apiJar, repositoryJar, "before")
        when (input) {
            "empty Maven outputs", "static Jakarta named query with empty outputs" -> {
                Files.createDirectories(workspace.resolve(MAIN_OUTPUT))
                Files.createDirectories(workspace.resolve(TEST_OUTPUT))
            }
            "compiled Maven output", "static Jakarta named query with compiled output" -> {
                val classFile = Path.of("example/jpql/User.class")
                val target = workspace.resolve(MAIN_OUTPUT).resolve(classFile)
                Files.createDirectories(target.parent)
                Files.copy(compiled.resolve(classFile), target)
            }
            "generated Maven output" -> write(workspace.resolve(TEST_OUTPUT).resolve("META-INF/orm.xml"), "<entity-mappings/>\n")
            "linked empty Maven output" -> linkMainOutput()
        }
        original = projectImage()
    }

    @When("the IDE previews the saved entity field rename with Maven SDK authority")
    fun previewRename() {
        snapshot = call("project.open", buildJsonObject { put("root", workspace.toString()) })
            .getValue("snapshotHash").jsonPrimitive.content
        val sdk = System.getProperty("java.home")
        val sdkSelection = buildJsonObject {
            put("expectedSnapshotHash", snapshot)
            put("sdkHome", sdk)
        }
        val aggregate = call("project.context", sdkSelection)
        val readout = if (staticNamedQuery || input == "computed Jakarta named query") {
            val module = aggregate.getValue("modules").jsonArray.single().jsonObject
            call("project.context.v7", buildJsonObject {
                sdkSelection.forEach { (key, value) -> put(key, value) }
                put("providerId", "maven-effective-v1")
                put("moduleId", module.getValue("id"))
                put("sourceSetId", "main")
            })
        } else aggregate
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
        val references = analysis.getValue("references").jsonArray.map { it.jsonObject }
        val namedReferences = references.filter { it["provenance"]?.jsonPrimitive?.content == "jpa.named-query-jpql" }
        val diff = plan.getValue("renderedDiff").jsonPrimitive.content.lines()
        if (staticNamedQuery) {
            assertEquals(2, namedReferences.size, "Both references in @NamedQuery.query should be visible")
            val line = original.getValue(ENTITY).lines().indexOf(STATIC_NAMED_QUERY)
            val first = STATIC_NAMED_QUERY.indexOf("u.amount") + 2
            val second = STATIC_NAMED_QUERY.lastIndexOf("u.amount") + 2
            namedReferences.zip(listOf(first, second)).forEach { (ref, character) ->
                assertEquals("framework.entity-property", ref.getValue("kind").jsonPrimitive.content)
                assertEquals("java.annotation-string", ref.getValue("resourceKind").jsonPrimitive.content)
                assertEquals("example.jpql.User", ref.getValue("targetOwner").jsonPrimitive.content)
                assertEquals("amount", ref.getValue("targetMember").jsonPrimitive.content)
                val location = ref.getValue("location").jsonObject
                assertEquals(ENTITY.toString(), location.getValue("path").jsonPrimitive.content)
                assertEquals(buildJsonObject {
                    put("start", buildJsonObject { put("line", line); put("character", character) })
                    put("end", buildJsonObject { put("line", line); put("character", character + "amount".length) })
                }, location.getValue("range"))
            }
            assertTrue("+$RENAMED_NAMED_QUERY" in diff)
        } else {
            assertTrue(namedReferences.isEmpty())
            if (input == "computed Jakarta named query") assertTrue(diff.none { it.startsWith("+@NamedQuery") })
        }
        assertEquals(2 + namedReferences.size, references.size)
        if (result == "preview-only") {
            assertTrue(!Files.exists(workspace.resolve(".refactorkit/transactions")))
            return
        }
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
        if (result.startsWith("late-")) {
            when (result) {
                "late-output-refusal" -> write(workspace.resolve(TEST_OUTPUT).resolve("META-INF/orm.xml"), "<entity-mappings/>\n")
                "late-link-refusal" -> linkMainOutput()
                else -> error("Unexpected late output fixture: $result")
            }
            val beforeApply = projectImage()
            val refusal = assertFailsWith<JsonRpcException> { call("refactor.apply", apply) }
            if (result == "late-link-refusal") {
                assertEquals(JsonRpcErrorCodes.SNAPSHOT_CHANGED, refusal.code)
                assertTrue(refusal.message.orEmpty().contains("build output", ignoreCase = true), refusal.message)
                assertTrue(Files.isSymbolicLink(workspace.resolve(MAIN_OUTPUT_LINK)))
            } else {
                assertEquals(JsonRpcErrorCodes.PLAN_VALIDATION_FAILED, refusal.code)
                assertEquals("java.context.snapshotStale", refusal.data?.jsonObject?.getValue("refusalCode")?.jsonPrimitive?.content)
            }
            assertEquals(beforeApply, projectImage())
            assertTrue(!Files.exists(workspace.resolve(".refactorkit/transactions")))
            return
        }
        val applied = call("refactor.apply", apply)
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content, applied.toString())
        assertEquals(original + mapOf(
            ENTITY to original.getValue(ENTITY).replace("String amount;", "String displayName;")
                .replace(STATIC_NAMED_QUERY, RENAMED_NAMED_QUERY),
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
        if (input == "empty Maven outputs" || input == "static Jakarta named query with empty outputs") {
            listOf(MAIN_OUTPUT, TEST_OUTPUT).forEach { output ->
                Files.newDirectoryStream(workspace.resolve(output)).use { assertTrue(!it.iterator().hasNext()) }
            }
        }
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
            .associateWith { path ->
                val charset = if (path.toString().endsWith(".class")) Charsets.ISO_8859_1 else Charsets.UTF_8
                Files.readString(workspace.resolve(path), charset)
            }
    }

    private fun linkMainOutput() {
        val target = workspace.resolve(MAIN_OUTPUT_LINK)
        Files.createDirectories(target.parent)
        val empty = Files.createDirectory(workspace.resolve("target/empty-output-target"))
        Files.createSymbolicLink(target, target.parent.relativize(empty))
    }

    private fun compileSources(persistence: Path, springData: Path, phase: String): Path {
        val output = Files.createDirectory(parent.resolve(phase))
        val result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "21", "-proc:none", "-classpath", listOf(persistence, springData).joinToString(java.io.File.pathSeparator),
            "-d", output.toString(), workspace.resolve(ENTITY).toString(), workspace.resolve(REPOSITORY).toString())
        assertEquals(0, result)
        return output
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
        const val STATIC_NAMED_QUERY =
            """@NamedQuery(name = "User.readNamed", query = "select u.amount from User u where u.amount is not null")"""
        const val RENAMED_NAMED_QUERY =
            """@NamedQuery(name = "User.readNamed", query = "select u.displayName from User u where u.displayName is not null")"""
        const val COMPUTED_NAMED_QUERY =
            """@NamedQuery(name = "User.readNamed", query = "select u." + "amount from User u where u.amount is not null")"""
        val ENTITY: Path = Path.of("src/main/java/example/jpql/User.java")
        val REPOSITORY: Path = Path.of("src/main/java/example/jpql/UserRepository.java")
        val MAIN_OUTPUT: Path = Path.of("target/classes")
        val MAIN_OUTPUT_LINK: Path = MAIN_OUTPUT.resolve("linked-empty")
        val TEST_OUTPUT: Path = Path.of("target/test-classes")
    }
}
