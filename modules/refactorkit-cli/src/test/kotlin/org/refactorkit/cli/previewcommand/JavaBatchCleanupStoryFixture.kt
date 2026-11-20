package org.refactorkit.cli.previewcommand

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JournalState
import org.refactorkit.core.TransactionId
import org.refactorkit.core.TransactionLog
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** Scenario-owned fixture for the existing low-risk batch Story; no production state. */
internal class JavaBatchCleanupStoryFixture(private val root: Path, private val sdk: Path) : AutoCloseable {
    private val rpc = newGradleContextRpcSession()
    private val first = "src/main/java/com/acme/CleanupA.java"
    private val second = "src/main/java/com/acme/CleanupB.java"
    private lateinit var before: Map<String, String>
    private lateinit var expectedUnchanged: Map<String, String>
    private lateinit var snapshot: String
    private lateinit var context: JsonObject
    private var response: JsonObject? = null
    private var refusal = "none"

    private fun source(name: String, sorted: Boolean) = "package com.acme;\n\n" +
        (if (sorted) "import java.util.ArrayList;\nimport java.util.List;\n" else "import java.util.List;\nimport java.util.ArrayList;\n") +
        "\npublic class $name {\n    List<String> values = new ArrayList<>();\n}\n"

    fun preview(case: String, snapshotState: String) {
        require(case in setOf("two-disjoint-import-organizers", "duplicate-organizer-target",
            "organizer-plus-visibility-widening", "organizer-plus-safe-delete"))
        require(snapshotState in setOf("current", "stale"))
        Files.writeString(root.resolve(first), source("CleanupA", false))
        Files.writeString(root.resolve(second), source("CleanupB", false))
        before = files()
        snapshot = rpc.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val view = rpc.dispatch("project.context", buildJsonObject {
            put("sdkHome", sdk.toString()); put("expectedSnapshotHash", snapshot)
        }).jsonObject
        assertEquals("available", view.getValue("status").jsonPrimitive.content, view.toString())
        val selectedSdk = view.getValue("sdk").jsonArray.single().jsonObject
        assertEquals(sdk, Path.of(selectedSdk.getValue("home").jsonPrimitive.content).toRealPath())
        fun hash(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
        assertEquals(hash(sdk.resolve("release")), selectedSdk.getValue("releaseMetadataSha256").jsonPrimitive.content)
        assertEquals(hash(sdk.resolve("lib/ct.sym")), selectedSdk.getValue("signatureArchiveSha256").jsonPrimitive.content)
        assertEquals("8", selectedSdk.getValue("sourceRelease").jsonPrimitive.content)
        context = buildJsonObject { put("sdkHome", sdk.toString()); put("expectedContextHash", view.getValue("contextHash")) }
        if (snapshotState == "stale") Files.writeString(root.resolve(first), before.getValue(first) + "// caller drift\n")
        expectedUnchanged = files()
        try {
            response = rpc.dispatch("refactor.preview", buildJsonObject {
                put("languageId", "java"); put("operation", "batchCleanup")
                put("expectedSnapshotHash", snapshot); put("javaContext", context)
                put("arguments", buildJsonObject {
                    put("actions", buildJsonArray {
                        fun action(operation: String, file: String) = add(buildJsonObject {
                            put("operation", operation); put("arguments", buildJsonObject { put("file", file) })
                        })
                        action("organizeImports", first)
                        when (case) {
                            "two-disjoint-import-organizers" -> action("organizeImports", second)
                            "duplicate-organizer-target" -> action("organizeImports", first)
                            "organizer-plus-visibility-widening" -> action("changeVisibility", second)
                            "organizer-plus-safe-delete" -> action("safeDelete", second)
                        }
                    })
                })
            }).jsonObject
        } catch (failure: JsonRpcException) {
            refusal = failure.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content ?: "rpc.${failure.code}"
        }
    }

    fun assertResult(status: String, expectedRefusal: String) {
        assertEquals(status, response?.get("status")?.jsonPrimitive?.content ?: "REFUSED")
        assertEquals(expectedRefusal, refusal)
        assertEquals(expectedUnchanged, files(), "Every preview/refusal must preserve all captured source/build bytes")
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        if (status == "PREVIEW") {
            val preview = assertNotNull(response)
            assertEquals("batchCleanup", preview.getValue("operation").jsonPrimitive.content)
            assertEquals(context.getValue("expectedContextHash"), preview.getValue("contextHash"))
            assertFalse(preview.containsKey("formatterProfileSha256"), "An ordinary batch must not acquire an unselected formatter profile")
        }
    }

    fun applyAndRollback() {
        val preview = response
        if (preview == null) {
            assertEquals(expectedUnchanged, files())
            assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
            return
        }
        val applied = rpc.dispatch("refactor.apply", buildJsonObject {
            put("planId", preview.getValue("planId")); put("javaContext", context); put("expectedSnapshotHash", snapshot)
        }).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        assertEquals(before + mapOf(first to source("CleanupA", true), second to source("CleanupB", true)), files())
        val transaction = applied.getValue("transactionId").jsonPrimitive.content
        val rolled = rpc.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", transaction); put("javaContext", context)
            put("expectedSnapshotHash", applied.getValue("snapshotHash"))
        }).jsonObject
        assertEquals("rolledBack", rolled.getValue("status").jsonPrimitive.content)
        assertEquals(snapshot, rolled.getValue("snapshotHash").jsonPrimitive.content)
        assertEquals(context.getValue("expectedContextHash"), rolled.getValue("rolledBackContextHash"))
        assertEquals(before, files())
        assertEquals(JournalState.ROLLED_BACK, assertNotNull(TransactionLog(root.resolve(".refactorkit/transactions"))
            .loadRecord(TransactionId(transaction))).state)
    }

    private fun files(): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && !root.relativize(it).startsWith(".refactorkit") }
            .sorted().toList().associate { root.relativize(it).toString() to Files.readString(it) }
    }

    override fun close() = rpc.close()
}
