package org.refactorkit.jvm.safedelete

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import io.cucumber.java.After
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.FileEdit
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.TransactionLog
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaLexer
import org.refactorkit.java.JavaPackageUtil
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaSafeDeletePlanner
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import org.refactorkit.java.JdtJavaSemanticSymbolKind
import org.refactorkit.mcp.McpSession
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story BDD glue for features/java-safe-delete-characterization.feature
 * (REQ-JAVA-SAFE-DELETE-CHAR-001, row C-DELETE of the approved finite J1 Java catalogue).
 *
 * It drives the real toolchain: a Java workspace scanned by JavaProjectScanner into a real build
 * model, the real JavaLanguageAdapter, and the real JavaSafeDeletePlanner.preview. The feature
 * begins GREEN because production already implements the planner; the glue asserts the ACTUAL
 * behavior rather than inventing it.
 *
 * Fixtures:
 *  - The standard fixture uses a Maven jar module whose pom declares <sourceDirectory>src/main/java
 *    so real scanned SourceFile paths are the bare src/main/java/... names. Legacy is a recognized
 *    non-generated class with no references and a clean JDT binding.
 *  - The referenced fixture adds Client.java referencing Legacy, so JDT reports exact type-binding
 *    references and the planner refuses without force or force-deletes with a HIGH-risk warning.
 *  - The lexical-fallback fixture adds an unresolved MissingDependency field so JDT reports a
 *    parse/classpath warning, which makes preview fall back to lexical evidence (LEXICAL_FALLBACK).
 *  - The framework fixture carries @Component / @Entity / @JsonProperty on the delete declaration,
 *    elevating risk to HIGH with framework warnings.
 *  - The generated-source fixtures use a pom declaring <sourceDirectory>.</sourceDirectory> so a
 *    root-level Generated.java is scanned as exactly "Generated.java" or the build/generated path,
 *    matching the declared generated-source messages.
 *
 * Every refusal condition is a truthful fixture that makes the real planner return the DECLARED
 * MESSAGE — no message is invented, no typed safeDelete.xxx code is introduced, and no production
 * branch is weakened. Refusal Then steps assert REFUSED, the exact real message in summary and
 * warnings, an empty WorkspaceEdit, an empty affected-file set, no approval, no managed-write
 * eligibility, confidence 0.0, risk HIGH, and no typed refusal code.
 *
 * Unreachable refusal row (pre-approved RED-deferral in the J1 catalogue baseline section 2) is
 * NOT asserted: "Declaration file not found" (the planner builds its symbol index from the same
 * snapshot.files it searches, so a found symbol's location.path is always present).
 */
class KotlinJvmSafeDeleteCharacterizationSteps {
    private val temporaryDirectories = mutableListOf<Path>()
    private val observedRefusals = mutableListOf<ObservedRefusal>()

    private var fixtureRoot: Path? = null
    private var snapshot: ProjectSnapshot? = null
    private var plan: PatchPlan? = null
    private var condition: String? = null
    private var framework: String? = null

    private var symbolFqn: String? = null
    private var declarationPath: Path? = null
    private var daemonSession: DaemonSession? = null
    private var managedApplyFailure: Throwable? = null
    private var mcpApplyFailure: Throwable? = null
    private var managedApplyResult: JsonObject? = null
    private var managedApplyWorkspaceImage: Map<Path, String>? = null
    private var daemonCapabilities: JsonObject? = null
    private var daemonPreviewResponse: JsonObject? = null
    private var mcpPreviewStructuredContent: JsonObject? = null
    private var safeDeleteManagedContext: JsonObject? = null
    private var safeDeleteManagedPlanId: String? = null
    private var safeDeleteManagedSnapshotHash: String? = null
    private var safeDeleteManagedProjectImage: Map<Path, String>? = null
    private var safeDeleteManagedTransactionId: String? = null

    private val adapter = JavaLanguageAdapter()
    private val planner = JavaSafeDeletePlanner(adapter)

    private val STANDARD_DECLARATION = Path.of("src/main/java/com/example/Legacy.java")

    // ------------------------------------------------------------ Shared Given

    @Given("^a Java workspace snapshot that contains a recognized non-generated Java type declaration file$")
    fun workspaceWithRecognizedType() {
        fixtureRoot = buildStandardFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        symbolFqn = "com.example.Legacy"
        declarationPath = STANDARD_DECLARATION
    }

    @Given("^a clean Maven reactor with an unused Java type referenced only from its root aggregator POM$")
    fun cleanReactorWithAggregatorOnlyLiteralReference() {
        val root = temporaryDirectory("rk-jvm-safedelete-aggregator-pom")
        write(root, "pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixtures</groupId><artifactId>reactor</artifactId><version>1.0.0</version>
              <packaging>pom</packaging>
              <properties>
                <maven.compiler.release>17</maven.compiler.release>
                <fixture.safeDeleteTarget>fixtures.unrelated.Unused</fixture.safeDeleteTarget>
              </properties>
              <modules><module>unrelated</module></modules>
            </project>
        """.trimIndent())
        write(root, "unrelated/pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>fixtures</groupId><artifactId>reactor</artifactId><version>1.0.0</version>
                <relativePath>../pom.xml</relativePath>
              </parent>
              <artifactId>unrelated</artifactId>
            </project>
        """.trimIndent())
        write(root, "unrelated/src/main/java/fixtures/unrelated/Unused.java", """
            package fixtures.unrelated;

            public final class Unused {
            }
        """.trimIndent() + "\n")
        fixtureRoot = root
        snapshot = JavaProjectScanner().scan(root)
        val scanned = requireNotNull(snapshot)
        val module = scanned.modules.singleOrNull { it.languageSettings["java.maven.pomPath"] == "unrelated/pom.xml" }
        assertNotNull(module, "the child module must belong to the captured Maven reactor")
        assertEquals("available", module.languageSettings["java.buildModel.status"])
        assertTrue(
            scanned.auxiliaryFiles.any { it.path == Path.of("pom.xml") && it.languageId == "maven-pom" },
            "the root aggregator POM must be captured as a Maven text input",
        )
        symbolFqn = "fixtures.unrelated.Unused"
        declarationPath = Path.of("unrelated/src/main/java/fixtures/unrelated/Unused.java")
    }

    @Given("^an available two-module Maven reactor has a package-private Java type with no source or text consumers$")
    fun availableTwoModuleReactorWithUnusedPackagePrivateType() {
        val root = temporaryDirectory("rk-jvm-safedelete-managed-maven")
        val compilerPlugin = """
            <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.11.0</version>
              <configuration><release>17</release><proc>none</proc></configuration>
            </plugin>
        """.trimIndent()
        write(root, "pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixtures</groupId><artifactId>orbit-reactor</artifactId><version>1.0.0</version><packaging>pom</packaging>
              <modules><module>app</module><module>sibling</module></modules>
              <build><plugins>$compilerPlugin</plugins></build>
            </project>
        """.trimIndent() + "\n")
        for (module in listOf("app", "sibling")) {
            write(root, "$module/pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>fixtures</groupId><artifactId>orbit-reactor</artifactId><version>1.0.0</version><relativePath>../pom.xml</relativePath></parent>
                  <artifactId>$module</artifactId>
                </project>
            """.trimIndent() + "\n")
        }
        write(root, "app/src/main/java/fixtures/orbit/Orphan.java", """
            package fixtures.orbit;

            final class Orphan {
            }
        """.trimIndent() + "\n")
        write(root, "sibling/src/main/java/fixtures/other/Unrelated.java", """
            package fixtures.other;

            final class Unrelated {
            }
        """.trimIndent() + "\n")

        fixtureRoot = root
        snapshot = JavaProjectScanner().scan(root)
        symbolFqn = "fixtures.orbit.Orphan"
        declarationPath = Path.of("app/src/main/java/fixtures/orbit/Orphan.java")
        val scanned = requireNotNull(snapshot)
        assertEquals(listOf("available"), scanned.buildModels.map { it.status.name.lowercase() })
        assertEquals(2, scanned.modules.size, "both Java-bearing Maven modules must be in the snapshot")
        assertEquals("0", scanned.buildModels.single().attributes["java.safeDelete.textReferenceScan.binaryInputs"])
    }

    @Given("^a two-module Maven reactor contains a package-private Java type and an opaque binary resource$")
    fun twoModuleReactorWithOpaqueBinaryResource() {
        availableTwoModuleReactorWithUnusedPackagePrivateType()
        val root = requireNotNull(fixtureRoot)
        val binary = root.resolve("app/src/main/resources/META-INF/opaque-index.bin")
        binary.parent.createDirectories()
        Files.write(binary, byteArrayOf(0x52, 0x4b, 0x00, 0x01, 0x7f))
        snapshot = JavaProjectScanner().scan(root)
        val scanned = requireNotNull(snapshot)
        assertEquals("complete", scanned.buildModels.single().attributes["java.safeDelete.textReferenceScan.status"])
        assertEquals("1", scanned.buildModels.single().attributes["java.safeDelete.textReferenceScan.binaryInputs"])
    }

    @Given("^a two-module Maven reactor contains a package-private Java type and an unmodeled input$")
    fun twoModuleReactorWithUnmodeledInput() {
        availableTwoModuleReactorWithUnusedPackagePrivateType()
        val root = requireNotNull(fixtureRoot)
        write(root, "external/Orphan.mapping", "type=fixtures.orbit.Orphan\n")
        snapshot = JavaProjectScanner().scan(root)
        val scanned = requireNotNull(snapshot)
        val modelAttributes = scanned.buildModels.single().attributes
        assertEquals("complete", modelAttributes["java.safeDelete.textReferenceScan.status"])
        assertEquals("0", modelAttributes["java.safeDelete.textReferenceScan.binaryInputs"])
        assertEquals("1", modelAttributes["java.safeDelete.textReferenceScan.unmodeledInputs"])
    }

    @When("^the IDE requests safeDelete for fixtures\\.orbit\\.Orphan with an SDK-bound daemon context$")
    fun ideRequestsManagedSafeDeleteWithSdkContext() {
        val root = requireNotNull(fixtureRoot)
        val daemon = DaemonSession()
        daemonSession = daemon
        val opened = daemon.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
        val initialHash = opened.getValue("snapshotHash").jsonPrimitive.content
        safeDeleteManagedSnapshotHash = initialHash
        safeDeleteManagedProjectImage = projectImage(root)
        val context = daemon.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", initialHash)
            put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        val authority = buildJsonObject {
            put("sdkHome", System.getProperty("java.home"))
            put("expectedContextHash", context.getValue("contextHash").jsonPrimitive.content)
            put("contextSchemaVersion", context.getValue("schemaVersion").jsonPrimitive.content)
        }
        safeDeleteManagedContext = authority

        val unbound = daemon.dispatch("refactor.preview", buildJsonObject {
            put("operation", "safeDelete")
            put("languageId", "java")
            put("symbol", requireNotNull(symbolFqn))
            put("expectedSnapshotHash", initialHash)
        }).jsonObject
        assertEquals("PREVIEW", unbound.getValue("status").jsonPrimitive.content)
        val unboundApply = runCatching {
            daemon.dispatch("refactor.apply", buildJsonObject {
                put("planId", unbound.getValue("planId").jsonPrimitive.content)
                put("expectedSnapshotHash", initialHash)
            })
        }.exceptionOrNull()
        assertManagedApplyRefusal("unbound daemon", unboundApply, "java.safeDelete.referenceClosureIncomplete")

        val preview = daemon.dispatch("refactor.preview", buildJsonObject {
            put("operation", "safeDelete")
            put("languageId", "java")
            put("symbol", requireNotNull(symbolFqn))
            put("expectedSnapshotHash", initialHash)
            put("javaContext", authority)
        }).jsonObject
        daemonPreviewResponse = preview
        safeDeleteManagedPlanId = preview.getValue("planId").jsonPrimitive.content
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
    }

    @When("^the IDE previews safeDelete for fixtures\\.orbit\\.Orphan with an SDK-bound daemon context$")
    fun idePreviewsSafeDeleteWithOpaqueBinaryResource() {
        val root = requireNotNull(fixtureRoot)
        val daemon = DaemonSession()
        daemonSession = daemon
        val opened = daemon.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
        val hash = opened.getValue("snapshotHash").jsonPrimitive.content
        safeDeleteManagedSnapshotHash = hash
        safeDeleteManagedProjectImage = projectImage(root)
        val context = daemon.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", hash)
            put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        val authority = buildJsonObject {
            put("sdkHome", System.getProperty("java.home"))
            put("expectedContextHash", context.getValue("contextHash").jsonPrimitive.content)
            put("contextSchemaVersion", context.getValue("schemaVersion").jsonPrimitive.content)
        }
        safeDeleteManagedContext = authority
        daemonPreviewResponse = daemon.dispatch("refactor.preview", buildJsonObject {
            put("operation", "safeDelete")
            put("languageId", "java")
            put("symbol", requireNotNull(symbolFqn))
            put("expectedSnapshotHash", hash)
            put("javaContext", authority)
        }).jsonObject
        safeDeleteManagedPlanId = requireNotNull(daemonPreviewResponse)
            .getValue("planId").jsonPrimitive.content
    }

    @Then("^RefactorKit reports the binary resource as outside semantic closure and grants no Apply authority$")
    fun binaryResourceKeepsClosureIncomplete() {
        val preview = requireNotNull(daemonPreviewResponse)
        val analysis = preview.getValue("referenceAnalysis").jsonObject
        assertEquals("complete", analysis.getValue("status").jsonPrimitive.content)
        assertEquals("incomplete", analysis.getValue("referenceClosure").jsonPrimitive.content)
        assertEquals("none", analysis.getValue("managedApplyAuthority").jsonPrimitive.content)
        assertEquals(false, analysis.getValue("completeProject").jsonPrimitive.content.toBoolean())
        assertEquals(requireNotNull(safeDeleteManagedSnapshotHash), analysis.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
        assertEquals(requireNotNull(safeDeleteManagedProjectImage), projectImage(requireNotNull(fixtureRoot)))
    }

    @Then("^RefactorKit reports an unmodeled workspace input and grants no Apply authority$")
    fun unmodeledWorkspaceInputKeepsClosureIncomplete() {
        val preview = requireNotNull(daemonPreviewResponse)
        val analysis = preview.getValue("referenceAnalysis").jsonObject
        assertEquals("complete", analysis.getValue("status").jsonPrimitive.content)
        assertEquals("incomplete", analysis.getValue("referenceClosure").jsonPrimitive.content)
        assertEquals("none", analysis.getValue("managedApplyAuthority").jsonPrimitive.content)
        assertEquals(false, analysis.getValue("completeProject").jsonPrimitive.content.toBoolean())
        assertEquals(requireNotNull(safeDeleteManagedSnapshotHash), analysis.getValue("snapshotHash").jsonPrimitive.content)
        val modelAttributes = requireNotNull(snapshot).buildModels.single().attributes
        assertEquals("1", modelAttributes["java.safeDelete.textReferenceScan.unmodeledInputs"])
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
        assertEquals(requireNotNull(safeDeleteManagedProjectImage), projectImage(requireNotNull(fixtureRoot)))
    }

    @io.cucumber.java.en.And("^managed Apply is refused without changing the reactor or transaction log$")
    fun managedApplyIsRefusedForOpaqueBinaryResource() {
        val root = requireNotNull(fixtureRoot)
        val failure = runCatching {
            requireNotNull(daemonSession).dispatch("refactor.apply", buildJsonObject {
                put("planId", requireNotNull(safeDeleteManagedPlanId))
                put("expectedSnapshotHash", requireNotNull(safeDeleteManagedSnapshotHash))
                put("javaContext", requireNotNull(safeDeleteManagedContext))
            })
        }.exceptionOrNull()
        assertManagedApplyRefusal("binary-resource daemon", failure, "java.safeDelete.referenceClosureIncomplete")
        assertEquals(requireNotNull(safeDeleteManagedProjectImage), projectImage(root))
        assertEquals(0, TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size)
        assertTrue(!Files.exists(root.resolve(".refactorkit/workspace.lock")))
        assertTrue(!Files.exists(root.resolve(".refactorkit/transactions")))
    }

    @Then("^RefactorKit reports complete snapshot-bound Maven closure and managed Apply authority only for this preview$")
    fun refactorkitReportsBoundMavenClosureForPreview() {
        val preview = requireNotNull(daemonPreviewResponse)
        val analysis = preview.getValue("referenceAnalysis").jsonObject
        assertEquals("complete", analysis.getValue("status").jsonPrimitive.content)
        assertEquals("maven-reactor-complete", analysis.getValue("referenceClosure").jsonPrimitive.content)
        assertEquals("snapshot-bound", analysis.getValue("managedApplyAuthority").jsonPrimitive.content)
        assertEquals(true, analysis.getValue("completeProject").jsonPrimitive.content.toBoolean())
        assertEquals(requireNotNull(safeDeleteManagedSnapshotHash), analysis.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals("HIGH", preview.getValue("riskLevel").jsonPrimitive.content)
        assertTrue(preview.getValue("confidence").jsonPrimitive.content.toDouble() > 0.0)

        val capabilities = requireNotNull(daemonSession).dispatch("server.capabilities", buildJsonObject { }).jsonObject
        val safeDeleteCapability = capabilities.getValue("javaContextRefactoring").jsonObject
            .getValue("operationCapabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "safeDelete" }
            .getValue("referenceAnalysis").jsonObject
        assertEquals("conditional-snapshot-bound", safeDeleteCapability.getValue("managedApplyAuthority").jsonPrimitive.content)
        assertEquals(false, safeDeleteCapability.getValue("completeProject").jsonPrimitive.content.toBoolean())
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
        assertEquals(requireNotNull(safeDeleteManagedProjectImage), projectImage(requireNotNull(fixtureRoot)))
    }

    @io.cucumber.java.en.And("^a stale Apply is refused, then exact Apply and rollback restore the full reactor image$")
    fun staleApplyIsRefusedThenExactApplyRollsBack() {
        val root = requireNotNull(fixtureRoot)
        val daemon = requireNotNull(daemonSession)
        val originalPom = Files.readString(root.resolve("app/pom.xml"))
        write(root, "app/pom.xml", originalPom + "<!-- stale preview input -->\n")
        val staleFailure = runCatching {
            daemon.dispatch("refactor.apply", buildJsonObject {
                put("planId", requireNotNull(safeDeleteManagedPlanId))
                put("expectedSnapshotHash", requireNotNull(safeDeleteManagedSnapshotHash))
                put("javaContext", requireNotNull(safeDeleteManagedContext))
            })
        }.exceptionOrNull()
        val stale = staleFailure as? JsonRpcException
            ?: error("stale Apply did not return a structured refusal: $staleFailure")
        assertEquals("java.context.snapshotStale", stale.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
        assertTrue(Files.exists(root.resolve(requireNotNull(declarationPath))))
        write(root, "app/pom.xml", originalPom)

        val opened = daemon.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
        val restoredHash = opened.getValue("snapshotHash").jsonPrimitive.content
        assertEquals(requireNotNull(safeDeleteManagedSnapshotHash), restoredHash)
        val contextResponse = daemon.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", restoredHash)
            put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
        assertEquals("available", contextResponse.getValue("status").jsonPrimitive.content, contextResponse.toString())
        val authority = buildJsonObject {
            put("sdkHome", System.getProperty("java.home"))
            put("expectedContextHash", contextResponse.getValue("contextHash").jsonPrimitive.content)
            put("contextSchemaVersion", contextResponse.getValue("schemaVersion").jsonPrimitive.content)
        }
        val preview = daemon.dispatch("refactor.preview", buildJsonObject {
            put("operation", "safeDelete")
            put("languageId", "java")
            put("symbol", requireNotNull(symbolFqn))
            put("expectedSnapshotHash", restoredHash)
            put("javaContext", authority)
        }).jsonObject
        assertEquals("snapshot-bound", preview.getValue("referenceAnalysis").jsonObject
            .getValue("managedApplyAuthority").jsonPrimitive.content)
        val applied = daemon.dispatch("refactor.apply", buildJsonObject {
            put("planId", preview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", restoredHash)
            put("javaContext", authority)
        }).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content.lowercase())
        assertTrue(!Files.exists(root.resolve(requireNotNull(declarationPath))))
        val transactionId = applied.getValue("transactionId").jsonPrimitive.content
        safeDeleteManagedTransactionId = transactionId
        val postHash = applied.getValue("snapshotHash").jsonPrimitive.content
        val rolledBack = daemon.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", transactionId)
            put("expectedSnapshotHash", postHash)
            put("javaContext", authority)
        }).jsonObject
        assertEquals("rolledback", rolledBack.getValue("status").jsonPrimitive.content
            .lowercase().replace("_", "").replace("-", ""), rolledBack.toString())
        assertEquals(requireNotNull(safeDeleteManagedProjectImage), projectImage(root))
        assertTrue(Files.exists(root.resolve(requireNotNull(declarationPath))))
    }

    @Given("^the project contains an unresolved dynamic class-loading consumer with no literal target name$")
    fun projectContainsUnresolvedDynamicClassLoadingConsumer() {
        val root = requireNotNull(fixtureRoot)
        write(
            root,
            "src/main/java/com/example/DynamicTypeLoader.java",
            """
            package com.example;

            public final class DynamicTypeLoader {
                public Class<?> load(String configuredTypeName) throws ClassNotFoundException {
                    return Class.forName(configuredTypeName);
                }
            }
            """.trimIndent() + "\n",
        )
        snapshot = JavaProjectScanner().scan(root)
        assertEquals(0, jdtReferenceCount(requireNotNull(snapshot), requireNotNull(symbolFqn), requireNotNull(declarationPath)))
        val plan = planner.preview(requireNotNull(snapshot), requireNotNull(symbolFqn))
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertTrue(plan.warnings.any { it.contains("dynamic references are not resolved") }, plan.warnings.toString())
    }

    @Given("^a captured workspace text input of type \"([^\"]+)\" references the selected type at \"([^\"]+)\"$")
    fun workspaceTextInputReferencesType(surface: String, relativePath: String) {
        val root = requireNotNull(fixtureRoot)
        val content = when (surface) {
            "maven" -> {
                val pomPath = root.resolve(relativePath)
                val pom = String(Files.readAllBytes(pomPath), Charsets.UTF_8)
                pom.replace(
                    "</project>",
                    "  <properties><safeDelete.target>com.example.Legacy</safeDelete.target></properties>\n</project>",
                )
            }
            "gradle" -> "ext.safeDeleteTarget = \"com.example.Legacy\"\n"
            "xml" -> "<hibernate-mapping><class name=\"com.example.Legacy\"/></hibernate-mapping>\n"
            "yaml" -> "entity: com.example.Legacy\n"
            "sql" -> "-- query for com.example.Legacy\nselect * from Legacy;\n"
            "service" -> "com.example.LegacyAdapter\n"
            else -> error("Unknown project text surface: '$surface'")
        }
        write(root, relativePath, content)
        snapshot = JavaProjectScanner().scan(root)
        val targetPath = Path.of(relativePath)
        val captured = requireNotNull(snapshot).auxiliaryFiles.singleOrNull { it.path == targetPath }
        assertNotNull(captured, "$relativePath must be retained as a captured workspace text input")
        assertTrue(captured.content.contains("Legacy"), "$relativePath must contain the selected type name")
    }

    @Given("^workspace text-reference scan evidence is (.+)$")
    fun workspaceTextReferenceScanEvidence(coverage: String) {
        val root = requireNotNull(fixtureRoot)
        val scanned = requireNotNull(snapshot)
        when (coverage) {
            "over-budget" -> {
                write(root, "src/main/resources/oversized.yml", "x".repeat(1_048_577))
                snapshot = JavaProjectScanner().scan(root)
                val states = requireNotNull(snapshot).buildModels.mapNotNull {
                    it.attributes["java.safeDelete.textReferenceScan.status"]
                }.distinct()
                assertEquals(listOf("incomplete"), states, "the oversized text input must make scan coverage incomplete")
            }
            "missing" -> {
                snapshot = scanned.copy(buildModels = scanned.buildModels.map { model ->
                    model.copy(attributes = model.attributes - setOf(
                        "java.safeDelete.textReferenceScan.status",
                        "java.safeDelete.textReferenceScan.version",
                    ))
                })
                assertTrue(
                    requireNotNull(snapshot).buildModels.none {
                        "java.safeDelete.textReferenceScan.status" in it.attributes
                    },
                    "the fixture must remove the scanner completeness proof",
                )
            }
            else -> error("Unknown text-reference scan coverage: '$coverage'")
        }
    }

    @Given("^workspace text-reference coverage is hidden by a symbolic link$")
    fun workspaceTextReferenceCoverageHiddenBySymbolicLink() {
        val root = requireNotNull(fixtureRoot)
        val externalRoot = temporaryDirectory("rk-jvm-safedelete-external-text")
        val externalInput = externalRoot.resolve("legacy-reference.yml")
        write(externalRoot, "legacy-reference.yml", "entity: com.example.Legacy\n")
        val link = root.resolve("src/main/resources/external-reference.yml")
        link.parent.createDirectories()
        Files.createSymbolicLink(link, externalInput)

        snapshot = JavaProjectScanner().scan(root)
        val states = requireNotNull(snapshot).buildModels.mapNotNull {
            it.attributes["java.safeDelete.textReferenceScan.status"]
        }.distinct()
        assertEquals(listOf("incomplete"), states, "a workspace symlink must invalidate text-reference coverage")
    }

    // ------------------------------------------------------------ Scenario 1 Given

    @Given("^the JDT binding analysis is clean with no parse or classpath warnings$")
    fun jdtBindingCleanNoWarnings() {
        val analysis = JdtJavaSemanticAnalyzer().analyze(requireNotNull(snapshot))
        assertTrue(
            analysis.warnings.isEmpty(),
            "expected clean JDT binding, got warnings: ${analysis.warnings.map { it.message }}",
        )
    }

    @Given("^the type binding resolves to exactly one JDT deleteable type candidate with a binding key$")
    fun typeBindingResolvesToOneCandidate() {
        val analysis = JdtJavaSemanticAnalyzer().analyze(requireNotNull(snapshot))
        val candidates = analysis.symbols.filter { symbol ->
            symbol.qualifiedName == requireNotNull(symbolFqn) && symbol.kind in JDT_DELETEABLE_KINDS
        }
        assertEquals(
            1, candidates.size,
            "expected exactly one JDT deleteable candidate: ${analysis.symbols.map { it.qualifiedName }}",
        )
        assertNotNull(candidates[0].bindingKey, "expected a JDT binding key on the candidate")
    }

    @Given("^the type has no references anywhere in the project$")
    fun typeHasNoReferences() {
        val snap = requireNotNull(snapshot)
        val fqn = requireNotNull(symbolFqn)
        val decl = requireNotNull(declarationPath)
        val simpleName = JavaPackageUtil.simpleName(fqn)
        val referencing = snap.files.filter { it.path != decl && it.languageId == "java" }.filter { file ->
            file.content.contains(fqn) || JavaLexer.findOccurrences(file.content, simpleName).isNotEmpty()
        }
        assertTrue(
            referencing.isEmpty(),
            "expected no references to $fqn, found files: ${referencing.map { it.path }}",
        )
    }

    // ------------------------------------------------------------ Scenario 2 Given

    @Given("^the JDT binding analysis is clean and resolves the type binding$")
    fun jdtBindingCleanAndResolves() {
        val analysis = JdtJavaSemanticAnalyzer().analyze(requireNotNull(snapshot))
        assertTrue(
            analysis.warnings.isEmpty(),
            "expected clean JDT binding, got warnings: ${analysis.warnings.map { it.message }}",
        )
        val candidates = analysis.symbols.filter { symbol ->
            symbol.qualifiedName == requireNotNull(symbolFqn) && symbol.kind in JDT_DELETEABLE_KINDS
        }
        assertEquals(1, candidates.size, "expected exactly one deleteable type binding")
        assertNotNull(candidates[0].bindingKey, "expected a JDT binding key")
    }

    @Given("^the type has at least one reference in the project$")
    fun typeHasAtLeastOneReference() {
        fixtureRoot = buildReferencedFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        assertTrue(
            jdtReferenceCount(requireNotNull(snapshot), requireNotNull(symbolFqn), requireNotNull(declarationPath)) >= 1,
            "expected at least one JDT reference to ${requireNotNull(symbolFqn)}",
        )
    }

    // ------------------------------------------------------------ Scenario 3 Given

    @Given("^the JDT binding analysis reports an error warning or no usable binding for the type$")
    fun jdtBindingReportsErrorWarning() {
        fixtureRoot = buildLexicalFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        val analysis = JdtJavaSemanticAnalyzer().analyze(requireNotNull(snapshot))
        assertTrue(
            analysis.warnings.isNotEmpty(),
            "expected not-clean JDT binding for the lexical fallback fixture",
        )
    }

    // ------------------------------------------------------------ Scenario 4 Given

    @Given("^a Java workspace snapshot whose delete declaration file carries a (.+) annotation$")
    fun workspaceWithFrameworkAnnotation(framework: String) {
        this.framework = framework.trim()
        fixtureRoot = buildFrameworkFixture(requireNotNull(this.framework))
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        symbolFqn = "com.example.Legacy"
        declarationPath = STANDARD_DECLARATION
    }

    // ------------------------------------------------------------ Scenario 5 Given

    @Given("^the Java adapter returns a REFUSED patch plan for a safeDelete preview$")
    fun adapterReturnsRefusedSafeDeletePlan() {
        fixtureRoot = buildStandardFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        // A real refusal: a symbol absent from the symbol index. The plan is REFUSED and carries the real message.
        plan = planner.preview(requireNotNull(snapshot), "com.example.Missing")
        assertEquals(PatchStatus.REFUSED, requireNotNull(plan).status, requireNotNull(plan).summary)
    }

    // ------------------------------------------------------------ Scenario 6/7 Given

    @Given("^the type has one or more references in the project$")
    fun typeHasOneOrMoreReferences() {
        fixtureRoot = buildReferencedFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        assertTrue(
            jdtReferenceCount(requireNotNull(snapshot), requireNotNull(symbolFqn), requireNotNull(declarationPath)) >= 1,
            "expected at least one JDT reference to ${requireNotNull(symbolFqn)}",
        )
    }

    @Given("^the type has more than twenty references in the project$")
    fun typeHasMoreThanTwentyReferences() {
        fixtureRoot = buildManyReferencesFixture()
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must yield a real build model")
        assertTrue(
            jdtReferenceCount(requireNotNull(snapshot), requireNotNull(symbolFqn), requireNotNull(declarationPath)) > 20,
            "expected more than twenty JDT references to ${requireNotNull(symbolFqn)}",
        )
    }

    // ------------------------------------------------------------ Scenario 8 Given

    @Given("^a Java workspace snapshot for a safeDelete preview under the condition (.+)$")
    fun workspaceUnderCondition(condition: String) {
        this.condition = condition.trim()
        fixtureRoot = buildFixture(requireNotNull(this.condition))
        snapshot = JavaProjectScanner().scan(requireNotNull(fixtureRoot))
    }

    // ------------------------------------------------------------ WHEN steps

    @When("^the caller requests a safeDelete preview for that fully qualified type name without force$")
    fun previewWithoutForce() {
        plan = planner.preview(requireNotNull(snapshot), requireNotNull(symbolFqn))
    }

    @When("^the caller requests a safeDelete preview for that fully qualified type name with force allowed$")
    fun previewWithForce() {
        plan = planner.preview(requireNotNull(snapshot), requireNotNull(symbolFqn), force = true)
    }

    @When("^the caller inspects the refusal plan$")
    fun inspectRefusalPlan() {
        requireNotNull(plan)
    }

    @When("^the caller requests a safeDelete preview$")
    fun previewUnderCondition() {
        previewUnderCondition(requireNotNull(condition))
    }

    @When("^the caller previews and applies safeDelete through the daemon and MCP$")
    fun previewAndApplySafeDeleteThroughManagedSurfaces() {
        val root = requireNotNull(fixtureRoot)
        managedApplyWorkspaceImage = workspaceImage(root)
        val daemon = DaemonSession()
        daemonSession = daemon
        val opened = daemon.dispatch("project.open", buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        daemonCapabilities = daemon.dispatch("server.capabilities", buildJsonObject { }).jsonObject
        val preview = daemon.dispatch("refactor.preview", buildJsonObject {
            put("operation", "safeDelete")
            put("languageId", "java")
            put("symbol", requireNotNull(symbolFqn))
            put("expectedSnapshotHash", opened.getValue("snapshotHash").jsonPrimitive.content)
        }).jsonObject
        daemonPreviewResponse = preview
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
        val applyResult = runCatching {
            daemon.dispatch("refactor.apply", buildJsonObject {
                put("planId", preview.getValue("planId").jsonPrimitive.content)
                put("expectedSnapshotHash", opened.getValue("snapshotHash").jsonPrimitive.content)
            }).jsonObject
        }
        managedApplyResult = applyResult.getOrNull()
        managedApplyFailure = applyResult.exceptionOrNull()

        McpSession().use { mcp ->
            mcpToolCall(mcp, "project_scan", buildJsonObject { put("root", root.toString()) })
            val mcpPreview = mcpToolCall(mcp, "preview_refactoring", buildJsonObject {
                put("operation", "safeDelete")
                put("languageId", "java")
                put("symbol", requireNotNull(symbolFqn))
            })
            mcpPreviewStructuredContent = mcpPreview["structuredContent"]?.jsonObject
            val previewText = mcpToolText(mcpPreview)
            assertTrue(previewText.contains("Status   : PREVIEW"), previewText)
            val mcpPlanId = Regex("Plan ID\\s*:\\s*(\\S+)").find(previewText)?.groupValues?.get(1)
                ?: error("MCP safeDelete preview omitted plan ID: $previewText")
            val mcpApply = runCatching {
                mcpToolCall(mcp, "apply_refactoring", buildJsonObject { put("planId", mcpPlanId) })
            }
            mcpApplyFailure = mcpApply.exceptionOrNull()
        }
    }

    @Then("^managed Apply is refused with code \"([^\"]+)\"$")
    fun managedApplyIsRefusedWithCode(expectedCode: String) {
        assertTrue(managedApplyResult == null, "managed Apply unexpectedly succeeded: $managedApplyResult")
        assertManagedApplyRefusal("daemon", managedApplyFailure, expectedCode)
        assertManagedApplyRefusal("MCP", mcpApplyFailure, expectedCode)
    }

    private fun assertManagedApplyRefusal(surface: String, failure: Throwable?, expectedCode: String) {
        val refusal = failure as? JsonRpcException
            ?: error("$surface managed Apply did not return a structured JSON-RPC refusal: $failure")
        assertEquals(JsonRpcErrorCodes.PLAN_VALIDATION_FAILED, refusal.code, "$surface: ${refusal.message}")
        val data = requireNotNull(refusal.data).jsonObject
        assertEquals(expectedCode, data.getValue("refusalCode").jsonPrimitive.content, surface)
    }

    private fun mcpToolCall(session: McpSession, name: String, arguments: JsonObject): JsonObject =
        session.dispatch("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }).jsonObject

    private fun mcpToolText(result: JsonObject): String = result.getValue("content").jsonArray.single()
        .jsonObject.getValue("text").jsonPrimitive.content

    @io.cucumber.java.en.And("^workspace files and transaction metadata remain unchanged$")
    fun workspaceAndTransactionMetadataRemainUnchanged() {
        val root = requireNotNull(fixtureRoot)
        assertEquals(requireNotNull(managedApplyWorkspaceImage), workspaceImage(root))
        assertEquals(0, TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size)
        assertTrue(!Files.exists(root.resolve(".refactorkit/workspace.lock")))
        assertTrue(!Files.exists(root.resolve(".refactorkit/transactions")))
    }

    @io.cucumber.java.en.And("^daemon and MCP expose only snapshot-bound workspace-text analysis without Apply authority$")
    fun daemonAndMcpExposeReadOnlyTextAnalysis() {
        val capabilities = requireNotNull(daemonCapabilities).getValue("javaContextRefactoring").jsonObject
        val safeDeleteCapability = capabilities.getValue("operationCapabilities").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "safeDelete" }
            .getValue("referenceAnalysis").jsonObject
        assertEquals("java-safe-delete-reference-analysis-v1", safeDeleteCapability.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("workspace-text-v1", safeDeleteCapability.getValue("scope").jsonPrimitive.content)
        assertEquals("referenceAnalysis", safeDeleteCapability.getValue("resultField").jsonPrimitive.content)
        assertEquals("conditional-snapshot-bound", safeDeleteCapability.getValue("managedApplyAuthority").jsonPrimitive.content)
        assertEquals(false, safeDeleteCapability.getValue("completeProject").jsonPrimitive.content.toBoolean())

        val daemon = requireNotNull(daemonPreviewResponse)
        val daemonAnalysis = daemon.getValue("referenceAnalysis").jsonObject
        assertReferenceAnalysisIsReadOnly(daemonAnalysis, requireNotNull(daemon.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content))

        val mcpAnalysis = requireNotNull(mcpPreviewStructuredContent).getValue("referenceAnalysis").jsonObject
        assertReferenceAnalysisIsReadOnly(mcpAnalysis, daemonAnalysis.getValue("snapshotHash").jsonPrimitive.content)

        val incompleteSnapshot = requireNotNull(snapshot).copy(buildModels = emptyList())
        val incompleteAnalysis = org.refactorkit.java.JavaSafeDeleteReferenceAnalysis.from(incompleteSnapshot)
        assertEquals(org.refactorkit.java.JavaSafeDeleteReferenceAnalysis.Status.INCOMPLETE, incompleteAnalysis.status)
        assertEquals(null, incompleteAnalysis.inputCount)
        assertEquals(null, incompleteAnalysis.verifiedBytes)
        assertEquals(incompleteSnapshot.hash, incompleteAnalysis.snapshotHash)
        assertEquals("none", incompleteAnalysis.managedApplyAuthority)
        assertEquals(false, incompleteAnalysis.completeProject)

        val completeSnapshot = requireNotNull(snapshot)
        val sourceModel = completeSnapshot.buildModels.first()
        val missingScanCountModel = sourceModel.copy(
            attributes = sourceModel.attributes - setOf(
                "java.safeDelete.textReferenceScan.inputs",
                "java.safeDelete.textReferenceScan.bytes",
            ),
        )
        val inconsistentSnapshot = completeSnapshot.copy(buildModels = listOf(missingScanCountModel))
        val inconsistentAnalysis = org.refactorkit.java.JavaSafeDeleteReferenceAnalysis.from(inconsistentSnapshot)
        assertEquals(org.refactorkit.java.JavaSafeDeleteReferenceAnalysis.Status.INCOMPLETE, inconsistentAnalysis.status)
        assertEquals(null, inconsistentAnalysis.inputCount)
        assertEquals(null, inconsistentAnalysis.verifiedBytes)
        assertEquals(inconsistentSnapshot.hash, inconsistentAnalysis.snapshotHash)
        assertEquals("none", inconsistentAnalysis.managedApplyAuthority)
        assertEquals(false, inconsistentAnalysis.completeProject)
    }

    private fun assertReferenceAnalysisIsReadOnly(analysis: JsonObject, expectedSnapshotHash: String) {
        assertEquals("java-safe-delete-reference-analysis-v1", analysis.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("safeDelete", analysis.getValue("operation").jsonPrimitive.content)
        assertEquals("workspace-text-v1", analysis.getValue("scope").jsonPrimitive.content)
        assertEquals("complete", analysis.getValue("status").jsonPrimitive.content)
        assertTrue(analysis.getValue("inputCount").jsonPrimitive.content.toInt() > 0, analysis.toString())
        assertTrue(analysis.getValue("verifiedBytes").jsonPrimitive.content.toLong() > 0L, analysis.toString())
        assertEquals(expectedSnapshotHash, analysis.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals("none", analysis.getValue("managedApplyAuthority").jsonPrimitive.content)
        assertEquals(false, analysis.getValue("completeProject").jsonPrimitive.content.toBoolean())
        assertEquals("incomplete", analysis.getValue("referenceClosure").jsonPrimitive.content)
    }

    // ------------------------------------------------------------ PREVIEW Thens

    @Then("^the adapter returns a PREVIEW patch plan for the operation safeDelete$")
    fun adapterReturnsPreviewPlan() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.PREVIEW, p.status, "${p.summary}")
        assertEquals("safeDelete", p.operation, p.toString())
    }

    @Then("^the plan carries confidence 1.0$")
    fun planCarriesConfidence10() {
        assertEquals(1.0, requireNotNull(plan).confidence, requireNotNull(plan).toString())
    }

    @Then("^the plan carries confidence 0.3$")
    fun planCarriesConfidence03() {
        assertEquals(0.3, requireNotNull(plan).confidence, requireNotNull(plan).toString())
    }

    @Then("^the plan requires user approval$")
    fun planRequiresUserApproval() {
        assertTrue(requireNotNull(plan).requiresUserApproval, "expected requiresUserApproval=true: ${requireNotNull(plan)}")
    }

    @Then("^the plan evidence is JDT_BINDING$")
    fun planEvidenceJdtBinding() {
        assertEquals(RefactoringEvidence.JDT_BINDING, requireNotNull(plan).evidence, requireNotNull(plan).toString())
    }

    @Then("^the plan evidence is LEXICAL_FALLBACK$")
    fun planEvidenceLexicalFallback() {
        assertEquals(RefactoringEvidence.LEXICAL_FALLBACK, requireNotNull(plan).evidence, requireNotNull(plan).toString())
    }

    @Then("^the plan evidence is JDT_BINDING when the JDT binding is clean, or LEXICAL_FALLBACK otherwise$")
    fun planEvidenceJdtOrLexical() {
        val p = requireNotNull(plan)
        assertTrue(
            p.evidence == RefactoringEvidence.JDT_BINDING || p.evidence == RefactoringEvidence.LEXICAL_FALLBACK,
            "expected JDT_BINDING or LEXICAL_FALLBACK evidence: ${p.evidence}",
        )
    }

    @Then("^the plan risk is LOW$")
    fun planRiskLow() {
        assertEquals(RiskLevel.LOW, requireNotNull(plan).riskLevel, requireNotNull(plan).toString())
    }

    @Then("^the plan risk is HIGH$")
    fun planRiskHigh() {
        assertEquals(RiskLevel.HIGH, requireNotNull(plan).riskLevel, requireNotNull(plan).toString())
    }

    @Then("^the plan affected-file set lists only the declaration file$")
    fun planAffectedSetOnlyDeclaration() {
        val p = requireNotNull(plan)
        assertEquals(setOf(requireNotNull(declarationPath)), p.affectedFiles, "expected only the declaration file: ${p.affectedFiles}")
    }

    @Then("^the plan carries a WorkspaceEdit with exactly one FileEdit.Delete for the declaration file$")
    fun planWorkspaceEditSingleDelete() {
        val p = requireNotNull(plan)
        val decl = requireNotNull(declarationPath)
        assertEquals(1, p.workspaceEdit.edits.size, "expected exactly one FileEdit: ${p.workspaceEdit.edits}")
        val edit = p.workspaceEdit.edits[0]
        assertTrue(edit is FileEdit.Delete, "expected a FileEdit.Delete: ${edit}")
        assertEquals(decl, edit.path, "expected the Delete on the declaration file")
    }

    @io.cucumber.java.en.And("^the review-only proposal identifies \"([^\"]+)\" and PatchEngine refuses Apply with \"([^\"]+)\" before mutation$")
    fun reviewOnlyProposalCannotBeApplied(pomPath: String, refusalCode: String) {
        val root = requireNotNull(fixtureRoot)
        val snap = requireNotNull(snapshot)
        val p = requireNotNull(plan)
        val warning = p.warnings.singleOrNull {
            it.contains("Review-only proposal:") && it.contains("complete project-wide reference analysis is not advertised")
        }
        assertNotNull(warning, "expected an explicit project-wide-analysis boundary warning: ${p.warnings}")
        assertTrue(Regex("${Regex.escape(pomPath)}:[1-9][0-9]*").containsMatchIn(warning), warning)
        assertTrue(p.warnings.none { it.startsWith("Forced delete:") }, "unforced review proposal must not claim force: ${p.warnings}")

        val before = workspaceImage(root)
        val result = PatchEngine(root).apply(
            p,
            snap,
            ApplyAuthorization.explicit("story-bdd"),
            DiagnosticsGate.disabled("story-bdd"),
        )
        val refused = result as? ApplyResult.Refused
            ?: error("PatchEngine unexpectedly applied a review-only plan: $result")
        assertTrue(refused.diagnostics.any { it.code == refusalCode }, refused.diagnostics.toString())
        assertEquals(before, workspaceImage(root), "review-only Apply must preserve workspace bytes")
        assertTrue(!Files.exists(root.resolve(".refactorkit/workspace.lock")), "refusal must happen before workspace locking")
        assertTrue(!Files.exists(root.resolve(".refactorkit/transactions")), "refusal must not create transaction metadata")
    }

    // ------------------------------------------------------------ Scenario 1 Thens

    @Then("^the plan reports the adapter diagnostics computed after the declaration file is removed from the snapshot$")
    fun planReportsDiagnosticsAfterDeclarationRemoved() {
        val p = requireNotNull(plan)
        val snap = requireNotNull(snapshot)
        val decl = requireNotNull(declarationPath)
        val expected = adapter.diagnostics(snap.copy(files = snap.files.filterNot { it.path == decl }))
        assertEquals(expected, p.diagnosticsAfterPreview, "expected diagnostics computed after declaration removal: ${p.diagnosticsAfterPreview}")
    }

    @Then("^the plan warns that Java source references were evaluated using exact JDT type-binding evidence$")
    fun planWarnsJdtTypeBindingEvidence() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any { it.contains("Java source references were evaluated using exact JDT type-binding evidence") },
            "expected the JDT type-binding warning: ${p.warnings}",
        )
    }

    @Then("^the plan warns that captured workspace text inputs are scanned for literal target names only$")
    fun planWarnsCapturedTextInputsAreLiteralOnly() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any {
                it.contains("Captured workspace text inputs were scanned for literal target type names") &&
                    it.contains("dynamic references are not resolved")
            },
            "expected the bounded workspace-text-reference warning: ${p.warnings}",
        )
    }

    @Then("^the plan summary names the fully qualified type and the declaration file path$")
    fun planSummaryNamesFqnAndDeclaration() {
        val p = requireNotNull(plan)
        assertTrue(
            p.summary.contains(requireNotNull(symbolFqn)) && p.summary.contains(requireNotNull(declarationPath).toString()),
            "expected FQN and declaration path in summary: ${p.summary}",
        )
    }

    // ------------------------------------------------------------ Scenario 2 Thens

    @Then("^the plan warns that the delete is forced and that the found references were ignored and the build will break$")
    fun planWarnsForcedDelete() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any {
                it.contains("Forced delete") && it.contains("reference(s) were found and ignored") &&
                    it.contains("break the build")
            },
            "expected the forced-delete warning: ${p.warnings}",
        )
    }

    // ------------------------------------------------------------ Scenario 3 Thens

    @Then("^the plan warns that JDT type-binding evidence was unavailable or not clean and that Java source references use lexical fallback and need careful review$")
    fun planWarnsLexicalFallback() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any {
                it.contains("JDT type-binding evidence was unavailable or not clean") &&
                    it.contains("lexical fallback") && it.contains("Review carefully")
            },
            "expected the lexical-fallback warning: ${p.warnings}",
        )
    }

    // ------------------------------------------------------------ Scenario 4 Thens

    @Then("^the plan carries a warning that names the (.+) framework and the detected annotation$")
    fun planWarnsFramework(framework: String) {
        val p = requireNotNull(plan)
        val enumName = frameworkEnumName(framework.trim())
        val annotation = frameworkAnnotation(framework.trim())
        assertTrue(
            p.warnings.any { it.contains(enumName) && it.contains(annotation) },
            "expected a warning naming $enumName and $annotation: ${p.warnings}",
        )
    }

    @Then("^the plan warns about the framework annotation locations$")
    fun planWarnsFrameworkLocations() {
        val p = requireNotNull(plan)
        assertTrue(
            p.warnings.any { it.contains("Framework annotation locations:") },
            "expected the framework annotation locations warning: ${p.warnings}",
        )
    }

    // ------------------------------------------------------------ Scenario 5 Thens

    @Then("^the refusal operation name is deterministically safeDelete$")
    fun refusalOperationName() {
        val p = requireNotNull(plan)
        assertEquals("safeDelete", p.operation, p.toString())
    }

    @Then("^the refusal carries an empty WorkspaceEdit$")
    fun refusalCarriesEmptyWorkspaceEdit() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(p.workspaceEdit.edits.isEmpty(), "expected empty WorkspaceEdit: ${p.toString()}")
    }

    @Then("^the refusal carries an empty affected-file set$")
    fun refusalCarriesEmptyAffectedSet() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(p.affectedFiles.isEmpty(), "expected empty affected-file set: ${p.toString()}")
    }

    @Then("^the refusal grants no approval and no managed-write eligibility$")
    fun refusalGrantsNoApprovalNoManagedWrite() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(!p.requiresUserApproval, "expected no user approval on the refusal: ${p.toString()}")
        assertTrue(p.authorityLease == null, "expected no managed-write authority lease: ${p.toString()}")
    }

    @Then("^the refusal carries confidence 0.0 and risk HIGH$")
    fun refusalCarriesZeroConfidenceHighRisk() {
        val p = requireNotNull(plan)
        assertEquals(0.0, p.confidence, "expected zero confidence on a refused plan: ${p.toString()}")
        assertEquals(RiskLevel.HIGH, p.riskLevel, p.toString())
    }

    @Then("^the refusal carries its real reason message in both the summary and the warnings$")
    fun refusalCarriesRealReasonInSummaryAndWarnings() {
        val p = requireNotNull(plan)
        assertTrue(p.summary.isNotBlank(), "refusal summary must carry the real reason")
        assertEquals(listOf(p.summary), p.warnings, "refusal warnings must carry the same real reason")
    }

    @Then("^the refusal carries no typed refusal code because refusals are message-based$")
    fun refusalCarriesNoTypedCode() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(p.refusalCode == null, "expected no typed refusal code: ${p.refusalCode}")
    }

    // ------------------------------------------------------------ Scenario 6/7/8 Thens

    @Then("^the adapter returns a REFUSED patch plan for the operation safeDelete$")
    fun adapterReturnsRefusedSafeDelete() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals("safeDelete", p.operation, p.toString())
    }

    @Then("^the refusal carries an empty WorkspaceEdit and an empty affected-file set$")
    fun refusalCarriesEmptyEditAndAffectedSet() {
        val p = requireNotNull(plan)
        assertTrue(p.workspaceEdit.edits.isEmpty(), "expected empty WorkspaceEdit: ${p.toString()}")
        assertTrue(p.affectedFiles.isEmpty(), "expected empty affected-file set: ${p.toString()}")
    }

    @Then("^the refusal grants no approval and carries confidence 0.0 with risk HIGH$")
    fun refusalGrantsNoApprovalZeroConfidenceHighRisk() {
        val p = requireNotNull(plan)
        assertTrue(!p.requiresUserApproval, "expected no user approval on the refusal: ${p.toString()}")
        assertEquals(0.0, p.confidence, "expected zero confidence on a refused plan: ${p.toString()}")
        assertEquals(RiskLevel.HIGH, p.riskLevel, p.toString())
    }

    @Then("^the refusal summary and warning carry a message that begins with Cannot delete and the fully qualified type name and that reports the reference count and the evidence kind$")
    fun refusalSummaryBeginsCannotDelete() {
        val p = requireNotNull(plan)
        val fqn = requireNotNull(symbolFqn)
        assertTrue(
            p.summary.startsWith("Cannot delete $fqn"),
            "expected message to begin with 'Cannot delete $fqn': ${p.summary}",
        )
        assertTrue(
            p.summary.contains("reference(s) found using JDT binding evidence"),
            "expected reference count and evidence kind in message: ${p.summary}",
        )
    }

    @Then("^the refusal message lists each reference as a path and line, up to the first twenty references$")
    fun refusalMessageListsReferencePathsAndLines() {
        val p = requireNotNull(plan)
        val lines = referenceLines(p.summary)
        assertTrue(lines.isNotEmpty(), "expected at least one reference line in the message: ${p.summary}")
        assertTrue(
            lines.all { it.matches(Regex("^\\s{2}.+:\\d+$")) },
            "expected each reference line to be '  <path>:<line>': ${lines}",
        )
    }

    @Then("^the refusal message lists the first twenty references$")
    fun refusalMessageListsFirstTwenty() {
        val p = requireNotNull(plan)
        assertEquals(20, referenceLines(p.summary).size, "expected exactly twenty listed references: ${p.summary}")
    }

    @Then("^the refusal message appends a line that reports the additional references beyond the first twenty$")
    fun refusalMessageAppendsMoreSuffix() {
        val p = requireNotNull(plan)
        val snap = requireNotNull(snapshot)
        val fqn = requireNotNull(symbolFqn)
        val decl = requireNotNull(declarationPath)
        val count = jdtReferenceCount(snap, fqn, decl)
        assertTrue(count > 20, "expected more than twenty references, got $count")
        assertTrue(
            p.summary.contains("... and ${count - 20} more"),
            "expected '... and ${count - 20} more' suffix: ${p.summary}",
        )
    }

    @Then("^the refusal message carries the phrase Use --force to delete anyway \\(dangerous\\).$")
    fun refusalMessageCarriesForcePhrase() {
        val p = requireNotNull(plan)
        assertTrue(
            p.summary.contains("Use --force to delete anyway (dangerous)."),
            "expected the force instruction phrase: ${p.summary}",
        )
    }

    @Then("^the refusal summary and warning carry the message \"([^\"]+)\"$")
    fun refusalCarriesMessage(declaredMessage: String) {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals(
            declaredMessage, p.summary,
            "declared message '$declaredMessage' did not equal the actual summary '${p.summary}'",
        )
        assertEquals(
            listOf(declaredMessage), p.warnings,
            "declared message '$declaredMessage' did not equal the actual warnings '${p.warnings}'",
        )
        observedRefusals += ObservedRefusal(declaredMessage, p.summary, p.status)
    }

    // ------------------------------------------------------------------ helpers

    private fun previewUnderCondition(condition: String) {
        val snap = requireNotNull(snapshot)
        when (condition) {
            "the fully qualified type name is absent from the symbol index or is not a deleteable type" ->
                plan = planner.preview(snap, "com.example.Missing")
            "the declaration path is inside a generated-source or build-output location",
            "the declaration source declares a generated-code annotation",
            "the declaration source header identifies generated code",
            -> plan = planner.preview(snap, "com.example.Generated")
            else -> error("Unknown condition: '$condition'")
        }
    }

    private fun buildFixture(condition: String): Path = when (condition) {
        "the fully qualified type name is absent from the symbol index or is not a deleteable type" ->
            buildStandardFixture()
        "the declaration path is inside a generated-source or build-output location" ->
            buildGeneratedPathFixture()
        "the declaration source declares a generated-code annotation" ->
            buildGeneratedAnnotationFixture()
        "the declaration source header identifies generated code" ->
            buildGeneratedHeaderFixture()
        else -> error("Unknown condition: '$condition'")
    }

    private fun buildStandardFixture(): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-standard")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/Legacy.java",
            "package com.example;\npublic class Legacy {}\n",
        )
        return root
    }

    private fun buildReferencedFixture(): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-referenced")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/Legacy.java",
            "package com.example;\npublic class Legacy {}\n",
        )
        write(
            root,
            "src/main/java/com/example/Client.java",
            "package com.example;\npublic class Client { Legacy legacy = new Legacy(); }\n",
        )
        return root
    }

    private fun buildLexicalFixture(): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-lexical")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/Legacy.java",
            "package com.example;\npublic class Legacy {}\n",
        )
        write(
            root,
            "src/main/java/com/example/NeedsDependency.java",
            "package com.example;\npublic class NeedsDependency { private MissingDependency dependency; }\n",
        )
        return root
    }

    private fun buildFrameworkFixture(framework: String): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-framework")
        writePom(root, "src/main/java")
        val annotation = frameworkAnnotation(framework)
        write(
            root,
            "src/main/java/com/example/Legacy.java",
            "package com.example;\n$annotation\npublic class Legacy {}\n",
        )
        return root
    }

    private fun buildManyReferencesFixture(): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-manyrefs")
        writePom(root, "src/main/java")
        write(
            root,
            "src/main/java/com/example/Legacy.java",
            "package com.example;\npublic class Legacy {}\n",
        )
        val fields = (1..21).joinToString("\n") { "    Legacy l$it = new Legacy();" }
        write(
            root,
            "src/main/java/com/example/Client.java",
            "package com.example;\npublic class Client {\n$fields\n}\n",
        )
        return root
    }

    private fun buildGeneratedPathFixture(): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-gen-path")
        writePom(root, ".")
        write(
            root,
            "build/generated/sources/annotationProcessor/java/main/Generated.java",
            "package com.example;\npublic class Generated {}\n",
        )
        return root
    }

    private fun buildGeneratedAnnotationFixture(): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-gen-annotation")
        writePom(root, ".")
        write(
            root,
            "Generated.java",
            "@Generated\npackage com.example;\npublic class Generated {}\n",
        )
        return root
    }

    private fun buildGeneratedHeaderFixture(): Path {
        val root = temporaryDirectory("rk-jvm-safedelete-gen-header")
        writePom(root, ".")
        write(
            root,
            "Generated.java",
            "// generated by a schema processor, do not edit\npackage com.example;\npublic class Generated {}\n",
        )
        return root
    }

    private fun jdtReferenceCount(snap: ProjectSnapshot, symbolFqn: String, declarationPath: Path): Int {
        val analysis = JdtJavaSemanticAnalyzer().analyze(snap)
        val target = analysis.symbols.single { symbol ->
            symbol.qualifiedName == symbolFqn && symbol.kind in JDT_DELETEABLE_KINDS
        }
        val key = requireNotNull(target.bindingKey)
        return analysis.references
            .filter { it.bindingKey == key && it.path != declarationPath }
            .distinctBy {
                "${it.path}:${it.sourceRange.start.line}:${it.sourceRange.start.character}:${it.sourceRange.end.character}"
            }
            .size
    }

    private fun referenceLines(summary: String): List<String> =
        summary.lines().filter { it.startsWith("  ") && !it.startsWith("  ...") }

    private fun frameworkEnumName(framework: String): String = when (framework) {
        "Spring" -> "SPRING"
        "JPA" -> "JPA"
        "Jackson" -> "JACKSON"
        else -> error("Unknown framework: '$framework'")
    }

    private fun frameworkAnnotation(framework: String): String = when (framework) {
        "Spring" -> "@Component"
        "JPA" -> "@Entity"
        "Jackson" -> "@JsonProperty"
        else -> error("Unknown framework: '$framework'")
    }

    private fun writePom(root: Path, sourceDir: String) {
        write(
            root,
            "pom.xml",
            """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId><artifactId>safedelete</artifactId><version>1</version>
              <build><sourceDirectory>$sourceDir</sourceDirectory></build>
            </project>
            """.trimIndent(),
        )
    }

    private fun write(root: Path, rel: String, content: String) {
        val p = root.resolve(rel)
        p.parent.createDirectories()
        p.writeText(content)
    }

    private fun temporaryDirectory(prefix: String): Path {
        val base = Path.of(System.getProperty("user.dir")).resolve("build/test-tmp").toAbsolutePath().normalize()
        Files.createDirectories(base)
        return Files.createTempDirectory(base, prefix).also(temporaryDirectories::add)
    }

    private fun workspaceImage(root: Path): Map<Path, String> = Files.walk(root).use { files ->
        files.toList().filter(Files::isRegularFile)
            .associate { path -> root.relativize(path) to Files.readString(path) }
    }

    private fun projectImage(root: Path): Map<Path, String> = Files.walk(root).use { files ->
        files.toList().filter(Files::isRegularFile)
            .filter { !root.relativize(it).normalize().startsWith(Path.of(".refactorkit")) }
            .associate { path -> root.relativize(path) to Files.readString(path) }
    }

    private fun deleteNoFollow(root: Path) {
        if (!root.exists()) return
        Files.walk(root).use { stream -> stream.toList() }
            .sortedByDescending(Path::getNameCount)
            .forEach { path -> Files.delete(path) }
    }

    @After
    fun cleanup(scenario: Scenario) {
        val reportDir = Path.of(System.getProperty("user.dir")).resolve("build/reports/cucumber")
        reportDir.createDirectories()
        val report = reportDir.resolve("java-safe-delete-characterization-messages.txt")
        val lines = mutableListOf<String>()
        lines += "scenario:${scenario.name}"
        observedRefusals.forEach { lines += "  ${it.declaredMessage} -> ${it.actualMessage} (${it.status})" }
        plan?.let { lines += "  plan:${it.status} operation=${it.operation} confidence=${it.confidence} risk=${it.riskLevel}" }
        Files.write(report, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND)

        daemonSession?.close()
        daemonSession = null

        var cleanupFailure: Throwable? = null
        temporaryDirectories.asReversed().forEach { directory ->
            try {
                deleteNoFollow(directory)
            } catch (failure: Throwable) {
                cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
            }
        }
        temporaryDirectories.clear()
        cleanupFailure?.let { throw it }
    }

    private data class ObservedRefusal(
        val declaredMessage: String,
        val actualMessage: String,
        val status: PatchStatus,
    )

    private companion object {
        val JDT_DELETEABLE_KINDS = setOf(
            JdtJavaSemanticSymbolKind.CLASS,
            JdtJavaSemanticSymbolKind.INTERFACE,
            JdtJavaSemanticSymbolKind.ENUM,
            JdtJavaSemanticSymbolKind.RECORD,
            JdtJavaSemanticSymbolKind.ANNOTATION,
        )
    }
}
