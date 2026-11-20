package org.refactorkit.cli.navigation

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.java.JdtJavaSemanticAnalysisResult
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import org.refactorkit.java.JdtJavaSemanticEvidence
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaNavigationSteps {
    private val fixture = JavaNavigationFixture()
    private lateinit var semanticRoot: Path
    private lateinit var semanticSession: DaemonSession
    private lateinit var semanticSnapshotHash: String
    private var semanticIndexGeneration = 0L
    private lateinit var typeDefinition: JsonObject
    private lateinit var typeHierarchy: JsonObject
    private lateinit var enumHierarchy: JsonObject
    private lateinit var incomingCalls: JsonObject
    private lateinit var outgoingCalls: JsonObject
    private lateinit var constructorCalls: JsonObject
    private lateinit var typeImplementations: JsonObject
    private lateinit var methodImplementations: JsonObject
    private lateinit var limitedTypeImplementations: JsonObject
    private lateinit var overlayTypeImplementations: JsonObject
    private lateinit var overlayMethodImplementations: JsonObject
    private lateinit var overlayContextHash: String
    private lateinit var sourceOnlyRoot: Path
    private lateinit var sourceOnlyFile: Path
    private lateinit var sourceOnlyContent: String
    private lateinit var sourceOnlySnapshot: ProjectSnapshot
    private lateinit var sourceOnlyAnalysis: JdtJavaSemanticAnalysisResult
    private lateinit var classpathRoot: Path
    private lateinit var classpathSession: DaemonSession
    private lateinit var classpathSnapshotHash: String
    private var classpathIndexGeneration = 0L
    private lateinit var classpathCallerContent: String
    private lateinit var classpathCallHierarchy: JsonObject

    @Given("a source-only Java snapshot contains a declaration that uses java.lang.String")
    fun createSourceOnlyJavaSnapshot() {
        sourceOnlyRoot = Files.createTempDirectory("rk-java-source-only")
        sourceOnlyFile = Path.of("src/main/java/demo/SourceOnly.java")
        sourceOnlyContent = """
            package demo;
            public class SourceOnly {
                public String echo(String value) { return value; }
            }
        """.trimIndent() + "\n"
        val diskFile = sourceOnlyRoot.resolve(sourceOnlyFile)
        Files.createDirectories(diskFile.parent)
        Files.writeString(diskFile, sourceOnlyContent)
        sourceOnlySnapshot = ProjectSnapshot(
            workspace = Workspace(sourceOnlyRoot),
            modules = emptyList(),
            files = listOf(SourceFile(sourceOnlyFile, sourceOnlyContent, "java")),
        )
    }

    @When("JDT analysis runs without build roots or classpath entries")
    fun analyzeSourceOnlyJavaSnapshot() {
        sourceOnlyAnalysis = JdtJavaSemanticAnalyzer().analyze(sourceOnlySnapshot)
    }

    @Then("the source declaration and java.lang.String use have exact compiler bindings")
    fun verifySourceOnlyCompilerBindings() {
        val declaration = sourceOnlyAnalysis.symbols.single { it.qualifiedName == "demo.SourceOnly" }
        assertTrue(declaration.bindingKey?.isNotBlank() == true, declaration.toString())
        assertEquals(JdtJavaSemanticEvidence.JDT_BINDING, declaration.evidence)
        assertTrue(!declaration.recovered, declaration.toString())
        val platformUses = sourceOnlyAnalysis.bindingUses.filter {
            it.path == sourceOnlyFile && it.simpleName == "String" && it.typeQualifiedName == "java.lang.String"
        }
        assertTrue(platformUses.isNotEmpty(), sourceOnlyAnalysis.bindingUses.toString())
        assertTrue(platformUses.all { it.bindingKey.isNotBlank() && !it.recovered && it.evidence == JdtJavaSemanticEvidence.JDT_BINDING })
    }

    @Then("analysis reports no binding problems and leaves the source bytes unchanged")
    fun verifySourceOnlyWorkspaceUnchanged() {
        assertTrue(sourceOnlyAnalysis.warnings.isEmpty(), sourceOnlyAnalysis.warnings.toString())
        assertEquals(sourceOnlyContent, Files.readString(sourceOnlyRoot.resolve(sourceOnlyFile)))
    }

    @Given("a packaged Java navigation workspace contains these declarations and a compiler API consumer:")
    fun prepare(table: DataTable) = fixture.prepare(table.asMaps())

    @Given("a Maven Java daemon project calls overloaded methods from a local dependency JAR")
    fun prepareMavenDependencyCallHierarchy() {
        classpathRoot = Files.createTempDirectory("rk-java-maven-call-hierarchy")
        val dependencyJar = createOverloadedDependencyJar(classpathRoot)
        writeClasspathProjectFile("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId><artifactId>call-hierarchy</artifactId><version>1.0</version>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
              <dependencies><dependency>
                <groupId>fixture.dependency</groupId><artifactId>overloaded-api</artifactId><version>1</version>
                <scope>system</scope><systemPath>$dependencyJar</systemPath>
              </dependency></dependencies>
            </project>
        """.trimIndent())
        prepareClasspathCaller()
        classpathSession = DaemonSession()
        val opened = classpathSession.dispatch(
            "project.open", buildJsonObject { put("root", classpathRoot.toString()) },
        ).jsonObject
        classpathSnapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        classpathIndexGeneration = opened.getValue("indexGeneration").jsonPrimitive.longOrNull
            ?: error("Maven project.open omitted its index generation")
    }

    @Given("a Gradle Java daemon project calls overloaded methods from a local dependency JAR")
    fun prepareGradleDependencyCallHierarchy() {
        classpathRoot = Files.createTempDirectory("rk-java-gradle-call-hierarchy")
        val dependencyJar = createOverloadedDependencyJar(classpathRoot)
        writeClasspathProjectFile("settings.gradle.kts", "rootProject.name = \"call-hierarchy\"\n")
        writeClasspathProjectFile("build.gradle.kts", """
            plugins { java }
            dependencies { implementation(files("lib/overloaded-api.jar")) }
            java {
                sourceCompatibility = JavaVersion.VERSION_1_8
                targetCompatibility = JavaVersion.VERSION_1_8
            }
        """.trimIndent() + "\n")
        check(dependencyJar.startsWith(classpathRoot))
        prepareClasspathCaller()
        classpathSession = DaemonSession()
        val opened = classpathSession.dispatch(
            "project.open", buildJsonObject { put("root", classpathRoot.toString()) },
        ).jsonObject
        val initialHash = opened.getValue("snapshotHash").jsonPrimitive.content
        val gradleExecutable = System.getenv("REFACTORKIT_GRADLE_EXECUTABLE")?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists",
                "gradle-9.6.1-bin", "4ticwg1pgcbps2hj28r8so764", "gradle-9.6.1", "bin", "gradle")
        val synchronized = classpathSession.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", initialHash)
            put("sdkHome", System.getProperty("java.home"))
            put("gradleExecutable", gradleExecutable.toAbsolutePath().normalize().toString())
            put("gradleUserHome", classpathRoot.resolve(".gradle-user-home").toString())
            put("allowBuildExecution", true)
        }).jsonObject
        assertEquals("synchronized", synchronized.getValue("status").jsonPrimitive.content, synchronized.toString())
        classpathSnapshotHash = synchronized.getValue("snapshotHash").jsonPrimitive.content
        classpathIndexGeneration = 0L
    }

    @When("I query outgoing call hierarchy for the selected source caller")
    fun queryDependencyCallHierarchy() {
        val line = classpathCallerContent.lines().indexOfFirst { "public String invoke" in it }
        check(line >= 0) { "Call hierarchy fixture has no invoke method" }
        val character = classpathCallerContent.lines()[line].indexOf("invoke") + 1
        classpathCallHierarchy = classpathSession.dispatch("intelligence.query", buildJsonObject {
            put("requestId", "dependency-call-hierarchy-$line")
            put("expectedSnapshotHash", classpathSnapshotHash)
            if (classpathIndexGeneration > 0L) put("expectedIndexGeneration", classpathIndexGeneration)
            put("languageId", "java"); put("kind", "callHierarchy")
            put("path", "src/main/java/app/Caller.java")
            put("position", buildJsonObject { put("line", line); put("character", character) })
            put("direction", "outgoing")
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
        }).jsonObject
    }

    @Then("the call hierarchy returns distinct exact overload identities and retains the project-local callee")
    fun verifyDependencyCallHierarchyIdentities() {
        assertEquals("ready", classpathCallHierarchy.getValue("status").jsonPrimitive.content, classpathCallHierarchy.toString())
        assertEquals("workspace-direct-invocations", classpathCallHierarchy.getValue("scope").jsonPrimitive.content)
        val items = classpathCallHierarchy.getValue("items").jsonArray.map { it.jsonObject }
        val overloads = items.filter { item ->
            item["methodIdentity"]?.jsonObject?.get("qualifiedName")?.jsonPrimitive?.content
                ?.startsWith("dependency.Overloads#pick(") == true
        }
        assertEquals(2, overloads.size, "Both dependency overloads must appear: $items")
        val descriptors = overloads.map { it.getValue("methodIdentity").jsonObject.getValue("jvmIdentity").jsonObject
            .getValue("descriptor").jsonPrimitive.content }.toSet()
        assertEquals(setOf("(Ljava/lang/String;)Ljava/lang/String;", "(I)Ljava/lang/String;"), descriptors)
        assertEquals(2, overloads.map {
            it.getValue("methodIdentity").jsonObject.getValue("bindingKey").jsonPrimitive.content
        }.toSet().size)
        val local = items.single { item ->
            (item["method"] as? JsonObject)?.get("id")?.jsonPrimitive?.content == "app.Caller#local(java.lang.String)"
        }
        assertEquals("available", local.getValue("sourceNavigation").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("src/main/java/app/Caller.java", local.getValue("sourceNavigation").jsonObject
            .getValue("location").jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals(1, local.getValue("callSites").jsonArray.size)
    }

    @Then("binary-only dependency targets have no fabricated source location and the result remains explicitly partial")
    fun verifyBinaryOnlyCallHierarchyTargets() {
        val navigationCapability = classpathSession.dispatch("server.capabilities", null).jsonObject
            .getValue("methods").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "intelligence.query" }
        assertEquals("true", navigationCapability.getValue("features").jsonObject
            .getValue("javaResolvedClasspathCallHierarchyTargets").jsonPrimitive.content)
        val items = classpathCallHierarchy.getValue("items").jsonArray.map { it.jsonObject }
        val overloads = items.filter { item ->
            item["methodIdentity"]?.jsonObject?.get("qualifiedName")?.jsonPrimitive?.content
                ?.startsWith("dependency.Overloads#pick(") == true
        }
        assertEquals(2, overloads.size, "Resolved binary-only methods must remain visible: $items")
        overloads.forEach { item ->
            assertEquals(JsonNull, item.getValue("method"), "Binary-only methods have no workspace Symbol")
            val navigation = item.getValue("sourceNavigation").jsonObject
            assertEquals("unavailable", navigation.getValue("status").jsonPrimitive.content)
            assertEquals("classpathSourceUnavailable", navigation.getValue("reason").jsonPrimitive.content)
            assertEquals(JsonNull, navigation.getValue("location"))
        }
        assertEquals("partial", classpathCallHierarchy.getValue("completeness").jsonPrimitive.content)
        assertFalse(classpathCallHierarchy.getValue("complete").jsonPrimitive.content.toBoolean())
    }

    @When("the packaged CLI lists and locates those members before and after an unrelated type-resolution failure")
    fun navigate() = fixture.navigate()

    @Then("each annotated member is listed once at its exact source location and signed callable definitions work with the embedded compiler APIs")
    fun verifyDeclarations() = fixture.verifyDeclarations()

    @Then("absent symbols and unavailable semantic analysis have distinct diagnostics while every read preserves the workspace bytes")
    fun verifyRefusalsAndReadOnly() = fixture.verifyRefusalsAndReadOnly()

    @Given("a Java daemon workspace contains bound type and method relationships")
    fun createBoundJavaWorkspace() {
        semanticRoot = Files.createTempDirectory("rk-java-semantic-navigation")
        writeSemantic("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>demo</groupId><artifactId>navigation</artifactId><version>1.0-SNAPSHOT</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins><plugin>
                <groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration><release>21</release><proc>none</proc></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent())
        writeSemantic("src/main/java/demo/Base.java", """
            package demo;
            public class Base {
                public Base() {}
            }
        """.trimIndent())
        writeSemantic("src/main/java/demo/Child.java", """
            package demo;
            public class Child extends Base {}
        """.trimIndent())
        writeSemantic("src/main/java/demo/Marker.java", """
            package demo;
            public interface Marker {}
        """.trimIndent())
        writeSemantic("src/main/java/demo/Handler.java", """
            package demo;
            public interface Handler {
                void handle();
            }
        """.trimIndent())
        writeSemantic("src/main/java/demo/AbstractHandler.java", """
            package demo;
            public abstract class AbstractHandler implements Handler {}
        """.trimIndent())
        writeSemantic("src/main/java/demo/PrimaryHandler.java", """
            package demo;
            public class PrimaryHandler extends AbstractHandler {
                @Override public void handle() {}
            }
        """.trimIndent())
        writeSemantic("src/main/java/demo/SecondaryHandler.java", """
            package demo;
            public class SecondaryHandler extends PrimaryHandler {}
        """.trimIndent())
        writeSemantic("src/main/java/demo/UnrelatedHandler.java", """
            package demo;
            public class UnrelatedHandler {
                public void handle() {}
            }
        """.trimIndent())
        writeSemantic("src/main/java/demo/Kind.java", """
            package demo;
            public enum Kind implements Marker { VALUE }
        """.trimIndent())
        writeSemantic("src/main/java/demo/Service.java", """
            package demo;
            public class Service {
                public Base run() { return new Base(); }
            }
        """.trimIndent())
        writeSemantic("src/main/java/demo/Client.java", """
            package demo;
            public class Client {
                private final Service service = new Service();
                public void use() {
                    service.run();
                }
            }
        """.trimIndent())
        semanticSession = DaemonSession()
        val opened = semanticSession.dispatch(
            "project.open", buildJsonObject { put("root", semanticRoot.toString()) },
        ).jsonObject
        semanticSnapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        semanticIndexGeneration = opened.getValue("indexGeneration").jsonPrimitive.longOrNull
            ?: error("project.open did not return an index generation")
    }

    @When("I query type definition, type hierarchy, call hierarchy, and concrete type and method implementations")
    fun queryNavigation() {
        val client = "src/main/java/demo/Client.java"
        val service = "src/main/java/demo/Service.java"
        val base = "src/main/java/demo/Base.java"
        typeDefinition = semanticQuery(
            "typeDefinition", client, 4, "        service.run();".indexOf("service") + 1,
        )
        typeHierarchy = semanticQuery(
            "typeHierarchy", base, 1, "public class Base {".indexOf("Base") + 1, "both",
        )
        enumHierarchy = semanticQuery(
            "typeHierarchy", "src/main/java/demo/Marker.java", 1,
            "public interface Marker {}".indexOf("Marker") + 1, "subtypes",
        )
        incomingCalls = semanticQuery(
            "callHierarchy", service, 2, "    public Base run() { return new Base(); }".indexOf("run") + 1,
            "incoming",
        )
        outgoingCalls = semanticQuery(
            "callHierarchy", client, 3, "    public void use() {".indexOf("use") + 1, "outgoing",
        )
        constructorCalls = semanticQuery(
            "callHierarchy", service, 2, "    public Base run() { return new Base(); }".indexOf("run") + 1,
            "outgoing",
        )
        val handler = "src/main/java/demo/Handler.java"
        typeImplementations = semanticQuery(
            "implementations", handler, 1, "public interface Handler {".indexOf("Handler") + 1,
        )
        limitedTypeImplementations = semanticQuery(
            "implementations", handler, 1, "public interface Handler {".indexOf("Handler") + 1, limit = 1,
        )
        methodImplementations = semanticQuery(
            "implementations", handler, 2, "    void handle();".indexOf("handle") + 1,
        )
        val sdkHome = Path.of(System.getProperty("java.home"))
        val context = semanticSession.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", semanticSnapshotHash)
            put("sdkHome", sdkHome.toString())
        }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        overlayContextHash = context.getValue("contextHash").jsonPrimitive.content
        overlayTypeImplementations = overlayImplementations(
            handler, 1, "public interface Handler {".indexOf("Handler") + 1, sdkHome,
        )
        overlayMethodImplementations = overlayImplementations(
            handler, 2, "    void handle();".indexOf("handle") + 1, sdkHome,
        )
    }

    @Then("each query identifies the expected source declaration and call site")
    fun verifyNavigationTargets() {
        val typeTarget = typeDefinition.getValue("target").jsonObject
        assertEquals("demo.Service", typeTarget.getValue("id").jsonPrimitive.content)
        assertEquals(
            "src/main/java/demo/Service.java",
            typeTarget.getValue("location").jsonObject.getValue("path").jsonPrimitive.content,
        )
        assertTrue(typeHierarchy.getValue("items").jsonArray.any { item ->
            val value = item.jsonObject
            value.getValue("relation").jsonPrimitive.content == "subtype" &&
                value.getValue("symbol").jsonObject.getValue("id").jsonPrimitive.content == "demo.Child"
        })
        assertEquals("workspace-source", typeHierarchy.getValue("scope").jsonPrimitive.content)
        assertEquals("both", typeHierarchy.getValue("direction").jsonPrimitive.content)
        assertTrue(enumHierarchy.getValue("items").jsonArray.any { item ->
            val value = item.jsonObject
            value.getValue("relation").jsonPrimitive.content == "subtype" &&
                value.getValue("symbol").jsonObject.getValue("id").jsonPrimitive.content == "demo.Kind"
        })
        assertTrue(incomingCalls.getValue("items").jsonArray.any { item ->
            item.jsonObject.getValue("method").jsonObject.getValue("id").jsonPrimitive.content == "demo.Client#use()" &&
                item.jsonObject.getValue("callSites").jsonArray.isNotEmpty()
        })
        assertEquals("workspace-direct-invocations", incomingCalls.getValue("scope").jsonPrimitive.content)
        assertEquals("incoming", incomingCalls.getValue("direction").jsonPrimitive.content)
        assertEquals("partial", incomingCalls.getValue("completeness").jsonPrimitive.content)
        assertTrue(outgoingCalls.getValue("items").jsonArray.any { item ->
            item.jsonObject.getValue("method").jsonObject.getValue("id").jsonPrimitive.content == "demo.Service#run()" &&
                item.jsonObject.getValue("callSites").jsonArray.isNotEmpty()
        })
        assertEquals("outgoing", outgoingCalls.getValue("direction").jsonPrimitive.content)
        assertTrue(constructorCalls.getValue("items").jsonArray.any { item ->
            item.jsonObject.getValue("method").jsonObject.getValue("id").jsonPrimitive.content == "demo.Base#<init>()" &&
                item.jsonObject.getValue("callSites").jsonArray.isNotEmpty()
        })
    }

    @Then("implementation lookup returns exact concrete workspace types and overriding methods")
    fun verifyImplementations() {
        assertEquals("type", typeImplementations.getValue("targetKind").jsonPrimitive.content)
        assertEquals("workspace-source", typeImplementations.getValue("scope").jsonPrimitive.content)
        assertEquals(true, typeImplementations.getValue("complete").jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("demo.PrimaryHandler", "demo.SecondaryHandler"),
            typeImplementations.getValue("items").jsonArray.map { item ->
                val value = item.jsonObject
                assertEquals("concreteType", value.getValue("relation").jsonPrimitive.content)
                value.getValue("symbol").jsonObject.getValue("id").jsonPrimitive.content
            },
        )
        assertEquals("method", methodImplementations.getValue("targetKind").jsonPrimitive.content)
        assertEquals("workspace-source", methodImplementations.getValue("scope").jsonPrimitive.content)
        assertEquals(true, methodImplementations.getValue("complete").jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("demo.PrimaryHandler#handle()"),
            methodImplementations.getValue("items").jsonArray.map { item ->
                val value = item.jsonObject
                assertEquals("concreteMethod", value.getValue("relation").jsonPrimitive.content)
                value.getValue("symbol").jsonObject.getValue("id").jsonPrimitive.content
            },
        )
        assertEquals(2, typeImplementations.getValue("total").jsonPrimitive.int)
        assertEquals(1, methodImplementations.getValue("total").jsonPrimitive.int)
        assertEquals(1, limitedTypeImplementations.getValue("returned").jsonPrimitive.int)
        assertEquals(2, limitedTypeImplementations.getValue("total").jsonPrimitive.int)
        assertEquals(true, limitedTypeImplementations.getValue("truncated").jsonPrimitive.content.toBoolean())
        assertEquals(false, limitedTypeImplementations.getValue("complete").jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf("demo.OverlayHandler", "demo.PrimaryHandler", "demo.SecondaryHandler"),
            implementationIds(overlayTypeImplementations),
        )
        assertEquals(
            implementationIds(methodImplementations),
            implementationIds(overlayMethodImplementations),
        )
        assertEquals(
            "package demo;\npublic interface Handler {\n    void handle();\n}\n",
            Files.readString(semanticRoot.resolve("src/main/java/demo/Handler.java")),
        )
        listOf(overlayTypeImplementations, overlayMethodImplementations).forEach { result ->
            assertEquals("ready", result.getValue("status").jsonPrimitive.content)
            assertEquals("compiler", result.getValue("evidence").jsonPrimitive.content)
            assertEquals("java-jdt-bindings-v1", result.getValue("providerId").jsonPrimitive.content)
            assertEquals("immutable-editor-overlay", result.getValue("sourceAuthority").jsonObject
                .getValue("kind").jsonPrimitive.content)
            assertEquals(semanticSnapshotHash, result.getValue("snapshotHash").jsonPrimitive.content)
            assertEquals(overlayContextHash, result.getValue("contextHash").jsonPrimitive.content)
            assertTrue(result.getValue("providerSnapshotHash").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
            assertTrue(result.getValue("provenanceHash").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
            assertEquals("workspace-source", result.getValue("scope").jsonPrimitive.content)
            assertEquals(true, result.getValue("complete").jsonPrimitive.content.toBoolean())
        }

        val unresolvedRoot = Files.createTempDirectory("rk-java-unresolved-implementation")
        val unresolvedSession = DaemonSession()
        try {
            val unresolvedSource = unresolvedRoot.resolve("src/main/java/demo/UnknownConsumer.java")
            Files.createDirectories(unresolvedSource.parent)
            Files.writeString(unresolvedSource, "package demo;\npublic class UnknownConsumer extends MissingBase {}\n")
            val opened = unresolvedSession.dispatch(
                "project.open", buildJsonObject { put("root", unresolvedRoot.toString()) },
            ).jsonObject
            val unresolved = unresolvedSession.dispatch("intelligence.query", buildJsonObject {
                put("requestId", "navigation-implementations-unresolved")
                put("expectedSnapshotHash", opened.getValue("snapshotHash").jsonPrimitive.content)
                put("expectedIndexGeneration", opened.getValue("indexGeneration").jsonPrimitive.content)
                put("languageId", "java"); put("kind", "implementations")
                put("path", "src/main/java/demo/UnknownConsumer.java")
                put("position", buildJsonObject {
                    put("line", 1); put("character", "public class UnknownConsumer extends ".length + 1)
                })
                put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
            }).jsonObject
            assertEquals("refused", unresolved.getValue("status").jsonPrimitive.content)
            assertTrue(unresolved.getValue("error").jsonObject.getValue("code").jsonPrimitive.content
                .startsWith("java.implementations."))
            assertTrue("evidence" !in unresolved)
        } finally {
            unresolvedSession.close()
            unresolvedRoot.toFile().deleteRecursively()
        }
    }

    @Then("every result carries compiler evidence and the opened snapshot identity")
    fun verifyCompilerAndSnapshotAuthority() {
        listOf(typeDefinition, typeHierarchy, enumHierarchy, incomingCalls, outgoingCalls, constructorCalls,
            typeImplementations, methodImplementations, limitedTypeImplementations).forEach { result ->
            assertEquals("ready", result.getValue("status").jsonPrimitive.content)
            assertEquals("compiler", result.getValue("evidence").jsonPrimitive.content)
            assertEquals("java-jdt-bindings-v1", result.getValue("providerId").jsonPrimitive.content)
            assertEquals("eclipse-jdt-3.44-bindings-v1", result.getValue("backend").jsonPrimitive.content)
            assertEquals(semanticSnapshotHash, result.getValue("snapshotHash").jsonPrimitive.content)
            assertTrue(result.getValue("provenanceHash").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
            val authority = result.getValue("sourceAuthority").jsonObject
            assertEquals("saved-snapshot", authority.getValue("kind").jsonPrimitive.content)
            assertTrue(authority.getValue("contentSha256").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
            assertTrue(result.getValue("cache").jsonObject.getValue("entries").jsonPrimitive.int > 0)
        }
    }

    @After
    fun cleanup() {
        fixture.close()
        if (::semanticSession.isInitialized) semanticSession.close()
        if (::classpathSession.isInitialized) classpathSession.close()
        if (::semanticRoot.isInitialized) semanticRoot.toFile().deleteRecursively()
        if (::sourceOnlyRoot.isInitialized) sourceOnlyRoot.toFile().deleteRecursively()
        if (::classpathRoot.isInitialized) classpathRoot.toFile().deleteRecursively()
    }

    private fun semanticQuery(
        kind: String,
        path: String,
        line: Int,
        character: Int,
        direction: String? = null,
        limit: Int? = null,
    ): JsonObject {
        val response = semanticSession.dispatch("intelligence.query", buildJsonObject {
            put("requestId", "navigation-$kind-${direction ?: "type"}-${limit ?: "default"}-$line")
            put("expectedSnapshotHash", semanticSnapshotHash)
            put("expectedIndexGeneration", semanticIndexGeneration)
            put("languageId", "java")
            put("kind", kind)
            put("path", path)
            put("position", buildJsonObject { put("line", line); put("character", character) })
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
            direction?.let { put("direction", it) }
            limit?.let { put("limit", it) }
        }).jsonObject
        response["indexGeneration"]?.jsonPrimitive?.longOrNull?.let { semanticIndexGeneration = it }
        return response
    }

    private fun implementationIds(result: JsonObject): List<String> = result.getValue("items").jsonArray.map { item ->
        item.jsonObject.getValue("symbol").jsonObject.getValue("id").jsonPrimitive.content
    }

    private fun overlayImplementations(
        path: String,
        line: Int,
        character: Int,
        sdkHome: Path,
    ): JsonObject {
        val response = semanticSession.dispatch("intelligence.query", buildJsonObject {
        put("requestId", "navigation-implementations-overlay-$line")
        put("expectedSnapshotHash", semanticSnapshotHash)
        put("expectedIndexGeneration", semanticIndexGeneration)
        put("languageId", "java"); put("kind", "implementations"); put("path", path)
        put("position", buildJsonObject { put("line", line); put("character", character) })
        put("javaContext", buildJsonObject {
            put("sdkHome", sdkHome.toString()); put("expectedContextHash", overlayContextHash)
        })
        put("sourceAuthority", buildJsonObject {
            put("kind", "immutable-editor-overlay")
            put("documents", buildJsonArray {
                val savedContent = Files.readString(semanticRoot.resolve(path))
                add(buildJsonObject {
                    put("path", path); put("version", 1)
                    put("content", savedContent.replace(
                        "    void handle();\n}",
                        "    void handle();\n}\nclass OverlayHandler extends PrimaryHandler {}",
                    ))
                })
            })
        })
        }).jsonObject
        assertEquals("ready", response.getValue("status").jsonPrimitive.content, response.toString())
        return response
    }

    private fun writeSemantic(relative: String, content: String) {
        val path = semanticRoot.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, "$content\n")
    }

    private fun prepareClasspathCaller() {
        classpathCallerContent = """
            package app;
            import dependency.Overloads;
            public final class Caller {
                private final Overloads target = new Overloads();
                public String invoke(String value) {
                    target.pick(value);
                    target.pick(7);
                    return local(value);
                }
                public String local(String value) { return value; }
            }
        """.trimIndent() + "\n"
        writeClasspathProjectFile("src/main/java/app/Caller.java", classpathCallerContent)
    }

    private fun createOverloadedDependencyJar(projectRoot: Path): Path {
        val source = projectRoot.resolve("dependency-source/dependency/Overloads.java")
        val classes = projectRoot.resolve("dependency-classes")
        val jar = projectRoot.resolve("lib/overloaded-api.jar")
        Files.createDirectories(source.parent)
        Files.createDirectories(classes)
        Files.writeString(source, """
            package dependency;
            public final class Overloads {
                public String pick(String value) { return value; }
                public String pick(int value) { return Integer.toString(value); }
            }
        """.trimIndent() + "\n")
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("A JDK compiler is required for the local dependency fixture")
        val compilerOutput = ByteArrayOutputStream()
        val exit = compiler.run(null, null, compilerOutput,
            "--release", "8", "-d", classes.toString(), source.toString())
        assertEquals(0, exit, compilerOutput.toString(Charsets.UTF_8))
        Files.createDirectories(jar.parent)
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            Files.walk(classes).use { paths ->
                paths.filter { Files.isRegularFile(it) }.sorted().forEach { classFile ->
                    val entryName = classes.relativize(classFile).toString().replace('\\', '/')
                    output.putNextEntry(JarEntry(entryName).apply { time = 0L })
                    Files.copy(classFile, output)
                    output.closeEntry()
                }
            }
        }
        return jar.toAbsolutePath().normalize()
    }

    private fun writeClasspathProjectFile(relative: String, content: String) {
        val file = classpathRoot.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
}
