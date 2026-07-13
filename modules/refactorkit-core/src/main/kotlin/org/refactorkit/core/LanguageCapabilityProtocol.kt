package org.refactorkit.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Versioned, deterministic, bounded capability schema shared by integration surfaces. */
object LanguageCapabilityProtocol {
    const val SCHEMA_VERSION = 1

    fun render(descriptors: Collection<LanguageAdapterDescriptor>): JsonObject {
        require(descriptors.size <= LanguageAdapterRegistry.MAX_ADAPTERS) { "language capability schema exceeds adapter limit" }
        require(descriptors.map(LanguageAdapterDescriptor::languageId).distinct().size == descriptors.size) {
            "language capability schema has duplicate languages"
        }
        return buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("adapters", buildJsonArray {
                descriptors.sortedBy(LanguageAdapterDescriptor::languageId).forEach { descriptor ->
                    add(renderDescriptor(descriptor))
                }
            })
            put("vocabulary", buildJsonObject {
                put("stability", values<CapabilityStability>())
                put("evidence", values<SemanticEvidenceKind>())
                put("mutationAuthority", values<MutationAuthority>())
                put("executionMode", values<AdapterExecutionMode>())
            })
        }
    }

    private fun renderDescriptor(descriptor: LanguageAdapterDescriptor) = buildJsonObject {
        put("languageId", descriptor.languageId)
        put("extensions", JsonArray(descriptor.extensions.sorted().map(::JsonPrimitive)))
        put("backend", descriptor.backend)
        put("runtime", buildJsonObject {
            put("executionMode", descriptor.runtime.executionMode.protocolName())
            put("supportsTimeout", descriptor.runtime.supportsTimeout)
            put("supportsCancellation", descriptor.runtime.supportsCancellation)
            put("usesWorkspaceOverlay", descriptor.runtime.usesWorkspaceOverlay)
            put("recordsProcessProvenance", descriptor.runtime.recordsProcessProvenance)
            put("limits", buildJsonObject {
                nullableLong("requestTimeoutMillis", descriptor.runtime.limits.requestTimeoutMillis)
                nullableLong("maxInputBytes", descriptor.runtime.limits.maxInputBytes)
                nullableLong("maxOutputBytes", descriptor.runtime.limits.maxOutputBytes)
                nullableLong("maxProcesses", descriptor.runtime.limits.maxProcesses?.toLong())
            })
        })
        put("capabilities", buildJsonArray {
            descriptor.capabilities.sortedBy(LanguageCapability::operation).forEach { capability ->
                add(buildJsonObject {
                    put("operation", capability.operation)
                    put("stability", capability.stability.protocolName())
                    put("evidence", capability.evidence.protocolName())
                    put("mutationAuthority", capability.mutationAuthority.protocolName())
                })
            }
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.nullableLong(name: String, value: Long?) {
        put(name, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private inline fun <reified T : Enum<T>> values() = JsonArray(enumValues<T>().map { JsonPrimitive(it.protocolName()) })
    private fun Enum<*>.protocolName(): String = name.lowercase().replace('_', '-')
}
