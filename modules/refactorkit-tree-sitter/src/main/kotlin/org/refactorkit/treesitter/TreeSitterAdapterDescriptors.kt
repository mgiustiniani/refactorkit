package org.refactorkit.treesitter

import org.refactorkit.core.AdapterExecutionMode
import org.refactorkit.core.CapabilityStability
import org.refactorkit.core.LanguageAdapterDescriptor
import org.refactorkit.core.LanguageAdapterResourceLimits
import org.refactorkit.core.LanguageAdapterRuntime
import org.refactorkit.core.LanguageCapability
import org.refactorkit.core.MutationAuthority
import org.refactorkit.core.SemanticEvidenceKind

object TreeSitterAdapterDescriptors {
    fun descriptors(): List<LanguageAdapterDescriptor> = listOf(
        descriptor("typescript", setOf("ts")),
        descriptor("javascript", setOf("js")),
    )

    private fun descriptor(languageId: String, extensions: Set<String>) = LanguageAdapterDescriptor(
        languageId = languageId,
        extensions = extensions,
        backend = BonedeTreeSitterBinding.BACKEND,
        capabilities = listOf(
            LanguageCapability("outline", CapabilityStability.STABLE, SemanticEvidenceKind.NATIVE_AST),
            LanguageCapability("identifierSearch", CapabilityStability.STABLE, SemanticEvidenceKind.NATIVE_AST),
            LanguageCapability(
                "localRename",
                CapabilityStability.STRUCTURAL,
                SemanticEvidenceKind.LEXICAL,
                MutationAuthority.PROPOSAL_ONLY,
            ),
        ),
        runtime = LanguageAdapterRuntime(
            executionMode = AdapterExecutionMode.IN_PROCESS,
            supportsTimeout = true,
            supportsCancellation = false,
            limits = LanguageAdapterResourceLimits(
                requestTimeoutMillis = BonedeTreeSitterBinding.PARSE_TIMEOUT_MICROS / 1_000,
                maxInputBytes = BonedeTreeSitterBinding.MAX_SOURCE_BYTES.toLong(),
            ),
        ),
    )
}
