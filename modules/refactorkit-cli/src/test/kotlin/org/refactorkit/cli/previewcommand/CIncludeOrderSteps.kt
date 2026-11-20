package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainProvenance
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CIncludeOrderSteps {
    private var sandbox: Path? = null
    private lateinit var workspace: Path
    private lateinit var original: ByteArray
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var plan: PatchPlan
    private var cliExit: Int? = null
    private var cliOutput: String? = null
    private var cliError: String? = null
    private var cliApply: CliInvocation? = null
    private var cliRollback: CliInvocation? = null
    private var sourceAfterApply: ByteArray? = null
    private var originalHeaders: Map<Path, ByteArray> = emptyMap()
    private var originalAuxiliary: Map<Path, ByteArray> = emptyMap()
    private var fixtureCase: String? = null

    @Given("an isolated C include workspace with {string} directives")
    fun source(case: String) {
        fixtureCase = case
        val code = when (case) {
            "custom" -> "#include <z.h>\n#include <a.h>\nint main(void) { return VALUE; }\n"
            "macro" -> "#define MODE 1\n#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n"
            "standard" -> "#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n"
            "cpp", "objective-c" -> "#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n"
            "standard-with-comments" -> "    #include <stdlib.h> /* allocation routines */\n  #include <stdio.h> // output routine\nint main(void) { return 0; }\n"
            "standard-with-blank-line" -> "#include <stdlib.h>\n\n#include <stdio.h>\nint main(void) { return 0; }\n"
            "duplicate-once" -> "#include \"api.h\"\n#include \"api.h\" // duplicate declaration\nint main(void) { return c_include_order_value(); }\n"
            "duplicate-once-with-other-include" -> "#include \"api.h\"\n#include <stdio.h>\n#include \"api.h\" // duplicate declaration\nint main(void) { return c_include_order_value(); }\n"
            "multiple-duplicate-once-groups" -> "#include \"api.h\"\n#include \"api.h\"\n#include \"other.h\"\n#include \"other.h\"\nint main(void) { return 0; }\n"
            "independent-prototypes" -> "  #include \"zeta.h\" // Zeta API\n#include \"alpha.h\" /* Alpha API */\nint main(void) { zeta_api(); alpha_api(); return 0; }\n"
            "typedef-dependent" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return 0; }\n"
            "independent-builtin-prototypes" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return 0; }\n"
            "multiple-independent-builtin-prototypes" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return zeta_api() + (int) zeta_aux() + (int) alpha_api() + alpha_aux(); }\n"
            "duplicate-prototype-name" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return shared_api(); }\n"
            "independent-builtin-prototypes-c11" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return 0; }\n"
            "independent-builtin-prototypes-c11-two-tu" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return 0; }\n"
            "independent-builtin-prototypes-c11-eight-tu" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return 0; }\n"
            "independent-builtin-prototypes-c11-nine-tu" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return 0; }\n"
            "independent-builtin-prototypes-c11-distinct-literal-defines" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return RK_MAIN_MODE; }\n"
            "independent-builtin-prototypes-c11-symbolic-define" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return RK_MAIN_MODE; }\n"
            "independent-builtin-prototypes-c11-duplicate-define" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return RK_MAIN_MODE; }\n"
            "independent-builtin-prototypes-mixed-standards" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return 0; }\n"
            "independent-builtin-prototypes-multi-tu" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return 0; }\n"
            "independent-builtin-prototypes-three-tu" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return 0; }\n"
            "independent-builtin-prototypes-eight-tu" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return 0; }\n"
            "independent-builtin-prototypes-nine-tu" -> "#include \"zeta.h\"\n#include \"alpha.h\"\nint main(void) { return 0; }\n"
            else -> error("Unknown case $case")
        }
        sandbox = Files.createTempDirectory("refactorkit-c-include-order-")
        workspace = Files.createDirectory(sandbox!!.resolve("workspace"))
        cliExit = null
        cliOutput = null
        cliError = null
        cliApply = null
        cliRollback = null
        sourceAfterApply = null
        originalHeaders = emptyMap()
        originalAuxiliary = emptyMap()
        original = code.toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("main.c"), original)
        val headerSources = when (case) {
            "duplicate-once", "duplicate-once-with-other-include" ->
                mapOf(Path.of("api.h") to "#pragma once\nint c_include_order_value(void);\n")
            "multiple-duplicate-once-groups" -> mapOf(
                Path.of("api.h") to "#pragma once\n",
                Path.of("other.h") to "#pragma once\n",
            )
            "independent-prototypes" -> mapOf(
                Path.of("zeta.h") to "#pragma once\nvoid zeta_api(void);\n",
                Path.of("alpha.h") to "#pragma once\nvoid alpha_api(void);\n",
            )
            "independent-builtin-prototypes" -> mapOf(
                Path.of("zeta.h") to "#pragma once\nint zeta_api(void);\n",
                Path.of("alpha.h") to "#pragma once\nlong alpha_api(void);\n",
            )
            "multiple-independent-builtin-prototypes" -> mapOf(
                Path.of("zeta.h") to "#pragma once\nint zeta_api(void);\nlong zeta_aux(void);\n",
                Path.of("alpha.h") to "#pragma once\nlong alpha_api(void);\nint alpha_aux(void);\n",
            )
            "duplicate-prototype-name" -> mapOf(
                Path.of("zeta.h") to "#pragma once\nint shared_api(void);\n",
                Path.of("alpha.h") to "#pragma once\nint shared_api(void);\n",
            )
            "independent-builtin-prototypes-c11" -> mapOf(
                Path.of("zeta.h") to "#pragma once\nint zeta_api(void);\n",
                Path.of("alpha.h") to "#pragma once\nlong alpha_api(void);\n",
            )
            "independent-builtin-prototypes-c11-two-tu",
            "independent-builtin-prototypes-c11-eight-tu",
            "independent-builtin-prototypes-c11-nine-tu",
            "independent-builtin-prototypes-c11-distinct-literal-defines",
            "independent-builtin-prototypes-c11-symbolic-define",
            "independent-builtin-prototypes-c11-duplicate-define" -> mapOf(
                Path.of("zeta.h") to "#pragma once\nint zeta_api(void);\n",
                Path.of("alpha.h") to "#pragma once\nlong alpha_api(void);\n",
            )
            "independent-builtin-prototypes-mixed-standards" -> mapOf(
                Path.of("zeta.h") to "#pragma once\nint zeta_api(void);\n",
                Path.of("alpha.h") to "#pragma once\nlong alpha_api(void);\n",
            )
            "independent-builtin-prototypes-multi-tu" -> mapOf(
                Path.of("zeta.h") to "#pragma once\nint zeta_api(void);\n",
                Path.of("alpha.h") to "#pragma once\nlong alpha_api(void);\n",
            )
            "independent-builtin-prototypes-three-tu" -> mapOf(
                Path.of("zeta.h") to "#pragma once\nint zeta_api(void);\n",
                Path.of("alpha.h") to "#pragma once\nlong alpha_api(void);\n",
            )
            "independent-builtin-prototypes-eight-tu" -> mapOf(
                Path.of("zeta.h") to "#pragma once\nint zeta_api(void);\n",
                Path.of("alpha.h") to "#pragma once\nlong alpha_api(void);\n",
            )
            "independent-builtin-prototypes-nine-tu" -> mapOf(
                Path.of("zeta.h") to "#pragma once\nint zeta_api(void);\n",
                Path.of("alpha.h") to "#pragma once\nlong alpha_api(void);\n",
            )
            "typedef-dependent" -> mapOf(
                Path.of("zeta.h") to "#pragma once\nrk_value_t zeta_api(void);\n",
                Path.of("alpha.h") to "#pragma once\ntypedef int rk_value_t;\n",
            )
            else -> emptyMap()
        }
        for ((path, content) in headerSources) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            Files.write(workspace.resolve(path), bytes)
            originalHeaders = originalHeaders + (path to bytes)
        }
        val additionalSources = when (case) {
            "independent-builtin-prototypes-c11-two-tu" -> (1..1).associate { index ->
                Path.of("consumer$index.c") to "#include \"alpha.h\"\n#include \"zeta.h\"\nint consumer$index(void) { return (int) alpha_api() + zeta_api(); }\n"
            }
            "independent-builtin-prototypes-c11-eight-tu" -> (1..7).associate { index ->
                Path.of("consumer$index.c") to "#include \"alpha.h\"\n#include \"zeta.h\"\nint consumer$index(void) { return (int) alpha_api() + zeta_api(); }\n"
            }
            "independent-builtin-prototypes-c11-nine-tu" -> (1..8).associate { index ->
                Path.of("consumer$index.c") to "#include \"alpha.h\"\n#include \"zeta.h\"\nint consumer$index(void) { return (int) alpha_api() + zeta_api(); }\n"
            }
            "independent-builtin-prototypes-c11-distinct-literal-defines" -> mapOf(
                Path.of("consumer.c") to "#include \"alpha.h\"\n#include \"zeta.h\"\nint consumer(void) { return RK_CONSUMER_MODE; }\n",
            )
            "independent-builtin-prototypes-mixed-standards" -> mapOf(
                Path.of("consumer.c") to "#include \"alpha.h\"\n#include \"zeta.h\"\nint consumer(void) { return (int) alpha_api() + zeta_api(); }\n",
            )
            "independent-builtin-prototypes-multi-tu" -> mapOf(
                Path.of("consumer.c") to "#include \"alpha.h\"\n#include \"zeta.h\"\nint consumer(void) { return (int) alpha_api() + zeta_api(); }\n",
            )
            "independent-builtin-prototypes-three-tu" -> mapOf(
                Path.of("consumer.c") to "#include \"alpha.h\"\n#include \"zeta.h\"\nint consumer(void) { return (int) alpha_api() + zeta_api(); }\n",
                Path.of("audit.c") to "#include \"zeta.h\"\n#include \"alpha.h\"\nlong audit(void) { return alpha_api() + zeta_api(); }\n",
            )
            "independent-builtin-prototypes-eight-tu" -> (1..7).associate { index ->
                Path.of("consumer$index.c") to "#include \"alpha.h\"\n#include \"zeta.h\"\nint consumer$index(void) { return (int) alpha_api() + zeta_api(); }\n"
            }
            "independent-builtin-prototypes-nine-tu" -> (1..8).associate { index ->
                Path.of("consumer$index.c") to "#include \"alpha.h\"\n#include \"zeta.h\"\nint consumer$index(void) { return (int) alpha_api() + zeta_api(); }\n"
            }
            else -> emptyMap()
        }
        for ((path, content) in additionalSources) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            Files.write(workspace.resolve(path), bytes)
            originalAuxiliary = originalAuxiliary + (path to bytes)
        }
        if (case in setOf("standard", "standard-with-comments", "duplicate-once", "duplicate-once-with-other-include", "independent-prototypes", "independent-builtin-prototypes", "multiple-independent-builtin-prototypes", "independent-builtin-prototypes-c11", "independent-builtin-prototypes-c11-two-tu", "independent-builtin-prototypes-c11-eight-tu", "independent-builtin-prototypes-c11-nine-tu", "independent-builtin-prototypes-c11-distinct-literal-defines", "independent-builtin-prototypes-c11-symbolic-define", "independent-builtin-prototypes-c11-duplicate-define", "independent-builtin-prototypes-mixed-standards", "independent-builtin-prototypes-multi-tu", "independent-builtin-prototypes-three-tu", "independent-builtin-prototypes-eight-tu", "independent-builtin-prototypes-nine-tu")) {
            val toolchainRoot = Path.of(requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain")) {
                "C CLI acceptance requires the configured LLVM qualification toolchain"
            })
            val source = workspace.resolve("main.c").toAbsolutePath().normalize()
            val clang = toolchainRoot.resolve("usr/bin/clang-22").toAbsolutePath().normalize()
            val unitSources = buildList {
                add(source)
                if (case in setOf("independent-builtin-prototypes-c11-two-tu", "independent-builtin-prototypes-c11-eight-tu", "independent-builtin-prototypes-c11-nine-tu", "independent-builtin-prototypes-c11-distinct-literal-defines", "independent-builtin-prototypes-mixed-standards", "independent-builtin-prototypes-multi-tu", "independent-builtin-prototypes-three-tu", "independent-builtin-prototypes-eight-tu", "independent-builtin-prototypes-nine-tu")) {
                    addAll(additionalSources.keys.map { workspace.resolve(it).toAbsolutePath().normalize() })
                }
            }
            val databaseEntries = unitSources.map { unitSource ->
                val standard = when {
                    case.startsWith("independent-builtin-prototypes-c11-") || case == "independent-builtin-prototypes-c11" -> "c11"
                    case == "independent-builtin-prototypes-mixed-standards" && unitSource == source -> "c11"
                    else -> "c17"
                }
                val defines = when (case) {
                    "independent-builtin-prototypes-c11-distinct-literal-defines" ->
                        if (unitSource == source) listOf("-DRK_MAIN_MODE=1") else listOf("-DRK_CONSUMER_MODE=2")
                    "independent-builtin-prototypes-c11-symbolic-define" -> listOf("-DRK_MAIN_MODE=RK_OTHER_MODE")
                    "independent-builtin-prototypes-c11-duplicate-define" -> listOf("-DRK_MAIN_MODE=1", "-DRK_MAIN_MODE=2")
                    else -> emptyList()
                }
                val arguments = listOf(clang.toString(), "-std=$standard") + defines + listOf("-c", unitSource.toString())
                """{"directory":${JsonPrimitive(workspace.toAbsolutePath().normalize().toString())},"file":${JsonPrimitive(unitSource.toString())},"arguments":[${arguments.joinToString(",") { JsonPrimitive(it).toString() }}]}"""
            }
            val database = databaseEntries.joinToString(prefix = "[", postfix = "]")
            val databaseBytes = database.toByteArray(Charsets.UTF_8)
            Files.write(workspace.resolve("compile_commands.json"), databaseBytes)
            originalAuxiliary = originalAuxiliary + (Path.of("compile_commands.json") to databaseBytes)
        }
        val languageId = if (case in setOf("cpp", "objective-c")) case else "c"
        val sources = mutableListOf(SourceFile(Path.of("main.c"), code, languageId))
        for ((path, content) in headerSources) sources += SourceFile(path, content, "c")
        for ((path, content) in additionalSources) sources += SourceFile(path, content, "c")
        if (case == "custom") {
            val headers = mapOf(
                "z.h" to "#define VALUE 1\n",
                "a.h" to "#if defined(VALUE)\n#undef VALUE\n#define VALUE 2\n#else\n#define VALUE 1\n#endif\n",
            )
            for ((name, content) in headers) {
                Files.writeString(workspace.resolve(name), content)
                sources += SourceFile(Path.of(name), content, "c")
            }
        }
        snapshot = ProjectSnapshot(Workspace(workspace), emptyList(), sources)
    }

    @When("the C organize-includes preview is requested")
    fun preview() {
        if (fixtureCase in setOf("standard", "standard-with-comments", "independent-prototypes", "independent-builtin-prototypes", "multiple-independent-builtin-prototypes", "independent-builtin-prototypes-c11", "independent-builtin-prototypes-c11-two-tu", "independent-builtin-prototypes-c11-eight-tu", "independent-builtin-prototypes-c11-nine-tu", "independent-builtin-prototypes-c11-distinct-literal-defines", "independent-builtin-prototypes-c11-symbolic-define", "independent-builtin-prototypes-c11-duplicate-define", "independent-builtin-prototypes-mixed-standards", "independent-builtin-prototypes-multi-tu", "independent-builtin-prototypes-three-tu", "independent-builtin-prototypes-eight-tu", "independent-builtin-prototypes-nine-tu")) {
            val result = invokeCli(organizeIncludesCommand(apply = false))
            cliExit = result.exitCode
            cliOutput = result.stdout
            cliError = result.stderr
            return
        }
        val toolchain = ClangSemanticToolchain(
            clangExecutable = workspace.resolve("unused-clang"),
            clangdExecutable = workspace.resolve("unused-clangd"),
            clangFormatExecutable = workspace.resolve("unused-clang-format"),
            provenance = ClangToolchainProvenance(
                clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
                targetTriple = "x86_64-pc-linux-gnu", resourceDir = null, evidence = emptyList(),
            ),
        )
        ExternalSemanticProcessManager().use { manager ->
            CRefactoringFacade(toolchain, manager).use { facade ->
                plan = facade.preview(snapshot, "organizeIncludes", mapOf("file" to "main.c"))
            }
        }
    }

    @When("the C organize-includes CLI preview is requested")
    fun cliPreview() {
        val result = invokeCli(organizeIncludesCommand(apply = false))
        cliExit = result.exitCode
        cliOutput = result.stdout
        cliError = result.stderr
    }

    @When("the C organize-includes CLI plan is applied and rolled back")
    fun cliApplyAndRollback() {
        val applied = invokeCli(organizeIncludesCommand(apply = true))
        cliApply = applied
        if (applied.exitCode == 0) {
            val response = Json.parseToJsonElement(applied.stdout).jsonObject
            val transactionId = response["transactionId"]?.jsonPrimitive?.content
            if (!transactionId.isNullOrBlank()) {
                sourceAfterApply = Files.readAllBytes(workspace.resolve("main.c"))
                cliRollback = invokeCli(listOf("patch", "rollback", transactionId, "--root", workspace.toString()))
            }
        }
    }

    @Then("the {word} include preview is {string} without losing an include")
    fun outcome(case: String, disposition: String) {
        cliOutput?.let { output ->
            if (disposition == "refused") {
                assertEquals(1, cliExit, cliError.orEmpty().ifBlank { output })
                assertTrue(output.isBlank(), output)
                val error = cliError.orEmpty()
                assertTrue(error.contains("c.includeOrderCompilationContextUnavailable"), error)
                if (case in setOf(
                        "independent-builtin-prototypes-c11-symbolic-define",
                        "independent-builtin-prototypes-c11-duplicate-define",
                    )) {
                    assertTrue(error.contains("distinct literal numeric macro definitions"), error)
                } else {
                    assertTrue(error.contains("c.compilationDatabaseLimit") || error.contains("up to 8"), error)
                }
                return
            }
            assertEquals(0, cliExit, cliError.orEmpty().ifBlank { output })
            val response = Json.parseToJsonElement(output).jsonObject
            assertEquals("PREVIEW", response.getValue("status").jsonPrimitive.content)
            val affectedFiles = response.getValue("affectedFiles").jsonArray
            assertEquals(1, affectedFiles.size)
            assertTrue(affectedFiles.single().toString().contains("main.c"), affectedFiles.toString())
            val renderedDiff = response.getValue("renderedDiff").jsonPrimitive.content
            if (disposition == "redundant-preview") {
                assertTrue(renderedDiff.contains("-#include \"api.h\" // duplicate declaration"), renderedDiff)
                assertTrue(renderedDiff.contains("// duplicate declaration"), renderedDiff)
                assertFalse(renderedDiff.contains("+#include \"api.h\""), renderedDiff)
                val warnings = response["warnings"]?.jsonArray.orEmpty().joinToString(" ") { it.jsonPrimitive.content }
                assertTrue(warnings.contains("#pragma once"), warnings)
                if (case == "duplicate-once-with-other-include") {
                    assertFalse(renderedDiff.contains("-#include <stdio.h>"), renderedDiff)
                    val changedLines = renderedDiff.lineSequence().filter {
                        (it.startsWith("+") && !it.startsWith("+++")) ||
                            (it.startsWith("-") && !it.startsWith("---"))
                    }.toList()
                    assertEquals(2, changedLines.size, renderedDiff)
                }
                return
            }
            if (case == "independent-prototypes") {
                val alpha = renderedDiff.indexOf("+#include \"alpha.h\" /* Alpha API */")
                val zeta = renderedDiff.indexOf("+  #include \"zeta.h\" // Zeta API")
                assertTrue(alpha >= 0 && zeta > alpha, renderedDiff)
                val warnings = response["warnings"]?.jsonArray.orEmpty().joinToString(" ") { it.jsonPrimitive.content }
                assertTrue(warnings.contains("exact captured C compiler evaluated all 1 translation units"), warnings)
                return
            }
            if (case in setOf("independent-builtin-prototypes", "multiple-independent-builtin-prototypes", "independent-builtin-prototypes-c11")) {
                val alpha = renderedDiff.indexOf("+#include \"alpha.h\"")
                val zeta = renderedDiff.indexOf("+#include \"zeta.h\"")
                assertTrue(alpha >= 0 && zeta > alpha, renderedDiff)
                val warnings = response["warnings"]?.jsonArray.orEmpty().joinToString(" ") { it.jsonPrimitive.content }
                assertTrue(warnings.contains("exact captured C compiler evaluated all 1 translation units"), warnings)
                assertTrue(warnings.contains("recognized built-in return-type prototype"), warnings)
                return
            }
            if (case == "standard-with-comments") {
                val standardIo = renderedDiff.indexOf("+  #include <stdio.h> // output routine")
                val standardLibrary = renderedDiff.indexOf("+    #include <stdlib.h> /* allocation routines */")
                assertTrue(standardIo >= 0 && standardLibrary > standardIo, renderedDiff)
                return
            }
            if (case in setOf("independent-builtin-prototypes-c11-two-tu", "independent-builtin-prototypes-c11-eight-tu", "independent-builtin-prototypes-c11-distinct-literal-defines", "independent-builtin-prototypes-mixed-standards", "independent-builtin-prototypes-multi-tu", "independent-builtin-prototypes-three-tu", "independent-builtin-prototypes-eight-tu")) {
                val alpha = renderedDiff.indexOf("+#include \"alpha.h\"")
                val zeta = renderedDiff.indexOf("+#include \"zeta.h\"")
                assertTrue(alpha >= 0 && zeta > alpha, renderedDiff)
                val warnings = response["warnings"]?.jsonArray.orEmpty().joinToString(" ") { it.jsonPrimitive.content }
                val expectedTranslationUnits = when (case) {
                    "independent-builtin-prototypes-c11-two-tu" -> 2
                    "independent-builtin-prototypes-c11-eight-tu" -> 8
                    "independent-builtin-prototypes-c11-distinct-literal-defines" -> 2
                    "independent-builtin-prototypes-mixed-standards" -> 2
                    "independent-builtin-prototypes-multi-tu" -> 2
                    "independent-builtin-prototypes-three-tu" -> 3
                    else -> 8
                }
                assertTrue(warnings.contains("exact captured C compiler evaluated all $expectedTranslationUnits translation units"), warnings)
                assertTrue(warnings.contains("recognized built-in return-type prototype"), warnings)
                if (case == "independent-builtin-prototypes-c11-distinct-literal-defines") {
                    val database = Json.parseToJsonElement(
                        requireNotNull(originalAuxiliary[Path.of("compile_commands.json")]).toString(Charsets.UTF_8),
                    ).jsonArray
                    assertEquals(2, database.size)
                    val argumentsByFile = database.associate { entry ->
                        val command = entry.jsonObject
                        command.getValue("file").jsonPrimitive.content to
                            command.getValue("arguments").jsonArray.map { it.jsonPrimitive.content }
                    }
                    val mainArguments = argumentsByFile.getValue(workspace.resolve("main.c").toAbsolutePath().normalize().toString())
                    val consumerArguments = argumentsByFile.getValue(workspace.resolve("consumer.c").toAbsolutePath().normalize().toString())
                    assertEquals(listOf("-std=c11", "-DRK_MAIN_MODE=1"), mainArguments.filter {
                        it.startsWith("-std=") || it.startsWith("-D")
                    })
                    assertEquals(listOf("-std=c11", "-DRK_CONSUMER_MODE=2"), consumerArguments.filter {
                        it.startsWith("-std=") || it.startsWith("-D")
                    })
                }
                if (case.startsWith("independent-builtin-prototypes-c11-")) {
                    val compilationDatabase = requireNotNull(originalAuxiliary[Path.of("compile_commands.json")])
                        .toString(Charsets.UTF_8)
                    assertEquals(expectedTranslationUnits, Regex("\\\"file\\\":").findAll(compilationDatabase).count())
                    assertEquals(expectedTranslationUnits, Regex("-std=c11").findAll(compilationDatabase).count())
                    assertFalse(compilationDatabase.contains("-std=c17"), compilationDatabase)
                }
                if (case == "independent-builtin-prototypes-mixed-standards") {
                    val compilationDatabase = requireNotNull(originalAuxiliary[Path.of("compile_commands.json")]).toString(Charsets.UTF_8)
                    assertTrue(compilationDatabase.contains("-std=c11"), compilationDatabase)
                    assertTrue(compilationDatabase.contains("-std=c17"), compilationDatabase)
                }
                return
            }
            val standardOutput = renderedDiff.indexOf("+#include <stdio.h>")
            val standardLibrary = renderedDiff.indexOf("+#include <stdlib.h>")
            assertTrue(standardOutput >= 0 && standardLibrary > standardOutput, renderedDiff)
            return
        }
        when (disposition) {
            "refused" -> {
                assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
                assertTrue(plan.workspaceEdit.edits.isEmpty())
                if (case in setOf("cpp", "objective-c")) {
                    assertEquals(case, snapshot.files.single().languageId)
                    assertEquals("c.includeOrder.refused", plan.refusalCode)
                    assertEquals("Organize includes currently supports C sources only", plan.summary)
                    assertTrue(plan.affectedFiles.isEmpty())
                } else if (case == "standard-with-blank-line") {
                    assertTrue(plan.summary.contains("blank line"), plan.summary)
                } else if (case == "multiple-duplicate-once-groups") {
                    assertTrue(plan.summary.contains("Only one repeated pair in one include group"), plan.summary)
                } else if (case == "duplicate-prototype-name") {
                    assertTrue(plan.summary.contains("Quoted includes can be order-sensitive; reordering is refused without proof"), plan.summary)
                } else {
                    assertTrue(plan.summary.isNotBlank(), "A refusal must explain why no plan was produced")
                }
            }
            "sorted-preview" -> {
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
                val result = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files.single().content
                if (case == "standard-with-comments") {
                    assertEquals(
                        "  #include <stdio.h> // output routine\n    #include <stdlib.h> /* allocation routines */\nint main(void) { return 0; }\n",
                        result,
                    )
                } else {
                    assertTrue(result.contains("#include <stdio.h>\n#include <stdlib.h>"), result)
                }
            }
            else -> error("Unknown outcome for $case: $disposition")
        }
    }

    @Then("the C CLI reports a successful Apply and exact rollback")
    fun cliApplyResult() {
        val applied = requireNotNull(cliApply)
        assertEquals(0, applied.exitCode, applied.stderr.ifBlank { applied.stdout })
        val response = Json.parseToJsonElement(applied.stdout).jsonObject
        assertEquals("applied", response.getValue("status").jsonPrimitive.content)
        assertTrue(response.getValue("transactionId").jsonPrimitive.content.isNotBlank())
        assertFalse(requireNotNull(sourceAfterApply).contentEquals(original), "Apply must change the source before rollback")
        if (fixtureCase == "duplicate-once-with-other-include") {
            val appliedSource = requireNotNull(sourceAfterApply).toString(Charsets.UTF_8)
            assertEquals(1, appliedSource.lineSequence().count { it.trim() == "#include \"api.h\"" }, appliedSource)
            assertEquals(1, appliedSource.lineSequence().count { it.trim() == "#include <stdio.h>" }, appliedSource)
        }
        if (fixtureCase == "multiple-independent-builtin-prototypes") {
            val appliedSource = requireNotNull(sourceAfterApply).toString(Charsets.UTF_8)
            val alpha = appliedSource.indexOf("#include \"alpha.h\"")
            val zeta = appliedSource.indexOf("#include \"zeta.h\"")
            assertTrue(alpha >= 0 && zeta > alpha, appliedSource)
            assertEquals(1, appliedSource.lineSequence().count { it.trim() == "#include \"alpha.h\"" }, appliedSource)
            assertEquals(1, appliedSource.lineSequence().count { it.trim() == "#include \"zeta.h\"" }, appliedSource)
        }
        val rollback = requireNotNull(cliRollback)
        assertEquals(0, rollback.exitCode, rollback.stderr.ifBlank { rollback.stdout })
    }

    @Then("the original source is restored with its rollback transaction record")
    fun cliRollbackResult() {
        assertContentEquals(original, Files.readAllBytes(workspace.resolve("main.c")))
        for ((path, bytes) in originalHeaders) {
            assertContentEquals(bytes, Files.readAllBytes(workspace.resolve(path)))
        }
        assertTrue(Files.isDirectory(workspace.resolve(".refactorkit/transactions")))
        if (fixtureCase == "multiple-independent-builtin-prototypes") {
            val applyResponse = Json.parseToJsonElement(requireNotNull(cliApply).stdout).jsonObject
            val transactionId = applyResponse.getValue("transactionId").jsonPrimitive.content
            val journalPath = workspace.resolve(".refactorkit/transactions/$transactionId.json")
            assertTrue(Files.isRegularFile(journalPath), journalPath.toString())
            val journal = Json.parseToJsonElement(Files.readString(journalPath)).jsonObject
            assertEquals("ROLLED_BACK", journal.getValue("state").jsonPrimitive.content)
            assertTrue(
                journal.getValue("history").jsonArray.any {
                    it.jsonObject.getValue("state").jsonPrimitive.content == "ROLLED_BACK"
                },
                journal.toString(),
            )
        }
    }

    @Then("the include source bytes and transaction journal remain unchanged")
    fun unchanged() {
        assertContentEquals(original, Files.readAllBytes(workspace.resolve("main.c")))
        for ((path, bytes) in originalHeaders) {
            assertContentEquals(bytes, Files.readAllBytes(workspace.resolve(path)))
        }
        for ((path, bytes) in originalAuxiliary) {
            assertContentEquals(bytes, Files.readAllBytes(workspace.resolve(path)))
        }
        assertFalse(Files.exists(workspace.resolve(".refactorkit")))
    }

    private fun organizeIncludesCommand(apply: Boolean): List<String> {
        val toolchainRoot = Path.of(requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain")) {
            "C CLI acceptance requires the configured LLVM qualification toolchain"
        })
        return buildList {
            addAll(listOf(
                "c", "organize-includes", workspace.toString(), "--file", "main.c",
                "--clang", toolchainRoot.resolve("usr/bin/clang-22").toString(),
                "--clangd", toolchainRoot.resolve("usr/bin/clangd").toString(),
                "--clang-format", toolchainRoot.resolve("usr/bin/clang-format").toString(),
            ))
            if (apply) add("--apply")
        }
    }

    private fun invokeCli(arguments: List<String>): CliInvocation {
        val previousOut = System.out
        val previousError = System.err
        val output = java.io.ByteArrayOutputStream()
        val error = java.io.ByteArrayOutputStream()
        val exitCode = try {
            System.setOut(java.io.PrintStream(output, true, Charsets.UTF_8))
            System.setErr(java.io.PrintStream(error, true, Charsets.UTF_8))
            RefactorKitCli().run(arguments)
        } finally {
            System.setOut(previousOut)
            System.setErr(previousError)
        }
        return CliInvocation(exitCode, output.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }

    private data class CliInvocation(val exitCode: Int, val stdout: String, val stderr: String)

    @After
    fun cleanup() {
        sandbox?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
