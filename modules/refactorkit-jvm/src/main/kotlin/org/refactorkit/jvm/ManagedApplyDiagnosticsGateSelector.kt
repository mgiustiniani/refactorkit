package org.refactorkit.jvm

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaMoveAcrossMavenModulesPlanner
import org.refactorkit.kotlin.KotlinLanguageAdapter
import java.util.Objects

internal data class ManagedApplyDiagnosticsProviderFunctions(
    val javaMavenOwnership: (JavaLanguageAdapter, ProjectSnapshot) -> List<Diagnostic>,
    val javaJdt: (JavaLanguageAdapter, ProjectSnapshot) -> List<Diagnostic>,
    val kotlinJvmMoveDeclaration: (KotlinLanguageAdapter, ProjectSnapshot) -> List<Diagnostic>,
    val javaKotlinPublicTypeRename: (KotlinLanguageAdapter, ProjectSnapshot) -> List<Diagnostic>,
    val kotlinJavaPublicTypeRename: (KotlinLanguageAdapter, ProjectSnapshot) -> List<Diagnostic>,
    val kotlinK2: (KotlinLanguageAdapter, ProjectSnapshot) -> List<Diagnostic>,
)

/**
 * Selects the lazy diagnostics gate for managed daemon and MCP apply operations.
 *
 * Implements REQ-MANAGED-APPLY-DIAGNOSTICS-SELECTOR-001 and
 * REQ-MANAGED-APPLY-DIAGNOSTICS-SELECTOR-002 without invoking a diagnostics provider.
 */
object ManagedApplyDiagnosticsGateSelector {
    fun select(
        plan: PatchPlan,
        languageId: String,
        javaAdapter: JavaLanguageAdapter,
        kotlinAdapter: KotlinLanguageAdapter,
        externalGateResolver: (String) -> DiagnosticsGate,
    ): DiagnosticsGate = select(
        plan = plan,
        languageId = languageId,
        javaAdapter = javaAdapter,
        kotlinAdapter = kotlinAdapter,
        externalGateResolver = externalGateResolver,
        providerFunctions = ManagedApplyDiagnosticsProviderFunctions(
            javaMavenOwnership = { currentAdapter, candidate ->
                JavaMoveAcrossMavenModulesPlanner(currentAdapter).diagnostics(candidate)
            },
            javaJdt = { currentAdapter, candidate -> currentAdapter.diagnostics(candidate) },
            kotlinJvmMoveDeclaration = { currentAdapter, candidate ->
                KotlinJvmMoveDeclarationPlanner(currentAdapter).diagnostics(candidate)
            },
            javaKotlinPublicTypeRename = { currentAdapter, candidate ->
                JavaKotlinPublicTypeRenamePlanner(currentAdapter).diagnostics(candidate)
            },
            kotlinJavaPublicTypeRename = { currentAdapter, candidate ->
                KotlinJavaPublicTypeRenamePlanner(currentAdapter).diagnostics(candidate)
            },
            kotlinK2 = { currentAdapter, candidate ->
                currentAdapter.compilerDiagnostics(candidate).diagnostics
            },
        ),
    )

    internal fun select(
        plan: PatchPlan,
        languageId: String,
        javaAdapter: JavaLanguageAdapter,
        kotlinAdapter: KotlinLanguageAdapter,
        externalGateResolver: (String) -> DiagnosticsGate,
        providerFunctions: ManagedApplyDiagnosticsProviderFunctions,
    ): DiagnosticsGate = when (languageId) {
        "java" -> if (plan.operation == JavaMoveAcrossMavenModulesPlanner.OPERATION) {
            DiagnosticsGate.enabled("java-maven-ownership") { candidate ->
                providerFunctions.javaMavenOwnership(javaAdapter, candidate)
            }
        } else {
            DiagnosticsGate.enabled("java-jdt") { candidate ->
                providerFunctions.javaJdt(javaAdapter, candidate)
            }
        }
        "kotlin" -> {
            val javaAffected = plan.affectedFiles.any {
                Objects.requireNonNull(it.fileName).toString().endsWith(".java")
            }
            when {
                javaAffected && plan.operation == "moveDeclaration" ->
                    DiagnosticsGate.enabled("kotlin-k2-java-jdt") { candidate ->
                        providerFunctions.kotlinJvmMoveDeclaration(kotlinAdapter, candidate)
                    }
                javaAffected && plan.evidence == RefactoringEvidence.JDT_BINDING ->
                    DiagnosticsGate.enabled("kotlin-k2-java-jdt") { candidate ->
                        providerFunctions.javaKotlinPublicTypeRename(kotlinAdapter, candidate)
                    }
                javaAffected -> DiagnosticsGate.enabled("kotlin-k2-java-jdt") { candidate ->
                    providerFunctions.kotlinJavaPublicTypeRename(kotlinAdapter, candidate)
                }
                else -> DiagnosticsGate.enabled("kotlin-k2") { candidate ->
                    providerFunctions.kotlinK2(kotlinAdapter, candidate)
                }
            }
        }
        else -> externalGateResolver(languageId)
    }
}
