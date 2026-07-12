package org.refactorkit.java

import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.RefactoringEvidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaDiagnosticsAcceptanceTest {
    @Test
    fun representativeSamplesExposeDeterministicCompilerDiagnosticsWithoutBuildExecution() {
        val expectedClean = setOf("java-maven-simple", "java-gradle-simple", "java-multimodule", "java-maven-reactor-21")
        val frameworkSamples = setOf("java-spring-simple", "java-jpa-simple")
        (expectedClean + frameworkSamples).forEach { sampleName ->
            val snapshot = JavaProjectScanner().scan(repoRoot().resolve("samples/$sampleName"))
            val diagnostics = JavaLanguageAdapter().diagnostics(snapshot)
            val errors = diagnostics.filter { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR }
            if (sampleName in expectedClean) {
                assertTrue(errors.isEmpty(), "$sampleName diagnostics: $errors")
            } else {
                assertTrue(errors.isNotEmpty(), "$sampleName should expose unresolved external framework types")
                assertTrue(errors.all {
                    it.evidence == DiagnosticEvidence.COMPILER &&
                        it.category == DiagnosticCategory.TYPE_RESOLUTION &&
                        it.code == "java.jdt.typeResolution" &&
                        it.location != null
                }, "$sampleName diagnostics: $errors")
            }
        }
    }

    @Test
    fun frameworkSamplePreviewIsExplicitReviewOnlyFallback() {
        val root = repoRoot().resolve("samples/java-spring-simple")
        val snapshot = JavaProjectScanner().scan(root)

        val plan = JavaRenameClassPlanner(JavaLanguageAdapter())
            .preview(snapshot, "com.example.UserService", "AccountService")

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertEquals(RefactoringEvidence.LEXICAL_FALLBACK, plan.evidence)
        assertTrue(plan.warnings.any { it.contains("Spring", ignoreCase = true) })
    }

    @Test
    fun diagnosticsRemainCleanAfterManagedApplyAndRollback() {
        val root = Files.createTempDirectory("refactorkit-diagnostics-lifecycle")
        val manager = root.resolve("src/main/java/example/UserManager.java")
        val client = root.resolve("src/main/java/example/Client.java")
        Files.createDirectories(manager.parent)
        Files.writeString(manager, "package example;\npublic class UserManager {}\n")
        Files.writeString(client, "package example;\nclass Client { UserManager value = new UserManager(); }\n")
        val scanner = JavaProjectScanner()
        val adapter = JavaLanguageAdapter()
        val before = scanner.scan(root)
        assertTrue(adapter.diagnostics(before).none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
        val plan = JavaRenameClassPlanner(adapter).preview(before, "example.UserManager", "AccountManager")
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertEquals(RefactoringEvidence.JDT_BINDING, plan.evidence, "${plan.summary}; ${plan.warnings}")

        val applied = assertIs<ApplyResult.Applied>(PatchEngine(root).apply(plan, before))
        val after = scanner.scan(root)
        assertTrue(adapter.diagnostics(after).none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })

        assertIs<ApplyResult.Applied>(PatchEngine(root).rollback(applied.transaction))
        val rolledBack = scanner.scan(root)
        assertTrue(adapter.diagnostics(rolledBack).none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
        assertTrue(root.resolve("src/main/java/example/UserManager.java").exists())
    }

    private fun repoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (current.resolve("settings.gradle.kts").exists() && current.resolve("samples").exists()) return current
            current = current.parent ?: return@repeat
        }
        error("Repository root not found")
    }
}
