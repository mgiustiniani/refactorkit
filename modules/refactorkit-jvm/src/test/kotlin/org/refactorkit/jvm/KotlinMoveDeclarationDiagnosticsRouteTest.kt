package org.refactorkit.jvm

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.kotlin.KotlinLanguageAdapter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinMoveDeclarationDiagnosticsRouteTest {
    @Test
    fun kotlinOnlyMoveUsesLazyOperationDiagnosticsRatherThanGenericK2() {
        val calls = linkedMapOf<String, Int>()
        val gate = ManagedApplyDiagnosticsGateSelector.select(
            plan = plan("moveDeclaration"),
            languageId = "kotlin",
            javaAdapter = JavaLanguageAdapter(),
            kotlinAdapter = KotlinLanguageAdapter(),
            externalGateResolver = { error("external resolver must remain unused") },
            providerFunctions = providers(calls),
        )

        assertEquals("kotlin-k2-java-jdt", gate.id)
        assertEquals(emptyMap(), calls)
        assertEquals(emptyList(), requireNotNull(gate.provider).invoke(snapshot()))
        assertEquals(mapOf("move" to 1), calls)
    }

    @Test
    fun kotlinChangeSignatureUsesLazyMixedOperationDiagnostics() {
        val calls = linkedMapOf<String, Int>()
        val gate = ManagedApplyDiagnosticsGateSelector.select(
            plan = plan(org.refactorkit.kotlin.KotlinChangeSignaturePlanner.OPERATION),
            languageId = "kotlin",
            javaAdapter = JavaLanguageAdapter(),
            kotlinAdapter = KotlinLanguageAdapter(),
            externalGateResolver = { error("external resolver must remain unused") },
            providerFunctions = providers(calls),
        )

        assertEquals("kotlin-k2-java-jdt-change-signature", gate.id)
        assertEquals(emptyMap(), calls)
        assertEquals(emptyList(), requireNotNull(gate.provider).invoke(snapshot()))
        assertEquals(mapOf("change-signature" to 1), calls)
    }

    @Test
    fun kotlinOnlyNonMoveRetainsLazyGenericK2Fallback() {
        val calls = linkedMapOf<String, Int>()
        val gate = ManagedApplyDiagnosticsGateSelector.select(
            plan = plan("renameSymbol"),
            languageId = "kotlin",
            javaAdapter = JavaLanguageAdapter(),
            kotlinAdapter = KotlinLanguageAdapter(),
            externalGateResolver = { error("external resolver must remain unused") },
            providerFunctions = providers(calls),
        )

        assertEquals("kotlin-k2", gate.id)
        assertEquals(emptyMap(), calls)
        assertEquals(emptyList(), requireNotNull(gate.provider).invoke(snapshot()))
        assertEquals(mapOf("k2" to 1), calls)
    }

    private fun providers(calls: MutableMap<String, Int>) = ManagedApplyDiagnosticsProviderFunctions(
        javaMavenOwnership = { _, _ -> record(calls, "java-maven") },
        javaJdt = { _, _ -> record(calls, "java-jdt") },
        kotlinJvmMoveDeclaration = { _, _ -> record(calls, "move") },
        kotlinJvmChangeSignature = { _, _ -> record(calls, "change-signature") },
        javaKotlinPublicTypeRename = { _, _ -> record(calls, "java-kotlin") },
        kotlinJavaPublicTypeRename = { _, _ -> record(calls, "kotlin-java") },
        kotlinK2 = { _, _ -> record(calls, "k2") },
    )

    private fun record(calls: MutableMap<String, Int>, route: String): List<Diagnostic> {
        calls[route] = (calls[route] ?: 0) + 1
        return emptyList()
    }

    private fun plan(operation: String) = PatchPlan(
        operation = operation,
        snapshotHash = "snapshot",
        confidence = 1.0,
        summary = operation,
        affectedFiles = setOf(Path.of("src/main/kotlin/example/Move.kt")),
        workspaceEdit = WorkspaceEdit(),
        evidence = RefactoringEvidence.NATIVE_AST,
    )

    private fun snapshot() = ProjectSnapshot(
        workspace = Workspace(Path.of(".")),
        modules = emptyList(),
        files = emptyList(),
    )
}
