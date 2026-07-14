package org.refactorkit.core

import java.nio.file.Path

/** Provider-neutral operation discriminator; response variants remain operation-specific. */
enum class SemanticQueryKind {
    WORKSPACE_SYMBOLS,
    DOCUMENT_SYMBOLS,
    COMPLETION,
    HOVER,
    SIGNATURE_HELP,
    DEFINITION,
    REFERENCES,
}

enum class SemanticDocumentMode {
    SAVED_SNAPSHOT,
    IMMUTABLE_EDITOR_OVERLAY,
}

/** Exact document authority used by an interactive semantic read. */
data class SemanticDocumentAuthority(
    val path: Path,
    val mode: SemanticDocumentMode,
    val contentSha256: String,
    val version: Long? = null,
) {
    init {
        require(!path.isAbsolute && path.normalize().toString().isNotBlank() && !path.normalize().startsWith("..")) {
            "semantic document path must be workspace-relative"
        }
        require(Regex("[0-9a-f]{64}").matches(contentSha256)) { "semantic document hash is invalid" }
        require(version == null || version >= 0) { "semantic document version must be non-negative" }
        require(mode != SemanticDocumentMode.IMMUTABLE_EDITOR_OVERLAY || version != null) {
            "editor overlay authority requires a document version"
        }
    }
}

data class SemanticQueryEnvelope(
    val requestId: String,
    val expectedSnapshotHash: String,
    val expectedIndexGeneration: Long? = null,
    val document: SemanticDocumentAuthority? = null,
    val overlay: ImmutableEditorOverlay? = null,
    val query: SemanticQueryRequest,
) {
    init {
        require(requestId.length in 1..ProtocolLimits.MAX_DIAGNOSTICS_REQUEST_ID_CHARS) {
            "semantic query request ID is invalid"
        }
        require(Regex("[0-9a-f]{64}").matches(expectedSnapshotHash)) {
            "semantic query snapshot hash is invalid"
        }
        require(expectedIndexGeneration == null || expectedIndexGeneration >= 1) {
            "semantic query index generation must be positive"
        }
        if (query.requiresDocument) require(document != null) {
            "${query.kind} requires document authority"
        }
        overlay?.let { value ->
            require(value.baseSnapshotHash == expectedSnapshotHash) {
                "semantic query overlay belongs to another saved snapshot"
            }
            require(document?.mode == SemanticDocumentMode.IMMUTABLE_EDITOR_OVERLAY) {
                "semantic query overlay requires immutable editor document authority"
            }
            val overlayAuthority = document?.let { value.authority(it.path) }
            require(overlayAuthority == document) {
                "semantic query document authority does not match overlay content/version"
            }
        }
    }
}

sealed interface SemanticQueryRequest {
    val kind: SemanticQueryKind
    val requiresDocument: Boolean

    data class WorkspaceSymbols(
        val text: String = "",
        val languageId: String? = null,
        val documentPath: Path? = null,
        val limit: Int = ProtocolLimits.MAX_SYMBOL_RESULTS,
    ) : SemanticQueryRequest {
        override val kind = if (documentPath == null) SemanticQueryKind.WORKSPACE_SYMBOLS else SemanticQueryKind.DOCUMENT_SYMBOLS
        override val requiresDocument = false

        init {
            require(text.length <= ProtocolLimits.MAX_INTELLIGENCE_QUERY_CHARS) { "semantic symbol query is too long" }
            require(limit in 1..ProtocolLimits.MAX_SYMBOL_RESULTS) { "semantic symbol query limit is invalid" }
            documentPath?.let { path ->
                require(!path.isAbsolute && path.normalize().toString().isNotBlank() && !path.normalize().startsWith("..")) {
                    "semantic symbol document path must be workspace-relative"
                }
            }
        }
    }

    data class Completion(
        val position: SourcePosition,
        val trigger: CompletionTrigger = CompletionTrigger.INVOKED,
        val triggerCharacter: String? = null,
        val limit: Int = ProtocolLimits.MAX_SYMBOL_RESULTS,
    ) : SemanticQueryRequest {
        override val kind = SemanticQueryKind.COMPLETION
        override val requiresDocument = true
        init {
            require(triggerCharacter == null || triggerCharacter.length <= 8) { "completion trigger is invalid" }
            require(limit in 1..ProtocolLimits.MAX_SYMBOL_RESULTS) { "completion limit is invalid" }
        }
    }

    data class Hover(val position: SourcePosition) : SemanticQueryRequest {
        override val kind = SemanticQueryKind.HOVER
        override val requiresDocument = true
    }

    data class SignatureHelp(
        val position: SourcePosition,
        val triggerCharacter: String? = null,
        val retrigger: Boolean = false,
    ) : SemanticQueryRequest {
        override val kind = SemanticQueryKind.SIGNATURE_HELP
        override val requiresDocument = true
        init { require(triggerCharacter == null || triggerCharacter.length <= 8) { "signature trigger is invalid" } }
    }

    data class Definition(val position: SourcePosition) : SemanticQueryRequest {
        override val kind = SemanticQueryKind.DEFINITION
        override val requiresDocument = true
    }

    data class References(
        val position: SourcePosition,
        val includeDeclaration: Boolean = true,
        val limit: Int = ProtocolLimits.MAX_REFERENCE_RESULTS,
    ) : SemanticQueryRequest {
        override val kind = SemanticQueryKind.REFERENCES
        override val requiresDocument = true
        init { require(limit in 1..ProtocolLimits.MAX_REFERENCE_RESULTS) { "reference limit is invalid" } }
    }
}

enum class CompletionTrigger { INVOKED, TRIGGER_CHARACTER, INCOMPLETE_RETRIGGER }

data class SemanticProviderCapability(
    val kind: SemanticQueryKind,
    val evidence: SemanticEvidenceKind,
    val completeness: WorkspaceIndexCompleteness,
) {
    init { require(evidence != SemanticEvidenceKind.NONE) { "semantic provider capability requires evidence" } }
}

data class SemanticQueryAttestation(
    val providerId: String,
    val backend: String,
    val evidence: SemanticEvidenceKind,
    val completeness: WorkspaceIndexCompleteness,
    val snapshotHash: String,
    val indexGeneration: Long,
    val document: SemanticDocumentAuthority? = null,
) {
    init {
        require(providerId.isNotBlank() && backend.isNotBlank()) { "semantic query provider attestation is incomplete" }
        require(evidence != SemanticEvidenceKind.NONE) { "semantic query attestation requires evidence" }
        require(indexGeneration >= 1) { "semantic query attestation generation is invalid" }
    }
}

sealed interface SemanticQueryResponse {
    val requestId: String

    data class Symbols(
        override val requestId: String,
        val kind: SemanticQueryKind,
        val items: List<WorkspaceIndexedSymbol>,
        val total: Int,
        val truncated: Boolean,
        val attestation: SemanticQueryAttestation,
    ) : SemanticQueryResponse {
        init {
            require(kind in setOf(SemanticQueryKind.WORKSPACE_SYMBOLS, SemanticQueryKind.DOCUMENT_SYMBOLS))
            require(items.size <= ProtocolLimits.MAX_SYMBOL_RESULTS && total >= items.size)
        }
    }

    data class Completion(
        override val requestId: String,
        val items: List<SemanticCompletionItem>,
        val incomplete: Boolean,
        val attestation: SemanticQueryAttestation,
    ) : SemanticQueryResponse {
        init { require(items.size <= ProtocolLimits.MAX_SYMBOL_RESULTS) }
    }

    data class Hover(
        override val requestId: String,
        val range: SourceRange?,
        val sections: List<SemanticHoverSection>,
        val attestation: SemanticQueryAttestation,
    ) : SemanticQueryResponse

    data class SignatureHelp(
        override val requestId: String,
        val signatures: List<SemanticSignature>,
        val activeSignature: Int?,
        val activeParameter: Int?,
        val attestation: SemanticQueryAttestation,
    ) : SemanticQueryResponse

    data class Definition(
        override val requestId: String,
        val locations: List<SourceLocation>,
        val attestation: SemanticQueryAttestation,
    ) : SemanticQueryResponse

    data class References(
        override val requestId: String,
        val references: List<Reference>,
        val total: Int,
        val truncated: Boolean,
        val attestation: SemanticQueryAttestation,
    ) : SemanticQueryResponse {
        init { require(references.size <= ProtocolLimits.MAX_REFERENCE_RESULTS && total >= references.size) }
    }

    data class Refused(
        override val requestId: String,
        val kind: SemanticQueryKind,
        val code: String,
        val message: String,
        val currentSnapshotHash: String,
        val currentIndexGeneration: Long,
    ) : SemanticQueryResponse
}

data class SemanticCompletionItem(
    val label: String,
    val kind: Symbol.Kind,
    val detail: String? = null,
    val documentation: String? = null,
    val sortText: String? = null,
    val filterText: String? = null,
    val insertText: String? = null,
    val replacementRange: SourceRange? = null,
    val additionalTextEdits: List<TextEdit> = emptyList(),
)

data class SemanticHoverSection(val format: Format, val value: String) {
    enum class Format { PLAIN_TEXT, MARKDOWN }
}

data class SemanticSignature(
    val label: String,
    val documentation: String? = null,
    val parameters: List<SemanticSignatureParameter> = emptyList(),
)

data class SemanticSignatureParameter(
    val labelStart: Int,
    val labelEnd: Int,
    val documentation: String? = null,
) {
    init { require(labelStart >= 0 && labelEnd >= labelStart) { "signature parameter span is invalid" } }
}

fun interface SemanticCancellationToken {
    fun isCancellationRequested(): Boolean

    companion object {
        val NONE = SemanticCancellationToken { false }
    }
}

/**
 * Language/provider SPI for typed semantic reads. Implementations own compiler
 * objects and caches; only normalized responses cross into core.
 */
interface SemanticQueryProvider : AutoCloseable {
    val languageId: String
    val providerId: String
    fun capabilities(): Set<SemanticProviderCapability>
    fun query(
        snapshot: ProjectSnapshot,
        index: WorkspaceIndex,
        envelope: SemanticQueryEnvelope,
        cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE,
    ): SemanticQueryResponse
    override fun close() = Unit
}
