package org.refactorkit.daemon

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkspaceIndexDaemonTest {
    @TempDir lateinit var root: Path

    @Test
    fun `saved file watcher refreshes index before the next read`() {
        write("src/main/java/example/UserService.java", "package example;\npublic class UserService {}\n")
        DaemonSession().use { session ->
            val opened = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
            Files.delete(root.resolve("src/main/java/example/UserService.java"))
            write("src/main/java/example/AccountService.java", "package example;\npublic class AccountService {}\n")
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            var observed = false
            while (System.nanoTime() < deadline) {
                val watch = session.dispatch("workspace.watch.status", null).jsonObject
                if (watch.getValue("dirty").jsonPrimitive.content.toBoolean()) {
                    observed = true
                    break
                }
                Thread.sleep(10)
            }
            assertTrue(observed)

            val refreshed = session.dispatch("index.status", null).jsonObject

            assertNotEquals(opened.getValue("snapshotHash").jsonPrimitive.content,
                refreshed.getValue("snapshotHash").jsonPrimitive.content)
            val query = session.dispatch("intelligence.query", buildJsonObject {
                put("requestId", "watch-query"); put("expectedSnapshotHash", refreshed.getValue("snapshotHash"))
                put("expectedIndexGeneration", refreshed.getValue("generation")); put("kind", "workspaceSymbols")
                put("query", "AccountService"); put("languageId", "java")
            }).jsonObject
            assertEquals("AccountService", query.getValue("items").jsonArray.single().jsonObject
                .getValue("name").jsonPrimitive.content)
            assertFalse(Files.exists(root.resolve(".refactorkit")))
        }
    }

    @Test
    fun `project open builds a read-only whole-workspace index and searches symbols`() {
        write("src/main/java/example/UserService.java", """
            package example;
            public class UserService {
                private int count;
            }
        """.trimIndent())
        write("src/app.ts", "export const answer = 42\n")
        write("src/main/kotlin/example/Greeting.kt", "package example\nclass Greeting\n")

        DaemonSession().use { session ->
            val opened = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
            assertEquals(3, opened.getValue("indexedSourceCount").jsonPrimitive.content.toInt())
            assertTrue(opened.getValue("indexedSymbolCount").jsonPrimitive.content.toInt() >= 1)

            val status = session.dispatch("index.status", null).jsonObject
            assertEquals(setOf("java", "kotlin", "typescript"), status.getValue("languages").jsonArray
                .map { it.jsonPrimitive.content }.toSet())
            assertEquals(false, status.getValue("providers").jsonArray.single().jsonObject
                .getValue("truncated").jsonPrimitive.content.toBoolean())

            val response = session.dispatch("intelligence.query", buildJsonObject {
                put("requestId", "workspace-search-1")
                put("expectedSnapshotHash", opened.getValue("snapshotHash").jsonPrimitive.content)
                put("kind", "workspaceSymbols")
                put("query", "User")
                put("languageId", "java")
                put("limit", 20)
            }).jsonObject
            assertEquals("ready", response.getValue("status").jsonPrimitive.content)
            assertEquals("declarations", response.getValue("coverage").jsonPrimitive.content)
            val item = response.getValue("items").jsonArray.map { it.jsonObject }.single {
                it.getValue("symbolKind").jsonPrimitive.content == "class"
            }
            assertEquals("UserService", item.getValue("name").jsonPrimitive.content)
            assertEquals("lexical", item.getValue("evidence").jsonPrimitive.content)
            assertEquals(
                "src/main/java/example/UserService.java",
                item.getValue("location").jsonObject.getValue("path").jsonPrimitive.content,
            )
        }

        assertFalse(Files.exists(root.resolve(".refactorkit")))
        assertFalse(Files.exists(root.resolve("workspace.lock")))
    }

    @Test
    fun `query refuses stale snapshots and unimplemented interactive kinds`() {
        write("Example.java", "public class Example {}\n")
        DaemonSession().use { session ->
            val opened = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
            val stale = session.dispatch("intelligence.query", buildJsonObject {
                put("requestId", "stale-1")
                put("expectedSnapshotHash", "0".repeat(64))
                put("kind", "workspaceSymbols")
            }).jsonObject
            assertEquals("refused", stale.getValue("status").jsonPrimitive.content)
            assertEquals(
                "intelligence.snapshotStale",
                stale.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
            )

            val staleGeneration = session.dispatch("intelligence.query", buildJsonObject {
                put("requestId", "generation-1")
                put("expectedSnapshotHash", opened.getValue("snapshotHash").jsonPrimitive.content)
                put("expectedIndexGeneration", 999)
                put("kind", "workspaceSymbols")
            }).jsonObject
            assertEquals(
                "intelligence.indexStale",
                staleGeneration.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
            )

            val hover = session.dispatch("intelligence.query", buildJsonObject {
                put("requestId", "hover-1")
                put("expectedSnapshotHash", opened.getValue("snapshotHash").jsonPrimitive.content)
                put("kind", "hover")
            }).jsonObject
            assertEquals("refused", hover.getValue("status").jsonPrimitive.content)
            assertEquals(
                "intelligence.hoverOverlayRequired",
                hover.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
            )
        }
    }

    private fun write(relative: String, content: String) {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent ?: root)
        Files.writeString(target, content)
    }
}
