package org.refactorkit.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefactorKitCliTest {

    @Test
    fun helpListsJavaImportClassAndRecipeCommands() {
        val result = captureStdout { RefactorKitCli().run(listOf("--help")) }

        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("refactorkit java import-class"))
        assertTrue(result.stdout.contains("refactorkit recipe run"))
    }

    @Test
    fun javaSymbolsSubcommandDelegatesToTopLevelSymbols() {
        val sample = repoRoot().resolve("samples/java-maven-simple").toString()

        val result = captureStdout { RefactorKitCli().run(listOf("java", "symbols", sample)) }

        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("CLASS\tcom.example.UserManager"))
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

    private fun repoRoot(): Path {
        var current = Paths.get("").toAbsolutePath().normalize()
        while (true) {
            if (current.resolve("settings.gradle.kts").exists() && current.resolve("samples").exists()) return current
            current = current.parent ?: error("Could not locate repository root from ${Paths.get("").toAbsolutePath()}")
        }
    }

    private data class CapturedResult(val code: Int, val stdout: String)
}
