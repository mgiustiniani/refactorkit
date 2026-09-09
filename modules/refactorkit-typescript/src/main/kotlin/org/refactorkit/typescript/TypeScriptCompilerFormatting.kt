package org.refactorkit.typescript

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The supported, explicit subset of tsserver ConfigureRequestArguments. */
data class TypeScriptCompilerFormatting(
    val indentSize: Int = 4,
    val tabSize: Int = 4,
    val newLine: String = "lf",
    val convertTabsToSpaces: Boolean = true,
    val quotePreference: String = "single",
) {
    init {
        require(indentSize in 1..8 && tabSize in 1..8) { "Indent and tab sizes must be between 1 and 8" }
        require(newLine in setOf("lf", "crlf")) { "Newline must be lf or crlf" }
        require(quotePreference in setOf("single", "double", "auto")) { "Unsupported quote preference" }
    }
    val formatOptions: String get() = buildJsonObject {
        put("indentSize", indentSize); put("tabSize", tabSize)
        put("newLineCharacter", if (newLine == "lf") "\n" else "\r\n")
        put("convertTabsToSpaces", convertTabsToSpaces)
    }.toString()
    val preferences: String get() = buildJsonObject {
        put("quotePreference", quotePreference)
        put("provideRefactorNotApplicableReason", true)
    }.toString()
    fun evidence(): Map<String, String> = mapOf("formatOptions" to formatOptions, "preferences" to preferences)

    companion object {
        fun from(arguments: Map<String, String>): TypeScriptCompilerFormatting = TypeScriptCompilerFormatting(
            arguments["indentSize"]?.toInt() ?: 4, arguments["tabSize"]?.toInt() ?: 4,
            arguments["newLine"] ?: "lf", arguments["convertTabsToSpaces"]?.toBooleanStrict() ?: true,
            arguments["quotePreference"] ?: "single",
        )
    }
}

enum class TypeScriptOrganizeImportsMode(val protocolName: String) {
    ALL("All"), SORT_AND_COMBINE("SortAndCombine"), REMOVE_UNUSED("RemoveUnused"),
}
