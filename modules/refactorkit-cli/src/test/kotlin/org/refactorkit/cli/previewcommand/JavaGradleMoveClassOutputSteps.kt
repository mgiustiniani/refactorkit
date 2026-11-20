package org.refactorkit.cli.previewcommand

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
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaGradleMoveClassOutputSteps {
    private lateinit var fixture: Path
    private lateinit var root: Path
    private lateinit var gradleHome: Path
    private lateinit var before: Map<Path, List<Byte>>
    private var preview: JsonObject? = null
    private var refusal: JsonRpcException? = null
    private val javaHome = Path.of(System.getProperty("java.home")).toRealPath()
    private val gradle by lazy { Path.of(requireNotNull(System.getenv("REFACTORKIT_GRADLE_EXECUTABLE"))) }

    @Given("a Gradle class move has {string}")
    fun prepare(condition: String) {
        fixture = Files.createTempDirectory("rk-gradle-move-class-outputs-")
        root = Files.createDirectory(fixture.resolve("workspace"))
        gradleHome = Files.createDirectory(fixture.resolve("gradle-home"))
        val processing = condition in setOf("cold annotation processing", "materialized annotation outputs")
        val modules = if (processing) "'app', 'library', 'processor'" else "'app', 'library'"
        write("settings.gradle", "rootProject.name = 'move-class-output-context'\ninclude $modules\n")
        write("build.gradle", "subprojects { apply plugin: 'java-library'; " +
            "tasks.withType(JavaCompile).configureEach { options.release = 17 } }\n")
        val processorDependencies = if (processing) {
            "compileOnly project(':processor'); annotationProcessor project(':processor')"
        } else ""
        write("app/build.gradle", "dependencies { implementation project(':library'); $processorDependencies }\n")
        write("app/src/main/java/fixture/app/Product.java",
            "package fixture.app;\npublic class Product { public int value() { return 7; } }\n")
        write("app/src/main/java/fixture/app/Caller.java",
            "package fixture.app;\npublic class Caller { int value() { return new Product().value(); } }\n")
        write("library/src/main/java/fixture/library/Anchor.java", "package fixture.library;\npublic class Anchor {}\n")
        write("app/src/test/java/fixture/app/Observer.java",
            "package fixture.app;\npublic class Observer { Product value() { return new Product(); } }\n")
        if (processing) writeAnnotationProcessorFixture()
        if (condition in setOf("materialized annotation outputs", "unrelated binding error")) compile()
        if (condition == "unrelated binding error") {
            write("app/src/main/java/fixture/app/Broken.java",
                "package fixture.app;\npublic class Broken { MissingUnrelated type; }\n")
        }
        val generated = root.resolve("app/build/generated/sources/annotationProcessor/java/main/generated/Produced.java")
        assertEquals(condition == "materialized annotation outputs", Files.isRegularFile(generated))
    }

    @When("I preview moving the Gradle class into its library dependency with an acquired Java context")
    fun preview() {
        DaemonSession().use { daemon ->
            val opened = daemon.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
            val synced = daemon.dispatch("project.sync", buildJsonObject {
                put("buildSystem", "gradle"); put("allowBuildExecution", true)
                put("expectedSnapshotHash", opened.getValue("snapshotHash"))
                put("gradleExecutable", gradle.toString()); put("gradleUserHome", gradleHome.toString())
                put("sdkHome", javaHome.toString())
            }).jsonObject
            assertEquals("synchronized", synced.getValue("status").jsonPrimitive.content, synced.toString())
            val context = daemon.dispatch("project.context.aggregate", buildJsonObject {
                put("sdkHome", javaHome.toString()); put("expectedSnapshotHash", synced.getValue("snapshotHash"))
            }).jsonObject
            assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
            before = workspaceImages()
            try {
                preview = daemon.dispatch("refactor.preview", buildJsonObject {
                    put("languageId", "java"); put("operation", "moveClass"); put("symbol", "fixture.app.Product")
                    put("expectedSnapshotHash", synced.getValue("snapshotHash"))
                    put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
                    put("arguments", buildJsonObject { put("targetPackage", "fixture.moved"); put("targetModuleId", ":library") })
                    put("javaContext", buildJsonObject {
                        put("sdkHome", javaHome.toString()); put("expectedContextHash", context.getValue("contextHash"))
                        put("contextSchemaVersion", context.getValue("schemaVersion"))
                    })
                }).jsonObject
            } catch (failure: JsonRpcException) {
                refusal = failure
            }
        }
    }

    @Then("the Gradle move reports {string} and preserves the saved workspace")
    fun verify(outcome: String) {
        when (outcome) {
            "missing Gradle outputs" -> {
                val failure = verifyRefusal("java.moveClass.gradleCompilerOutputsMissing")
                assertTrue(failure.message.orEmpty().contains(":library:main"), failure.message)
                assertTrue(failure.message.orEmpty().contains("synchronize Gradle"), failure.message)
            }
            "generic incomplete JDT bindings" -> verifyRefusal("java.moveClass.crossModuleContextUnavailable")
            "binding-proven preview" -> {
                assertNull(refusal, "unexpected refusal ${refusal?.message}")
                val result = assertNotNull(preview)
                assertEquals("PREVIEW", result.getValue("status").jsonPrimitive.content, result.toString())
                val affected = result.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet()
                assertEquals(setOf(
                    "app/src/main/java/fixture/app/Product.java", "app/src/main/java/fixture/app/Caller.java",
                    "app/src/test/java/fixture/app/Observer.java", "library/src/main/java/fixture/moved/Product.java",
                ), affected)
            }
            else -> error("Unknown outcome $outcome")
        }
        assertEquals(before, workspaceImages())
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    private fun verifyRefusal(code: String): JsonRpcException {
        assertNull(preview)
        val failure = assertNotNull(refusal)
        assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, failure.code)
        assertEquals(code, failure.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content, failure.message)
        return failure
    }

    private fun compile() {
        val log = fixture.resolve("gradle.log")
        val process = ProcessBuilder(gradle.toString(), "--offline", "--no-daemon", "--max-workers=1", "--console=plain",
            ":app:classes", ":app:testClasses").directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
            .apply { environment()["JAVA_HOME"] = javaHome.toString(); environment()["GRADLE_USER_HOME"] = gradleHome.toString() }
            .start()
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Gradle fixture did not finish")
            assertEquals(0, process.exitValue(), Files.readString(log))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun writeAnnotationProcessorFixture() {
        write("processor/src/main/java/processing/MakeValue.java", "package processing;\npublic @interface MakeValue {}\n")
        write("processor/src/main/java/processing/MakeValueProcessor.java", """
            package processing;
            import java.io.*;
            import java.util.Set;
            import javax.annotation.processing.*;
            import javax.lang.model.SourceVersion;
            import javax.lang.model.element.TypeElement;
            @SupportedAnnotationTypes("processing.MakeValue")
            @SupportedSourceVersion(SourceVersion.RELEASE_17)
            public class MakeValueProcessor extends AbstractProcessor {
                public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                    if (round.getElementsAnnotatedWith(MakeValue.class).isEmpty()) return false;
                    try (Writer file = processingEnv.getFiler().createSourceFile("generated.Produced").openWriter()) {
                        file.write("package generated;\npublic class Produced { public static int value() { return 1; } }");
                    } catch (IOException failure) { throw new UncheckedIOException(failure); }
                    return true;
                }
            }
        """.trimIndent())
        write("processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor", "processing.MakeValueProcessor\n")
        write("app/src/main/java/fixture/app/UsesGenerated.java", """
            package fixture.app;
            @processing.MakeValue public class UsesGenerated { int value() { return generated.Produced.value(); } }
        """.trimIndent())
    }

    private fun write(name: String, body: String) {
        val path = root.resolve(name)
        Files.createDirectories(path.parent)
        Files.writeString(path, body)
    }

    private fun workspaceImages(): Map<Path, List<Byte>> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).filter { path ->
            root.relativize(path).none { it.toString() in setOf(".gradle", ".refactorkit") }
        }.toList().associate { path ->
            root.relativize(path) to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toList()
        }
    }

    @After
    fun cleanup() {
        if (::fixture.isInitialized) {
            Files.walk(fixture).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
