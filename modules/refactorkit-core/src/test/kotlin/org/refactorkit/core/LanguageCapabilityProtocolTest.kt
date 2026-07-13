package org.refactorkit.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LanguageCapabilityProtocolTest {
    @Test
    fun rendersVersionedDeterministicRuntimeAndCapabilitySchema() {
        val external = LanguageAdapterDescriptor(
            languageId = "typescript",
            extensions = setOf("tsx", "ts"),
            backend = "external-lsp",
            capabilities = listOf(
                LanguageCapability("references", CapabilityStability.EXPERIMENTAL, SemanticEvidenceKind.LANGUAGE_SERVER),
                LanguageCapability("definition", CapabilityStability.STABLE, SemanticEvidenceKind.LANGUAGE_SERVER),
            ),
            runtime = LanguageAdapterRuntime(
                executionMode = AdapterExecutionMode.EXTERNAL_PROCESS,
                supportsTimeout = true,
                supportsCancellation = true,
                usesWorkspaceOverlay = true,
                recordsProcessProvenance = true,
                limits = LanguageAdapterResourceLimits(10_000, 8_192, 65_536, 1),
            ),
        )
        val java = LanguageAdapterDescriptor(
            "java", setOf("java"), "jdt", listOf(
                LanguageCapability("renameClass", CapabilityStability.STABLE, SemanticEvidenceKind.COMPILER, MutationAuthority.MANAGED_STABLE),
            ),
        )

        val schema = LanguageCapabilityProtocol.render(listOf(external, java))
        assertEquals(1, schema["schemaVersion"]!!.jsonPrimitive.content.toInt())
        val adapters = schema["adapters"]!!.jsonArray
        assertEquals(listOf("java", "typescript"), adapters.map { it.jsonObject["languageId"]!!.jsonPrimitive.content })
        val typescript = adapters[1].jsonObject
        assertEquals(listOf("ts", "tsx"), typescript["extensions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("definition", "references"), typescript["capabilities"]!!.jsonArray.map {
            it.jsonObject["operation"]!!.jsonPrimitive.content
        })
        val runtime = typescript["runtime"]!!.jsonObject
        assertEquals("external-process", runtime["executionMode"]!!.jsonPrimitive.content)
        assertTrue(runtime["recordsProcessProvenance"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("10000", runtime["limits"]!!.jsonObject["requestTimeoutMillis"]!!.jsonPrimitive.content)
        assertTrue(adapters[0].jsonObject["runtime"]!!.jsonObject["limits"]!!.jsonObject["maxProcesses"] is JsonNull)
    }

    @Test
    fun runtimeClaimsAreInternallyConsistent() {
        assertFailsWith<IllegalArgumentException> {
            LanguageAdapterRuntime(executionMode = AdapterExecutionMode.IN_PROCESS, recordsProcessProvenance = true)
        }
        assertFailsWith<IllegalArgumentException> {
            LanguageAdapterRuntime(supportsTimeout = true)
        }
        assertFailsWith<IllegalArgumentException> {
            LanguageAdapterRuntime(usesWorkspaceOverlay = true)
        }
    }
}
