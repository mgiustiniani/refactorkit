package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.JsonRpcException
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaMoveClassOperationDispatcher
import org.refactorkit.java.JavaMoveClassOperationOutcome
import org.refactorkit.java.JavaProjectScanner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class JavaMoveClassCrossModuleSteps {
    private lateinit var root: Path
    private lateinit var adapter: JavaLanguageAdapter
    private lateinit var originalFiles: Map<String, String>
    private lateinit var outcome: JavaMoveClassOperationOutcome
    private lateinit var daemon: DaemonSession
    private lateinit var daemonSnapshotHash: String
    private lateinit var daemonContext: JsonObject
    private lateinit var originalWorkspaceHashes: Map<String, String>
    private var daemonSelection: JsonObject? = null
    private var daemonExpectedTargetPackage: String = "fixtures.library"
    private var daemonSelectedTargetModuleId: String? = null
    private var daemonPreview: JsonObject? = null
    private var daemonFailure: JsonRpcException? = null

    @Given("a clean two-module Maven reactor whose client module depends on the target package module")
    fun createReactor() {
        root = Files.createTempDirectory("refactorkit-move-class-cross-module-")
        write("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.acme</groupId><artifactId>reactor</artifactId><version>1.0.0</version>
              <packaging>pom</packaging>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
              <modules><module>api</module><module>client</module></modules>
            </project>
        """.trimIndent())
        write("api/pom.xml", childPom("api"))
        write("client/pom.xml", """
            ${childPom("client").removeSuffix("</project>")}
              <dependencies><dependency>
                <groupId>com.acme</groupId><artifactId>api</artifactId><version>1.0.0</version>
              </dependency></dependencies>
            </project>
        """.trimIndent())
        write("api/src/main/java/com/acme/api/ApiMarker.java", """
            package com.acme.api;
            public final class ApiMarker {}
        """.trimIndent())
        write("client/src/main/java/com/acme/legacy/Product.java", """
            package com.acme.legacy;
            public final class Product {
                public String name() { return "product"; }
            }
        """.trimIndent())
        write("client/src/main/java/com/acme/client/ProductClient.java", """
            package com.acme.client;
            import com.acme.legacy.Product;
            public final class ProductClient {
                public Product create() { return new Product(); }
            }
        """.trimIndent())
        originalFiles = workspaceFiles()
        adapter = JavaLanguageAdapter()
    }

    @When("I preview moving com.acme.legacy.Product into package com.acme.api")
    fun previewMove() {
        val snapshot = JavaProjectScanner().scan(root)
        outcome = JavaMoveClassOperationDispatcher(adapter).preview(
            snapshot,
            "com.acme.legacy.Product",
            "com.acme.api",
        )
    }

    @Given("a six-module Maven reactor for the IDE Product migration with generated sources and compiled outputs")
    fun createIdeProductReactor() {
        root = Files.createTempDirectory("refactorkit-ide-product-move-class-")
        write("maven/pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion><groupId>ide.reference</groupId>
              <artifactId>java-effective-project</artifactId><version>1.0</version><packaging>pom</packaging>
              <modules><module>processor</module><module>library</module><module>runtime</module>
                <module>testsupport</module><module>unrelated</module><module>app</module></modules>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin></plugins></build>
            </project>
        """.trimIndent())
        write("maven/processor/pom.xml", productModulePom("processor", """
            <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-compiler-plugin</artifactId><configuration><proc>none</proc></configuration>
            </plugin></plugins></build>
        """.trimIndent()))
        listOf("library", "runtime", "testsupport", "unrelated").forEach { module ->
            write("maven/$module/pom.xml", productModulePom(module))
        }
        write("maven/app/pom.xml", productModulePom("app", """
            <dependencies>
              ${productDependency("library")}
              ${productDependency("processor", "provided")}
              ${productDependency("runtime", "runtime")}
              ${productDependency("testsupport", "test")}
            </dependencies>
            <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-compiler-plugin</artifactId><configuration>
                <annotationProcessorPaths><path><groupId>ide.reference</groupId>
                  <artifactId>processor</artifactId><version>1.0</version></path></annotationProcessorPaths>
                <annotationProcessors><annotationProcessor>fixtures.processor.GreetingProcessor</annotationProcessor></annotationProcessors>
              </configuration></plugin></plugins></build>
        """.trimIndent()))

        write("maven/processor/src/main/java/fixtures/processor/GenerateGreeting.java", """
            package fixtures.processor;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.SOURCE)
            @Target(ElementType.TYPE)
            public @interface GenerateGreeting {}
        """.trimIndent())
        write("maven/processor/src/main/java/fixtures/processor/GreetingProcessor.java", """
            package fixtures.processor;
            import java.io.*;
            import java.util.*;
            import javax.annotation.processing.*;
            import javax.lang.model.SourceVersion;
            import javax.lang.model.element.TypeElement;
            import javax.tools.StandardLocation;
            @SupportedAnnotationTypes("fixtures.processor.GenerateGreeting")
            @SupportedSourceVersion(SourceVersion.RELEASE_17)
            public final class GreetingProcessor extends AbstractProcessor {
              private boolean generated;
              @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                if (generated || round.processingOver() || round.getElementsAnnotatedWith(GenerateGreeting.class).isEmpty()) return false;
                try (Writer writer = processingEnv.getFiler().createSourceFile("generated.GeneratedGreeting").openWriter()) {
                  writer.write("package generated; public final class GeneratedGreeting { public static String value() { return \\\"generated-greeting\\\"; } }");
                } catch (IOException exception) { throw new UncheckedIOException(exception); }
                try (Writer writer = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", "processor-jdk-home.txt").openWriter()) {
                  writer.write(System.getProperty("java.home"));
                } catch (IOException exception) { throw new UncheckedIOException(exception); }
                generated = true;
                return true;
              }
            }
        """.trimIndent())
        write("maven/library/src/main/java/fixtures/library/BaseGreeting.java", """
            package fixtures.library;
            public class BaseGreeting { public String message(int count) { return "count:" + count; } }
        """.trimIndent())
        write("maven/library/src/main/java/fixtures/library/GreetingService.java", """
            package fixtures.library;
            public final class GreetingService extends BaseGreeting {
              public String message(String name) { return "hello " + name; }
            }
        """.trimIndent())
        write("maven/runtime/src/main/java/fixtures/runtime/RuntimeToken.java", """
            package fixtures.runtime;
            public final class RuntimeToken { public static final String VALUE = "runtime-token"; }
        """.trimIndent())
        write("maven/testsupport/src/main/java/fixtures/testsupport/TestToken.java", """
            package fixtures.testsupport;
            public final class TestToken { public static final String VALUE = "test-token"; }
        """.trimIndent())
        write("maven/unrelated/src/main/java/pkg/Peer.java", """
            package pkg;
            public final class Peer { public static String value() { return "WRONG_UNRELATED_PEER"; } }
        """.trimIndent())
        write("maven/unrelated/src/main/java/fixtures/unrelated/Hidden.java", """
            package fixtures.unrelated;
            public final class Hidden {}
        """.trimIndent())
        write("maven/unrelated/src/main/java/fixtures/unrelated/UsesHidden.java", """
            package fixtures.unrelated;
            public final class UsesHidden {
              private final Hidden hidden = new Hidden();
              public Hidden value() { return hidden; }
            }
        """.trimIndent())
        write("maven/app/src/main/java/pkg/Runner.java", """
            package pkg;
            import fixtures.library.GreetingService;
            import fixtures.processor.GenerateGreeting;
            import generated.GeneratedGreeting;
            @GenerateGreeting
            public final class Runner {
              public static String result() throws Exception {
                GreetingService service = new GreetingService();
                String runtime = (String) Class.forName("fixtures.runtime.RuntimeToken").getField("VALUE").get(null);
                return service.message("JVM") + "|" + service.message(2) + "|" + GeneratedGreeting.value() + "|" + runtime + "|" + Peer.value();
              }
            }
            class Peer { static String value() { return "main-peer"; } }
        """.trimIndent())
        write("maven/app/src/main/java/pkg/MigrateToLibrary.java", """
            package pkg;
            public final class MigrateToLibrary {
              public static String value() { return "moved"; }
            }
        """.trimIndent())
        write("maven/app/src/main/java/pkg/MoveClient.java", """
            package pkg;
            public final class MoveClient {
              public String value() { return MigrateToLibrary.value(); }
            }
        """.trimIndent())
        write("maven/app/src/test/java/pkg/Runner.java", """
            package pkg;
            class Unrelated { static String value() { return "test-source-with-same-relative-path"; } }
        """.trimIndent())
        write("maven/app/src/test/java/pkg/RunnerTest.java", """
            package pkg;
            import fixtures.testsupport.TestToken;
            import generated.GeneratedGreeting;
            public final class RunnerTest {
              String values = TestToken.VALUE + GeneratedGreeting.value();
            }
        """.trimIndent())
        write("maven/app/src/test/java/pkg/MoveClientTest.java", """
            package pkg;
            public final class MoveClientTest {
              public String value() { return MigrateToLibrary.value(); }
            }
        """.trimIndent())
        write("maven/app/target/generated-sources/annotations/generated/GeneratedGreeting.java", """
            package generated;
            public final class GeneratedGreeting { public static String value() { return "generated-greeting"; } }
        """.trimIndent())
        compileProductFixtureOutputs()

        adapter = JavaLanguageAdapter()
        daemon = DaemonSession()
        val opened = daemon.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
        daemonSnapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        val sdkHome = Path.of(System.getProperty("java.home")).toRealPath()
        daemonContext = daemon.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", daemonSnapshotHash)
            put("sdkHome", sdkHome.toString())
        }).jsonObject
        assertEquals("available", daemonContext.getValue("status").jsonPrimitive.content, daemonContext.toString())
        originalWorkspaceHashes = workspaceHashes()
    }

    @When("I preview moving pkg.MigrateToLibrary into fixtures.library with an acquired Java context")
    fun previewIdeProductMove() {
        previewIdeProductMove("fixtures.library", null)
    }

    @When("I preview moving pkg.MigrateToLibrary into new package fixtures.newlibrary in module library with an acquired Java context")
    fun previewIdeProductMoveToSelectedModule() {
        val libraryModuleId = JavaProjectScanner().scan(root).buildModels
            .single { it.providerId == "maven-effective-v1" }
            .modules.single { it.coordinates?.artifactId == "library" }
            .id
        previewIdeProductMove("fixtures.newlibrary", libraryModuleId)
    }

    private fun previewIdeProductMove(targetPackage: String, targetModuleId: String?) {
        daemonExpectedTargetPackage = targetPackage
        daemonSelectedTargetModuleId = targetModuleId
        val sdkHome = Path.of(System.getProperty("java.home")).toRealPath()
        if (targetModuleId != null) {
            val selectedPath = "maven/app/src/main/java/pkg/MigrateToLibrary.java"
            val source = Files.readString(root.resolve(selectedPath))
            val lines = source.lines()
            val selectedLine = lines.indexOfFirst { "class MigrateToLibrary" in it }
            assertTrue(selectedLine >= 0, source)
            val selectedLineText = lines[selectedLine]
            val selection = daemon.dispatch("intelligence.query", buildJsonObject {
                put("requestId", "move-class-target-module-selection")
                put("kind", "mutationSelection")
                put("languageId", "java")
                put("path", selectedPath)
                put("position", buildJsonObject {
                    put("line", selectedLine)
                    put("character", selectedLineText.indexOf("MigrateToLibrary") + 1)
                })
                put("expectedSnapshotHash", daemonSnapshotHash)
                put("javaContext", buildJsonObject {
                    put("sdkHome", sdkHome.toString())
                    put("expectedContextHash", daemonContext.getValue("contextHash").jsonPrimitive.content)
                })
                put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
            }).jsonObject
            assertEquals("ready", selection.getValue("status").jsonPrimitive.content, selection.toString())
            assertEquals("mutationSelection", selection.getValue("kind").jsonPrimitive.content)
            daemonSelection = selection.getValue("selection").jsonObject
            assertEquals("class", daemonSelection!!.getValue("symbolKind").jsonPrimitive.content)
            assertEquals("pkg.MigrateToLibrary", daemonSelection!!.getValue("symbolId").jsonPrimitive.content)
        }
        try {
            daemonPreview = daemon.dispatch("refactor.preview", buildJsonObject {
                put("operation", "moveClass")
                put("symbol", daemonSelection?.getValue("symbolId")?.jsonPrimitive?.content ?: "pkg.MigrateToLibrary")
                put("languageId", "java")
                put("expectedSnapshotHash", daemonSnapshotHash)
                put("javaContext", buildJsonObject {
                    put("sdkHome", sdkHome.toString())
                    put("expectedContextHash", daemonContext.getValue("contextHash").jsonPrimitive.content)
                })
                daemonSelection?.let { put("javaSelection", it) }
                put("arguments", buildJsonObject {
                    put("targetPackage", targetPackage)
                    targetModuleId?.let { put("targetModuleId", it) }
                })
            }).jsonObject
        } catch (refused: JsonRpcException) {
            daemonFailure = refused
        }
    }

    @Then("the daemon advertises the bounded moveClass target-module selection contract")
    fun assertTargetModuleSelectionCapability() {
        val capabilities = daemon.dispatch("server.capabilities", null).jsonObject
        val javaContextCapabilities = capabilities.getValue("javaContextRefactoring").jsonObject
        val moveClass = javaContextCapabilities.getValue("operationCapabilities").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "moveClass" }
        assertEquals(
            buildJsonObject {
                put("schemaVersion", "java-move-class-target-module-v1")
                put("argument", "targetModuleId")
                put("identity", "module-id-within-source-provider")
                put("requiresAvailableContext", true)
                put("sourceSetKind", "main")
                put("requiresUniqueNonGeneratedRoot", true)
            },
            moveClass.getValue("targetModuleSelection").jsonObject,
        )

        val missingContext = runCatching {
            daemon.dispatch("refactor.preview", buildJsonObject {
                put("operation", "moveClass")
                put("symbol", "pkg.MigrateToLibrary")
                put("languageId", "java")
                put("expectedSnapshotHash", daemonSnapshotHash)
                put("arguments", buildJsonObject {
                    put("targetPackage", "fixtures.newlibrary")
                    put("targetModuleId", requireNotNull(daemonSelectedTargetModuleId))
                })
            })
        }.exceptionOrNull() as? JsonRpcException
        assertNotNull(missingContext, "targetModuleId without explicit Java context must be refused")
        assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, missingContext.code)
        assertEquals(
            "java.context.authorityRequired",
            missingContext.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content,
        )

        val unknownModule = runCatching {
            daemon.dispatch("refactor.preview", buildJsonObject {
                put("operation", "moveClass")
                put("symbol", "pkg.MigrateToLibrary")
                put("languageId", "java")
                put("expectedSnapshotHash", daemonSnapshotHash)
                put("javaContext", buildJsonObject {
                    put("sdkHome", Path.of(System.getProperty("java.home")).toRealPath().toString())
                    put("expectedContextHash", daemonContext.getValue("contextHash").jsonPrimitive.content)
                })
                put("arguments", buildJsonObject {
                    put("targetPackage", "fixtures.newlibrary")
                    put("targetModuleId", "missing-module")
                })
            })
        }.exceptionOrNull() as? JsonRpcException
        assertNotNull(unknownModule, "unknown provider-scoped module ID must be refused")
        assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, unknownModule.code)
        assertEquals(
            "java.moveClass.targetModuleUnavailable",
            unknownModule.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content,
        )
    }

    @Then("the daemon previews the exact Product move with staged JDT binding evidence and clean diagnostics without writing files")
    fun assertIdeProductMovePreview() {
        assertNull(daemonFailure, "daemon rejected IDE Product move: $daemonFailure")
        val preview = assertNotNull(daemonPreview, "daemon did not return a move preview")
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
        assertEquals("JDT_BINDING", preview.getValue("evidence").jsonPrimitive.content)
        daemonSelection?.let { selection ->
            assertEquals(selection.getValue("selectorHash").jsonPrimitive.content,
                preview.getValue("selectionHash").jsonPrimitive.content)
        }
        assertEquals(daemonContext.getValue("contextHash").jsonPrimitive.content,
            preview.getValue("contextHash").jsonPrimitive.content)
        val rows = preview.getValue("structuredDiff").jsonArray.map { it.jsonObject }
        assertTrue(rows.any { row ->
            row.getValue("type").jsonPrimitive.content == "renameFile" &&
                row.getValue("path").jsonPrimitive.content == "maven/app/src/main/java/pkg/MigrateToLibrary.java" &&
                row.getValue("newPath").jsonPrimitive.content == productTargetPath()
        }, rows.toString())
        assertTrue(rows.any { row -> row.getValue("path").jsonPrimitive.content ==
            "maven/app/src/main/java/pkg/MoveClient.java" }, rows.toString())
        val errors = preview.getValue("diagnosticsAfterPreview").jsonArray.filter { diagnostic ->
            diagnostic.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
        }
        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(originalWorkspaceHashes, workspaceHashes(), "daemon preview must not write to workspace inputs or outputs")
    }

    @Then("managed apply and rollback restore the exact Product workspace images")
    fun applyAndRollbackSelectedModuleMove() {
        val preview = assertNotNull(daemonPreview, "daemon did not return a move preview")
        val sdkHome = Path.of(System.getProperty("java.home")).toRealPath()
        val authority = buildJsonObject {
            put("sdkHome", sdkHome.toString())
            put("expectedContextHash", daemonContext.getValue("contextHash").jsonPrimitive.content)
        }
        val applied = daemon.dispatch("refactor.apply", buildJsonObject {
            put("planId", preview.getValue("planId").jsonPrimitive.content)
            put("javaContext", authority)
            put("expectedSnapshotHash", daemonSnapshotHash)
        }).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        val transactionId = applied.getValue("transactionId").jsonPrimitive.content
        val committedHash = applied.getValue("snapshotHash").jsonPrimitive.content
        val targetPath = root.resolve(productTargetPath())
        assertTrue(Files.isRegularFile(targetPath), "Apply must create the selected module/package destination")
        assertTrue(applied.getValue("diagnostics").jsonArray.none { diagnostic ->
            diagnostic.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
        })

        val rollback = daemon.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", transactionId)
            put("expectedSnapshotHash", committedHash)
            put("javaContext", authority)
        }).jsonObject
        assertEquals("rolledBack", rollback.getValue("status").jsonPrimitive.content)
        assertEquals(daemonSnapshotHash, rollback.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals(originalWorkspaceHashes, workspaceHashes(), "rollback must restore all original workspace images")
    }

    @Then("the preview moves Product into the target module source root with JDT binding evidence")
    fun assertTargetRootAndBinding() {
        val preview = (outcome as? JavaMoveClassOperationOutcome.Plan)?.preview
            ?: fail("cross-module move did not produce a managed plan: $outcome")
        assertEquals(PatchStatus.PREVIEW, preview.plan.status)
        assertEquals(RefactoringEvidence.JDT_BINDING, preview.plan.evidence)
        val move = preview.plan.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>().singleOrNull {
            it.path.toString().replace('\\', '/') == "client/src/main/java/com/acme/legacy/Product.java"
        }
        assertNotNull(move, "the declaration must be moved exactly once")
        assertEquals("api/src/main/java/com/acme/api/Product.java", move.newPath.toString().replace('\\', '/'))
        assertTrue(preview.plan.workspaceEdit.edits.any { edit ->
            edit is FileEdit.Modify && edit.path.toString().replace('\\', '/') ==
                "client/src/main/java/com/acme/client/ProductClient.java"
        }, "the binding-proven client reference must be updated")
    }

    @Then("the preview introduces no Java errors and leaves the workspace unchanged")
    fun assertDiagnosticsAndNoWrites() {
        val preview = (outcome as? JavaMoveClassOperationOutcome.Plan)?.preview
            ?: fail("cross-module move did not produce a plan: $outcome")
        val snapshot = JavaProjectScanner().scan(root)
        val staged = WorkspaceEditSimulator.apply(snapshot, preview.plan.workspaceEdit)
        assertNotNull(staged, "the preview edit must stage against the captured snapshot")
        assertTrue(adapter.diagnostics(snapshot).none { it.severity.name == "ERROR" }, "baseline Java diagnostics")
        assertTrue(adapter.diagnostics(staged).none { it.severity.name == "ERROR" }, "staged Java diagnostics")
        assertEquals(originalFiles, workspaceFiles(), "preview must not write to the workspace")
    }

    @After
    fun removeReactor() {
        if (::root.isInitialized && Files.exists(root)) {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun childPom(artifactId: String): String = """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent><groupId>com.acme</groupId><artifactId>reactor</artifactId>
            <version>1.0.0</version><relativePath>../pom.xml</relativePath></parent>
          <artifactId>$artifactId</artifactId>
        </project>
    """.trimIndent()

    private fun productModulePom(artifactId: String, body: String = ""): String = """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent><groupId>ide.reference</groupId><artifactId>java-effective-project</artifactId>
            <version>1.0</version><relativePath>../pom.xml</relativePath></parent>
          <artifactId>$artifactId</artifactId>$body
        </project>
    """.trimIndent()

    private fun productDependency(module: String, scope: String = "compile"): String = """
        <dependency><groupId>ide.reference</groupId><artifactId>$module</artifactId>
          <version>1.0</version><scope>$scope</scope></dependency>
    """.trimIndent()

    private fun compileProductFixtureOutputs() {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: fail("a JDK compiler is required for this build-output fixture")
        fun compile(module: String, sourcePaths: List<String>, classpath: List<String> = emptyList(), testOutput: Boolean = false) {
            val output = root.resolve("maven/$module/target/${if (testOutput) "test-classes" else "classes"}")
            output.createDirectories()
            val args = buildList {
                addAll(listOf("--release", "17", "-proc:none", "-d", output.toString()))
                if (classpath.isNotEmpty()) addAll(listOf("-classpath", classpath.joinToString(java.io.File.pathSeparator) { root.resolve(it).toString() }))
                addAll(sourcePaths.map { root.resolve("maven/$module/$it").toString() })
            }
            assertEquals(0, compiler.run(null, null, null, *args.toTypedArray()), "javac fixture compilation failed for $module")
        }
        compile("processor", listOf(
            "src/main/java/fixtures/processor/GenerateGreeting.java",
            "src/main/java/fixtures/processor/GreetingProcessor.java",
        ))
        compile("library", listOf("src/main/java/fixtures/library/BaseGreeting.java", "src/main/java/fixtures/library/GreetingService.java"))
        compile("runtime", listOf("src/main/java/fixtures/runtime/RuntimeToken.java"))
        compile("testsupport", listOf("src/main/java/fixtures/testsupport/TestToken.java"))
        compile("unrelated", listOf(
            "src/main/java/pkg/Peer.java",
            "src/main/java/fixtures/unrelated/Hidden.java",
            "src/main/java/fixtures/unrelated/UsesHidden.java",
        ))
        val dependencies = listOf("processor", "library", "runtime", "testsupport", "unrelated")
            .map { "maven/$it/target/classes" }
        compile("app", listOf(
            "src/main/java/pkg/Runner.java",
            "src/main/java/pkg/MigrateToLibrary.java",
            "src/main/java/pkg/MoveClient.java",
            "target/generated-sources/annotations/generated/GeneratedGreeting.java",
        ), dependencies)
        compile("app", listOf(
            "src/test/java/pkg/Runner.java",
            "src/test/java/pkg/RunnerTest.java",
            "src/test/java/pkg/MoveClientTest.java",
        ), dependencies + "maven/app/target/classes", testOutput = true)
    }

    private fun write(relative: String, content: String) {
        val path = root.resolve(relative)
        path.parent.createDirectories()
        path.writeText("$content\n")
    }

    private fun workspaceFiles(): Map<String, String> = Files.walk(root).use { paths ->
        paths.iterator().asSequence().filter(Files::isRegularFile).sortedBy { it.toString() }.associate { path ->
            root.relativize(path).toString().replace('\\', '/') to Files.readString(path)
        }
    }

    private fun workspaceHashes(): Map<String, String> = Files.walk(root).use { paths ->
        paths.iterator().asSequence()
            .filter(Files::isRegularFile)
            .filter { path -> !root.relativize(path).toString().replace('\\', '/').startsWith(".refactorkit/") }
            .sortedBy { it.toString() }.associate { path ->
            val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            root.relativize(path).toString().replace('\\', '/') to digest.joinToString("") { "%02x".format(it) }
        }
    }

    private fun productTargetPath(): String =
        "maven/library/src/main/java/${daemonExpectedTargetPackage.replace('.', '/')}/MigrateToLibrary.java"
}
