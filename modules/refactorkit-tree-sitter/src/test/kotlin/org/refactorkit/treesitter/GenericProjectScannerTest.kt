package org.refactorkit.treesitter

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenericProjectScannerTest {

    private val scanner = GenericProjectScanner()

    private fun root(vararg files: Pair<String, String>): java.nio.file.Path {
        val dir = createTempDirectory("gps-test-")
        for ((rel, content) in files) {
            val p = dir.resolve(rel)
            p.parent.createDirectories()
            p.writeText(content)
        }
        return dir
    }

    @Test
    fun emptyDirectoryReturnsEmptySnapshot() {
        val r = root()
        val snap = scanner.scan(r)
        assertTrue(snap.files.isEmpty())
    }

    @Test
    fun scansKnownLanguageExtensions() {
        val r = root(
            "src/main/java/Foo.java" to "public class Foo {}",
            "src/main/kotlin/Bar.kt" to "class Bar",
            "scripts/setup.py" to "pass",
            "web/app.ts" to "export class App {}",
        )
        val snap = scanner.scan(r)
        val langIds = snap.files.map { it.languageId }.toSet()
        assertTrue("java" in langIds)
        assertTrue("kotlin" in langIds)
        assertTrue("python" in langIds)
        assertTrue("typescript" in langIds)
    }

    @Test
    fun ignoresUnknownExtensions() {
        val r = root(
            "README.md" to "# hello",
            "Makefile" to "build:",
            "src/Foo.java" to "class Foo {}",
        )
        val snap = scanner.scan(r)
        assertEquals(listOf("java"), snap.files.map { it.languageId })
    }

    @Test
    fun ignoresBuildAndDotDirectories() {
        val r = root(
            "src/Foo.java" to "class Foo {}",
            "build/Foo.class" to "",
            "target/Foo.class" to "",
            "node_modules/lib.js" to "module.exports = {}",
            ".git/config" to "[core]",
            "__pycache__/mod.pyc" to "",
        )
        val snap = scanner.scan(r)
        assertEquals(1, snap.files.size)
        assertEquals("java", snap.files.single().languageId)
    }

    @Test
    fun filesAreSortedByPath() {
        val r = root(
            "z/Z.java" to "class Z {}",
            "a/A.java" to "class A {}",
            "m/M.java" to "class M {}",
        )
        val snap = scanner.scan(r)
        val paths = snap.files.map { it.path.toString() }
        assertEquals(paths.sorted(), paths)
    }

    @Test
    fun snapshotContainsModuleWithSourceRoots() {
        val r = root(
            "src/main/java/Foo.java" to "class Foo {}",
        )
        val snap = scanner.scan(r)
        assertEquals(1, snap.modules.size)
        assertTrue(snap.modules.single().sourceRoots.isNotEmpty())
    }

    @Test
    fun nonExistentRootReturnsEmptySnapshot() {
        val snap = scanner.scan(java.nio.file.Paths.get("/nonexistent/path/xyz"))
        assertTrue(snap.files.isEmpty())
    }
}
