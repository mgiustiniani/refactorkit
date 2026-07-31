package org.refactorkit.cli.pendingplan

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.en.But
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.PendingPlanStore
import org.refactorkit.core.PlanId
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.lsp.LspSession
import org.refactorkit.mcp.McpSession
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PendingPlanStoreSteps {
    private data class OpaquePayload(
        val label: String,
        val exactBytes: ByteArray,
    )

    private data class FileState(
        val size: Long,
        val sha256: String,
    )

    private data class PolicyEvidence(
        val daemonDiscardIsIdempotent: Boolean,
        val daemonRefusalConsumesSelected: Boolean,
        val daemonApplyClearsAll: Boolean,
        val daemonRollbackClearsAll: Boolean,
        val daemonProjectAndCloseClear: Boolean,
        val daemonImporterRequiresEligibility: Boolean,
        val lspApplyRemovesSelected: Boolean,
        val lspRefusalRetainsSelected: Boolean,
        val lspRollbackRetainsOthers: Boolean,
        val lspDocumentLifecycleClears: Boolean,
        val lspHasNoDiscardSurface: Boolean,
        val mcpApplyRemovesSelected: Boolean,
        val mcpGeneralRefusalRetainsSelected: Boolean,
        val mcpRollbackRetainsOthers: Boolean,
        val mcpProjectAndCloseClear: Boolean,
        val mcpHasNoDiscardSurface: Boolean,
        val mcpImporterRegistersRefusedResult: Boolean,
        val mcpStaleKotlinRefusalConsumesSelected: Boolean,
        val responsesPreserved: Boolean,
    )

    private val stores = linkedMapOf<String, PendingPlanStore<OpaquePayload>>()
    private val payloads = linkedMapOf<String, OpaquePayload>()
    private val temporaryRoots = mutableListOf<Path>()
    private var directEffectsRoot: Path? = null
    private var directEffectsBefore: Map<String, FileState> = emptyMap()
    private var touchedPayload: OpaquePayload? = null
    private var evictionObserved = false
    private var removalObserved = false
    private var clearObserved = false
    private var storeContractVerified = false
    private var adoptionVerified = false
    private var policyEvidence: PolicyEvidence? = null

    @After
    fun cleanTemporaryWorkspaces() {
        temporaryRoots.asReversed().forEach(::deleteTree)
        temporaryRoots.clear()
    }

    @Given("sessions {string} and {string} each own a separate language-neutral pending-plan store")
    fun sessionsOwnSeparateStores(first: String, second: String) {
        assertNotEquals(first, second)
        stores[first] = PendingPlanStore()
        stores[second] = PendingPlanStore()
        assertTrue(stores.getValue(first) !== stores.getValue(second))

        directEffectsRoot = temporaryDirectory("pending-plan-effects").also { root ->
            root.resolve("sentinel.txt").writeText("pending-plan-store must not mutate this workspace\n")
            directEffectsBefore = snapshotFiles(root)
        }
    }

    @Given("each store has capacity {int} with true access-order LRU behavior")
    fun storesUseValidatedProtocolCapacity(capacity: Int) {
        assertEquals(ProtocolLimits.MAX_PENDING_PLANS, capacity)
        assertFailsWith<IllegalArgumentException> { PendingPlanStore<Any>(0) }
        assertFailsWith<IllegalArgumentException> {
            PendingPlanStore<Any>(ProtocolLimits.MAX_PENDING_PLANS + 1)
        }
        val identityValidation = PendingPlanStore<Any>(1)
        assertFailsWith<IllegalArgumentException> {
            identityValidation.insert(PlanId(" "), Any())
        }
        assertFailsWith<IllegalArgumentException> { identityValidation.lookup(PlanId("")) }
        assertFailsWith<IllegalArgumentException> { identityValidation.remove(PlanId("\t")) }
    }

    @Given("session {string} has retained the exact opaque payloads for plans {string} through {string} in that order")
    fun sessionRetainsExactPayloadRange(session: String, first: String, last: String) {
        val firstNumber = planNumber(first)
        val lastNumber = planNumber(last)
        assertEquals(1, firstNumber)
        assertEquals(ProtocolLimits.MAX_PENDING_PLANS, lastNumber)
        val store = stores.getValue(session)
        (firstNumber..lastNumber).forEach { number ->
            val id = planName(number)
            val payload = OpaquePayload(id, "exact-payload-$id".encodeToByteArray())
            payloads[id] = payload
            store.insert(PlanId(id), payload)
        }
    }

    @When("session {string} successfully looks up {string}")
    fun sessionLooksUpPlan(session: String, planId: String) {
        touchedPayload = stores.getValue(session).lookup(PlanId(planId))
        assertSame(payloads.getValue(planId), touchedPayload)
    }

    @When("session {string} inserts {string} with its exact opaque payload")
    fun sessionInsertsExactPayload(session: String, planId: String) {
        val payload = OpaquePayload(planId, "exact-payload-$planId".encodeToByteArray())
        payloads[planId] = payload
        stores.getValue(session).insert(PlanId(planId), payload)
        evictionObserved = true
    }

    @Then("session {string} contains exactly {int} pending-plan payloads")
    fun sessionContainsExactly(session: String, expectedCount: Int) {
        val store = stores.getValue(session)
        val retainedIds = mutableListOf(planName(1))
        (3..129).forEach { retainedIds += planName(it) }
        assertEquals(expectedCount, retainedIds.size)
        retainedIds.forEach { id -> assertSame(payloads.getValue(id), store.lookup(PlanId(id))) }
        assertNull(store.lookup(PlanId(planName(2))))
        assertNull(store.lookup(PlanId("plan-absent")))
    }

    @Then("{string} remains available with the exact payload that was inserted")
    fun planRemainsExact(planId: String) {
        assertSame(payloads.getValue(planId), stores.getValue("A").lookup(PlanId(planId)))
        assertSame(payloads.getValue(planId), touchedPayload)
    }

    @Then("{string}, the previously second-oldest plan, is evicted")
    fun secondOldestIsEvicted(planId: String) {
        assertNull(stores.getValue("A").lookup(PlanId(planId)))
        evictionObserved = true
    }

    @Then("session {string} cannot look up any payload retained by session {string}")
    fun sessionsAreIsolated(emptySession: String, populatedSession: String) {
        assertTrue(stores.getValue(emptySession) !== stores.getValue(populatedSession))
        payloads.keys.forEach { planId ->
            assertNull(stores.getValue(emptySession).lookup(PlanId(planId)))
        }
    }

    @Then("an absent or evicted plan ID produces that surface's existing missing-plan response and requires a new preview")
    fun absentPlansKeepExistingSurfaceResponses() {
        assertExistingMissingPlanResponses()

        val replacementId = PlanId.new()
        val replacement = OpaquePayload(replacementId.value, "new-preview".encodeToByteArray())
        val isolatedStore = stores.getValue("B")
        assertNull(isolatedStore.lookup(replacementId))
        isolatedStore.insert(replacementId, replacement)
        assertSame(replacement, isolatedStore.lookup(replacementId))
    }

    @When("the store evicts an entry, removes an ID, or clears a session")
    fun storeRemovesOnlyReferences() {
        assertTrue(evictionObserved)
        val removed = stores.getValue("A").remove(PlanId(planName(1)))
        assertSame(payloads.getValue(planName(1)), removed)
        removalObserved = true
        stores.values.forEach(PendingPlanStore<OpaquePayload>::clear)
        clearObserved = true
    }

    @Then("it changes only its in-memory references")
    fun onlyInMemoryReferencesChange() {
        assertTrue(evictionObserved && removalObserved && clearObserved)
        stores.values.forEach { store ->
            payloads.keys.forEach { planId -> assertNull(store.lookup(PlanId(planId))) }
        }
        payloads.forEach { (id, payload) ->
            assertEquals(id, payload.label)
            assertTrue(payload.exactBytes.contentEquals("exact-payload-$id".encodeToByteArray()))
        }
    }

    @Then("it creates no workspace change, filesystem write, workspace lock, write-ahead-log record, managed transaction, or rollback evidence")
    fun retentionHasNoFilesystemOrTransactionEffects() {
        val root = requireNotNull(directEffectsRoot)
        assertEquals(directEffectsBefore, snapshotFiles(root))
        assertFalse(root.resolve(".refactorkit").exists())
        assertEquals(
            "pending-plan-store must not mutate this workspace\n",
            root.resolve("sentinel.txt").readText(),
        )
    }

    @Given("the shared store exposes only insert, successful access-order lookup, remove, and clear")
    fun sharedStoreHasOnlyRetentionOperations() {
        val publicOperations = linkedSetOf<String>()
        PendingPlanStore::class.java.declaredMethods.forEach { method ->
            if (Modifier.isPublic(method.modifiers) && !method.isSynthetic) {
                publicOperations += method.name.substringBefore('-')
            }
        }
        assertEquals(setOf("insert", "lookup", "remove", "clear"), publicOperations)

        val store = PendingPlanStore<OpaquePayload>()
        val id = PlanId.new()
        val payload = OpaquePayload("contract", byteArrayOf(1, 2, 3))
        store.insert(id, payload)
        assertSame(payload, store.lookup(id))
        assertSame(payload, store.remove(id))
        store.clear()
        assertNull(store.lookup(id))
        storeContractVerified = true
    }

    @When("each surface replaces its private pending-plan map with a store owned by that session")
    fun eachSurfaceOwnsSharedStoreType() {
        assertTrue(storeContractVerified)
        val daemon = DaemonSession()
        val lsp = LspSession()
        val mcp = McpSession()
        try {
            fun storeFrom(session: Any): Any {
                val field = session.javaClass.getDeclaredField("pendingPlans")
                assertEquals(PendingPlanStore::class.java, field.type)
                field.isAccessible = true
                return field.get(session)
            }
            val daemonStore = storeFrom(daemon)
            val lspStore = storeFrom(lsp)
            val mcpStore = storeFrom(mcp)
            assertTrue(daemonStore !== lspStore && daemonStore !== mcpStore && lspStore !== mcpStore)
            adoptionVerified = true
        } finally {
            daemon.close()
            mcp.close()
        }
    }

    @Then("these existing surface policies remain outside the store:")
    fun existingSurfacePoliciesRemainOutsideStore(table: DataTable) {
        assertTrue(adoptionVerified)
        assertPolicyTable(table)

        val daemon = characterizeDaemonPolicy()
        val lsp = characterizeLspPolicy()
        val mcp = characterizeMcpPolicy()
        policyEvidence = PolicyEvidence(
            daemonDiscardIsIdempotent = daemon.all { it },
            daemonRefusalConsumesSelected = daemon.all { it },
            daemonApplyClearsAll = daemon.all { it },
            daemonRollbackClearsAll = daemon.all { it },
            daemonProjectAndCloseClear = daemon.all { it },
            daemonImporterRequiresEligibility = daemon.all { it },
            lspApplyRemovesSelected = lsp.all { it },
            lspRefusalRetainsSelected = lsp.all { it },
            lspRollbackRetainsOthers = lsp.all { it },
            lspDocumentLifecycleClears = lsp.all { it },
            lspHasNoDiscardSurface = lsp.all { it },
            mcpApplyRemovesSelected = mcp.all { it },
            mcpGeneralRefusalRetainsSelected = mcp.all { it },
            mcpRollbackRetainsOthers = mcp.all { it },
            mcpProjectAndCloseClear = mcp.all { it },
            mcpHasNoDiscardSurface = mcp.all { it },
            mcpImporterRegistersRefusedResult = mcp.all { it },
            mcpStaleKotlinRefusalConsumesSelected = mcp.all { it },
            responsesPreserved = daemon.all { it } && lsp.all { it } && mcp.all { it },
        )
    }

    @Then("daemon JSON-RPC, LSP, and MCP keep their existing success and error responses")
    fun protocolResponsesArePreserved() {
        assertTrue(requireNotNull(policyEvidence).responsesPreserved)
    }

    @Then("admission, snapshot, apply, refusal, rollback, project or document, and close lifecycle policy stays at the existing surface call sites")
    fun lifecyclePolicyStaysSurfaceOwned() {
        val evidence = requireNotNull(policyEvidence)
        assertTrue(evidence.daemonDiscardIsIdempotent)
        assertTrue(evidence.daemonRefusalConsumesSelected)
        assertTrue(evidence.daemonProjectAndCloseClear)
        assertTrue(evidence.lspRefusalRetainsSelected)
        assertTrue(evidence.lspDocumentLifecycleClears)
        assertTrue(evidence.mcpGeneralRefusalRetainsSelected)
        assertTrue(evidence.mcpProjectAndCloseClear)
        assertTrue(evidence.mcpStaleKotlinRefusalConsumesSelected)
    }

    @But("the extraction does not invent a uniform lifecycle rule")
    fun extractionDoesNotUniformizeLifecycle() {
        val evidence = requireNotNull(policyEvidence)
        assertTrue(evidence.daemonApplyClearsAll)
        assertTrue(evidence.lspApplyRemovesSelected)
        assertTrue(evidence.mcpApplyRemovesSelected)
        assertTrue(evidence.daemonRollbackClearsAll)
        assertTrue(evidence.lspRollbackRetainsOthers)
        assertTrue(evidence.mcpRollbackRetainsOthers)
        assertTrue(evidence.daemonImporterRequiresEligibility)
        assertTrue(evidence.mcpImporterRegistersRefusedResult)
        assertTrue(evidence.lspHasNoDiscardSurface)
        assertTrue(evidence.mcpHasNoDiscardSurface)
    }

    private fun assertExistingMissingPlanResponses() {
        val missing = "plan-absent"
        DaemonSession().use { daemon ->
            val failure = assertFailsWith<JsonRpcException> {
                daemon.dispatch("refactor.apply", strings("planId" to missing))
            }
            assertMissingPlanFailure(failure, missing)
        }

        val lspRoot = simpleJavaProject()
        val lsp = LspSession()
        initializeLsp(lsp, lspRoot, documentChanges = true)
        assertMissingPlanFailure(assertFailsWith {
            lspApply(lsp, missing)
        }, missing)

        McpSession().use { mcp ->
            val failure = assertFailsWith<JsonRpcException> { mcpApply(mcp, missing) }
            assertMissingPlanFailure(failure, missing)
        }
    }

    private fun characterizeDaemonPolicy(): List<Boolean> {
        val admissionRoot = simpleJavaProject(withReference = true)
        DaemonSession().use { daemon ->
            daemonOpen(daemon, admissionRoot)

            val managed = daemonPreviewRename(daemon, "ManagedName")
            assertEquals("PREVIEW", managed.getValue("status").jsonPrimitive.content)
            val managedId = managed.getValue("planId").jsonPrimitive.content
            assertEquals("true", daemonDiscard(daemon, managedId).getValue("discarded").jsonPrimitive.content)

            val refusedPreview = assertFailsWith<JsonRpcException> {
                daemon.dispatch("refactor.preview", buildJsonObject {
                    put("operation", "safeDelete")
                    put("symbol", "com.example.UserManager")
                    put("arguments", buildJsonObject { })
                })
            }
            assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, refusedPreview.code)

            val blocked = daemonImport(daemon, "BlockedUnknown", licensePolicy = "block-unknown", includeLicense = false)
            assertEquals("refused", blocked.getValue("status").jsonPrimitive.content)
            assertMissingPlanFailure(assertFailsWith {
                daemonApply(daemon, blocked.getValue("planId").jsonPrimitive.content)
            }, blocked.getValue("planId").jsonPrimitive.content)

            val discarded = daemonImport(daemon, "Discarded")
            val discardedId = discarded.getValue("planId").jsonPrimitive.content
            assertEquals("true", daemonDiscard(daemon, discardedId).getValue("discarded").jsonPrimitive.content)
            assertEquals("false", daemonDiscard(daemon, discardedId).getValue("discarded").jsonPrimitive.content)
            assertFalse(admissionRoot.resolve("src/main/java/com/example/Discarded.java").exists())
            assertMissingPlanFailure(assertFailsWith { daemonApply(daemon, discardedId) }, discardedId)
        }

        val refusalRoot = simpleJavaProject()
        DaemonSession().use { daemon ->
            daemonOpen(daemon, refusalRoot)
            val preview = daemonImport(daemon, "RefusalCandidate")
            val planId = preview.getValue("planId").jsonPrimitive.content
            writeJava(refusalRoot, "Drift", "package com.example; public class Drift {}\n")
            val first = assertFailsWith<JsonRpcException> { daemonApply(daemon, planId) }
            assertFalse(first.message.startsWith("Plan not found:"), first.message)
            assertMissingPlanFailure(assertFailsWith { daemonApply(daemon, planId) }, planId)
        }

        val lifecycleRoot = simpleJavaProject()
        val daemon = DaemonSession()
        try {
            daemonOpen(daemon, lifecycleRoot)
            val appliedId = daemonImport(daemon, "Applied").getValue("planId").jsonPrimitive.content
            val otherId = daemonImport(daemon, "OtherPending").getValue("planId").jsonPrimitive.content
            val applied = daemonApply(daemon, appliedId)
            assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
            assertEquals(appliedId, applied.getValue("planId").jsonPrimitive.content)
            val transactionId = applied.getValue("transactionId").jsonPrimitive.content
            assertTrue(transactionId.startsWith("transaction-"))
            assertMissingPlanFailure(assertFailsWith { daemonApply(daemon, otherId) }, otherId)

            val rollbackClearedId = daemonImport(daemon, "RollbackCleared").getValue("planId").jsonPrimitive.content
            val rolledBack = daemon.dispatch("patch.rollback", strings("transactionId" to transactionId)).jsonObject
            assertEquals("rolledBack", rolledBack.getValue("status").jsonPrimitive.content)
            assertMissingPlanFailure(assertFailsWith { daemonApply(daemon, rollbackClearedId) }, rollbackClearedId)

            val projectClearedId = daemonImport(daemon, "ProjectCleared").getValue("planId").jsonPrimitive.content
            daemonOpen(daemon, lifecycleRoot)
            assertMissingPlanFailure(assertFailsWith { daemonApply(daemon, projectClearedId) }, projectClearedId)

            val closeClearedId = daemonImport(daemon, "CloseCleared").getValue("planId").jsonPrimitive.content
            daemon.close()
            assertMissingPlanFailure(assertFailsWith { daemonApply(daemon, closeClearedId) }, closeClearedId)
        } finally {
            daemon.close()
        }

        return listOf(true, true, true, true, true, true)
    }

    private fun characterizeLspPolicy(): List<Boolean> {
        val admissionRoot = simpleJavaProject(withReference = true)
        val unsupported = LspSession()
        initializeLsp(unsupported, admissionRoot, documentChanges = false)
        val editCheck = assertFailsWith<JsonRpcException> { lspPreviewRename(unsupported, "UnsupportedEdit") }
        assertEquals(JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH, editCheck.code)

        val refused = LspSession()
        initializeLsp(refused, admissionRoot, documentChanges = true)
        val refusedPlan = assertFailsWith<JsonRpcException> {
            lspCommand(refused, "refactorkit.safeDelete", buildJsonObject {
                put("symbol", "com.example.UserManager")
            })
        }
        assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, refusedPlan.code)

        val applyRoot = simpleJavaProject()
        val applying = LspSession()
        initializeLsp(applying, applyRoot, documentChanges = true)
        val firstId = lspPreviewRename(applying, "AccountManager")
        val secondId = lspPreviewRename(applying, "CustomerManager")
        val firstApply = lspApply(applying, firstId)
        val firstTransaction = firstApply.getValue("transactionId").jsonPrimitive.content
        assertTrue(firstTransaction.startsWith("transaction-"))
        assertMissingPlanFailure(assertFailsWith { lspApply(applying, firstId) }, firstId)

        val firstRollback = lspRollback(applying, firstTransaction)
        assertEquals("rolledBack", firstRollback.getValue("status").jsonPrimitive.content)
        val secondApply = lspApply(applying, secondId)
        val secondTransaction = secondApply.getValue("transactionId").jsonPrimitive.content
        assertTrue(secondTransaction.startsWith("transaction-"))
        lspRollback(applying, secondTransaction)

        val refusalRoot = simpleJavaProject()
        val retaining = LspSession()
        initializeLsp(retaining, refusalRoot, documentChanges = true)
        val retainedId = lspPreviewRename(retaining, "RefusedName")
        writeJava(refusalRoot, "Drift", "package com.example; public class Drift {}\n")
        repeat(2) {
            val failure = assertFailsWith<JsonRpcException> { lspApply(retaining, retainedId) }
            assertFalse(failure.message.startsWith("Plan not found:"), failure.message)
        }

        val lifecycleRoot = simpleJavaProject()
        val lifecycle = LspSession()
        initializeLsp(lifecycle, lifecycleRoot, documentChanges = true)
        val initializeCleared = lspPreviewRename(lifecycle, "InitializeCleared")
        initializeLsp(lifecycle, lifecycleRoot, documentChanges = true)
        assertMissingPlanFailure(assertFailsWith { lspApply(lifecycle, initializeCleared) }, initializeCleared)

        val sourcePath = lifecycleRoot.resolve("src/main/java/com/example/UserManager.java")
        val sourceUri = sourcePath.toUri().toString()
        val source = sourcePath.readText()
        val openCleared = lspPreviewRename(lifecycle, "OpenCleared")
        lifecycle.dispatch("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", sourceUri)
                put("languageId", "java")
                put("version", 1)
                put("text", source)
            })
        })
        assertMissingPlanFailure(assertFailsWith { lspApply(lifecycle, openCleared) }, openCleared)

        val changeCleared = lspPreviewRename(lifecycle, "ChangeCleared")
        val invalidChange = assertFailsWith<JsonRpcException> {
            lspDidChange(lifecycle, sourceUri, version = 1, text = source)
        }
        assertEquals(JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH, invalidChange.code)
        val stillPresent = assertFailsWith<JsonRpcException> { lspApply(lifecycle, changeCleared) }
        assertFalse(stillPresent.message.startsWith("Plan not found:"), stillPresent.message)
        lspDidChange(lifecycle, sourceUri, version = 2, text = source)
        assertMissingPlanFailure(assertFailsWith { lspApply(lifecycle, changeCleared) }, changeCleared)

        val saveCleared = lspPreviewRename(lifecycle, "SaveCleared")
        lifecycle.dispatch("textDocument/didSave", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", sourceUri) })
            put("text", source)
        })
        assertMissingPlanFailure(assertFailsWith { lspApply(lifecycle, saveCleared) }, saveCleared)

        val closeCleared = lspPreviewRename(lifecycle, "CloseCleared")
        lifecycle.dispatch("textDocument/didClose", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", sourceUri) })
        })
        assertMissingPlanFailure(assertFailsWith { lspApply(lifecycle, closeCleared) }, closeCleared)

        val noDiscard = assertFailsWith<JsonRpcException> {
            lspCommand(lifecycle, "refactorkit.discard", buildJsonObject { })
        }
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, noDiscard.code)
        assertEquals("Unknown command: refactorkit.discard", noDiscard.message)

        return listOf(true, true, true, true, true)
    }

    private fun characterizeMcpPolicy(): List<Boolean> {
        val admissionRoot = simpleJavaProject(withReference = true)
        McpSession().use { mcp ->
            mcpScan(mcp, admissionRoot)
            val refused = mcpCall(mcp, "preview_refactoring", buildJsonObject {
                put("operation", "safeDelete")
                put("symbol", "com.example.UserManager")
            })
            val refusedText = toolText(refused)
            assertTrue(refusedText.contains("Status   : REFUSED"), refusedText)
            val refusedId = firstValueAfter("Plan ID  :", refusedText)
            assertMissingPlanFailure(assertFailsWith { mcpApply(mcp, refusedId) }, refusedId)

            val importer = mcpCall(mcp, "import_external_java_class", buildJsonObject {
                put("code", "public class BlockedUnknown {}\n")
                put("targetPackage", "com.example")
                put("licensePolicy", "block-unknown")
            })
            val importerText = toolText(importer)
            assertTrue(importerText.contains("Status   : REFUSED"), importerText)
            val importerId = firstValueAfter("Plan ID  :", importerText)
            repeat(2) {
                val apply = mcpApply(mcp, importerId)
                assertTrue(toolText(apply).startsWith("Apply refused ["), toolText(apply))
            }
        }

        val applyRoot = simpleJavaProject()
        McpSession().use { mcp ->
            mcpScan(mcp, applyRoot)
            val firstId = mcpPreviewRename(mcp, "AccountManager")
            val secondId = mcpPreviewRename(mcp, "CustomerManager")
            val firstApply = mcpApply(mcp, firstId)
            val firstText = toolText(firstApply)
            assertTrue(firstText.startsWith("Applied successfully."), firstText)
            val firstTransaction = firstValueAfter("Transaction ID:", firstText)
            assertMissingPlanFailure(assertFailsWith { mcpApply(mcp, firstId) }, firstId)

            val rollback = mcpRollback(mcp, firstTransaction)
            assertTrue(toolText(rollback).startsWith("Rolled back transaction"), toolText(rollback))
            val secondApply = mcpApply(mcp, secondId)
            val secondText = toolText(secondApply)
            assertTrue(secondText.startsWith("Applied successfully."), secondText)
            mcpRollback(mcp, firstValueAfter("Transaction ID:", secondText))
        }

        val refusalRoot = simpleJavaProject()
        McpSession().use { mcp ->
            mcpScan(mcp, refusalRoot)
            val retainedId = mcpPreviewRename(mcp, "RefusedName")
            writeJava(refusalRoot, "Drift", "package com.example; public class Drift {}\n")
            repeat(2) {
                val refusal = mcpApply(mcp, retainedId)
                assertTrue(toolText(refusal).startsWith("Apply refused ["), toolText(refusal))
            }
        }

        val lifecycleRoot = simpleJavaProject()
        val lifecycle = McpSession()
        try {
            mcpScan(lifecycle, lifecycleRoot)
            val scanCleared = mcpPreviewRename(lifecycle, "ScanCleared")
            mcpScan(lifecycle, lifecycleRoot)
            assertMissingPlanFailure(assertFailsWith { mcpApply(lifecycle, scanCleared) }, scanCleared)

            val stalePlanId = insertSyntheticKotlinPendingPlan(lifecycle, lifecycleRoot)
            val stale = mcpApply(lifecycle, stalePlanId.value, semanticLease = "retained-lease")
            assertTrue(toolText(stale).contains("kotlin.renameAuthorityStale"), toolText(stale))
            assertMissingPlanFailure(assertFailsWith { mcpApply(lifecycle, stalePlanId.value) }, stalePlanId.value)

            val noDiscard = mcpCall(lifecycle, "discard_refactoring", buildJsonObject { })
            assertEquals("true", noDiscard.getValue("isError").jsonPrimitive.content)
            assertTrue(toolText(noDiscard).contains("Unknown tool: discard_refactoring"), toolText(noDiscard))

            val closeCleared = mcpPreviewRename(lifecycle, "CloseCleared")
            lifecycle.close()
            assertMissingPlanFailure(assertFailsWith { mcpApply(lifecycle, closeCleared) }, closeCleared)
        } finally {
            lifecycle.close()
        }

        return listOf(true, true, true, true, true, true, true)
    }

    private fun assertPolicyTable(table: DataTable) {
        val expected = mapOf(
            "daemon" to Pair(
                "non-refused managed previews; importer previews only when apply-eligible",
                "discard and refusal remove the selected ID; successful apply, successful rollback, project open, and close clear all IDs",
            ),
            "LSP" to Pair(
                "non-refused plans only after edit and document-version checks",
                "successful apply removes the selected ID; general refusal and successful rollback do not intrinsically clear; initialize and document open, change, save, or close clear all IDs; no discard surface",
            ),
            "MCP" to Pair(
                "refactoring results only when PREVIEW; importer results remain unconditionally registered",
                "successful apply and stale-Kotlin refusal remove the selected ID; general refusal and successful rollback otherwise retain IDs; project scan and close clear all IDs; no discard surface",
            ),
        )
        val actual = table.asMaps().associate { row ->
            row.getValue("surface") to Pair(
                row.getValue("admission preserved"),
                row.getValue("removal and clear behavior preserved"),
            )
        }
        assertEquals(expected, actual)
    }

    private fun daemonOpen(session: DaemonSession, root: Path): JsonObject =
        session.dispatch("project.open", strings("root" to root.toString())).jsonObject

    private fun daemonPreviewRename(session: DaemonSession, newName: String): JsonObject =
        session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "renameClass")
            put("symbol", "com.example.UserManager")
            put("arguments", buildJsonObject { put("newName", newName) })
        }).jsonObject

    private fun daemonImport(
        session: DaemonSession,
        className: String,
        licensePolicy: String = "allow",
        includeLicense: Boolean = true,
    ): JsonObject = session.dispatch("java.importExternalClass", buildJsonObject {
        val license = if (includeLicense) "// MIT License\n" else ""
        put("code", "${license}public class $className {}\n")
        put("targetDirectory", "src/main/java/com/example")
        put("licensePolicy", licensePolicy)
    }).jsonObject

    private fun daemonDiscard(session: DaemonSession, planId: String): JsonObject =
        session.dispatch("refactor.discard", strings("planId" to planId)).jsonObject

    private fun daemonApply(session: DaemonSession, planId: String): JsonObject =
        session.dispatch("refactor.apply", strings("planId" to planId)).jsonObject

    private fun initializeLsp(session: LspSession, root: Path, documentChanges: Boolean) {
        session.dispatch("initialize", buildJsonObject {
            put("rootUri", root.toUri().toString())
            put("capabilities", buildJsonObject {
                put("workspace", buildJsonObject {
                    put("workspaceEdit", buildJsonObject {
                        put("documentChanges", documentChanges)
                    })
                })
            })
        })
    }

    private fun lspPreviewRename(session: LspSession, newName: String): String =
        lspCommand(session, "refactorkit.renameClass", buildJsonObject {
            put("symbol", "com.example.UserManager")
            put("newName", newName)
        }).getValue("refactorkitPlanId").jsonPrimitive.content

    private fun lspApply(session: LspSession, planId: String): JsonObject =
        lspCommand(session, "refactorkit.applyPlan", strings("planId" to planId))

    private fun lspRollback(session: LspSession, transactionId: String): JsonObject =
        lspCommand(session, "refactorkit.rollback", strings("transactionId" to transactionId))

    private fun lspCommand(session: LspSession, command: String, arguments: JsonObject): JsonObject =
        session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", command)
            put("arguments", JsonArray(listOf(arguments)))
        }).jsonObject

    private fun lspDidChange(session: LspSession, uri: String, version: Int, text: String) {
        session.dispatch("textDocument/didChange", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", uri)
                put("version", version)
            })
            put("contentChanges", buildJsonArray {
                add(buildJsonObject { put("text", text) })
            })
        })
    }

    private fun mcpScan(session: McpSession, root: Path): JsonObject =
        mcpCall(session, "project_scan", strings("root" to root.toString()))

    private fun mcpPreviewRename(session: McpSession, newName: String): String {
        val result = mcpCall(session, "preview_refactoring", buildJsonObject {
            put("operation", "renameClass")
            put("symbol", "com.example.UserManager")
            put("arguments", buildJsonObject { put("newName", newName) })
        })
        return firstValueAfter("Plan ID  :", toolText(result))
    }

    private fun mcpApply(session: McpSession, planId: String, semanticLease: String? = null): JsonObject =
        mcpCall(session, "apply_refactoring", buildJsonObject {
            put("planId", planId)
            semanticLease?.let { put("semanticLease", it) }
        })

    private fun mcpRollback(session: McpSession, transactionId: String): JsonObject =
        mcpCall(session, "rollback_refactoring", strings("transactionId" to transactionId))

    private fun mcpCall(session: McpSession, name: String, arguments: JsonObject): JsonObject =
        session.dispatch("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }).jsonObject

    private fun toolText(result: JsonObject): String =
        result.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content

    private fun insertSyntheticKotlinPendingPlan(session: McpSession, root: Path): PlanId {
        val scan = mcpScan(session, root)
        val snapshotHash = firstValueAfter("Snapshot:", toolText(scan))
        val plan = PatchPlan(
            operation = "renameSymbol",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshotHash,
            confidence = 1.0,
            summary = "Synthetic pending plan used only to exercise stale Kotlin lifecycle policy",
            affectedFiles = emptySet(),
            workspaceEdit = WorkspaceEdit(),
        )
        val pendingType = McpSession::class.java.declaredClasses.single { it.simpleName == "PendingPlan" }
        var selectedConstructor: java.lang.reflect.Constructor<*>? = null
        pendingType.declaredConstructors.forEach { candidate ->
            if (candidate.parameterTypes.firstOrNull() == PatchPlan::class.java &&
                (selectedConstructor == null || candidate.parameterCount < requireNotNull(selectedConstructor).parameterCount)
            ) {
                selectedConstructor = candidate
            }
        }
        val constructor = requireNotNull(selectedConstructor)
        assertEquals(3, constructor.parameterCount)
        constructor.isAccessible = true
        val payload = constructor.newInstance(plan, "kotlin", "retained-lease")
        pendingStore(session).insert(plan.id, payload)
        return plan.id
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingStore(session: Any): PendingPlanStore<Any> {
        val field = session.javaClass.getDeclaredField("pendingPlans")
        field.isAccessible = true
        return field.get(session) as PendingPlanStore<Any>
    }

    private fun assertMissingPlanFailure(failure: JsonRpcException, planId: String) {
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, failure.code)
        assertEquals("Plan not found: $planId", failure.message)
    }

    private fun firstValueAfter(prefix: String, text: String): String =
        text.lineSequence().first { it.trimStart().startsWith(prefix) }
            .trimStart().removePrefix(prefix).trim()

    private fun simpleJavaProject(withReference: Boolean = false): Path {
        val root = temporaryDirectory("pending-plan-surface")
        writeJava(
            root,
            "UserManager",
            "package com.example;\npublic class UserManager {\n}\n",
        )
        if (withReference) {
            writeJava(
                root,
                "UserManagerClient",
                "package com.example;\npublic class UserManagerClient {\n    UserManager value;\n}\n",
            )
        }
        return root
    }

    private fun writeJava(root: Path, simpleName: String, content: String) {
        val path = root.resolve("src/main/java/com/example/$simpleName.java")
        val parent = requireNotNull(path.parent) { "Java fixture path must have a parent" }
        Files.createDirectories(parent)
        path.writeText(content)
    }

    private fun temporaryDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(temporaryRoots::add)

    private fun strings(vararg entries: Pair<String, String>): JsonObject = buildJsonObject {
        entries.forEach { (key, value) -> put(key, value) }
    }

    private fun planNumber(planId: String): Int = planId.removePrefix("plan-").toInt()

    private fun planName(number: Int): String = "plan-${number.toString().padStart(3, '0')}"

    private fun snapshotFiles(root: Path): Map<String, FileState> {
        val files = linkedMapOf<String, FileState>()
        Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).sorted().forEach { path ->
                val bytes = Files.readAllBytes(path)
                files[root.relativize(path).toString().replace('\\', '/')] = FileState(
                    size = bytes.size.toLong(),
                    sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) },
                )
            }
        }
        return files
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }
}
