package org.refactorkit.c

import kotlinx.serialization.json.*
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import java.nio.file.Path

/** JSON input projection only; discovery owns SDK scope, metadata and captured-input policy. */
object CReadOnlySdkRequestJson {
    fun decode(value: JsonElement?): List<CReadOnlySdkRequest> {
        if (value == null) return emptyList()
        val entries = value as? JsonArray ?: invalid("readOnlySdks")
        if (entries.size > CNativeSysrootPolicy.MAX_ROOTS) invalid("readOnlySdks")
        return entries.mapIndexed { index, raw ->
            val field = "readOnlySdks[$index]"
            val item = raw as? JsonObject ?: invalid(field)
            if (item.keys != setOf("root", "provenance", "license")) invalid(field)
            fun string(key: String): String = (item[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: invalid("$field.$key")
            val root = runCatching { Path.of(string("root")) }.getOrElse { invalid("$field.root") }
            CReadOnlySdkRequest(root, string("provenance"), string("license"))
        }
    }

    fun schema(): JsonObject = buildJsonObject {
        put("type", "array"); put("maxItems", CNativeSysrootPolicy.MAX_ROOTS)
        put("description", "Explicit readonly C SDK inputs; provenance and license are caller declarations. Omission preserves the empty SDK scope.")
        put("items", buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            put("required", buildJsonArray { listOf("root", "provenance", "license").forEach { add(JsonPrimitive(it)) } })
            put("properties", buildJsonObject {
                put("root", buildJsonObject { put("type", "string"); put("description", "Absolute normalized external SDK root") })
                put("provenance", buildJsonObject { put("type", "string"); put("description", "Caller-declared SDK provenance") })
                put("license", buildJsonObject { put("type", "string"); put("description", "Caller-declared SDK license") })
            })
        })
    }

    private fun invalid(field: String): Nothing = throw JsonRpcException(
        JsonRpcErrorCodes.INVALID_PARAMS,
        "c.sdkConfigurationInvalid: invalid $field in readonly SDK configuration",
        buildJsonObject { put("refusalCode", "c.sdkConfigurationInvalid"); put("field", field) },
    )
}
