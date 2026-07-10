package org.refactorkit.java

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.SymbolId
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaLanguageAdapterTest {

    @Test
    fun findsReferencesAcrossImportsSamePackageAndQualifiedNames() {
        val root = createTempProject(
            "src/main/java/com/example/UserManager.java" to """
                package com.example;
                public class UserManager {
                    public UserManager child;
                }
            """.trimIndent(),
            "src/main/java/com/example/SamePackageClient.java" to """
                package com.example;
                public class SamePackageClient {
                    UserManager manager;
                }
            """.trimIndent(),
            "src/main/java/com/other/ImportClient.java" to """
                package com.other;
                import com.example.UserManager;
                public class ImportClient {
                    UserManager manager = new UserManager();
                }
            """.trimIndent(),
            "src/main/java/com/other/QualifiedClient.java" to """
                package com.other;
                public class QualifiedClient {
                    com.example.UserManager manager;
                }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val refs = JavaLanguageAdapter().findReferences(snap, SymbolId("com.example.UserManager"))

        assertTrue(refs.any { it.location.path.toString().endsWith("SamePackageClient.java") })
        assertTrue(refs.any { it.location.path.toString().endsWith("ImportClient.java") })
        assertTrue(refs.any { it.location.path.toString().endsWith("QualifiedClient.java") })
        assertTrue(refs.any { it.location.path.toString().endsWith("UserManager.java") }, "constructor/member references in declaration file are reported")
    }

    @Test
    fun reportsDuplicateSymbolsAndPackagePathMismatchDiagnostics() {
        val root = createTempProject(
            "src/main/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}",
            "src/test/java/com/example/Foo.java" to "package com.example;\npublic class Foo {}",
            "src/main/java/com/wrong/Bar.java" to "package com.example;\npublic class Bar {}",
        )
        val diagnostics = JavaLanguageAdapter().diagnostics(JavaProjectScanner().scan(root))

        assertTrue(diagnostics.any { it.code == "java.duplicateSymbol" && it.severity == Diagnostic.Severity.ERROR })
        assertTrue(diagnostics.any { it.code == "java.packagePathMismatch" && it.severity == Diagnostic.Severity.WARNING })
    }

    @Test
    fun indexesMethodsFieldsAndConstructorsWithoutNestedTypes() {
        val root = createTempProject(
            "src/main/java/com/example/UserManager.java" to """
                package com.example;
                public class UserManager {
                    private String prefix;
                    public UserManager() {}
                    public String displayName(String name) { return prefix + name; }
                    public class Inner {}
                }
            """.trimIndent(),
        )
        val symbols = JavaLanguageAdapter()
            .buildSymbols(JavaProjectScanner().scan(root))
            .symbols

        assertTrue(symbols.any { it.kind == org.refactorkit.core.Symbol.Kind.FIELD && it.id.value == "com.example.UserManager#prefix" })
        assertTrue(symbols.any { it.kind == org.refactorkit.core.Symbol.Kind.CONSTRUCTOR && it.id.value == "com.example.UserManager#<init>" })
        assertTrue(symbols.any { it.kind == org.refactorkit.core.Symbol.Kind.METHOD && it.id.value == "com.example.UserManager#displayName" })
        assertTrue(symbols.none { it.id.value == "com.example.Inner" }, "nested type is not indexed as top-level symbol")
    }

    @Test
    fun resolvesImportedTypeAndSameFileMemberAtLocation() {
        val root = createTempProject(
            "src/main/java/com/example/UserManager.java" to """
                package com.example;
                public class UserManager {
                    public String displayName(String name) { return name; }
                }
            """.trimIndent(),
            "src/main/java/com/other/Client.java" to """
                package com.other;
                import com.example.UserManager;
                public class Client {
                    private UserManager manager;
                    public String call() { return manager.displayName("ada"); }
                }
            """.trimIndent(),
        )
        val snap = JavaProjectScanner().scan(root)
        val adapter = JavaLanguageAdapter()
        adapter.buildSymbols(snap)

        val typeResolution = adapter.resolveSymbol(snap, location("src/main/java/com/other/Client.java", 3, 20))
        assertEquals("com.example.UserManager", typeResolution.symbol?.id?.value)

        val methodResolution = adapter.resolveSymbol(snap, location("src/main/java/com/other/Client.java", 4, 45))
        assertEquals("com.example.UserManager#displayName", methodResolution.symbol?.id?.value)
    }

    @Test
    fun signedMemberDefinitionAndReferencesUseJdtBindingEvidence() {
        val root = createTempProject(
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
        val snap = JavaProjectScanner().scan(root)
        val adapter = JavaLanguageAdapter()
        val symbolId = SymbolId("com.example.Lookup#find(java.lang.String)")

        val symbol = adapter.findSymbol(snap, symbolId)
        val references = adapter.findReferences(snap, symbolId)

        assertNotNull(symbol)
        assertEquals(symbolId, symbol.id)
        assertEquals(org.refactorkit.core.Symbol.Kind.METHOD, symbol.kind)
        assertTrue(symbol.location.path.toString().endsWith("Lookup.java"))
        assertEquals(1, references.size, "expected only the String overload call site, got $references")
        assertTrue(references.single().location.path.toString().endsWith("LookupClient.java"))
        val clientContent = snap.files.single { it.path.toString().endsWith("LookupClient.java") }.content
        val referencedLine = clientContent.lines()[references.single().location.range.start.line]
        assertTrue(referencedLine.contains("find(\"abc\")"), "expected String overload reference line, got $referencedLine")
        assertFalse(referencedLine.contains("find(7)"), "int overload must not be reported for signed String selector")
    }

    @Test
    fun symbolSearchIncludesSignedJdtMemberSelectors() {
        val root = createTempProject(
            "src/main/java/com/example/Lookup.java" to """
                package com.example;
                public class Lookup {
                    public String find(String key) { return key; }
                    public String find(int id) { return String.valueOf(id); }
                }
            """.trimIndent(),
        )
        val results = JavaLanguageAdapter().searchSymbols(JavaProjectScanner().scan(root), "find(java.lang.String)")

        assertTrue(results.any { it.id.value == "com.example.Lookup#find(java.lang.String)" }, "expected signed selector in $results")
        assertFalse(results.any { it.id.value == "com.example.Lookup#find(int)" }, "query should not match unrelated overload: $results")
    }

    @Test
    fun symbolLocationSelectsTypeNameNotLineStart() {
        val root = createTempProject(
            "src/main/java/com/example/UserManager.java" to "package com.example;\npublic class UserManager {}",
        )
        val symbol = JavaLanguageAdapter()
            .buildSymbols(JavaProjectScanner().scan(root))
            .symbols
            .single { it.id.value == "com.example.UserManager" }

        assertEquals(13, symbol.location.range.start.character)
    }

    private fun location(path: String, line: Int, character: Int): SourceLocation = SourceLocation(
        java.nio.file.Paths.get(path),
        SourceRange(SourcePosition(line, character), SourcePosition(line, character)),
    )

    private fun createTempProject(vararg entries: Pair<String, String>): java.nio.file.Path {
        val root = Files.createTempDirectory("refactorkit-java-adapter-test")
        for ((rel, content) in entries) {
            val file = root.resolve(rel)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return root
    }
}
