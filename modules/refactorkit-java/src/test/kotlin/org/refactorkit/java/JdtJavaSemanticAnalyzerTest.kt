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
    fun jdtEvidenceFindsMethodsFieldsAndReferences() {
        val root = Files.createTempDirectory("rk-jdt-member-test")
        root.resolve("src/main/java/com/acme/User.java").apply {
            Files.createDirectories(parent)
            writeText("""
                package com.acme;
                public class User {
                    private String name;
                    public String displayName() { return name; }
                }
            """.trimIndent() + "\n")
        }
        root.resolve("src/main/java/com/acme/UserPrinter.java").apply {
            Files.createDirectories(parent)
            writeText("""
                package com.acme;
                public class UserPrinter {
                    public String print(User user) { return user.displayName(); }
                }
            """.trimIndent() + "\n")
        }
        val snapshot = JavaProjectScanner().scan(root)

        val result = JdtJavaSemanticAnalyzer().analyze(snapshot)
        val symbols = result.symbols.associateBy { it.qualifiedName }

        assertEquals(JdtJavaSemanticSymbolKind.FIELD, symbols["com.acme.User#name"]?.kind)
        assertEquals(JdtJavaSemanticSymbolKind.METHOD, symbols["com.acme.User#displayName"]?.kind)
        assertTrue(result.references.any {
            it.symbolQualifiedName == "com.acme.User#displayName" &&
                it.path.toString().endsWith("UserPrinter.java") &&
                it.evidence == JdtJavaSemanticEvidence.JDT_BINDING
        }, "expected JDT-backed method reference, got ${result.references}")
        assertTrue(result.references.any {
            it.symbolQualifiedName == "com.acme.User#name" &&
                it.path.toString().endsWith("User.java") &&
                it.evidence == JdtJavaSemanticEvidence.JDT_BINDING
        }, "expected JDT-backed field reference, got ${result.references}")
    }

    @Test
    fun jdtReferencesDistinguishSameSimpleClassNameImports() {
        val root = Files.createTempDirectory("rk-jdt-reference-test")
        root.resolve("src/main/java/com/acme/left/Service.java").apply {
            Files.createDirectories(parent)
            writeText("package com.acme.left;\npublic class Service {}\n")
        }
        root.resolve("src/main/java/com/acme/right/Service.java").apply {
            Files.createDirectories(parent)
            writeText("package com.acme.right;\npublic class Service {}\n")
        }
        root.resolve("src/main/java/com/acme/app/App.java").apply {
            Files.createDirectories(parent)
            writeText("""
                package com.acme.app;
                import com.acme.right.Service;
                public class App {
                    Service service;
                }
            """.trimIndent() + "\n")
        }
        val snapshot = JavaProjectScanner().scan(root)

        val references = JdtJavaSemanticAnalyzer().analyze(snapshot).references
            .filter { it.simpleName == "Service" && it.path.toString().endsWith("App.java") }

        assertTrue(references.any { it.symbolQualifiedName == "com.acme.right.Service" }, "expected right.Service reference, got $references")
        assertTrue(references.none { it.symbolQualifiedName == "com.acme.left.Service" }, "did not expect left.Service reference, got $references")
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
