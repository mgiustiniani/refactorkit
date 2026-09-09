package org.refactorkit.cli.previewcommand

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JournalState
import org.refactorkit.core.TransactionId
import org.refactorkit.core.TransactionLog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** I1 stress copy only: the separate J1 five-edit fixture and its assertions remain unchanged. */
internal class MavenModuleCrashFixture : AutoCloseable {
    private val root = Files.createTempDirectory("rk-module-directory-crash-")
    private val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
    private val fixture = repository.resolve("testdata/acceptance/java-maven-move-class-authority-20-modules")
    private val fixtureBefore = image(fixture)
    private lateinit var before: Map<String, List<Byte>>
    private lateinit var movedSource: String
    private lateinit var journal: Path
    private var wire: TypeScriptPackagedSurface? = null
    private var conflictLink: Path? = null
    private var protectedReceipt: Pair<Path, List<Byte>>? = null
    private lateinit var conflicted: Map<String, List<Byte>>

    fun prepare() {
        fixtureBefore.forEach { (path, content) ->
            Files.createDirectories(root.resolve(path).parent)
            Files.write(root.resolve(path), content.toByteArray())
        }
        val moduleSources = fixtureBefore.keys.filter { it.startsWith("catalog-model/") && it.endsWith(".java") }
        val original = moduleSources.single()
        movedSource = "catalog-domain/" + original.removePrefix("catalog-model/")
        val packageName = Regex("package ([A-Za-z0-9_.]+);").find(fixtureBefore.getValue(original).toByteArray().toString(Charsets.UTF_8))!!.groupValues[1]
        val sourceDirectory = root.resolve(original).parent
        repeat(512) { index ->
            val name = "RecoveryProbe${index.toString().padStart(3, '0')}"
            Files.writeString(sourceDirectory.resolve("$name.java"), "package $packageName;\npublic final class $name {}\n")
        }
        before = image(root)
        val packaged = System.getProperty("refactorkit.ts.packaged.root")
        wire = if (packaged != null) TypeScriptPackagedSurface(Path.of(packaged), root)
        else TypeScriptPackagedSurface(Path.of(System.getProperty("java.home")), root, System.getProperty("java.class.path"))
    }

    fun applyAndCrash() {
        val client = requireNotNull(wire)
        client.dispatch("daemon", "project.open", buildJsonObject { put("root", root.toString()) })
        val preview = client.dispatch("daemon", "refactor.preview", buildJsonObject {
            put("operation", "renameMavenModule"); put("languageId", "java")
            put("arguments", buildJsonObject {
                put("oldModuleDir", "catalog-model"); put("newModuleDir", "catalog-domain"); put("newArtifactId", "catalog-domain")
            })
        }).jsonObject
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
        // Original three POM modifications + two renames, plus exactly512 independently authored class renames.
        assertEquals(517, preview.getValue("structuredDiff").jsonArray.size)
        assertEquals(before, image(root))
        assertTrue(journals().isEmpty())
        client.killApplying(preview.getValue("planId").jsonPrimitive.content) {
            Files.isRegularFile(root.resolve(movedSource))
        }
        journal = journals().single()
        val interrupted = record()
        client.record("interrupted-journal", interrupted)
        assertEquals("8", interrupted.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("APPLYING", interrupted.getValue("state").jsonPrimitive.content)
        assertEquals("java.renameMavenModule", interrupted.getValue("operation").jsonPrimitive.content)
        val partial = image(root)
        assertTrue(partial != before, "The process must die after a real workspace change")
        assertTrue(movedSource in partial, "A module source file must have moved")
        assertTrue(partial.keys.any { it.startsWith("catalog-model/") && it.endsWith(".java") },
            "The crash must interrupt a partial directory move, not a completed apply")
    }

    fun recoverAndVerify() {
        // killApplying retired the original child: the same wire holder starts a genuinely new daemon.
        val recovered = try {
            requireNotNull(wire).dispatch("daemon", "patch.recover", buildJsonObject { put("root", root.toString()) }).jsonObject
        } finally {
            requireNotNull(wire).record("recovery-journal", record())
        }
        assertEquals("true", recovered.getValue("recovered").jsonPrimitive.content)
        requireNotNull(wire).dispatch("daemon", "project.open", buildJsonObject { put("root", root.toString()) })
        assertEquals(before, image(root))
        assertFalse(Files.exists(root.resolve("catalog-domain")))
        assertEquals(listOf(journal), journals())
        val rolledBack = record()
        assertEquals("ROLLED_BACK", rolledBack.getValue("state").jsonPrimitive.content)
        assertTrue(rolledBack.getValue("failure").jsonPrimitive.content.contains("interrupted applying"))
        Files.walk(root).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().startsWith(".refactorkit-stage-") })
        }
        assertFalse(Files.exists(journal.resolveSibling(".${journal.fileName.toString().removeSuffix(".json")}.staging")))
        assertEquals(fixtureBefore, image(fixture))
    }

    fun introduceConflict(kind: String) {
        val directory = root.resolve(movedSource).parent
        val owned = Files.list(directory).use { paths -> paths.filter {
            it.fileName.toString().startsWith(".refactorkit-stage-transaction-")
        }.findFirst().orElseThrow() }
        when (kind) {
            "unowned stage-like file" -> Files.writeString(
                directory.resolve(".refactorkit-stage-00000000-0000-4000-8000-000000000000.tmp"),
                "External data is not transaction-owned.\n")
            "changed owned stage" -> Files.writeString(owned, "External replacement is not a retained image prefix.\n")
            "symbolic owned stage" -> {
                Files.delete(owned)
                Files.createSymbolicLink(owned, root.resolve(movedSource))
                conflictLink = owned
            }
            "same-byte stage replacement" -> {
                val bytes = Files.readAllBytes(owned)
                // Hold the original inode so the replacement cannot accidentally reuse its identity.
                Files.move(owned, root.resolve(".refactorkit/held-stage-inode"))
                Files.write(owned, bytes)
                assertTrue(bytes.contentEquals(Files.readAllBytes(owned)))
            }
            "corrupt ownership receipt" -> {
                val receipt = journal.resolveSibling(".${journal.fileName.toString().removeSuffix(".json")}.staging")
                val content = Json.parseToJsonElement(Files.readString(receipt)).jsonObject
                Files.writeString(receipt, JsonObject(content + ("checksum" to JsonPrimitive("not-a-valid-checksum"))).toString())
                protectedReceipt = receipt to Files.readAllBytes(receipt).toList()
            }
            else -> error("Unknown conflict: $kind")
        }
        conflicted = image(root)
    }

    fun legacyStagingCollision() {
        val id = TransactionId(journal.fileName.toString().removeSuffix(".json"))
        val current = requireNotNull(TransactionLog(journal.parent).loadRecord(id))
        val legacyDirectory = root.resolve(".refactorkit/legacy-fixture")
        val legacyLog = TransactionLog(legacyDirectory)
        val legacy = current.copy(state = JournalState.PREPARED, history = emptyList(), failure = null)
        // Public unmarked writer form, including its real canonical v8 checksum and two lifecycle events.
        legacyLog.prepare(legacy)
        legacyLog.update(legacy.copy(state = JournalState.APPLYING))
        Files.write(journal, Files.readAllBytes(legacyDirectory.resolve(journal.fileName)))
        val unmarked = requireNotNull(TransactionLog(journal.parent).loadRecord(id))
        assertTrue(unmarked.history.all { it.detail == null })
        val exactNewStylePath = Files.list(root.resolve(movedSource).parent).use { paths -> paths.filter {
            it.fileName.toString().startsWith(".refactorkit-stage-transaction-")
        }.findFirst().orElseThrow() }
        Files.write(exactNewStylePath, byteArrayOf())
        conflicted = image(root)
        requireNotNull(wire).record("legacy-unmarked-journal", record())
    }

    fun reconstructPreStageCollision() {
        val id = TransactionId(journal.fileName.toString().removeSuffix(".json"))
        val current = requireNotNull(TransactionLog(journal.parent).loadRecord(id))
        val preStageDirectory = root.resolve(".refactorkit/pre-stage-fixture")
        val preStageLog = TransactionLog(preStageDirectory)
        val intent = current.copy(state = JournalState.PREPARED, history = current.history.take(1), failure = null)
        preStageLog.prepare(intent)
        preStageLog.update(intent.copy(state = JournalState.APPLYING))
        Files.write(journal, Files.readAllBytes(preStageDirectory.resolve(journal.fileName)))
        Files.deleteIfExists(journal.resolveSibling(".${id.value}.staging"))
        // Replay a valid state immediately after APPLYING, using authored S0, not a returned post-image.
        Files.walk(root).use { paths -> paths.filter {
            it != root && !it.startsWith(root.resolve(".refactorkit"))
        }.toList().sortedByDescending(Path::getNameCount).forEach(Files::delete) }
        before.forEach { (path, bytes) ->
            Files.createDirectories(root.resolve(path).parent); Files.write(root.resolve(path), bytes.toByteArray())
        }
        assertEquals(before, image(root))
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(movedSource.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val foreign = root.resolve(movedSource).parent.resolve(".refactorkit-stage-${id.value}-$key.tmp")
        Files.createDirectories(foreign.parent)
        Files.write(foreign, byteArrayOf())
        conflicted = image(root)
        requireNotNull(wire).record("marked-pre-stage-journal", record())
    }

    fun assertConflictPreserved() {
        val failure = assertFailsWith<JsonRpcException> {
            requireNotNull(wire).dispatch("daemon", "patch.recover", buildJsonObject { put("root", root.toString()) })
        }
        assertTrue(failure.message.contains("requires manual recovery"))
        assertEquals(conflicted, image(root))
        conflictLink?.let { assertTrue(Files.isSymbolicLink(it)) }
        protectedReceipt?.let { (path, bytes) -> assertEquals(bytes, Files.readAllBytes(path).toList()) }
        assertEquals("RECOVERY_REQUIRED", record().getValue("state").jsonPrimitive.content)
        requireNotNull(wire).record("conflicted-journal", record())
        assertEquals(listOf(journal), journals())
        assertEquals(fixtureBefore, image(fixture))
    }

    private fun record(): JsonObject = Json.parseToJsonElement(Files.readString(journal)).jsonObject
    private fun journals(): List<Path> = root.resolve(".refactorkit/transactions").let { directory ->
        if (!Files.exists(directory)) emptyList() else Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.toList()
        }
    }

    private fun image(directory: Path): Map<String, List<Byte>> = Files.walk(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) && !it.startsWith(directory.resolve(".refactorkit")) }.toList()
            .associate { directory.relativize(it).toString().replace('\\', '/') to Files.readAllBytes(it).toList() }
    }

    override fun close() {
        try { wire?.close() } finally {
            assertEquals(fixtureBefore, image(fixture))
            conflictLink?.let { if (Files.isSymbolicLink(it)) Files.delete(it) }
            Files.walk(root).use { paths -> paths.toList().sortedByDescending(Path::getNameCount).forEach {
                check(!Files.isSymbolicLink(it)); Files.delete(it)
            } }
        }
    }
}
