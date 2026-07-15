package org.refactorkit.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtocolPathTest {
    @Test
    fun serializesWindowsOrientedRelativePathsWithForwardSlashesOnEveryHost() {
        assertEquals(
            "module/src/main/java/com/example/Foo.java",
            ProtocolPath.serialize(Path.of("module\\src\\main\\java\\com\\example\\Foo.java")),
        )
        assertEquals("src/main/java/Foo.java", ProtocolPath.serialize(Path.of("src/main/./java/Foo.java")))
    }

    @Test
    fun `parses only canonical forward-slash workspace-relative protocol paths`() {
        assertEquals(Path.of("src", "main", "App.kt"), ProtocolPath.parseRelative("src/main/App.kt"))
        assertEquals("src/main/App.kt", ProtocolPath.serialize(Path.of("src", "main", "App.kt")))

        listOf(
            "",
            "../App.kt",
            "src/../App.kt",
            "/workspace/App.kt",
            "C:/workspace/App.kt",
            "src\\main\\App.kt",
            "src//main/App.kt",
        ).forEach { raw ->
            assertFailsWith<IllegalArgumentException>(raw) { ProtocolPath.parseRelative(raw) }
        }
    }
}
