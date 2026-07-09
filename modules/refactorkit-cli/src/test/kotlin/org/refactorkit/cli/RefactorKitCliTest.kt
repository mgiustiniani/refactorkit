package org.refactorkit.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
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
