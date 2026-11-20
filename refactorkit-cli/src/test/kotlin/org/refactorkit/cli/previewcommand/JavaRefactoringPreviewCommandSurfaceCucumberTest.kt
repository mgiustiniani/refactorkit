package org.refactorkit.cli.previewcommand

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import kotlinx.serialization.json.*
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-refactoring-preview-command-boundary.feature")
@SelectClasspathResource("java-organize-imports-path-selection.feature")
@SelectClasspathResource("java-effective-project-context.feature")
@SelectClasspathResource("java-create-maven-module.feature")
@SelectClasspathResource("java-gradle-create-module.feature")
@SelectClasspathResource("java-gradle-module-rename.feature")
@SelectClasspathResource("java-query-context-authority.feature")
@SelectClasspathResource("java-mutation-context-authority.feature")
@SelectClasspathResource("java-gradle-effective-context.feature")
@SelectClasspathResource("java-retained-plan-diff.feature")
@SelectClasspathResource("java-journal-sdk-authority.feature")
@SelectClasspathResource("java-protected-sdk-rollback.feature")
@SelectClasspathResource("java-journal-format-compatibility.feature")
@SelectClasspathResource("java-immutable-editor-overlay.feature")
@SelectClasspathResource("java-semantic-navigation-overlay.feature")
@SelectClasspathResource("java-cursor-mutation-selection.feature")
@SelectClasspathResource("java-sdk-rollback-original-diagnostics.feature")
@SelectClasspathResource("java-gradle-main-test-source-lookup.feature")
@SelectClasspathResource("java-editor-mutation-preview.feature")
@SelectClasspathResource("java-scan-output-directory-exclusion.feature")
@SelectClasspathResource("java-scan-declared-output-module.feature")
@SelectClasspathResource("java-package-migration-provider.feature")
@SelectClasspathResource("java-move-class-cross-module.feature")
@SelectClasspathResource("java-capability-truth.feature")
@SelectClasspathResource("java-extract-interface.feature")
@SelectClasspathResource("java-extract-superclass.feature")
@SelectClasspathResource("java-hierarchy-pull-up-bounded.feature")
@SelectClasspathResource("java-hierarchy-push-down-bounded.feature")
@SelectClasspathResource("java-change-visibility.feature")
@SelectClasspathResource("java-batch-cleanup.feature")
@SelectClasspathResource("java-extract-variable.feature")
@SelectClasspathResource("java-extract-constant.feature")
@SelectClasspathResource("java-inline-constant.feature")
@SelectClasspathResource("java-extract-field.feature")
@SelectClasspathResource("java-extract-parameter.feature")
@SelectClasspathResource("java-runtime-bounds.feature")
@SelectClasspathResource("c-clangd-launch-configuration.feature")
@SelectClasspathResource("c-compilation-record-working-directory.feature")
@SelectClasspathResource("c-build-model-compilation-units.feature")
@SelectClasspathResource("interim-linux-qualification-selection.feature")
@SelectClasspathResource("interim-host-support-reconciliation.feature")
@SelectClasspathResource("c-warm-configuration-invalidation.feature")
@SelectClasspathResource("c-clangd-resource-context.feature")
@SelectClasspathResource("c-clangd-resource-admission.feature")
@SelectClasspathResource("c-definition-response-identity.feature")
@SelectClasspathResource("c-function-facts-captured-execution.feature")
@SelectClasspathResource("c-function-facts-preview-evaluation.feature")
@SelectClasspathResource("c-parameter-reordering-preview.feature")
@SelectClasspathResource("c-parameter-reordering-managed-apply.feature")
@SelectClasspathResource("c-parameter-reordering-managed-surfaces.feature")
@SelectClasspathResource("c-parameter-reordering-cli.feature")
@SelectClasspathResource("c-parameter-insertion-preview.feature")
@SelectClasspathResource("c-parameter-insertion-managed-apply.feature")
@SelectClasspathResource("c-parameter-insertion-managed-surfaces.feature")
@SelectClasspathResource("c-parameter-insertion-cli.feature")
@SelectClasspathResource("c-statement-facts-captured-execution.feature")
@SelectClasspathResource("c-statement-helper-preview.feature")
@SelectClasspathResource("c-statement-helper-managed-apply.feature")
@SelectClasspathResource("c-statement-helper-managed-surfaces.feature")
@SelectClasspathResource("c-statement-helper-cli.feature")
@SelectClasspathResource("c-scalar-facts-captured-execution.feature")
@SelectClasspathResource("c-scalar-extraction-preview.feature")
@SelectClasspathResource("c-scalar-extraction-managed-apply.feature")
@SelectClasspathResource("c-scalar-extraction-managed-surfaces.feature")
@SelectClasspathResource("c-scalar-extraction-cli.feature")
@SelectClasspathResource("c-native-managed-post-commit.feature")
@SelectClasspathResource("c-native-resource-post-commit.feature")
@SelectClasspathResource("c-elf-artifact-input-capture.feature")
@SelectClasspathResource("c-native-runtime-paths.feature")
@SelectClasspathResource("c-runtime-manifest-capture.feature")
@SelectClasspathResource("c-glibc-runtime-inputs.feature")
@SelectClasspathResource("c-glibc-runtime-resolution.feature")
@SelectClasspathResource("c-toolchain-runtime-discovery.feature")
@SelectClasspathResource("c-compiler-runtime-currency.feature")
@SelectClasspathResource("c-formatter-runtime-currency.feature")
@SelectClasspathResource("c-clang-resource-directory-capture.feature")
@SelectClasspathResource("c-compiler-resource-context.feature")
@SelectClasspathResource("c-syntax-resource-context.feature")
@SelectClasspathResource("c-resource-bound-preview-authority.feature")
@SelectClasspathResource("typescript-advanced-surfaces.feature")
@SelectClasspathResource("managed-module-crash-recovery.feature")
@SelectClasspathResource("c-format-process-failure.feature")
@SelectClasspathResource("c-format-source-context.feature")
@SelectClasspathResource("c-format-managed-apply.feature")
@SelectClasspathResource("c-format-managed-style-drift.feature")
@SelectClasspathResource("c-format-snapshot-stdin.feature")
@SelectClasspathResource("c-format-style-snapshot.feature")
@SelectClasspathResource("c-reference-failure-safe-delete.feature")
@SelectClasspathResource("c-safe-delete-declaration-boundary.feature")
@SelectClasspathResource("c-inline-local-value-preservation.feature")
@SelectClasspathResource("c-inline-function-value-preservation.feature")
@SelectClasspathResource("c-extract-expression-type-and-placement.feature")
@SelectClasspathResource("c-include-order-independence.feature")
@SelectClasspathResource("c-format-preserves-include-order.feature")
@SelectClasspathResource("c-compilation-context-apply-refusal.feature")
@SelectClasspathResource("c-caller-diagnostics-gate-authority.feature")
@SelectClasspathResource("c-cmake-snapshot-capture.feature")
@SelectClasspathResource("c-cmake-literal-move-binding.feature")
@SelectClasspathResource("c-cmake-literal-component-binding.feature")
@SelectClasspathResource("c-cmake-component-managed-apply.feature")
@SelectClasspathResource("c-cmake-apply-context-refusal.feature")
@SelectClasspathResource("c-cmake-nested-manifest-refusal.feature")
@SelectClasspathResource("c-cmake-late-manifest-apply-refusal.feature")
@SelectClasspathResource("c-cmake-single-tu-managed-apply.feature")
@SelectClasspathResource("c-cmake-single-tu-managed-surface.feature")
@SelectClasspathResource("c-cmake-late-configuration-refusal.feature")
@SelectClasspathResource("c-cmake-preview-diagnostics-evidence.feature")
@SelectClasspathResource("c-cmake-library-gate-bypass-refusal.feature")
@SelectClasspathResource("c-cmake-library-tu-managed-apply.feature")
@SelectClasspathResource("c-cmake-multi-tu-managed-apply.feature")
@SelectClasspathResource("c-cmake-multi-tu-compile-context.feature")
@SelectClasspathResource("c-cmake-all-tu-diagnostics.feature")
@SelectClasspathResource("c-compiler-exit-consistency.feature")
@SelectClasspathResource("c-cmake-tu-ownership.feature")
@SelectClasspathResource("c-cmake-include-flag-origin.feature")
@SelectClasspathResource("c-cmake-consumer-probes.feature")
@SelectClasspathResource("c-cmake-compiler-define-probes.feature")
@SelectClasspathResource("c-cmake-token-paste-consumers.feature")
@SelectClasspathResource("c-cmake-trigraph-consumers.feature")
@SelectClasspathResource("c-cmake-resource-probes.feature")
@SelectClasspathResource("c-cmake-newline-consumers.feature")
@SelectClasspathResource("c-cmake-moved-path-macros.feature")
@SelectClasspathResource("c-cmake-compiler-define-path-macros.feature")
@SelectClasspathResource("c-cmake-spliced-directives.feature")
@SelectClasspathResource("c-cmake-unparsed-directives.feature")
@SelectClasspathResource("c-cmake-header-include-move.feature")
@SelectClasspathResource("c-cmake-shared-header-managed-apply.feature")
@SelectClasspathResource("c-semantic-rename-exact-selection.feature")
@SelectClasspathResource("c-symbol-prefix-rename.feature")
@SelectClasspathResource("c-symbol-prefix-rename-cli.feature")
@SelectClasspathResource("java-formatter-profile.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.cli.previewcommand")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-refactoring-preview-command-surface.json",
)
class JavaRefactoringPreviewCommandSurfaceCucumberTest

/** Shared preview-command runner glue for the Java Gradle module-creation Story. */
class JavaCreateGradleModuleSteps {
    private lateinit var root: java.nio.file.Path
    private var container: java.nio.file.Path? = null
    private var session: org.refactorkit.daemon.DaemonSession? = null
    private lateinit var dsl: String
    private lateinit var snapshotHash: String
    private lateinit var baseline: Map<java.nio.file.Path, String>
    private lateinit var javaContext: kotlinx.serialization.json.JsonObject
    private var selectedSettingsForm = "literal include"
    private var preview: kotlinx.serialization.json.JsonObject? = null
    private var applied: kotlinx.serialization.json.JsonObject? = null
    private var rollback: kotlinx.serialization.json.JsonObject? = null
    private var modelInvalidated = false
    private var modelAvailableAfterResult = false
    private var renameRefusalCode: String? = null

    @io.cucumber.java.en.Given("the Gradle rename settings form {string} is selected")
    fun selectRenameSettingsForm(form: String) {
        require(form in setOf("literal include", "explicit projectDir map", "conditional include"))
        selectedSettingsForm = form
    }

    @io.cucumber.java.en.Given("a synchronized {word} Gradle composite workspace with one existing Java module")
    fun synchronizedWorkspace(selectedDsl: String) {
        require(selectedDsl in setOf("Kotlin", "Groovy"))
        dsl = selectedDsl
        val owned = java.nio.file.Files.createTempDirectory("rk-gradle-add-module-")
        container = owned
        root = owned.resolve("workspace")
        val kotlinDsl = selectedDsl == "Kotlin"
        val settingsName = if (kotlinDsl) "settings.gradle.kts" else "settings.gradle"
        val buildName = if (kotlinDsl) "build.gradle.kts" else "build.gradle"
        write(root.resolve(settingsName), if (kotlinDsl) {
            "rootProject.name = \"add-module\"\nincludeBuild(\"selected-build\")\n"
        } else {
            "rootProject.name = 'add-module'\nincludeBuild 'selected-build'\n"
        })
        val selectedBuildSettings = when (selectedSettingsForm) {
            "literal include" -> if (kotlinDsl) {
                "rootProject.name = \"selected-build\"\ninclude(\":app\")\n"
            } else {
                "rootProject.name = 'selected-build'\ninclude ':app'\n"
            }
            "explicit projectDir map" -> if (kotlinDsl) {
                "rootProject.name = \"selected-build\"\ninclude(\":app\")\nproject(\":app\").projectDir = file(\"app\")\n"
            } else {
                "rootProject.name = 'selected-build'\ninclude ':app'\nproject(':app').projectDir = file('app')\n"
            }
            "conditional include" -> if (kotlinDsl) {
                "rootProject.name = \"selected-build\"\nif (true) { include(\":app\") }\n"
            } else {
                "rootProject.name = 'selected-build'\nif (true) { include ':app' }\n"
            }
            else -> error("Unsupported Gradle settings form: $selectedSettingsForm")
        }
        write(root.resolve("selected-build/$settingsName"), selectedBuildSettings)
        write(root.resolve("selected-build/app/$buildName"), if (kotlinDsl) {
            "plugins { id(\"java\") }\n"
        } else {
            "plugins { id 'java' }\n"
        })
        write(root.resolve("selected-build/app/src/main/java/fixture/Existing.java"),
            "package fixture; public class Existing {}\n")
        session = org.refactorkit.daemon.DaemonSession()
        val active = requireNotNull(session)
        val opened = active.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        val synchronized = active.dispatch("project.sync", kotlinx.serialization.json.buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", opened.getValue("snapshotHash").jsonPrimitive.content)
            put("sdkHome", System.getProperty("java.home"))
            put("gradleExecutable", java.nio.file.Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists",
                "gradle-9.6.1-bin", "4ticwg1pgcbps2hj28r8so764", "gradle-9.6.1", "bin", "gradle").toString())
            put("allowBuildExecution", true)
        }).jsonObject
        kotlin.test.assertEquals("synchronized", synchronized.getValue("status").jsonPrimitive.content, synchronized.toString())
        snapshotHash = synchronized.getValue("snapshotHash").jsonPrimitive.content
        val context = active.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
        val provider = context.getValue("providers").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "gradle-effective-v1" }
        kotlin.test.assertEquals("available", provider.getValue("status").jsonPrimitive.content, context.toString())
        javaContext = kotlinx.serialization.json.buildJsonObject {
            put("sdkHome", System.getProperty("java.home"))
            put("expectedContextHash", context.getValue("contextHash").jsonPrimitive.content)
            put("contextSchemaVersion", context.getValue("schemaVersion").jsonPrimitive.content)
        }
        baseline = userInputs()
    }

    @io.cucumber.java.en.When("the daemon previews and applies Gradle module path {string}")
    fun previewApplyAndRollback(projectPath: String) {
        val active = requireNotNull(session)
        val methods = active.dispatch("server.capabilities", kotlinx.serialization.json.JsonObject(emptyMap())).jsonObject
            .getValue("methods").jsonArray.map { it.jsonObject }
        val capability = methods.single { it.getValue("name").jsonPrimitive.content == "java.createGradleModule" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        preview = active.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "createGradleModule")
            put("languageId", "java")
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", javaContext)
            put("arguments", kotlinx.serialization.json.buildJsonObject {
                put("buildRoot", "selected-build")
                put("projectPath", projectPath)
            })
        }).jsonObject
        val plan = requireNotNull(preview)
        applied = active.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", plan.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", javaContext)
        }).jsonObject
        val transaction = requireNotNull(applied)
        val postHash = transaction.getValue("snapshotHash").jsonPrimitive.content
        val postContext = active.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", postHash)
            put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
        val gradleProvider = postContext.getValue("providers").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "gradle-effective-v1" }
        modelInvalidated = gradleProvider.getValue("status").jsonPrimitive.content != "available"
        rollback = active.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", transaction.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", postHash)
            put("javaContext", kotlinx.serialization.json.buildJsonObject {
                put("sdkHome", System.getProperty("java.home"))
                put("expectedContextHash", javaContext.getValue("expectedContextHash"))
                put("contextSchemaVersion", javaContext.getValue("contextSchemaVersion"))
            })
        }).jsonObject
    }

    @io.cucumber.java.en.When("the daemon previews the Gradle module rename for {string} settings")
    fun previewRename(settingsForm: String) {
        kotlin.test.assertEquals(selectedSettingsForm, settingsForm)
        val active = requireNotNull(session)
        val methods = active.dispatch("server.capabilities", kotlinx.serialization.json.JsonObject(emptyMap())).jsonObject
            .getValue("methods").jsonArray.map { it.jsonObject }
        val capability = methods.single { it.getValue("name").jsonPrimitive.content == "java.renameGradleModule" }
        kotlin.test.assertEquals("experimental", capability.getValue("stability").jsonPrimitive.content)
        val features = capability.getValue("features").jsonObject
        listOf("preview", "renderedDiff", "apply", "rollback", "literalSettingsOnly",
            "preservePhysicalProjectDirectory", "explicitGradleSyncRequired", "inboundProjectDependenciesRefused")
            .forEach { feature -> kotlin.test.assertEquals("true", features.getValue(feature).jsonPrimitive.content) }
        preview = try {
            active.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "renameGradleModule")
                put("languageId", "java")
                put("expectedSnapshotHash", snapshotHash)
                put("javaContext", javaContext)
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("buildRoot", "selected-build")
                    put("oldProjectPath", ":app")
                    put("newProjectPath", ":services:catalog")
                })
            }).jsonObject
        } catch (failure: org.refactorkit.core.JsonRpcException) {
            if (failure.code != org.refactorkit.core.JsonRpcErrorCodes.PLAN_REFUSED) throw failure
            val data = failure.data?.jsonObject ?: throw failure
            val code = data["refusalCode"]?.jsonPrimitive?.contentOrNull ?: throw failure
            kotlinx.serialization.json.buildJsonObject {
                put("status", "REFUSED")
                put("operation", "java.renameGradleModule")
                put("refusalCode", code)
                put("refusalMessage", failure.message)
            }
        }
        val plan = requireNotNull(preview)
        renameRefusalCode = plan["refusalCode"]?.jsonPrimitive?.contentOrNull
        when (plan.getValue("status").jsonPrimitive.content) {
            "PREVIEW" -> {
                val transaction = active.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
                    put("planId", plan.getValue("planId").jsonPrimitive.content)
                    put("expectedSnapshotHash", snapshotHash)
                    put("javaContext", javaContext)
                }).jsonObject
                applied = transaction
                val postHash = transaction.getValue("snapshotHash").jsonPrimitive.content
                val postContext = active.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
                    put("expectedSnapshotHash", postHash)
                    put("sdkHome", System.getProperty("java.home"))
                }).jsonObject
                val provider = postContext.getValue("providers").jsonArray.map { it.jsonObject }
                    .single { it.getValue("providerId").jsonPrimitive.content == "gradle-effective-v1" }
                modelInvalidated = provider.getValue("status").jsonPrimitive.content != "available"
                rollback = active.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
                    put("transactionId", transaction.getValue("transactionId").jsonPrimitive.content)
                    put("expectedSnapshotHash", postHash)
                    put("javaContext", kotlinx.serialization.json.buildJsonObject {
                        put("sdkHome", System.getProperty("java.home"))
                        put("expectedContextHash", javaContext.getValue("expectedContextHash"))
                        put("contextSchemaVersion", javaContext.getValue("contextSchemaVersion"))
                    })
                }).jsonObject
                modelAvailableAfterResult = gradleModelAvailable(active, snapshotHash)
            }
            "REFUSED" -> {
                modelAvailableAfterResult = gradleModelAvailable(active, snapshotHash)
            }
            else -> kotlin.test.fail("Unexpected Gradle rename plan: $plan")
        }
    }

    @io.cucumber.java.en.Then("the experimental Gradle module rename returns {word} with refusal {word} and a reversible settings-only preview")
    fun renameHasBoundedResult(expectedStatus: String, expectedRefusalCode: String) {
        val plan = requireNotNull(preview)
        kotlin.test.assertEquals(expectedStatus, plan.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("java.renameGradleModule", plan.getValue("operation").jsonPrimitive.content)
        if (expectedStatus == "PREVIEW") {
            kotlin.test.assertEquals("none", expectedRefusalCode)
            kotlin.test.assertEquals(null, renameRefusalCode)
            val settingsPath = if (dsl == "Kotlin") "selected-build/settings.gradle.kts" else "selected-build/settings.gradle"
            kotlin.test.assertEquals(setOf(settingsPath),
                plan.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet())
            val diff = plan.getValue("renderedDiff").jsonPrimitive.content
            kotlin.test.assertTrue(diff.contains(if (dsl == "Kotlin") "include(\":services:catalog\")" else "include ':services:catalog'"), diff)
            val expectedMapping = if (dsl == "Kotlin") {
                "project(\":services:catalog\").projectDir = file(\"app\")"
            } else {
                "project(':services:catalog').projectDir = file('app')"
            }
            kotlin.test.assertTrue(diff.contains(expectedMapping), diff)
            kotlin.test.assertEquals("applied", requireNotNull(applied).getValue("status").jsonPrimitive.content)
            kotlin.test.assertEquals("rolledBack", requireNotNull(rollback).getValue("status").jsonPrimitive.content)
            kotlin.test.assertEquals(snapshotHash, rollback?.getValue("snapshotHash")?.jsonPrimitive?.content)
            kotlin.test.assertTrue(modelInvalidated, "Apply must invalidate the previous synchronized Gradle model")
        } else {
            kotlin.test.assertEquals(expectedRefusalCode, renameRefusalCode)
            kotlin.test.assertFalse("affectedFiles" in plan || "workspaceEdit" in plan,
                "A refusal envelope must not expose an applicable patch")
            kotlin.test.assertEquals(null, applied)
            kotlin.test.assertEquals(null, rollback)
        }
    }

    @io.cucumber.java.en.Then("the workspace and synchronized Gradle model remain safe after the result")
    fun workspaceAndModelRemainSafe() {
        kotlin.test.assertEquals(baseline, userInputs(), "Preview, refusal, or rollback changed workspace bytes")
        kotlin.test.assertTrue(modelAvailableAfterResult, "Gradle context must remain usable after refusal or exact rollback")
    }

    private fun gradleModelAvailable(active: org.refactorkit.daemon.DaemonSession, expectedHash: String): Boolean {
        val context = active.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", expectedHash)
            put("sdkHome", System.getProperty("java.home"))
        }).jsonObject
        return context.getValue("providers").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == "gradle-effective-v1" }
            .getValue("status").jsonPrimitive.content == "available"
    }

    @io.cucumber.java.en.Then("the stable Gradle module capability previews the exact {word} settings append and matching build script")
    fun previewInfersDsl(expectedDsl: String) {
        kotlin.test.assertEquals(dsl, expectedDsl)
        val capability = requireNotNull(session).dispatch("server.capabilities", kotlinx.serialization.json.JsonObject(emptyMap())).jsonObject
            .getValue("methods").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "java.createGradleModule" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        val features = capability.getValue("features").jsonObject
        listOf("preview", "renderedDiff", "apply", "rollback", "workspaceRelativeBuildRoot",
            "canonicalNestedProjectPath", "settingsInferredDsl").forEach { feature ->
            kotlin.test.assertEquals("true", features.getValue(feature).jsonPrimitive.content, "Missing capability $feature")
        }
        val plan = requireNotNull(preview)
        kotlin.test.assertEquals("PREVIEW", plan.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("java.createGradleModule", plan.getValue("operation").jsonPrimitive.content)
        val settings = if (dsl == "Kotlin") "selected-build/settings.gradle.kts" else "selected-build/settings.gradle"
        val buildScript = if (dsl == "Kotlin") "selected-build/services/catalog/build.gradle.kts"
            else "selected-build/services/catalog/build.gradle"
        kotlin.test.assertEquals(setOf(settings, buildScript),
            plan.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val diff = plan.getValue("renderedDiff").jsonPrimitive.content
        kotlin.test.assertTrue(diff.contains(if (dsl == "Kotlin") "include(\":services:catalog\")" else "include ':services:catalog'"), diff)
        kotlin.test.assertTrue(diff.contains(if (dsl == "Kotlin") "plugins { id(\"java\") }" else "plugins { id 'java' }"), diff)
        kotlin.test.assertEquals("applied", requireNotNull(applied).getValue("status").jsonPrimitive.content)
    }

    @io.cucumber.java.en.Then("managed rollback restores every workspace byte and invalidates the synchronized Gradle model")
    fun rollbackIsExactAndInvalidatesModel() {
        kotlin.test.assertTrue(modelInvalidated, "Apply must invalidate the prior synchronized Gradle model")
        val result = requireNotNull(rollback)
        kotlin.test.assertEquals("rolledBack", result.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(snapshotHash, result.getValue("snapshotHash").jsonPrimitive.content)
        kotlin.test.assertEquals(baseline, userInputs())
        kotlin.test.assertFalse(java.nio.file.Files.exists(root.resolve("selected-build/services/catalog")))
    }

    @io.cucumber.java.After
    fun cleanup() {
        session?.close()
        container?.let { owned -> java.nio.file.Files.walk(owned).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists)
        } }
    }

    private fun write(path: java.nio.file.Path, text: String) {
        java.nio.file.Files.createDirectories(path.parent)
        java.nio.file.Files.writeString(path, text)
    }

    private fun userInputs(): Map<java.nio.file.Path, String> = java.nio.file.Files.walk(root).use { paths ->
        paths.filter(java.nio.file.Files::isRegularFile).filter { path ->
            root.relativize(path).none { it.toString() == ".refactorkit" }
        }.toList().associate { path ->
            root.relativize(path) to java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.nio.file.Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        }
    }
}

class JavaMakeStaticSteps {
    private lateinit var root: java.nio.file.Path
    private lateinit var source: String
    private lateinit var session: org.refactorkit.daemon.DaemonSession
    private lateinit var snapshotHash: String
    private lateinit var preview: kotlinx.serialization.json.JsonObject
    private lateinit var lspSession: org.refactorkit.lsp.LspSession
    private lateinit var lspSourcePath: java.nio.file.Path
    private lateinit var lspUri: String
    private lateinit var lspCapabilities: kotlinx.serialization.json.JsonObject
    private lateinit var lspActions: kotlinx.serialization.json.JsonArray
    private lateinit var javaContext: kotlinx.serialization.json.JsonObject
    private lateinit var savedMethodSelection: kotlinx.serialization.json.JsonObject
    private lateinit var appliedSelectionHash: String

    @io.cucumber.java.en.Given("a Java class with an isolated private instance method")
    fun createIsolatedPrivateMethod() {
        root = java.nio.file.Files.createTempDirectory("refactorkit-make-static")
        val relative = java.nio.file.Path.of("src/main/java/pkg/Calculator.java")
        val file = root.resolve(relative)
        java.nio.file.Files.createDirectories(file.parent)
        source = """package pkg;
            |public class Calculator {
            |    private int sum(int left, int right) {
            |        return left + right;
            |    }
            |}
            |""".trimMargin()
        java.nio.file.Files.writeString(file, source)
        session = org.refactorkit.daemon.DaemonSession()
        snapshotHash = session.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
    }

    @io.cucumber.java.en.Given("a Maven Java class with an isolated private instance method and SDK context")
    fun createSdkBoundIsolatedPrivateMethod() {
        root = java.nio.file.Files.createTempDirectory("refactorkit-make-static-selection")
        java.nio.file.Files.writeString(root.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>pkg</groupId><artifactId>make-static-selection</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
            </project>
        """.trimIndent() + "\n")
        val relative = java.nio.file.Path.of("src/main/java/pkg/Calculator.java")
        val file = root.resolve(relative)
        java.nio.file.Files.createDirectories(file.parent)
        source = """package pkg;
            |public class Calculator {
            |    private int sum(int left, int right) {
            |        return left + right;
            |    }
            |}
            |""".trimMargin()
        java.nio.file.Files.writeString(file, source)
        session = org.refactorkit.daemon.DaemonSession()
        snapshotHash = session.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val sdkHome = java.nio.file.Path.of(System.getProperty("java.home"))
        val contextResult = session.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", sdkHome.toString())
        }).jsonObject
        kotlin.test.assertEquals("available", contextResult.getValue("status").jsonPrimitive.content, contextResult.toString())
        javaContext = kotlinx.serialization.json.buildJsonObject {
            put("sdkHome", sdkHome.toString())
            put("expectedContextHash", contextResult.getValue("contextHash").jsonPrimitive.content)
        }
    }

    @io.cucumber.java.en.When("I preview makeStatic for the selected method")
    fun previewMakeStatic() {
        preview = session.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "makeStatic")
            put("languageId", "java")
            put("symbol", "pkg.Calculator#sum(int,int)")
            put("expectedSnapshotHash", snapshotHash)
            put("arguments", kotlinx.serialization.json.buildJsonObject {})
        }).jsonObject
    }

    @io.cucumber.java.en.When("I preview makeStatic with the exact saved JDT method selection")
    fun previewMakeStaticWithSavedSelection() {
        val methodLine = source.lines().indexOfFirst { "private int sum" in it }
        kotlin.test.assertTrue(methodLine >= 0, source)
        val methodText = source.lines()[methodLine]
        val query = session.dispatch("intelligence.query", kotlinx.serialization.json.buildJsonObject {
            put("requestId", "make-static-saved-cursor")
            put("kind", "mutationSelection")
            put("languageId", "java")
            put("path", "src/main/java/pkg/Calculator.java")
            put("position", kotlinx.serialization.json.buildJsonObject {
                put("line", methodLine)
                put("character", methodText.indexOf("sum") + 1)
            })
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", javaContext)
            put("sourceAuthority", kotlinx.serialization.json.buildJsonObject { put("kind", "saved-snapshot") })
        }).jsonObject
        kotlin.test.assertEquals("ready", query.getValue("status").jsonPrimitive.content, query.toString())
        savedMethodSelection = query.getValue("selection").jsonObject
        kotlin.test.assertEquals("method", savedMethodSelection.getValue("symbolKind").jsonPrimitive.content)
        kotlin.test.assertEquals("pkg.Calculator#sum(int,int)", savedMethodSelection.getValue("symbolId").jsonPrimitive.content)
        preview = session.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "makeStatic")
            put("languageId", "java")
            put("symbol", savedMethodSelection.getValue("symbolId").jsonPrimitive.content)
            put("javaSelection", savedMethodSelection)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", javaContext)
        }).jsonObject
    }

    @io.cucumber.java.en.Then("the preview adds one static modifier with clean diagnostics")
    fun checkStaticPreview() {
        kotlin.test.assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
        kotlin.test.assertEquals("makeStatic", preview.getValue("operation").jsonPrimitive.content)
        kotlin.test.assertEquals(snapshotHash, preview.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content)
        kotlin.test.assertEquals("src/main/java/pkg/Calculator.java", preview.getValue("affectedFiles").jsonArray.single().jsonPrimitive.content)
        kotlin.test.assertEquals("modifyFile", preview.getValue("structuredDiff").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
        val renderedDiff = preview.getValue("renderedDiff").jsonPrimitive.content
        kotlin.test.assertTrue(renderedDiff.contains("private static int sum"), renderedDiff)
        val staged = renderedDiff
        kotlin.test.assertEquals(1, Regex("\\bstatic\\b").findAll(staged).count())
        kotlin.test.assertTrue(preview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
        })
        kotlin.test.assertEquals("HIGH", preview.getValue("riskLevel").jsonPrimitive.content)
        kotlin.test.assertTrue(preview.getValue("warnings").jsonArray.any {
            it.jsonPrimitive.content.contains("reflective behavior")
        })
        val capability = org.refactorkit.java.JavaAdapterRegistration.capability("makeStatic")
        kotlin.test.assertEquals(org.refactorkit.core.CapabilityStability.STABLE, capability?.stability)
        kotlin.test.assertEquals(org.refactorkit.core.MutationAuthority.MANAGED_STABLE, capability?.mutationAuthority)
        kotlin.test.assertTrue(org.refactorkit.java.JavaAdapterRegistration.descriptors().any { it.id == "makeStatic" })
    }

    @io.cucumber.java.en.And("applying and rolling back the preview restores the exact workspace")
    fun applyAndRollbackMakeStatic() {
        val applied = session.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", preview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", snapshotHash)
            if (::javaContext.isInitialized) put("javaContext", javaContext)
        }).jsonObject
        kotlin.test.assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        appliedSelectionHash = applied["appliedSelectionHash"]?.jsonPrimitive?.content.orEmpty()
        val sourcePath = root.resolve("src/main/java/pkg/Calculator.java")
        kotlin.test.assertTrue(java.nio.file.Files.readString(sourcePath).contains("private static int sum"))
        val rolledBack = session.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", applied.getValue("transactionId").jsonPrimitive.content)
            if (::javaContext.isInitialized) {
                put("expectedSnapshotHash", applied.getValue("snapshotHash").jsonPrimitive.content)
                put("javaContext", javaContext)
            }
        }).jsonObject
        kotlin.test.assertTrue(rolledBack.getValue("rolledBack").jsonPrimitive.boolean)
        kotlin.test.assertEquals(source, java.nio.file.Files.readString(sourcePath))
    }

    @io.cucumber.java.en.And("the preview and applied transaction retain the selected method identity")
    fun makeStaticSelectionIdentityIsRetained() {
        val selectionHash = preview.getValue("selectionHash").jsonPrimitive.content
        kotlin.test.assertEquals(savedMethodSelection.getValue("selectorHash").jsonPrimitive.content, selectionHash)
        kotlin.test.assertEquals(selectionHash, appliedSelectionHash)
    }

    @io.cucumber.java.en.Given("a Java class has makeStatic eligibility shape {string} in an open LSP document")
    fun createLspMakeStaticFixture(shape: String) {
        kotlin.test.assertTrue(shape in setOf("isolated", "instance-dependent"), shape)
        root = java.nio.file.Files.createTempDirectory("refactorkit-lsp-make-static")
        lspSourcePath = root.resolve("src/main/java/pkg/Calculator.java")
        java.nio.file.Files.createDirectories(lspSourcePath.parent)
        source = if (shape == "isolated") {
            """package pkg;
                |public class Calculator {
                |    private int total(int left, int right) { return left + right; }
                |    int call() { return total(1, 2); }
                |}
                |""".trimMargin()
        } else {
            """package pkg;
                |public class Calculator {
                |    private int base = 10;
                |    private int total(int left, int right) { return this.base + left + right; }
                |    int call() { return total(1, 2); }
                |}
                |""".trimMargin()
        }
        java.nio.file.Files.writeString(lspSourcePath, source)
        lspUri = lspSourcePath.toUri().toString()
        lspSession = org.refactorkit.lsp.LspSession()
        lspCapabilities = lspSession.dispatch("initialize", kotlinx.serialization.json.buildJsonObject {
            put("rootUri", root.toUri().toString())
            put("capabilities", kotlinx.serialization.json.buildJsonObject {
                put("workspace", kotlinx.serialization.json.buildJsonObject {
                    put("workspaceEdit", kotlinx.serialization.json.buildJsonObject { put("documentChanges", true) })
                })
            })
        }).jsonObject.getValue("capabilities").jsonObject
        lspSession.dispatch("textDocument/didOpen", kotlinx.serialization.json.buildJsonObject {
            put("textDocument", kotlinx.serialization.json.buildJsonObject {
                put("uri", lspUri); put("languageId", "java"); put("version", 7); put("text", source)
            })
        })
    }

    @io.cucumber.java.en.When("I request the makeStatic code action for its method")
    fun requestLspMakeStaticAction() {
        val lines = source.lines()
        val line = lines.indexOfFirst { "private int total" in it }
        kotlin.test.assertTrue(line >= 0, source)
        val character = lines[line].indexOf("total")
        lspActions = lspSession.dispatch("textDocument/codeAction", kotlinx.serialization.json.buildJsonObject {
            put("textDocument", kotlinx.serialization.json.buildJsonObject { put("uri", lspUri) })
            put("range", kotlinx.serialization.json.buildJsonObject {
                put("start", kotlinx.serialization.json.buildJsonObject { put("line", line); put("character", character) })
                put("end", kotlinx.serialization.json.buildJsonObject { put("line", line); put("character", character + "total".length) })
            })
        }) as kotlinx.serialization.json.JsonArray
    }

    @io.cucumber.java.en.Then("the LSP makeStatic action is {string} and workspace bytes remain unchanged")
    fun verifyLspMakeStaticAction(expected: String) {
        val action = lspActions.map { it.jsonObject }.single { it.getValue("title").jsonPrimitive.content == "RefactorKit: Make total static" }
        val commands = lspCapabilities.getValue("executeCommandProvider").jsonObject
            .getValue("commands").jsonArray.map { it.jsonPrimitive.content }
        kotlin.test.assertTrue("refactorkit.makeStatic" !in commands, commands.toString())
        if (expected == "available") {
            kotlin.test.assertTrue("disabled" !in action, action.toString())
            kotlin.test.assertTrue("command" !in action, action.toString())
            val edit = action.getValue("edit").jsonObject
            kotlin.test.assertEquals(setOf("documentChanges"), edit.keys)
            val change = edit.getValue("documentChanges").jsonArray.single().jsonObject
            kotlin.test.assertEquals(7, change.getValue("textDocument").jsonObject.getValue("version").jsonPrimitive.int)
            kotlin.test.assertTrue(change.getValue("edits").jsonArray.any {
                it.jsonObject.getValue("newText").jsonPrimitive.content == " static"
            }, change.toString())
            val data = action.getValue("data").jsonObject
            kotlin.test.assertEquals("makeStatic", data.getValue("operation").jsonPrimitive.content)
            kotlin.test.assertEquals("HIGH", data.getValue("riskLevel").jsonPrimitive.content)
            kotlin.test.assertEquals("JDT_BINDING", data.getValue("evidence").jsonPrimitive.content)
            kotlin.test.assertTrue(data.getValue("warnings").jsonArray.any {
                it.jsonPrimitive.content.contains("reflective behavior")
            }, data.toString())
            kotlin.test.assertEquals("client-managed", data.getValue("editOwnership").jsonPrimitive.content)
            kotlin.test.assertEquals(false, data.getValue("rollbackAvailable").jsonPrimitive.boolean)
        } else {
            kotlin.test.assertEquals("disabled", expected)
            val disabled = action.getValue("disabled").jsonObject
            kotlin.test.assertTrue(disabled.getValue("reason").jsonPrimitive.content.isNotBlank())
            kotlin.test.assertTrue("command" !in action, action.toString())
            kotlin.test.assertTrue("edit" !in action, action.toString())
        }
        kotlin.test.assertEquals(source, java.nio.file.Files.readString(lspSourcePath))
    }

    @io.cucumber.java.After
    fun cleanMakeStaticFixture() {
        if (::session.isInitialized) session.close()
        if (::lspSession.isInitialized) lspSession.dispatch("shutdown", null)
        if (!::root.isInitialized) return
        java.nio.file.Files.walk(root).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists)
        }
    }
}

class JavaCapabilityTruthSteps {
    private data class ParameterTypeChangeSpec(
        val symbol: String,
        val parameterName: String,
        val newType: String,
        val includeHierarchy: Boolean = false,
        val acceptExternalConsumerRisk: Boolean = false,
        val replacements: Map<String, Pair<String, String>>,
    )

    private data class ParameterTypeChangeEvidence(
        val shape: String,
        val originalImage: Map<String, String>,
        val expectedImage: Map<String, String>,
        val expectedDeclarations: List<String>,
        val daemonPreview: kotlinx.serialization.json.JsonObject,
        val daemonApply: kotlinx.serialization.json.JsonObject,
        val daemonRollback: kotlinx.serialization.json.JsonObject,
        val daemonAppliedImage: Map<String, String>,
        val daemonDiagnostics: kotlinx.serialization.json.JsonArray,
        val contextHash: String,
        val selectionHash: String?,
        val mcpPreviewText: String,
        val mcpApplyText: String,
        val mcpRollbackText: String,
        val mcpAppliedImage: Map<String, String>,
        val mcpDiagnosticsText: String,
        val transactionOperations: List<String>,
        val transactionStates: List<String>,
    )

    private lateinit var owned: java.nio.file.Path
    private lateinit var root: java.nio.file.Path
    private lateinit var sdk: java.nio.file.Path
    private lateinit var servicePath: java.nio.file.Path
    private lateinit var serviceOriginal: String
    private lateinit var calculatorPath: java.nio.file.Path
    private lateinit var daemonApply: kotlinx.serialization.json.JsonObject
    private lateinit var daemonRollback: kotlinx.serialization.json.JsonObject
    private lateinit var mcpAppliedText: String
    private lateinit var mcpRollbackText: String
    private lateinit var context: kotlinx.serialization.json.JsonObject
    private lateinit var removeParameterOriginalImage: Map<String, String>
    private lateinit var removeParameterExpectedImage: Map<String, String>
    private lateinit var removeParameterDaemonPreview: kotlinx.serialization.json.JsonObject
    private lateinit var removeParameterDaemonApply: kotlinx.serialization.json.JsonObject
    private lateinit var removeParameterDaemonRollback: kotlinx.serialization.json.JsonObject
    private lateinit var removeParameterDaemonAppliedImage: Map<String, String>
    private lateinit var removeParameterMcpPreviewText: String
    private lateinit var removeParameterMcpApplyText: String
    private lateinit var removeParameterMcpRollbackText: String
    private lateinit var removeParameterMcpAppliedImage: Map<String, String>
    private lateinit var removeParameterTransactionOperations: List<String>
    private lateinit var removeParameterTransactionStates: List<String>
    private lateinit var typeChangeEvidence: ParameterTypeChangeEvidence
    private var session: org.refactorkit.daemon.DaemonSession? = null
    private lateinit var calculatorOriginal: String
    private lateinit var calculatorExpected: String
    private lateinit var extractionDaemonPreview: kotlinx.serialization.json.JsonObject
    private lateinit var extractionDaemonApply: kotlinx.serialization.json.JsonObject
    private lateinit var extractionDaemonRollback: kotlinx.serialization.json.JsonObject
    private lateinit var extractionDaemonPartial: kotlinx.serialization.json.JsonObject
    private lateinit var extractionMcpPreviewText: String
    private lateinit var extractionMcpApplyText: String
    private lateinit var extractionMcpRollbackText: String
    private lateinit var extractionMcpPartialText: String
    private lateinit var extractionRefusalCodes: Map<String, String>
    private lateinit var addParameterExpected: String
    private lateinit var addParameterDaemonPreview: kotlinx.serialization.json.JsonObject
    private lateinit var addParameterDaemonApply: kotlinx.serialization.json.JsonObject
    private lateinit var addParameterDaemonRollback: kotlinx.serialization.json.JsonObject
    private lateinit var addParameterMcpPreviewText: String
    private lateinit var addParameterMcpApplyText: String
    private lateinit var addParameterMcpRollbackText: String
    private lateinit var extractConstantOriginal: String
    private lateinit var extractConstantExpected: String
    private lateinit var extractConstantDaemonPreview: kotlinx.serialization.json.JsonObject
    private lateinit var extractConstantDaemonApply: kotlinx.serialization.json.JsonObject
    private lateinit var extractConstantDaemonRollback: kotlinx.serialization.json.JsonObject
    private lateinit var extractConstantDaemonRefusalCode: String
    private lateinit var extractConstantMcpPreviewText: String
    private lateinit var extractConstantMcpApplyText: String
    private lateinit var extractConstantMcpRollbackText: String
    private lateinit var extractConstantMcpRefusalText: String
    private lateinit var inlineConstantOriginal: String
    private lateinit var inlineConstantExpected: String
    private lateinit var inlineConstantDaemonPreview: kotlinx.serialization.json.JsonObject
    private lateinit var inlineConstantDaemonApply: kotlinx.serialization.json.JsonObject
    private lateinit var inlineConstantDaemonRollback: kotlinx.serialization.json.JsonObject
    private lateinit var inlineConstantDaemonRefusalCodes: Map<String, String>
    private lateinit var inlineConstantMcpPreviewText: String
    private lateinit var inlineConstantMcpApplyText: String
    private lateinit var inlineConstantMcpRollbackText: String
    private lateinit var inlineConstantMcpRefusalText: String
    private lateinit var extractParameterOriginal: String
    private lateinit var extractParameterExpected: String
    private lateinit var extractParameterDaemonPreview: kotlinx.serialization.json.JsonObject
    private lateinit var extractParameterDaemonApply: kotlinx.serialization.json.JsonObject
    private lateinit var extractParameterDaemonRollback: kotlinx.serialization.json.JsonObject
    private lateinit var extractParameterDaemonRefusalCode: String
    private lateinit var extractParameterMcpJavaContext: kotlinx.serialization.json.JsonObject
    private lateinit var extractParameterMcpPreviewText: String
    private lateinit var extractParameterMcpApplyText: String
    private lateinit var extractParameterMcpRollbackText: String
    private lateinit var extractParameterMcpRefusalText: String
    private lateinit var extractFieldOriginal: String
    private lateinit var extractFieldDaemonPreview: kotlinx.serialization.json.JsonObject
    private lateinit var extractFieldDaemonApply: kotlinx.serialization.json.JsonObject
    private lateinit var extractFieldDaemonRollback: kotlinx.serialization.json.JsonObject
    private lateinit var extractFieldMcpJavaContext: kotlinx.serialization.json.JsonObject
    private lateinit var extractFieldMcpPreviewText: String
    private lateinit var extractFieldMcpApplyText: String
    private lateinit var extractFieldMcpRollbackText: String
    private lateinit var extractFieldMcpRefusalText: String
    private lateinit var extractFieldDaemonRefusalCode: String
    private lateinit var extractFieldShape: String
    private lateinit var encapsulateFieldOriginalService: String
    private lateinit var encapsulateFieldOriginalClient: String
    private lateinit var encapsulateFieldExpectedService: String
    private lateinit var encapsulateFieldExpectedClient: String
    private lateinit var encapsulateFieldDaemonPreview: kotlinx.serialization.json.JsonObject
    private lateinit var encapsulateFieldDaemonApply: kotlinx.serialization.json.JsonObject
    private lateinit var encapsulateFieldDaemonRollback: kotlinx.serialization.json.JsonObject
    private lateinit var encapsulateFieldMcpContext: kotlinx.serialization.json.JsonObject
    private lateinit var encapsulateFieldMcpPreviewText: String
    private lateinit var encapsulateFieldMcpApplyText: String
    private lateinit var encapsulateFieldMcpRollbackText: String
    private val encapsulateFieldRefusals = linkedMapOf<String, String>()
    private var encapsulateFieldAdditionalTypes = emptySet<String>()
    private val encapsulateFieldAdditionalRefusals = linkedMapOf<String, String>()
    private lateinit var extractInterfaceSnapshotHash: String
    private lateinit var extractInterfaceSourceBefore: String
    private lateinit var extractInterfaceSelection: kotlinx.serialization.json.JsonObject
    private lateinit var extractInterfacePreview: kotlinx.serialization.json.JsonObject
    private lateinit var extractInterfaceApply: kotlinx.serialization.json.JsonObject
    private lateinit var extractSuperclassSnapshotHash: String
    private lateinit var extractSuperclassSourceBefore: String
    private lateinit var extractSuperclassSelection: kotlinx.serialization.json.JsonObject
    private lateinit var extractSuperclassPreview: kotlinx.serialization.json.JsonObject
    private lateinit var changeVisibilitySnapshotHash: String
    private lateinit var changeVisibilitySourceBefore: String
    private lateinit var changeVisibilityMethodSymbol: String
    private lateinit var changeVisibilitySelection: JsonObject
    private lateinit var changeVisibilityPreview: kotlinx.serialization.json.JsonObject
    private lateinit var batchCleanupSnapshotHash: String
    private lateinit var batchCleanupBaseline: Map<java.nio.file.Path, String>
    private var batchCleanupPreview: kotlinx.serialization.json.JsonObject? = null
    private var batchCleanupRefusalCode: String? = null
    private var batchCleanupAccepted = false

    @io.cucumber.java.en.Given("an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context")
    fun openFixture() {
        owned = java.nio.file.Files.createTempDirectory("rk-java-capability-truth-")
        root = owned.resolve("app")
        sdk = java.nio.file.Path.of(System.getProperty("java.home"))
        write("pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version><configuration><release>8</release><proc>none</proc></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        write("src/main/java/com/acme/Service.java", """
            package com.acme;
            import java.util.List;
            public class Service {
                public String execute(String value) { return String.valueOf(helper(value)); }
                private Object helper(String value) { return value; }
                private int box(int second) { return second; }
                private String quarantine(String label, boolean unused) { return label; }
                private String runQuarantine() { return quarantine("legacy", true); }
            }
        """.trimIndent() + "\n")
        write("src/main/java/com/acme/Calculator.java", """
            package com.acme;
            public class Calculator {
                public int calculate(int input) {
                    return input * 2 + 1;
                }
            }
        """.trimIndent() + "\n")
        write("src/main/java/com/acme/Lookup.java", """
            package com.acme;
            public interface Lookup {
                CharSequence find(String key);
            }
        """.trimIndent() + "\n")
        write("src/main/java/com/acme/DefaultLookup.java", """
            package com.acme;
            public final class DefaultLookup implements Lookup {
                @Override public CharSequence find(String value) { return value; }
            }
        """.trimIndent() + "\n")
        write("src/main/java/com/acme/LookupClient.java", """
            package com.acme;
            public final class LookupClient {
                public CharSequence find(Lookup lookup) { return lookup.find("key"); }
            }
        """.trimIndent() + "\n")
        write("src/main/java/com/acme/Token.java", """
            package com.acme;
            public final class Token {
                private final CharSequence value;
                private Token(String value) { this.value = value; }
                public static Token create() { return new Token("token"); }
                public CharSequence value() { return value; }
            }
        """.trimIndent() + "\n")
        servicePath = root.resolve("src/main/java/com/acme/Service.java")
        serviceOriginal = java.nio.file.Files.readString(servicePath)
        calculatorPath = root.resolve("src/main/java/com/acme/Calculator.java")
        extractInterfaceSourceBefore = java.nio.file.Files.readString(calculatorPath)
        session = org.refactorkit.daemon.DaemonSession()
        val opened = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        extractInterfaceSnapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", opened.getValue("snapshotHash").jsonPrimitive.content)
            put("sdkHome", sdk.toString())
        }).jsonObject
        kotlin.test.assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
    }

    @io.cucumber.java.en.When("I preview extractInterface for Calculator.calculate using its exact saved JDT type selection")
    fun previewExtractInterfaceForSavedType() {
        val classLine = extractInterfaceSourceBefore.lines().indexOfFirst { "public class Calculator" in it }
        kotlin.test.assertTrue(classLine >= 0, extractInterfaceSourceBefore)
        val lineText = extractInterfaceSourceBefore.lines()[classLine]
        val query = session!!.dispatch("intelligence.query", kotlinx.serialization.json.buildJsonObject {
            put("requestId", "extract-interface-calculator-selection")
            put("kind", "mutationSelection")
            put("languageId", "java")
            put("path", "src/main/java/com/acme/Calculator.java")
            put("position", kotlinx.serialization.json.buildJsonObject {
                put("line", classLine)
                put("character", lineText.indexOf("Calculator") + 1)
            })
            put("expectedSnapshotHash", extractInterfaceSnapshotHash)
            put("javaContext", javaContext())
            put("sourceAuthority", kotlinx.serialization.json.buildJsonObject { put("kind", "saved-snapshot") })
        }).jsonObject
        kotlin.test.assertEquals("ready", query.getValue("status").jsonPrimitive.content, query.toString())
        extractInterfaceSelection = query.getValue("selection").jsonObject
        kotlin.test.assertEquals("class", extractInterfaceSelection.getValue("symbolKind").jsonPrimitive.content)
        kotlin.test.assertEquals("com.acme.Calculator", extractInterfaceSelection.getValue("symbolId").jsonPrimitive.content)
        extractInterfacePreview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "extractInterface")
            put("languageId", "java")
            put("symbol", extractInterfaceSelection.getValue("symbolId").jsonPrimitive.content)
            put("javaSelection", extractInterfaceSelection)
            put("expectedSnapshotHash", extractInterfaceSnapshotHash)
            put("javaContext", javaContext())
            put("arguments", kotlinx.serialization.json.buildJsonObject {
                put("file", "src/main/java/com/acme/Calculator.java")
                put("interfaceName", "CalculatorOperations")
                put("methodName", "calculate")
            })
        }).jsonObject
    }

    @io.cucumber.java.en.Then("extractInterface is advertised as stable and the preview creates CalculatorOperations and makes Calculator implement the interface with clean diagnostics")
    fun verifyExtractInterfaceCapabilityAndPreview() {
        kotlin.test.assertEquals("PREVIEW", extractInterfacePreview.getValue("status").jsonPrimitive.content, extractInterfacePreview.toString())
        kotlin.test.assertEquals("extractInterface", extractInterfacePreview.getValue("operation").jsonPrimitive.content)
        kotlin.test.assertEquals(extractInterfaceSnapshotHash, extractInterfacePreview.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content)
        kotlin.test.assertEquals(
            setOf("src/main/java/com/acme/Calculator.java", "src/main/java/com/acme/CalculatorOperations.java"),
            extractInterfacePreview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        val diff = extractInterfacePreview.getValue("renderedDiff").jsonPrimitive.content
        kotlin.test.assertTrue(diff.contains("implements CalculatorOperations"), diff)
        kotlin.test.assertTrue(diff.contains("public interface CalculatorOperations"), diff)
        kotlin.test.assertTrue(diff.contains("int calculate(int input);"), diff)
        kotlin.test.assertTrue(extractInterfacePreview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
        })
        kotlin.test.assertEquals(
            extractInterfaceSelection.getValue("selectorHash").jsonPrimitive.content,
            extractInterfacePreview.getValue("selectionHash").jsonPrimitive.content,
        )
        val capabilities = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
        val java = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "extractInterface" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("extractInterface"))
    }

    @io.cucumber.java.en.And("a reference-typed method selection is refused before the workspace changes")
    fun refuseReferenceTypedExtractInterfaceMethod() {
        val classLine = serviceOriginal.lines().indexOfFirst { "public class Service" in it }
        kotlin.test.assertTrue(classLine >= 0, serviceOriginal)
        val lineText = serviceOriginal.lines()[classLine]
        val query = session!!.dispatch("intelligence.query", kotlinx.serialization.json.buildJsonObject {
            put("requestId", "extract-interface-service-selection")
            put("kind", "mutationSelection")
            put("languageId", "java")
            put("path", "src/main/java/com/acme/Service.java")
            put("position", kotlinx.serialization.json.buildJsonObject {
                put("line", classLine)
                put("character", lineText.indexOf("Service") + 1)
            })
            put("expectedSnapshotHash", extractInterfaceSnapshotHash)
            put("javaContext", javaContext())
            put("sourceAuthority", kotlinx.serialization.json.buildJsonObject { put("kind", "saved-snapshot") })
        }).jsonObject
        val selection = query.getValue("selection").jsonObject
        val refused = kotlin.test.assertFailsWith<org.refactorkit.core.JsonRpcException> {
            session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "extractInterface")
                put("languageId", "java")
                put("symbol", selection.getValue("symbolId").jsonPrimitive.content)
                put("javaSelection", selection)
                put("expectedSnapshotHash", extractInterfaceSnapshotHash)
                put("javaContext", javaContext())
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("file", "src/main/java/com/acme/Service.java")
                    put("interfaceName", "ServiceOperations")
                    put("methodName", "execute")
                })
            })
        }
        kotlin.test.assertEquals("java.extractInterface.signatureUnsupported",
            refused.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
        kotlin.test.assertEquals(serviceOriginal, java.nio.file.Files.readString(servicePath))
        kotlin.test.assertFalse(java.nio.file.Files.exists(root.resolve("src/main/java/com/acme/ServiceOperations.java")))
        kotlin.test.assertFalse(java.nio.file.Files.exists(root.resolve("src/main/java/com/acme/CalculatorOperations.java")))
        kotlin.test.assertTrue(org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().isEmpty())
    }

    @io.cucumber.java.en.And("applying and rolling back extractInterface restores the exact workspace and preserves selected type identity")
    fun applyAndRollbackExtractInterface() {
        extractInterfaceApply = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", extractInterfacePreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", extractInterfaceSnapshotHash)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("applied", extractInterfaceApply.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(
            extractInterfaceSelection.getValue("selectorHash").jsonPrimitive.content,
            extractInterfaceApply.getValue("appliedSelectionHash").jsonPrimitive.content,
        )
        kotlin.test.assertTrue(java.nio.file.Files.readString(calculatorPath).contains("implements CalculatorOperations"))
        val interfacePath = root.resolve("src/main/java/com/acme/CalculatorOperations.java")
        kotlin.test.assertTrue(java.nio.file.Files.readString(interfacePath).contains("int calculate(int input);"))
        val rollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", extractInterfaceApply.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", extractInterfaceApply.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertTrue(rollback.getValue("rolledBack").jsonPrimitive.boolean)
        kotlin.test.assertEquals(extractInterfaceSourceBefore, java.nio.file.Files.readString(calculatorPath))
        kotlin.test.assertFalse(java.nio.file.Files.exists(interfacePath))
        kotlin.test.assertEquals(serviceOriginal, java.nio.file.Files.readString(servicePath))

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.boolean, scan.toString())
            val contextResult = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "java_project_context")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("sdkHome", sdk.toString()) })
            }).jsonObject
            val contextText = contextResult.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            val mcpContext = kotlinx.serialization.json.Json.parseToJsonElement(contextText)
                .jsonObject.getValue("javaContext").jsonObject
            val mcpPreview = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "preview_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("operation", "extractInterface")
                    put("languageId", "java")
                    put("symbol", "com.acme.Calculator")
                    put("javaContext", mcpContext)
                    put("arguments", kotlinx.serialization.json.buildJsonObject {
                        put("file", "src/main/java/com/acme/Calculator.java")
                        put("interfaceName", "CalculatorOperations")
                        put("methodName", "calculate")
                    })
                })
            }).jsonObject
            kotlin.test.assertFalse(mcpPreview.getValue("isError").jsonPrimitive.boolean, mcpPreview.toString())
            val mcpPreviewText = mcpPreview.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(mcpPreviewText.contains("Operation: extractInterface"), mcpPreviewText)
            kotlin.test.assertTrue(mcpPreviewText.contains("Status   : PREVIEW"), mcpPreviewText)
            val mcpPlanId = mcpPreviewText.lineSequence().singleOrNull { it.trimStart().startsWith("Plan ID") }
                ?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)
                ?: error("MCP extractInterface preview has no plan ID: $mcpPreviewText")
            val mcpApply = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("planId", mcpPlanId)
                    put("javaContext", mcpContext)
                })
            }).jsonObject
            kotlin.test.assertFalse(mcpApply.getValue("isError").jsonPrimitive.boolean, mcpApply.toString())
            val mcpApplyText = mcpApply.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(mcpApplyText.startsWith("Applied successfully."), mcpApplyText)
            val mcpTransactionId = Regex("Transaction ID: ([^\\s]+)").find(mcpApplyText)?.groupValues?.get(1)
                ?: error("MCP extractInterface Apply has no transaction ID: $mcpApplyText")
            kotlin.test.assertTrue(java.nio.file.Files.readString(interfacePath).contains("int calculate(int input);"))
            val mcpRollback = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("transactionId", mcpTransactionId)
                    put("javaContext", mcpContext)
                })
            }).jsonObject
            kotlin.test.assertFalse(mcpRollback.getValue("isError").jsonPrimitive.boolean, mcpRollback.toString())
            val mcpRollbackText = mcpRollback.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(mcpRollbackText.startsWith("Rolled back transaction"), mcpRollbackText)
            kotlin.test.assertEquals(extractInterfaceSourceBefore, java.nio.file.Files.readString(calculatorPath))
            kotlin.test.assertFalse(java.nio.file.Files.exists(interfacePath))
        } finally {
            mcp.close()
        }
    }

    @io.cucumber.java.en.When("I preview extractSuperclass for Calculator.calculate using its exact saved JDT type selection")
    fun previewExtractSuperclassForSavedType() {
        extractSuperclassSourceBefore = java.nio.file.Files.readString(calculatorPath)
        extractSuperclassSnapshotHash = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val classLine = extractSuperclassSourceBefore.lines().indexOfFirst { "public class Calculator" in it }
        kotlin.test.assertTrue(classLine >= 0, extractSuperclassSourceBefore)
        val lineText = extractSuperclassSourceBefore.lines()[classLine]
        val selected = session!!.dispatch("intelligence.query", kotlinx.serialization.json.buildJsonObject {
            put("requestId", "extract-superclass-calculator-selection")
            put("kind", "mutationSelection")
            put("languageId", "java")
            put("path", "src/main/java/com/acme/Calculator.java")
            put("position", kotlinx.serialization.json.buildJsonObject {
                put("line", classLine)
                put("character", lineText.indexOf("Calculator") + 1)
            })
            put("expectedSnapshotHash", extractSuperclassSnapshotHash)
            put("javaContext", javaContext())
            put("sourceAuthority", kotlinx.serialization.json.buildJsonObject { put("kind", "saved-snapshot") })
        }).jsonObject
        kotlin.test.assertEquals("ready", selected.getValue("status").jsonPrimitive.content, selected.toString())
        extractSuperclassSelection = selected.getValue("selection").jsonObject
        kotlin.test.assertEquals("class", extractSuperclassSelection.getValue("symbolKind").jsonPrimitive.content)
        extractSuperclassPreview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "extractSuperclass")
            put("languageId", "java")
            put("symbol", extractSuperclassSelection.getValue("symbolId").jsonPrimitive.content)
            put("javaSelection", extractSuperclassSelection)
            put("expectedSnapshotHash", extractSuperclassSnapshotHash)
            put("javaContext", javaContext())
            put("arguments", kotlinx.serialization.json.buildJsonObject {
                put("file", "src/main/java/com/acme/Calculator.java")
                put("superclassName", "CalculatorBase")
                put("methodName", "calculate")
            })
        }).jsonObject
    }

    @io.cucumber.java.en.Then("extractSuperclass is advertised as stable and the preview creates CalculatorBase and makes Calculator extend it with clean diagnostics")
    fun verifyExtractSuperclassCapabilityAndPreview() {
        kotlin.test.assertEquals("PREVIEW", extractSuperclassPreview.getValue("status").jsonPrimitive.content, extractSuperclassPreview.toString())
        kotlin.test.assertEquals("extractSuperclass", extractSuperclassPreview.getValue("operation").jsonPrimitive.content)
        kotlin.test.assertEquals(extractSuperclassSnapshotHash, extractSuperclassPreview.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content)
        kotlin.test.assertEquals(
            setOf("src/main/java/com/acme/Calculator.java", "src/main/java/com/acme/CalculatorBase.java"),
            extractSuperclassPreview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        val diff = extractSuperclassPreview.getValue("renderedDiff").jsonPrimitive.content
        kotlin.test.assertTrue(diff.contains("extends CalculatorBase"), diff)
        kotlin.test.assertTrue(diff.contains("public class CalculatorBase"), diff)
        kotlin.test.assertTrue(diff.contains("public int calculate(int input)"), diff)
        kotlin.test.assertTrue(diff.contains("return input * 2 + 1;"), diff)
        kotlin.test.assertTrue(extractSuperclassPreview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
        })
        kotlin.test.assertEquals(
            extractSuperclassSelection.getValue("selectorHash").jsonPrimitive.content,
            extractSuperclassPreview.getValue("selectionHash").jsonPrimitive.content,
        )
        val capabilities = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
        val java = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "extractSuperclass" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("extractSuperclass"))
    }

    @io.cucumber.java.en.And("a reference-typed extractSuperclass method selection is refused before the workspace changes")
    fun refuseReferenceTypedExtractSuperclassMethod() {
        val before = java.nio.file.Files.readString(servicePath)
        val classLine = before.lines().indexOfFirst { "public class Service" in it }
        kotlin.test.assertTrue(classLine >= 0, before)
        val lineText = before.lines()[classLine]
        val selected = session!!.dispatch("intelligence.query", kotlinx.serialization.json.buildJsonObject {
            put("requestId", "extract-superclass-service-selection")
            put("kind", "mutationSelection")
            put("languageId", "java")
            put("path", "src/main/java/com/acme/Service.java")
            put("position", kotlinx.serialization.json.buildJsonObject {
                put("line", classLine)
                put("character", lineText.indexOf("Service") + 1)
            })
            put("expectedSnapshotHash", extractSuperclassSnapshotHash)
            put("javaContext", javaContext())
            put("sourceAuthority", kotlinx.serialization.json.buildJsonObject { put("kind", "saved-snapshot") })
        }).jsonObject.getValue("selection").jsonObject
        val refused = kotlin.test.assertFailsWith<org.refactorkit.core.JsonRpcException> {
            session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "extractSuperclass")
                put("languageId", "java")
                put("symbol", selected.getValue("symbolId").jsonPrimitive.content)
                put("javaSelection", selected)
                put("expectedSnapshotHash", extractSuperclassSnapshotHash)
                put("javaContext", javaContext())
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("file", "src/main/java/com/acme/Service.java")
                    put("superclassName", "ServiceBase")
                    put("methodName", "execute")
                })
            })
        }
        kotlin.test.assertEquals("java.extractSuperclass.signatureUnsupported",
            refused.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
        kotlin.test.assertEquals(before, java.nio.file.Files.readString(servicePath))
        kotlin.test.assertEquals(extractSuperclassSourceBefore, java.nio.file.Files.readString(calculatorPath))
        kotlin.test.assertFalse(java.nio.file.Files.exists(root.resolve("src/main/java/com/acme/ServiceBase.java")))
        kotlin.test.assertTrue(org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().isEmpty())
    }

    @io.cucumber.java.en.And("applying and rolling back extractSuperclass restores the exact workspace and preserves selected type identity")
    fun applyAndRollbackExtractSuperclass() {
        val applied = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", extractSuperclassPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", extractSuperclassSnapshotHash)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(extractSuperclassSelection.getValue("selectorHash").jsonPrimitive.content,
            applied.getValue("appliedSelectionHash").jsonPrimitive.content)
        val basePath = root.resolve("src/main/java/com/acme/CalculatorBase.java")
        val calculator = java.nio.file.Files.readString(calculatorPath)
        val base = java.nio.file.Files.readString(basePath)
        kotlin.test.assertTrue(calculator.contains("extends CalculatorBase"), calculator)
        kotlin.test.assertFalse(calculator.contains("int calculate("), calculator)
        kotlin.test.assertTrue(base.contains("public int calculate(int input)"), base)
        val rollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", applied.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", applied.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertTrue(rollback.getValue("rolledBack").jsonPrimitive.boolean)
        kotlin.test.assertEquals(extractSuperclassSourceBefore, java.nio.file.Files.readString(calculatorPath))
        kotlin.test.assertFalse(java.nio.file.Files.exists(basePath))

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.boolean, scan.toString())
            val contextResult = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "java_project_context")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("sdkHome", sdk.toString()) })
            }).jsonObject
            val contextText = contextResult.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            val mcpContext = kotlinx.serialization.json.Json.parseToJsonElement(contextText)
                .jsonObject.getValue("javaContext").jsonObject
            val available = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "available_refactorings")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("symbol", "com.acme.Calculator")
                    put("languageId", "java")
                })
            }).jsonObject
            kotlin.test.assertFalse(available.getValue("isError").jsonPrimitive.boolean, available.toString())
            val availableText = available.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(availableText.contains("- extractSuperclass:"), availableText)
            val preview = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "preview_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("operation", "extractSuperclass")
                    put("languageId", "java")
                    put("symbol", "com.acme.Calculator")
                    put("javaContext", mcpContext)
                    put("arguments", kotlinx.serialization.json.buildJsonObject {
                        put("file", "src/main/java/com/acme/Calculator.java")
                        put("superclassName", "CalculatorBase")
                        put("methodName", "calculate")
                    })
                })
            }).jsonObject
            kotlin.test.assertFalse(preview.getValue("isError").jsonPrimitive.boolean, preview.toString())
            val previewText = preview.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(previewText.contains("Operation: extractSuperclass"), previewText)
            val planId = previewText.lineSequence().singleOrNull { it.trimStart().startsWith("Plan ID") }
                ?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)
                ?: error("MCP extractSuperclass preview has no plan ID: $previewText")
            val mcpApply = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("planId", planId)
                    put("javaContext", mcpContext)
                })
            }).jsonObject
            kotlin.test.assertFalse(mcpApply.getValue("isError").jsonPrimitive.boolean, mcpApply.toString())
            val applyText = mcpApply.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            val transactionId = Regex("Transaction ID: ([^\\s]+)").find(applyText)?.groupValues?.get(1)
                ?: error("MCP extractSuperclass Apply has no transaction ID: $applyText")
            kotlin.test.assertTrue(java.nio.file.Files.readString(basePath).contains("public int calculate(int input)"))
            val mcpRollback = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("transactionId", transactionId)
                    put("javaContext", mcpContext)
                })
            }).jsonObject
            kotlin.test.assertFalse(mcpRollback.getValue("isError").jsonPrimitive.boolean, mcpRollback.toString())
            kotlin.test.assertEquals(extractSuperclassSourceBefore, java.nio.file.Files.readString(calculatorPath))
            kotlin.test.assertFalse(java.nio.file.Files.exists(basePath))
        } finally {
            mcp.close()
        }
    }

    @io.cucumber.java.en.When("I preview changeVisibility from private to public for the final helper method")
    fun previewChangeVisibilityForFinalHelper() {
        val source = java.nio.file.Files.readString(servicePath)
        changeVisibilitySourceBefore = source
            .replace("private Object helper", "private final Object helper")
            .replace(
                "private int box(int second)",
                "String unsafe(String value) { return value; }\n                private int box(int second)",
            )
        kotlin.test.assertNotEquals(source, changeVisibilitySourceBefore)
        java.nio.file.Files.writeString(servicePath, changeVisibilitySourceBefore)
        changeVisibilitySnapshotHash = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        changeVisibilitySelection = selectedBoundMethodSelection("helper", changeVisibilitySnapshotHash)
        changeVisibilityMethodSymbol = changeVisibilitySelection.getValue("symbolId").jsonPrimitive.content
        changeVisibilityPreview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "changeVisibility")
            put("languageId", "java")
            put("symbol", changeVisibilityMethodSymbol)
            put("expectedSnapshotHash", changeVisibilitySnapshotHash)
            put("javaContext", javaContext())
            put("javaSelection", changeVisibilitySelection)
            put("arguments", kotlinx.serialization.json.buildJsonObject { put("visibility", "public") })
        }).jsonObject
    }

    @io.cucumber.java.en.Then("changeVisibility is advertised as stable and only the selected Java visibility is widened")
    fun verifyChangeVisibilityPreview() {
        kotlin.test.assertEquals("PREVIEW", changeVisibilityPreview.getValue("status").jsonPrimitive.content, changeVisibilityPreview.toString())
        kotlin.test.assertEquals("changeVisibility", changeVisibilityPreview.getValue("operation").jsonPrimitive.content)
        kotlin.test.assertEquals(changeVisibilitySnapshotHash,
            changeVisibilityPreview.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content)
        kotlin.test.assertEquals(setOf("src/main/java/com/acme/Service.java"),
            changeVisibilityPreview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val diff = changeVisibilityPreview.getValue("renderedDiff").jsonPrimitive.content
        kotlin.test.assertTrue(diff.contains("-    private final Object helper(String value)"), diff)
        kotlin.test.assertTrue(diff.contains("+    public final Object helper(String value)"), diff)
        kotlin.test.assertTrue(changeVisibilityPreview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
        })
        kotlin.test.assertTrue(changeVisibilityPreview.getValue("warnings").jsonArray.any {
            it.jsonPrimitive.content.contains("reflective API")
        }, changeVisibilityPreview.toString())
        val caps = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
        val java = caps.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "changeVisibility" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("changeVisibility"))
    }

    @io.cucumber.java.en.And("a non-final method with incomplete override safety is refused without workspace mutation")
    fun refuseChangeVisibilityWithoutOverrideSafety() {
        val source = java.nio.file.Files.readString(servicePath)
        val selection = selectedBoundMethodSelection("unsafe", changeVisibilitySnapshotHash)
        val symbol = selection.getValue("symbolId").jsonPrimitive.content
        val refused = kotlin.test.assertFailsWith<org.refactorkit.core.JsonRpcException> {
            session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "changeVisibility")
                put("languageId", "java")
                put("symbol", symbol)
                put("expectedSnapshotHash", changeVisibilitySnapshotHash)
                put("javaContext", javaContext())
                put("javaSelection", selection)
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("visibility", "public") })
            })
        }
        kotlin.test.assertEquals("java.changeVisibility.overrideClosureUnsupported",
            refused.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
        kotlin.test.assertEquals(source, java.nio.file.Files.readString(servicePath))
        kotlin.test.assertEquals(changeVisibilitySourceBefore, source)
        kotlin.test.assertTrue(org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().isEmpty())
    }

    @io.cucumber.java.en.And("applying and rolling back changeVisibility restores exact source and method binding")
    fun applyAndRollbackChangeVisibility() {
        val applied = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", changeVisibilityPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", changeVisibilitySnapshotHash)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        kotlin.test.assertTrue(java.nio.file.Files.readString(servicePath).contains("public final Object helper(String value)"))
        val rollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", applied.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", applied.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertTrue(rollback.getValue("rolledBack").jsonPrimitive.boolean)
        kotlin.test.assertEquals(changeVisibilitySourceBefore, java.nio.file.Files.readString(servicePath))
        val restoredHash = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        kotlin.test.assertEquals(changeVisibilityMethodSymbol, selectedBoundMethodSymbol("helper", restoredHash))

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.boolean, scan.toString())
            val contextResult = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "java_project_context")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("sdkHome", sdk.toString()) })
            }).jsonObject
            val contextText = contextResult.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            val mcpContext = kotlinx.serialization.json.Json.parseToJsonElement(contextText).jsonObject.getValue("javaContext").jsonObject
            val available = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "available_refactorings")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("symbol", "com.acme.Service")
                    put("languageId", "java")
                })
            }).jsonObject
            val availableText = available.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(availableText.contains("- changeVisibility:"), availableText)
            val previewResult = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "preview_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("operation", "changeVisibility")
                    put("languageId", "java")
                    put("symbol", changeVisibilityMethodSymbol)
                    put("javaContext", mcpContext)
                    put("arguments", kotlinx.serialization.json.buildJsonObject { put("visibility", "public") })
                })
            }).jsonObject
            kotlin.test.assertFalse(previewResult.getValue("isError").jsonPrimitive.boolean, previewResult.toString())
            val previewText = previewResult.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(previewText.contains("Operation: changeVisibility"), previewText)
            val planId = previewText.lineSequence().singleOrNull { it.trimStart().startsWith("Plan ID") }
                ?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)
                ?: error("MCP changeVisibility preview has no plan ID: $previewText")
            val mcpApply = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("planId", planId); put("javaContext", mcpContext) })
            }).jsonObject
            kotlin.test.assertFalse(mcpApply.getValue("isError").jsonPrimitive.boolean, mcpApply.toString())
            val applyText = mcpApply.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            val transactionId = applyText.lineSequence().singleOrNull { it.trimStart().startsWith("Transaction ID") }
                ?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)
                ?: error("MCP changeVisibility Apply has no transaction ID: $applyText")
            kotlin.test.assertTrue(java.nio.file.Files.readString(servicePath).contains("public final Object helper(String value)"))
            val mcpRollback = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("transactionId", transactionId)
                    put("javaContext", mcpContext)
                })
            }).jsonObject
            kotlin.test.assertFalse(mcpRollback.getValue("isError").jsonPrimitive.boolean, mcpRollback.toString())
            kotlin.test.assertEquals(changeVisibilitySourceBefore, java.nio.file.Files.readString(servicePath))
        } finally {
            mcp.close()
        }
    }

    @io.cucumber.java.en.When("the daemon previews the bounded batchCleanup request for {string} using {string}")
    fun previewBoundedBatchCleanup(case: String, snapshotState: String) {
        write("src/main/java/com/acme/CleanupA.java", """
            package com.acme;
            import java.util.List;
            import java.util.ArrayList;
            public final class CleanupA { private List<String> values; }
        """.trimIndent() + "\n")
        write("src/main/java/com/acme/CleanupB.java", """
            package com.acme;
            import java.util.Map;
            import java.util.HashMap;
            public final class CleanupB { private Map<String, String> values; }
        """.trimIndent() + "\n")
        val opened = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        batchCleanupSnapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        batchCleanupBaseline = batchCleanupUserFiles()

        fun organizer(path: String) = kotlinx.serialization.json.buildJsonObject {
            put("operation", "organizeImports")
            put("arguments", kotlinx.serialization.json.buildJsonObject { put("file", path) })
        }
        val actions = when (case) {
            "two-disjoint-import-organizers" -> kotlinx.serialization.json.buildJsonArray {
                add(organizer("src/main/java/com/acme/CleanupA.java"))
                add(organizer("src/main/java/com/acme/CleanupB.java"))
            }
            "duplicate-organizer-target" -> kotlinx.serialization.json.buildJsonArray {
                add(organizer("src/main/java/com/acme/CleanupA.java"))
                add(organizer("src/main/java/com/acme/CleanupA.java"))
            }
            "organizer-plus-visibility-widening" -> kotlinx.serialization.json.buildJsonArray {
                add(organizer("src/main/java/com/acme/CleanupA.java"))
                add(kotlinx.serialization.json.buildJsonObject {
                    put("operation", "changeVisibility")
                    put("symbol", "com.acme.Service#helper(java.lang.String)")
                    put("arguments", kotlinx.serialization.json.buildJsonObject { put("visibility", "public") })
                })
            }
            "organizer-plus-safe-delete" -> kotlinx.serialization.json.buildJsonArray {
                add(organizer("src/main/java/com/acme/CleanupA.java"))
                add(kotlinx.serialization.json.buildJsonObject {
                    put("operation", "safeDelete")
                    put("arguments", kotlinx.serialization.json.buildJsonObject {
                        put("file", "src/main/java/com/acme/CleanupB.java")
                    })
                })
            }
            else -> error("Unknown batchCleanup case: $case")
        }
        try {
            batchCleanupPreview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "batchCleanup")
                put("languageId", "java")
                put("expectedSnapshotHash", if (snapshotState == "stale") "0".repeat(64) else batchCleanupSnapshotHash)
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("actions", actions) })
            }).jsonObject
            batchCleanupAccepted = true
        } catch (failure: org.refactorkit.core.JsonRpcException) {
            if (failure.code != org.refactorkit.core.JsonRpcErrorCodes.PLAN_REFUSED) throw failure
            batchCleanupRefusalCode = failure.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content
        }
    }

    @io.cucumber.java.en.Then("the batchCleanup result is {string} with refusal {string} and no partial changes")
    fun batchCleanupResultIs(status: String, refusalCode: String) {
        if (status == "PREVIEW") {
            val preview = requireNotNull(batchCleanupPreview)
            kotlin.test.assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
            kotlin.test.assertEquals("batchCleanup", preview.getValue("operation").jsonPrimitive.content)
            kotlin.test.assertEquals("LOW", preview.getValue("riskLevel").jsonPrimitive.content)
            kotlin.test.assertEquals(setOf(
                "src/main/java/com/acme/CleanupA.java",
                "src/main/java/com/acme/CleanupB.java",
            ), preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet())
            kotlin.test.assertTrue(preview.getValue("diagnosticsAfterPreview").jsonArray.none {
                it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
            }, preview.toString())
            val capabilities = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
            val javaAdapter = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
                .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
            val capability = javaAdapter.getValue("capabilities").jsonArray.map { it.jsonObject }
                .single { it.getValue("operation").jsonPrimitive.content == "batchCleanup" }
            kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
            kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
            kotlin.test.assertEquals("structural-parse", capability.getValue("evidence").jsonPrimitive.content)
            kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("batchCleanup"))
            kotlin.test.assertTrue(batchCleanupAccepted)
        } else {
            kotlin.test.assertNull(batchCleanupPreview)
            kotlin.test.assertFalse(batchCleanupAccepted)
            kotlin.test.assertEquals(refusalCode, batchCleanupRefusalCode)
        }
        kotlin.test.assertEquals(batchCleanupBaseline, batchCleanupUserFiles(), "Preview or refusal must not mutate saved files")
    }

    @io.cucumber.java.en.And("applying and rolling back the accepted batchCleanup restores the exact workspace")
    fun applyAndRollbackAcceptedBatchCleanup() {
        if (!batchCleanupAccepted) return
        val preview = requireNotNull(batchCleanupPreview)
        val applied = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", preview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", batchCleanupSnapshotHash)
        }).jsonObject
        kotlin.test.assertEquals("applied", applied.getValue("status").jsonPrimitive.content, applied.toString())
        val cleanupA = java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/CleanupA.java"))
        val cleanupB = java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/CleanupB.java"))
        kotlin.test.assertFalse(cleanupA.contains("import java.util.ArrayList;"), cleanupA)
        kotlin.test.assertFalse(cleanupB.contains("import java.util.HashMap;"), cleanupB)
        val rollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", applied.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", applied.getValue("snapshotHash").jsonPrimitive.content)
        }).jsonObject
        kotlin.test.assertTrue(rollback.getValue("rolledBack").jsonPrimitive.boolean, rollback.toString())
        val restored = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        kotlin.test.assertEquals(batchCleanupSnapshotHash, restored.getValue("snapshotHash").jsonPrimitive.content)
        kotlin.test.assertEquals(batchCleanupBaseline, batchCleanupUserFiles(), "Rollback must restore every saved workspace byte")

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.boolean, scan.toString())
            val scanText = scan.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            val mcpSnapshotHash = Regex("Snapshot: ([0-9a-f]{64})").find(scanText)?.groupValues?.get(1)
                ?: error("MCP project_scan returned no snapshot hash: $scanText")
            kotlin.test.assertEquals(batchCleanupSnapshotHash, mcpSnapshotHash)
            fun organizer(path: String) = kotlinx.serialization.json.buildJsonObject {
                put("operation", "organizeImports")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("file", path) })
            }
            val actions = kotlinx.serialization.json.buildJsonArray {
                add(organizer("src/main/java/com/acme/CleanupA.java"))
                add(organizer("src/main/java/com/acme/CleanupB.java"))
            }
            val mcpPreview = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "preview_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("operation", "batchCleanup")
                    put("languageId", "java")
                    put("expectedSnapshotHash", mcpSnapshotHash)
                    put("arguments", kotlinx.serialization.json.buildJsonObject { put("actions", actions) })
                })
            }).jsonObject
            kotlin.test.assertFalse(mcpPreview.getValue("isError").jsonPrimitive.boolean, mcpPreview.toString())
            val mcpPreviewText = mcpPreview.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(mcpPreviewText.contains("Operation: batchCleanup"), mcpPreviewText)
            val mcpPlanId = Regex("Plan ID\\s*: ([^\\s]+)").find(mcpPreviewText)?.groupValues?.get(1)
                ?: error("MCP batchCleanup preview has no plan ID: $mcpPreviewText")
            val mcpApply = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("planId", mcpPlanId) })
            }).jsonObject
            kotlin.test.assertFalse(mcpApply.getValue("isError").jsonPrimitive.boolean, mcpApply.toString())
            val mcpApplyText = mcpApply.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            val mcpTransactionId = Regex("Transaction ID: ([^\\s]+)").find(mcpApplyText)?.groupValues?.get(1)
                ?: error("MCP batchCleanup Apply has no transaction ID: $mcpApplyText")
            kotlin.test.assertFalse(java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/CleanupA.java"))
                .contains("import java.util.ArrayList;"))
            kotlin.test.assertFalse(java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/CleanupB.java"))
                .contains("import java.util.HashMap;"))
            val mcpRollback = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("transactionId", mcpTransactionId) })
            }).jsonObject
            kotlin.test.assertFalse(mcpRollback.getValue("isError").jsonPrimitive.boolean, mcpRollback.toString())
            val mcpRollbackText = mcpRollback.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(mcpRollbackText.startsWith("Rolled back transaction"), mcpRollbackText)
            kotlin.test.assertEquals(batchCleanupBaseline, batchCleanupUserFiles(), "MCP rollback must restore every saved workspace byte")
        } finally {
            mcp.close()
        }
    }

    private fun batchCleanupUserFiles(): Map<java.nio.file.Path, String> = java.nio.file.Files.walk(root).use { paths ->
        paths.filter(java.nio.file.Files::isRegularFile).filter { path ->
            root.relativize(path).none { it.toString() == ".refactorkit" }
        }.toList().associate { path ->
            root.relativize(path) to java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.nio.file.Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        }
    }

    private fun selectedBoundMethodSelection(methodName: String, snapshotHash: String): JsonObject {
        val lines = java.nio.file.Files.readString(servicePath).lines()
        val line = lines.indexOfFirst { "$methodName(String value)" in it }
        kotlin.test.assertTrue(line >= 0, lines.joinToString("\n"))
        val text = lines[line]
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", sdk.toString())
        }).jsonObject
        kotlin.test.assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        val response = session!!.dispatch("intelligence.query", kotlinx.serialization.json.buildJsonObject {
            put("requestId", "change-visibility-$methodName-selection")
            put("kind", "mutationSelection")
            put("languageId", "java")
            put("path", "src/main/java/com/acme/Service.java")
            put("position", kotlinx.serialization.json.buildJsonObject {
                put("line", line)
                put("character", text.indexOf(methodName) + 1)
            })
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", javaContext())
            put("sourceAuthority", kotlinx.serialization.json.buildJsonObject { put("kind", "saved-snapshot") })
        }).jsonObject
        kotlin.test.assertEquals("ready", response.getValue("status").jsonPrimitive.content, response.toString())
        val selection = response.getValue("selection").jsonObject
        kotlin.test.assertEquals("method", selection.getValue("symbolKind").jsonPrimitive.content)
        return selection
    }

    private fun selectedBoundMethodSymbol(methodName: String, snapshotHash: String): String =
        selectedBoundMethodSelection(methodName, snapshotHash).getValue("symbolId").jsonPrimitive.content

    @io.cucumber.java.en.When("I remove the unused JDT-bound parameter through daemon and MCP")
    fun removeUnusedParameterThroughEngineSurfaces() {
        val capabilities = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
        val javaDescriptor = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val registered = javaDescriptor.getValue("capabilities").jsonArray.map { it.jsonObject }
            .filter { it.getValue("mutationAuthority").jsonPrimitive.content != "none" }
            .associateBy { it.getValue("operation").jsonPrimitive.content }
        val sdkRefactoring = capabilities.getValue("javaContextRefactoring").jsonObject
        val sdkOperations = sdkRefactoring.getValue("operations").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val operationCapabilities = sdkRefactoring.getValue("operationCapabilities").jsonArray.map { it.jsonObject }
            .associateBy { it.getValue("operation").jsonPrimitive.content }
        val adapter = org.refactorkit.java.JavaAdapterRegistration.create().adapter
        val descriptors = adapter.availableRefactorings(org.refactorkit.core.CodeSelection(
            org.refactorkit.core.SourceLocation(servicePath, org.refactorkit.core.SourceRange(
                org.refactorkit.core.SourcePosition(0, 0), org.refactorkit.core.SourcePosition(0, 0),
            )),
        )).associateBy { it.id }
        kotlin.test.assertEquals(registered.keys, sdkOperations)
        kotlin.test.assertEquals(registered.keys, operationCapabilities.keys)
        kotlin.test.assertEquals(registered.keys, descriptors.keys)
        kotlin.test.assertFalse("java.moveAcrossMavenModules" in registered)
        kotlin.test.assertFalse("java.moveAcrossMavenModules" in sdkOperations)
        kotlin.test.assertEquals("stable", operationCapabilities.getValue("changeSignature.renameParameter").getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", operationCapabilities.getValue("changeSignature.renameParameter").getValue("mutationAuthority").jsonPrimitive.content)
        var repositoryRoot = java.nio.file.Path.of("").toAbsolutePath()
        while (!java.nio.file.Files.exists(repositoryRoot.resolve("docs/api-0.2-contract.json"))) {
            repositoryRoot = repositoryRoot.parent ?: error("Repository root not found")
        }
        val contractRefactoring = kotlinx.serialization.json.Json.parseToJsonElement(
            java.nio.file.Files.readString(repositoryRoot.resolve("docs/api-0.2-contract.json")),
        ).jsonObject.getValue("daemon").jsonObject.getValue("javaContextRefactoring").jsonObject
        kotlin.test.assertEquals(sdkOperations,
            contractRefactoring.getValue("operations").jsonArray.map { it.jsonPrimitive.content }.toSet())
        kotlin.test.assertEquals(operationCapabilities,
            contractRefactoring.getValue("operationCapabilities").jsonArray.map { it.jsonObject }
                .associateBy { it.getValue("operation").jsonPrimitive.content })
        registered.forEach { (operation, value) ->
            val discovered = operationCapabilities.getValue(operation)
            kotlin.test.assertEquals(value.getValue("stability"), discovered.getValue("stability"))
            kotlin.test.assertEquals(value.getValue("mutationAuthority"), discovered.getValue("mutationAuthority"))
            kotlin.test.assertEquals(value.getValue("evidence"), discovered.getValue("evidence"))
            val descriptorCapability = kotlin.test.assertNotNull(descriptors.getValue(operation).capability)
            kotlin.test.assertEquals(operation, descriptorCapability.operation)
            kotlin.test.assertEquals(value.getValue("stability").jsonPrimitive.content, descriptorCapability.stability.name.lowercase().replace('_', '-'))
            kotlin.test.assertEquals(value.getValue("mutationAuthority").jsonPrimitive.content, descriptorCapability.mutationAuthority.name.lowercase().replace('_', '-'))
            kotlin.test.assertEquals(value.getValue("evidence").jsonPrimitive.content, descriptorCapability.evidence.name.lowercase().replace('_', '-'))
        }
        kotlin.test.assertFalse(sdkOperations.any { it in setOf("renameParameter", "changeParameterType", "addParameter", "reorderParameters", "removeParameter") })
        val removeCapability = operationCapabilities.getValue("changeSignature.removeParameter")
        kotlin.test.assertEquals("stable", removeCapability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", removeCapability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", removeCapability.getValue("evidence").jsonPrimitive.content)

        removeParameterOriginalImage = fixtureImage()
        val serviceKey = "src/main/java/com/acme/Service.java"
        val originalService = removeParameterOriginalImage.getValue(serviceKey)
        val expectedService = originalService
            .replace("private String quarantine(String label, boolean unused)", "private String quarantine(String label)")
            .replace("return quarantine(\"legacy\", true);", "return quarantine(\"legacy\");")
        kotlin.test.assertNotEquals(originalService, expectedService)
        removeParameterExpectedImage = removeParameterOriginalImage.toMutableMap().also { it[serviceKey] = expectedService }

        val initial = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        val initialHash = initial.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", initialHash)
            put("sdkHome", sdk.toString())
        }).jsonObject
        removeParameterDaemonPreview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "changeSignature.removeParameter")
            put("languageId", "java")
            put("symbol", "com.acme.Service#quarantine")
            put("expectedSnapshotHash", initialHash)
            put("javaContext", javaContext())
            put("arguments", kotlinx.serialization.json.buildJsonObject { put("name", "unused") })
        }).jsonObject
        removeParameterDaemonApply = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", removeParameterDaemonPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", initialHash)
            put("javaContext", javaContext())
        }).jsonObject
        removeParameterDaemonAppliedImage = fixtureImage()
        removeParameterDaemonRollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", removeParameterDaemonApply.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", removeParameterDaemonApply.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext())
        }).jsonObject

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scan.toString())
            val preview = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "preview_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("operation", "changeSignature.removeParameter")
                    put("languageId", "java")
                    put("symbol", "com.acme.Service#quarantine")
                    put("arguments", kotlinx.serialization.json.buildJsonObject { put("name", "unused") })
                })
            }).jsonObject
            removeParameterMcpPreviewText = preview.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            val planId = removeParameterMcpPreviewText.lineSequence().single { it.trimStart().startsWith("Plan ID") }
                .substringAfter(':').trim()
            removeParameterMcpApplyText = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("planId", planId) })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            removeParameterMcpAppliedImage = fixtureImage()
            val diagnostics = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "diagnostics")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("languageId", "java")
                    put("jdkHome", sdk.toString())
                })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertFalse(diagnostics.lineSequence().any { it.startsWith("[ERROR]") }, diagnostics)
            val transactionId = Regex("Transaction ID: ([^\\s]+)").find(removeParameterMcpApplyText)?.groupValues?.get(1)
                ?: error("MCP remove-parameter Apply did not return its transaction ID: $removeParameterMcpApplyText")
            removeParameterMcpRollbackText = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("transactionId", transactionId) })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
        } finally {
            mcp.close()
        }
        val records = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        removeParameterTransactionOperations = records.map { it.operation }
        removeParameterTransactionStates = records.map { it.state.name }
    }

    @io.cucumber.java.en.Then("adapter descriptors and SDK mutation discovery agree with registered Java mutation authority")
    fun verifyDiscovery() {
        kotlin.test.assertEquals("PREVIEW", removeParameterDaemonPreview.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("JDT_BINDING", removeParameterDaemonPreview.getValue("evidence").jsonPrimitive.content)
        val capabilities = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
        val javaCapabilityDescriptor = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = javaCapabilityDescriptor.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "changeSignature.removeParameter" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("changeSignature.removeParameter"))
        kotlin.test.assertFalse(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("removeParameter"))
    }

    @io.cucumber.java.en.And("daemon and MCP remove-parameter transactions update bound calls and restore exact source bytes")
    fun verifyManagedRemoveParameterLifecycle() {
        kotlin.test.assertEquals("PREVIEW", removeParameterDaemonPreview.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("changeSignature.removeParameter", removeParameterDaemonPreview.getValue("operation").jsonPrimitive.content)
        kotlin.test.assertEquals("JDT_BINDING", removeParameterDaemonPreview.getValue("evidence").jsonPrimitive.content)
        val diff = removeParameterDaemonPreview.getValue("renderedDiff").jsonPrimitive.content
        kotlin.test.assertTrue(diff.contains("quarantine(String label)"), diff)
        kotlin.test.assertTrue(diff.contains("quarantine(\"legacy\")"), diff)
        kotlin.test.assertTrue(removeParameterDaemonPreview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
        })
        kotlin.test.assertEquals("applied", removeParameterDaemonApply.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(removeParameterExpectedImage, removeParameterDaemonAppliedImage)
        kotlin.test.assertTrue(removeParameterDaemonApply.getValue("diagnostics").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
        })
        kotlin.test.assertEquals("rolledBack", removeParameterDaemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(removeParameterOriginalImage, fixtureImage(), "daemon rollback restores exact workspace bytes")
        kotlin.test.assertTrue(removeParameterMcpPreviewText.contains("Remove JDT-proven unused parameter"), removeParameterMcpPreviewText)
        kotlin.test.assertTrue(removeParameterMcpPreviewText.contains("Evidence : JDT_BINDING"), removeParameterMcpPreviewText)
        kotlin.test.assertTrue(removeParameterMcpApplyText.startsWith("Applied successfully."), removeParameterMcpApplyText)
        kotlin.test.assertEquals(removeParameterExpectedImage, removeParameterMcpAppliedImage)
        kotlin.test.assertTrue(removeParameterMcpRollbackText.startsWith("Rolled back transaction"), removeParameterMcpRollbackText)
        kotlin.test.assertEquals(removeParameterOriginalImage, fixtureImage(), "MCP rollback restores exact workspace bytes")
        kotlin.test.assertEquals(listOf("changeSignature.removeParameter", "changeSignature.removeParameter"), removeParameterTransactionOperations)
        kotlin.test.assertEquals(listOf("ROLLED_BACK", "ROLLED_BACK"), removeParameterTransactionStates)
    }

    @io.cucumber.java.en.And("effectful removed arguments refuse before daemon or MCP transaction")
    fun refuseEffectfulRemovedArgument() {
        val base = java.nio.file.Files.readString(servicePath)
        val sideEffecting = base
            .replace("                private String quarantine(", "                private int sideEffects;\n                private boolean nextFlag() { sideEffects++; return true; }\n                private String quarantine(")
            .replace("return quarantine(\"legacy\", true);", "return quarantine(\"legacy\", nextFlag());")
        kotlin.test.assertNotEquals(base, sideEffecting)
        java.nio.file.Files.writeString(servicePath, sideEffecting)
        val unchangedImage = fixtureImage()
        val log = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions"))
        val beforeTransactions = log.listRecords().size
        val opened = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        val hash = opened.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", hash)
            put("sdkHome", sdk.toString())
        }).jsonObject
        val daemonRefusal = try {
            session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "changeSignature.removeParameter")
                put("languageId", "java")
                put("symbol", "com.acme.Service#quarantine")
                put("expectedSnapshotHash", hash)
                put("javaContext", javaContext())
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("name", "unused") })
            })
            error("Effectful removed arguments must be refused during daemon preview")
        } catch (failure: org.refactorkit.core.JsonRpcException) {
            failure
        }
        kotlin.test.assertTrue(daemonRefusal.message.orEmpty().contains("observable effects"), daemonRefusal.toString())
        kotlin.test.assertEquals(unchangedImage, fixtureImage())
        kotlin.test.assertEquals(beforeTransactions, log.listRecords().size)

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scan.toString())
            val refusalText = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "preview_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("operation", "changeSignature.removeParameter")
                    put("languageId", "java")
                    put("symbol", "com.acme.Service#quarantine")
                    put("arguments", kotlinx.serialization.json.buildJsonObject { put("name", "unused") })
                })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(refusalText.contains("Status   : REFUSED"), refusalText)
            kotlin.test.assertTrue(refusalText.contains("observable effects"), refusalText)
        } finally {
            mcp.close()
        }
        kotlin.test.assertEquals(unchangedImage, fixtureImage())
        kotlin.test.assertEquals(beforeTransactions, log.listRecords().size,
            "An effectful removed argument must be refused before transaction creation")
    }

    @io.cucumber.java.en.When("I preview, apply and roll back changing the {string} parameter type through daemon and MCP")
    fun manageParameterTypeChange(shape: String) {
        val spec = parameterTypeChangeSpec(shape)
        val originalImage = fixtureImage()
        val expectedImage = originalImage.toMutableMap()
        spec.replacements.forEach { (path, replacement) ->
            val before = originalImage.getValue(path)
            kotlin.test.assertEquals(1, Regex(Regex.escape(replacement.first)).findAll(before).count(), path)
            expectedImage[path] = before.replace(replacement.first, replacement.second)
        }

        val initial = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        val initialHash = initial.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", initialHash)
            put("sdkHome", sdk.toString())
        }).jsonObject

        val selected = if (shape == "cursor-primitive-boxing") {
            val source = originalImage.getValue("src/main/java/com/acme/Service.java")
            val lineNumber = source.lines().indexOfFirst { it.contains("private int box(int second)") }
            kotlin.test.assertTrue(lineNumber >= 0, "cursor fixture declaration must exist")
            val line = source.lines()[lineNumber]
            val query = session!!.dispatch("intelligence.query", kotlinx.serialization.json.buildJsonObject {
                put("requestId", "type-change-saved-cursor")
                put("kind", "mutationSelection")
                put("languageId", "java")
                put("path", "src/main/java/com/acme/Service.java")
                put("position", kotlinx.serialization.json.buildJsonObject {
                    put("line", lineNumber)
                    put("character", line.indexOf("box") + 1)
                })
                put("expectedSnapshotHash", initialHash)
                put("javaContext", javaContext())
                put("sourceAuthority", kotlinx.serialization.json.buildJsonObject { put("kind", "saved-snapshot") })
            }).jsonObject
            kotlin.test.assertEquals("ready", query.getValue("status").jsonPrimitive.content, query.toString())
            query.getValue("selection").jsonObject
        } else null

        val daemonPreview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "changeSignature.changeParameterType")
            put("languageId", "java")
            put("symbol", selected?.get("symbolId")?.jsonPrimitive?.content ?: spec.symbol)
            selected?.let { put("javaSelection", it) }
            put("expectedSnapshotHash", initialHash)
            put("javaContext", javaContext())
            put("arguments", parameterTypeChangeArguments(spec))
        }).jsonObject
        selected?.let {
            kotlin.test.assertEquals(it.getValue("contextHash").jsonPrimitive.content,
                daemonPreview.getValue("contextHash").jsonPrimitive.content)
            kotlin.test.assertEquals(it.getValue("selectorHash").jsonPrimitive.content,
                daemonPreview.getValue("selectionHash").jsonPrimitive.content)
        }
        val daemonApply = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", daemonPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", initialHash)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals(context.getValue("contextHash").jsonPrimitive.content,
            daemonApply.getValue("appliedContextHash").jsonPrimitive.content)
        selected?.let {
            kotlin.test.assertEquals(daemonPreview.getValue("selectionHash").jsonPrimitive.content,
                daemonApply.getValue("appliedSelectionHash").jsonPrimitive.content)
        }
        val daemonAppliedImage = fixtureImage()
        val daemonRollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", daemonApply.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", daemonApply.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals(originalImage, fixtureImage(), "daemon rollback must restore every project file")

        val mcp = org.refactorkit.mcp.McpSession()
        val mcpEvidence = try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scan.toString())
            val preview = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "preview_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("operation", "changeSignature.changeParameterType")
                    put("languageId", "java")
                    put("symbol", spec.symbol)
                    put("arguments", parameterTypeChangeArguments(spec))
                })
            }).jsonObject
            val previewText = preview.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            val planId = previewText.lineSequence().single { it.trimStart().startsWith("Plan ID") }
                .substringAfter(':').trim()
            val apply = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("planId", planId) })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            val mcpAppliedImage = fixtureImage()
            val diagnostics = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "diagnostics")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("languageId", "java")
                    put("jdkHome", sdk.toString())
                })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            val transactionId = Regex("Transaction ID: ([^\\s]+)").find(apply)?.groupValues?.get(1)
                ?: error("MCP type-change Apply did not return its transaction ID: $apply")
            val rollback = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("transactionId", transactionId) })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertEquals(originalImage, fixtureImage(), "MCP rollback must restore every project file")
            Triple(previewText, apply, rollback) to (mcpAppliedImage to diagnostics)
        } finally {
            mcp.close()
        }
        val records = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        typeChangeEvidence = ParameterTypeChangeEvidence(
            shape = shape,
            originalImage = originalImage,
            expectedImage = expectedImage,
            expectedDeclarations = spec.replacements.values.map { it.second },
            daemonPreview = daemonPreview,
            daemonApply = daemonApply,
            daemonRollback = daemonRollback,
            daemonAppliedImage = daemonAppliedImage,
            daemonDiagnostics = daemonApply.getValue("diagnostics").jsonArray,
            contextHash = context.getValue("contextHash").jsonPrimitive.content,
            selectionHash = selected?.get("selectorHash")?.jsonPrimitive?.content,
            mcpPreviewText = mcpEvidence.first.first,
            mcpApplyText = mcpEvidence.first.second,
            mcpRollbackText = mcpEvidence.first.third,
            mcpAppliedImage = mcpEvidence.second.first,
            mcpDiagnosticsText = mcpEvidence.second.second,
            transactionOperations = records.map { it.operation },
            transactionStates = records.map { it.state.name },
        )
    }

    @io.cucumber.java.en.Then("changeSignature.changeParameterType is advertised as stable managed Java mutation")
    fun verifyParameterTypeChangeAuthority() {
        val capabilities = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
        val javaAdapter = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = javaAdapter.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "changeSignature.changeParameterType" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        val contextCapability = capabilities.getValue("javaContextRefactoring").jsonObject
            .getValue("operationCapabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "changeSignature.changeParameterType" }
        kotlin.test.assertEquals("stable", contextCapability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", contextCapability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations()
            .contains("changeSignature.changeParameterType"))
        kotlin.test.assertTrue(org.refactorkit.java.JavaAdapterRegistration.supportsSdkMutationOperation(
            "changeSignature.changeParameterType",
        ))
    }

    @io.cucumber.java.en.And("daemon and MCP {string} type-change transactions replace only bound type tokens and restore exact source bytes")
    fun verifyParameterTypeChangeLifecycle(shape: String) {
        val evidence = typeChangeEvidence
        kotlin.test.assertEquals(shape, evidence.shape)
        kotlin.test.assertEquals(evidence.expectedImage, evidence.daemonAppliedImage)
        kotlin.test.assertEquals(evidence.expectedImage, evidence.mcpAppliedImage)
        kotlin.test.assertEquals(evidence.originalImage, fixtureImage())
        kotlin.test.assertEquals("PREVIEW", evidence.daemonPreview.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("changeSignature.changeParameterType", evidence.daemonPreview.getValue("operation").jsonPrimitive.content)
        kotlin.test.assertEquals("JDT_BINDING", evidence.daemonPreview.getValue("evidence").jsonPrimitive.content)
        val daemonDiff = evidence.daemonPreview.getValue("renderedDiff").jsonPrimitive.content
        evidence.expectedDeclarations.forEach { declaration ->
            kotlin.test.assertTrue(daemonDiff.contains(declaration), daemonDiff)
        }
        kotlin.test.assertEquals("applied", evidence.daemonApply.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(evidence.contextHash,
            evidence.daemonApply.getValue("appliedContextHash").jsonPrimitive.content)
        evidence.selectionHash?.let {
            kotlin.test.assertEquals(it, evidence.daemonPreview.getValue("selectionHash").jsonPrimitive.content)
            kotlin.test.assertEquals(it, evidence.daemonApply.getValue("appliedSelectionHash").jsonPrimitive.content)
        }
        kotlin.test.assertEquals("rolledBack", evidence.daemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(evidence.contextHash,
            evidence.daemonRollback.getValue("rolledBackContextHash").jsonPrimitive.content)
        kotlin.test.assertTrue(evidence.daemonDiagnostics.none { diagnostic ->
            diagnostic.jsonObject["severity"]?.jsonPrimitive?.content?.equals("error", ignoreCase = true) == true
        }, evidence.daemonDiagnostics.toString())
        kotlin.test.assertTrue(evidence.mcpPreviewText.contains("Evidence : JDT_BINDING"), evidence.mcpPreviewText)
        val expectedMcpSummary = if (shape == "override-family") "Change parameter index 0" else "Change JDT-proven parameter"
        kotlin.test.assertTrue(evidence.mcpPreviewText.contains(expectedMcpSummary), evidence.mcpPreviewText)
        kotlin.test.assertTrue(evidence.mcpApplyText.startsWith("Applied successfully."), evidence.mcpApplyText)
        kotlin.test.assertTrue(evidence.mcpRollbackText.startsWith("Rolled back transaction"), evidence.mcpRollbackText)
        kotlin.test.assertFalse(evidence.mcpDiagnosticsText.lineSequence().any { it.startsWith("[ERROR]") }, evidence.mcpDiagnosticsText)
        kotlin.test.assertEquals(listOf("changeSignature.changeParameterType", "changeSignature.changeParameterType"),
            evidence.transactionOperations)
        kotlin.test.assertEquals(listOf("ROLLED_BACK", "ROLLED_BACK"), evidence.transactionStates)
    }

    private fun parameterTypeChangeSpec(shape: String): ParameterTypeChangeSpec = when (shape) {
        "private-method" -> ParameterTypeChangeSpec(
            symbol = "com.acme.Service#helper",
            parameterName = "value",
            newType = "Object",
            replacements = mapOf(
                "src/main/java/com/acme/Service.java" to
                    ("private Object helper(String value)" to "private Object helper(Object value)"),
            ),
        )
        "cursor-primitive-boxing" -> ParameterTypeChangeSpec(
            symbol = "com.acme.Service#box",
            parameterName = "second",
            newType = "Integer",
            replacements = mapOf(
                "src/main/java/com/acme/Service.java" to
                    ("private int box(int second)" to "private int box(Integer second)"),
            ),
        )
        "override-family" -> ParameterTypeChangeSpec(
            symbol = "com.acme.Lookup#find(java.lang.String)",
            parameterName = "key",
            newType = "CharSequence",
            includeHierarchy = true,
            acceptExternalConsumerRisk = true,
            replacements = mapOf(
                "src/main/java/com/acme/Lookup.java" to
                    ("find(String key)" to "find(CharSequence key)"),
                "src/main/java/com/acme/DefaultLookup.java" to
                    ("find(String value)" to "find(CharSequence value)"),
            ),
        )
        "private-constructor" -> ParameterTypeChangeSpec(
            symbol = "com.acme.Token#<init>(java.lang.String)",
            parameterName = "value",
            newType = "CharSequence",
            replacements = mapOf(
                "src/main/java/com/acme/Token.java" to
                    ("private Token(String value)" to "private Token(CharSequence value)"),
            ),
        )
        else -> error("Unsupported parameter type Story shape: $shape")
    }

    private fun parameterTypeChangeArguments(spec: ParameterTypeChangeSpec) =
        kotlinx.serialization.json.buildJsonObject {
            put("type", spec.newType)
            put("name", spec.parameterName)
            put("includeHierarchy", spec.includeHierarchy)
            put("acceptExternalConsumerRisk", spec.acceptExternalConsumerRisk)
        }

    private fun fixtureImage(): Map<String, String> = java.nio.file.Files.walk(root).use { paths ->
        paths.filter { path ->
            java.nio.file.Files.isRegularFile(path) && !root.relativize(path).normalize().startsWith(java.nio.file.Path.of(".refactorkit"))
        }.toList().associate { path ->
            root.relativize(path).toString().replace('\\', '/') to java.nio.file.Files.readString(path)
        }
    }

    @io.cucumber.java.en.When("I preview, apply and roll back adding a parameter through daemon and MCP")
    fun manageAddParameterThroughEngineSurfaces() {
        val original = java.nio.file.Files.readString(servicePath)
        addParameterExpected = original
            .replace("helper(String value)", "helper(String value, String suffix)")
            .replace("helper(value)", "helper(value, \"added\")")
        val initial = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        val initialHash = initial.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", initialHash)
            put("sdkHome", sdk.toString())
        }).jsonObject
        addParameterDaemonPreview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "changeSignature.addParameter")
            put("languageId", "java")
            put("symbol", "com.acme.Service#helper")
            put("expectedSnapshotHash", initialHash)
            put("javaContext", javaContext())
            put("arguments", kotlinx.serialization.json.buildJsonObject {
                put("type", "String"); put("name", "suffix"); put("default", "\"added\"")
            })
        }).jsonObject
        kotlin.test.assertEquals("PREVIEW", addParameterDaemonPreview.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("changeSignature.addParameter", addParameterDaemonPreview.getValue("operation").jsonPrimitive.content)
        kotlin.test.assertEquals("JDT_BINDING", addParameterDaemonPreview.getValue("evidence").jsonPrimitive.content)
        val renderedDiff = addParameterDaemonPreview.getValue("renderedDiff").jsonPrimitive.content
        kotlin.test.assertTrue(renderedDiff.contains("helper(String value, String suffix)"), renderedDiff)
        kotlin.test.assertTrue(renderedDiff.contains("helper(value, \"added\")"), renderedDiff)
        kotlin.test.assertTrue(addParameterDaemonPreview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
        })
        addParameterDaemonApply = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", addParameterDaemonPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", initialHash)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("applied", addParameterDaemonApply.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(addParameterExpected, java.nio.file.Files.readString(servicePath))
        kotlin.test.assertTrue(addParameterDaemonApply.getValue("diagnostics").jsonArray.none { diagnostic ->
            diagnostic.jsonObject["severity"]?.jsonPrimitive?.content?.equals("error", ignoreCase = true) == true
        })
        addParameterDaemonRollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", addParameterDaemonApply.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", addParameterDaemonApply.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("rolledBack", addParameterDaemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(original, java.nio.file.Files.readString(servicePath))

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scan.toString())
            val preview = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "preview_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("operation", "changeSignature.addParameter")
                    put("languageId", "java")
                    put("symbol", "com.acme.Service#helper")
                    put("arguments", kotlinx.serialization.json.buildJsonObject {
                        put("type", "String"); put("name", "suffix"); put("default", "\"added\"")
                    })
                })
            }).jsonObject
            addParameterMcpPreviewText = preview.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(addParameterMcpPreviewText.contains("Evidence : JDT_BINDING"), addParameterMcpPreviewText)
            kotlin.test.assertTrue(addParameterMcpPreviewText.contains("Add JDT-proven parameter"), addParameterMcpPreviewText)
            val planId = addParameterMcpPreviewText.lineSequence().single { it.trimStart().startsWith("Plan ID") }
                .substringAfter(':').trim()
            val apply = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("planId", planId) })
            }).jsonObject
            addParameterMcpApplyText = apply.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(addParameterMcpApplyText.startsWith("Applied successfully."), addParameterMcpApplyText)
            val transactionId = Regex("Transaction ID: ([^\\s]+)").find(addParameterMcpApplyText)?.groupValues?.get(1)
                ?: error("MCP addParameter Apply did not return its transaction ID: $addParameterMcpApplyText")
            kotlin.test.assertEquals(addParameterExpected, java.nio.file.Files.readString(servicePath))
            val diagnostics = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "diagnostics")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("languageId", "java"); put("jdkHome", sdk.toString())
                })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertFalse(diagnostics.lineSequence().any { it.startsWith("[ERROR]") }, diagnostics)
            addParameterMcpRollbackText = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("transactionId", transactionId) })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(addParameterMcpRollbackText.startsWith("Rolled back transaction"), addParameterMcpRollbackText)
            kotlin.test.assertEquals(original, java.nio.file.Files.readString(servicePath))
        } finally {
            mcp.close()
        }
    }

    @io.cucumber.java.en.Then("changeSignature.addParameter is advertised as stable managed Java mutation")
    fun verifyAddParameterAuthority() {
        val capabilities = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
        val javaAdapter = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = javaAdapter.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "changeSignature.addParameter" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("changeSignature.addParameter"))
        val capabilityTruth = capabilities.getValue("javaContextRefactoring").jsonObject
            .getValue("operationCapabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "changeSignature.addParameter" }
        kotlin.test.assertEquals("stable", capabilityTruth.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capabilityTruth.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capabilityTruth.getValue("evidence").jsonPrimitive.content)
    }

    @io.cucumber.java.en.And("daemon and MCP add-parameter transactions update bound calls and restore exact source bytes")
    fun verifyManagedAddParameterLifecycle() {
        kotlin.test.assertEquals("rolledBack", addParameterDaemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertTrue(addParameterMcpApplyText.startsWith("Applied successfully."))
        kotlin.test.assertTrue(addParameterMcpRollbackText.startsWith("Rolled back transaction"))
        val records = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        kotlin.test.assertEquals(2, records.size)
        kotlin.test.assertTrue(records.all { it.operation == "changeSignature.addParameter" })
        kotlin.test.assertTrue(records.all { it.state == org.refactorkit.core.JournalState.ROLLED_BACK })
        kotlin.test.assertTrue(addParameterMcpPreviewText.contains("Evidence : JDT_BINDING"))
        kotlin.test.assertTrue(addParameterDaemonPreview.getValue("renderedDiff").jsonPrimitive.content.contains("helper(value, \"added\")"))
        kotlin.test.assertEquals(serviceOriginal, java.nio.file.Files.readString(servicePath))
    }

    @io.cucumber.java.en.When("I preview apply and roll back changeSignature.renameParameter through daemon and MCP")
    fun manageParameterRenameThroughEngineSurfaces() {
        val original = java.nio.file.Files.readString(servicePath)
        val expected = original.replace("execute(String value)", "execute(String item)").replace("helper(value)", "helper(item)")
        val initialHash = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val daemonPreview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "changeSignature.renameParameter")
            put("languageId", "java")
            put("symbol", "com.acme.Service#execute")
            put("expectedSnapshotHash", initialHash)
            put("javaContext", javaContext())
            put("arguments", kotlinx.serialization.json.buildJsonObject {
                put("oldName", "value"); put("newName", "item")
            })
        }).jsonObject
        kotlin.test.assertEquals("PREVIEW", daemonPreview.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("changeSignature.renameParameter", daemonPreview.getValue("operation").jsonPrimitive.content)
        daemonApply = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", daemonPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", initialHash)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("applied", daemonApply.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(expected, java.nio.file.Files.readString(servicePath))
        val applyErrors = daemonApply.getValue("diagnostics").jsonArray.filter { diagnostic ->
            diagnostic.jsonObject["severity"]?.jsonPrimitive?.content?.equals("error", ignoreCase = true) == true
        }
        kotlin.test.assertTrue(applyErrors.isEmpty(), applyErrors.toString())
        daemonRollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", daemonApply.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", daemonApply.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("rolledBack", daemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(original, java.nio.file.Files.readString(servicePath))

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scan.toString())
            val preview = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "preview_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("operation", "changeSignature.renameParameter")
                    put("languageId", "java")
                    put("symbol", "com.acme.Service#execute")
                    put("arguments", kotlinx.serialization.json.buildJsonObject {
                        put("oldName", "value"); put("newName", "item")
                    })
                })
            }).jsonObject
            val previewText = preview.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(previewText.contains("Rename JDT-proven parameter"), previewText)
            val planId = previewText.lineSequence().single { it.trimStart().startsWith("Plan ID") }
                .substringAfter(':').trim()
            val apply = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("planId", planId) })
            }).jsonObject
            val applyText = apply.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(applyText.startsWith("Applied successfully."), applyText)
            val transactionId = Regex("Transaction ID: ([^\\s]+)").find(applyText)?.groupValues?.get(1)
                ?: error("MCP Apply did not return its transaction ID: $applyText")
            kotlin.test.assertEquals(expected, java.nio.file.Files.readString(servicePath))
            mcpAppliedText = applyText
            val diagnostics = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "diagnostics")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("languageId", "java"); put("jdkHome", sdk.toString())
                })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertFalse(diagnostics.lineSequence().any { it.startsWith("[ERROR]") }, diagnostics)
            mcpRollbackText = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("transactionId", transactionId) })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(mcpRollbackText.startsWith("Rolled back transaction"), mcpRollbackText)
            kotlin.test.assertEquals(original, java.nio.file.Files.readString(servicePath))
        } finally {
            mcp.close()
        }
    }

    @io.cucumber.java.en.Then("changeSignature.renameParameter is advertised as stable managed Java mutation")
    fun verifyParameterRenameAuthority() {
        val capabilities = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
        val java = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "changeSignature.renameParameter" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("changeSignature.renameParameter"))
        kotlin.test.assertFalse(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("renameParameter"))
        val siblingOperations = setOf("changeSignature.removeParameter")
        val discovered = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .associateBy { it.getValue("operation").jsonPrimitive.content }
        siblingOperations.forEach { operation ->
            kotlin.test.assertEquals("stable", discovered.getValue(operation).getValue("stability").jsonPrimitive.content)
            kotlin.test.assertEquals("managed-stable", discovered.getValue(operation).getValue("mutationAuthority").jsonPrimitive.content)
            kotlin.test.assertEquals("compiler", discovered.getValue(operation).getValue("evidence").jsonPrimitive.content)
        }
    }

    @io.cucumber.java.en.And("daemon and MCP parameter-rename transactions pass diagnostics and restore exact source bytes")
    fun verifyManagedParameterRenameLifecycle() {
        kotlin.test.assertTrue(daemonApply.getValue("planId").jsonPrimitive.content.isNotBlank())
        kotlin.test.assertEquals("rolledBack", daemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertTrue(mcpAppliedText.startsWith("Applied successfully."))
        kotlin.test.assertTrue(mcpRollbackText.startsWith("Rolled back transaction"))
        val records = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        kotlin.test.assertEquals(2, records.size)
        kotlin.test.assertTrue(records.all { it.operation == "changeSignature.renameParameter" })
        kotlin.test.assertTrue(records.all { it.state == org.refactorkit.core.JournalState.ROLLED_BACK })
        kotlin.test.assertEquals(serviceOriginal, java.nio.file.Files.readString(servicePath))
    }

    @io.cucumber.java.en.When("I preview apply and roll back extractVariable for a bound return subexpression with safety refusals through daemon and MCP")
    fun manageExtractVariableThroughEngineSurfaces() {
        val initialCalculator = java.nio.file.Files.readString(calculatorPath)
        calculatorOriginal = initialCalculator.replace("return input * 2 + 1;", "return 6 * 7 + 0;")
        val classEnd = calculatorOriginal.lastIndexOf('}')
        kotlin.test.assertTrue(classEnd >= 0)
        calculatorOriginal = calculatorOriginal.substring(0, classEnd) + """

                private int touch() { return 9; }
                private int effectfulPrefix() { return touch() + 6 * 7; }
                private boolean shortCircuit(boolean enabled) { return enabled && 6 * 7 > 0; }
                private Object nonDenotable() { return null; }
                private java.util.function.Supplier<String> poly() { return String::new; }
            }
        """.trimIndent() + "\n"
        java.nio.file.Files.writeString(calculatorPath, calculatorOriginal)
        val returnAnchor = "return 6 * 7 + 0;"
        val returnOffset = calculatorOriginal.indexOf(returnAnchor)
        val returnLineStart = calculatorOriginal.lastIndexOf('\n', returnOffset - 1) + 1
        val indentation = calculatorOriginal.substring(returnLineStart, returnOffset)
        calculatorExpected = calculatorOriginal.replace(
            returnAnchor,
            "int doubled = 6 * 7;\n${indentation}return doubled + 0;",
        )
        val originalOpen = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        val initialHash = originalOpen.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", initialHash)
            put("sdkHome", sdk.toString())
        }).jsonObject
        kotlin.test.assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        val fullArguments = extractVariableArguments(calculatorOriginal, "return 6 * 7 + 0;", "6 * 7", "doubled")
        extractionDaemonPreview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "extractVariable")
            put("languageId", "java")
            put("expectedSnapshotHash", initialHash)
            put("javaContext", javaContext())
            put("arguments", fullArguments)
        }).jsonObject
        kotlin.test.assertEquals("PREVIEW", extractionDaemonPreview.getValue("status").jsonPrimitive.content)
        extractionDaemonApply = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", extractionDaemonPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", initialHash)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("applied", extractionDaemonApply.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(calculatorExpected, java.nio.file.Files.readString(calculatorPath))
        val daemonErrors = extractionDaemonApply.getValue("diagnostics").jsonArray.filter { diagnostic ->
            diagnostic.jsonObject["severity"]?.jsonPrimitive?.content?.equals("error", ignoreCase = true) == true
        }
        kotlin.test.assertTrue(daemonErrors.isEmpty(), daemonErrors.toString())
        extractionDaemonRollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", extractionDaemonApply.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", extractionDaemonApply.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("rolledBack", extractionDaemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(calculatorOriginal, java.nio.file.Files.readString(calculatorPath))
        val unsafeSelections = linkedMapOf(
            "effectful-prefix" to extractVariableArguments(calculatorOriginal, "return touch() + 6 * 7;", "6 * 7", "unsafe"),
            "short-circuit" to extractVariableArguments(calculatorOriginal, "return enabled && 6 * 7 > 0;", "6 * 7", "unsafe"),
            "null-type" to extractVariableArguments(calculatorOriginal, "return null;", "null", "unsafe"),
            "poly-method-reference" to extractVariableArguments(calculatorOriginal, "return String::new;", "String::new", "unsafe"),
            "partial-ast-range" to extractVariableArguments(calculatorOriginal, "return 6 * 7 + 0;", "6 *", "unsafe"),
        )
        extractionRefusalCodes = unsafeSelections.mapValues { (_, arguments) ->
            val freshHash = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
                put("root", root.toString())
            }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
            context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
                put("expectedSnapshotHash", freshHash)
                put("sdkHome", sdk.toString())
            }).jsonObject
            kotlin.test.assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
            val refusal = kotlin.test.assertFailsWith<org.refactorkit.core.JsonRpcException> {
                session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                    put("operation", "extractVariable")
                    put("languageId", "java")
                    put("expectedSnapshotHash", freshHash)
                    put("javaContext", javaContext())
                    put("arguments", arguments)
                })
            }
            refusal.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content.orEmpty()
        }
        extractionDaemonPartial = kotlinx.serialization.json.buildJsonObject {
            put("status", "REFUSED")
            put("refusalCode", extractionRefusalCodes.getValue("partial-ast-range"))
        }

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scan.toString())
            val preview = mcp.dispatch("tools/call", mcpExtractVariableRequest(fullArguments)).jsonObject
            extractionMcpPreviewText = preview.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            val planId = extractionMcpPreviewText.lineSequence().singleOrNull { it.trimStart().startsWith("Plan ID") }
                ?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)
                ?: error("MCP extractVariable preview did not return a plan ID: $extractionMcpPreviewText")
            val apply = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("planId", planId) })
            }).jsonObject
            extractionMcpApplyText = apply.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(extractionMcpApplyText.startsWith("Applied successfully."), extractionMcpApplyText)
            val transactionId = Regex("Transaction ID: ([^\\s]+)").find(extractionMcpApplyText)?.groupValues?.get(1)
                ?: error("MCP extractVariable Apply did not return its transaction ID: $extractionMcpApplyText")
            kotlin.test.assertEquals(calculatorExpected, java.nio.file.Files.readString(calculatorPath))
            val diagnostics = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "diagnostics")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("languageId", "java"); put("jdkHome", sdk.toString())
                })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            kotlin.test.assertFalse(diagnostics.lineSequence().any { it.startsWith("[ERROR]") }, diagnostics)
            extractionMcpRollbackText = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("transactionId", transactionId) })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(extractionMcpRollbackText.startsWith("Rolled back transaction"), extractionMcpRollbackText)
            kotlin.test.assertEquals(calculatorOriginal, java.nio.file.Files.readString(calculatorPath))
            val partial = mcp.dispatch("tools/call", mcpExtractVariableRequest(unsafeSelections.getValue("effectful-prefix"))).jsonObject
            extractionMcpPartialText = partial.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
        } finally {
            mcp.close()
        }
    }

    @io.cucumber.java.en.Then("the managed extractVariable plan inserts a typed local before return and refuses hazardous selections while rollback restores exact source")
    fun verifyExtractVariableCapabilityAndPreview() {
        kotlin.test.assertEquals("PREVIEW", extractionDaemonPreview.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("extractVariable", extractionDaemonPreview.getValue("operation").jsonPrimitive.content)
        kotlin.test.assertTrue(extractionDaemonPreview.toString().contains("int doubled = 6 * 7"), extractionDaemonPreview.toString())
        kotlin.test.assertTrue(extractionMcpPreviewText.contains("Operation: extractVariable"), extractionMcpPreviewText)
        kotlin.test.assertTrue(extractionMcpPreviewText.contains("Status   : PREVIEW"), extractionMcpPreviewText)
        val capabilities = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
        val javaCapabilityDescriptor = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = javaCapabilityDescriptor.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "extractVariable" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("extractVariable"))
        val descriptor = org.refactorkit.java.JavaAdapterRegistration.create().adapter
            .availableRefactorings(org.refactorkit.core.CodeSelection(org.refactorkit.core.SourceLocation(
                calculatorPath,
                org.refactorkit.core.SourceRange(org.refactorkit.core.SourcePosition(0, 0), org.refactorkit.core.SourcePosition(0, 0)),
            ))).single { it.id == "extractVariable" }
        kotlin.test.assertEquals("extractVariable", descriptor.capability?.operation)
        val daemonPreviewText = extractionDaemonPreview.toString()
        kotlin.test.assertFalse(Regex("(?i)\\\"severity\\\"\\s*:\\s*\\\"error\\\"").containsMatchIn(daemonPreviewText), daemonPreviewText)
        kotlin.test.assertEquals("applied", extractionDaemonApply.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("rolledBack", extractionDaemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("REFUSED", extractionDaemonPartial.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("java.extractVariable.selectionNotExpression",
            extractionDaemonPartial.getValue("refusalCode").jsonPrimitive.content)
        kotlin.test.assertEquals("java.extractVariable.evaluationOrderUnsupported", extractionRefusalCodes.getValue("effectful-prefix"))
        kotlin.test.assertEquals("java.extractVariable.conditionalEvaluationUnsupported", extractionRefusalCodes.getValue("short-circuit"))
        kotlin.test.assertEquals("java.extractVariable.expressionTypeUnsupported", extractionRefusalCodes.getValue("null-type"))
        kotlin.test.assertEquals("java.extractVariable.expressionTypeUnsupported", extractionRefusalCodes.getValue("poly-method-reference"))
        kotlin.test.assertEquals(calculatorOriginal, java.nio.file.Files.readString(calculatorPath))
        kotlin.test.assertTrue(extractionMcpApplyText.startsWith("Applied successfully."))
        kotlin.test.assertTrue(extractionMcpRollbackText.startsWith("Rolled back transaction"))
        kotlin.test.assertTrue(extractionMcpPartialText.contains("java.extractVariable.evaluationOrderUnsupported"), extractionMcpPartialText)
        kotlin.test.assertEquals(calculatorOriginal, java.nio.file.Files.readString(calculatorPath))
        val records = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        kotlin.test.assertEquals(2, records.size)
        kotlin.test.assertTrue(records.all { it.operation == "extractVariable" })
        kotlin.test.assertTrue(records.all { it.state == org.refactorkit.core.JournalState.ROLLED_BACK })
    }

    private fun extractVariableArguments(source: String, anchor: String, selected: String, name: String) = kotlinx.serialization.json.buildJsonObject {
        val anchorStart = source.indexOf(anchor)
        kotlin.test.assertTrue(anchorStart >= 0, "Could not find source anchor: $anchor")
        val startOffset = source.indexOf(selected, anchorStart)
        kotlin.test.assertTrue(startOffset >= anchorStart && startOffset < anchorStart + anchor.length,
            "Could not find selection '$selected' inside anchor '$anchor'")
        val endOffset = startOffset + selected.length
        fun line(offset: Int) = source.substring(0, offset).count { it == '\n' }
        fun column(offset: Int): Int = offset - (source.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1)
        put("file", "src/main/java/com/acme/Calculator.java")
        put("startLine", line(startOffset)); put("startCharacter", column(startOffset))
        put("endLine", line(endOffset)); put("endCharacter", column(endOffset))
        put("name", name)
    }

    private fun mcpExtractVariableRequest(arguments: kotlinx.serialization.json.JsonObject) = kotlinx.serialization.json.buildJsonObject {
        put("name", "preview_refactoring")
        put("arguments", kotlinx.serialization.json.buildJsonObject {
            put("operation", "extractVariable"); put("languageId", "java"); put("arguments", arguments)
        })
    }

    @io.cucumber.java.en.When("I preview apply and roll back extractConstant for a bound integer constant expression through daemon and MCP")
    fun manageExtractConstantThroughEngineSurfaces() {
        val fixtureSource = java.nio.file.Files.readString(calculatorPath)
        extractConstantOriginal = fixtureSource.replace("return input * 2 + 1;", "return input + 2 * 3;")
        kotlin.test.assertNotEquals(fixtureSource, extractConstantOriginal, "The extract-constant fixture source must be installed")
        java.nio.file.Files.writeString(calculatorPath, extractConstantOriginal)
        extractConstantExpected = extractConstantOriginal
            .replace(
                "    public int calculate",
                "    private static final int TRIPLE = 2 * 3;\n\n    public int calculate",
            )
            .replace("return input + 2 * 3;", "return input + com.acme.Calculator.TRIPLE;")

        val opened = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        val initialHash = opened.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", initialHash); put("sdkHome", sdk.toString())
        }).jsonObject
        kotlin.test.assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())

        val line = extractConstantOriginal.lineSequence().elementAt(3)
        val fullStart = line.indexOf("2 * 3")
        kotlin.test.assertTrue(fullStart >= 0, line)
        val fullArguments = extractConstantArguments(fullStart, fullStart + "2 * 3".length)
        extractConstantDaemonPreview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "extractConstant"); put("languageId", "java")
            put("expectedSnapshotHash", initialHash); put("javaContext", javaContext())
            put("arguments", fullArguments)
        }).jsonObject
        kotlin.test.assertEquals("PREVIEW", extractConstantDaemonPreview.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(extractConstantOriginal, java.nio.file.Files.readString(calculatorPath), "Preview must be read-only")
        extractConstantDaemonApply = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", extractConstantDaemonPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", initialHash); put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("applied", extractConstantDaemonApply.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(extractConstantExpected, java.nio.file.Files.readString(calculatorPath))
        kotlin.test.assertTrue(extractConstantDaemonApply.getValue("diagnostics").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
        })
        extractConstantDaemonRollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", extractConstantDaemonApply.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", extractConstantDaemonApply.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("rolledBack", extractConstantDaemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(extractConstantOriginal, java.nio.file.Files.readString(calculatorPath))

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scan.toString())
            val preview = mcp.dispatch("tools/call", mcpExtractConstantRequest(fullArguments)).jsonObject
            extractConstantMcpPreviewText = preview.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            val planId = extractConstantMcpPreviewText.lineSequence().singleOrNull { it.trimStart().startsWith("Plan ID") }
                ?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)
                ?: error("MCP extractConstant preview did not return a plan ID: $extractConstantMcpPreviewText")
            val apply = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("planId", planId) })
            }).jsonObject
            extractConstantMcpApplyText = apply.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(extractConstantMcpApplyText.startsWith("Applied successfully."), extractConstantMcpApplyText)
            val transactionId = Regex("Transaction ID: ([^\\s]+)").find(extractConstantMcpApplyText)?.groupValues?.get(1)
                ?: error("MCP extractConstant Apply did not return its transaction ID: $extractConstantMcpApplyText")
            kotlin.test.assertEquals(extractConstantExpected, java.nio.file.Files.readString(calculatorPath))
            val diagnostics = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "diagnostics")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("languageId", "java"); put("jdkHome", sdk.toString())
                })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            kotlin.test.assertFalse(diagnostics.lineSequence().any { it.startsWith("[ERROR]") }, diagnostics)
            extractConstantMcpRollbackText = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("transactionId", transactionId) })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(extractConstantMcpRollbackText.startsWith("Rolled back transaction"), extractConstantMcpRollbackText)
            kotlin.test.assertEquals(extractConstantOriginal, java.nio.file.Files.readString(calculatorPath))

            val partialStart = line.indexOf("input")
            val partialArguments = extractConstantArguments(partialStart, partialStart + "input".length)
            val partial = mcp.dispatch("tools/call", mcpExtractConstantRequest(partialArguments)).jsonObject
            extractConstantMcpRefusalText = partial.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
        } finally {
            mcp.close()
        }

        val partialStart = line.indexOf("input")
        val partialArguments = extractConstantArguments(partialStart, partialStart + "input".length)
        val currentHash = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", currentHash); put("sdkHome", sdk.toString())
        }).jsonObject
        val refusal = kotlin.test.assertFailsWith<org.refactorkit.core.JsonRpcException> {
            session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "extractConstant"); put("languageId", "java")
                put("expectedSnapshotHash", currentHash); put("javaContext", javaContext())
                put("arguments", partialArguments)
            })
        }
        extractConstantDaemonRefusalCode = refusal.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content.orEmpty()
    }

    @io.cucumber.java.en.Then("extractConstant is advertised as stable managed Java mutation and stages a bound static final constant")
    fun verifyExtractConstantCapabilityAndPreview() {
        kotlin.test.assertEquals("PREVIEW", extractConstantDaemonPreview.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("extractConstant", extractConstantDaemonPreview.getValue("operation").jsonPrimitive.content)
        val rendered = extractConstantDaemonPreview.getValue("renderedDiff").jsonPrimitive.content
        kotlin.test.assertTrue(rendered.contains("private static final int TRIPLE = 2 * 3;"), rendered)
        kotlin.test.assertTrue(rendered.contains("com.acme.Calculator.TRIPLE"), rendered)
        kotlin.test.assertTrue(extractConstantMcpPreviewText.contains("Operation: extractConstant"), extractConstantMcpPreviewText)
        kotlin.test.assertTrue(extractConstantMcpPreviewText.contains("Status   : PREVIEW"), extractConstantMcpPreviewText)
        val capabilities = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
        val java = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "extractConstant" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("extractConstant"))
        val descriptor = org.refactorkit.java.JavaAdapterRegistration.create().adapter
            .availableRefactorings(org.refactorkit.core.CodeSelection(org.refactorkit.core.SourceLocation(
                calculatorPath,
                org.refactorkit.core.SourceRange(org.refactorkit.core.SourcePosition(0, 0), org.refactorkit.core.SourcePosition(0, 0)),
            ))).single { it.id == "extractConstant" }
        kotlin.test.assertEquals("extractConstant", descriptor.capability?.operation)
    }

    @io.cucumber.java.en.And("nonconstant expressions refuse before managed writes while both transactions restore exact source bytes")
    fun verifyExtractConstantRefusalAndRollback() {
        kotlin.test.assertEquals("applied", extractConstantDaemonApply.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("rolledBack", extractConstantDaemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("java.extractConstant.notCompileTimeConstant", extractConstantDaemonRefusalCode)
        kotlin.test.assertTrue(extractConstantMcpApplyText.startsWith("Applied successfully."))
        kotlin.test.assertTrue(extractConstantMcpRollbackText.startsWith("Rolled back transaction"))
        kotlin.test.assertTrue(extractConstantMcpRefusalText.contains("java.extractConstant.notCompileTimeConstant"), extractConstantMcpRefusalText)
        kotlin.test.assertEquals(extractConstantOriginal, java.nio.file.Files.readString(calculatorPath))
        val records = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        kotlin.test.assertEquals(2, records.size)
        kotlin.test.assertTrue(records.all { it.operation == "extractConstant" })
        kotlin.test.assertTrue(records.all { it.state == org.refactorkit.core.JournalState.ROLLED_BACK })
    }

    @io.cucumber.java.en.When("I preview apply and roll back inlineConstant for a bound private static final integer literal through daemon and MCP")
    fun manageInlineConstantThroughEngineSurfaces() {
        inlineConstantOriginal = """
            package com.acme;
            public class Calculator {
                private static final int OFFSET = 7;

                public int calculate(int input) {
                    return input + OFFSET;
                }

                private static final class Nested {
                    private int add(int OFFSET) {
                        return OFFSET + Calculator.OFFSET;
                    }
                }

                private static String label() { return "OFFSET"; }
            }
        """.trimIndent() + "\n"
        java.nio.file.Files.writeString(calculatorPath, inlineConstantOriginal)
        inlineConstantExpected = inlineConstantOriginal
            .replace("    private static final int OFFSET = 7;\n", "")
            .replace("return input + OFFSET;", "return input + 7;")
            .replace("return OFFSET + Calculator.OFFSET;", "return OFFSET + 7;")

        val opened = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        val initialHash = opened.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", initialHash); put("sdkHome", sdk.toString())
        }).jsonObject
        inlineConstantDaemonPreview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "inlineConstant"); put("languageId", "java")
            put("expectedSnapshotHash", initialHash); put("javaContext", javaContext())
            put("arguments", inlineConstantArguments())
        }).jsonObject
        kotlin.test.assertEquals("PREVIEW", inlineConstantDaemonPreview.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(inlineConstantOriginal, java.nio.file.Files.readString(calculatorPath), "Preview must be read-only")
        inlineConstantDaemonApply = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", inlineConstantDaemonPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", initialHash); put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("applied", inlineConstantDaemonApply.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(inlineConstantExpected, java.nio.file.Files.readString(calculatorPath))
        kotlin.test.assertTrue(inlineConstantDaemonApply.getValue("diagnostics").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
        })
        inlineConstantDaemonRollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", inlineConstantDaemonApply.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", inlineConstantDaemonApply.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("rolledBack", inlineConstantDaemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(inlineConstantOriginal, java.nio.file.Files.readString(calculatorPath))

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scan.toString())
            val contextResult = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "java_project_context")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("sdkHome", sdk.toString()) })
            }).jsonObject
            val mcpContextText = contextResult.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            val mcpContext = kotlinx.serialization.json.Json.parseToJsonElement(mcpContextText)
                .jsonObject.getValue("javaContext").jsonObject
            val preview = mcp.dispatch("tools/call", mcpInlineConstantRequest(mcpContext)).jsonObject
            inlineConstantMcpPreviewText = preview.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            val planId = inlineConstantMcpPreviewText.lineSequence().singleOrNull { it.trimStart().startsWith("Plan ID") }
                ?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)
                ?: error("MCP inlineConstant preview did not return a plan ID: $inlineConstantMcpPreviewText")
            val apply = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("planId", planId); put("javaContext", mcpContext)
                })
            }).jsonObject
            inlineConstantMcpApplyText = apply.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(inlineConstantMcpApplyText.startsWith("Applied successfully."), inlineConstantMcpApplyText)
            val transactionId = Regex("Transaction ID: ([^\\s]+)").find(inlineConstantMcpApplyText)?.groupValues?.get(1)
                ?: error("MCP inlineConstant Apply did not return its transaction ID: $inlineConstantMcpApplyText")
            kotlin.test.assertEquals(inlineConstantExpected, java.nio.file.Files.readString(calculatorPath))
            inlineConstantMcpRollbackText = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("transactionId", transactionId); put("javaContext", mcpContext)
                })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(inlineConstantMcpRollbackText.startsWith("Rolled back transaction"), inlineConstantMcpRollbackText)
            kotlin.test.assertEquals(inlineConstantOriginal, java.nio.file.Files.readString(calculatorPath))
        } finally {
            mcp.close()
        }

        val refusalCodes = linkedMapOf<String, String>()
        val nonLiteral = inlineConstantOriginal
            .replace("private static final int OFFSET = 7;", "private static final int OFFSET = readOffset();")
            .replace("    private static final class Nested", "    private static int readOffset() { return 7; }\n\n    private static final class Nested")
        java.nio.file.Files.writeString(calculatorPath, nonLiteral)
        val nonLiteralHash = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", nonLiteralHash); put("sdkHome", sdk.toString())
        }).jsonObject
        try {
            val refused = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "inlineConstant"); put("languageId", "java")
                put("expectedSnapshotHash", nonLiteralHash); put("javaContext", javaContext())
                put("arguments", inlineConstantArguments())
            }).jsonObject
            refusalCodes["nonLiteral"] = refused["refusalCode"]?.jsonPrimitive?.content.orEmpty()
        } catch (failure: org.refactorkit.core.JsonRpcException) {
            refusalCodes["nonLiteral"] = failure.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content.orEmpty()
        }
        kotlin.test.assertEquals(nonLiteral, java.nio.file.Files.readString(calculatorPath))

        val mcpRefusal = org.refactorkit.mcp.McpSession()
        try {
            mcpRefusal.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            })
            val mcpContextResult = mcpRefusal.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "java_project_context")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("sdkHome", sdk.toString()) })
            }).jsonObject
            val currentMcpContext = kotlinx.serialization.json.Json.parseToJsonElement(
                mcpContextResult.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
            ).jsonObject.getValue("javaContext").jsonObject
            inlineConstantMcpRefusalText = mcpRefusal.dispatch("tools/call", mcpInlineConstantRequest(currentMcpContext))
                .jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        } finally {
            mcpRefusal.close()
        }
        kotlin.test.assertEquals(nonLiteral, java.nio.file.Files.readString(calculatorPath))

        val mutable = inlineConstantOriginal.replace("private static final int OFFSET = 7;", "private static int OFFSET = 7;")
        java.nio.file.Files.writeString(calculatorPath, mutable)
        val mutableHash = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", mutableHash); put("sdkHome", sdk.toString())
        }).jsonObject
        try {
            val refused = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "inlineConstant"); put("languageId", "java")
                put("expectedSnapshotHash", mutableHash); put("javaContext", javaContext())
                put("arguments", inlineConstantArguments())
            }).jsonObject
            refusalCodes["mutable"] = refused["refusalCode"]?.jsonPrimitive?.content.orEmpty()
        } catch (failure: org.refactorkit.core.JsonRpcException) {
            refusalCodes["mutable"] = failure.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content.orEmpty()
        }
        inlineConstantDaemonRefusalCodes = refusalCodes
        kotlin.test.assertEquals(mutable, java.nio.file.Files.readString(calculatorPath))
        java.nio.file.Files.writeString(calculatorPath, inlineConstantOriginal)
    }

    @io.cucumber.java.en.Then("inlineConstant is advertised as stable managed Java mutation and replaces only its bound literal reads")
    fun verifyInlineConstantCapabilityAndBinding() {
        kotlin.test.assertEquals("PREVIEW", inlineConstantDaemonPreview.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("inlineConstant", inlineConstantDaemonPreview.getValue("operation").jsonPrimitive.content)
        val rendered = inlineConstantDaemonPreview.getValue("renderedDiff").jsonPrimitive.content
        kotlin.test.assertTrue(rendered.contains("return input + 7;"), rendered)
        kotlin.test.assertTrue(rendered.contains("return OFFSET + 7;"), rendered)
        kotlin.test.assertFalse(rendered.contains("return 7 + 7;"), rendered)
        kotlin.test.assertTrue(inlineConstantMcpPreviewText.contains("Operation: inlineConstant"), inlineConstantMcpPreviewText)
        kotlin.test.assertTrue(inlineConstantMcpPreviewText.contains("Status   : PREVIEW"), inlineConstantMcpPreviewText)
        val capabilities = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
        val java = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "inlineConstant" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("inlineConstant"))
        kotlin.test.assertTrue(org.refactorkit.java.JavaAdapterRegistration.create().adapter
            .availableRefactorings(org.refactorkit.core.CodeSelection(org.refactorkit.core.SourceLocation(
                calculatorPath,
                org.refactorkit.core.SourceRange(org.refactorkit.core.SourcePosition(0, 0), org.refactorkit.core.SourcePosition(0, 0)),
            ))).any { it.id == "inlineConstant" && it.capability?.operation == "inlineConstant" })
    }

    @io.cucumber.java.en.And("non-literal or mutable fields refuse before managed writes while both surfaces restore exact source bytes")
    fun verifyInlineConstantRefusalAndRollback() {
        kotlin.test.assertEquals("applied", inlineConstantDaemonApply.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("rolledBack", inlineConstantDaemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertTrue(inlineConstantDaemonRefusalCodes["nonLiteral"].orEmpty().startsWith("java.inlineConstant."), inlineConstantDaemonRefusalCodes.toString())
        kotlin.test.assertTrue(inlineConstantDaemonRefusalCodes["mutable"].orEmpty().startsWith("java.inlineConstant."), inlineConstantDaemonRefusalCodes.toString())
        kotlin.test.assertTrue(inlineConstantMcpApplyText.startsWith("Applied successfully."), inlineConstantMcpApplyText)
        kotlin.test.assertTrue(inlineConstantMcpRollbackText.startsWith("Rolled back transaction"), inlineConstantMcpRollbackText)
        kotlin.test.assertTrue(inlineConstantMcpRefusalText.contains("java.inlineConstant."), inlineConstantMcpRefusalText)
        kotlin.test.assertEquals(inlineConstantOriginal, java.nio.file.Files.readString(calculatorPath))
        val records = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        kotlin.test.assertEquals(2, records.size)
        kotlin.test.assertTrue(records.all { it.operation == "inlineConstant" })
        kotlin.test.assertTrue(records.all { it.state == org.refactorkit.core.JournalState.ROLLED_BACK })
    }

    @io.cucumber.java.en.When("I exercise extractField for expression shape {string} through daemon and MCP")
    fun exerciseExtractField(shape: String) {
        kotlin.test.assertTrue(shape in setOf("compile-time-int", "parameter-int"), shape)
        extractFieldShape = shape
        val originalFixture = java.nio.file.Files.readString(calculatorPath)
        val selectedExpression = if (shape == "compile-time-int") "3 + 4" else "input + 4"
        extractFieldOriginal = originalFixture.replace("return input * 2 + 1;", "return $selectedExpression;")
        kotlin.test.assertNotEquals(originalFixture, extractFieldOriginal)
        java.nio.file.Files.writeString(calculatorPath, extractFieldOriginal)

        val opened = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        val snapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", snapshotHash); put("sdkHome", sdk.toString())
        }).jsonObject
        kotlin.test.assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        val line = extractFieldOriginal.lineSequence().elementAt(3)
        val selectionStart = line.indexOf(selectedExpression)
        kotlin.test.assertTrue(selectionStart >= 0, line)
        val arguments = extractFieldArguments(selectionStart, selectionStart + selectedExpression.length)
        try {
            extractFieldDaemonPreview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "extractField"); put("languageId", "java")
                put("expectedSnapshotHash", snapshotHash); put("javaContext", javaContext())
                put("arguments", arguments)
            }).jsonObject
            if (extractFieldDaemonPreview["status"]?.jsonPrimitive?.content == "REFUSED") {
                extractFieldDaemonRefusalCode = extractFieldDaemonPreview["refusalCode"]?.jsonPrimitive?.content.orEmpty()
            }
        } catch (refusal: org.refactorkit.core.JsonRpcException) {
            extractFieldDaemonRefusalCode = refusal.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content.orEmpty()
            extractFieldDaemonPreview = kotlinx.serialization.json.buildJsonObject { put("status", "REFUSED") }
        }
        if (shape == "compile-time-int") {
            kotlin.test.assertEquals("PREVIEW", extractFieldDaemonPreview.getValue("status").jsonPrimitive.content)
            kotlin.test.assertEquals(extractFieldOriginal, java.nio.file.Files.readString(calculatorPath))
            extractFieldDaemonApply = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
                put("planId", extractFieldDaemonPreview.getValue("planId").jsonPrimitive.content)
                put("expectedSnapshotHash", snapshotHash); put("javaContext", javaContext())
            }).jsonObject
            kotlin.test.assertEquals("applied", extractFieldDaemonApply.getValue("status").jsonPrimitive.content)
            kotlin.test.assertTrue(extractFieldDaemonApply.getValue("diagnostics").jsonArray.none {
                it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
            })
            val rendered = extractFieldDaemonPreview.getValue("renderedDiff").jsonPrimitive.content
            kotlin.test.assertTrue(rendered.contains("private final int derived = 3 + 4;"), rendered)
            kotlin.test.assertTrue(rendered.contains("this.derived"), rendered)
            extractFieldDaemonRollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
                put("transactionId", extractFieldDaemonApply.getValue("transactionId").jsonPrimitive.content)
                put("expectedSnapshotHash", extractFieldDaemonApply.getValue("snapshotHash").jsonPrimitive.content)
                put("javaContext", javaContext())
            }).jsonObject
            kotlin.test.assertEquals("rolledBack", extractFieldDaemonRollback.getValue("status").jsonPrimitive.content)
            kotlin.test.assertEquals(extractFieldOriginal, java.nio.file.Files.readString(calculatorPath))
        } else {
            kotlin.test.assertEquals("REFUSED", extractFieldDaemonPreview.getValue("status").jsonPrimitive.content)
            kotlin.test.assertEquals("java.extractField.notCompileTimeConstant", extractFieldDaemonRefusalCode)
            kotlin.test.assertEquals(extractFieldOriginal, java.nio.file.Files.readString(calculatorPath))
        }

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scan.toString())
            val contextResult = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "java_project_context")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("sdkHome", sdk.toString()) })
            }).jsonObject
            val contextText = contextResult.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            extractFieldMcpJavaContext = kotlinx.serialization.json.Json.parseToJsonElement(contextText)
                .jsonObject.getValue("javaContext").jsonObject
            val preview = mcp.dispatch("tools/call", mcpExtractFieldRequest(arguments, extractFieldMcpJavaContext)).jsonObject
            extractFieldMcpPreviewText = preview.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            if (shape == "compile-time-int") {
                kotlin.test.assertTrue(extractFieldMcpPreviewText.contains("Status   : PREVIEW"), extractFieldMcpPreviewText)
                val planId = extractFieldMcpPreviewText.lineSequence().singleOrNull { it.trimStart().startsWith("Plan ID") }
                    ?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)
                    ?: error("MCP extractField preview did not return a plan ID: $extractFieldMcpPreviewText")
                val apply = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                    put("name", "apply_refactoring")
                    put("arguments", kotlinx.serialization.json.buildJsonObject {
                        put("planId", planId); put("javaContext", extractFieldMcpJavaContext)
                    })
                }).jsonObject
                extractFieldMcpApplyText = apply.getValue("content").jsonArray.single().jsonObject
                    .getValue("text").jsonPrimitive.content
                kotlin.test.assertTrue(extractFieldMcpApplyText.startsWith("Applied successfully."), extractFieldMcpApplyText)
                val transactionId = Regex("Transaction ID: ([^\\s]+)").find(extractFieldMcpApplyText)?.groupValues?.get(1)
                    ?: error("MCP extractField Apply did not return its transaction ID: $extractFieldMcpApplyText")
                kotlin.test.assertTrue(java.nio.file.Files.readString(calculatorPath).contains("private final int derived = 3 + 4;"))
                extractFieldMcpRollbackText = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                    put("name", "rollback_refactoring")
                    put("arguments", kotlinx.serialization.json.buildJsonObject {
                        put("transactionId", transactionId); put("javaContext", extractFieldMcpJavaContext)
                    })
                }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
                kotlin.test.assertTrue(extractFieldMcpRollbackText.startsWith("Rolled back transaction"), extractFieldMcpRollbackText)
                kotlin.test.assertEquals(extractFieldOriginal, java.nio.file.Files.readString(calculatorPath))
            } else {
                extractFieldMcpRefusalText = extractFieldMcpPreviewText
                kotlin.test.assertTrue(extractFieldMcpRefusalText.contains("java.extractField.notCompileTimeConstant"), extractFieldMcpRefusalText)
                kotlin.test.assertEquals(extractFieldOriginal, java.nio.file.Files.readString(calculatorPath))
            }
        } finally {
            mcp.close()
        }
    }

    @io.cucumber.java.en.Then("extractField results are {string} and managed rollback restores exact source")
    fun verifyExtractFieldManagedOutcome(outcome: String) {
        if (outcome == "applied") {
            kotlin.test.assertEquals("applied", extractFieldDaemonApply.getValue("status").jsonPrimitive.content)
            kotlin.test.assertEquals("rolledBack", extractFieldDaemonRollback.getValue("status").jsonPrimitive.content)
            kotlin.test.assertTrue(extractFieldMcpApplyText.startsWith("Applied successfully."), extractFieldMcpApplyText)
            kotlin.test.assertTrue(extractFieldMcpRollbackText.startsWith("Rolled back transaction"), extractFieldMcpRollbackText)
        } else {
            kotlin.test.assertEquals("REFUSED", extractFieldDaemonPreview.getValue("status").jsonPrimitive.content)
            kotlin.test.assertTrue(extractFieldMcpRefusalText.contains("java.extractField.notCompileTimeConstant"), extractFieldMcpRefusalText)
        }
        kotlin.test.assertEquals(extractFieldOriginal, java.nio.file.Files.readString(calculatorPath))
        val records = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        kotlin.test.assertEquals(if (outcome == "applied") 2 else 0, records.size)
        if (outcome == "applied") {
            kotlin.test.assertTrue(records.all { it.operation == "extractField" })
            kotlin.test.assertTrue(records.all { it.state == org.refactorkit.core.JournalState.ROLLED_BACK })
        }
    }

    @io.cucumber.java.en.And("extractField is stable and instance-field previews use private final semantics")
    fun verifyExtractFieldCapability() {
        val java = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
            .getValue("languageKernel").jsonObject.getValue("adapters").jsonArray.map { it.jsonObject }
            .single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "extractField" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        val descriptor = org.refactorkit.java.JavaAdapterRegistration.create().adapter.availableRefactorings(
            org.refactorkit.core.CodeSelection(org.refactorkit.core.SourceLocation(
                calculatorPath,
                org.refactorkit.core.SourceRange(org.refactorkit.core.SourcePosition(0, 0), org.refactorkit.core.SourcePosition(0, 0)),
            )),
        ).single { it.id == "extractField" }
        kotlin.test.assertEquals("extractField", descriptor.capability?.operation)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("extractField"))
        if (extractFieldShape == "compile-time-int") {
            val diff = extractFieldDaemonPreview.getValue("renderedDiff").jsonPrimitive.content
            kotlin.test.assertTrue(diff.contains("private final int derived = 3 + 4;"), diff)
            kotlin.test.assertTrue(diff.contains("this.derived"), diff)
            kotlin.test.assertTrue(extractFieldMcpPreviewText.contains("Operation: extractField"), extractFieldMcpPreviewText)
        }
    }

    @io.cucumber.java.en.When("I install a final-field client fixture and exercise encapsulateField through daemon and MCP")
    fun exerciseEncapsulateField() {
        encapsulateFieldOriginalService = """
            package com.acme;
            public class Service {
                public final int code = 41;
                public int doubled() { return code * 2; }
            }
        """.trimIndent() + "\n"
        encapsulateFieldOriginalClient = """
            package com.acme;
            public class Client {
                public int read(Service service) { return service.code; }
            }
        """.trimIndent() + "\n"
        encapsulateFieldExpectedService = """
            package com.acme;
            public class Service {
                private final int code = 41;
                public int doubled() { return code * 2; }

                public int getCode() { return this.code; }
            }
        """.trimIndent() + "\n"
        encapsulateFieldExpectedClient = """
            package com.acme;
            public class Client {
                public int read(Service service) { return service.getCode(); }
            }
        """.trimIndent() + "\n"
        servicePath = root.resolve("src/main/java/com/acme/Service.java")
        write("src/main/java/com/acme/Service.java", encapsulateFieldOriginalService)
        write("src/main/java/com/acme/Client.java", encapsulateFieldOriginalClient)
        val opened = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        val snapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", snapshotHash); put("sdkHome", sdk.toString())
        }).jsonObject
        kotlin.test.assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())

        encapsulateFieldDaemonPreview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "encapsulateField"); put("languageId", "java")
            put("symbol", "com.acme.Service#code")
            put("expectedSnapshotHash", snapshotHash); put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("PREVIEW", encapsulateFieldDaemonPreview.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(encapsulateFieldOriginalService, java.nio.file.Files.readString(servicePath))
        kotlin.test.assertEquals(encapsulateFieldOriginalClient,
            java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/Client.java")))
        val rendered = encapsulateFieldDaemonPreview.getValue("renderedDiff").jsonPrimitive.content
        kotlin.test.assertTrue(rendered.contains("private final int code = 41;"), rendered)
        kotlin.test.assertTrue(rendered.contains("public int getCode() { return this.code; }"), rendered)
        kotlin.test.assertTrue(rendered.contains("service.getCode()"), rendered)

        encapsulateFieldDaemonApply = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", encapsulateFieldDaemonPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", snapshotHash); put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("applied", encapsulateFieldDaemonApply.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(encapsulateFieldExpectedService, java.nio.file.Files.readString(servicePath))
        kotlin.test.assertEquals(encapsulateFieldExpectedClient,
            java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/Client.java")))
        kotlin.test.assertTrue(encapsulateFieldDaemonApply.getValue("diagnostics").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
        })
        encapsulateFieldDaemonRollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", encapsulateFieldDaemonApply.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", encapsulateFieldDaemonApply.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("rolledBack", encapsulateFieldDaemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(encapsulateFieldOriginalService, java.nio.file.Files.readString(servicePath))
        kotlin.test.assertEquals(encapsulateFieldOriginalClient,
            java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/Client.java")))

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scan.toString())
            val contextResult = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "java_project_context")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("sdkHome", sdk.toString()) })
            }).jsonObject
            val contextText = contextResult.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            encapsulateFieldMcpContext = kotlinx.serialization.json.Json.parseToJsonElement(contextText)
                .jsonObject.getValue("javaContext").jsonObject
            val preview = mcp.dispatch("tools/call", mcpEncapsulateFieldRequest(encapsulateFieldMcpContext)).jsonObject
            encapsulateFieldMcpPreviewText = preview.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(encapsulateFieldMcpPreviewText.contains("Status   : PREVIEW"), encapsulateFieldMcpPreviewText)
            kotlin.test.assertTrue(encapsulateFieldMcpPreviewText.contains("update 1 bound workspace read"), encapsulateFieldMcpPreviewText)
            val planId = encapsulateFieldMcpPreviewText.lineSequence().singleOrNull { it.trimStart().startsWith("Plan ID") }
                ?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)
                ?: error("MCP encapsulateField preview did not return a plan ID: $encapsulateFieldMcpPreviewText")
            val apply = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("planId", planId); put("javaContext", encapsulateFieldMcpContext)
                })
            }).jsonObject
            encapsulateFieldMcpApplyText = apply.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(encapsulateFieldMcpApplyText.startsWith("Applied successfully."), encapsulateFieldMcpApplyText)
            kotlin.test.assertEquals(encapsulateFieldExpectedService, java.nio.file.Files.readString(servicePath))
            kotlin.test.assertEquals(encapsulateFieldExpectedClient,
                java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/Client.java")))
            val transactionId = Regex("Transaction ID: ([^\\s]+)").find(encapsulateFieldMcpApplyText)?.groupValues?.get(1)
                ?: error("MCP encapsulateField Apply did not return a transaction ID: $encapsulateFieldMcpApplyText")
            encapsulateFieldMcpRollbackText = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("transactionId", transactionId); put("javaContext", encapsulateFieldMcpContext)
                })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(encapsulateFieldMcpRollbackText.startsWith("Rolled back transaction"), encapsulateFieldMcpRollbackText)
            kotlin.test.assertEquals(encapsulateFieldOriginalService, java.nio.file.Files.readString(servicePath))
            kotlin.test.assertEquals(encapsulateFieldOriginalClient,
                java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/Client.java")))
        } finally {
            mcp.close()
        }
    }

    @io.cucumber.java.en.When("I exercise additional resolved field types for encapsulateField through managed Apply")
    fun exerciseEncapsulateFieldAdditionalTypes() {
        val shapes = listOf(
            Triple("String", "\"Ada\"", "String"),
            Triple("java.util.List<String>", "java.util.Collections.emptyList()", "java.util.List<String>"),
            Triple("int[]", "new int[] { 1, 2 }", "int[]"),
        )
        for ((type, initializer, clientType) in shapes) {
            val service = """
                package com.acme;
                public class Service {
                    public final $type value = $initializer;
                    public $type internalRead() { return this.value; }
                }
            """.trimIndent() + "\n"
            val client = """
                package com.acme;
                public class Client {
                    public $clientType read(Service service) { return service.value; }
                }
            """.trimIndent() + "\n"
            write("src/main/java/com/acme/Service.java", service)
            write("src/main/java/com/acme/Client.java", client)
            val opened = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
                put("root", root.toString())
            }).jsonObject
            val snapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
            context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
                put("expectedSnapshotHash", snapshotHash); put("sdkHome", sdk.toString())
            }).jsonObject
            kotlin.test.assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())

            val preview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "encapsulateField"); put("languageId", "java")
                put("symbol", "com.acme.Service#value")
                put("expectedSnapshotHash", snapshotHash); put("javaContext", javaContext())
            }).jsonObject
            kotlin.test.assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content,
                "$type: ${preview.getValue("summary").jsonPrimitive.content}")
            kotlin.test.assertEquals(service, java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/Service.java")))
            kotlin.test.assertEquals(client, java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/Client.java")))
            val rendered = preview.getValue("renderedDiff").jsonPrimitive.content
            kotlin.test.assertTrue(rendered.contains("public $type getValue() { return this.value; }"), "$type: $rendered")
            kotlin.test.assertTrue(rendered.contains("service.getValue()"), "$type: $rendered")

            val apply = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
                put("planId", preview.getValue("planId").jsonPrimitive.content)
                put("expectedSnapshotHash", snapshotHash); put("javaContext", javaContext())
            }).jsonObject
            kotlin.test.assertEquals("applied", apply.getValue("status").jsonPrimitive.content, "$type: $apply")
            kotlin.test.assertTrue(apply.getValue("diagnostics").jsonArray.none {
                it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
            }, "$type: ${apply.getValue("diagnostics")}")
            kotlin.test.assertTrue(java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/Service.java"))
                .contains("private final $type value"))
            kotlin.test.assertTrue(java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/Client.java"))
                .contains("service.getValue()"))

            val rollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
                put("transactionId", apply.getValue("transactionId").jsonPrimitive.content)
                put("expectedSnapshotHash", apply.getValue("snapshotHash").jsonPrimitive.content)
                put("javaContext", javaContext())
            }).jsonObject
            kotlin.test.assertEquals("rolledBack", rollback.getValue("status").jsonPrimitive.content, "$type: $rollback")
            kotlin.test.assertEquals(service, java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/Service.java")))
            kotlin.test.assertEquals(client, java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/Client.java")))
        }
        encapsulateFieldAdditionalTypes = shapes.map { it.first }.toSet()
        val capability = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
            .getValue("languageKernel").jsonObject.getValue("adapters").jsonArray.map { it.jsonObject }
            .single { it.getValue("languageId").jsonPrimitive.content == "java" }
            .getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "encapsulateField" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)

        val refusalShapes = listOf(
            Triple("annotated", "@Deprecated public final int value = 1;", "java.encapsulateField.annotatedFieldUnsupported"),
            Triple("name-side-dimensions", "public final int value[] = { 1 };", "java.encapsulateField.fieldDimensionsUnsupported"),
        )
        for ((name, declaration, expectedCode) in refusalShapes) {
            val service = "package com.acme; public class Service { $declaration }\n"
            val client = "package com.acme; public class Client { public Object read(Service service) { return service.value; } }\n"
            write("src/main/java/com/acme/Service.java", service)
            write("src/main/java/com/acme/Client.java", client)
            val opened = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
                put("root", root.toString())
            }).jsonObject
            val hash = opened.getValue("snapshotHash").jsonPrimitive.content
            context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
                put("expectedSnapshotHash", hash); put("sdkHome", sdk.toString())
            }).jsonObject
            val recordsBefore = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size
            val refusal = kotlin.test.assertFailsWith<org.refactorkit.core.JsonRpcException>("$name must be refused") {
                session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                    put("operation", "encapsulateField"); put("languageId", "java")
                    put("symbol", "com.acme.Service#value")
                    put("expectedSnapshotHash", hash); put("javaContext", javaContext())
                })
            }
            kotlin.test.assertEquals(org.refactorkit.core.JsonRpcErrorCodes.PLAN_REFUSED, refusal.code, "$name: ${refusal.message}")
            val refusalCode = refusal.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content.orEmpty()
            encapsulateFieldAdditionalRefusals[name] = refusalCode
            kotlin.test.assertEquals(expectedCode, refusalCode, "$name: ${refusal.message}")
            kotlin.test.assertEquals(service, java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/Service.java")))
            kotlin.test.assertEquals(client, java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/Client.java")))
            kotlin.test.assertEquals(recordsBefore,
                org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size,
                "$name refusal must not create a transaction")
        }
    }

    @io.cucumber.java.en.Then("String, parameterized reference, and array fields preserve getter bindings and rollback exactly, while annotations and post-name dimensions refuse without edits")
    fun verifyEncapsulateFieldAdditionalTypes() {
        kotlin.test.assertEquals(setOf("String", "java.util.List<String>", "int[]"), encapsulateFieldAdditionalTypes)
        kotlin.test.assertEquals(
            mapOf(
                "annotated" to "java.encapsulateField.annotatedFieldUnsupported",
                "name-side-dimensions" to "java.encapsulateField.fieldDimensionsUnsupported",
            ),
            encapsulateFieldAdditionalRefusals,
        )
    }

    @io.cucumber.java.en.Then("the field becomes private and bound reads use its public getter in both surfaces")
    fun verifyEncapsulateFieldManagedResult() {
        kotlin.test.assertTrue(encapsulateFieldDaemonPreview.getValue("warnings").jsonArray.any {
            it.jsonPrimitive.content.contains("external") || it.jsonPrimitive.content.contains("reflection")
        }, encapsulateFieldDaemonPreview.toString())
        kotlin.test.assertEquals("rolledBack", encapsulateFieldDaemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertTrue(encapsulateFieldMcpPreviewText.contains("Operation: encapsulateField"), encapsulateFieldMcpPreviewText)
        kotlin.test.assertTrue(encapsulateFieldMcpApplyText.startsWith("Applied successfully."), encapsulateFieldMcpApplyText)
        kotlin.test.assertTrue(encapsulateFieldMcpRollbackText.startsWith("Rolled back transaction"), encapsulateFieldMcpRollbackText)
        val java = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
            .getValue("languageKernel").jsonObject.getValue("adapters").jsonArray.map { it.jsonObject }
            .single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "encapsulateField" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("encapsulateField"))
        val descriptor = org.refactorkit.java.JavaAdapterRegistration.create().adapter.availableRefactorings(
            org.refactorkit.core.CodeSelection(org.refactorkit.core.SourceLocation(
                servicePath,
                org.refactorkit.core.SourceRange(org.refactorkit.core.SourcePosition(0, 0), org.refactorkit.core.SourcePosition(0, 0)),
            )),
        ).single { it.id == "encapsulateField" }
        kotlin.test.assertEquals("encapsulateField", descriptor.capability?.operation)
    }

    @io.cucumber.java.en.And("mutable, static, and unresolved field shapes refuse without workspace edits or transactions")
    fun verifyEncapsulateFieldRefusals() {
        for (shape in listOf("mutable", "static", "unresolved")) {
            val service = when (shape) {
                "mutable" -> """
                    package com.acme;
                    public class Service { public int code = 41; }
                """.trimIndent() + "\n"
                "static" -> """
                    package com.acme;
                    public class Service { public static final int code = 41; }
                """.trimIndent() + "\n"
                else -> """
                    package com.acme;
                    public class Service { public final Missing code = null; }
                """.trimIndent() + "\n"
            }
            val client = """
                package com.acme;
                public class Client { public Object read(Service service) { return service.code; } }
            """.trimIndent() + "\n"
            write("src/main/java/com/acme/Service.java", service)
            write("src/main/java/com/acme/Client.java", client)
            val opened = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
                put("root", root.toString())
            }).jsonObject
            val hash = opened.getValue("snapshotHash").jsonPrimitive.content
            context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
                put("expectedSnapshotHash", hash); put("sdkHome", sdk.toString())
            }).jsonObject
            val refusal = kotlin.test.assertFailsWith<org.refactorkit.core.JsonRpcException>("$shape must be refused") {
                session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                    put("operation", "encapsulateField"); put("languageId", "java")
                    put("symbol", "com.acme.Service#code")
                    put("expectedSnapshotHash", hash); put("javaContext", javaContext())
                })
            }
            kotlin.test.assertEquals(org.refactorkit.core.JsonRpcErrorCodes.PLAN_REFUSED, refusal.code, "$shape: ${refusal.message}")
            encapsulateFieldRefusals[shape] = refusal.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content.orEmpty()
            kotlin.test.assertTrue(encapsulateFieldRefusals.getValue(shape).startsWith("java.encapsulateField."), "$shape: $refusal")
            kotlin.test.assertEquals(service, java.nio.file.Files.readString(servicePath))
            kotlin.test.assertEquals(client, java.nio.file.Files.readString(root.resolve("src/main/java/com/acme/Client.java")))
            val records = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
            kotlin.test.assertEquals(2, records.size, "$shape refusal must not create a transaction")
            kotlin.test.assertTrue(records.all { it.state == org.refactorkit.core.JournalState.ROLLED_BACK })
        }
        kotlin.test.assertEquals(setOf("mutable", "static", "unresolved"), encapsulateFieldRefusals.keys)
    }

    private fun mcpEncapsulateFieldRequest(javaContext: kotlinx.serialization.json.JsonObject) =
        kotlinx.serialization.json.buildJsonObject {
            put("name", "preview_refactoring")
            put("arguments", kotlinx.serialization.json.buildJsonObject {
                put("operation", "encapsulateField"); put("languageId", "java")
                put("symbol", "com.acme.Service#code"); put("javaContext", javaContext)
            })
        }

    @io.cucumber.java.en.When("I preview apply and roll back extractParameter for a bound integer return expression through daemon and MCP")
    fun manageExtractParameterThroughEngineSurfaces() {
        extractParameterOriginal = """
            package com.acme;
            public class Calculator {
                private static int twice(int value) {
                    return value + 2;
                }
                public int calculate(int input) {
                    return twice(input);
                }
            }
        """.trimIndent() + "\n"
        extractParameterExpected = """
            package com.acme;
            public class Calculator {
                private static int twice(int value, int doubled) {
                    return doubled;
                }
                public int calculate(int input) {
                    return twice(input, input + 2);
                }
            }
        """.trimIndent() + "\n"
        java.nio.file.Files.writeString(calculatorPath, extractParameterOriginal)
        val opened = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        val initialHash = opened.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", initialHash); put("sdkHome", sdk.toString())
        }).jsonObject
        kotlin.test.assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())

        val line = extractParameterOriginal.lineSequence().elementAt(3)
        val start = line.indexOf("value + 2")
        kotlin.test.assertTrue(start >= 0, line)
        val fullArguments = extractParameterArguments(start, start + "value + 2".length)
        extractParameterDaemonPreview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "extractParameter"); put("languageId", "java")
            put("expectedSnapshotHash", initialHash); put("javaContext", javaContext())
            put("arguments", fullArguments)
        }).jsonObject
        kotlin.test.assertEquals("PREVIEW", extractParameterDaemonPreview.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(extractParameterOriginal, java.nio.file.Files.readString(calculatorPath), "Preview must be read-only")
        extractParameterDaemonApply = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", extractParameterDaemonPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", initialHash); put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("applied", extractParameterDaemonApply.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(extractParameterExpected, java.nio.file.Files.readString(calculatorPath))
        kotlin.test.assertTrue(extractParameterDaemonApply.getValue("diagnostics").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
        })
        extractParameterDaemonRollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", extractParameterDaemonApply.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", extractParameterDaemonApply.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext())
        }).jsonObject
        kotlin.test.assertEquals("rolledBack", extractParameterDaemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(extractParameterOriginal, java.nio.file.Files.readString(calculatorPath))

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scan.toString())
            val contextResult = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "java_project_context")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("sdkHome", sdk.toString()) })
            }).jsonObject
            val contextText = contextResult.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            extractParameterMcpJavaContext = kotlinx.serialization.json.Json.parseToJsonElement(contextText)
                .jsonObject.getValue("javaContext").jsonObject
            val preview = mcp.dispatch("tools/call", mcpExtractParameterRequest(fullArguments, extractParameterMcpJavaContext)).jsonObject
            extractParameterMcpPreviewText = preview.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(extractParameterMcpPreviewText.contains("Status   : PREVIEW"), extractParameterMcpPreviewText)
            val planId = extractParameterMcpPreviewText.lineSequence().singleOrNull { it.trimStart().startsWith("Plan ID") }
                ?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)
                ?: error("MCP extractParameter preview did not return a plan ID: $extractParameterMcpPreviewText")
            val apply = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("planId", planId); put("javaContext", extractParameterMcpJavaContext)
                })
            }).jsonObject
            extractParameterMcpApplyText = apply.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(extractParameterMcpApplyText.startsWith("Applied successfully."), extractParameterMcpApplyText)
            val transactionId = Regex("Transaction ID: ([^\\s]+)").find(extractParameterMcpApplyText)?.groupValues?.get(1)
                ?: error("MCP extractParameter Apply did not return its transaction ID: $extractParameterMcpApplyText")
            kotlin.test.assertEquals(extractParameterExpected, java.nio.file.Files.readString(calculatorPath))
            val diagnostics = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "diagnostics")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("languageId", "java"); put("jdkHome", sdk.toString())
                })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            kotlin.test.assertFalse(diagnostics.lineSequence().any { it.startsWith("[ERROR]") }, diagnostics)
            extractParameterMcpRollbackText = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("transactionId", transactionId); put("javaContext", extractParameterMcpJavaContext)
                })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            kotlin.test.assertTrue(extractParameterMcpRollbackText.startsWith("Rolled back transaction"), extractParameterMcpRollbackText)
            kotlin.test.assertEquals(extractParameterOriginal, java.nio.file.Files.readString(calculatorPath))

            val partialStart = line.indexOf("value")
            val partialArguments = extractParameterArguments(partialStart, partialStart + "value".length)
            val refusal = mcp.dispatch("tools/call", mcpExtractParameterRequest(partialArguments, extractParameterMcpJavaContext)).jsonObject
            extractParameterMcpRefusalText = refusal.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
        } finally {
            mcp.close()
        }

        val partialStart = line.indexOf("value")
        val partialArguments = extractParameterArguments(partialStart, partialStart + "value".length)
        val currentHash = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", currentHash); put("sdkHome", sdk.toString())
        }).jsonObject
        val refusal = kotlin.test.assertFailsWith<org.refactorkit.core.JsonRpcException> {
            session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "extractParameter"); put("languageId", "java")
                put("expectedSnapshotHash", currentHash); put("javaContext", javaContext())
                put("arguments", partialArguments)
            })
        }
        extractParameterDaemonRefusalCode = refusal.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content.orEmpty()
    }

    @io.cucumber.java.en.Then("extractParameter is advertised as stable managed Java mutation and stages the derived call-site argument")
    fun verifyExtractParameterCapabilityAndPreview() {
        kotlin.test.assertEquals("PREVIEW", extractParameterDaemonPreview.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("extractParameter", extractParameterDaemonPreview.getValue("operation").jsonPrimitive.content)
        val rendered = extractParameterDaemonPreview.getValue("renderedDiff").jsonPrimitive.content
        kotlin.test.assertTrue(rendered.contains("int value, int doubled"), rendered)
        kotlin.test.assertTrue(rendered.contains("return doubled;"), rendered)
        kotlin.test.assertTrue(rendered.contains("twice(input, input + 2)"), rendered)
        kotlin.test.assertTrue(extractParameterMcpPreviewText.contains("Operation: extractParameter"), extractParameterMcpPreviewText)
        kotlin.test.assertTrue(extractParameterMcpPreviewText.contains("Status   : PREVIEW"), extractParameterMcpPreviewText)
        val capabilities = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
        val java = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "extractParameter" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("extractParameter"))
        val descriptor = org.refactorkit.java.JavaAdapterRegistration.create().adapter
            .availableRefactorings(org.refactorkit.core.CodeSelection(org.refactorkit.core.SourceLocation(
                calculatorPath,
                org.refactorkit.core.SourceRange(org.refactorkit.core.SourcePosition(0, 0), org.refactorkit.core.SourcePosition(0, 0)),
            ))).single { it.id == "extractParameter" }
        kotlin.test.assertEquals("extractParameter", descriptor.capability?.operation)
    }

    @io.cucumber.java.en.And("unsupported selections refuse before managed writes while both transactions restore exact source bytes")
    fun verifyExtractParameterRefusalAndRollback() {
        kotlin.test.assertEquals("applied", extractParameterDaemonApply.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("rolledBack", extractParameterDaemonRollback.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("java.extractParameter.selectionNotReturnExpression", extractParameterDaemonRefusalCode)
        kotlin.test.assertTrue(extractParameterMcpApplyText.startsWith("Applied successfully."))
        kotlin.test.assertTrue(extractParameterMcpRollbackText.startsWith("Rolled back transaction"))
        kotlin.test.assertTrue(extractParameterMcpRefusalText.contains("java.extractParameter.selectionNotReturnExpression"), extractParameterMcpRefusalText)
        kotlin.test.assertEquals(extractParameterOriginal, java.nio.file.Files.readString(calculatorPath))
        val records = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        kotlin.test.assertEquals(2, records.size)
        kotlin.test.assertTrue(records.all { it.operation == "extractParameter" })
        kotlin.test.assertTrue(records.all { it.state == org.refactorkit.core.JournalState.ROLLED_BACK })
    }

    private fun extractParameterArguments(startCharacter: Int, endCharacter: Int) = kotlinx.serialization.json.buildJsonObject {
        put("file", "src/main/java/com/acme/Calculator.java")
        put("startLine", 3); put("startCharacter", startCharacter)
        put("endLine", 3); put("endCharacter", endCharacter)
        put("name", "doubled")
    }

    private fun mcpExtractParameterRequest(
        arguments: kotlinx.serialization.json.JsonObject,
        javaContext: kotlinx.serialization.json.JsonObject,
    ) = kotlinx.serialization.json.buildJsonObject {
        put("name", "preview_refactoring")
        put("arguments", kotlinx.serialization.json.buildJsonObject {
            put("operation", "extractParameter"); put("languageId", "java"); put("arguments", arguments)
            put("javaContext", javaContext)
        })
    }

    private fun extractConstantArguments(startCharacter: Int, endCharacter: Int) = kotlinx.serialization.json.buildJsonObject {
        put("file", "src/main/java/com/acme/Calculator.java")
        put("startLine", 3); put("startCharacter", startCharacter)
        put("endLine", 3); put("endCharacter", endCharacter)
        put("name", "TRIPLE")
    }

    private fun extractFieldArguments(startCharacter: Int, endCharacter: Int) = kotlinx.serialization.json.buildJsonObject {
        put("file", "src/main/java/com/acme/Calculator.java")
        put("startLine", 3); put("startCharacter", startCharacter)
        put("endLine", 3); put("endCharacter", endCharacter)
        put("name", "derived")
    }

    private fun mcpExtractFieldRequest(
        arguments: kotlinx.serialization.json.JsonObject,
        javaContext: kotlinx.serialization.json.JsonObject,
    ) = kotlinx.serialization.json.buildJsonObject {
        put("name", "preview_refactoring")
        put("arguments", kotlinx.serialization.json.buildJsonObject {
            put("operation", "extractField"); put("languageId", "java"); put("arguments", arguments)
            put("javaContext", javaContext)
        })
    }

    private fun mcpExtractConstantRequest(arguments: kotlinx.serialization.json.JsonObject) = kotlinx.serialization.json.buildJsonObject {
        put("name", "preview_refactoring")
        put("arguments", kotlinx.serialization.json.buildJsonObject {
            put("operation", "extractConstant"); put("languageId", "java"); put("arguments", arguments)
        })
    }

    private fun inlineConstantArguments() = kotlinx.serialization.json.buildJsonObject {
        put("file", "src/main/java/com/acme/Calculator.java")
        put("name", "OFFSET")
    }

    private fun mcpInlineConstantRequest(javaContext: kotlinx.serialization.json.JsonObject) = kotlinx.serialization.json.buildJsonObject {
        put("name", "preview_refactoring")
        put("arguments", kotlinx.serialization.json.buildJsonObject {
            put("operation", "inlineConstant"); put("languageId", "java")
            put("arguments", inlineConstantArguments()); put("javaContext", javaContext)
        })
    }

    private fun javaContext() = kotlinx.serialization.json.buildJsonObject {
        put("sdkHome", sdk.toString()); put("expectedContextHash", context.getValue("contextHash").jsonPrimitive.content)
    }

    private fun write(relative: String, content: String) {
        val file = root.resolve(relative)
        java.nio.file.Files.createDirectories(file.parent)
        java.nio.file.Files.writeString(file, content)
    }

    @io.cucumber.java.After
    fun close() {
        session?.close()
        if (::owned.isInitialized) java.nio.file.Files.walk(owned).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists)
        }
    }
}

class JavaPackageMigrationProviderSteps {
    private lateinit var owned: java.nio.file.Path
    private lateinit var root: java.nio.file.Path
    private lateinit var sdk: java.nio.file.Path
    private var session: org.refactorkit.daemon.DaemonSession? = null
    private lateinit var baseline: Map<String, String>
    private lateinit var context: kotlinx.serialization.json.JsonObject
    private lateinit var preview: kotlinx.serialization.json.JsonObject
    private lateinit var applied: kotlinx.serialization.json.JsonObject
    private lateinit var rolledBack: kotlinx.serialization.json.JsonObject
    private lateinit var appliedTree: Map<String, String>
    private var staleRefusal: org.refactorkit.core.JsonRpcException? = null
    private var transactionsBeforeApply: Int = -1
    private var transactionCountAfterApply: Int = -1
    private lateinit var generatedBefore: String

    @io.cucumber.java.en.Given("a two-module Maven package-migration workspace with package-info, tests, external consumers, and generated sources")
    fun createWorkspace() {
        owned = java.nio.file.Files.createTempDirectory("rk-java-move-package-")
        root = owned.resolve("reactor")
        sdk = java.nio.file.Path.of(System.getProperty("java.home"))
        write("pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>reactor</artifactId><version>1.0</version><packaging>pom</packaging>
              <properties><maven.compiler.release>8</maven.compiler.release></properties><modules><module>library</module><module>app</module></modules>
            </project>
        """.trimIndent() + "\n")
        write("library/pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><parent><groupId>com.acme</groupId><artifactId>reactor</artifactId><version>1.0</version><relativePath>../pom.xml</relativePath></parent><artifactId>library</artifactId></project>
        """.trimIndent() + "\n")
        write("app/pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><parent><groupId>com.acme</groupId><artifactId>reactor</artifactId><version>1.0</version><relativePath>../pom.xml</relativePath></parent><artifactId>app</artifactId>
              <dependencies><dependency><groupId>com.acme</groupId><artifactId>library</artifactId><version>1.0</version></dependency></dependencies>
            </project>
        """.trimIndent() + "\n")
        write("library/src/main/java/com/acme/old/PublicAdapter.java", "package com.acme.old;\npublic class PublicAdapter { PackageHelper helper; }\n")
        write("library/src/main/java/com/acme/old/PackageHelper.java", "package com.acme.old;\nclass PackageHelper {}\n")
        write("library/src/main/java/com/acme/old/package-info.java", "@Deprecated\npackage com.acme.old;\n")
        write("library/src/test/java/com/acme/old/AdapterTest.java", "package com.acme.old;\nclass AdapterTest { PublicAdapter value; }\n")
        write("library/src/main/java/com/acme/old/sub/Child.java", "package com.acme.old.sub;\nimport com.acme.old.PublicAdapter;\nclass Child { PublicAdapter value; String path = \"src/main/java/com/acme/old/PublicAdapter.java\"; String nested = \"com.acme.old.sub\"; }\n")
        write("app/src/main/java/com/acme/app/App.java", "package com.acme.app;\nimport com.acme.old.PublicAdapter;\nclass App { PublicAdapter value; String packageName = \"com.acme.old\"; }\n")
        write("library/target/generated-sources/annotations/com/acme/old/Generated.java", "package com.acme.old;\nclass Generated {}\n")
        generatedBefore = java.nio.file.Files.readString(root.resolve("library/target/generated-sources/annotations/com/acme/old/Generated.java"))
        session = org.refactorkit.daemon.DaemonSession()
        val opened = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) }).jsonObject
        val hash = opened.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", hash); put("sdkHome", sdk.toString())
        }).jsonObject
        kotlin.test.assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        baseline = exactTree()
    }

    @io.cucumber.java.en.When("I exercise typed movePackage preview, stale-plan refusal, fresh apply, and rollback through the daemon")
    fun exerciseProvider() {
        preview = previewMove()
        kotlin.test.assertEquals(baseline, exactTree(), "Preview must not modify workspace files")
        val stalePath = root.resolve("app/src/main/java/com/acme/app/App.java")
        val originalApp = java.nio.file.Files.readString(stalePath)
        java.nio.file.Files.writeString(stalePath, originalApp + "// stale after preview\n")
        try {
            session!!.dispatch("refactor.apply", applyRequest(preview, context, preview.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content))
        } catch (failure: org.refactorkit.core.JsonRpcException) {
            staleRefusal = failure
        } finally {
            java.nio.file.Files.writeString(stalePath, originalApp)
        }
        transactionsBeforeApply = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size

        val freshPreview = previewMove()
        applied = session!!.dispatch("refactor.apply", applyRequest(
            freshPreview,
            context,
            freshPreview.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content,
        )).jsonObject
        appliedTree = exactTree()
        transactionCountAfterApply = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size
        val appliedHash = applied.getValue("snapshotHash").jsonPrimitive.content
        rolledBack = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", applied.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", appliedHash)
            put("javaContext", javaContext(context))
        }).jsonObject
    }

    @io.cucumber.java.en.Then("the direct provider returns a read-only preview with staged diagnostics and moves only exact-package non-generated units")
    fun verifyMoveAndRollback() {
        kotlin.test.assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals("movePackage", preview.getValue("operation").jsonPrimitive.content)
        kotlin.test.assertTrue("package-info=1" in preview.getValue("summary").jsonPrimitive.content, preview.toString())
        kotlin.test.assertTrue(preview.containsKey("diagnosticsAfterPreview"), preview.toString())
        kotlin.test.assertEquals(4, preview.getValue("structuredDiff").jsonArray.count { it.jsonObject.getValue("type").jsonPrimitive.content == "renameFile" })
        kotlin.test.assertNotNull(staleRefusal, "Apply against a changed snapshot must be refused")
        kotlin.test.assertEquals("java.context.snapshotStale", staleRefusal!!.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
        kotlin.test.assertEquals(0, transactionsBeforeApply, "Stale Apply must not create a transaction")
        kotlin.test.assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(1, transactionCountAfterApply, "A package move must commit one PatchEngine transaction")
        kotlin.test.assertTrue("library/src/main/java/com/acme/migrated/PublicAdapter.java" in appliedTree)
        kotlin.test.assertTrue("library/src/main/java/com/acme/migrated/PackageHelper.java" in appliedTree)
        kotlin.test.assertTrue("library/src/main/java/com/acme/migrated/package-info.java" in appliedTree)
        kotlin.test.assertTrue("library/src/test/java/com/acme/migrated/AdapterTest.java" in appliedTree)
        kotlin.test.assertFalse("library/src/main/java/com/acme/old/PublicAdapter.java" in appliedTree)
        val child = appliedText("library/src/main/java/com/acme/old/sub/Child.java")
        kotlin.test.assertTrue("package com.acme.old.sub;" in child)
        kotlin.test.assertTrue("import com.acme.migrated.PublicAdapter;" in child)
        kotlin.test.assertTrue("src/main/java/com/acme/migrated/PublicAdapter.java" in child)
        kotlin.test.assertTrue("com.acme.old.sub" in child)
        val app = appliedText("app/src/main/java/com/acme/app/App.java")
        kotlin.test.assertTrue("import com.acme.migrated.PublicAdapter;" in app)
        kotlin.test.assertTrue("\"com.acme.migrated\"" in app)
        kotlin.test.assertEquals(generatedBefore, appliedText("library/target/generated-sources/annotations/com/acme/old/Generated.java"))
        kotlin.test.assertEquals("rolledBack", rolledBack.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(baseline, exactTree(), "Rollback must restore the exact workspace file tree")
    }

    @io.cucumber.java.en.And("operation discovery agrees with the stable Java movePackage route and does not expose arbitrary recipe execution")
    fun verifyDiscovery() {
        val capabilities = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
        val javaAdapter = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val move = javaAdapter.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "movePackage" }
        kotlin.test.assertEquals("stable", move.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", move.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("structural-parse", move.getValue("evidence").jsonPrimitive.content)
        val contextOperations = capabilities.getValue("javaContextRefactoring").jsonObject.getValue("operations").jsonArray
            .map { it.jsonPrimitive.content }.toSet()
        kotlin.test.assertTrue("movePackage" in contextOperations)
        kotlin.test.assertFalse("recipe" in contextOperations)
        val unsupported = try {
            session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "recipe"); put("languageId", "java"); put("expectedSnapshotHash", preview.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content)
                put("javaContext", javaContext(context)); put("arguments", kotlinx.serialization.json.buildJsonObject { })
            })
            null
        } catch (failure: org.refactorkit.core.JsonRpcException) { failure }
        kotlin.test.assertNotNull(unsupported, "Generic Java recipe execution must remain unavailable")
        kotlin.test.assertEquals("java.context.operationUnsupported", unsupported!!.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
    }

    private fun previewMove(): kotlinx.serialization.json.JsonObject {
        val openedHash = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) }).jsonObject
            .getValue("snapshotHash").jsonPrimitive.content
        val active = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", openedHash); put("sdkHome", sdk.toString())
        }).jsonObject
        context = active
        return session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "movePackage"); put("languageId", "java"); put("expectedSnapshotHash", openedHash)
            put("javaContext", javaContext(active))
            put("arguments", kotlinx.serialization.json.buildJsonObject {
                put("fromPackage", "com.acme.old"); put("toPackage", "com.acme.migrated")
            })
        }).jsonObject
    }

    private fun applyRequest(
        plan: kotlinx.serialization.json.JsonObject,
        authority: kotlinx.serialization.json.JsonObject,
        hash: String,
    ) = kotlinx.serialization.json.buildJsonObject {
        put("planId", plan.getValue("planId").jsonPrimitive.content); put("expectedSnapshotHash", hash)
        put("javaContext", javaContext(authority))
    }

    private fun javaContext(authority: kotlinx.serialization.json.JsonObject) = kotlinx.serialization.json.buildJsonObject {
        put("sdkHome", sdk.toString()); put("expectedContextHash", authority.getValue("contextHash").jsonPrimitive.content)
    }

    private fun write(relative: String, text: String) {
        val file = root.resolve(relative)
        java.nio.file.Files.createDirectories(file.parent)
        java.nio.file.Files.writeString(file, text)
    }

    private fun exactTree(): Map<String, String> = java.nio.file.Files.walk(root).use { paths ->
        paths.filter { java.nio.file.Files.isRegularFile(it) && !root.relativize(it).startsWith(".refactorkit") }
            .toList().associate { file ->
                root.relativize(file).toString().replace('\\', '/') to
                    java.util.Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(file))
            }
    }

    private fun appliedText(relative: String): String = java.util.Base64.getDecoder()
        .decode(kotlin.test.assertNotNull(appliedTree[relative], "Missing post-Apply path $relative"))
        .toString(Charsets.UTF_8)

    @io.cucumber.java.After
    fun close() {
        session?.close()
        if (::owned.isInitialized) java.nio.file.Files.walk(owned).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists)
        }
    }
}

class JavaRuntimeBoundsSteps {
    private lateinit var root: java.nio.file.Path
    private lateinit var sourceSnapshot: org.refactorkit.core.ProjectSnapshot
    private lateinit var inputCache: org.refactorkit.java.JdtJavaAnalysisCache
    private var inputFailure: Throwable? = null
    private var encodedOversizedResponse: String? = null
    private var daemon: org.refactorkit.daemon.DaemonSession? = null
    private var cliFixture: java.nio.file.Path? = null
    private var oversizedCliOutput: org.refactorkit.cli.testharness.GuardedChildJvm.Output? = null

    @io.cucumber.java.en.Given("a Java analysis cache with a four-byte input budget and a five-byte source snapshot")
    fun boundedJavaInput() {
        root = java.nio.file.Files.createTempDirectory("rk-java-runtime-bounds-")
        sourceSnapshot = org.refactorkit.core.ProjectSnapshot(
            org.refactorkit.core.Workspace(root),
            emptyList(),
            listOf(org.refactorkit.core.SourceFile(java.nio.file.Path.of("src/Main.java"), "12345", "java")),
        )
        inputCache = org.refactorkit.java.JdtJavaAnalysisCache(
            maxJavaSourceBytes = 4,
            analyze = { _, _ -> error("Over-budget input must be refused before JDT starts") },
        )
    }

    @io.cucumber.java.en.When("I request Java analysis and encode an oversized daemon response")
    fun requestBoundedAnalysisAndResponse() {
        inputFailure = runCatching { inputCache.get(sourceSnapshot) }.exceptionOrNull()
        val oversized = org.refactorkit.core.JsonRpcResponse(
            id = kotlinx.serialization.json.JsonPrimitive("runtime-bounds"),
            result = kotlinx.serialization.json.JsonPrimitive(
                "x".repeat(org.refactorkit.core.ProtocolLimits.MAX_DAEMON_RESPONSE_BYTES),
            ),
        )
        encodedOversizedResponse = org.refactorkit.daemon.DaemonResponseEncoder.encode(
            oversized,
            kotlinx.serialization.json.Json { encodeDefaults = true },
        )
    }

    @io.cucumber.java.en.Then("Java runtime discovery distinguishes cancellable JDT reads from non-cancellable operations")
    fun runtimeMetadataIsHonest() {
        kotlin.test.assertIs<org.refactorkit.java.JdtJavaAnalysisLimitException>(inputFailure)
        val encoded = kotlin.test.assertNotNull(encodedOversizedResponse)
        kotlin.test.assertTrue(
            encoded.toByteArray(Charsets.UTF_8).size <= org.refactorkit.core.ProtocolLimits.MAX_DAEMON_RESPONSE_BYTES,
        )
        val error = kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonObject.getValue("error").jsonObject
        kotlin.test.assertEquals(
            org.refactorkit.core.JsonRpcErrorCodes.INTERNAL_ERROR,
            error.getValue("code").jsonPrimitive.int,
        )

        daemon = org.refactorkit.daemon.DaemonSession()
        val capabilities = daemon!!.dispatch("server.capabilities", null).jsonObject
        val adapters = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
        val java = adapters.single { it.jsonObject.getValue("languageId").jsonPrimitive.content == "java" }.jsonObject
        val runtime = java.getValue("runtime").jsonObject
        kotlin.test.assertEquals("in-process", runtime.getValue("executionMode").jsonPrimitive.content)
        kotlin.test.assertFalse(runtime.getValue("supportsTimeout").jsonPrimitive.boolean)
        kotlin.test.assertFalse(runtime.getValue("supportsCancellation").jsonPrimitive.boolean)
        kotlin.test.assertFalse(runtime.getValue("recordsProcessProvenance").jsonPrimitive.boolean)
        val limits = runtime.getValue("limits").jsonObject
        kotlin.test.assertEquals("null", limits.getValue("maxInputBytes").toString())
        kotlin.test.assertEquals(
            org.refactorkit.core.ProtocolLimits.MAX_DAEMON_RESPONSE_BYTES.toLong(),
            limits.getValue("maxOutputBytes").jsonPrimitive.long,
        )
        kotlin.test.assertEquals("null", limits.getValue("requestTimeoutMillis").toString())
        kotlin.test.assertEquals("null", limits.getValue("maxProcesses").toString())

        val operations = java.getValue("capabilities").jsonArray.associateBy {
            it.jsonObject.getValue("operation").jsonPrimitive.content
        }
        val definitionRuntime = operations.getValue("definition")!!.jsonObject.getValue("runtime").jsonObject
        kotlin.test.assertTrue(definitionRuntime.getValue("supportsCancellation").jsonPrimitive.boolean)
        val definitionLimits = definitionRuntime.getValue("limits").jsonObject
        kotlin.test.assertEquals(268_435_456L, definitionLimits.getValue("maxInputBytes").jsonPrimitive.long)
        kotlin.test.assertEquals(
            org.refactorkit.core.ProtocolLimits.MAX_DAEMON_RESPONSE_BYTES.toLong(),
            definitionLimits.getValue("maxOutputBytes").jsonPrimitive.long,
        )
        val mutationRuntime = operations.getValue("renameClass")!!.jsonObject.getValue("runtime").jsonObject
        kotlin.test.assertFalse(mutationRuntime.getValue("supportsCancellation").jsonPrimitive.boolean)
    }

    @io.cucumber.java.en.And("cancellation during JDT AST parsing aborts analysis without caching partial results")
    fun cancellationReachesJdtParser() {
        java.nio.file.Files.createDirectories(root.resolve("src"))
        val body = buildString {
            append("package example; public class Cancellable {\n")
            repeat(500) { append("public int value$it() { return $it; }\n") }
            append("}\n")
        }
        val snapshot = org.refactorkit.core.ProjectSnapshot(
            org.refactorkit.core.Workspace(root),
            listOf(org.refactorkit.core.Module("app", root, sourceRoots = listOf(java.nio.file.Path.of("src")))),
            listOf(org.refactorkit.core.SourceFile(java.nio.file.Path.of("src/example/Cancellable.java"), body, "java")),
        )
        val checks = java.util.concurrent.atomic.AtomicInteger()
        val token = org.refactorkit.core.SemanticCancellationToken { checks.incrementAndGet() >= 5 }
        val cache = org.refactorkit.java.JdtJavaAnalysisCache(analyze = { candidate, cancellation ->
            org.refactorkit.java.JdtJavaSemanticAnalyzer().analyze(candidate, cancellation)
        })
        val failure = runCatching { cache.get(snapshot, token) }.exceptionOrNull()
        kotlin.test.assertIs<org.refactorkit.java.JdtJavaAnalysisCancelledException>(failure, failure?.stackTraceToString())
        kotlin.test.assertTrue(checks.get() >= 5, "JDT progress monitor must observe the cancellation token")
        kotlin.test.assertEquals(0, cache.status().entries, "Cancelled analysis must not enter the semantic cache")
    }

    @io.cucumber.java.en.Given("a CLI search fixture produces output larger than the published process output limit")
    fun createOversizedCliSearchFixture() {
        root = java.nio.file.Files.createTempDirectory("rk-cli-output-bounds-")
        val source = root.resolve("many-results.txt")
        java.nio.file.Files.newBufferedWriter(source, Charsets.UTF_8).use { writer ->
            repeat(100_000) { writer.append("needle\n") }
        }
        val shortestRenderedMatch = "${source}:1:1  needle\n".toByteArray(Charsets.UTF_8).size
        kotlin.test.assertTrue(
            shortestRenderedMatch.toLong() * 100_000 >
                org.refactorkit.core.ProtocolLimits.MAX_CLI_OUTPUT_BYTES,
            "fixture's minimum rendered search result set must exceed the published ceiling",
        )
        cliFixture = source
    }

    @io.cucumber.java.en.When("the source-built CLI searches that fixture")
    fun runOversizedCliSearch() {
        val mainClass = Class.forName("org.refactorkit.cli.RefactorKitCliKt")
        oversizedCliOutput = org.refactorkit.cli.testharness.GuardedChildJvm.launch(
            workingDirectory = root,
            mainClass = mainClass,
            arguments = listOf("search", "--pattern", "needle", requireNotNull(cliFixture).toString()),
            requiredClasses = listOf(
                mainClass,
                org.refactorkit.cli.RefactorKitCli::class.java,
                org.refactorkit.treesitter.GenericStructuralSearch::class.java,
            ),
        )
    }

    @io.cucumber.java.en.Then("the CLI discards all command output and returns a bounded {string} diagnostic")
    fun cliOutputOverflowIsAtomic(refusalCode: String) {
        val result = kotlin.test.assertNotNull(oversizedCliOutput)
        kotlin.test.assertEquals(1, result.exitCode)
        kotlin.test.assertEquals(0, result.stdout.size, "no partial command output may escape")
        kotlin.test.assertTrue(
            result.stderr.size <= org.refactorkit.core.ProtocolLimits.MAX_CLI_OUTPUT_BYTES,
            "overflow diagnostic must remain within the aggregate CLI process budget",
        )
        val diagnostic = result.stderr.toString(Charsets.UTF_8)
        kotlin.test.assertTrue(diagnostic.startsWith("$refusalCode: combined stdout/stderr exceeded "), diagnostic)
        kotlin.test.assertTrue(diagnostic.contains("output discarded"), diagnostic)
    }

    @io.cucumber.java.en.And("CLI help publishes the same byte limit and a normal command still returns its complete output")
    fun cliOutputLimitIsDiscoverableAndPreservesNormalOutput() {
        val mainClass = Class.forName("org.refactorkit.cli.RefactorKitCliKt")
        fun runCli(vararg arguments: String) = org.refactorkit.cli.testharness.GuardedChildJvm.launch(
            workingDirectory = root,
            mainClass = mainClass,
            arguments = arguments.toList(),
            requiredClasses = listOf(mainClass, org.refactorkit.cli.RefactorKitCli::class.java),
        )

        val limit = org.refactorkit.core.ProtocolLimits.MAX_CLI_OUTPUT_BYTES
        val help = runCli("--help")
        kotlin.test.assertEquals(0, help.exitCode)
        kotlin.test.assertTrue(help.stdout.size <= limit)
        kotlin.test.assertTrue(
            help.stdout.toString(Charsets.UTF_8).contains(
                "${String.format(java.util.Locale.ROOT, "%,d", limit)} UTF-8 bytes across stdout and stderr per invocation",
            ),
        )
        kotlin.test.assertTrue(help.stdout.toString(Charsets.UTF_8).contains("cli.outputLimitExceeded"))
        kotlin.test.assertEquals(0, help.stderr.size)

        val version = runCli("--version")
        kotlin.test.assertEquals(0, version.exitCode)
        kotlin.test.assertTrue(version.stdout.size <= limit)
        kotlin.test.assertEquals(
            "${org.refactorkit.core.RefactorKitVersion.NAME} ${org.refactorkit.core.RefactorKitVersion.VERSION} " +
                "(API ${org.refactorkit.core.RefactorKitVersion.API_VERSION})\n",
            version.stdout.toString(Charsets.UTF_8),
        )
        kotlin.test.assertEquals(0, version.stderr.size)
    }

    @io.cucumber.java.After
    fun close() {
        daemon?.close()
        if (::root.isInitialized) java.nio.file.Files.walk(root).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists)
        }
    }
}

class JavaReorderParametersSteps {
    private lateinit var root: java.nio.file.Path
    private lateinit var sdk: java.nio.file.Path
    private lateinit var sourcePath: java.nio.file.Path
    private lateinit var callerPath: java.nio.file.Path
    private lateinit var originalSource: String
    private lateinit var originalCaller: String
    private lateinit var expectedSource: String
    private lateinit var expectedCaller: String
    private lateinit var callShape: String
    private var cursorSelection: kotlinx.serialization.json.JsonObject? = null
    private lateinit var snapshotHash: String
    private lateinit var session: org.refactorkit.daemon.DaemonSession
    private lateinit var context: kotlinx.serialization.json.JsonObject
    private lateinit var daemonPreview: kotlinx.serialization.json.JsonObject
    private lateinit var mcpPreviewText: String
    private var daemonApply: kotlinx.serialization.json.JsonObject? = null
    private var daemonRollback: kotlinx.serialization.json.JsonObject? = null
    private var mcpApplyText: String? = null
    private var mcpRollbackText: String? = null

    @io.cucumber.java.en.Given("a JDT-bound Java method has {string} caller values in an offline Maven project")
    fun openReorderFixture(shape: String) {
        callShape = shape
        root = java.nio.file.Files.createTempDirectory("rk-java-reorder-managed-")
        sdk = java.nio.file.Path.of(System.getProperty("java.home"))
        java.nio.file.Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>reorder</artifactId><version>1.0</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version><configuration><release>17</release><proc>none</proc></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        sourcePath = root.resolve("src/main/java/com/acme/Service.java")
        callerPath = root.resolve("src/main/java/com/acme/App.java")
        java.nio.file.Files.createDirectories(sourcePath.parent)
        originalSource = if (shape == "hierarchy-literal-values") {
            """package com.acme;
                public interface Service {
                    String combine(String text, boolean active);
                }
                final class DefaultService implements Service {
                    @Override public String combine(String text, boolean active) { return active ? text : ""; }
                }
            """.trimMargin()
        } else if (shape == "cursor-public-method") {
            """package com.acme;
                public class Service {
                    public String combine(String text, int count) { return text.repeat(count); }
                }
            """.trimMargin()
        } else {
            """package com.acme;
                public class Service {
                    String combine(String text, int count) { return text.repeat(count); }
                }
            """.trimMargin()
        }
        originalCaller = when (shape) {
            "local-values", "cursor-local-values", "cursor-public-method" -> """package com.acme;
                public class App {
                    String run(Service service, String text, int count) { return service.combine(text, count); }
                }
            """.trimMargin()
            "hierarchy-literal-values" -> """package com.acme;
                public class App {
                    String run(Service service) { return service.combine("value", true); }
                }
            """.trimMargin()
            else -> """package com.acme;
                public class App {
                    private int sequence;
                    private String nextText() { sequence++; return "value"; }
                    private int nextCount() { sequence++; return 2; }
                    String run(Service service) { return service.combine(nextText(), nextCount()); }
                }
            """.trimMargin()
        }
        java.nio.file.Files.writeString(sourcePath, originalSource)
        java.nio.file.Files.writeString(callerPath, originalCaller)
        expectedSource = if (shape == "hierarchy-literal-values") {
            originalSource.replace("combine(String text, boolean active)", "combine(boolean active, String text)")
        } else {
            originalSource.replace("combine(String text, int count)", "combine(int count, String text)")
        }
        expectedCaller = if (shape == "hierarchy-literal-values") {
            originalCaller.replace("combine(\"value\", true)", "combine(true, \"value\")")
        } else {
            originalCaller.replace("combine(text, count)", "combine(count, text)")
        }
        session = org.refactorkit.daemon.DaemonSession()
        val opened = session.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        val contextResult = session.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", opened.getValue("snapshotHash").jsonPrimitive.content)
            put("sdkHome", sdk.toString())
        }).jsonObject
        kotlin.test.assertEquals("available", contextResult.getValue("status").jsonPrimitive.content, contextResult.toString())
        context = kotlinx.serialization.json.buildJsonObject {
            put("sdkHome", sdk.toString())
            put("expectedContextHash", contextResult.getValue("contextHash").jsonPrimitive.content)
        }
    }

    @io.cucumber.java.en.When("I request parameter reordering through the daemon and MCP")
    fun previewReorderThroughEngineSurfaces() {
        val opened = session.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        val hash = opened.getValue("snapshotHash").jsonPrimitive.content
        snapshotHash = hash
        val contextResult = session.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", hash)
            put("sdkHome", sdk.toString())
        }).jsonObject
        kotlin.test.assertEquals("available", contextResult.getValue("status").jsonPrimitive.content, contextResult.toString())
        context = kotlinx.serialization.json.buildJsonObject {
            put("sdkHome", sdk.toString())
            put("expectedContextHash", contextResult.getValue("contextHash").jsonPrimitive.content)
        }
        val selected = if (callShape in setOf("cursor-local-values", "cursor-public-method")) {
            val declarationLine = originalSource.lines()[2]
            val query = session.dispatch("intelligence.query", kotlinx.serialization.json.buildJsonObject {
                put("requestId", "reorder-saved-cursor")
                put("kind", "mutationSelection")
                put("languageId", "java")
                put("path", "src/main/java/com/acme/Service.java")
                put("position", kotlinx.serialization.json.buildJsonObject {
                    put("line", 2)
                    put("character", declarationLine.indexOf("combine") + 2)
                })
                put("expectedSnapshotHash", hash)
                put("javaContext", context)
                put("sourceAuthority", kotlinx.serialization.json.buildJsonObject { put("kind", "saved-snapshot") })
            }).jsonObject
            kotlin.test.assertEquals("ready", query.getValue("status").jsonPrimitive.content, query.toString())
            query.getValue("selection").jsonObject.also { cursorSelection = it }
        } else null
        val selectedSymbol = selected?.getValue("symbolId")?.jsonPrimitive?.content ?: "com.acme.Service#combine"
        daemonPreview = try {
            session.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "changeSignature.reorderParameters")
                put("languageId", "java")
                put("symbol", selectedSymbol)
                if (selected != null) put("javaSelection", selected)
                put("expectedSnapshotHash", hash)
                put("javaContext", context)
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("order", if (callShape == "hierarchy-literal-values") "active,text" else "count,text")
                    if (callShape == "hierarchy-literal-values") {
                        put("includeHierarchy", "true")
                        put("acceptExternalConsumerRisk", "true")
                    }
                })
            }).jsonObject
        } catch (failure: org.refactorkit.core.JsonRpcException) {
            val expectedRefusal = if (callShape == "cursor-public-method") {
                "public method or protected method"
            } else {
                "evaluation order"
            }
            kotlin.test.assertTrue(failure.message.orEmpty().contains(expectedRefusal, ignoreCase = true), failure.message.orEmpty())
            kotlinx.serialization.json.buildJsonObject {
                put("status", "REFUSED")
                put("summary", failure.message.orEmpty())
            }
        }
        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scanned = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scanned.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scanned.toString())
            val preview = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "preview_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("operation", "changeSignature.reorderParameters")
                    put("languageId", "java")
                    put("symbol", "com.acme.Service#combine")
                    put("arguments", kotlinx.serialization.json.buildJsonObject {
                        put("order", if (callShape == "hierarchy-literal-values") "active,text" else "count,text")
                        if (callShape == "hierarchy-literal-values") {
                            put("includeHierarchy", "true")
                            put("acceptExternalConsumerRisk", "true")
                        }
                    })
                })
            }).jsonObject
            mcpPreviewText = preview.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content
        } finally {
            mcp.close()
        }
    }

    @io.cucumber.java.en.Then("the reorder outcome is {string} with no workspace mutation")
    fun checkReorderPreview(outcome: String) {
        if (outcome == "applied-and-rolled-back") {
            kotlin.test.assertEquals("PREVIEW", daemonPreview.getValue("status").jsonPrimitive.content, daemonPreview.toString())
            kotlin.test.assertEquals("changeSignature.reorderParameters", daemonPreview.getValue("operation").jsonPrimitive.content)
            kotlin.test.assertEquals("JDT_BINDING", daemonPreview.getValue("evidence").jsonPrimitive.content)
            cursorSelection?.let { selection ->
                kotlin.test.assertEquals(selection.getValue("contextHash").jsonPrimitive.content, daemonPreview.getValue("contextHash").jsonPrimitive.content)
                kotlin.test.assertEquals(selection.getValue("selectorHash").jsonPrimitive.content, daemonPreview.getValue("selectionHash").jsonPrimitive.content)
            }
            val diff = daemonPreview.getValue("renderedDiff").jsonPrimitive.content
            val expectedSignature = if (callShape == "hierarchy-literal-values") {
                "combine(boolean active, String text)"
            } else {
                "combine(int count, String text)"
            }
            val expectedCall = if (callShape == "hierarchy-literal-values") {
                "combine(true, \"value\")"
            } else {
                "combine(count, text)"
            }
            kotlin.test.assertTrue(diff.contains(expectedSignature), diff)
            kotlin.test.assertTrue(diff.contains(expectedCall), diff)
            val expectedSummary = if (callShape == "hierarchy-literal-values") {
                "Reorder 2 JDT-connected declarations"
            } else {
                "Reorder JDT-proven parameters"
            }
            kotlin.test.assertTrue(mcpPreviewText.contains(expectedSummary), mcpPreviewText)
        } else {
            kotlin.test.assertEquals("REFUSED", daemonPreview.getValue("status").jsonPrimitive.content)
            val expectedRefusal = if (callShape == "cursor-public-method") {
                "public method or protected method"
            } else {
                "evaluation order"
            }
            kotlin.test.assertTrue(daemonPreview.getValue("summary").jsonPrimitive.content.contains(expectedRefusal, ignoreCase = true))
            kotlin.test.assertTrue(mcpPreviewText.contains(expectedRefusal, ignoreCase = true), mcpPreviewText)
        }
        kotlin.test.assertEquals(originalSource, java.nio.file.Files.readString(sourcePath))
        kotlin.test.assertEquals(originalCaller, java.nio.file.Files.readString(callerPath))
        kotlin.test.assertTrue(transactionRecords().isEmpty())
    }

    @io.cucumber.java.en.And("changeSignature.reorderParameters is advertised as {string}")
    fun verifyReorderCapabilityAndLifecycle(authority: String) {
        val capabilities = session.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
        val javaAdapter = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = javaAdapter.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "changeSignature.reorderParameters" }
        kotlin.test.assertEquals(authority, capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("changeSignature.reorderParameters"))
        if (callShape in setOf("side-effecting-call-values", "cursor-public-method")) {
            kotlin.test.assertTrue(transactionRecords().isEmpty())
            return
        }

        val daemonApplyResult = session.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", daemonPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", context)
        }).jsonObject
        daemonApply = daemonApplyResult
        kotlin.test.assertEquals("applied", daemonApplyResult.getValue("status").jsonPrimitive.content)
        kotlin.test.assertEquals(expectedSource, java.nio.file.Files.readString(sourcePath))
        kotlin.test.assertEquals(expectedCaller, java.nio.file.Files.readString(callerPath))
        kotlin.test.assertTrue(daemonApplyResult.getValue("diagnostics").jsonArray.none { d ->
            d.jsonObject["severity"]?.jsonPrimitive?.content?.equals("error", ignoreCase = true) == true
        })
        daemonRollback = session.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", daemonApplyResult.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", daemonApplyResult.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", context)
        }).jsonObject
        kotlin.test.assertEquals("rolledBack", daemonRollback!!.getValue("status").jsonPrimitive.content)
        cursorSelection?.let { selection ->
            kotlin.test.assertEquals(selection.getValue("selectorHash").jsonPrimitive.content, daemonApplyResult.getValue("appliedSelectionHash").jsonPrimitive.content)
        }
        kotlin.test.assertEquals(originalSource, java.nio.file.Files.readString(sourcePath))
        kotlin.test.assertEquals(originalCaller, java.nio.file.Files.readString(callerPath))

        val mcp = org.refactorkit.mcp.McpSession()
        try {
            val scan = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "project_scan")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            kotlin.test.assertFalse(scan.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scan.toString())
            val preview = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "preview_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("operation", "changeSignature.reorderParameters")
                    put("languageId", "java")
                    put("symbol", "com.acme.Service#combine")
                    put("arguments", kotlinx.serialization.json.buildJsonObject {
                        put("order", if (callShape == "hierarchy-literal-values") "active,text" else "count,text")
                        if (callShape == "hierarchy-literal-values") {
                            put("includeHierarchy", "true")
                            put("acceptExternalConsumerRisk", "true")
                        }
                    })
                })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            val expectedSummary = if (callShape == "hierarchy-literal-values") {
                "Reorder 2 JDT-connected declarations"
            } else {
                "Reorder JDT-proven parameters"
            }
            kotlin.test.assertTrue(preview.contains(expectedSummary), preview)
            val planId = preview.lineSequence().single { it.trimStart().startsWith("Plan ID") }.substringAfter(':').trim()
            val apply = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("planId", planId) })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            mcpApplyText = apply
            kotlin.test.assertTrue(apply.startsWith("Applied successfully."), apply)
            val transactionId = Regex("Transaction ID: ([^\\s]+)").find(apply)?.groupValues?.get(1)
                ?: error("MCP reorder Apply did not return its transaction ID: $apply")
            kotlin.test.assertEquals(expectedSource, java.nio.file.Files.readString(sourcePath))
            kotlin.test.assertEquals(expectedCaller, java.nio.file.Files.readString(callerPath))
            val diagnostics = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "diagnostics")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("languageId", "java"); put("jdkHome", sdk.toString()) })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            kotlin.test.assertFalse(diagnostics.lineSequence().any { it.startsWith("[ERROR]") }, diagnostics)
            val rollback = mcp.dispatch("tools/call", kotlinx.serialization.json.buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", kotlinx.serialization.json.buildJsonObject { put("transactionId", transactionId) })
            }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
            mcpRollbackText = rollback
            kotlin.test.assertTrue(rollback.startsWith("Rolled back transaction"), rollback)
            kotlin.test.assertEquals(originalSource, java.nio.file.Files.readString(sourcePath))
            kotlin.test.assertEquals(originalCaller, java.nio.file.Files.readString(callerPath))
        } finally {
            mcp.close()
        }
        val records = transactionRecords()
        kotlin.test.assertEquals(2, records.size)
        kotlin.test.assertTrue(records.all { it.operation == "changeSignature.reorderParameters" })
        kotlin.test.assertTrue(records.all { it.state == org.refactorkit.core.JournalState.ROLLED_BACK })
    }

    @io.cucumber.java.After
    fun cleanupReorderFixture() {
        if (::session.isInitialized) session.close()
        if (::root.isInitialized) java.nio.file.Files.walk(root).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists)
        }
    }

    private fun transactionRecords() = org.refactorkit.core.TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
}

/** Scenario-scoped daemon acceptance for the first compiler-proven pull-up cell. */
class JavaPullUpMemberBoundedSteps {
    private lateinit var owned: java.nio.file.Path
    private lateinit var root: java.nio.file.Path
    private lateinit var source: java.nio.file.Path
    private lateinit var sdk: java.nio.file.Path
    private lateinit var beforeImage: Map<String, String>
    private lateinit var snapshotHash: String
    private lateinit var javaContext: kotlinx.serialization.json.JsonObject
    private lateinit var selection: kotlinx.serialization.json.JsonObject
    private lateinit var preview: kotlinx.serialization.json.JsonObject
    private var applied: kotlinx.serialization.json.JsonObject? = null
    private var session: org.refactorkit.daemon.DaemonSession? = null

    @io.cucumber.java.en.Given("an available Java 8 Maven module contains one final int literal method on a child of a direct superclass")
    fun openBoundPullUpFixture() {
        owned = java.nio.file.Files.createTempDirectory("rk-java-pull-up-member-")
        root = owned.resolve("workspace")
        sdk = java.nio.file.Path.of(System.getProperty("java.home"))
        source = root.resolve("src/main/java/fixture/Hierarchy.java")
        write("pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>pull-up</artifactId><version>1.0</version>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version><configuration><release>8</release><proc>none</proc></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        write("src/main/java/fixture/Hierarchy.java", """
            package fixture;
            abstract class Parent {
            }
            final class Child extends Parent {
                public int read() {
                    return value();
                }
                protected final int value() {
                    return 42;
                }
            }
        """.trimIndent() + "\n")
        beforeImage = sourceImage()
        session = org.refactorkit.daemon.DaemonSession()
        val opened = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        snapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        val context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", sdk.toString())
        }).jsonObject
        kotlin.test.assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        javaContext = kotlinx.serialization.json.buildJsonObject {
            put("sdkHome", sdk.toString())
            put("expectedContextHash", context.getValue("contextHash").jsonPrimitive.content)
        }
    }

    @io.cucumber.java.en.When("I request pullUpMember for fixture.Child#value into fixture.Parent from the saved method selection")
    fun previewSavedMethodSelection() {
        val lines = java.nio.file.Files.readAllLines(source)
        val methodLine = lines.indexOfFirst { "protected final int value" in it }
        kotlin.test.assertTrue(methodLine >= 0, lines.joinToString("\n"))
        val selected = session!!.dispatch("intelligence.query", kotlinx.serialization.json.buildJsonObject {
            put("requestId", "pull-up-member-value-selection")
            put("kind", "mutationSelection")
            put("languageId", "java")
            put("path", "src/main/java/fixture/Hierarchy.java")
            put("position", kotlinx.serialization.json.buildJsonObject {
                put("line", methodLine)
                put("character", lines[methodLine].indexOf("value") + 1)
            })
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", javaContext)
            put("sourceAuthority", kotlinx.serialization.json.buildJsonObject { put("kind", "saved-snapshot") })
        }).jsonObject
        kotlin.test.assertEquals("ready", selected.getValue("status").jsonPrimitive.content, selected.toString())
        selection = selected.getValue("selection").jsonObject
        kotlin.test.assertEquals("method", selection.getValue("symbolKind").jsonPrimitive.content)
        kotlin.test.assertEquals("fixture.Child#value()", selection.getValue("symbolId").jsonPrimitive.content)
        preview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "pullUpMember")
            put("languageId", "java")
            put("symbol", selection.getValue("symbolId").jsonPrimitive.content)
            put("javaSelection", selection)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", javaContext)
            put("arguments", kotlinx.serialization.json.buildJsonObject {
                put("file", "src/main/java/fixture/Hierarchy.java")
                put("targetType", "fixture.Parent")
            })
        }).jsonObject
    }

    @io.cucumber.java.en.Then("RefactorKit advertises a compiler-backed managed pullUpMember preview that is read-only and hierarchy-proven")
    fun assertCapabilityAndReadOnlyPreview() {
        kotlin.test.assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
        kotlin.test.assertEquals("pullUpMember", preview.getValue("operation").jsonPrimitive.content)
        kotlin.test.assertEquals(snapshotHash, preview.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content)
        kotlin.test.assertEquals(setOf("src/main/java/fixture/Hierarchy.java"),
            preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val diff = preview.getValue("renderedDiff").jsonPrimitive.content
        kotlin.test.assertTrue(diff.contains("protected final int value()"), diff)
        kotlin.test.assertTrue(diff.contains("return 42;"), diff)
        kotlin.test.assertTrue(preview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
        }, preview.toString())
        kotlin.test.assertEquals(selection.getValue("selectorHash").jsonPrimitive.content,
            preview.getValue("selectionHash").jsonPrimitive.content)
        kotlin.test.assertEquals(beforeImage, sourceImage())
        val unsupported = kotlin.test.assertFailsWith<org.refactorkit.core.JsonRpcException> {
            session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "pullUpMember")
                put("languageId", "java")
                put("symbol", selection.getValue("symbolId").jsonPrimitive.content)
                put("javaSelection", selection)
                put("expectedSnapshotHash", snapshotHash)
                put("javaContext", javaContext)
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("file", "src/main/java/fixture/Hierarchy.java")
                    put("targetType", "fixture.MissingParent")
                })
            })
        }
        kotlin.test.assertEquals("java.hierarchy.pullUp.targetUnavailable",
            unsupported.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content, unsupported.message)
        kotlin.test.assertEquals(beforeImage, sourceImage())
        val java = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
            .getValue("languageKernel").jsonObject.getValue("adapters").jsonArray.map { it.jsonObject }
            .single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "pullUpMember" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("pullUpMember"))
    }

    @io.cucumber.java.en.And("applying and rolling back pullUpMember restores every original source byte and path")
    fun applyAndRollbackRestoresSourceImage() {
        applied = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", preview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", javaContext)
        }).jsonObject
        kotlin.test.assertEquals("applied", applied!!.getValue("status").jsonPrimitive.content, applied.toString())
        val afterApply = java.nio.file.Files.readString(source)
        kotlin.test.assertTrue(afterApply.contains("abstract class Parent {\n    protected final int value()"), afterApply)
        kotlin.test.assertTrue(afterApply.indexOf("protected final int value()") < afterApply.indexOf("final class Child"), afterApply)
        kotlin.test.assertTrue(afterApply.contains("return value();"), afterApply)
        val rollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", applied!!.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", applied!!.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext)
        }).jsonObject
        kotlin.test.assertTrue(rollback.getValue("rolledBack").jsonPrimitive.boolean, rollback.toString())
        kotlin.test.assertEquals(beforeImage, sourceImage())
    }

    private fun write(relative: String, content: String) {
        val target = root.resolve(relative)
        java.nio.file.Files.createDirectories(target.parent)
        java.nio.file.Files.writeString(target, content)
    }

    private fun sourceImage(): Map<String, String> = java.nio.file.Files.walk(root).use { paths ->
        paths.filter { java.nio.file.Files.isRegularFile(it) }
            .filter { !root.relativize(it).startsWith(".refactorkit") }
            .sorted()
            .toList()
            .associate { root.relativize(it).toString().replace('\\', '/') to java.nio.file.Files.readString(it) }
    }

    @io.cucumber.java.After
    fun cleanupPullUpFixture() {
        session?.close()
        if (::owned.isInitialized) java.nio.file.Files.walk(owned).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists)
        }
    }
}

/** Scenario-scoped daemon acceptance for the first compiler-proven push-down cell. */
class JavaPushDownMemberBoundedSteps {
    private lateinit var owned: java.nio.file.Path
    private lateinit var root: java.nio.file.Path
    private lateinit var source: java.nio.file.Path
    private lateinit var sdk: java.nio.file.Path
    private lateinit var beforeImage: Map<String, String>
    private lateinit var snapshotHash: String
    private lateinit var javaContext: kotlinx.serialization.json.JsonObject
    private lateinit var selection: kotlinx.serialization.json.JsonObject
    private lateinit var preview: kotlinx.serialization.json.JsonObject
    private var applied: kotlinx.serialization.json.JsonObject? = null
    private var session: org.refactorkit.daemon.DaemonSession? = null

    @io.cucumber.java.en.Given("an available Java 8 Maven module contains one final int literal method on an abstract parent with one final child")
    fun openBoundPushDownFixture() {
        owned = java.nio.file.Files.createTempDirectory("rk-java-push-down-member-")
        root = owned.resolve("workspace")
        sdk = java.nio.file.Path.of(System.getProperty("java.home"))
        source = root.resolve("src/main/java/fixture/Hierarchy.java")
        write("pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>push-down</artifactId><version>1.0</version>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version><configuration><release>8</release><proc>none</proc></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        write("src/main/java/fixture/Hierarchy.java", """
            package fixture;
            abstract class Parent {
                protected final int value() {
                    return 42;
                }
            }
            final class Child extends Parent {
                public int read() {
                    return value();
                }
            }
        """.trimIndent() + "\n")
        beforeImage = sourceImage()
        session = org.refactorkit.daemon.DaemonSession()
        val opened = session!!.dispatch("project.open", kotlinx.serialization.json.buildJsonObject {
            put("root", root.toString())
        }).jsonObject
        snapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        val context = session!!.dispatch("project.context", kotlinx.serialization.json.buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", sdk.toString())
        }).jsonObject
        kotlin.test.assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        javaContext = kotlinx.serialization.json.buildJsonObject {
            put("sdkHome", sdk.toString())
            put("expectedContextHash", context.getValue("contextHash").jsonPrimitive.content)
        }
    }

    @io.cucumber.java.en.When("I request pushDownMember for fixture.Parent#value into fixture.Child from the saved method selection")
    fun previewSavedMethodSelection() {
        val lines = java.nio.file.Files.readAllLines(source)
        val methodLine = lines.indexOfFirst { "protected final int value" in it }
        kotlin.test.assertTrue(methodLine >= 0, lines.joinToString("\n"))
        val selected = session!!.dispatch("intelligence.query", kotlinx.serialization.json.buildJsonObject {
            put("requestId", "push-down-member-value-selection")
            put("kind", "mutationSelection")
            put("languageId", "java")
            put("path", "src/main/java/fixture/Hierarchy.java")
            put("position", kotlinx.serialization.json.buildJsonObject {
                put("line", methodLine)
                put("character", lines[methodLine].indexOf("value") + 1)
            })
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", javaContext)
            put("sourceAuthority", kotlinx.serialization.json.buildJsonObject { put("kind", "saved-snapshot") })
        }).jsonObject
        kotlin.test.assertEquals("ready", selected.getValue("status").jsonPrimitive.content, selected.toString())
        selection = selected.getValue("selection").jsonObject
        kotlin.test.assertEquals("method", selection.getValue("symbolKind").jsonPrimitive.content)
        kotlin.test.assertEquals("fixture.Parent#value()", selection.getValue("symbolId").jsonPrimitive.content)
        preview = session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
            put("operation", "pushDownMember")
            put("languageId", "java")
            put("symbol", selection.getValue("symbolId").jsonPrimitive.content)
            put("javaSelection", selection)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", javaContext)
            put("arguments", kotlinx.serialization.json.buildJsonObject {
                put("file", "src/main/java/fixture/Hierarchy.java")
                put("targetType", "fixture.Child")
            })
        }).jsonObject
    }

    @io.cucumber.java.en.Then("RefactorKit advertises a compiler-backed managed pushDownMember preview that is read-only and hierarchy-proven")
    fun assertCapabilityAndReadOnlyPreview() {
        kotlin.test.assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
        kotlin.test.assertEquals("pushDownMember", preview.getValue("operation").jsonPrimitive.content)
        kotlin.test.assertEquals(snapshotHash, preview.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content)
        kotlin.test.assertEquals(setOf("src/main/java/fixture/Hierarchy.java"),
            preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val diff = preview.getValue("renderedDiff").jsonPrimitive.content
        kotlin.test.assertTrue(diff.contains("protected final int value()"), diff)
        kotlin.test.assertTrue(diff.contains("return 42;"), diff)
        kotlin.test.assertTrue(preview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
        }, preview.toString())
        kotlin.test.assertEquals(selection.getValue("selectorHash").jsonPrimitive.content,
            preview.getValue("selectionHash").jsonPrimitive.content)
        kotlin.test.assertEquals(beforeImage, sourceImage())
        val unsupported = kotlin.test.assertFailsWith<org.refactorkit.core.JsonRpcException> {
            session!!.dispatch("refactor.preview", kotlinx.serialization.json.buildJsonObject {
                put("operation", "pushDownMember")
                put("languageId", "java")
                put("symbol", selection.getValue("symbolId").jsonPrimitive.content)
                put("javaSelection", selection)
                put("expectedSnapshotHash", snapshotHash)
                put("javaContext", javaContext)
                put("arguments", kotlinx.serialization.json.buildJsonObject {
                    put("file", "src/main/java/fixture/Hierarchy.java")
                    put("targetType", "fixture.Parent")
                })
            })
        }
        kotlin.test.assertEquals("java.hierarchy.pushDown.hierarchyUnsupported",
            unsupported.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content, unsupported.message)
        kotlin.test.assertEquals(beforeImage, sourceImage())
        val java = session!!.dispatch("server.capabilities", kotlinx.serialization.json.buildJsonObject { }).jsonObject
            .getValue("languageKernel").jsonObject.getValue("adapters").jsonArray.map { it.jsonObject }
            .single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "pushDownMember" }
        kotlin.test.assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        kotlin.test.assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        kotlin.test.assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        kotlin.test.assertTrue(org.refactorkit.java.JavaBoundContext.supportedMutationOperations().contains("pushDownMember"))
    }

    @io.cucumber.java.en.And("applying and rolling back pushDownMember restores every original source byte and path")
    fun applyAndRollbackRestoresSourceImage() {
        applied = session!!.dispatch("refactor.apply", kotlinx.serialization.json.buildJsonObject {
            put("planId", preview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", javaContext)
        }).jsonObject
        kotlin.test.assertEquals("applied", applied!!.getValue("status").jsonPrimitive.content, applied.toString())
        val afterApply = java.nio.file.Files.readString(source)
        val childOffset = afterApply.indexOf("final class Child")
        val methodOffset = afterApply.indexOf("protected final int value()")
        kotlin.test.assertTrue(childOffset >= 0 && methodOffset > childOffset, afterApply)
        kotlin.test.assertFalse(afterApply.substring(0, childOffset).contains("protected final int value()"), afterApply)
        kotlin.test.assertTrue(afterApply.contains("return value();"), afterApply)
        val rollback = session!!.dispatch("patch.rollback", kotlinx.serialization.json.buildJsonObject {
            put("transactionId", applied!!.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", applied!!.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext)
        }).jsonObject
        kotlin.test.assertTrue(rollback.getValue("rolledBack").jsonPrimitive.boolean, rollback.toString())
        kotlin.test.assertEquals(beforeImage, sourceImage())
    }

    private fun write(relative: String, content: String) {
        val target = root.resolve(relative)
        java.nio.file.Files.createDirectories(target.parent)
        java.nio.file.Files.writeString(target, content)
    }

    private fun sourceImage(): Map<String, String> = java.nio.file.Files.walk(root).use { paths ->
        paths.filter { java.nio.file.Files.isRegularFile(it) }
            .filter { !root.relativize(it).startsWith(".refactorkit") }
            .sorted()
            .toList()
            .associate { root.relativize(it).toString().replace('\\', '/') to java.nio.file.Files.readString(it) }
    }

    @io.cucumber.java.After
    fun cleanupPushDownFixture() {
        session?.close()
        if (::owned.isInitialized) java.nio.file.Files.walk(owned).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists)
        }
    }
}
