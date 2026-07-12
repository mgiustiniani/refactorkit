package org.refactorkit.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolPathTest {
    @Test
    fun serializesWindowsOrientedRelativePathsWithForwardSlashesOnEveryHost() {
        assertEquals(
            "module/src/main/java/com/example/Foo.java",
            ProtocolPath.serialize(Path.of("module\\src\\main\\java\\com\\example\\Foo.java")),
        )
        assertEquals("src/main/java/Foo.java", ProtocolPath.serialize(Path.of("src/main/./java/Foo.java")))
    }
}
