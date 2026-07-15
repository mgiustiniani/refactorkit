package org.refactorkit.java.recipe

import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.JournalState
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.TransactionId
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
        assertTrue(result.recipePlan.workspaceEdit.edits.any { it is FileEdit.Rename })
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
    fun laterStepFailureLeavesWorkspaceAndJournalUntouched() {
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
        val introducedErrorEngine = RecipeEngine(diagnosticsProvider = { snapshot ->
            if (snapshot.files.any { it.path.fileName.toString() == "AccountManager.java" }) {
                listOf(Diagnostic("introduced fixture error", Diagnostic.Severity.ERROR, code = "fixture.introduced"))
            } else emptyList()
        })
        val result = introducedErrorEngine.run(
            recipe,
            mapOf("symbol" to "com.example.UserManager", "newName" to "AccountManager"),
            root,
            dryRun = false,
        )

        val failed = assertIs<RecipeResult.Failed>(result)
        assertTrue(failed.reason.contains("runDiagnostics"), failed.reason)
        assertTrue(root.resolve("src/main/java/com/example/UserManager.java").exists())
        assertTrue(!root.resolve("src/main/java/com/example/AccountManager.java").exists())
        assertTrue(root.resolve("src/main/java/com/example/UserManager.java").readText().contains("UserManager"))
        val records = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        assertTrue(records.isEmpty())
    }

    @Test
    fun dependentStepsAreStagedAndCommittedAsOneTransaction() {
        val root = createProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}\n",
        )
        val yaml = """
            id: java.rename-and-move
            name: Rename and move
            parameters:
              symbol: string
              newName: string
              targetPackage: string
            steps:
              - type: renameClass
                symbol: "{{ symbol }}"
                newName: "{{ newName }}"
              - type: moveClass
                symbol: "com.example.{{ newName }}"
                to: "{{ targetPackage }}"
        """.trimIndent()
        val recipe = RecipeLoader.load(yaml.byteInputStream())

        val result = engine.run(
            recipe,
            mapOf(
                "symbol" to "com.example.UserManager",
                "newName" to "AccountManager",
                "targetPackage" to "com.account",
            ),
            root,
            dryRun = false,
        )

        val applied = assertIs<RecipeResult.Applied>(result)
        assertEquals(1, applied.transactionIds.size)
        assertEquals(1, TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size)
        assertTrue(root.resolve("src/main/java/com/account/AccountManager.java").exists())
        assertTrue(!root.resolve("src/main/java/com/example/UserManager.java").exists())
        assertTrue(!root.resolve("src/main/java/com/example/AccountManager.java").exists())

        val log = TransactionLog(root.resolve(".refactorkit/transactions"))
        val transactionId = TransactionId.parseOrNull(applied.transactionIds.single())!!
        val transaction = log.load(transactionId)!!
        assertIs<ApplyResult.Applied>(PatchEngine(root).rollback(transaction))
        assertTrue(root.resolve("src/main/java/com/example/UserManager.java").exists())
        assertTrue(!root.resolve("src/main/java/com/account/AccountManager.java").exists())
        assertEquals(JournalState.ROLLED_BACK, log.loadRecord(transactionId)?.state)
    }

    @Test
    fun noOpRecipeApplyCreatesNoTransaction() {
        val root = createProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}\n",
        )
        val recipe = RecipeLoader.load("""
            id: java.summary-only
            name: Summary only
            steps:
              - type: summarizePatch
        """.trimIndent().byteInputStream())

        val result = engine.run(recipe, emptyMap(), root, dryRun = false)

        val applied = assertIs<RecipeResult.Applied>(result)
        assertTrue(applied.transactionIds.isEmpty())
        assertTrue(TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().isEmpty())
    }

    @Test
    fun movePackageStagesEachClassAgainstThePreviousMove() {
        val root = createProject(
            "src/main/java/com/old/A.java" to "package com.old;\npublic class A { B value; }\n",
            "src/main/java/com/old/B.java" to "package com.old;\npublic class B {}\n",
        )
        val recipe = RecipeLoader.load("""
            id: java.move-package-test
            name: Move package
            parameters:
              from: string
              to: string
            steps:
              - type: movePackage
                from: "{{ from }}"
                to: "{{ to }}"
        """.trimIndent().byteInputStream())

        val result = engine.run(
            recipe,
            mapOf("from" to "com.old", "to" to "com.newpkg"),
            root,
            dryRun = false,
        )

        val applied = assertIs<RecipeResult.Applied>(result)
        assertEquals(1, applied.transactionIds.size)
        assertTrue(root.resolve("src/main/java/com/newpkg/A.java").readText().contains("package com.newpkg;"))
        assertTrue(root.resolve("src/main/java/com/newpkg/B.java").readText().contains("package com.newpkg;"))
        assertTrue(!root.resolve("src/main/java/com/old/A.java").exists())
        assertTrue(!root.resolve("src/main/java/com/old/B.java").exists())
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
