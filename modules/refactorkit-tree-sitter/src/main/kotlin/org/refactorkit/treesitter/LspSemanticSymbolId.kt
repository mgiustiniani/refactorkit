package org.refactorkit.treesitter

import org.refactorkit.core.SymbolId
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Location-independent identity for symbols reported by a semantic LSP provider.
 *
 * LSP does not standardize compiler symbol handles, so the durable key is derived
 * from the workspace-relative declaration path and the semantic hierarchy emitted
 * by DocumentSymbol/SymbolInformation. Source ranges are deliberately excluded.
 */
internal object LspSemanticSymbolId {
    private const val SCHEMA = "lsp-symbol-v1"

    fun create(
        languageId: String,
        path: Path,
        hierarchy: List<Component>,
    ): SymbolId {
        val canonicalPath = path.normalize().toString().replace('\\', '/')
        val input = buildString {
            field(SCHEMA)
            field(languageId)
            field(canonicalPath)
            hierarchy.forEach { component ->
                field(component.kind.toString())
                field(component.name)
                field(component.detail.orEmpty())
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return SymbolId("$SCHEMA:$digest")
    }

    data class Component(
        val kind: Int,
        val name: String,
        val detail: String? = null,
    )

    private fun StringBuilder.field(value: String) {
        append(value.length).append(':').append(value).append('|')
    }
}
