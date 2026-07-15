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
    fun `java definition uses one snapshot keyed JDT analysis and publishes semantic partition`() {
        write("src/main/java/example/UserService.java", """
            package example;
            public class UserService {
                /** Greets the caller. */
                public String greet() { return "hello"; }
            }
        """.trimIndent())
        val useLine = "        return service.greet();"
        write("src/main/java/example/Use.java",
            "package example;\npublic class Use {\n    String run(UserService service) {\n$useLine\n    }\n}\n")
        DaemonSession().use { session ->
            val opened = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
            fun definition(generation: Long, requestId: String) = session.dispatch("intelligence.query", buildJsonObject {
                put("requestId", requestId); put("expectedSnapshotHash", opened.getValue("snapshotHash"))
                put("expectedIndexGeneration", generation); put("kind", "definition"); put("languageId", "java")
                put("path", "src/main/java/example/Use.java")
                put("position", buildJsonObject { put("line", 3); put("character", useLine.indexOf("greet") + 1) })
                put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
            }).jsonObject

            val first = definition(opened.getValue("indexGeneration").jsonPrimitive.content.toLong(), "java-def-1")
            assertEquals("ready", first.getValue("status").jsonPrimitive.content)
            assertEquals("src/main/java/example/UserService.java", first.getValue("locations").jsonArray.single()
                .jsonObject.getValue("path").jsonPrimitive.content)
            assertEquals(1, first.getValue("cache").jsonObject.getValue("misses").jsonPrimitive.content.toInt())
            val second = definition(first.getValue("indexGeneration").jsonPrimitive.content.toLong(), "java-def-2")
            assertEquals(1, second.getValue("cache").jsonObject.getValue("hits").jsonPrimitive.content.toInt())
            fun references(includeDeclaration: Boolean, requestId: String) = session.dispatch(
                "intelligence.query", buildJsonObject {
                    put("requestId", requestId); put("expectedSnapshotHash", opened.getValue("snapshotHash"))
                    put("expectedIndexGeneration", second.getValue("indexGeneration")); put("kind", "references")
                    put("languageId", "java"); put("path", "src/main/java/example/Use.java")
                    put("includeDeclaration", includeDeclaration); put("limit", 10)
                    put("position", buildJsonObject { put("line", 3); put("character", useLine.indexOf("greet") + 1) })
                    put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
                },
            ).jsonObject
            val withDeclaration = references(true, "java-refs-1")
            assertEquals(2, withDeclaration.getValue("total").jsonPrimitive.content.toInt())
            assertTrue(withDeclaration.getValue("complete").jsonPrimitive.content.toBoolean())
            assertEquals(0, withDeclaration.getValue("warningCount").jsonPrimitive.content.toInt())
            assertEquals(
                setOf("src/main/java/example/UserService.java", "src/main/java/example/Use.java"),
                withDeclaration.getValue("references").jsonArray.map {
                    it.jsonObject.getValue("path").jsonPrimitive.content
                }.toSet(),
            )
            assertEquals(2, withDeclaration.getValue("cache").jsonObject.getValue("hits").jsonPrimitive.content.toInt())
            val withoutDeclaration = references(false, "java-refs-2")
            assertEquals(1, withoutDeclaration.getValue("total").jsonPrimitive.content.toInt())
            assertEquals("src/main/java/example/Use.java", withoutDeclaration.getValue("references").jsonArray.single()
                .jsonObject.getValue("path").jsonPrimitive.content)
            val truncated = session.dispatch("intelligence.query", buildJsonObject {
                put("requestId", "java-refs-truncated"); put("expectedSnapshotHash", opened.getValue("snapshotHash"))
                put("expectedIndexGeneration", second.getValue("indexGeneration")); put("kind", "references")
                put("languageId", "java"); put("path", "src/main/java/example/Use.java"); put("limit", 1)
                put("position", buildJsonObject { put("line", 3); put("character", useLine.indexOf("greet") + 1) })
                put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
            }).jsonObject
            assertEquals(2, truncated.getValue("total").jsonPrimitive.content.toInt())
            assertEquals(1, truncated.getValue("returned").jsonPrimitive.content.toInt())
            assertTrue(truncated.getValue("truncated").jsonPrimitive.content.toBoolean())
            val hover = session.dispatch("intelligence.query", buildJsonObject {
                put("requestId", "java-hover"); put("expectedSnapshotHash", opened.getValue("snapshotHash"))
                put("expectedIndexGeneration", second.getValue("indexGeneration")); put("kind", "hover")
                put("languageId", "java"); put("path", "src/main/java/example/Use.java")
                put("position", buildJsonObject { put("line", 3); put("character", useLine.indexOf("greet") + 1) })
                put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
            }).jsonObject
            assertTrue(hover.getValue("contents").jsonArray.first().jsonObject.getValue("value")
                .jsonPrimitive.content.contains("java.lang.String greet()"))
            assertTrue(hover.getValue("contents").jsonArray.any {
                it.jsonObject.getValue("value").jsonPrimitive.content.contains("Greets the caller")
            })
            assertEquals(3, hover.getValue("range").jsonObject.getValue("start").jsonObject
                .getValue("line").jsonPrimitive.content.toInt())
            assertTrue(hover.getValue("complete").jsonPrimitive.content.toBoolean())
            assertTrue(session.dispatch("index.status", null).jsonObject.getValue("providers").jsonArray.any {
                it.jsonObject.getValue("providerId").jsonPrimitive.content == "java-jdt-bindings-v1"
            })
            write("src/main/java/example/UserService.java", "package example;\npublic class UserService {}\n")
            val refreshed = session.dispatch("workspace.refresh", null).jsonObject
            assertTrue(refreshed.getValue("invalidatedProviders").jsonArray.any {
                it.jsonPrimitive.content == "java-jdt-bindings-v1"
            })
            assertTrue(session.dispatch("index.status", null).jsonObject.getValue("providers").jsonArray.none {
                it.jsonObject.getValue("providerId").jsonPrimitive.content == "java-jdt-bindings-v1"
            })
            assertFalse(Files.exists(root.resolve(".refactorkit")))
        }
    }

    @Test
    fun `saved file watcher refreshes index before the next read`() {
        write("src/main/java/example/UserService.java", "package example;\npublic class UserService {}\n")
        DaemonSession().use { session ->
            val opened = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
            Files.delete(root.resolve("src/main/java/example/UserService.java"))
            write("src/main/java/example/AccountService.java", "package example;\npublic class AccountService {}\n")
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
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
