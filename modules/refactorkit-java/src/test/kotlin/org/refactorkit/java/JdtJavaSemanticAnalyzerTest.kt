package org.refactorkit.java

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JdtJavaSemanticAnalyzerTest {

    @Test
    fun jdtAnalyzerReadsRepresentativeMavenAndGradleSourceRoots() {
        val samples = mapOf(
            "java-maven-simple" to "com.example.UserManager",
            "java-gradle-simple" to "com.example.UserService",
        )

        samples.forEach { (sample, expectedType) ->
            val root = repoRoot().resolve("samples").resolve(sample)
            val result = JdtJavaSemanticAnalyzer().analyze(JavaProjectScanner().scan(root))

            assertTrue(result.symbols.any { it.qualifiedName == expectedType }, "missing $expectedType in $sample: ${result.symbols}")
            assertTrue(result.symbols.any { it.kind == JdtJavaSemanticSymbolKind.METHOD }, "missing method symbols in $sample")
            assertTrue(result.symbols.any { it.evidence in setOf(JdtJavaSemanticEvidence.JDT_BINDING, JdtJavaSemanticEvidence.JDT_PARSE) })
        }
    }

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
        assertEquals(JdtJavaSemanticSymbolKind.METHOD, symbols["com.acme.User#displayName()"]?.kind)
        assertTrue(result.references.any {
            it.symbolQualifiedName == "com.acme.User#displayName()" &&
                it.symbolSignature == "displayName()" &&
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
    fun jdtAnalyzerKeepsNestedTypeAndMemberOwnership() {
        val root = Files.createTempDirectory("rk-jdt-nested-test")
        root.resolve("src/main/java/com/acme/Outer.java").apply {
            Files.createDirectories(parent)
            writeText("""
                package com.acme;
                public class Outer {
                    public static class Inner {
                        public String touch() { return "ok"; }
                    }
                }
            """.trimIndent() + "\n")
        }
        root.resolve("src/main/java/com/acme/NestedClient.java").apply {
            Files.createDirectories(parent)
            writeText("""
                package com.acme;
                public class NestedClient {
                    String run() { return new Outer.Inner().touch(); }
                }
            """.trimIndent() + "\n")
        }
        val result = JdtJavaSemanticAnalyzer().analyze(JavaProjectScanner().scan(root))
        val symbols = result.symbols.associateBy { it.qualifiedName }

        assertEquals(JdtJavaSemanticSymbolKind.CLASS, symbols["com.acme.Outer"]?.kind)
        assertEquals(JdtJavaSemanticSymbolKind.CLASS, symbols["com.acme.Outer.Inner"]?.kind)
        assertEquals("com.acme.Outer", symbols["com.acme.Outer.Inner"]?.ownerQualifiedName)
        assertEquals(JdtJavaSemanticSymbolKind.METHOD, symbols["com.acme.Outer.Inner#touch()"]?.kind)
        assertEquals("com.acme.Outer.Inner", symbols["com.acme.Outer.Inner#touch()"]?.ownerQualifiedName)
        assertTrue(result.references.any {
            it.symbolQualifiedName == "com.acme.Outer.Inner#touch()" &&
                it.path.toString().endsWith("NestedClient.java")
        }, "expected nested method reference, got ${result.references}")
    }

    @Test
    fun jdtReferencesDistinguishOverloadedMethodsAndConstructors() {
        val root = Files.createTempDirectory("rk-jdt-overload-test")
        root.resolve("src/main/java/com/acme/Lookup.java").apply {
            Files.createDirectories(parent)
            writeText("""
                package com.acme;
                public class Lookup {
                    public Lookup() {}
                    public Lookup(String seed) {}
                    public String find(String key) { return key; }
                    public String find(int id) { return String.valueOf(id); }
                }
            """.trimIndent() + "\n")
        }
        root.resolve("src/main/java/com/acme/LookupClient.java").apply {
            Files.createDirectories(parent)
            writeText("""
                package com.acme;
                public class LookupClient {
                    String text() { return new Lookup("seed").find("abc"); }
                    String number() { return new Lookup().find(7); }
                }
            """.trimIndent() + "\n")
        }
        val result = JdtJavaSemanticAnalyzer().analyze(JavaProjectScanner().scan(root))
        val symbols = result.symbols.associateBy { it.qualifiedName }

        val stringFind = symbols["com.acme.Lookup#find(java.lang.String)"]
        val intFind = symbols["com.acme.Lookup#find(int)"]
        val noArgConstructor = symbols["com.acme.Lookup#<init>()"]
        val stringConstructor = symbols["com.acme.Lookup#<init>(java.lang.String)"]

        assertEquals(JdtJavaSemanticSymbolKind.METHOD, stringFind?.kind)
        assertEquals(JdtJavaSemanticSymbolKind.METHOD, intFind?.kind)
        assertEquals(JdtJavaSemanticSymbolKind.CONSTRUCTOR, noArgConstructor?.kind)
        assertEquals(JdtJavaSemanticSymbolKind.CONSTRUCTOR, stringConstructor?.kind)
        assertTrue(listOf(stringFind, intFind, noArgConstructor, stringConstructor).mapNotNull { it?.bindingKey }.toSet().size == 4)
        assertTrue(result.references.any {
            it.symbolQualifiedName == "com.acme.Lookup#find(java.lang.String)" &&
                it.symbolSignature == "find(java.lang.String)" &&
                it.path.toString().endsWith("LookupClient.java")
        }, "expected String overload reference, got ${result.references}")
        assertTrue(result.references.any {
            it.symbolQualifiedName == "com.acme.Lookup#find(int)" &&
                it.symbolSignature == "find(int)" &&
                it.path.toString().endsWith("LookupClient.java")
        }, "expected int overload reference, got ${result.references}")
        assertTrue(result.references.any {
            it.symbolQualifiedName == "com.acme.Lookup#<init>(java.lang.String)" &&
                it.symbolSignature == "<init>(java.lang.String)" &&
                it.path.toString().endsWith("LookupClient.java")
        }, "expected String constructor reference, got ${result.references}")
        assertTrue(result.references.any {
            it.symbolQualifiedName == "com.acme.Lookup#<init>()" &&
                it.symbolSignature == "<init>()" &&
                it.path.toString().endsWith("LookupClient.java")
        }, "expected no-arg constructor reference, got ${result.references}")
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
    fun jdtAnalyzerReportsOverrideRelations() {
        val root = Files.createTempDirectory("rk-jdt-override-test")
        root.resolve("src/main/java/com/acme/Base.java").apply {
            Files.createDirectories(parent)
            writeText("""
                package com.acme;
                public class Base {
                    public String find(String key) { return key; }
                }
            """.trimIndent() + "\n")
        }
        root.resolve("src/main/java/com/acme/Child.java").apply {
            Files.createDirectories(parent)
            writeText("""
                package com.acme;
                public class Child extends Base {
                    @Override public String find(String key) { return key.toUpperCase(); }
                }
            """.trimIndent() + "\n")
        }

        val result = JdtJavaSemanticAnalyzer().analyze(JavaProjectScanner().scan(root))

        assertTrue(result.overrideRelations.any {
            it.overridingSymbolQualifiedName == "com.acme.Child#find(java.lang.String)" &&
                it.overriddenSymbolQualifiedName == "com.acme.Base#find(java.lang.String)" &&
                it.evidence == JdtJavaSemanticEvidence.JDT_BINDING
        }, "expected override relation, got ${result.overrideRelations}")
    }

    @Test
    fun jdtAnalyzerReportsInterfaceImplementationOverrideRelations() {
        val root = Files.createTempDirectory("rk-jdt-interface-override-test")
        root.resolve("src/main/java/com/acme/LookupApi.java").apply {
            Files.createDirectories(parent)
            writeText("""
                package com.acme;
                public interface LookupApi {
                    String find(String key);
                }
            """.trimIndent() + "\n")
        }
        root.resolve("src/main/java/com/acme/DefaultLookup.java").apply {
            Files.createDirectories(parent)
            writeText("""
                package com.acme;
                public class DefaultLookup implements LookupApi {
                    @Override public String find(String key) { return key; }
                }
            """.trimIndent() + "\n")
        }

        val result = JdtJavaSemanticAnalyzer().analyze(JavaProjectScanner().scan(root))

        assertTrue(result.overrideRelations.any {
            it.overridingSymbolQualifiedName == "com.acme.DefaultLookup#find(java.lang.String)" &&
                it.overriddenSymbolQualifiedName == "com.acme.LookupApi#find(java.lang.String)" &&
                it.evidence == JdtJavaSemanticEvidence.JDT_BINDING
        }, "expected interface implementation relation, got ${result.overrideRelations}")
    }

    @Test
    fun jdtWarningsReportUnresolvedTypesWithoutClaimingBindingCertainty() {
        val root = Files.createTempDirectory("rk-jdt-unresolved-test")
        root.resolve("src/main/java/com/acme/NeedsDependency.java").apply {
            Files.createDirectories(parent)
            writeText("""
                package com.acme;
                public class NeedsDependency {
                    private MissingDependency dependency;
                }
            """.trimIndent() + "\n")
        }
        val result = JdtJavaSemanticAnalyzer().analyze(JavaProjectScanner().scan(root))

        assertTrue(result.symbols.any { it.qualifiedName == "com.acme.NeedsDependency" })
        assertTrue(result.warnings.any {
            it.path.toString().endsWith("NeedsDependency.java") &&
                it.message.contains("MissingDependency") &&
                it.evidence == JdtJavaSemanticEvidence.JDT_PARSE
        }, "expected unresolved type warning, got ${result.warnings}")
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

    private fun repoRoot(): Path {
        var current = Paths.get("").toAbsolutePath().normalize()
        while (true) {
            if (current.resolve("settings.gradle.kts").exists() && current.resolve("samples").exists()) return current
            current = current.parent ?: error("Could not locate repository root from ${Paths.get("").toAbsolutePath()}")
        }
    }
}
