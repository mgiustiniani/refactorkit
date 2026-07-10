package org.refactorkit.java

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JdtJavaSemanticAnalyzerTest {

    @Test
    fun jdtParseEvidenceDistinguishesSameSimpleNameInDifferentPackages() {
        val root = Files.createTempDirectory("rk-jdt-test")
        root.resolve("src/main/java/com/acme/left/Service.java").apply {
            Files.createDirectories(parent)
            writeText("package com.acme.left;\npublic class Service {}\n")
        }
        root.resolve("src/main/java/com/acme/right/Service.java").apply {
            Files.createDirectories(parent)
            writeText("package com.acme.right;\npublic class Service {}\n")
        }
        val snapshot = JavaProjectScanner().scan(root)

        val result = JdtJavaSemanticAnalyzer().analyze(snapshot)
        val services = result.symbols.filter { it.simpleName == "Service" }

        assertEquals(setOf("com.acme.left.Service", "com.acme.right.Service"), services.map { it.qualifiedName }.toSet())
        assertTrue(services.all { it.evidence in setOf(JdtJavaSemanticEvidence.JDT_BINDING, JdtJavaSemanticEvidence.JDT_PARSE) })
        assertTrue(services.all { it.path.toString().endsWith("Service.java") })
    }

    @Test
    fun jdtParseEvidenceFindsInterfacesAndEnums() {
        val root = Files.createTempDirectory("rk-jdt-kind-test")
        root.resolve("src/main/java/com/acme/Types.java").apply {
            Files.createDirectories(parent)
            writeText("""
                package com.acme;
                interface Named {}
                enum Status { ACTIVE }
            """.trimIndent() + "\n")
        }
        val snapshot = JavaProjectScanner().scan(root)

        val symbols = JdtJavaSemanticAnalyzer().analyze(snapshot).symbols.associateBy { it.qualifiedName }

        assertEquals(JdtJavaSemanticSymbolKind.INTERFACE, symbols["com.acme.Named"]?.kind)
        assertEquals(JdtJavaSemanticSymbolKind.ENUM, symbols["com.acme.Status"]?.kind)
    }
}
