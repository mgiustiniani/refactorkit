package org.refactorkit.java

import org.refactorkit.core.CapabilityStability
import org.refactorkit.core.LanguageAdapterDescriptor
import org.refactorkit.core.LanguageCapability
import org.refactorkit.core.MutationAuthority
import org.refactorkit.core.RegisteredLanguageAdapter
import org.refactorkit.core.SemanticEvidenceKind

/** Reference registration consumed by the multi-language kernel. */
object JavaAdapterRegistration {
    fun create(adapter: JavaLanguageAdapter = JavaLanguageAdapter()) = RegisteredLanguageAdapter(
        descriptor = LanguageAdapterDescriptor(
            languageId = "java",
            extensions = setOf("java"),
            backend = "eclipse-jdt-3.44",
            capabilities = listOf(
                stable("renameClass", SemanticEvidenceKind.COMPILER),
                stable("renameMember", SemanticEvidenceKind.COMPILER),
                stable("moveClass", SemanticEvidenceKind.COMPILER),
                stable("moveSourceRoot", SemanticEvidenceKind.STRUCTURAL_PARSE),
                stable("organizeImports", SemanticEvidenceKind.STRUCTURAL_PARSE),
                stable("formatFile", SemanticEvidenceKind.STRUCTURAL_PARSE),
                stable("safeDelete", SemanticEvidenceKind.COMPILER),
                experimental("extractMethod"),
                experimental("changeSignature.renameParameter"),
                experimental("changeSignature.addParameter"),
                experimental("changeSignature.reorderParameters"),
                experimental("changeSignature.removeParameter"),
            ),
        ),
        adapter = adapter,
    )

    private fun stable(operation: String, evidence: SemanticEvidenceKind) = LanguageCapability(
        operation,
        CapabilityStability.STABLE,
        evidence,
        MutationAuthority.MANAGED_STABLE,
    )

    private fun experimental(operation: String) = LanguageCapability(
        operation,
        CapabilityStability.EXPERIMENTAL,
        SemanticEvidenceKind.COMPILER,
        MutationAuthority.PROPOSAL_ONLY,
    )
}
