package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaLegacy8PlatformSteps {
    private lateinit var fixture: Path
    private lateinit var root: Path
    private lateinit var sdk: Path
    private lateinit var configuration: String
    private lateinit var context: JsonObject
    private lateinit var diagnostics: JsonArray
    private var definition: JsonObject? = null
    private lateinit var before: Map<String, String>
    private val source = "library/src/main/java/fixture/library/Api.java"
    private val caller = "app/src/main/java/fixture/app/Caller.java"

    private val generated = "app/build/generated/sources/annotationProcessor/java/main/fixture/generated/Greeting.java"
    private val redactionProbe = "rk-processor-redaction-probe"
    private var processorArgument: String? = null

    @Given("a Java platform fixture uses {string}")
    fun prepare(case: String) {
        val home = System.getenv("REFACTORKIT_JAVA_8_HOME")
        assumeTrue(!home.isNullOrBlank(), "Set REFACTORKIT_JAVA_8_HOME to run native JDK 8 acceptance")
        sdk = Path.of(requireNotNull(home)).toRealPath()
        assertTrue(Files.readString(sdk.resolve("release")).contains("JAVA_VERSION=\"1.8."))
        fixture = Files.createTempDirectory("rk-native-jdk8-")
        root = Files.createDirectory(fixture.resolve("project"))
        configuration = case
        val body = if (case.endsWith("newer API")) "return \" value \".strip();" else
            "return java.time.LocalDate.of(2020, 1, 1).toString() + javax.crypto.Cipher.getMaxAllowedKeyLength(\"AES\");"
        write(source, """
            package fixture.library;
            public final class Api {
                public static String value() throws Exception { $body }
            }
        """.trimIndent())
        write(caller, """
            package fixture.app;
            public final class Caller {
                public String call() throws Exception { return fixture.library.Api.value(); }
            }
        """.trimIndent())
        if (case.startsWith("Gradle")) gradleBuild() else mavenBuild()
        if (!case.endsWith("11")) verifyWithSelectedJavac()
        if (case.startsWith("Gradle processor ")) prepareProcessor()
        before = savedFiles()
    }

    @When("the source project acquires and uses its explicitly selected Java platform")
    fun acquire() {
        DaemonSession().use { daemon ->
            val localRepository = Files.createDirectory(fixture.resolve("maven-repository"))
            val opened = daemon.dispatch("project.open", buildJsonObject {
                put("root", root.toString()); put("mavenRepository", localRepository.toString())
            }).jsonObject
            var snapshot = opened.getValue("snapshotHash").jsonPrimitive.content
            if (configuration.startsWith("Gradle")) {
                val gradle = requireNotNull(System.getenv("REFACTORKIT_GRADLE_9_8_1_EXECUTABLE"))
                val synced = daemon.dispatch("project.sync", buildJsonObject {
                    put("buildSystem", "gradle"); put("expectedSnapshotHash", snapshot); put("sdkHome", sdk.toString())
                    put("allowBuildExecution", true); put("gradleExecutable", gradle)
                    put("gradleUserHome", Files.createDirectories(fixture.resolve("gradle-home")).toString())
                    put("gradleRuntimeHome", System.getProperty("java.home"))
                }).jsonObject
                assertEquals("synchronized", synced.getValue("status").jsonPrimitive.content, synced.toString())
                assertFalse(synced.toString().contains(redactionProbe))
                snapshot = synced.getValue("snapshotHash").jsonPrimitive.content
            }
            context = daemon.dispatch("project.context.aggregate", buildJsonObject {
                put("sdkHome", sdk.toString()); put("expectedSnapshotHash", snapshot)
            }).jsonObject
            if (context["status"]?.jsonPrimitive?.content != "available") return
            diagnostics = daemon.dispatch("diagnostics", buildJsonObject {
                put("languageId", "java"); put("jdkHome", sdk.toString())
            }).jsonArray
            if (configuration.endsWith("newer API")) return
            val token = if (validProcessorOption()) "generatedValue" else "value"
            val column = Files.readAllLines(root.resolve(caller))[2].indexOf(token) + 1
            assertTrue(column > 0)
            definition = daemon.dispatch("intelligence.query", buildJsonObject {
                put("requestId", "jdk8-definition"); put("kind", "definition"); put("languageId", "java"); put("path", caller)
                put("position", buildJsonObject { put("line", 2); put("character", column) })
                put("expectedSnapshotHash", snapshot)
                put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
                put("javaContext", buildJsonObject {
                    put("sdkHome", sdk.toString()); put("expectedContextHash", context.getValue("contextHash"))
                    put("contextSchemaVersion", context.getValue("schemaVersion"))
                })
            }).jsonObject
        }
    }

    @Then("the Java platform proves {string} and keeps the saved project unchanged")
    fun verify(expected: String) {
        if (expected == "selected JDK too old") {
            assertEquals("refused", context.getValue("status").jsonPrimitive.content, context.toString())
            val refusal = context.getValue("refusal").jsonObject
            assertEquals("java.platform.releaseSignaturesUnavailable", refusal.getValue("code").jsonPrimitive.content)
            assertTrue(refusal.getValue("message").jsonPrimitive.content.contains("release 8"), refusal.toString())
        } else {
            assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
            val selected = context.getValue("sdk").jsonArray.single().jsonObject
            assertEquals("8", selected.getValue("platformRelease").jsonPrimitive.content)
            assertEquals(sdk.resolve("lib/ct.sym").toString(), selected.getValue("signatureArchive").jsonPrimitive.content)
            val errors = diagnostics.map { it.jsonObject }.filter { it.getValue("severity").jsonPrimitive.content == "ERROR" }
            if (expected == "Java 8 API diagnostic") {
                assertTrue(errors.any { it["file"]?.jsonPrimitive?.content == source &&
                    it["message"]?.jsonPrimitive?.content.orEmpty().contains("strip") }, errors.toString())
            } else {
                assertEquals("native Java 8 binding", expected)
                assertTrue(errors.isEmpty(), errors.toString())
                val result = assertNotNull(definition)
                assertEquals("ready", result.getValue("status").jsonPrimitive.content, result.toString())
                val location = result.getValue("locations").jsonArray.single().jsonObject
                assertEquals(source, location.getValue("path").jsonPrimitive.content)
                val range = location.getValue("range").jsonObject
                assertEquals("2", range.getValue("start").jsonObject.getValue("line").jsonPrimitive.content)
                assertEquals("25", range.getValue("start").jsonObject.getValue("character").jsonPrimitive.content)
                assertEquals("30", range.getValue("end").jsonObject.getValue("character").jsonPrimitive.content)
            }
        }
        assertEquals(before, savedFiles())
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    @Then("the Gradle compiler option is {string} without changing saved inputs")
    fun verifyProcessorOption(outcome: String) {
        assertFalse(context.toString().contains(redactionProbe))
        if (outcome == "qualified processor option") {
            assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
            val sdkFacts = context.getValue("sdk").jsonArray.single().jsonObject
            assertEquals("8", sdkFacts.getValue("platformRelease").jsonPrimitive.content)
            val main = context.getValue("modules").jsonArray.map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == ":app" }
                .getValue("sourceSets").jsonArray.map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == "main" }
            val arg = requireNotNull(processorArgument)
            val reported = main.getValue("compilerOptions").jsonArray.map { it.jsonObject }
                .single { it.getValue("name").jsonPrimitive.content == "compilerArg" }
            assertEquals(arg, reported.getValue("value").jsonPrimitive.content)
            val options = main.getValue("annotationProcessors").jsonObject.getValue("options").jsonObject
            val key = arg.removePrefix("-A").substringBefore('=')
            assertEquals(arg.substringAfter('=', ""), options.getValue(key).jsonPrimitive.content)
            assertTrue(diagnostics.none { it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR" },
                diagnostics.toString())
            val result = assertNotNull(definition)
            assertEquals("ready", result.getValue("status").jsonPrimitive.content, result.toString())
            assertEquals(generated, result.getValue("locations").jsonArray.single().jsonObject
                .getValue("path").jsonPrimitive.content)
        } else {
            assertEquals("refused", context.getValue("status").jsonPrimitive.content, context.toString())
            val refusal = context.getValue("refusal").jsonObject
            assertEquals("java.platform.compilerApiUnqualified", refusal.getValue("code").jsonPrimitive.content)
            val message = refusal.getValue("message").jsonPrimitive.content
            val expected = if (outcome == "redacted option refused") "Compiler options do not prove"
                else "Custom compiler arguments may change"
            assertTrue(message.startsWith(expected), refusal.toString())
        }
        assertEquals(before, savedFiles())
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    private fun validProcessorOption() =
        configuration in setOf("Gradle processor value", "Gradle processor valueless")

    private fun prepareProcessor() {
        val arg = when (configuration) {
            "Gradle processor value" -> "-Agreeting.mode=base"
            "Gradle processor valueless" -> "-Agreeting.toggle"
            "Gradle processor malformed" -> "-Agreeting..mode=base"
            "Gradle processor boot option" -> "-Xbootclasspath/p:platform.jar"
            "Gradle processor unknown" -> "-Xunknown-refactorkit"
            "Gradle processor redacted" -> "-Agreeting.token=$redactionProbe"
            else -> error("Unknown processor option fixture")
        }
        processorArgument = arg
        write("settings.gradle", "rootProject.name = 'jdk8-platform'; include 'app', 'library', 'processor'\n")
        write("app/build.gradle", """
            dependencies {
                implementation project(':library')
                annotationProcessor project(':processor')
            }
            tasks.named('compileJava') { options.compilerArgs.add('$arg') }
        """.trimIndent())
        write("processor/build.gradle", "tasks.withType(JavaCompile).configureEach { options.compilerArgs.add('-proc:none') }")
        write("processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor",
            "fixture.processor.GreetingProcessor\n")
        write("processor/src/main/java/fixture/processor/GreetingProcessor.java", """
            package fixture.processor;

            import java.io.IOException;
            import java.io.Writer;
            import java.util.Map;
            import java.util.Set;
            import javax.annotation.processing.*;
            import javax.lang.model.SourceVersion;
            import javax.lang.model.element.TypeElement;

            @SupportedAnnotationTypes("*")
            @SupportedOptions({"greeting.mode", "greeting.toggle"})
            @SupportedSourceVersion(SourceVersion.RELEASE_8)
            public final class GreetingProcessor extends AbstractProcessor {
                private boolean generated;

                @Override
                public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                    if (generated || round.processingOver()) return false;
                    generated = true;
                    Map<String, String> options = processingEnv.getOptions();
                    String greeting = options.containsKey("greeting.toggle") ? "toggle" : options.get("greeting.mode");
                    try (Writer writer = processingEnv.getFiler().createSourceFile("fixture.generated.Greeting").openWriter()) {
                        writer.write("package fixture.generated; public class Greeting { "
                            + "public static String generatedValue() { return \""
                            + greeting + "\"; } }");
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                    return false;
                }
            }
        """.trimIndent())
        if (!validProcessorOption()) return
        val original = Files.readString(root.resolve(caller))
        write(caller, original.replace("Api.value()", "Api.value() + fixture.generated.Greeting.generatedValue()"))
        val gradle = requireNotNull(System.getenv("REFACTORKIT_GRADLE_9_8_1_EXECUTABLE"))
        val log = fixture.resolve("gradle-build.log")
        val build = ProcessBuilder(gradle, "--offline", "--no-daemon", "--console=plain",
            "-Porg.gradle.java.installations.paths=$sdk", "-Porg.gradle.java.installations.auto-detect=false",
            "-Porg.gradle.java.installations.auto-download=false", ":app:classes")
            .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
        build.environment()["JAVA_HOME"] = System.getProperty("java.home")
        build.environment()["GRADLE_USER_HOME"] = Files.createDirectory(fixture.resolve("gradle-home")).toString()
        val process = build.start()
        try {
            assertTrue(process.waitFor(100, TimeUnit.SECONDS), "Gradle did not finish building the fixture")
            assertEquals(0, process.exitValue(), Files.readString(log))
            val expected = if (configuration == "Gradle processor value") "base" else "toggle"
            assertTrue(Files.readString(root.resolve(generated)).contains("return \"$expected\""))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun verifyWithSelectedJavac() {
        val output = Files.createDirectory(fixture.resolve("classes"))
        val log = fixture.resolve("javac.log")
        val javac = ProcessBuilder(sdk.resolve("bin/javac").toString(), "-source", "8", "-target", "8", "-proc:none",
            "-d", output.toString(), root.resolve(source).toString(), root.resolve(caller).toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(javac.waitFor(30, TimeUnit.SECONDS), "Selected javac did not finish")
            assertEquals(!configuration.endsWith("newer API"), javac.exitValue() == 0, Files.readString(log))
        } finally {
            if (javac.isAlive) javac.destroyForcibly()
        }
    }

    private fun mavenBuild() {
        val options = when (configuration) {
            "Maven release 11" -> "<release>11</release>"
            "Maven source target" -> "<source>1.8</source><target>1.8</target>"
            else -> "<release>8</release>"
        }
        write("pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>reactor</artifactId><version>1</version>
            <packaging>pom</packaging><modules><module>app</module><module>library</module></modules>
            <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
            <version>3.14.1</version><configuration>$options</configuration></plugin></plugins></build></project>
        """.trimIndent())
        val parent = "<parent><groupId>fixture</groupId><artifactId>reactor</artifactId><version>1</version></parent>"
        write("library/pom.xml", "<project><modelVersion>4.0.0</modelVersion>$parent<artifactId>library</artifactId></project>")
        write("app/pom.xml", """
            <project><modelVersion>4.0.0</modelVersion>$parent<artifactId>app</artifactId><dependencies>
            <dependency><groupId>fixture</groupId><artifactId>library</artifactId><version>1</version></dependency>
            </dependencies></project>
        """.trimIndent())
    }

    private fun gradleBuild() {
        write("settings.gradle", "rootProject.name = 'jdk8-platform'; include 'app', 'library'\n")
        write("build.gradle", """
            subprojects {
              apply plugin: 'java-library'
              java {
                toolchain { languageVersion = JavaLanguageVersion.of(8) }
                sourceCompatibility = JavaVersion.VERSION_1_8
                targetCompatibility = JavaVersion.VERSION_1_8
              }
            }
        """.trimIndent())
        write("app/build.gradle", "dependencies { implementation project(':library') }\n")
    }

    private fun write(name: String, text: String) {
        val path = root.resolve(name)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun savedFiles() = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).filter { file ->
            root.relativize(file).none { it.toString() in setOf(".gradle", ".refactorkit") }
        }.toList().associate { file ->
            val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
            root.relativize(file).toString() to hash.joinToString("") { "%02x".format(it) }
        }
    }

    @After
    fun cleanup() {
        if (::fixture.isInitialized) {
            Files.walk(fixture).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
