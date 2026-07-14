package org.refactorkit.typescript

import org.refactorkit.core.LanguageAdapterDescriptor
import org.refactorkit.treesitter.TreeSitterAdapterDescriptors

/** Capability-layer composition for integration surfaces without starting a toolchain. */
object TypeScriptAdapterDescriptors {
    fun descriptors(): List<LanguageAdapterDescriptor> = listOf("typescript", "javascript").map(::descriptor)

    fun descriptor(languageId: String): LanguageAdapterDescriptor {
        val structural = TreeSitterAdapterDescriptors.descriptors().single { it.languageId == languageId }
        val semantic = TypeScriptSemanticAdapter.descriptor(languageId)
        val capabilities = structural.capabilities.map { capability ->
            capability.copy(
                backend = structural.backend, runtime = structural.runtime, extensions = structural.extensions,
            )
        } + semantic.capabilities.map { capability ->
            capability.copy(
                backend = capability.backend ?: semantic.backend,
                runtime = capability.runtime ?: semantic.runtime,
                extensions = capability.extensions ?: semantic.extensions,
            )
        }
        return LanguageAdapterDescriptor(
            languageId = languageId,
            extensions = structural.extensions + semantic.extensions,
            backend = "${structural.backend}+${semantic.backend}",
            capabilities = capabilities.sortedBy { it.operation },
            runtime = semantic.runtime,
        )
    }
}
