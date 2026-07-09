package org.refactorkit.java.recipe

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.TransactionLog
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecipeEngineTest {

    private val engine = RecipeEngine()

    private fun createProject(vararg files: Pair<String, String>): java.nio.file.Path {
        val root = Files.createTempDirectory("rk-recipe-test")
        for ((rel, content) in files) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return root
    }

    @Test
    fun loadRecipeFromYaml() {
        val yaml = """
            id: java.test-recipe
            name: Test Recipe
            language: java
            parameters:
              from: string
              to: string
            steps:
              - type: renameClass
                symbol: "{{ from }}"
                newName: "{{ to }}"
        """.trimIndent()
        val recipe = RecipeLoader.load(yaml.byteInputStream())
        assertEquals("java.test-recipe", recipe.id)
        assertEquals(2, recipe.parameters.size)
        assertEquals(1, recipe.steps.size)
    }

    @Test
    fun parameterSubstitution() {
        val step = StepDef("renameClass", mapOf("symbol" to "{{ oldName }}", "newName" to "{{ newName }}"))
        val substituted = step.substitute(mapOf("oldName" to "Foo", "newName" to "Bar"))
        assertEquals("Foo", substituted.params["symbol"])
        assertEquals("Bar", substituted.params["newName"])
    }

    @Test
    fun previewRenameRecipe() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val yaml = """
            id: java.rename
            name: Rename class
            parameters:
              symbol: string
              newName: string
            steps:
              - type: renameClass
                symbol: "{{ symbol }}"
                newName: "{{ newName }}"
        """.trimIndent()
        val recipe = RecipeLoader.load(yaml.byteInputStream())
        val result = engine.run(
            recipe,
            mapOf("symbol" to "com.example.UserManager", "newName" to "AccountManager"),
            root,
            dryRun = true,
        )
        assertIs<RecipeResult.Preview>(result)
        assertTrue(result.summary.contains("preview"))
        assertEquals(1, result.stepPlans.size)
        assertEquals(PatchStatus.PREVIEW, result.stepPlans[0].plan?.status)
    }

    @Test
    fun diagnosticsStepPassesForCleanProject() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val yaml = """
            id: java.diagnose
            name: Run diagnostics
            steps:
              - type: runDiagnostics
        """.trimIndent()
        val recipe = RecipeLoader.load(yaml.byteInputStream())
        val result = engine.run(recipe, emptyMap(), root, dryRun = true)
        assertIs<RecipeResult.Preview>(result)
        val diagStep = result.stepPlans.first()
        assertEquals("runDiagnostics", diagStep.stepType)
        assertTrue(diagStep.diagnostics.isEmpty())
    }

    @Test
    fun missingRequiredParameterThrows() {
        val yaml = """
            id: java.needs-param
            name: Needs param
            parameters:
              required: string
            steps:
              - type: summarizePatch
        """.trimIndent()
        val recipe = RecipeLoader.load(yaml.byteInputStream())
        try {
            engine.run(recipe, emptyMap(), Files.createTempDirectory("x"), dryRun = true)
            error("Should have thrown")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("required") == true)
        }
    }

    @Test
    fun sampleRenamePackageRecipeLoads() {
        val file = java.nio.file.Paths.get("recipes/java/rename-package.yml")
        if (!file.toFile().exists()) return // skip if not present
        val recipe = RecipeLoader.load(file)
        assertEquals("java.rename-package", recipe.id)
        assertTrue(recipe.steps.any { it.type == "movePackage" })
    }

    @Test
    fun defaultParameterIsUsedInPreview() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val yaml = """
            id: java.rename-default
            name: Rename class with default
            parameters:
              symbol:
                type: string
                default: com.example.UserManager
              newName:
                type: string
                default: AccountManager
            steps:
              - type: renameClass
                symbol: "{{ symbol }}"
                newName: "{{ newName }}"
        """.trimIndent()
        val recipe = RecipeLoader.load(yaml.byteInputStream())
        val result = engine.run(recipe, emptyMap(), root, dryRun = true)

        assertIs<RecipeResult.Preview>(result)
        assertEquals(PatchStatus.PREVIEW, result.stepPlans.single().plan?.status)
        assertTrue(result.stepPlans.single().plan?.summary?.contains("AccountManager") == true)
    }

    @Test
    fun successfulApplyPersistsTransactionLog() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val yaml = """
            id: java.rename-apply
            name: Rename apply
            parameters:
              symbol: string
              newName: string
            steps:
              - type: renameClass
                symbol: "{{ symbol }}"
                newName: "{{ newName }}"
        """.trimIndent()
        val recipe = RecipeLoader.load(yaml.byteInputStream())
        val result = engine.run(
            recipe,
            mapOf("symbol" to "com.example.UserManager", "newName" to "AccountManager"),
            root,
            dryRun = false,
        )

        val applied = assertIs<RecipeResult.Applied>(result)
        assertEquals(1, applied.transactionIds.size)
        assertTrue(root.resolve("src/main/java/com/example/AccountManager.java").exists())
        assertTrue(!root.resolve("src/main/java/com/example/UserManager.java").exists())
        val log = TransactionLog(root.resolve(".refactorkit/transactions"))
        assertEquals(applied.transactionIds.toSet(), log.list().map { it.value }.toSet())
    }

    @Test
    fun applyFailureRollsBackPreviouslyAppliedSteps() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
            "src/main/java/com/example/Wrong.java" to "package com.example;\npublic class Other {}\n",
        )
        val yaml = """
            id: java.rename-then-diagnostics
            name: Rename then diagnostics
            parameters:
              symbol: string
              newName: string
            steps:
              - type: renameClass
                symbol: "{{ symbol }}"
                newName: "{{ newName }}"
              - type: runDiagnostics
        """.trimIndent()
        val recipe = RecipeLoader.load(yaml.byteInputStream())
        val result = engine.run(
            recipe,
            mapOf("symbol" to "com.example.UserManager", "newName" to "AccountManager"),
            root,
            dryRun = false,
        )

        val failed = assertIs<RecipeResult.Failed>(result)
        assertTrue(failed.reason.contains("Rolled back 1 applied step"))
        assertTrue(root.resolve("src/main/java/com/example/UserManager.java").exists())
        assertTrue(!root.resolve("src/main/java/com/example/AccountManager.java").exists())
        assertTrue(root.resolve("src/main/java/com/example/UserManager.java").readText().contains("UserManager"))
        assertTrue(TransactionLog(root.resolve(".refactorkit/transactions")).list().isEmpty())
    }

    @Test
    fun unsupportedParameterTypeFailsFast() {
        val yaml = """
            id: java.bad-param-type
            name: Bad param type
            parameters:
              count: number
            steps:
              - type: summarizePatch
        """.trimIndent()
        val recipe = RecipeLoader.load(yaml.byteInputStream())
        try {
            engine.run(recipe, mapOf("count" to "1"), Files.createTempDirectory("x"), dryRun = true)
            error("Should have thrown")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("unsupported type") == true)
        }
    }
}
