package org.refactorkit.java

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.AuthoritativeDiagnosticsEvaluation
import org.refactorkit.core.AuthoritativeDiagnosticsProvider
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class JavaGradleModuleStagedSnapshotSpec : StringSpec({
    "Gradle module apply journals the same stale-model hash returned by a live scan and rolls back exactly" {
        val root = Files.createTempDirectory("refactorkit-gradle-module-staged-")
        try {
            val settingsPath = Path.of("settings.gradle.kts")
            val rootBuildPath = Path.of("build.gradle.kts")
            val sourcePath = Path.of("src/main/java/example/App.java")
            val settings = "rootProject.name = \"catalog\"\n"
            Files.createDirectories(root.resolve(sourcePath).parent)
            Files.writeString(root.resolve(settingsPath), settings)
            Files.writeString(root.resolve(rootBuildPath), "plugins { java }\n")
            Files.writeString(root.resolve(sourcePath), "package example; public class App {}\n")

            val model = effectiveModel(root)
            var changedInputs = false
            val scanner = JavaProjectScanner(
                effectiveGradleModelProvider = {
                    if (changedInputs) JavaGradleBuildInputInvalidation.invalidate(model) else model
                },
                effectiveGradleEvidenceProvider = { emptyList() },
            )
            val before = scanner.scan(root)
            before.buildModels.single().status shouldBe BuildModelStatus.AVAILABLE

            val projectPath = ":services:catalog"
            val settingsProposal = GradleSettingsModuleEdit.propose(
                settingsPath.fileName.toString(), settings, projectPath,
            ) as GradleSettingsModuleEditResult.Supported
            val buildScriptPath = Path.of("services/catalog/build.gradle.kts")
            val buildScript = "plugins {\n    `java-library`\n}\n"
            val end = SourcePosition(1, 0)
            val edit = WorkspaceEdit(listOf(
                FileEdit.Modify(settingsPath, listOf(TextEdit(SourceRange(end, end), settingsProposal.proposal.insertedText))),
                FileEdit.Create(buildScriptPath, buildScript),
            ))
            val expectedPost = JavaGradleModuleStagedSnapshot.evaluate(before, edit)

            val gate = DiagnosticsGate.authoritative(
                "java-create-gradle-module-staged-snapshot-v1",
                AuthoritativeDiagnosticsProvider { candidate ->
                    val evaluated = when {
                        candidate.hash == before.hash -> candidate
                        JavaGradleModuleStagedSnapshot.isInvalidated(candidate) -> candidate
                        else -> JavaGradleModuleStagedSnapshot.evaluate(before, edit)
                    }
                    AuthoritativeDiagnosticsEvaluation(evaluated, emptyList())
                },
            )
            val plan = PatchPlan(
                operation = "java.createGradleModule",
                snapshotHash = before.hash,
                confidence = 1.0,
                summary = "Add a nested Gradle project to the selected build",
                affectedFiles = edit.affectedFiles(),
                workspaceEdit = edit,
                riskLevel = RiskLevel.MEDIUM,
            )
            val engine = PatchEngine(root)
            val applied = engine.apply(plan, before, ApplyAuthorization.explicit("kotest"), gate)
            (applied is ApplyResult.Applied) shouldBe true
            val transaction = (applied as ApplyResult.Applied).transaction
            changedInputs = true

            Files.readString(root.resolve(settingsPath)) shouldBe "rootProject.name = \"catalog\"\ninclude(\":services:catalog\")\n"
            val actualPost = scanner.scan(root)
            actualPost.modules shouldBe expectedPost.modules
            actualPost.files shouldBe expectedPost.files
            actualPost.auxiliaryFiles shouldBe expectedPost.auxiliaryFiles
            actualPost.classpathEvidence shouldBe expectedPost.classpathEvidence
            actualPost.sourceExtensions shouldBe expectedPost.sourceExtensions
            actualPost.buildModels shouldBe expectedPost.buildModels
            actualPost.hash shouldBe expectedPost.hash
            val journal = TransactionLog(root.resolve(".refactorkit/transactions")).loadRecord(transaction.id)
            journal?.postSnapshotHash shouldBe actualPost.hash

            changedInputs = false
            val rolledBack = engine.rollback(transaction)
            (rolledBack is ApplyResult.Applied) shouldBe true
            Files.readString(root.resolve(settingsPath)) shouldBe settings
            Files.exists(root.resolve(buildScriptPath)) shouldBe false
            scanner.scan(root).hash shouldBe before.hash
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
})

private fun effectiveModel(root: Path): BuildModel = BuildModel(
    providerId = "gradle-effective-v1",
    status = BuildModelStatus.AVAILABLE,
    modules = listOf(BuildModule(
        id = ":",
        name = "root",
        root = root,
        sourceSets = listOf(BuildSourceSet(
            id = "main",
            kind = SourceSetKind.MAIN,
            sourceRoots = listOf(Path.of("src/main/java")),
            outputDirectories = listOf(Path.of("build/classes/java/main")),
            attributes = mapOf("java.sourceLevel" to "17", "visibility" to "main"),
        )),
        attributes = mapOf(
            "java.buildSystem" to "gradle",
            "java.buildModel.status" to "available",
            "java.sourceLevel" to "17",
            "gradle.buildRoot" to ".",
            "gradle.projectPath" to ":",
        ),
    )),
    attributes = mapOf(
        "ecosystem" to "gradle",
        "strategy" to "gradle-evaluated-v1",
        "gradle.workspaceRoot" to root.toAbsolutePath().normalize().toString(),
        "gradle.version" to "9.6.1",
        "java.safeDelete.textReferenceScan.status" to "complete",
    ),
)
