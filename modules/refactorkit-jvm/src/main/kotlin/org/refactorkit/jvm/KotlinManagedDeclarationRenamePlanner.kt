package org.refactorkit.jvm

import org.refactorkit.core.PatchPlan
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SymbolId
import org.refactorkit.kotlin.KotlinLanguageAdapter
import org.refactorkit.kotlin.KotlinPrivateDeclarationRenamePlanner

/** Routes Kotlin-only K4 and mixed-JVM rename without granting lexical fallback. */
class KotlinManagedDeclarationRenamePlanner(
    private val kotlin: KotlinLanguageAdapter,
) {
    fun preview(
        snapshot: ProjectSnapshot,
        symbolId: SymbolId,
        newName: String,
        acceptExternalConsumerRisk: Boolean = false,
    ): PatchPlan = if (snapshot.files.any { it.languageId == "java" }) {
        if (symbolId.value.startsWith("kotlin-jvm-")) {
            KotlinJavaPublicTypeRenamePlanner(kotlin).preview(
                snapshot, symbolId, newName, acceptExternalConsumerRisk,
            )
        } else {
            JavaKotlinPublicTypeRenamePlanner(kotlin).preview(
                snapshot, symbolId, newName, acceptExternalConsumerRisk,
            )
        }
    } else {
        KotlinPrivateDeclarationRenamePlanner(kotlin).preview(snapshot, symbolId, newName)
    }
}
