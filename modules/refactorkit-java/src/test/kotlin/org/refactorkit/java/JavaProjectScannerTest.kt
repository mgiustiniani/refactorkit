package org.refactorkit.java

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files

class JavaProjectScannerTest {
    @Test
    fun scansConventionalJavaSourceRoot() {
        val root = Files.createTempDirectory("refactorkit-java-scan")
        val sourceDir = root.resolve("src/main/java/com/example")
        Files.createDirectories(sourceDir)
        Files.writeString(sourceDir.resolve("UserManager.java"), "package com.example;\n\npublic class UserManager {}\n")

        val snapshot = JavaProjectScanner().scan(root)
        val symbols = JavaLanguageAdapter().buildSymbols(snapshot)

        assertEquals(1, snapshot.files.size)
        assertTrue(symbols.symbols.any { it.id.value == "com.example.UserManager" })
    }

    @Test
    fun detectsMultiModuleSourceRoots() {
        val root = Files.createTempDirectory("refactorkit-java-multimodule-scan")
        val apiDir = root.resolve("api/src/main/java/com/example/api")
        val serviceDir = root.resolve("service/src/main/java/com/example/service")
        Files.createDirectories(apiDir)
        Files.createDirectories(serviceDir)
        Files.writeString(apiDir.resolve("UserApi.java"), "package com.example.api;\npublic interface UserApi {}\n")
        Files.writeString(serviceDir.resolve("UserService.java"), "package com.example.service;\npublic class UserService {}\n")

        val snapshot = JavaProjectScanner().scan(root)
        val moduleNames = snapshot.modules.map { it.name }.toSet()
        val symbols = JavaLanguageAdapter().buildSymbols(snapshot).symbols.map { it.id.value }.toSet()

        assertEquals(setOf("api", "service"), moduleNames)
        assertEquals(2, snapshot.files.size)
        assertTrue("com.example.api.UserApi" in symbols)
        assertTrue("com.example.service.UserService" in symbols)
    }
}
