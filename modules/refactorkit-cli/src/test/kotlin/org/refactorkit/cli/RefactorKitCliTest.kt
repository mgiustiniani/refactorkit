package org.refactorkit.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.refactorkit.core.RefactorKitVersion
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefactorKitCliTest {
    @Test
    fun semanticCommandsRequireExplicitSubcommandAndToolchain() {
        val cli = RefactorKitCli()
        assertEquals(2, cli.run(listOf("typescript")))
        assertEquals(2, cli.run(listOf("typescript", "diagnostics", ".")))
        assertEquals(2, cli.run(listOf("kotlin")))
        assertEquals(2, cli.run(listOf("kotlin", "diagnostics", ".")))
        assertEquals(2, cli.run(listOf("kotlin", "definition", ".")))
        assertEquals(2, cli.run(listOf("kotlin", "references", ".", "--file", "App.kt", "--line", "0")))
        assertEquals(2, cli.run(listOf(
            "kotlin", "references", ".", "--file", "App.kt", "--line", "-1", "--character", "0",
        )))
    }


    @Test
    fun helpListsJavaImportClassRecipeAndVersionCommands() {
        val result = captureStdout { RefactorKitCli().run(listOf("--help")) }

        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("RefactorKit ${RefactorKitVersion.VERSION}"))
        assertTrue(result.stdout.contains("refactorkit --version"))
        assertTrue(result.stdout.contains("refactorkit capabilities"))
        assertTrue(result.stdout.contains("refactorkit index"))
        assertTrue(result.stdout.contains("refactorkit intelligence search"))
        assertTrue(result.stdout.contains("refactorkit java import-class"))
        assertTrue(result.stdout.contains("refactorkit java move-source-root"))
        assertTrue(result.stdout.contains("refactorkit format-file"))
        assertTrue(result.stdout.contains("diagnostics-v2"))
        assertTrue(result.stdout.contains("refactorkit kotlin diagnostics"))
        assertTrue(result.stdout.contains("refactorkit kotlin symbols"))
        assertTrue(result.stdout.contains("refactorkit kotlin definition"))
        assertTrue(result.stdout.contains("refactorkit kotlin references"))
        assertTrue(result.stdout.contains("refactorkit patch recover"))
        assertTrue(result.stdout.contains("refactorkit recipe run"))
    }

    @Test
    fun versionPrintsPublicVersionAndApiVersion() {
        val result = captureStdout { RefactorKitCli().run(listOf("--version")) }

        assertEquals(0, result.code)
        assertTrue(result.stdout.contains(RefactorKitVersion.VERSION))
        assertTrue(result.stdout.contains("API ${RefactorKitVersion.API_VERSION}"))
    }

    @Test
    fun capabilitiesPrintsVersionedLanguageKernelSchema() {
        val result = captureStdout { RefactorKitCli().run(listOf("capabilities")) }
        assertEquals(0, result.code)
        val schema = Json.parseToJsonElement(result.stdout.trim()).jsonObject
        assertEquals("1", schema["schemaVersion"]!!.jsonPrimitive.content)
        assertEquals(listOf("java", "javascript", "kotlin", "typescript"), schema["adapters"]!!.jsonArray.map {
            it.jsonObject["languageId"]!!.jsonPrimitive.content
        })
        val typescript = schema["adapters"]!!.jsonArray.single {
            it.jsonObject["languageId"]!!.jsonPrimitive.content == "typescript"
        }.jsonObject
        val diagnostics = typescript["capabilities"]!!.jsonArray.single {
            it.jsonObject["operation"]!!.jsonPrimitive.content == "diagnostics"
        }.jsonObject
        assertEquals("typescript-compiler-exact-v1", diagnostics["backend"]!!.jsonPrimitive.content)
        assertEquals("30000", diagnostics["runtime"]!!.jsonObject["limits"]!!.jsonObject
            ["requestTimeoutMillis"]!!.jsonPrimitive.content)
        assertEquals(listOf("immutable-editor-overlay", "saved-disk"), diagnostics["diagnosticSnapshotModes"]!!
            .jsonArray.map { it.jsonPrimitive.content })
        val kotlin = schema["adapters"]!!.jsonArray.single {
            it.jsonObject["languageId"]!!.jsonPrimitive.content == "kotlin"
        }.jsonObject
        val kotlinDiagnostics = kotlin["capabilities"]!!.jsonArray.single {
            it.jsonObject["operation"]!!.jsonPrimitive.content == "diagnostics"
        }.jsonObject
        assertEquals("experimental", kotlinDiagnostics["stability"]!!.jsonPrimitive.content)
        assertEquals("compiler", kotlinDiagnostics["evidence"]!!.jsonPrimitive.content)
        assertEquals("kotlin-compiler-diagnostics-k2-v1", kotlinDiagnostics["backend"]!!.jsonPrimitive.content)
        assertEquals("external-process", kotlinDiagnostics["runtime"]!!.jsonObject["executionMode"]!!.jsonPrimitive.content)
    }

    @Test
    fun indexAndIntelligenceSearchSupportJsonOutput() {
        val project = createProject(
            "src/main/java/example/UserService.java" to "package example;\npublic class UserService {}\n",
            "src/app.ts" to "export const answer = 42\n",
        )

        val index = captureStdout { RefactorKitCli().run(listOf("index", project.toString(), "--json")) }
        assertEquals(0, index.code)
        val indexJson = Json.parseToJsonElement(index.stdout.trim()).jsonObject
        assertEquals("2", indexJson.getValue("sourceCount").jsonPrimitive.content)
        assertTrue(indexJson.getValue("symbolCount").jsonPrimitive.content.toInt() >= 1)

        val search = captureStdout { RefactorKitCli().run(listOf(
            "intelligence", "search", project.toString(),
            "--query", "User", "--language", "java", "--json",
        )) }
        assertEquals(0, search.code)
        val searchJson = Json.parseToJsonElement(search.stdout.trim()).jsonObject
        assertEquals("ready", searchJson.getValue("status").jsonPrimitive.content)
        val userType = searchJson.getValue("items").jsonArray.map { it.jsonObject }.single {
            it.getValue("symbolKind").jsonPrimitive.content == "class"
        }
        assertEquals("UserService", userType.getValue("name").jsonPrimitive.content)
        assertTrue(!Files.exists(project.resolve(".refactorkit")))
    }

    @Test
    fun javaMoveSourceRootProducesRenameOnlyPreview() {
        val sample = repoRoot().resolve("samples/java-maven-reactor-21").toString()

        val result = captureStdout { RefactorKitCli().run(listOf(
            "java", "move-source-root",
            "--from", "domain/src/main/java",
            "--to", "domain-relocated/src/main/java",
            "--root", sample,
        )) }

        assertEquals(0, result.code, result.stdout)
        assertTrue(result.stdout.contains("moveSourceRoot"), result.stdout)
        assertTrue(result.stdout.contains("rename domain/src/main/java"), result.stdout)
        assertTrue(result.stdout.contains("Use --apply"), result.stdout)
        assertTrue(repoRoot().resolve("samples/java-maven-reactor-21/domain/src/main/java/example/reactor/domain/DomainValue.java").exists())
    }

    @Test
    fun javaSymbolsSubcommandDelegatesToTopLevelSymbols() {
        val sample = repoRoot().resolve("samples/java-maven-simple").toString()

        val result = captureStdout { RefactorKitCli().run(listOf("java", "symbols", sample)) }

        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("CLASS\tcom.example.UserManager"))
    }

    @Test
    fun definitionAndReferencesSupportSignedMemberSelectors() {
        val root = createProject(
            "src/main/java/com/example/Lookup.java" to """
                package com.example;
                public class Lookup {
                    public String find(String key) { return key; }
                    public String find(int id) { return String.valueOf(id); }
                }
            """.trimIndent(),
            "src/main/java/com/example/LookupClient.java" to """
                package com.example;
                public class LookupClient {
                    String text(Lookup lookup) { return lookup.find("abc"); }
                    String number(Lookup lookup) { return lookup.find(7); }
                }
            """.trimIndent(),
        )
        val symbol = "com.example.Lookup#find(java.lang.String)"

        val definition = captureStdout { RefactorKitCli().run(listOf("definition", "--symbol", symbol, root.toString())) }
        val references = captureStdout { RefactorKitCli().run(listOf("references", "--symbol", symbol, root.toString())) }

        assertEquals(0, definition.code, definition.stdout)
        assertTrue(definition.stdout.contains("Lookup.java:3"), definition.stdout)
        assertEquals(0, references.code, references.stdout)
        assertTrue(references.stdout.contains("LookupClient.java:3"), references.stdout)
        assertTrue(!references.stdout.contains("LookupClient.java:4"), references.stdout)
    }

    @Test
    fun javaImportClassOutputIncludesProvenanceAndLicenseFields() {
        val root = Files.createTempDirectory("rk-cli-import-test")
        val source = Files.createTempFile("Imported", ".java")
        source.toFile().writeText("// MIT License\npublic class Imported {}\n")

        val result = captureStdout {
            RefactorKitCli().run(listOf(
                "java", "import-class",
                "--target-package", "com.example.imported",
                "--file", source.toString(),
                root.toString(),
            ))
        }

        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("Provenance:"), result.stdout)
        assertTrue(result.stdout.contains("sourceKind=FILE"), result.stdout)
        assertTrue(result.stdout.contains("licenseDetected=MIT"), result.stdout)
        assertTrue(result.stdout.contains("licenseRisk=LOW"), result.stdout)
        assertTrue(Regex("originalHash=[0-9a-f]{64}").containsMatchIn(result.stdout), result.stdout)
    }

    @Test
    fun formatFilePreviewsAndAppliesThroughManagedCli() {
        val root = createProject(
            "src/main/java/com/example/Example.java" to
                "package com.example;\npublic class Example{void run(){int value=1;}}\n",
        )
        val relative = "src/main/java/com/example/Example.java"

        val preview = captureStdout {
            RefactorKitCli().run(listOf("format-file", relative, "--root", root.toString()))
        }
        assertEquals(0, preview.code, preview.stdout)
        assertTrue(preview.stdout.contains("Format Java compilation unit"), preview.stdout)
        assertTrue(root.resolve(relative).readText().contains("Example{"))

        val apply = captureStdout {
            RefactorKitCli().run(listOf("format-file", relative, "--apply", "--root", root.toString()))
        }
        assertEquals(0, apply.code, apply.stdout)
        assertTrue(apply.stdout.contains("Transaction:"), apply.stdout)
        assertTrue(root.resolve(relative).readText().contains("Example {"))
    }

    @Test
    fun scanRepresentativeSampleProjectsFindsJavaSymbols() {
        val samples = listOf(
            "java-maven-simple",
            "java-gradle-simple",
            "java-spring-simple",
            "java-jpa-simple",
            "java-multimodule",
        )

        samples.forEach { sampleName ->
            val sample = repoRoot().resolve("samples").resolve(sampleName).toString()

            val scan = captureStdout { RefactorKitCli().run(listOf("scan", sample)) }
            assertEquals(0, scan.code, "scan failed for $sampleName")
            assertTrue(scan.stdout.contains("Files   : "), "scan did not report files for $sampleName")
            assertTrue(!scan.stdout.contains("Files   : 0"), "scan found no Java files for $sampleName")

            val symbols = captureStdout { RefactorKitCli().run(listOf("symbols", sample)) }
            assertEquals(0, symbols.code, "symbols failed for $sampleName")
            assertTrue(symbols.stdout.contains("com.example."), "symbols found no sample Java symbols for $sampleName")
        }
    }

    private fun captureStdout(block: () -> Int): CapturedResult {
        val originalOut = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8.name()))
        return try {
            CapturedResult(block(), buffer.toString(Charsets.UTF_8.name()))
        } finally {
            System.setOut(originalOut)
        }
    }

    private fun createProject(vararg entries: Pair<String, String>): Path {
        val root = Files.createTempDirectory("rk-cli-test")
        for ((rel, content) in entries) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            file.toFile().writeText(content)
        }
        return root
    }

    private fun repoRoot(): Path {
        var current = Paths.get("").toAbsolutePath().normalize()
        while (true) {
            if (current.resolve("settings.gradle.kts").exists() && current.resolve("samples").exists()) return current
            current = current.parent ?: error("Could not locate repository root from ${Paths.get("").toAbsolutePath()}")
        }
    }

    private data class CapturedResult(val code: Int, val stdout: String)
}
